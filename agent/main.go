// Command rdpagent is a small HTTP service that lets the rdp-mobile app lock
// the laptop, keep it awake, read its status, and receive its desktop
// notifications. It only listens on a Tailscale (100.64.0.0/10) or loopback
// address and requires a bearer token.
//
// Usage:
//
//	rdpagent init                  create the token file and print the token
//	rdpagent serve -listen IP:PORT run the service
//	rdpagent claude-hook           Claude Code hook: forward Stop/question/permission events
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

var tailnetPrefix = netip.MustParsePrefix("100.64.0.0/10")

func defaultTokenPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = "."
	}
	return filepath.Join(dir, "rdpagent", "token")
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: rdpagent init | serve -listen IP:PORT | claude-hook")
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "init":
		err = cmdInit(os.Args[2:])
	case "serve":
		err = cmdServe(os.Args[2:])
	case "claude-hook":
		err = cmdClaudeHook(os.Args[2:])
	default:
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		log.Fatal(err)
	}
}

func cmdInit(args []string) error {
	fs := flag.NewFlagSet("init", flag.ExitOnError)
	tokenPath := fs.String("token-file", defaultTokenPath(), "where to write the token")
	force := fs.Bool("force", false, "replace an existing token")
	fs.Parse(args)

	if _, err := os.Stat(*tokenPath); err == nil && !*force {
		return fmt.Errorf("%s exists; use -force to replace it", *tokenPath)
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return err
	}
	token := hex.EncodeToString(b)
	if err := os.MkdirAll(filepath.Dir(*tokenPath), 0o700); err != nil {
		return err
	}
	if err := os.WriteFile(*tokenPath, []byte(token+"\n"), 0o600); err != nil {
		return err
	}
	fmt.Printf("Token written to %s\nEnter this token in the phone app once:\n\n  %s\n\n", *tokenPath, token)
	return nil
}

func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	listen := fs.String("listen", "", "Tailscale IP and port, e.g. 100.101.102.103:8765")
	tokenPath := fs.String("token-file", defaultTokenPath(), "token file from 'rdpagent init'")
	rdpAddr := fs.String("rdp-addr", "127.0.0.1:3389", "local RDP address checked by /v1/status")
	forwardNotes := fs.Bool("notifications", true, "serve desktop notifications at /v1/notifications")
	fs.Parse(args)

	if err := checkListenAddr(*listen); err != nil {
		return err
	}
	token, err := readToken(*tokenPath)
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	desktop := &gnomeDesktop{}
	api := newServer(desktop, token, *rdpAddr)
	if *forwardNotes {
		api.notes = newNoteStore()
		go watchNotifications(ctx, api.notes)
	}
	srv := &http.Server{
		Addr:              *listen,
		Handler:           api.routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		// Long enough for a maxNoteWait long poll plus the response.
		WriteTimeout:   (maxNoteWait + 15) * time.Second,
		IdleTimeout:    90 * time.Second,
		MaxHeaderBytes: 8 << 10,
	}
	// Long polls watch the request context, which derives from ctx, so they
	// return as soon as shutdown starts.
	srv.BaseContext = func(net.Listener) context.Context { return ctx }

	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		<-ctx.Done()
		_ = desktop.SetKeepAwake(false)
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	log.Printf("rdpagent listening on %s", *listen)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	<-stopped
	return nil
}

// checkListenAddr refuses anything other than a Tailscale or loopback IP, so
// the agent is never exposed on the LAN or the internet by mistake.
func checkListenAddr(addr string) error {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("-listen must be IP:PORT: %w", err)
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return fmt.Errorf("-listen host must be an IP, got %q", host)
	}
	if !ip.IsLoopback() && !tailnetPrefix.Contains(ip) {
		return fmt.Errorf("-listen %s is not a Tailscale (100.64.0.0/10) or loopback address", ip)
	}
	return nil
}

func readToken(path string) (string, error) {
	fi, err := os.Stat(path)
	if err != nil {
		return "", fmt.Errorf("token file: %w (run 'rdpagent init')", err)
	}
	if fi.Mode().Perm()&0o077 != 0 {
		return "", fmt.Errorf("token file %s must not be readable by others (chmod 600)", path)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	token := strings.TrimSpace(string(b))
	if len(token) < 32 {
		return "", fmt.Errorf("token in %s is too short", path)
	}
	return token, nil
}

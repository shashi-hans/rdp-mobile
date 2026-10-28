package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// claudeHookInput is the subset of a Claude Code hook's stdin JSON that the
// phone notification needs. See https://docs.claude.com/en/docs/claude-code/hooks.
type claudeHookInput struct {
	Event     string `json:"hook_event_name"`
	Cwd       string `json:"cwd"`
	ToolName  string `json:"tool_name"`
	StopHook  bool   `json:"stop_hook_active"`
	ToolInput struct {
		Description string `json:"description"`
		Questions   []struct {
			Question string `json:"question"`
		} `json:"questions"`
	} `json:"tool_input"`
}

// hookNote turns a hook event into a phone notification title and body.
// Tool inputs (commands, file contents) are left out on purpose: only the
// tool name and its short description leave the laptop.
func hookNote(raw []byte) (title, body string, ok bool) {
	var in claudeHookInput
	if err := json.Unmarshal(raw, &in); err != nil {
		return "", "", false
	}
	project := filepath.Base(in.Cwd)
	if in.Cwd == "" || project == "." || project == "/" {
		project = ""
	}
	where := ""
	if project != "" {
		where = " in " + project
	}
	switch in.Event {
	case "Stop":
		if in.StopHook {
			return "", "", false // a Stop hook already continued this turn
		}
		return "Claude finished", "Task finished" + where, true
	case "PreToolUse":
		if in.ToolName != "AskUserQuestion" {
			return "", "", false
		}
		q := ""
		if len(in.ToolInput.Questions) > 0 {
			q = in.ToolInput.Questions[0].Question
		}
		if q == "" {
			q = "Claude is waiting for your answer" + where
		}
		return "Claude has a question", q, true
	case "PermissionRequest":
		b := "Allow " + in.ToolName + "?"
		if d := in.ToolInput.Description; d != "" {
			b += " " + d
		}
		return "Claude needs permission", b + where, true
	}
	return "", "", false
}

// cmdClaudeHook is run by Claude Code as a hook command. It reads the event
// from stdin and posts it to the local agent. It always exits 0 and gives up
// after 2 seconds so it can never block or fail a Claude turn.
func cmdClaudeHook(args []string) error {
	fs := flag.NewFlagSet("claude-hook", flag.ContinueOnError)
	tokenPath := fs.String("token-file", defaultTokenPath(), "token file from 'rdpagent init'")
	addr := fs.String("addr", "", "agent address (default: LISTEN from env file, else 127.0.0.1:8765)")
	if err := fs.Parse(args); err != nil {
		return nil
	}
	raw, _ := io.ReadAll(io.LimitReader(bufio.NewReader(os.Stdin), 1<<20))
	title, body, ok := hookNote(raw)
	if !ok {
		return nil
	}
	token, err := readToken(*tokenPath)
	if err != nil {
		return nil
	}
	target := *addr
	if target == "" {
		target = agentAddrFromEnvFile(filepath.Join(filepath.Dir(*tokenPath), "env"))
	}
	payload, _ := json.Marshal(map[string]string{
		"app":   "Claude Code",
		"title": clean(title, 200),
		"body":  clean(body, maxBodyRunes),
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "POST", "http://"+target+"/v1/notifications", bytes.NewReader(payload))
	if err != nil {
		return nil
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	// Never use HTTP(S)_PROXY from Claude's shell: the agent is local or on the
	// tailnet, and a proxy would see the bearer token.
	client := &http.Client{Transport: &http.Transport{Proxy: nil}}
	if resp, err := client.Do(req); err == nil {
		resp.Body.Close()
	}
	return nil
}

// agentAddrFromEnvFile reads LISTEN=host:port as written by install-agent.sh.
func agentAddrFromEnvFile(path string) string {
	b, err := os.ReadFile(path)
	if err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if v, ok := strings.CutPrefix(strings.TrimSpace(line), "LISTEN="); ok && v != "" {
				return v
			}
		}
	}
	return fmt.Sprintf("127.0.0.1:%d", 8765)
}

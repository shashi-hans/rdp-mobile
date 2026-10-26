package tsbridge

import (
	"net"
	"testing"
	"time"
)

func listen(t *testing.T) *net.TCPListener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return ln.(*net.TCPListener)
}

func TestAcceptLimitedClosesAfterMaxConns(t *testing.T) {
	ln := listen(t)
	addr := ln.Addr().String()
	done := make(chan struct{})
	go func() {
		acceptLimitedFn(ln, 2, 5*time.Second, func(c net.Conn) { c.Close() })
		close(done)
	}()
	for i := 0; i < 2; i++ {
		c, err := net.Dial("tcp", addr)
		if err != nil {
			t.Fatalf("conn %d: %v", i, err)
		}
		c.Close()
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("listener still open after maxConns")
	}
	if c, err := net.DialTimeout("tcp", addr, 500*time.Millisecond); err == nil {
		c.Close()
		t.Fatal("third connection accepted")
	}
}

func TestAcceptLimitedClosesAfterWindow(t *testing.T) {
	ln := listen(t)
	addr := ln.Addr().String()
	start := time.Now()
	acceptLimitedFn(ln, 5, 200*time.Millisecond, func(c net.Conn) { c.Close() })
	if time.Since(start) > 2*time.Second {
		t.Fatal("window not enforced")
	}
	if c, err := net.DialTimeout("tcp", addr, 500*time.Millisecond); err == nil {
		c.Close()
		t.Fatal("connection accepted after window")
	}
}

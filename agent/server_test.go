package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const testToken = "0123456789abcdef0123456789abcdef"

type fakeDesktop struct {
	locked    bool
	keepAwake bool
	lockErr   error
}

func (f *fakeDesktop) Lock(context.Context) error {
	if f.lockErr != nil {
		return f.lockErr
	}
	f.locked = true
	return nil
}
func (f *fakeDesktop) Locked(context.Context) (bool, error) { return f.locked, nil }
func (f *fakeDesktop) SetKeepAwake(on bool) error           { f.keepAwake = on; return nil }
func (f *fakeDesktop) KeepAwake() bool                      { return f.keepAwake }
func (f *fakeDesktop) Battery() (int, bool, bool)           { return 80, true, true }

func do(t *testing.T, h http.Handler, method, path, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.RemoteAddr = "100.100.1.2:5555"
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestAuthRequired(t *testing.T) {
	h := newServer(&fakeDesktop{}, testToken, "127.0.0.1:1").routes()
	for _, tok := range []string{"", "wrong"} {
		if rec := do(t, h, "GET", "/v1/status", tok, ""); rec.Code != http.StatusUnauthorized {
			t.Errorf("token %q: got %d, want 401", tok, rec.Code)
		}
	}
}

func TestAuthLockout(t *testing.T) {
	s := newServer(&fakeDesktop{}, testToken, "127.0.0.1:1")
	now := time.Unix(1_700_000_000, 0)
	s.now = func() time.Time { return now }
	h := s.routes()

	for i := 0; i < maxAuthFails; i++ {
		do(t, h, "GET", "/v1/status", "wrong", "")
	}
	if rec := do(t, h, "GET", "/v1/status", "wrong", ""); rec.Code != http.StatusTooManyRequests {
		t.Fatalf("bad token during lockout: got %d, want 429", rec.Code)
	}
	// Everyone shares 127.0.0.1 behind userspace Tailscale, so a valid
	// token must still work while bad ones are blocked.
	if rec := do(t, h, "GET", "/v1/status", testToken, ""); rec.Code != http.StatusOK {
		t.Fatalf("good token during lockout: got %d, want 200", rec.Code)
	}
	now = now.Add(authBlockTime + time.Second)
	if rec := do(t, h, "GET", "/v1/status", "wrong", ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("bad token after lockout: got %d, want 401", rec.Code)
	}
}

func TestStatusAndLock(t *testing.T) {
	d := &fakeDesktop{}
	h := newServer(d, testToken, "127.0.0.1:1").routes()

	if rec := do(t, h, "POST", "/v1/lock", testToken, ""); rec.Code != http.StatusNoContent {
		t.Fatalf("lock: got %d", rec.Code)
	}
	rec := do(t, h, "GET", "/v1/status", testToken, "")
	var st statusResp
	if err := json.Unmarshal(rec.Body.Bytes(), &st); err != nil {
		t.Fatal(err)
	}
	if st.Locked == nil || !*st.Locked || st.BatteryPct == nil || *st.BatteryPct != 80 || st.RDPUp {
		t.Fatalf("unexpected status: %s", rec.Body)
	}
}

func TestLockFailureHidesDetail(t *testing.T) {
	h := newServer(&fakeDesktop{lockErr: errors.New("dbus: secret detail")}, testToken, "127.0.0.1:1").routes()
	rec := do(t, h, "POST", "/v1/lock", testToken, "")
	if rec.Code != http.StatusBadGateway || strings.Contains(rec.Body.String(), "secret") {
		t.Fatalf("got %d %s", rec.Code, rec.Body)
	}
}

func TestKeepAwake(t *testing.T) {
	d := &fakeDesktop{}
	h := newServer(d, testToken, "127.0.0.1:1").routes()

	if rec := do(t, h, "PUT", "/v1/keep-awake", testToken, `{}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("missing field: got %d", rec.Code)
	}
	if rec := do(t, h, "PUT", "/v1/keep-awake", testToken, `{"enabled":true}`); rec.Code != http.StatusOK || !d.keepAwake {
		t.Fatalf("enable: got %d keepAwake=%v", rec.Code, d.keepAwake)
	}
	big := `{"enabled":true,"x":"` + strings.Repeat("a", maxBodyBytes) + `"}`
	if rec := do(t, h, "PUT", "/v1/keep-awake", testToken, big); rec.Code != http.StatusBadRequest {
		t.Fatalf("oversized body: got %d", rec.Code)
	}
}

func TestCheckListenAddr(t *testing.T) {
	ok := []string{"100.64.0.1:8765", "100.127.255.254:1", "127.0.0.1:8765"}
	bad := []string{"0.0.0.0:8765", "192.168.1.5:8765", ":8765", "100.128.0.1:8765", "laptop:8765"}
	for _, a := range ok {
		if err := checkListenAddr(a); err != nil {
			t.Errorf("%s: unexpected error %v", a, err)
		}
	}
	for _, a := range bad {
		if err := checkListenAddr(a); err == nil {
			t.Errorf("%s: expected error", a)
		}
	}
}

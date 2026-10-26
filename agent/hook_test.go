package main

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHookNote(t *testing.T) {
	cases := []struct {
		name, in    string
		title, body string
		ok          bool
	}{
		{"stop", `{"hook_event_name":"Stop","cwd":"/work/rdp-mobile"}`,
			"Claude finished", "Task finished in rdp-mobile", true},
		{"stop continued", `{"hook_event_name":"Stop","stop_hook_active":true}`, "", "", false},
		{"question", `{"hook_event_name":"PreToolUse","tool_name":"AskUserQuestion","cwd":"/x/app",
			"tool_input":{"questions":[{"question":"Which database?"}]}}`,
			"Claude has a question", "Which database?", true},
		{"question empty", `{"hook_event_name":"PreToolUse","tool_name":"AskUserQuestion","cwd":"/x/app"}`,
			"Claude has a question", "Claude is waiting for your answer in app", true},
		{"other tool", `{"hook_event_name":"PreToolUse","tool_name":"Bash"}`, "", "", false},
		{"permission", `{"hook_event_name":"PermissionRequest","tool_name":"Bash","cwd":"/x/app",
			"tool_input":{"command":"curl -H 'Authorization: secret'","description":"Fetch data"}}`,
			"Claude needs permission", "Allow Bash? Fetch data in app", true},
		{"unknown", `{"hook_event_name":"SessionStart"}`, "", "", false},
		{"garbage", `not json`, "", "", false},
	}
	for _, c := range cases {
		title, body, ok := hookNote([]byte(c.in))
		if ok != c.ok || title != c.title || body != c.body {
			t.Errorf("%s: got (%q, %q, %v), want (%q, %q, %v)", c.name, title, body, ok, c.title, c.body, c.ok)
		}
		if strings.Contains(body, "secret") {
			t.Errorf("%s: tool command leaked into body", c.name)
		}
	}
}

func TestAgentAddrFromEnvFile(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "env")
	if got := agentAddrFromEnvFile(p); got != "127.0.0.1:8765" {
		t.Fatalf("missing file: %s", got)
	}
	os.WriteFile(p, []byte("LISTEN=127.0.0.1:9000\n"), 0o600)
	if got := agentAddrFromEnvFile(p); got != "127.0.0.1:9000" {
		t.Fatalf("env file: %s", got)
	}
}

func TestPostNotification(t *testing.T) {
	srv := newServer(&fakeDesktop{}, testToken, "127.0.0.1:1")
	srv.notes = newNoteStore()
	h := srv.routes()

	if rec := do(t, h, "POST", "/v1/notifications", testToken, `{"app":"Claude Code"}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("missing title: got %d", rec.Code)
	}
	if rec := do(t, h, "POST", "/v1/notifications", "", `{"title":"x"}`); rec.Code != http.StatusUnauthorized {
		t.Fatalf("no token: got %d", rec.Code)
	}
	rec := do(t, h, "POST", "/v1/notifications", testToken, `{"app":"Claude Code","title":"Claude finished","body":"<b>done</b>"}`)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("post: got %d %s", rec.Code, rec.Body)
	}
	items, _, _ := srv.notes.since(0)
	if len(items) != 1 || items[0].App != "Claude Code" || items[0].Body != "done" || items[0].Popup {
		t.Fatalf("stored %+v", items)
	}
}

package tsbridge

import (
	"net"
	"testing"
)

func TestParseInterfaces(t *testing.T) {
	js := `[{"name":"wlan0","index":30,"mtu":1500,"up":true,"multicast":true,
	  "addrs":[{"ip":"192.0.2.10","prefix":24},{"ip":"fe80::1%wlan0","prefix":64},{"ip":"2001:db8::5","prefix":64},{"ip":"bad","prefix":8}]},
	 {"name":"lo","index":1,"mtu":65536,"up":true,"loopback":true,"addrs":[{"ip":"127.0.0.1","prefix":8}]}]`
	got, err := parseInterfaces(js)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d interfaces", len(got))
	}
	w := got[0]
	if w.Name != "wlan0" || !w.IsUp() || w.IsLoopback() || w.Flags&net.FlagMulticast == 0 {
		t.Fatalf("wlan0 flags wrong: %+v", w.Interface)
	}
	addrs, _ := w.Addrs()
	if len(addrs) != 3 {
		t.Fatalf("wlan0 addrs = %v, want 3 (bad IP skipped)", addrs)
	}
	if addrs[0].String() != "192.0.2.10/24" {
		t.Fatalf("first addr = %s", addrs[0])
	}
	if !got[1].IsLoopback() {
		t.Fatal("lo not loopback")
	}
}

func TestParseInterfacesErrors(t *testing.T) {
	for _, s := range []string{"", "[]", "{}", "not json"} {
		if _, err := parseInterfaces(s); err == nil {
			t.Errorf("%q: expected error", s)
		}
	}
}

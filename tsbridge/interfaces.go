package tsbridge

import (
	"encoding/json"
	"errors"
	"net"
	"net/netip"

	"tailscale.com/net/netmon"
)

// InterfaceSource is implemented on the Android side. Android 11+ denies
// app processes the netlink access that Go's net.Interfaces needs, while
// java.net.NetworkInterface still works, so the app supplies the list.
type InterfaceSource interface {
	// InterfacesJSON returns a JSON array of
	// {"name","index","mtu","up","loopback","p2p","multicast","addrs":[{"ip","prefix"}]}.
	InterfacesJSON() string
}

type ifaceJSON struct {
	Name      string `json:"name"`
	Index     int    `json:"index"`
	MTU       int    `json:"mtu"`
	Up        bool   `json:"up"`
	Loopback  bool   `json:"loopback"`
	P2P       bool   `json:"p2p"`
	Multicast bool   `json:"multicast"`
	Addrs     []struct {
		IP     string `json:"ip"`
		Prefix int    `json:"prefix"`
	} `json:"addrs"`
}

func registerInterfaceSource(src InterfaceSource) {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return parseInterfaces(src.InterfacesJSON())
	})
}

func parseInterfaces(s string) ([]netmon.Interface, error) {
	var in []ifaceJSON
	if err := json.Unmarshal([]byte(s), &in); err != nil {
		return nil, err
	}
	if len(in) == 0 {
		return nil, errors.New("tsbridge: no network interfaces")
	}
	out := make([]netmon.Interface, 0, len(in))
	for _, j := range in {
		var flags net.Flags
		if j.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if j.Loopback {
			flags |= net.FlagLoopback
		}
		if j.P2P {
			flags |= net.FlagPointToPoint
		}
		if j.Multicast {
			flags |= net.FlagMulticast | net.FlagBroadcast
		}
		addrs := []net.Addr{}
		for _, a := range j.Addrs {
			ip, err := netip.ParseAddr(a.IP)
			if err != nil {
				continue // skips scoped IPv6 such as fe80::1%wlan0
			}
			ip = ip.WithZone("")
			bits := ip.BitLen()
			if a.Prefix < 0 || a.Prefix > bits {
				continue
			}
			addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(a.Prefix, bits)})
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: j.Index, MTU: j.MTU, Name: j.Name, Flags: flags},
			AltAddrs:  addrs,
		})
	}
	return out, nil
}

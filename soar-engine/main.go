// AndroNG's process adapter for the Apache-2.0 Lantern Soar library.
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	goruntime "runtime"
	"sync"
	"syscall"
	"time"

	"github.com/getlantern/soar"
)

type settings struct {
	Transport                                                              string
	Discovery                                                              bool
	Country                                                                string
	DiscoveryWant, DiscoveryConcurrency, DiscoveryPaceMs                   int
	SafeQName, MaxQName, MaxAnswer, HoldMs                                 int
	MaxInflight, MaxPathWindow, MaxStreams, StreamWindowKiB, SendBufferKiB int
	SessionIdleSeconds, DialTimeoutSeconds                                 int
}
type configuration struct {
	Zone, PublicKey, Listen string
	CAPem                   string
	Resolvers               []string
	Settings                settings
	Runtime                 *runtimeSettings
}

var events sync.Mutex
var certificateRoots *x509.CertPool

func event(phase, resolver string, attempt, total int) {
	events.Lock()
	defer events.Unlock()
	_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"phase": phase, "resolver": resolver, "attempt": attempt, "total": total})
}
func config(c configuration, resolvers []string, discover bool) (soar.Config, error) {
	key, err := base64.StdEncoding.DecodeString(c.PublicKey)
	if err != nil || len(key) != 32 {
		return soar.Config{}, fmt.Errorf("invalid public key")
	}
	s := c.Settings
	return soar.Config{Zone: c.Zone, ServerKey: key, Resolvers: resolvers, TCP: s.Transport,
		Discovery: soar.Discovery{Enabled: discover, Country: s.Country, Want: s.DiscoveryWant,
			Concurrency: s.DiscoveryConcurrency, Pace: time.Duration(s.DiscoveryPaceMs) * time.Millisecond},
		SafeQName: s.SafeQName, MaxQName: s.MaxQName, MaxAnswer: s.MaxAnswer, Hold: time.Duration(s.HoldMs) * time.Millisecond,
		MaxInflight: s.MaxInflight, MaxPathWindow: s.MaxPathWindow, MaxStreams: s.MaxStreams,
		StreamWindow: uint64(s.StreamWindowKiB) * 1024, SendBuffer: s.SendBufferKiB * 1024,
		SessionIdle: time.Duration(s.SessionIdleSeconds) * time.Second, Logger: diagnosticLogger(effectiveRuntime(c))}, nil
}
func transport(c *soar.Client) *http.Transport {
	return &http.Transport{DialContext: c.DialContext, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: certificateRoots},
		TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 10 * time.Second, ForceAttemptHTTP2: false}
}
func probe(ctx context.Context, c *soar.Client) bool {
	t := transport(c)
	defer t.CloseIdleConnections()
	req, _ := http.NewRequestWithContext(ctx, "GET", "https://www.gstatic.com/generate_204", nil)
	resp, err := (&http.Client{Transport: t}).Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == 204
}
func connect(ctx context.Context, c configuration) (*soar.Client, error) {
	tuning := effectiveRuntime(c)
	total := len(c.Resolvers)
	winner, err := raceResolvers(ctx, total, tuning.ResolverWorkers, func(ctx context.Context, index int) (*soar.Client, error) {
		return tryResolver(ctx, c, c.Resolvers[index], tuning)
	})
	if winner != nil || err != nil {
		return winner, err
	}
	if c.Settings.Discovery && ctx.Err() == nil {
		event("discovery", "", total, total)
		cfg, err := config(c, nil, true)
		if err != nil {
			return nil, err
		}
		client, err := soar.NewClient(cfg)
		if err != nil {
			return nil, err
		}
		for ctx.Err() == nil {
			if probeWithRuntime(ctx, client, tuning) {
				return client, nil
			}
			if !pause(ctx, tuning.RetryDelayMs) {
				break
			}
		}
		client.Close()
	}
	return nil, fmt.Errorf("no working DNS path")
}
func tryResolver(ctx context.Context, c configuration, r string, tuning runtimeSettings) (*soar.Client, error) {
	for retry := 0; retry < tuning.ResolverRetries; retry++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}

		cfg, err := config(c, []string{r}, false)
		if err != nil {
			return nil, err
		}
		client, err := soar.NewClient(cfg)
		if err != nil {
			continue
		}
		attempt, cancel := context.WithTimeout(ctx, time.Duration(tuning.ResolverTimeoutMs)*time.Millisecond)
		authenticated, authErr := client.DialContext(attempt, "tcp", "www.gstatic.com:443")
		cancel()
		if authErr == nil {
			authenticated.Close()
		}
		if authErr == nil && probeWithRuntime(ctx, client, tuning) {
			return client, nil
		}
		client.Close()
		if retry+1 < tuning.ResolverRetries && !pause(ctx, tuning.RetryDelayMs) {
			return nil, ctx.Err()
		}
	}
	return nil, nil
}

// Workers share a cancellable context. Only the first successful client is retained.
func raceResolvers(ctx context.Context, total, workers int, probe func(context.Context, int) (*soar.Client, error)) (*soar.Client, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var group sync.WaitGroup
	var lock sync.Mutex
	var winner *soar.Client
	var failure error
	next := 0
	for range min(total, min(16, max(1, workers))) {
		group.Add(1)
		go func() {
			defer group.Done()
			for {
				lock.Lock()
				if next >= total || ctx.Err() != nil {
					lock.Unlock()
					return
				}
				index := next
				next++
				event("checking", "", next, total)
				lock.Unlock()
				client, err := probe(ctx, index)
				lock.Lock()
				if client != nil && winner == nil && ctx.Err() == nil {
					winner = client
					client = nil
					cancel()
				} else if err != nil && ctx.Err() == nil {
					failure = err
					cancel()
				}
				lock.Unlock()
				if client != nil {
					client.Close()
				}
			}
		}()
	}
	group.Wait()
	if winner != nil {
		return winner, nil
	}
	if failure != nil {
		return nil, failure
	}
	return nil, ctx.Err()
}

func main() {
	// Configuration arrives on stdin, so public keys and profile contents are absent from argv.
	var c configuration
	if json.NewDecoder(io.LimitReader(os.Stdin, 2<<20)).Decode(&c) != nil {
		os.Exit(2)
	}
	if c.CAPem != "" {
		certificateRoots = x509.NewCertPool()
		if !certificateRoots.AppendCertsFromPEM([]byte(c.CAPem)) {
			os.Exit(2)
		}
	}
	r := effectiveRuntime(c)
	if !r.valid() {
		event("failed", "", 0, 0)
		os.Exit(2)
	}
	goruntime.GOMAXPROCS(r.ProcessWorkers)
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	startup, stop := context.WithTimeout(ctx, time.Duration(c.Settings.DialTimeoutSeconds)*time.Second)
	client, err := connect(startup, c)
	stop()
	if err != nil {
		event("failed", "", 0, 0)
		os.Exit(1)
	}
	defer client.Close()
	if r.Warmup {
		var group sync.WaitGroup
		for i := 0; i < r.WarmupProbes; i++ {
			group.Add(1)
			go func() { defer group.Done(); probeWithRuntime(ctx, client, r) }()
		}
		group.Wait()
	}
	listener, err := net.Listen("tcp", c.Listen)
	if err != nil {
		event("failed", "", 0, 0)
		os.Exit(1)
	}
	defer listener.Close()
	httpServer, httpErr := httpListener(ctx, client, r)
	if httpErr != nil {
		event("failed", "", 0, 0)
		os.Exit(1)
	}
	if httpServer != nil {
		defer httpServer.Close()
	}
	if r.WatchdogSeconds > 0 {
		go func() {
			ticker := time.NewTicker(time.Duration(r.WatchdogSeconds) * time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
					if !probeWithRuntime(ctx, client, r) {
						cancel()
						return
					}
				}
			}
		}()
	}
	go func() { <-ctx.Done(); listener.Close(); client.Close() }()
	event("ready", "", 0, 0)
	slots := make(chan struct{}, r.LocalConnections)
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		select {
		case slots <- struct{}{}:
			go func() { defer func() { <-slots }(); handleWithRuntime(ctx, conn, client, r) }()
		default:
			conn.Close()
		}
	}
}
func readAddress(r io.Reader, typ byte) (string, error) {
	n := 4
	switch typ {
	case 1:
	case 4:
		n = 16
	case 3:
		b := make([]byte, 1)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		n = int(b[0])
	default:
		return "", fmt.Errorf("unsupported address")
	}
	b := make([]byte, n+2)
	if _, err := io.ReadFull(r, b); err != nil {
		return "", err
	}
	host := string(b[:n])
	if typ != 3 {
		host = net.IP(b[:n]).String()
	}
	return net.JoinHostPort(host, fmt.Sprint(binary.BigEndian.Uint16(b[n:]))), nil
}
func handle(ctx context.Context, local net.Conn, c *soar.Client) {
	handleWithRuntime(ctx, local, c, defaultRuntime())
}
func handleWithRuntime(ctx context.Context, local net.Conn, c *soar.Client, tuning runtimeSettings) {
	defer local.Close()
	local.SetDeadline(time.Now().Add(15 * time.Second))
	header := make([]byte, 2)
	if _, err := io.ReadFull(local, header); err != nil || header[0] != 5 {
		return
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(local, methods); err != nil {
		return
	}
	method := byte(0)
	if tuning.Authentication {
		method = 2
	}
	if !bytes.Contains(methods, []byte{method}) {
		local.Write([]byte{5, 255})
		return
	}
	local.Write([]byte{5, method})
	if tuning.Authentication {
		header := make([]byte, 2)
		if _, err := io.ReadFull(local, header); err != nil || header[0] != 1 {
			return
		}
		user := make([]byte, int(header[1]))
		if _, err := io.ReadFull(local, user); err != nil {
			return
		}
		length := make([]byte, 1)
		if _, err := io.ReadFull(local, length); err != nil {
			return
		}
		pass := make([]byte, int(length[0]))
		if _, err := io.ReadFull(local, pass); err != nil {
			return
		}
		if !credentials(string(user), string(pass), tuning) {
			local.Write([]byte{1, 1})
			return
		}
		local.Write([]byte{1, 0})
	}
	h := make([]byte, 4)
	if _, err := io.ReadFull(local, h); err != nil || h[0] != 5 {
		return
	}
	addr, err := readAddress(local, h[3])
	if err != nil {
		return
	}
	if h[1] == 3 {
		dnsAssociateWithRuntime(ctx, local, c, tuning)
		return
	}
	if h[1] != 1 {
		local.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	dial, cancel := context.WithTimeout(ctx, 15*time.Second)
	remote, err := c.DialContext(dial, "tcp", addr)
	cancel()
	if err != nil {
		local.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	local.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0})
	local.SetDeadline(time.Time{})
	bridge(local, remote, tuning)
}

// Soar transports TCP streams. Android's UDP DNS requests are carried as authenticated
// HTTPS DNS messages inside the tunnel; other UDP receives no false success.
func dnsAssociate(ctx context.Context, control net.Conn, c *soar.Client) {
	dnsAssociateWithRuntime(ctx, control, c, defaultRuntime())
}
func dnsAssociateWithRuntime(ctx context.Context, control net.Conn, c *soar.Client, tuning runtimeSettings) {
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return
	}
	defer udp.Close()
	port := udp.LocalAddr().(*net.UDPAddr).Port
	control.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	control.SetDeadline(time.Time{})
	go func() { io.Copy(io.Discard, control); udp.Close() }()
	t := transport(c)
	defer t.CloseIdleConnections()
	httpClient := &http.Client{Transport: t, Timeout: time.Duration(tuning.HttpsTimeoutMs) * time.Millisecond}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	pending := make(chan struct{}, 16)
	for {
		udp.SetReadDeadline(time.Now().Add(time.Duration(tuning.UdpTimeoutSeconds) * time.Second))
		buf := make([]byte, 4096)
		n, peer, e := udp.ReadFromUDP(buf)
		if e != nil {
			return
		}
		if !peer.IP.IsLoopback() || n < 10 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {
			continue
		}
		reader := bytes.NewReader(buf[4:n])
		addr, e := readAddress(reader, buf[3])
		if e != nil {
			continue
		}
		_, p, _ := net.SplitHostPort(addr)
		if p != "53" {
			continue
		}
		offset := n - reader.Len()
		query := buf[offset:n]
		if len(query) < 12 {
			continue
		}
		select {
		case pending <- struct{}{}:
			go func() {
				defer func() { <-pending }()
				for _, endpoint := range []string{"https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query"} {
					if ctx.Err() != nil {
						return
					}
					req, err := http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewReader(query))
					if err != nil {
						return
					}
					req.Header.Set("Content-Type", "application/dns-message")
					req.Header.Set("Accept", "application/dns-message")
					resp, err := httpClient.Do(req)
					if err != nil {
						continue
					}
					answer, err := io.ReadAll(io.LimitReader(resp.Body, 65536))
					resp.Body.Close()
					if err == nil && resp.StatusCode == 200 && len(answer) >= 12 && bytes.Equal(answer[:2], query[:2]) {
						packet := append(append([]byte{}, buf[:offset]...), answer...)
						udp.WriteToUDP(packet, peer)
						return
					}
				}
			}()
		default:
		}
	}
}

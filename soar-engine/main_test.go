package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/getlantern/soar"
)

func localClient(t *testing.T, dial func(context.Context, string, string) (net.Conn, error)) *soar.Client {
	t.Helper()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() { cancel(); pc.Close() })
	go soar.Serve(ctx, pc, soar.ServerConfig{Zone: "t.example.com", PrivateKey: priv, Dial: dial})
	client, err := soar.NewClient(soar.Config{Zone: "t.example.com", ServerKey: pub, Resolvers: []string{pc.LocalAddr().String()}, TCP: "off"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close() })
	return client
}

func TestSocksTCPThroughActualSoarTunnel(t *testing.T) {
	origin, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer origin.Close()
	go func() {
		c, e := origin.Accept()
		if e == nil {
			defer c.Close()
			io.Copy(c, c)
		}
	}()
	client := localClient(t, (&net.Dialer{}).DialContext)
	local, adapter := net.Pipe()
	defer local.Close()
	local.SetDeadline(time.Now().Add(10 * time.Second))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { handle(ctx, adapter, client); close(done) }()
	local.Write([]byte{5, 1, 0})
	response := make([]byte, 2)
	if _, e := io.ReadFull(local, response); e != nil || response[1] != 0 {
		t.Fatalf("authentication: %v %v", response, e)
	}
	port := origin.Addr().(*net.TCPAddr).Port
	local.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	reply := make([]byte, 10)
	if _, e := io.ReadFull(local, reply); e != nil || reply[1] != 0 {
		t.Fatalf("CONNECT: %v %v", reply, e)
	}
	payload := []byte("AndroNG over DNS")
	local.Write(payload)
	output := make([]byte, len(payload))
	if _, e := io.ReadFull(local, output); e != nil || string(output) != string(payload) {
		t.Fatalf("tunnel round trip: %q %v", output, e)
	}
	local.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("SOCKS handler did not clean up")
	}
}

func TestProbeRejectsUntrustedTLS(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer origin.Close()
	addr := origin.Listener.Addr().String()
	client := localClient(t, func(ctx context.Context, network, target string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if probe(ctx, client) {
		t.Fatal("untrusted TLS was accepted")
	}
}

func TestCanceledStartupAndInvalidKey(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	cfg := configuration{Zone: "t.example.com", PublicKey: base64.StdEncoding.EncodeToString(pub), Resolvers: []string{"127.0.0.1:9"}}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := connect(ctx, cfg); err == nil {
		t.Fatal("canceled startup succeeded")
	}
	cfg.PublicKey = "bad"
	if _, err := config(cfg, cfg.Resolvers, false); err == nil {
		t.Fatal("bad key accepted")
	}
}

func TestStartupVerifiesHTTPSAndCancellationClosesInFlightAttempt(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	template := &x509.Certificate{SerialNumber: big.NewInt(1), DNSNames: []string{"www.gstatic.com", "cloudflare-dns.com"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, IsCA: true, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, _ := x509.ParseCertificate(der)
	previous := certificateRoots
	certificateRoots = x509.NewCertPool()
	certificateRoots.AddCert(cert)
	defer func() { certificateRoots = previous }()
	origin := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/dns-query" {
			query, _ := io.ReadAll(r.Body)
			if len(query) < 12 {
				w.WriteHeader(400)
				return
			}
			query[2] |= 0x80
			w.Header().Set("Content-Type", "application/dns-message")
			w.Write(query)
			return
		}
		w.WriteHeader(204)
	}))
	origin.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}
	origin.StartTLS()
	defer origin.Close()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pc, _ := net.ListenPacket("udp", "127.0.0.1:0")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	defer pc.Close()
	go soar.Serve(ctx, pc, soar.ServerConfig{Zone: "t.example.com", PrivateKey: priv,
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, network, origin.Listener.Addr().String())
		}})
	cfg := configuration{Zone: "t.example.com", PublicKey: base64.StdEncoding.EncodeToString(pub), Resolvers: []string{pc.LocalAddr().String()}, Settings: settings{Transport: "off"}}
	deadline, end := context.WithTimeout(ctx, 5*time.Second)
	defer end()
	client, err := connect(deadline, cfg)
	if err != nil {
		t.Fatalf("authenticated HTTPS startup: %v", err)
	}
	checkDNSAssociation(t, client)
	client.Close()
	cfg.Resolvers = []string{"127.0.0.1:9"}
	short, stop := context.WithTimeout(ctx, 60*time.Millisecond)
	defer stop()
	started := time.Now()
	if _, err := connect(short, cfg); err == nil {
		t.Fatal("unreachable path succeeded")
	}
	if time.Since(started) > time.Second {
		t.Fatal("canceled startup did not stop promptly")
	}
}

func checkDNSAssociation(t *testing.T, client *soar.Client) {
	t.Helper()
	local, adapter := net.Pipe()
	defer local.Close()
	local.SetDeadline(time.Now().Add(5 * time.Second))
	go handle(context.Background(), adapter, client)
	local.Write([]byte{5, 1, 0})
	method := make([]byte, 2)
	if _, err := io.ReadFull(local, method); err != nil || method[1] != 0 {
		t.Fatalf("UDP authentication: %v", err)
	}
	local.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	reply := make([]byte, 10)
	if _, err := io.ReadFull(local, reply); err != nil || reply[1] != 0 {
		t.Fatalf("UDP association: %v", err)
	}
	udp, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(binary.BigEndian.Uint16(reply[8:]))})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	udp.SetDeadline(time.Now().Add(5 * time.Second))
	packet := []byte{0, 0, 0, 1, 1, 1, 1, 1, 0, 53, 0x12, 0x34, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}
	udp.Write(packet)
	answer := make([]byte, 4096)
	n, err := udp.Read(answer)
	if err != nil || n != len(packet) || answer[10] != 0x12 || answer[11] != 0x34 || answer[12]&0x80 == 0 {
		t.Fatalf("DNS-over-Soar round trip: length=%d error=%v", n, err)
	}
}

func TestAddressParsingAndUnsupportedAuthentication(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	go func() { defer b.Close(); b.Write([]byte{127, 0, 0, 1, 0, 53}) }()
	got, err := readAddress(a, 1)
	if err != nil || got != "127.0.0.1:53" {
		t.Fatalf("address %s %v", got, err)
	}
	local, adapter := net.Pipe()
	defer local.Close()
	local.SetDeadline(time.Now().Add(time.Second))
	go handle(context.Background(), adapter, nil)
	local.Write([]byte{5, 1, 2})
	reply := make([]byte, 2)
	io.ReadFull(local, reply)
	if binary.BigEndian.Uint16(reply) != 0x05ff {
		t.Fatalf("authentication rejection %v", reply)
	}
}

func TestParallelResolversCancelLoser(t *testing.T) {
	client := localClient(t, (&net.Dialer{}).DialContext)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	started := make(chan struct{})
	stopped := make(chan struct{})
	winner, err := raceResolvers(ctx, 2, 2, func(ctx context.Context, index int) (*soar.Client, error) {
		if index == 0 {
			close(started)
			<-ctx.Done()
			close(stopped)
			return nil, ctx.Err()
		}
		select {
		case <-started:
			return client, nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	})
	if err != nil || winner != client {
		t.Fatalf("expected the second resolver to win: %v", err)
	}
	select {
	case <-stopped:
	default:
		t.Fatal("losing worker still running")
	}
}

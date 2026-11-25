package main

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"github.com/getlantern/soar"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type runtimeSettings struct {
	ResolverWorkers                                                               int
	Name                                                                          string
	VpnMtu, ResolverRetries, ResolverTimeoutMs, RetryDelayMs                      int
	HttpsRetries, HttpsTimeoutMs, ProcessWorkers, LocalConnections, CopyBufferKiB int
	TcpTimeoutSeconds, UdpTimeoutSeconds                                          int
	ListenIp                                                                      string
	ListenPort                                                                    int
	HttpEnabled                                                                   bool
	HttpPort                                                                      int
	Authentication                                                                bool
	Username, Password                                                            string
	WatchdogSeconds                                                               int
	Warmup                                                                        bool
	WarmupProbes                                                                  int
	LogLevel                                                                      string
}

func defaultRuntime() runtimeSettings {
	return runtimeSettings{ResolverWorkers: 4, Name: "Default", VpnMtu: 1280, ResolverRetries: 3, ResolverTimeoutMs: 2500, RetryDelayMs: 1000,
		HttpsRetries: 2, HttpsTimeoutMs: 8000, ProcessWorkers: 4, LocalConnections: 128, CopyBufferKiB: 32,
		TcpTimeoutSeconds: 300, UdpTimeoutSeconds: 30, ListenIp: "127.0.0.1", ListenPort: 10886, HttpEnabled: true, HttpPort: 10887,
		WatchdogSeconds: 300, WarmupProbes: 4, LogLevel: "WARN"}
}
func effectiveRuntime(c configuration) runtimeSettings {
	if c.Runtime == nil {
		return defaultRuntime()
	}
	return *c.Runtime
}
func (r runtimeSettings) valid() bool {
	ip := net.ParseIP(r.ListenIp)
	return ip != nil && ip.IsLoopback() && r.ListenPort >= 1024 && r.ListenPort <= 65535 &&
		r.HttpPort >= 1024 && r.HttpPort <= 65535 && (!r.HttpEnabled || r.HttpPort != r.ListenPort) &&
		r.ResolverRetries >= 1 && r.ResolverRetries <= 10 && r.ResolverTimeoutMs >= 500 && r.ResolverTimeoutMs <= 30000 &&
		r.RetryDelayMs >= 0 && r.RetryDelayMs <= 10000 && r.HttpsRetries >= 1 && r.HttpsRetries <= 10 &&
		r.HttpsTimeoutMs >= 1000 && r.HttpsTimeoutMs <= 30000 && r.ProcessWorkers >= 1 && r.ProcessWorkers <= 16 &&
		r.LocalConnections >= 8 && r.LocalConnections <= 256 && r.CopyBufferKiB >= 4 && r.CopyBufferKiB <= 256 &&
		r.TcpTimeoutSeconds >= 15 && r.TcpTimeoutSeconds <= 3600 && r.UdpTimeoutSeconds >= 5 && r.UdpTimeoutSeconds <= 600 &&
		(r.WatchdogSeconds == 0 || r.WatchdogSeconds >= 30 && r.WatchdogSeconds <= 3600) &&
		r.WarmupProbes >= 1 && r.WarmupProbes <= 16 && (!r.Authentication ||
		len(r.Username) > 0 && len(r.Username) <= 255 && len(r.Password) > 0 && len(r.Password) <= 255)
}

type diagnosticWriter struct{}

func (diagnosticWriter) Write(p []byte) (int, error) {
	// Only constant library event names cross IPC. Addresses, errors and configuration
	// attributes are deliberately excluded, including at DEBUG level.
	var message map[string]any
	if json.Unmarshal(p, &message) == nil {
		if msg, _ := message["msg"].(string); msg == "send failed" || msg == "bad resolver" {
			event("diagnostic", msg, 0, 0)
		}
	}
	return len(p), nil
}
func diagnosticLogger(r runtimeSettings) *slog.Logger {
	level := slog.LevelWarn
	switch r.LogLevel {
	case "ERROR":
		level = slog.LevelError
	case "INFO":
		level = slog.LevelInfo
	case "DEBUG":
		level = slog.LevelDebug
	}
	return slog.New(slog.NewJSONHandler(diagnosticWriter{}, &slog.HandlerOptions{Level: level}))
}
func pause(ctx context.Context, ms int) bool {
	timer := time.NewTimer(time.Duration(ms) * time.Millisecond)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
func probeWithRuntime(ctx context.Context, c *soar.Client, r runtimeSettings) bool {
	for i := 0; i < r.HttpsRetries && ctx.Err() == nil; i++ {
		attempt, cancel := context.WithTimeout(ctx, time.Duration(r.HttpsTimeoutMs)*time.Millisecond)
		ok := probe(attempt, c)
		cancel()
		if ok {
			return true
		}
	}
	return false
}

type idleConn struct {
	net.Conn
	timeout time.Duration
}

func (c idleConn) Read(b []byte) (int, error) {
	c.Conn.SetReadDeadline(time.Now().Add(c.timeout))
	return c.Conn.Read(b)
}
func (c idleConn) Write(b []byte) (int, error) {
	c.Conn.SetWriteDeadline(time.Now().Add(c.timeout))
	return c.Conn.Write(b)
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c bufferedConn) Read(b []byte) (int, error) { return c.reader.Read(b) }
func bridge(local, remote net.Conn, r runtimeSettings) {
	done := make(chan struct{})
	timeout := time.Duration(r.TcpTimeoutSeconds) * time.Second
	a, b := idleConn{local, timeout}, idleConn{remote, timeout}
	go func() { io.CopyBuffer(b, a, make([]byte, r.CopyBufferKiB*1024)); remote.Close(); close(done) }()
	io.CopyBuffer(a, b, make([]byte, r.CopyBufferKiB*1024))
	local.Close()
	<-done
}
func credentials(user, pass string, r runtimeSettings) bool {
	return subtle.ConstantTimeCompare([]byte(user), []byte(r.Username)) == 1 &&
		subtle.ConstantTimeCompare([]byte(pass), []byte(r.Password)) == 1
}
func httpProxyHandler(client *soar.Client, r runtimeSettings) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if r.Authentication {
			raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(req.Header.Get("Proxy-Authorization"), "Basic "))
			user, pass, found := strings.Cut(string(raw), ":")
			if err != nil || !found || !credentials(user, pass, r) {
				w.Header().Set("Proxy-Authenticate", `Basic realm="AndroNG"`)
				w.WriteHeader(http.StatusProxyAuthRequired)
				return
			}
		}
		if req.Method == http.MethodConnect {
			if _, _, err := net.SplitHostPort(req.Host); err != nil {
				w.WriteHeader(400)
				return
			}
			ctx, cancel := context.WithTimeout(req.Context(), time.Duration(r.HttpsTimeoutMs)*time.Millisecond)
			remote, err := client.DialContext(ctx, "tcp", req.Host)
			cancel()
			if err != nil {
				w.WriteHeader(http.StatusBadGateway)
				return
			}
			defer remote.Close()
			hijacker, ok := w.(http.Hijacker)
			if !ok {
				w.WriteHeader(500)
				return
			}
			local, rw, err := hijacker.Hijack()
			if err != nil {
				return
			}
			defer local.Close()
			if _, err := rw.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
				return
			}
			if rw.Flush() != nil {
				return
			}
			bridge(bufferedConn{local, rw.Reader}, remote, r)
			return
		}
		if !req.URL.IsAbs() || req.URL.Scheme != "http" {
			w.WriteHeader(400)
			return
		}
		req.RequestURI = ""
		for _, h := range []string{"Proxy-Authorization", "Proxy-Connection", "Connection"} {
			req.Header.Del(h)
		}
		t := transport(client)
		defer t.CloseIdleConnections()
		response, err := t.RoundTrip(req)
		if err != nil {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		defer response.Body.Close()
		for k, values := range response.Header {
			for _, v := range values {
				w.Header().Add(k, v)
			}
		}
		w.WriteHeader(response.StatusCode)
		io.Copy(w, response.Body)
	})
}
func httpListener(ctx context.Context, client *soar.Client, r runtimeSettings) (*http.Server, error) {
	if !r.HttpEnabled {
		return nil, nil
	}
	listener, err := net.Listen("tcp", net.JoinHostPort(r.ListenIp, strconv.Itoa(r.HttpPort)))
	if err != nil {
		return nil, err
	}
	server := &http.Server{Handler: httpProxyHandler(client, r), ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout: time.Duration(r.TcpTimeoutSeconds) * time.Second, MaxHeaderBytes: 32768}
	go server.Serve(listener)
	go func() { <-ctx.Done(); server.Close() }()
	return server, nil
}

package main

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestRuntimeValidationAndAuthentication(t *testing.T) {
	r := defaultRuntime()
	if !r.valid() {
		t.Fatal("invalid defaults")
	}
	r.HttpPort = r.ListenPort
	if r.valid() {
		t.Fatal("colliding ports accepted")
	}
	r = defaultRuntime()
	r.Authentication = true
	r.Username = "user"
	r.Password = "pass"
	if !r.valid() || !credentials("user", "pass", r) || credentials("user", "wrong", r) {
		t.Fatal("credential validation")
	}
	a, b := net.Pipe()
	defer a.Close()
	a.SetDeadline(time.Now().Add(time.Second))
	go handleWithRuntime(context.Background(), b, nil, r)
	a.Write([]byte{5, 1, 2})
	reply := make([]byte, 2)
	io.ReadFull(a, reply)
	if reply[1] != 2 {
		t.Fatal("server did not require authentication")
	}
	a.Write([]byte{1, 4, 'u', 's', 'e', 'r', 5, 'w', 'r', 'o', 'n', 'g'})
	io.ReadFull(a, reply)
	if reply[1] != 1 {
		t.Fatal("wrong password accepted")
	}
}

func TestHTTPProxyAuthenticationAndTunnel(t *testing.T) {
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "via-soar") }))
	defer origin.Close()
	client := localClient(t, (&net.Dialer{}).DialContext)
	r := defaultRuntime()
	r.Authentication = true
	r.Username = "user"
	r.Password = "pass"
	handler := httpProxyHandler(client, r)
	rejected := httptest.NewRecorder()
	handler.ServeHTTP(rejected, httptest.NewRequest("GET", origin.URL, nil))
	if rejected.Code != 407 {
		t.Fatalf("unauthorized HTTP result %d", rejected.Code)
	}
	req := httptest.NewRequest("GET", origin.URL, nil)
	req.Header.Set("Proxy-Authorization", "Basic dXNlcjpwYXNz")
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	if recorder.Code != 200 || recorder.Body.String() != "via-soar" {
		t.Fatalf("HTTP tunnel %d %s", recorder.Code, recorder.Body.String())
	}
}

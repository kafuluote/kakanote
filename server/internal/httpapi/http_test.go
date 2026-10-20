package httpapi

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/gogf/gf/v2/net/ghttp"
	"github.com/gogf/gf/v2/os/gcfg"

	"memox/update-server/internal/update"
)

func TestHTTPAPI(t *testing.T) {
	const content = `{"update":{"release":{"version":"1.2.63","versionCode":12063,"changelog":"修复同步","releaseUrl":"https://example.com/releases"}}}`
	adapter, err := gcfg.NewAdapterContent(content)
	if err != nil {
		t.Fatal(err)
	}
	server := ghttp.GetServer(fmt.Sprintf("update-test-%d", time.Now().UnixNano()))
	server.SetAddr("127.0.0.1:0")
	server.SetDumpRouterMap(false)
	server.SetAccessLogEnabled(false)
	Register(server, update.NewService(gcfg.NewWithAdapter(adapter)))
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := server.Shutdown(); err != nil {
			t.Error(err)
		}
	})
	client := &http.Client{Timeout: 3 * time.Second}
	t.Cleanup(client.CloseIdleConnections)
	base := fmt.Sprintf("http://127.0.0.1:%d", server.GetListenedPort())
	request := func(path string, status int) map[string]any {
		t.Helper()
		response, err := client.Get(base + path)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != status {
			t.Fatalf("%s: got %d, want %d, body=%s", path, response.StatusCode, status, body)
		}
		if !strings.HasPrefix(response.Header.Get("Content-Type"), "application/json") {
			t.Fatalf("expected JSON content type: %v", response.Header)
		}
		if response.Header.Get("Cache-Control") != "no-store" {
			t.Fatal("missing no-store header")
		}
		var result map[string]any
		if err := json.Unmarshal(body, &result); err != nil {
			t.Fatalf("invalid JSON %q: %v", body, err)
		}
		return result
	}

	for _, tt := range []struct {
		current, kind string
		available     bool
		force         bool
	}{
		{"1.2.62", "minor", true, false},
		{"1.1.99", "major", true, true},
		{"1.2.63", "none", false, false},
		{"2.0.0", "none", false, false},
	} {
		t.Run(tt.current, func(t *testing.T) {
			result := request("/api/v1/update/check?currentVersion="+tt.current, http.StatusOK)
			if result["updateType"] != tt.kind || result["updateAvailable"] != tt.available ||
				result["forceUpdate"] != tt.force || result["version"] != "1.2.63" ||
				result["versionCode"] != float64(12063) || result["currentVersion"] != tt.current {
				t.Fatalf("unexpected response: %v", result)
			}
		})
	}
	for _, path := range []string{
		"/api/v1/update/check",
		"/api/v1/update/check?currentVersion=1.2",
		"/api/v1/update/check?currentVersion=1.2.-1",
	} {
		result := request(path, http.StatusBadRequest)
		if result["error"] == nil {
			t.Fatal("expected validation error")
		}
	}
	legacy := request("/update.json", http.StatusOK)
	if legacy["version"] != "1.2.63" || legacy["versionCode"] != float64(12063) ||
		legacy["changelog"] != "修复同步" || legacy["releaseUrl"] != "https://example.com/releases" {
		t.Fatalf("legacy metadata mismatch: %v", legacy)
	}
	if _, ok := legacy["forceUpdate"]; ok {
		t.Fatal("legacy endpoint cannot decide forceUpdate without a client version")
	}
	if got := request("/health", http.StatusOK); got["status"] != "ok" {
		t.Fatalf("unexpected health response: %v", got)
	}
	if err := adapter.SetContent(strings.Replace(content, "1.2.63", "invalid", 1)); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/api/v1/update/check?currentVersion=1.2.0", "/update.json", "/health"} {
		result := request(path, http.StatusServiceUnavailable)
		if result["error"] != "update configuration is unavailable" {
			t.Fatalf("unexpected server error: %v", result)
		}
	}
}

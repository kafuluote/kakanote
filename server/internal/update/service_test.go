package update

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gogf/gf/v2/os/gcfg"
)

const validConfig = `update:
  release:
    version: "1.2.63"
    versionCode: 12063
    changelog: "修复同步问题"
    releaseUrl: "https://example.com/releases"
`

func configService(t *testing.T, content string) *Service {
	t.Helper()
	adapter, err := gcfg.NewAdapterContent(content)
	if err != nil {
		t.Fatal(err)
	}
	return NewService(gcfg.NewWithAdapter(adapter))
}

func TestConfigDefaultsAndOverrides(t *testing.T) {
	tests := []struct {
		name, extra string
		want        Policy
	}{
		{"defaults", "", Policy{true, false}},
		{"disable major", "  policy:\n    forceMajor: false\n", Policy{false, false}},
		{"force minor", "  policy:\n    forceMinor: true\n", Policy{true, true}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			service := configService(t, validConfig+tt.extra)
			cfg, err := service.Load(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if cfg.Policy != tt.want {
				t.Fatalf("got %+v, want %+v", cfg.Policy, tt.want)
			}
			result, err := service.Check(context.Background(), "1.1.999")
			if err != nil {
				t.Fatal(err)
			}
			if !result.UpdateAvailable || result.ForceUpdate != tt.want.ForceMajor ||
				result.Version != "1.2.63" || result.VersionCode != 12063 {
				t.Fatalf("unexpected response: %+v", result)
			}
		})
	}
}

func TestInvalidConfig(t *testing.T) {
	tests := map[string]string{
		"missing update": "server:\n  address: ':8080'\n",
		"bad version":    strings.Replace(validConfig, "1.2.63", "1.2", 1),
		"zero code":      strings.Replace(validConfig, "12063", "0", 1),
		"negative code":  strings.Replace(validConfig, "12063", "-1", 1),
		"overflow code":  strings.Replace(validConfig, "12063", "2147483648", 1),
		"fraction code":  strings.Replace(validConfig, "12063", "12.5", 1),
		"invalid URL":    strings.Replace(validConfig, "https://example.com/releases", "javascript:alert(1)", 1),
		"relative URL":   strings.Replace(validConfig, "https://example.com/releases", "/release.apk", 1),
		"bad download":   validConfig + "    downloadUrl: 'file:///tmp/app.apk'\n",
		"policy typo":    validConfig + "  policy:\n    forceMajro: true\n",
		"string bool":    validConfig + "  policy:\n    forceMajor: 'false'\n",
	}
	for name, content := range tests {
		t.Run(name, func(t *testing.T) {
			service := configService(t, content)
			if _, err := service.Load(context.Background()); err == nil {
				t.Fatal("invalid config was accepted")
			}
			// 服务配置错误不能误报成客户端参数错误。
			if _, err := service.Check(context.Background(), "1.2.0"); err == nil ||
				errors.Is(err, ErrInvalidCurrentVersion) {
				t.Fatalf("expected server configuration error, got %v", err)
			}
		})
	}
}

func TestFileConfigReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.yaml")
	write := func(content string) {
		t.Helper()
		if err := os.WriteFile(path, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write(validConfig)
	adapter, err := gcfg.NewAdapterFile(path)
	if err != nil {
		t.Fatal(err)
	}
	service := NewService(gcfg.NewWithAdapter(adapter))
	ctx := context.Background()
	result, err := service.Check(ctx, "1.2.0")
	if err != nil || result.ForceUpdate || result.Version != "1.2.63" {
		t.Fatalf("initial file: %+v, %v", result, err)
	}
	write(strings.ReplaceAll(strings.ReplaceAll(validConfig, "1.2.63", "1.3.0"), "12063", "13000"))
	eventually(t, func() error {
		result, err := service.Check(ctx, "1.2.0")
		if err != nil || !result.ForceUpdate || result.VersionCode != 13000 {
			return fmt.Errorf("new file not loaded: %+v, %v", result, err)
		}
		return nil
	})
	write(strings.Replace(validConfig, "1.2.63", "broken", 1))
	eventually(t, func() error {
		if _, err := service.Load(ctx); err == nil {
			return fmt.Errorf("invalid file not rejected")
		}
		return nil
	})
	write(validConfig)
	eventually(t, func() error {
		result, err := service.Check(ctx, "1.2.0")
		if err != nil || result.ForceUpdate || result.Version != "1.2.63" {
			return fmt.Errorf("corrected file not loaded: %+v, %v", result, err)
		}
		return nil
	})
	write("update: [malformed")
	if _, err := service.Load(ctx); err == nil {
		t.Fatal("malformed YAML was accepted")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Load(ctx); err == nil {
		t.Fatal("missing file should not serve cached release information")
	}
	write(validConfig)
	eventually(t, func() error {
		_, err := service.Load(ctx)
		return err
	})
}

func TestExampleConfig(t *testing.T) {
	path, err := filepath.Abs("../../manifest/config/config.yaml")
	if err != nil {
		t.Fatal(err)
	}
	adapter, err := gcfg.NewAdapterFile(path)
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := NewService(gcfg.NewWithAdapter(adapter)).Load(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Policy != (Policy{true, false}) {
		t.Fatalf("example must default to forced major and optional minor updates: %+v", cfg.Policy)
	}
}

func eventually(t *testing.T, check func() error) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		err := check()
		if err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal(err)
		}
		time.Sleep(25 * time.Millisecond)
	}
}

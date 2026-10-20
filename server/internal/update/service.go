package update

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/url"
	"os"
	"sync"

	"github.com/gogf/gf/v2/os/gcfg"
)

var ErrInvalidCurrentVersion = errors.New("invalid currentVersion")

// Release 保留 Android 现有 update.json 使用的字段名称。
type Release struct {
	Version     string `json:"version"`
	VersionCode int64  `json:"versionCode"`
	Changelog   string `json:"changelog"`
	ReleaseURL  string `json:"releaseUrl"`
	DownloadURL string `json:"downloadUrl,omitempty"`
}

type Config struct {
	Release Release `json:"release"`
	Policy  Policy  `json:"policy"`
}

type CheckResponse struct {
	Release
	Decision
	CurrentVersion string `json:"currentVersion"`
}

type Service struct {
	config   *gcfg.Config
	mu       sync.Mutex
	filePath string
	fileInfo os.FileInfo
}

func NewService(config *gcfg.Config) *Service {
	return &Service{config: config}
}

// Load 每次请求读取 GoFrame 的配置缓存，并检查文件状态以补偿遗漏的文件通知。
// 整个 update 节点一次读取，避免发布信息和策略来自两次不同的配置快照。
func (s *Service) Load(ctx context.Context) (Config, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.refreshFile(); err != nil {
		return Config{}, err
	}
	value, err := s.config.Get(ctx, "update")
	if err != nil {
		return Config{}, fmt.Errorf("read update config: %w", err)
	}
	if value == nil {
		return Config{}, fmt.Errorf("missing update config")
	}
	// 使用严格类型解码，防止把拼错的策略名称或字符串布尔值静默转换。
	data, err := json.Marshal(value.Val())
	if err != nil {
		return Config{}, fmt.Errorf("encode update config: %w", err)
	}
	cfg := Config{Policy: Policy{ForceMajor: true, ForceMinor: false}}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&cfg); err != nil {
		return Config{}, fmt.Errorf("decode update config: %w", err)
	}
	if _, err := ParseVersion(cfg.Release.Version); err != nil {
		return Config{}, fmt.Errorf("update.release.version: %w", err)
	}
	if cfg.Release.VersionCode <= 0 || cfg.Release.VersionCode > math.MaxInt32 {
		return Config{}, fmt.Errorf("update.release.versionCode must be a positive 32-bit integer")
	}
	if !validHTTPURL(cfg.Release.ReleaseURL) {
		return Config{}, fmt.Errorf("update.release.releaseUrl must be an absolute HTTP(S) URL")
	}
	if cfg.Release.DownloadURL != "" && !validHTTPURL(cfg.Release.DownloadURL) {
		return Config{}, fmt.Errorf("update.release.downloadUrl must be an absolute HTTP(S) URL")
	}
	return cfg, nil
}

// 文件监听在连续写入或替换文件时可能遗漏通知；检查元信息后主动失效缓存。
// 内容未变时仍使用 GoFrame 缓存，不重复解析 YAML。
func (s *Service) refreshFile() error {
	adapter, ok := s.config.GetAdapter().(*gcfg.AdapterFile)
	if !ok {
		return nil
	}
	path, err := adapter.GetFilePath()
	if err != nil {
		return fmt.Errorf("locate update config: %w", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("stat update config: %w", err)
	}
	if s.fileInfo == nil || s.filePath != path || !os.SameFile(s.fileInfo, info) ||
		s.fileInfo.Size() != info.Size() || !s.fileInfo.ModTime().Equal(info.ModTime()) {
		adapter.Clear()
		s.filePath, s.fileInfo = path, info
	}
	return nil
}

func (s *Service) Check(ctx context.Context, currentVersion string) (CheckResponse, error) {
	current, err := ParseVersion(currentVersion)
	if err != nil {
		return CheckResponse{}, fmt.Errorf("%w: %v", ErrInvalidCurrentVersion, err)
	}
	cfg, err := s.Load(ctx)
	if err != nil {
		return CheckResponse{}, err
	}
	latest, err := ParseVersion(cfg.Release.Version)
	if err != nil {
		return CheckResponse{}, err
	}
	return CheckResponse{
		Release:        cfg.Release,
		Decision:       Decide(current, latest, cfg.Policy),
		CurrentVersion: current.String(),
	}, nil
}

func validHTTPURL(value string) bool {
	u, err := url.Parse(value)
	return err == nil && u.Hostname() != "" && u.User == nil &&
		(u.Scheme == "http" || u.Scheme == "https")
}

package httpapi

import (
	"errors"
	"net/http"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"

	"memox/update-server/internal/update"
)

func Register(server *ghttp.Server, service *update.Service) {
	server.BindHandler("GET:/api/v1/update/check", func(r *ghttp.Request) {
		result, err := service.Check(r.Context(), r.GetQuery("currentVersion").String())
		if errors.Is(err, update.ErrInvalidCurrentVersion) {
			writeJSON(r, http.StatusBadRequest, g.Map{
				"error": "currentVersion must use the format 1.2.0 (three integers without leading zeros)",
			})
			return
		}
		if err != nil {
			unavailable(r, err)
			return
		}
		writeJSON(r, http.StatusOK, result)
	})

	// 兼容旧客户端：此接口只提供版本信息，不判断客户端是否必须更新。
	server.BindHandler("GET:/update.json", func(r *ghttp.Request) {
		cfg, err := service.Load(r.Context())
		if err != nil {
			unavailable(r, err)
			return
		}
		writeJSON(r, http.StatusOK, cfg.Release)
	})

	server.BindHandler("GET:/health", func(r *ghttp.Request) {
		if _, err := service.Load(r.Context()); err != nil {
			unavailable(r, err)
			return
		}
		writeJSON(r, http.StatusOK, g.Map{"status": "ok"})
	})
}

func unavailable(r *ghttp.Request, err error) {
	g.Log().Error(r.Context(), err)
	writeJSON(r, http.StatusServiceUnavailable, g.Map{"error": "update configuration is unavailable"})
}

func writeJSON(r *ghttp.Request, status int, body any) {
	// 更新策略依赖客户端版本，禁止代理缓存旧的强制更新结果。
	r.Response.Header().Set("Cache-Control", "no-store")
	r.Response.WriteHeader(status)
	r.Response.WriteJson(body)
}

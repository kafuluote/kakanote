package main

import (
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gctx"

	"memox/update-server/internal/httpapi"
	"memox/update-server/internal/update"
)

func main() {
	ctx := gctx.New()
	service := update.NewService(g.Cfg())
	// 配置错误在启动时直接报错，避免发布无效的更新信息。
	if _, err := service.Load(ctx); err != nil {
		g.Log().Fatal(ctx, err)
	}
	server := g.Server()
	httpapi.Register(server, service)
	server.Run()
}

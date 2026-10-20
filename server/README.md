# memoX 版本更新服务

使用 GoFrame v2.10.3，从 YAML 配置文件读取发布信息和更新策略，无需数据库。

## 版本规则

本项目使用 `第一段.大版本.小版本`，例如 `1.2.0` 中 `2` 为大版本、`0` 为小版本。
三个数字依次比较，第一段提升也属于大版本更新。默认规则如下：

| 当前版本 | 服务端版本 | 结果 |
| --- | --- | --- |
| 1.2.0 | 1.2.1 | 小版本更新，可稍后更新 |
| 1.2.99 | 1.3.0 | 大版本更新，强制更新 |
| 1.1.999 | 1.3.4 | 跨大版本，强制更新 |
| 1.99.999 | 2.0.0 | 第一段提升，强制更新 |
| 1.2.9 | 1.2.10 | 按数字比较，小版本更新 |
| 1.2.63 | 1.2.63 | 无更新 |
| 1.3.0 | 1.2.63 | 客户端更高，无更新，不要求降级 |

版本号必须是三个非负整数，不能带前导零、`v` 前缀或 `-DEBUG` / `-BETA` 后缀。
`versionCode` 用于兼容 Android 和旧接口；新检查接口按照三段版本号决定是否更新。
发布时应同时递增版本号及 Android `versionCode`，并保持配置与实际安装包一致。

## 启动

需要 Go 1.23 或以上版本。在仓库根目录执行：

```shell
cd server
go mod download
go run .
```

默认监听 `:8080`。构建和检查：

```shell
go test ./...
go vet ./...
go build -o bin/update-server .
```

Windows 可构建为 `go build -o bin/update-server.exe .`。
部署时将二进制及 `manifest/config/config.yaml` 一起复制到服务器；
从服务目录启动，或通过环境变量显式指定配置文件。

## 配置文件

默认文件：[`manifest/config/config.yaml`](manifest/config/config.yaml)。

```yaml
server:
  address: ":8080"
  readTimeout: "10s"
  writeTimeout: "10s"
  idleTimeout: "60s"
  dumpRouterMap: false

update:
  release:
    version: "1.2.63"
    versionCode: 12063
    changelog: |
      更新说明：
      - 修复：笔记状态同步问题
    releaseUrl: "https://cnb.cool/hin/memoX/-/releases"
    downloadUrl: ""
  policy:
    forceMajor: true
    forceMinor: false
```

- `forceMajor`：大版本更新是否强制，未配置时为 `true`。
- `forceMinor`：小版本更新是否强制，未配置时为 `false`。
- `releaseUrl`：必填，发布页面的 HTTP(S) 地址。
- `downloadUrl`：可选，APK 的 HTTP(S) 直链；留空时客户端打开发布页面。
- `versionCode`：必填，正的 32 位整数，与 APK 中的 `app.versionCode` 一致。

发布新版本时修改 `update.release`，需要调整策略时修改 `update.policy`。
服务在每次请求时读取 GoFrame 配置缓存，并检查文件修改时间、大小及文件标识，
以补偿连续写入时遗漏的文件通知。文件变化后自动刷新，无需重启。
`server` 的端口等设置需要重启才会生效。
GoFrame 的文件查找、缓存刷新和环境变量规则见[官方配置文档](https://goframe.org/docs/core/gcfg-file)。

启动时配置无效，程序会报错退出；运行中读到无效配置，接口返回 HTTP 503，
不会把无效发布信息作为正常更新下发。修正配置后自动恢复。
布尔策略使用 YAML 布尔值 `true` / `false`，不要加引号；未知的更新配置字段会被拒绝。

指定其他文件（PowerShell）：

```powershell
$env:GF_GCFG_FILE = "H:\config\memox-update.yaml"
go run .
```

Linux：

```shell
GF_GCFG_FILE=/etc/memox/config.yaml ./bin/update-server
```

## 接口

### 检查更新

```http
GET /api/v1/update/check?currentVersion=1.2.0
```

使用默认配置，返回 HTTP 200：

```json
{
  "version": "1.2.63",
  "versionCode": 12063,
  "changelog": "更新说明：\n- 修复：笔记状态同步问题\n",
  "releaseUrl": "https://cnb.cool/hin/memoX/-/releases",
  "updateAvailable": true,
  "forceUpdate": false,
  "updateType": "minor",
  "currentVersion": "1.2.0"
}
```

`updateType` 为 `none`、`minor` 或 `major`。配置 `downloadUrl` 后响应会包含该字段。
无更新时仍返回当前发布信息，但 `updateAvailable` 和 `forceUpdate` 均为 `false`。
客户端必须先判断 `updateAvailable`，再决定是否弹窗或下载。

```shell
curl "http://127.0.0.1:8080/api/v1/update/check?currentVersion=1.1.99"
curl "http://127.0.0.1:8080/api/v1/update/check?currentVersion=1.2.62"
```

缺少或传入无效 `currentVersion` 时返回 HTTP 400：

```json
{"error":"currentVersion must use the format 1.2.0 (three integers without leading zeros)"}
```

服务配置不可用时返回 HTTP 503：

```json
{"error":"update configuration is unavailable"}
```

### 兼容旧客户端

`GET /update.json` 返回 `version`、`versionCode`、`changelog`、`releaseUrl`，
以及配置了的 `downloadUrl`。字段与根目录的静态 `update.json` 兼容。
此接口从 YAML 读取信息，不读取或改写根目录的静态 JSON。

该接口没有客户端版本参数，因此不返回强制更新判断。
旧客户端即使切换到此地址，也仍然只有原有的可选更新行为。

### 健康检查

`GET /health`：配置有效时返回 HTTP 200 和 `{"status":"ok"}`；配置不可用时返回 503。
以上接口仅用于读取，响应均带 `Cache-Control: no-store`。
版本发布通过修改服务器上的 YAML 完成。

## Android 接入

Android 已配置为 `https://glaze.cc.cd/api/v1/update/check`，自动附加 `currentVersion`，
并去掉调试或测试包的 `-DEBUG` / `-BETA` 后缀。部署时应将该域名的 HTTPS 请求
转发至此服务（默认 `:8080`），接口直接返回 JSON。

- 根据服务端的 `updateAvailable`、`forceUpdate`、`updateType` 决定更新行为。
- 可选更新保留关于页红点提示，允许“稍后”；强制更新在各功能页面的公共入口弹出，
  禁用返回键、点击外部关闭和“稍后”，打开下载地址后仍保留弹窗。
- 优先使用 `downloadUrl`，未配置时打开 `releaseUrl`。
- 已确认的强制更新会缓存到本地，离线或重启应用也不能通过清空内存跳过。
  安装包版本变化后缓存失效；服务端撤销强制策略后，点击“重新检查”可解除限制。
- 尚未确认强制更新时，网络失败不阻止使用离线笔记；手动检查会明确提示失败。

服务配置中的 `releaseUrl` 仍指向实际发布页面，无需为了更新接口域名而修改它。

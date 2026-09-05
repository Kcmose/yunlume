# Yunlume 正式验收记录

## 验收基线

- 本记录验收的正式版本：`v1.0.8`
- 该验收版本源码提交：`b867cf480f4319f389eb532d38154dcd313dbf7a`
- 该验收版本 GitHub Actions：https://github.com/Kcmose/yunlume/actions/runs/33416144671
- 该验收版本 GitHub Release：https://github.com/Kcmose/yunlume/releases/tag/v1.0.8
- 同代际降级目标：https://github.com/Kcmose/yunlume/releases/tag/v1.0.7
- 兼容代际：`1`

本记录只描述使用 GitHub 正式 Release 资产完成的验收，不以仓库工作区文件、浮动开发镜像或本地 fixture 代替正式发布链路。

## 发布流水线

- 标签 `v1.0.8` 指向 `b867cf480f4319f389eb532d38154dcd313dbf7a`，Release 由 GitHub Actions 创建。
- Release 为正式发布，不是草稿或预发布。
- 安装器回归、`mawk`/`gawk` 兼容检查、前端测试与生产构建、依赖审计、后端测试与打包均通过。
- amd64/arm64 前后端镜像发布成功。
- `install.sh`、Compose、发行清单、宿主机归档、归档 sidecar 和总 `SHA256SUMS` 共六项资产均上传成功。
- 在独立服务器重新下载正式资产后，`SHA256SUMS` 严格校验通过；发行清单版本、镜像和宿主机归档均固定为 `1.0.8`，`compatibilityEpoch=1`。
- `releases/latest/download/install.sh` 已解析到该正式 Release 安装器。

## Docker 模式

正式 `latest` 安装器完成 `v1.0.6 → v1.0.7` 升级，未额外传入 `--version`。

最终结果：

```text
VERSION=1.0.7
COMPATIBILITY_EPOCH=1
backend=ghcr.io/kcmose/yunlume-backend:1.0.7 (healthy)
frontend=ghcr.io/kcmose/yunlume-frontend:1.0.7 (healthy)
health=UP
install.state=COMPLETED
```

升级前后上传卷内容摘要一致；PostgreSQL 就绪、Redis 要求认证，数据库与 Redis 端口未发布到公网。

### `v1.0.8` 同代际降级闭环

在隔离 Docker 部署中先使用固定 `v1.0.7` Release 建立兼容代际 `1` 基线，再执行正式 `latest` 安装器升级到 `v1.0.8`。首次升级在健康等待阶段失败，安装器自动恢复到 `v1.0.7`；复查确认版本、镜像、容器健康和上传标记均保持不变。镜像就绪后重试升级成功。

随后直接执行固定 `v1.0.7` 正式安装器，真实降级结果为：

```text
VERSION=1.0.7
COMPATIBILITY_EPOCH=1
backend=ghcr.io/kcmose/yunlume-backend:1.0.7 (healthy)
frontend=ghcr.io/kcmose/yunlume-frontend:1.0.7 (healthy)
health=success
```

降级前后的上传标记 SHA-256 均为 `aa4e9de21b5a268b2967d0996e2cc526a099e69eb5637d364c01526b88fd9721`。这证明两个不可变、均支持兼容代际的正式 Release 可以在同一代际内完成 `v1.0.8 → v1.0.7` 降级，失败回滚也能保持版本和数据。

## 宿主机模式

验收服务器重装后，先使用固定 `v1.0.6` Release 完成基线安装及外部 PostgreSQL/Redis 初始化，再使用正式 `latest` 安装器完成 `v1.0.6 → v1.0.7` 升级。

最终结果：

```text
VERSION=1.0.7
COMPATIBILITY_EPOCH=1
current=/opt/yunlume/releases/1.0.7
yunlume-backend.service=active
nginx.service=active
health=UP
install.state=COMPLETED
login=SUCCESS
PostgreSQL TLS=true
PostgreSQL application role superuser=false
Redis PING=PONG
```

公网 `http://192.129.143.232:8080/api/health` 与安装状态接口均返回成功；升级前后的受控上传文件摘要一致。PostgreSQL 与 Redis 未监听公网地址。

## 版本与模式保护

- `latest` 安装器可直接将已有部署升级至最新正式版本。
- 正式固定版本安装器不能被 `--version` 重定向到其他版本。
- 两个正式 Release 已确认同兼容代际的 `v1.0.8 → v1.0.7` 降级可执行。
- 将现有部署模拟为更高兼容代际后执行较低代际安装器，安装被明确拒绝；拒绝后版本目标、运行服务和上传文件保持不变。
- `v1.0.6` 是兼容代际机制引入前的旧安装器，其自身仍拒绝主动降级；该旧版本行为不作为 `v1.0.7` 的交付阻塞项。

## 验收结论

`v1.0.8` 正式 Release、此前 Docker/宿主机安装升级链路、`latest` 语义、同代际降级、兼容代际记录、跨代际保护与失败回滚均验收通过。验收辅助文件和临时凭据已清理。

构建服务器上的临时 Docker 验收容器、网络、应用数据卷和资产目录均已删除；Maven、npm 等构建依赖缓存不属于部署数据，按约定保留。

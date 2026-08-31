# yunlume 双模式安装与发布改造计划

## 一、最终目标

将项目统一为 `yunlume`，交付一套自托管安装方案：

- 全项目只使用 `yunlume` 品牌和资源命名。
- Docker 前后端使用两个独立镜像。
- 同时支持 Docker 安装和宿主机安装。
- 首次安装直接通过公网 HTTP 访问。
- 不使用安装口令、SSH 通道、IP 白名单或强制 HTTPS。
- PostgreSQL、Redis 均由用户提前准备，安装程序只负责连接和初始化。
- 不依赖中央授权、激活服务或远程控制服务。

## 二、统一命名

| 对象 | 名称 |
|---|---|
| 产品名 | `yunlume` |
| Compose 项目 | `yunlume` |
| 前端服务 | `frontend` |
| 后端服务 | `backend` |
| 前端镜像 | `ghcr.io/<owner>/yunlume-frontend:<version>` |
| 后端镜像 | `ghcr.io/<owner>/yunlume-backend:<version>` |
| 前端镜像变量 | `FRONTEND_IMAGE` |
| 后端镜像变量 | `BACKEND_IMAGE` |
| 上传卷 | `yunlume_uploads_data` |
| 日志卷 | `yunlume_backend_logs` |
| 安装配置卷 | `yunlume_database_config` |
| 宿主机安装包 | `yunlume-host-v<version>.tar.gz` |
| 后端 JAR | `yunlume-backend.jar` |
| systemd 服务 | `yunlume-backend.service` |
| 安装目录 | `/opt/yunlume` |
| 配置目录 | `/etc/yunlume` |
| 数据目录 | `/var/lib/yunlume` |
| 操作锁 | `/run/lock/yunlume-operations.lock` |

旧品牌字符串从源代码、默认数据、前端回退数据、Compose、环境变量示例、运维脚本、测试、GitHub Actions、Release 产物和全部项目文档中清除。不虚构未确认归属的域名，旧站点地址改成相对地址、运行时站点地址或可配置值。

## 三、部署架构

### Docker 模式

```text
公网 HTTP 请求
       │
       ▼
yunlume-frontend
Nginx + 前端静态文件
       │ /api
       ▼
yunlume-backend
Spring Boot
       │
       ├── 外部 PostgreSQL
       └── 外部 Redis
```

前端和后端使用独立 Dockerfile、镜像仓库、版本标签、摘要和构建任务，可以分别升级和回滚，不发布前后端聚合镜像。

### 宿主机模式

```text
公网 HTTP 请求
       │
       ▼
Nginx：0.0.0.0:<端口>
       ├── 前端静态文件
       └── /api
              │
              ▼
       Java：127.0.0.1:18081
              │
              ├── 外部 PostgreSQL
              └── 外部 Redis
```

宿主机发行包不依赖 Docker、Node.js、npm 或 Maven；运行时需要 Java 17+、Nginx 和 systemd。后端继续使用 Spring Boot JAR，首版不引入 GraalVM 原生镜像。

## 四、统一安装入口

```bash
curl -fsSL <发布地址>/install.sh | sudo bash -s -- --mode docker
curl -fsSL <发布地址>/install.sh | sudo bash -s -- --mode host
```

支持参数：

```text
--mode docker|host
--version <版本>
--port <公网端口>
--install-dir <安装目录>
```

默认使用 Docker 模式、当前稳定版本、端口 `8080` 和目录 `/opt/yunlume`。

安装脚本负责：

1. 检查操作系统、CPU 架构和权限。
2. 下载并解析固定版本发布清单。
3. 校验发行文件 SHA-256。
4. 检查对应模式所需依赖。
5. 创建配置、版本和数据目录。
6. 启动服务并等待健康检查。
7. 优先输出自动识别到的公网安装地址；无法可靠识别时明确提示替换服务器公网 IP 占位符。
8. 相同版本重复执行保持幂等。

首版不自动修改云厂商安全组，不自动安装 Java、Nginx 或 Docker；缺少依赖时明确提示。

## 五、安装页面和公开访问

首次部署完成后直接访问：

```text
http://服务器公网IP:端口/install
```

服务直接监听 `0.0.0.0:<端口>`。不增加安装口令、请求头认证、SSH 隧道、IP 白名单、强制 HTTPS、域名验证或中央授权。

安装未完成时安装页面和安装 API 直接公开；安装信息通过 HTTP 提交，第一个完成安装的人创建管理员。安装完成标记和事务锁保证安装不能重复完成。

## 六、六步安装向导

删除安装口令步骤，向导调整为：

1. PostgreSQL
2. Redis
3. 环境检查与运行参数
4. 站点信息
5. 管理员账号
6. 确认安装

状态流转：

```text
DATABASE_REQUIRED → REDIS_REQUIRED → REQUIRED → COMPLETED
```

前端步骤映射：

```text
DATABASE_REQUIRED → 第 1 步
REDIS_REQUIRED    → 第 2 步
REQUIRED          → 第 3 步
COMPLETED         → 登录页
```

删除仅由安装口令决定的中间状态。

## 七、后端安装接口调整

删除安装口令配置项、格式验证、比较逻辑、自定义请求头、Controller 参数、CORS 允许头和对应测试。

保留：

- 安装启用状态判断。
- PostgreSQL、Redis 连接测试。
- 短期单次配置票据。
- 安装实例标识。
- 数据库初始化事务。
- 永久安装完成标记。
- 并发完成安装锁。
- 已完成实例禁止重新初始化。

接口规则：

- 安装未完成时允许调用当前阶段对应接口。
- 安装已完成后所有写入型安装接口拒绝执行。
- 重启后继续根据永久完成标记保持关闭。
- `GET /api/install/status` 返回当前安装状态。
- 安装状态不再依赖口令。

## 八、HTTP 安装配置

首次运行启用：

```text
NAV_WEB_INSTALL_ENABLED=true
NAV_ALLOW_INSECURE_DATABASE_SETUP=true
```

允许通过 HTTP 提交 PostgreSQL、Redis、管理员和站点配置。安装完成后由永久完成标记锁定流程，不引入安装脚本后台轮询，也不依赖自动重写环境变量。

## 九、Docker 安装实现

安装目录：

```text
/opt/yunlume/
├── compose.yml
├── .env
├── VERSION
└── release-manifest.json
```

Compose 只包含 `frontend` 和 `backend`，使用：

```dotenv
FRONTEND_IMAGE=ghcr.io/<owner>/yunlume-frontend:<version>
BACKEND_IMAGE=ghcr.io/<owner>/yunlume-backend:<version>
```

持久化卷：

```text
yunlume_uploads_data
yunlume_backend_logs
yunlume_database_config
```

安装步骤：检查 Docker 与 Compose、下载配置、固定前后端版本、创建卷、启动后端和前端、检查 `/api/health` 与 `/healthz`，最后输出公网安装地址。PostgreSQL 和 Redis 不加入 Compose。

## 十、宿主机安装实现

发行包：

```text
yunlume-host-v<version>.tar.gz
├── backend/yunlume-backend.jar
├── frontend/
├── deploy/yunlume-backend.service
├── deploy/yunlume.nginx.conf
├── deploy/app.env.template
├── VERSION
└── SHA256SUMS
```

目录：

```text
/opt/yunlume/releases/<version>
/opt/yunlume/current
/etc/yunlume/app.env
/etc/yunlume/nginx.conf
/var/lib/yunlume/config
/var/lib/yunlume/uploads
```

Java 后端监听 `127.0.0.1:18081`，Nginx 监听 `0.0.0.0:<公网端口>`。Nginx 提供前端文件、上传文件、SPA 回退和 `/api/` 代理。后端日志进入 journald，systemd 使用 `Restart=always`，程序、配置和用户数据分离。

## 十一、发布流水线

版本标签触发以下任务：

1. 后端测试。
2. 前端测试和生产构建。
3. 分别构建 `yunlume-backend`、`yunlume-frontend`。
4. 发布 `linux/amd64` 和 `linux/arm64` 镜像。
5. 打包宿主机发行包。
6. 生成 SHA-256 校验文件。
7. 生成发布清单。
8. 上传 GitHub Release 资产和安装脚本。

发布清单分别记录前后端镜像和宿主机包，不使用聚合镜像；安装和回滚使用固定版本标签，不依赖浮动标签。

## 十二、升级和回滚

Docker 模式保存当前镜像引用，拉取指定新版本并重建容器；健康检查成功后更新版本文件，失败时恢复旧的前后端镜像引用。

宿主机模式将新版本解压到新的 release 目录，切换 `current` 软链接并重启服务；健康检查失败时切回旧目录。配置、上传文件和数据库不随版本目录切换。

数据库迁移必须保持向后兼容，文件和镜像回滚不负责撤销破坏性数据库变更。

## 十三、已有部署数据迁移

统一 Compose 项目和卷名时：

1. 记录当前容器、镜像、卷和配置。
2. 停止应用写入。
3. 创建新的 `yunlume_*` 卷。
4. 复制上传文件和安装配置。
5. 比较文件数量、大小和摘要。
6. 使用新 Compose 项目启动。
7. 验证数据库、Redis、登录和上传。
8. 保留原卷作为回滚点。

原卷不自动删除；外部 PostgreSQL 和 Redis 数据不搬迁、不删除。

## 十四、测试调整

后端覆盖无口令状态查询、PostgreSQL/Redis 配置、状态流转、安装完成、重复和并发完成、完成后拒绝配置及重启后的永久状态。

前端覆盖不再发送口令头、六步映射、刷新恢复、错误展示、管理员创建、完成后跳转及已安装实例禁止重新打开向导。

安装脚本覆盖参数解析、缺少依赖、下载失败、摘要错误、重复执行、健康检查失败以及两种模式回滚。

## 十五、验证环境

本地只执行不产生依赖和构建目录的字符串搜索、Shell/YAML 静态检查、配置引用检查和变更范围检查。

当前轮完整构建与打包验证在专用新服务器执行，本地虚拟机不再承担构建任务：

1. 后端测试。
2. 前端测试和生产构建。
3. 两个 Docker 镜像分别构建。
4. 生成宿主机发行包、发布清单和 SHA-256 校验文件。
5. 验证前后端镜像名称、标签、架构和摘要相互独立。
6. 验证 Compose 配置与发行包内容。
7. 验证全仓库旧品牌字符串为零。

早期改造阶段不执行 `install.sh`，不启动 yunlume 应用，也不配置 PostgreSQL 或 Redis。该限制已经完成其阶段性目的；代码推送、正式版本标签和 Release 资产完成后，已使用 `v1.0.7` 正式资产执行以下一键安装验收：

1. 从 GitHub Release 下载并执行 `install.sh`。
2. 分别验证 Docker 全新安装和宿主机全新安装。
3. 直接访问公网 HTTP 安装页面，不使用 SSH 转发。
4. 完成 PostgreSQL、Redis、管理员和登录流程。
5. 验证完成后不能重新安装。
6. 验证服务重启和重复运行安装脚本。
7. 验证两种模式升级、失败回滚、同兼容代际降级和跨兼容代际拒绝。

上述发布、Docker/宿主机安装升级、`latest` 语义、失败回滚、同代际降级和跨代际保护均已完成。真实同代际降级使用兼容代际均为 `1` 的正式 `v1.0.8 → v1.0.7` Release 验证。详细证据见 [`ACCEPTANCE.md`](ACCEPTANCE.md)。构建服务器上的临时 Docker 验收部署在每轮验收完成后删除，Maven、npm 等构建依赖缓存按约定保留。

## 十六、文档交付

更新 README、`jihua.md`、环境变量示例、Docker/宿主机安装说明、升级回滚说明和 Release 使用说明。不恢复已经删除的独立旧安装计划文件。

每完成一个代码修改任务同步追加 `jiyi.md`，记录修改内容、接口或配置变化、验证命令、验证结果和残余风险。

## 十七、执行顺序

1. 复核工作区并落地本计划。
2. 全仓库统一 `yunlume` 命名。
3. 删除后端安装口令和相关状态。
4. 前端七步向导改为六步。
5. 更新 Compose、环境变量和现有 E2E。
6. 实现统一安装脚本和发布清单。
7. 实现 Docker 安装、升级和回滚。
8. 实现宿主机安装、升级和回滚。
9. 调整 GitHub Actions 和 Release 产物。
10. 完成已有部署数据迁移适配。
11. 在测试服务器执行完整验证。
12. 更新文档和最终变更记录。
13. 复核全部验收项后交付。

# yunlume

yunlume 是一个前后端分离的自托管导航站。前台提供搜索、分类和书签入口，后台用于维护当前主题实际使用的站点信息、背景、分类、书签与搜索引擎。

## 发布与版本口径

- GitHub 当前最新正式 Release 为 [`v1.0.10`](https://github.com/Kcmose/yunlume/releases/tag/v1.0.10)；后续最新版本以 [`releases/latest`](https://github.com/Kcmose/yunlume/releases/latest) 为准。
- 最近完成完整现场闭环的正式验收基线仍为 `v1.0.8`。它不是“当前最新版”的别名；验收证据与适用边界见 [`ACCEPTANCE.md`](ACCEPTANCE.md)。
- Git 标签和 GitHub Release 使用产品版本 `vX.Y.Z`。`nav-frontend/package.json` 的 npm 版本与 `nav-backend/pom.xml` 的 Maven 版本是私有模块构建版本，独立演进，不用于判断产品 Release 或验收基线。
- 发布仓库必须启用并由仓库所有者强制执行 Immutable Releases，并配置 Actions secret
  `IMMUTABLE_RELEASES_READ_TOKEN`：使用仅限本仓库、仅有 **Administration: read** 的 fine-grained
  PAT 或 GitHub App installation token。发布写入仍只使用 `GITHUB_TOKEN`；该独立 secret 不应授予
  Administration write。完整信任模型和固定版 `gh` 校验见 [`ops/RELEASE-TRUST-MODEL.md`](ops/RELEASE-TRUST-MODEL.md)。

## 项目结构

```text
.
├── nav-frontend/       Vue 3 前端及其轻量 Nginx frontend 镜像
├── nav-backend/        Spring Boot 3 + Java 17 后端
├── database/           PostgreSQL 权威结构与迁移历史
├── deploy/host/        宿主机安装的 systemd、Nginx 与环境模板
├── ops/                发行打包、数据卷迁移、E2E 与应用回滚脚本
├── install.sh          Docker/宿主机统一安装入口
├── docker-compose.yml  frontend 与 backend 编排（PostgreSQL、Redis 均使用外部服务）
└── .env.example        部署环境变量示例
```

## 本地开发

依赖：Node.js 20+、npm 10+、JDK 17 和 Maven 3.9+。默认 `local` profile 使用 PostgreSQL 兼容模式的内存 H2，直接开发不要求安装 PostgreSQL 或 Redis；数据会在后端进程退出后清空。`prod` profile 和 Docker 部署只连接部署者提供的外部 PostgreSQL 与外部 Redis。

1. 启动后端：

   ```bash
   cd nav-backend
   mvn spring-boot:run
   ```

2. 在另一个终端启动前端：

   ```bash
   cd nav-frontend
   npm ci
   npm run dev
   ```

开发地址以终端输出为准，通常为：

- 前端：`http://localhost:5173/`
- 后台登录：`http://localhost:5173/admin/login`
- 后端健康检查：`http://localhost:8080/api/health`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- Swagger 兼容入口：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- H2 控制台：`http://localhost:8080/h2-console`（仅 `local` profile）

`local` profile 默认启用 Swagger/OpenAPI；`prod` profile 默认关闭，生产环境只有显式设置 `OPENAPI_ENABLED=true` 才会开放这些文档地址。

如需在本机按生产方式联调，请先提供外部 PostgreSQL 空白专用库和外部 Redis，再设置 `SPRING_PROFILES_ACTIVE=prod`、`CACHE_TYPE=redis`、JWT 签名密钥以及 `NAV_DATABASE_SOURCE=UNCONFIGURED`、`NAV_REDIS_SOURCE=UNCONFIGURED`。启动后通过网页安装向导依次提交两个外部服务的连接并初始化数据库 schema；不需要手工执行根目录 SQL。

## 一键安装

首次安装前先准备外部 PostgreSQL 14+ 空白专用数据库和外部 Redis。安装器只部署 `yunlume` 前后端，不会创建、搬迁或删除 PostgreSQL/Redis。默认监听端口的 HTTP 入口仅用于 `/healthz` 连通性诊断；提交 PostgreSQL、Redis 或管理员凭据前，必须配置受信任域名和 HTTPS 反向代理，并禁止公网绕过代理直连应用端口。

Docker 模式要求服务器已经安装 Docker 与 Compose v2：

```bash
curl -fsSL https://github.com/Kcmose/yunlume/releases/latest/download/install.sh | sudo bash
```

上面的 `latest` 地址始终下载最新正式 Release 的安装器；首次安装时安装最新版，已有部署执行时升级到最新版。需要安装、重装或固定操作某个版本时，直接使用该版本的 Release 地址，安装器已经内嵌自己的目标版本，不需要重复传入 `--version`：

```bash
curl -fsSL https://github.com/Kcmose/yunlume/releases/download/v1.0.7/install.sh | sudo bash -s -- --mode docker
```

宿主机模式不使用 Docker，要求服务器已经安装 Java 17+、Nginx 和 systemd。这里的“二进制安装”指发布包中的后端可执行 JAR 与编译后的前端静态文件：

```bash
curl -fsSL https://github.com/Kcmose/yunlume/releases/latest/download/install.sh | sudo bash -s -- --mode host
```

宿主机模式同样可以使用固定版本安装器：

```bash
curl -fsSL https://github.com/Kcmose/yunlume/releases/download/v1.0.7/install.sh | sudo bash -s -- --mode host
```

两种模式都可增加 `--port 端口`、`--install-dir 绝对路径`。成功后安装器输出的 HTTP 地址仅用于连通性诊断：

```text
http://服务器公网IP:8080/healthz
```

从首个包含该机制的新 Release 起，安装脚本通过严格发行清单固定同一版本的前后端 OCI digest，并校验 Compose 与宿主机归档的 SHA-256；`v1.0.10` 及更早的既有 Release 仍使用当时发布的标签型清单。版本标签仅作为便于阅读的发布别名，不是新版本安装或回滚的内容锚点。Docker 模式安装到 `/opt/yunlume`，使用 `yunlume_uploads_data`、`yunlume_backend_logs`、`yunlume_database_config` 三个命名卷；宿主机模式使用 `/opt/yunlume/releases`、`/etc/yunlume`、`/var/lib/yunlume`，后端只监听 `127.0.0.1:18081`，系统 Nginx 对公网提供统一入口。

已有实例需要升级到最新正式版本时，直接再次执行 `latest` 安装器；选择该入口本身就是升级授权：

```bash
curl -fsSL https://github.com/Kcmose/yunlume/releases/latest/download/install.sh | sudo bash -s -- --mode docker
curl -fsSL https://github.com/Kcmose/yunlume/releases/latest/download/install.sh | sudo bash -s -- --mode host
```

同版本重复执行是幂等恢复。每个 Release 清单携带正整数 `compatibilityEpoch`，部署记录成功应用过的最高代际：同一代际内允许使用固定版本安装器主动降级；目标代际低于部署记录时拒绝直接降级，必须恢复跨代升级前备份或使用受支持的反向迁移流程。安装器也拒绝在同一目录混用 Docker/宿主机模式。新版本未通过 `/healthz` 与 `/api/health` 时会恢复原配置、版本和服务，且不会提前提高兼容代际；如果回滚本身失败，会保留恢复材料并明确报错。升级前仍需按对应 Release 说明处理外部数据库兼容性。

首次安装失败且回滚、清理均成功时，安装器在安装目录写入 root 私有的 `.install-retry`，绑定原 `.install-mode` 文件身份。修正网络或配置问题后可原样重跑同模式命令，Host 已下载的版本目录可以保留。回滚失败、清理失败或 `SIGKILL` 中断不会授予自动重试；应先核对报错中的恢复材料与实际服务状态，不能直接删除 `.install-mode` 或伪造重试记录绕过检查。

### GitHub 自动构建镜像

仓库包含 `.github/workflows/publish-images.yml`。Pull Request 和普通分支只执行安装器回归检查、前后端测试、生产构建与依赖审计，不会登录镜像仓库或发布镜像。只有以下两种提交在全部门禁通过后发布到 GitHub Container Registry（GHCR）：

- 默认分支：`ghcr.io/<所有者>/yunlume-frontend:latest` 和 `ghcr.io/<所有者>/yunlume-backend:latest`。
- `v1.2.3` 形式的版本标签：先生成仅用于恢复的 `release-candidate-<40位提交摘要>`，发布后生成不可变的 `1.2.3` 和滚动的 `1.2` 标签；标签事件不生成 `sha-*`。默认分支始终生成对应的完整 SHA 标签。标签门禁通过后还会创建最新 GitHub Release。

镜像路径中的所有者和镜像名会自动转换为小写，以兼容 GHCR 命名规则。两个镜像同时发布 `linux/amd64` 与 `linux/arm64` 清单，并附带 BuildKit provenance 和 SBOM。版本 Release 包含安装脚本、Compose、发行清单、宿主机归档及其校验和，还保留精确 OCI 候选归档与承诺、`backend-jar-producer.json`、canonical owner 和签名溯源资产。同标签已发布 Release 必须通过提交、完整签名资产名称/字节、无重复的五项 `SHA256SUMS`、不可变镜像摘要及两个滚动别名校验；别名缺失、过旧、混合或读取失败均返回失败，整个重跑保持零外部写入。

后端测试、独立签名与下游下载通过生产者的 artifact ID、run/attempt、源码 SHA 和摘要关联；失败任务重跑不会使用消费者 attempt 拼接产物名。首次候选持久化的 `backend-jar-producer.json` 固定已签名 JAR，全部任务重跑也沿用该产物；保留期内找不到原产物时明确失败，不替换为重新构建的不同字节。canonical owner 在最终签名资产齐备后写入，已有 owner 时恢复原签名集合；尚未写入时由实际执行的 release attempt 签名。Release 作业执行前重新读取已发布状态，不把上游旧 attempt 缓存的预检结果作为写入依据。完整边界见 [发布信任模型](ops/RELEASE-TRUST-MODEL.md)。

工作流中的第三方 Action 全部固定到完整提交 SHA；测试任务只有仓库只读权限，候选发布任务按需取得 `packages: write`、`contents: write`、`attestations: write` 和 `id-token: write`，使用仓库自动提供的 `GITHUB_TOKEN`。独立 JAR 签名任务不检出或执行仓库脚本。两个 Docker 构建上下文分别限制在 `nav-frontend` 与 `nav-backend`，根目录的 `jiyi.md`、`jihua.md`、`.env` 和运行时凭据不会进入构建上下文；多阶段镜像最终层只包含前端静态运行文件或后端 JRE/JAR。

首次发布后，在 GitHub 仓库的 **Packages** 中确认两个包的可见性。公开项目建议把包设为 Public；私有包部署时应使用只授予 `read:packages` 的令牌登录 `ghcr.io`，不要在服务器上使用个人账号密码。部署时优先固定版本或完整 SHA 标签；`latest` 适合首次体验，但不适合作为可审计的生产版本锚点。

### 测试脚本与中断恢复

CI 对安装器、`ops/*.sh`、`ops/lib/*.sh` 和 Nginx `.envsh` 逐文件执行 `bash -n`，再运行回归测试。`ops/ci-shell-syntax-test.sh` 会故意破坏后续文件来确认门禁确实拦截；`ops/install-e2e-cleanup-test.sh` 在临时目录验证清理及信号行为，不依赖真实 Docker 服务。

隔离安装 E2E 的 `INT`/`TERM` 分别返回 `130`/`143`，中断不算测试通过。清理必须确认 Docker 查询成功、资源名称和 ownership label 匹配、删除后资源确实不存在；Docker 不可达、响应无效、归属不符或删除失败时返回非零并保留状态目录。恢复 Docker 连接后，使用原 `E2E_STATE_ROOT`（若曾自定义）和脚本打印的 run id 执行精确清理：

```bash
CONFIRM_ISOLATED_INSTALL_E2E_CLEANUP=CLEAN-ISOLATED-INSTALL-E2E \
  bash ops/install-e2e.sh cleanup <run-id>
```

清理仍失败时保留 `resource-manifest` 继续排查，不应以删除状态目录代替清理资源。完整安装 E2E 的执行条件和资源范围见 `bash ops/install-e2e.sh plan`。

### 开源提交前检查

不要在 GitHub 网页中直接拖拽整个本地目录；网页上传不会执行 `.gitignore`，可能把私有的 `jiyi.md`、`.env` 或 `.codex*` 临时归档一并上传。应在本地使用 Git 初始化和提交，并在首次提交前核对：

```bash
git check-ignore jiyi.md .env
git status --ignored --short
git ls-files
```

`jiyi.md` 与 `.env` 必须显示为已忽略，`git ls-files` 不得出现凭据、证书、备份、构建产物或 `.codex*` 文件。Shell 脚本应通过本地 Git 保留可执行位；许可证可在正式公开前按项目选择另行加入。

### 手工 Docker Compose 部署

1. 复制环境变量模板并修改其中所有密码和密钥：

   ```bash
   cp .env.example .env
   chmod 600 .env
   test "$(stat -c %a .env)" = 600
   ```

   Windows PowerShell 本地联调可执行：

   ```powershell
   Copy-Item .env.example .env
   ```

   正式 Linux 服务器必须在填写任何密钥前把 `.env` 设为 `0600`；Compose 本身不会阻止权限过宽的文件，镜像回滚脚本会直接拒绝权限过宽的环境文件。

   `JWT_SECRET` 是启动必填项；留空或不创建 `.env` 时 Compose 会拒绝启动。外部 PostgreSQL 与 Redis 的连接密码都在安装页中提交，不写入 `.env`；只有从旧版本升级并显式使用 `LEGACY_ENV` 时才继续读取 `REDIS_*` 变量。

2. 使用已经发布的镜像时，先在 `.env` 固定同一提交的 `FRONTEND_IMAGE` 和 `BACKEND_IMAGE`：

   ```dotenv
   FRONTEND_IMAGE=ghcr.io/<所有者>/yunlume-frontend:sha-<40位提交摘要>
   BACKEND_IMAGE=ghcr.io/<所有者>/yunlume-backend:sha-<40位提交摘要>
   ```

   再拉取并启动：

   ```bash
   docker compose pull
   docker compose up -d --no-build
   ```

   不应把尖括号原样写入配置；请在对应版本的 GitHub Packages 页面复制同一提交的两个真实镜像引用。使用私有包时，先用只具备 `read:packages` 的令牌执行 `docker login ghcr.io`。

   开发者需要从源码自行构建时才使用 `docker compose up -d --build`。

3. 查看状态与日志：

   ```bash
   docker compose ps
   docker compose logs -f backend frontend
   ```

默认 `APP_PORT=8080`，统一入口如下：

- 导航首页：`http://localhost:8080/`
- 首次安装：`http://localhost:8080/install`
- 管理后台：`http://localhost:8080/admin/login`
- API 健康检查：`http://localhost:8080/api/health`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`（仅 `OPENAPI_ENABLED=true`）
- Swagger 兼容入口：`http://localhost:8080/swagger-ui.html`（仅 `OPENAPI_ENABLED=true`）
- Knife4j（若后端启用）：`http://localhost:8080/doc.html`

Compose 不创建 PostgreSQL、Redis 容器或对应数据卷；两项服务都必须由部署者、1Panel 或云服务商预先提供。项目只保留 `yunlume_uploads_data`、`yunlume_backend_logs` 和 `yunlume_database_config` 三个显式命名卷，容器更新或重启不会清空上传文件、后端日志，以及安装向导保存的 PostgreSQL/Redis 连接配置、私有 CA 与实例标记。外部 PostgreSQL、Redis 的数据持久化、高可用与服务端备份由对应服务提供方负责。

安装向导中的 Redis 支持“系统信任证书”“自定义 CA”和“可信私网明文”三种方式。前两种都会校验证书与主机身份，自定义 CA 只保存在后端专用配置卷；关闭 TLS 只允许解析到私网地址并要求显式确认风险，不要以关闭 TLS 代替正确的证书配置。

推荐在 1Panel/服务商界面为应用创建独立 Redis ACL 用户和逻辑库，键范围只需 `~nav:*`，命令需要 PING、SELECT、INFO、SET（含 NX/PX）、GET、DEL、EVAL、EVALSHA、EXISTS、INCR、PSETEX、PEXPIRE；安装向导先读取 `INFO server` 确认 Redis 7+，再以随机 128 位命名空间执行一个原子 Lua 探针，在首次写入前检查全部实际命令/键权限与碰撞，并在同一脚本回复前清理。不要授予 CONFIG、ACL、FLUSHALL/FLUSHDB、MODULE、DEBUG 或 SHUTDOWN。不要把 Redis 密码直接写进 shell 命令历史。当前版本只支持 standalone 主机/端口与逻辑库，不支持 Sentinel 或 Cluster（Cluster 通常也只能使用 db 0）。

### 从旧四容器拓扑升级

旧版本由 `nginx`、`frontend`、`backend`、`redis` 四个项目容器组成。它没有向导托管的 Redis 配置标记，不能通过改 `.env` 后原地套用本版 Compose；本版会对“已有托管数据库但缺少 Redis 配置”的状态失败关闭，防止静默接入错误缓存。

推荐采用蓝绿迁移：先完整保留并备份旧发行目录、数据库、上传卷、Redis 数据和镜像，在旧后台导出可移植 ZIP；随后使用不同项目名、端口和全新命名卷部署本版，连接已准备的外部 PostgreSQL/Redis，完成六步安装并导入 ZIP。可移植包不包含旧管理员账号，新站管理员由安装向导重新创建。核对首页、分类、书签、搜索、自定义链接、站点配置和背景资源后，再把 1Panel OpenResty 流量切到新端口；观察期内保留旧环境作为回退锚点。

当前版本不提供旧环境变量 Redis 到托管 Redis 配置的原地迁移工具。若必须保留旧账号或无法使用可移植 ZIP，应先制定并演练专用数据迁移方案，不要直接重建旧 `backend`。全程禁止 `docker compose down -v`、`docker volume prune`、`docker system prune` 或 `--remove-orphans`，也不要删除旧 Redis/数据库卷。

`frontend` 内的 Nginx 会在启动时解析 `backend` 容器地址。日常发布或回滚后端时应同时重建 `frontend`，例如 `docker compose up -d --no-build --force-recreate backend frontend`，不要只替换后端后长期保留旧 frontend 容器。

### 迁移已有 Docker 上传卷和安装配置卷

仅需要把已有上传文件与已经托管的安装配置切换到统一卷名时，可使用 `ops/migrate-docker-volumes.sh`。它不会转换旧 Redis 环境变量配置，也不会操作外部 PostgreSQL、外部 Redis 或后端日志；源卷只会被 inspect、只读挂载和读取，不会写入或删除。迁移全程必须保持旧应用栈停止，并确认本机已有一个支持 `sh` 的 `pipefail`、且包含 `tar/find/wc/awk/sort/sha256sum/diff/stat` 的 Linux 镜像；当前版本的 `yunlume-frontend` 镜像可作为 helper，脚本不会自动拉取镜像。

```bash
sudo bash ops/migrate-docker-volumes.sh \
  --source-uploads-volume <原上传卷> \
  --source-config-volume <原安装配置卷> \
  --helper-image ghcr.io/<所有者>/yunlume-frontend:<当前版本> \
  --execute
```

目标 `yunlume_uploads_data`、`yunlume_database_config` 必须尚不存在。脚本对两个挂载都禁用 Docker 的 volume copy-up，使用无网络的临时容器复制数据，拒绝源卷中的符号链接/特殊文件，并比较目录差异、条目数、文件数、字节数、内容摘要和权限/所有者元数据摘要；ACL、xattr 和非本地卷插件语义不在自动校验范围内，应另行确认。报告写到 root 独占的 `/var/lib/yunlume/migrations`。失败时保留带 run 标签的目标卷供检查，不自动删除。成功后把新部署的 `UPLOADS_VOLUME_NAME`、`DATABASE_CONFIG_VOLUME_NAME` 指向报告中的目标卷，启动新 Compose，逐项验证健康、登录、上传内容和安装状态 `COMPLETED`。原卷继续保留作为回滚点。

### 配合 1Panel OpenResty

`frontend` 镜像已经包含轻量 Nginx，用于提供前端静态文件、代理 `/api`、读取上传卷和执行应用级限流；1Panel OpenResty 不替代这个内部服务器，只负责域名、HTTPS 和最外层反向代理。因此 Docker 中仍只有 `frontend` 与 `backend` 两个项目容器，不再单独运行入口 Nginx 容器。

直接通过 `APP_PORT` 访问时保持 `APP_BIND_ADDRESS=0.0.0.0`、`WEB_TRUST_PROXY_HEADERS=false`，此时客户端伪造的转发头不会被信任。接入 1Panel OpenResty 时按以下边界配置：

1. OpenResty 反向代理到宿主机的明确 loopback/私网地址与 `APP_PORT`，并显式覆写 `Host`、`X-Real-IP`、`X-Forwarded-For`、`X-Forwarded-Proto`；公网入口只开放 HTTPS。
2. 把 `APP_BIND_ADDRESS` 改为该明确的 loopback/私网地址，并用宿主机防火墙或隔离网络限制 `APP_PORT`，禁止普通客户端绕过 OpenResty 直连。若 OpenResty 运行在容器中，宿主机 `127.0.0.1` 通常不可从该容器访问，应使用受保护的宿主机私网地址或专用容器网络。
3. 先保持 `WEB_TRUST_PROXY_HEADERS=false` 发起一次代理请求，从 `docker compose logs frontend` 确认 frontend 容器实际看到的即时代理源地址；再设置 `WEB_TRUST_PROXY_HEADERS=true`，并把 `WEB_TRUSTED_PROXY_CIDR` 设为该地址的 `/32`（IPv6 用 `/128`）或最窄的隔离代理网段。
4. 执行 `docker compose up -d --no-deps --force-recreate frontend`，再确认 HTTPS 安装页、登录限流和访问日志中的客户端地址正确。

安全校验会拒绝“信任代理头 + `0.0.0.0`/`::` 泛监听”，也拒绝信任 `0.0.0.0/0` 或 `::/0`。不要为了省事扩大可信网段；只有来自所配即时代理地址的协议和客户端 IP 头才应进入后端。

后端另用 `NAV_TRUST_FORWARDED_HTTPS` 与 `NAV_TRUSTED_PROXY_PEERS` 校验其直接连接的代理。可信代理下，HTTPS `Origin` 与原始 `Host` 的主机及有效端口精确匹配时按同源放行，不必把本站 HTTPS 域名重复加入 CORS 白名单；`X-Forwarded-Host/Port` 不参与判断，真正跨域的前端仍需配置 `CORS_ALLOWED_ORIGINS`。

### 宿主机模式接入 HTTPS 代理

Host 模式由外层代理终止 TLS，再转发给安装器管理的系统 Nginx。把外层代理的证书和虚拟主机配置保存在独立文件；`/etc/yunlume/nginx.conf` 由安装器生成。Host 专用设置保存在 root 所有、权限 `0600` 的 `/etc/yunlume/app.env`，升级会保留：

```dotenv
HOST_BIND_ADDRESS=127.0.0.1
HOST_TRUST_PROXY_HEADERS=true
HOST_TRUSTED_PROXY_CIDR=127.0.0.1/32
```

以上示例适用于外层代理与系统 Nginx 共享宿主机网络、直属连接来源为 `127.0.0.1` 的情况。外层代理须覆写 `Host`、`X-Forwarded-Proto` 和 `X-Forwarded-For`；使用实际观察到的直属代理地址配置可信 CIDR。容器代理需要明确的宿主机私网绑定，并通过防火墙限制该端口只接受代理连接。启用代理信任时拒绝公网、泛监听地址及全网可信 CIDR；非可信来源伪造的 HTTPS 头不会转交后端。后端可信直属代理仍是本机 Nginx，保持 `NAV_TRUSTED_PROXY_PEERS=127.0.0.1`。

修改后使用**包含此修复的同版本 Release 安装器**执行 `--mode host` 应用配置，并保留原 `--install-dir`；固定版本地址避免把配置应用意外变成升级。先确认 HTTPS 的 `/healthz` 与 `/install` 可达，再填写凭据。不要直接修改生成的 `listen` 行添加证书：检测到无法识别的原监听配置时，安装器会中止，需先将 TLS 配置迁移到独立外层代理。

向导尚未完成时，只允许对旧受管后端正在运行、状态可确认的同版本实例应用代理配置。应用及失败恢复后均不得退回更早的向导阶段；已完成初始化的部署和跨版本升级继续要求 `UP/COMPLETED`。

## 首次部署安装向导

新部署把 `NAV_DATABASE_SOURCE` 与 `NAV_REDIS_SOURCE` 都设为 `UNCONFIGURED` 后，访问首页、后台或 `/install` 会进入首次部署向导。本发行编排和安装页只支持外部 PostgreSQL 与外部 Redis：请分别填写两个服务的结构化连接信息。Compose 不创建数据库或缓存服务。

部署者必须先准备一个非 superuser 业务用户，并为其提供空白、专用的 PostgreSQL 14+ 数据库以及在 `public` schema 创建表、索引、序列、函数、触发器和迁移登记所需的 DDL 权限。数据库既可在 1Panel 中创建，也可按下方两段 SQL 建立。安装页中的“管理员账号”是网站最高权限账号，不是 PostgreSQL 超级用户；页面不会索取数据库超级用户密码，也不会创建 PostgreSQL 服务器或角色。向导会先只读测试连接；目标含未知对象、残缺项目结构、旧版未迁移结构或已安装管理员时均零写入拒绝。接管现有未安装结构时，还会比对列类型、长度、默认值、identity/generated 属性和排序规则；列名相同但定义不符也会拒绝。空库只有在用户明确确认后才执行权威 PostgreSQL schema 初始化。

在 1Panel 的 PostgreSQL 管理界面中，先创建一个普通登录用户并生成独立高强度密码，确认未授予 superuser、createdb、createrole 等全局权限。随后可在 SQL 控制台按实际名称替换下面的标识符；先连接 `postgres` 维护库执行第一段，再切换到新数据库执行第二段。脚本不包含密码，安装页也提供相同的转义后 SQL 和复制按钮：

```sql
-- 第 1 段：连接 postgres 维护库执行
CREATE DATABASE nav_system WITH OWNER nav_app ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE nav_system FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE nav_system TO nav_app;

-- 第 2 段：切换连接到 nav_system 后执行
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO nav_app;
```

不要把数据库超级用户或 1Panel 面板账号填入安装页；只有业务用户的连接密码会提交给后端。

安装页直接进入“PostgreSQL → Redis → 环境 → 站点 → 管理员 → 确认”六步流程。PostgreSQL 与 Redis 都采用“测试连接 → 短期单次 ticket → 保存配置 → 后端重启接管”的流程；ticket 默认有效 5 分钟，可配置为 30–900 秒。连接测试返回后页面立即清除密码与 CA 原文，只将 ticket 保留到配置接口单次消费或过期；这些内容都不进入 URL、浏览器存储或应用日志。配置会写入仅后端挂载的 `yunlume_database_config` 卷（目录 `0700`、文件 `0600`）；Redis 配置标记与当前 PostgreSQL 实例 UUID 交叉绑定，防止把一套站点的运行配置误恢复到另一套数据库实例。

PostgreSQL TLS 默认 `VERIFY_FULL`，也可选择 `VERIFY_CA`；`REQUIRE` 不校验证书和主机名，必须显式确认风险，外部模式不允许关闭 TLS。Redis 默认使用 JVM 系统信任证书，也可在页面提供私有 CA；只有解析到可信私网地址时才允许显式确认后关闭 Redis TLS。

向导会在 Redis 测试和提交时比较 DNS 解析结果，并拒绝本机、链路本地、元数据及组播地址，但这不替代网络侧访问控制。TLS 模式依靠证书主机校验抵御错误目标；明文私网模式应优先填写固定私网 IP，或使用受控且不会被外部修改的内部 DNS，并由防火墙限制 Redis 只接受应用网段连接。

PostgreSQL 和 Redis 都接管并重启验证后，向导才执行数据库结构、站点单例、上传目录和 Redis 实际读写检查，然后创建站点信息与唯一首位网站管理员。匿名状态接口不会探测外部资源或公开组件细节。任一依赖不可达时环境检查失败，不允许完成安装；安装完成后的健康检查也会失败关闭，但不会重新开放换库或换 Redis 入口。安装完成后写入永久完成标记，后续所有修改型安装接口拒绝再次初始化；容器重启不会重新开放入口。

安装提交受到 frontend 容器内置 Nginx 的每来源 IP 限流。服务端会在同一事务中再次锁定并检查安装状态，只有用户表为空且安装标记未完成时才能写入；成功后入口永久关闭，不会签发自动登录令牌，需使用刚创建的账号在 `/admin/login` 登录。即使之后误删全部用户，安装标记也不会自动重开。已有管理员的升级部署会在数据库迁移时回填完成标记，不修改管理员、密码或站点业务数据。

默认 HTTP 入口只允许用于 `http://服务器公网IP:APP_PORT/healthz` 连通性诊断。新部署默认设置 `NAV_ALLOW_INSECURE_DATABASE_SETUP=false`；必须先按上节配置受信任域名、HTTPS 代理和可信代理来源，再访问 `https://受信任域名/install` 提交 PostgreSQL、Redis 和管理员凭据。安装完成标记写入后，安装配置和完成接口永久拒绝再次执行。

`POST /api/install/complete` 与数据库、Redis 凭据接口执行同一 HTTPS 检查，明文管理员初始化会返回 `403`。仅在可信局域网临时设置 `NAV_ALLOW_INSECURE_DATABASE_SETUP=true` 时才允许通过 HTTP 完成这些凭据提交。

通过安装向导完成的站点升级时继续保持 `NAV_DATABASE_SOURCE=UNCONFIGURED` 与 `NAV_REDIS_SOURCE=UNCONFIGURED`，并原样保留 `yunlume_database_config` 卷。数据库/Redis 连接文件、配置标记、完成标记或已有实例身份任一存在时，依赖断线只会进入故障状态，不会重新开放换库入口。旧版依赖 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 与 `LEGACY_ENV` 的直连部署不能直接套用本编排升级，应先在独立环境完成数据迁移和恢复演练。

`yunlume_database_config` 是安装状态、数据库实例身份及 Redis 连接状态的一部分，不是可丢弃缓存。它保存明文 PostgreSQL/Redis 连接凭据、可选 CA 和实例身份；整个卷丢失后项目没有受支持的“重新关联既有服务”流程，也不能把重新运行安装向导当作恢复方式。该卷必须独立加密、异机备份；跨主机恢复后保持目录 `0700`、文件 `0600`，并恢复为后端固定运行身份 `10001:10001`，先在隔离卷中完成恢复演练。安装完成并确认登录成功后，应保留完整配置卷和完成标记，并禁止对该卷执行 `down -v`、`volume rm` 或 `volume prune`。

若断电或存储故障留下 `redis.configured` 的 `PENDING` 状态、缺少 Redis 配置/CA、摘要或实例 UUID 不一致，后端会故意拒绝就绪，不会回退到空值、localhost 或旧环境变量。不要直接删除整个卷或重新安装；应先停止应用、加密备份现状并核对数据库实例 UUID，然后从最近一次已验证的完整配置卷备份恢复。当前版本不提供在线换 Redis 或自动修复部分工件的入口。

需要无人值守部署时仍可使用传统引导：设置 `NAV_BOOTSTRAP_ENABLED=true`、`ADMIN_USERNAME` 和满足强密码规则的 `ADMIN_PASSWORD`。环境变量引导成功后同样关闭网页安装入口；`ADMIN_PASSWORD` 不会在后台改密时回写。默认 `local` profile 的开发账号仍为 `admin / Local!Start2026`。生产默认 `NAV_DEMO_DATA_ENABLED=false`，不会因某张业务表为空而重新补写演示业务数据。

## 账号安全

登录后可在后台“账号安全”（`/admin/account`）查看当前账户、修改密码或退出全部会话。新密码必须满足以下规则：

- 12–72 个字符，且 UTF-8 编码后不超过 BCrypt 的 72 字节上限。
- 不含空格或其他空白字符。
- 大写字母、小写字母、数字、符号四类中至少包含三类。
- 不包含管理员用户名，且不能与当前密码相同。

修改密码时必须提供当前密码和一致的新密码确认。改密成功后，密码使用 BCrypt 保存，并使当前设备及其他设备上此前签发的全部 JWT 立即失效；请使用新密码重新登录。“退出全部会话”不会修改密码，但也会撤销所有现有 JWT。普通“退出”只清理当前浏览器保存的令牌。

相关管理接口均需要管理员 JWT：

- `PUT /api/admin/auth/password`：修改当前管理员密码并撤销旧会话。
- `POST /api/admin/auth/logout-all`：保留密码并撤销当前管理员的全部旧会话。
- `POST /api/admin/auth/logout`：客户端无状态退出。

## 主要环境变量

| 变量 | 用途 | 示例默认值 |
|---|---|---|
| `APP_PORT` | frontend 容器映射到宿主机的端口 | `8080` |
| `APP_BIND_ADDRESS` | frontend 端口绑定地址；信任代理头时必须是明确的 loopback/私网地址 | `0.0.0.0` |
| `WEB_TRUST_PROXY_HEADERS` | 是否接受所配即时代理提供的客户端 IP 与原始协议头 | `false` |
| `WEB_TRUSTED_PROXY_CIDR` | 唯一可信即时代理地址或最窄隔离网段；禁止全网段 | `127.0.0.1/32` |
| `NAV_TRUST_FORWARDED_HTTPS` | 后端是否接受所配即时代理转发的 HTTPS 协议 | Compose/宿主机模板为 `true`，后端独立启动默认 `false` |
| `NAV_TRUSTED_PROXY_PEERS` | 后端可信即时代理的地址或可解析主机名，逗号分隔 | Compose 为 `frontend`，宿主机为 `127.0.0.1,::1` |
| `TZ` | 容器时区 | `Asia/Hong_Kong` |
| `UPLOADS_VOLUME_NAME` | 当前上传文件卷 | `yunlume_uploads_data` |
| `LOGS_VOLUME_NAME` | 当前后端日志卷 | `yunlume_backend_logs` |
| `DATABASE_CONFIG_VOLUME_NAME` | 安装向导持久化 PostgreSQL/Redis 连接、CA 与实例标记的后端专用卷 | `yunlume_database_config` |
| `FRONTEND_IMAGE` / `BACKEND_IMAGE` | 可固定或回滚的 frontend/backend 应用镜像引用 | `yunlume-frontend:latest` / `yunlume-backend:latest` |
| `NAV_REDIS_SOURCE` | Redis 来源；新部署使用向导 `UNCONFIGURED`，旧版环境变量部署使用 `LEGACY_ENV` | `UNCONFIGURED` |
| `NAV_REDIS_TICKET_TTL_SECONDS` | Redis 连接测试 ticket 有效期（服务端限制 30–900 秒） | `300` |
| `NAV_REDIS_AUTO_RESTART` | 保存 Redis 配置后是否让容器自动重启接管 | `true` |
| `REDIS_HOST` / `REDIS_PORT` | 仅 `LEGACY_ENV` 升级兼容的外部 Redis 地址 | 空 / `6379` |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | 仅 `LEGACY_ENV` 使用的 ACL 凭据 | 空 |
| `REDIS_DATABASE` | 仅 `LEGACY_ENV` 使用的逻辑库编号 | `0` |
| `REDIS_SSL_ENABLED` | 仅 `LEGACY_ENV` 使用的 TLS 开关 | `true` |
| `REDIS_CONNECT_TIMEOUT` / `REDIS_READ_TIMEOUT` | 仅 `LEGACY_ENV` 使用的超时 | `3s` |
| `JWT_SECRET` | JWT 签名密钥 | 至少 32 字节随机值 |
| `JWT_EXPIRATION_MINUTES` | 登录令牌有效期（分钟，允许 5–10080） | `120` |
| `OPENAPI_ENABLED` | 生产环境是否开放 Swagger/OpenAPI | `false` |
| `CORS_ALLOWED_ORIGINS` | 允许真正跨域的前端来源；可信代理下精确匹配完整 Host 的 HTTPS 同源自动识别 | 跨域部署时按实际域名补充 |
| `CACHE_TYPE` | 公开数据缓存类型 | 本地为 `simple`，生产必须为 `redis` |
| `NAV_BOOTSTRAP_ENABLED` | 是否使用环境变量自动创建首位管理员 | `false` |
| `NAV_DEMO_DATA_ENABLED` | 是否由后端补写演示业务数据；生产应关闭 | `false` |
| `NAV_WEB_INSTALL_ENABLED` | 是否允许未初始化的新库使用网页安装向导 | `true` |
| `NAV_DATABASE_SOURCE` | 数据库来源；外部数据库安装向导部署保持 `UNCONFIGURED` | `UNCONFIGURED` |
| `NAV_ALLOW_INSECURE_DATABASE_SETUP` | 是否允许通过 HTTP 提交 PostgreSQL/Redis 凭据及初始化管理员；生产安装保持禁用 | `false` |
| `NAV_DATABASE_TICKET_TTL_SECONDS` | 数据库连接测试 ticket 有效期（服务端限制 30–900 秒） | `300` |
| `NAV_DATABASE_AUTO_RESTART` | 保存数据库配置后是否让容器自动重启接管 | `true` |
| `ADMIN_USERNAME` | 传统环境变量引导的管理员用户名 | `admin` |
| `ADMIN_PASSWORD` | 传统环境变量引导的管理员密码 | 仅启用传统引导时必填 |
| `VITE_API_BASE_URL` | 前端构建时 API 根路径 | `/api` |
| `APP_UPLOAD_MAX_BYTES` | 后台背景图单文件大小上限（字节，允许 1–10485760） | `10485760`（10MiB） |
| `APP_UPLOAD_MAX_TOTAL_BYTES` | 受管背景图片总容量上限（字节） | `1073741824`（1GB） |
| `APP_UPLOAD_MAX_FILES` | 受管背景图片数量上限 | `500` |
| `APP_UPLOAD_ORPHAN_GRACE_MS` | 未被配置引用图片的保留宽限期 | `86400000`（24小时） |
| `APP_UPLOAD_CLEANUP_INTERVAL_MS` | 孤儿图片定时清理间隔 | `21600000`（6小时） |
| `APP_UPLOAD_CLEANUP_INITIAL_DELAY_MS` | 启动后首次清理延迟 | `60000`（1分钟） |
| `JAVA_OPTS` | JVM 运行参数 | 见 `.env.example` |

## 背景设置

后台“站点配置”支持纯色和图片两种背景模式：

- 纯色模式提供纯黑、纯白快捷选项，也可自行选择背景色与字体色。
- 图片模式可分别上传 PC 端和移动端 JPG、JPEG 或 PNG 图片；移动端留空时自动沿用 PC 端图片。
- 上传图片保存在 `yunlume_uploads_data` 命名卷，容器重建不会丢失；默认限制为单张 10MiB、总量 1GB、最多 500 张。虽然数据包导入的 multipart 入口允许更大请求，背景图片服务仍独立强制 `APP_UPLOAD_MAX_BYTES` 为 1–10485760 字节。
- 单文件上限同时编译进 frontend 镜像的前端上传提示，并传给后端运行配置；修改 `APP_UPLOAD_MAX_BYTES` 后必须同时重新构建 frontend 与 backend，不能只重启容器。
- 系统只管理自身生成的 `/uploads/backgrounds/{32位小写十六进制}.{jpg|png}` 文件。当前 PC/移动端引用按 URL 路径识别，附加 `?v=1` 或 `#preview` 仍保护同一文件；未被任何站点配置引用的文件保留 24 小时后才可回收。
- 保存配置、回收孤儿图片和导入资产通过同一站点行的数据库事务锁协调。保存时受管图片已缺失或不是普通文件会返回 `400`；导出遇到受管背景缺失、不可读或符号链接则明确失败，避免生成缺图备份。
- 孤儿清理默认在启动 1 分钟后执行，此后每 6 小时执行一次，上传新图前也会先清理；读取配置引用失败时整次清理会跳过，不会冒险删除文件。
- 公开首页的公告、标题、简介、搜索、分类、书签和页脚统一使用当前字体色的完整不透明值，不再派生灰色文字层级；字体色设为纯黑时全页文字均为纯黑。
- 当前公开主题不展示推荐书签圆形入口和分类锚点快捷按钮，搜索框后直接进入分类卡片区域。
- 发布地址、背景特效、背景音乐和推荐书签属于兼容字段，当前主题不会消费，内置后台也不再展示对应的无效控件；接口与历史数据继续保留。

## 可靠性与故障处理

- 站点配置使用 `version` 做乐观并发控制。管理端 `PUT /api/admin/site-config` 必须携带本次读取到的 `expectedVersion`；保存成功版本加 1，旧页面继续保存会返回 `409`，不会覆盖较新的配置。
- 分类、书签、自定义链接的显隐，以及自定义链接排序，只更新目标字段和更新时间，不会用旧实体覆盖其他请求已提交的名称、URL、描述或位置。
- 本地 `simple` 模式的站点配置、导航、搜索引擎和自定义链接四个公开缓存各至多保留当前 generation 的一项结果；新代生效后拒绝迟到旧读回填，事务回滚也不会发布事务内数据或推进代际。
- 后台站点配置只有完整读取服务端数据后才允许编辑和保存；加载失败时表单保持锁定。页面会跟踪整份配置的未保存状态，刷新浏览器、重新加载或离开路由前都会提示，上传中的背景图同样阻止离开。
- 管理会话只在受保护接口明确返回 `401/403` 时因服务端认证失败而清除；网络中断和 `5xx` 会保留当前有效令牌与最近一次用户资料。资料请求会合并并发调用；已有缓存资料时使用 30 秒新鲜度窗口，但每次使用缓存、鉴权 getter 和路由决策前仍会重新核对持久化令牌。其他标签页对认证信封、屏障、固定旧令牌键或用户资料键的变更会触发一次有界同步：同步始终重新读取两把规范认证键，不信任事件 `newValue`，只有完整匹配的 active 对才会更新内存，否则立即清除令牌、用户和资料新鲜度。
- 退出先同步清除当前标签页内存并尽力写入 removed 墓碑，再等待服务端撤销；登录 API 或登录持久化失败只清除该请求所属且仍匹配的当前会话，并尽力持久化退出状态，不影响其他标签页或后续操作建立的新会话。物理边界：如果浏览器拒绝所有持久化写入，旧的完整 active 信封/屏障对可能继续留在介质中，并在存储恢复后的后续刷新中再次被接受；请求仍拥有的当前会话会立即清空，但客户端不能声称跨刷新持久失效。
- 总览页的分类、书签和站点状态独立加载；单个接口失败不会清空其他已成功数据，可只重试失败项。“公开展示”只统计同时位于可见分类内且自身可见的书签。
- 公开首页首次无法取得服务端数据时会明确提示正在展示内置示例并提供重试；已有真实数据不会被后续短暂故障覆盖。站点名称、简介和背景色会同步更新页面标题、描述与 `theme-color` 元信息。

## 搜索引擎管理

后台“搜索引擎”页面支持新增、编辑、删除、排序、启用/停用以及设置默认引擎。搜索地址必须是完整的 HTTP(S) 地址，可以使用 `{keyword}` 作为关键词占位符；占位符不能出现在主机名或 URL 片段中，并且不支持其他占位符。未填写 `{keyword}` 时，前端会自动追加 `q` 查询参数。

API、ZIP 与前端均允许合法路径或查询中的单引号，例如 `https://example.com/o'reilly?q={keyword}`，仍拒绝用户信息、双引号、反斜杠和空白。编辑时清空图标或占位文字会实际清除数据库字段。搜索引擎自动追加排序超过 32 位整数上限时返回 `409` 并回滚，需先调整已有排序值。

公开首页点击搜索框左侧的当前引擎图标，会在搜索框下方展开毛玻璃网格选择面板；面板按后台公开排序展示全部可用引擎和当前选中项，选择后立即更新图标与占位文字并回到搜索输入框。点击面板外部或按 `Esc` 可收起，桌面端使用四列、移动端使用两列，选项较多时在面板内部滚动。

系统保证有数据时只存在一个启用的默认引擎：第一条引擎会自动成为默认项；停用或删除当前默认项时会按排序选择下一条已启用引擎。最后一条引擎不能删除，仅剩的已启用默认引擎也不能直接停用。

公开接口无需登录：

- `GET /api/public/search-engines`：获取已启用的搜索引擎。

以下管理接口需要管理员 JWT：

- `GET /api/admin/search-engines`：获取全部搜索引擎。
- `POST /api/admin/search-engines`：新增搜索引擎。
- `PUT /api/admin/search-engines/{id}`：编辑搜索引擎。
- `DELETE /api/admin/search-engines/{id}`：删除搜索引擎。
- `PUT /api/admin/search-engines/{id}/default`：设为默认并自动启用。
- `PUT /api/admin/search-engines/{id}/visible`：启用或停用。
- `PUT /api/admin/search-engines/sort`：批量更新排序。

## 分类与书签管理

后台“分类管理”支持新增、编辑、显隐、删除和全量排序；排序弹窗可使用上移/下移按钮，也可聚焦条目后按 `Alt + ↑/↓`。仍含书签的分类不能删除，页面会显示实际书签数量并提示先到书签管理中移动或删除关联书签。

后台“书签管理”支持按分类与关键词筛选、桌面表格多选、移动端卡片选择、分类内完整排序和跨分类批量移动：

- 只有明确选择一个分类后才能排序，排序始终包含该分类的全部书签（包括隐藏书签），不会被关键词筛选裁掉。
- 多选状态可跨筛选保留，并显示不在当前筛选中的已选数量；可随时“清空全部”。
- 批量移动只迁移尚未位于目标分类的书签，混合选择中的原目标书签保持原位；成功后重新加载书签和分类计数，失败时保留当前选择。
- 分类和书签图标可留空、填写 1–3 字短标记/Emoji，或填写显式完整的 HTTP(S) 图片 URL。
- 720px 及以下使用移动端管理卡片，排序、选择、显隐、编辑和删除无需横向滚动表格。

相关管理接口均需要管理员 JWT：

- `PUT /api/admin/categories/sort`：批量更新分类排序。
- `PUT /api/admin/bookmarks/sort`：批量更新书签排序。
- `PUT /api/admin/bookmarks/batch-move`：将 `ids` 中尚未属于 `categoryId` 的书签按请求顺序追加到目标分类末尾。

三类批量请求均先完整校验后写入，任一 ID 非法、重复或不存在时整批不生效。排序请求单次最多 1000 项；批量移动为可安全重试的幂等操作，响应只包含本次请求的书签，内置前端会在成功后重新获取完整列表。

## 后台移动端体验

- 901px 及以上保留可折叠的固定侧栏；900px 及以下切换为带遮罩的侧滑菜单，不占用内容宽度。
- 移动菜单支持点击遮罩、选择菜单项或按 `Esc` 关闭；打开时锁定页面滚动并将键盘焦点限制在菜单内，关闭后焦点返回菜单按钮。
- 720px 及以下的管理列表使用移动卡片，筛选、排序、显隐、编辑、删除和批量操作不依赖横向滚动表格。
- 移动端表单、按钮、菜单项和主要操作区域的触控高度不小于 44px；弹窗在短屏内独立滚动，底部操作始终可达。
- 除按原设计保持紧凑比例的左侧品牌与导航栏外，自定义可见文字不使用低于 12px 的固定字号；后台右侧正文、表格、表单和按钮以约 14–16px 为基准，并同步放大 Element Plus 控件高度，避免文字被裁切。
- 页面按 320px 最小视口宽度适配，登录、总览、站点配置、搜索引擎、分类、书签和账号安全页面均避免产生整页横向滚动。

## 自定义链接兼容接口

自定义链接作为后端兼容能力保留，位置只接受 `header` 与 `footer`。当前公开首页主题不渲染头部或底部自定义链接，内置后台也不再提供管理入口；已有数据不会被删除，API 仍可供其他前端主题或外部集成使用。公开列表固定先返回头部链接，再返回底部链接，各组按 `sortOrder`、`id` 排序。

链接地址允许带有效主机名且不含用户信息的 HTTP(S) 地址、单斜杠开头的站内路径，以及非空 `#` 锚点。系统会拒绝危险协议、协议相对地址、反斜杠及包含空白或控制字符的地址。

新增或切换位置后自动追加排序时，每次递增 10；超过 `2147483647` 会返回 `409` 并回滚，避免排序值变成负数。

公开接口无需登录：

- `GET /api/public/custom-links`：获取合法且已启用的头部/底部链接。

以下管理接口需要管理员 JWT：

- `GET /api/admin/custom-links`：获取全部自定义链接。
- `POST /api/admin/custom-links`：新增自定义链接。
- `PUT /api/admin/custom-links/{id}`：编辑自定义链接。
- `DELETE /api/admin/custom-links/{id}`：删除自定义链接。
- `PUT /api/admin/custom-links/{id}/visible`：启用或停用。
- `PUT /api/admin/custom-links/sort`：批量更新排序。

## 数据管理与可移植备份

后台“数据管理”提供与数据库引擎无关的版本化 ZIP 数据包：

- “导出当前数据”包含站点配置、分类、书签、搜索引擎、兼容自定义链接，以及当前 PC/移动端配置实际引用的受管背景图。
- 管理员账号、密码哈希、会话版本、JWT/Redis/数据库密钥、环境变量和日志永远不会进入可移植数据包。
- 导入必须先上传并做零业务写入预检。服务端校验 ZIP 路径、条目数、压缩/展开大小、JSON 严格结构、SHA-256、图片签名、业务约束和引用完整性，再显示新增、更新、删除与不变数量。归档和总展开量各不超过 64MiB，单条目不超过 16MiB，最多 100 个条目；导入与导出的 `manifest.json`、`data.json` 使用相同的 16MiB 上限。
- 通过预检后还需确认已备份并输入确认短语。预检令牌绑定当前管理员、数据包摘要和当前业务版本，从成功发布预检起 15 分钟过期；预检后业务数据发生变化时返回 `409`，必须重新预检。
- 正式导入只替换上述业务数据，不修改管理员账号。数据库写入和导入后内容验证位于同一事务；失败会回滚数据库，并清理本次新建的背景资产。任务执行后不提供会误导用户的“取消”。
- 生产 Redis 模式共享未确认预检的元数据与归档，后端重启或切换到另一实例后仍可确认有效预检。非 Redis 模式把预检保存到本机临时目录，目录保留时可跨后端进程重启读取，但不提供跨主机共享；默认内存 H2 的业务数据仍会随进程重启清空。
- 同一 Redis 存储或本机同一预检目录同时受 8 个槽位与 512MiB 加权预算限制，每份预留按 `2 × ZIP 字节数 + 64MiB` 计入共享归档、工作副本与解压空间；超额返回 `429`。每实例最多同时处理 2 个预检/导入，处理期与活动任务的容量租约为 24 小时并每 30 秒续租。该预算只覆盖受管预检空间，不是整个 JVM 内存或 HTTP multipart 暂存总量上限；等待确认时不长期保留解析对象。
- Redis 任务记录以 24 小时 TTL 保留，不依赖单个后端进程；按任务 ID 或预检令牌查询优先读取 PostgreSQL 中的持久提交标记，已提交时恢复为完成状态。没有提交标记且心跳、租约均已失效时，保留最后的非终态并提示结果暂时无法确认，客户端继续查询；查询不会凭租约失效写入 `FAILED` 或延长记录 TTL。原事务仍可能提交，只有执行路径确认未启动或事务已回滚后才能报告失败。持续无法确定时，应由运维核对原实例、数据库事务及提交标记，确认结果前不要重复导入。Redis 临时状态过期、丢失或不可用均不能证明数据库已回滚，也不替代 PostgreSQL 备份；非 Redis 模式的未提交任务状态仍在进程内存中，重启后丢失。
- 确认响应超时、断网或返回 `5xx` 时，前端保留本次确认身份，按预检 token 只读查询任务及完成/失败结果；不会据此宣称未创建任务或已回滚，也不会自动重新提交确认。暂时查不到任务时仍需核对当前数据，已知 job ID 可继续直接查询。
- 格式 v1 的分类、书签、搜索引擎和兼容链接稳定 key 由导出时数据库 ID 生成；全量导入会创建新的数据库 ID。因此同一旧包在成功导入后再次预检时，部分项目可能显示为新增/删除，而不是不变。内容和关联恢复不受影响，确认时应以资源计数与具体预检内容为准。

同一页面还提供独立的“书签 Markdown 备份”：

- 按分类和后台排序导出全部分类与书签，包括隐藏项、空分类、链接、描述、图标文本、显示状态、推荐状态及打开方式。
- 生成后可直接预览、复制或下载 UTF-8 `.md` 文件；局域网 HTTP 环境下会自动尝试兼容复制，失败时选中预览内容供手动复制。
- Markdown 面向人工阅读、笔记归档和代码仓库留存，不包含管理员、数据库 ID 或内部时间戳，也不能用于系统恢复；需要恢复时仍应使用 ZIP 数据包。
- Markdown 保存完整 URL，链接可能包含私有查询参数。下载文件应妥善保管，不要未经检查提交到公开仓库。

管理接口均要求管理员 JWT：

- `GET /api/admin/data/export`：下载 ZIP 数据包。
- `GET /api/admin/data/bookmarks/markdown`：下载人类可读的 Markdown 书签副本。
- `POST /api/admin/data/import/preview`：上传并预检 ZIP，文件上限 64MiB。
- `POST /api/admin/data/import/{previewToken}/confirm`：确认并创建异步导入任务。
- `GET /api/admin/data/import/jobs/{jobId}`：查询当前管理员创建的任务。
- `GET /api/admin/data/import/jobs/current`：查询当前管理员可恢复的非终态任务。
- `GET /api/admin/data/import/previews/{previewToken}/job`：只读恢复当前管理员本次确认的任务及终态，查不到返回 `404`，不会创建任务。

可移植 ZIP 适合站点内容迁移和管理员自助恢复，但不替代灾难恢复备份。整站恢复还必须保存数据库服务商生成且验证过的 PostgreSQL 备份、外部 Redis 服务端持久化备份及恢复演练、上传卷、源码或发行清单、镜像引用、加密后的环境配置，以及独立加密保存的 `yunlume_database_config` 卷。

## frontend 容器的 Nginx 路由

- `/`：直接提供构建进 frontend 镜像的前端静态文件。
- `/api`：转发到后端容器并保留原始路径。
- `POST /api/admin/auth/login`：按来源 IP 限制为平均每分钟 5 次、允许 5 次突发；超限返回统一 JSON 格式的 `429`。
- `POST /api/install/database/test` 与 `POST /api/install/database/configure`：共享平均每分钟 3 次、允许 3 次突发的 PostgreSQL 配置预算；Redis 的 test/configure 使用独立同额度预算，`POST /api/install/check` 与 `POST /api/install/complete` 再使用另一组预算，避免一次合法六步安装流程消耗掉自己的完成额度。所有连接凭据端点都不写访问日志且请求体只在内存缓冲，匿名状态查询另限为每分钟 30 次。
- `GET /api/health`：按来源 IP 限制为平均每分钟 60 次、允许 20 次突发；后端还会在很短窗口内合并 Redis 实际读写探针，避免公开健康查询放大成无界缓存写负载。Docker 自身直接检查后端，不受 frontend 入口限流影响。
- `POST /api/admin/data/import/preview`：独立允许 66MiB 请求体并使用 120 秒上游读取超时，应用层仍严格限制 ZIP 为 64MiB。
- 默认限流键使用 frontend Nginx 直接看到的连接地址。只有同时启用 `WEB_TRUST_PROXY_HEADERS=true`、收窄 `APP_BIND_ADDRESS` 并匹配 `WEB_TRUSTED_PROXY_CIDR` 时，才恢复可信代理转发的真实客户端地址与原始协议；否则会安全回退到直接连接地址和 frontend 自身协议。错误地关闭恢复会使所有访客共用代理地址的限流额度，错误地扩大信任则会允许伪造来源。
- `/uploads/`：从持久化上传卷直接提供静态文件。
- `/swagger-ui/`、`/v3/api-docs`、`/doc.html`：转发到后端接口文档；生产默认由后端关闭。
- `/healthz`：Nginx 自身健康检查。

## PostgreSQL 初始化、迁移与灾难恢复

`nav-backend/src/main/resources/schema-postgresql.sql` 是唯一权威初始化资源，会打包进后端，并由安装向导初始化用户明确确认的空白外部数据库。`database/migrations/` 保存已经登记的不可修改迁移历史及 SHA-256，不能改写已发布文件。

已配置 PostgreSQL 的后端会在启动时运行 `PostgresqlMigrationRunner`，包括 `prod` profile；关闭 Spring SQL 自动初始化并不关闭该迁移器。当前迁移器只应用固定且校验 SHA-256 的 `20260904_0004_portable_import_operations.sql`：持有数据库事务级 advisory lock，检查迁移登记和结构，在同一事务内执行 DDL、验证结构并登记校验和。已经登记的迁移只校验、不重复执行；全新未配置实例跳过，空库由安装向导初始化。它不会扫描并自动重放整个历史迁移目录，也不负责 PostgreSQL 服务器大版本升级。

升级前先阅读发布说明，停止旧应用的业务写入，在数据库服务商侧创建并验证可恢复备份，保存对应应用版本、实例 UUID、连接配置卷和上传文件。运行账户须保留对项目表的所有权及迁移所需 DDL 权限，然后更新应用并让新后端执行上述受控迁移。校验失败、结构漂移或权限不足会使后端启动失败；当前迁移及其登记在同一事务中回滚。迁移成功后，切回旧应用镜像不会自动撤销数据库 DDL，必须先确认旧版本兼容性；需要恢复数据库时使用升级前匹配备份，并复核实例 UUID 和对应配置、文件。其他数据库的旧结构和一次性转换脚本已从仓库移除，非 PostgreSQL 站点不能直接使用当前发行包升级。

外部服务整站保护至少包括：PostgreSQL 服务商的加密备份与恢复演练、Redis 服务端持久化/备份、后台可移植 ZIP、上传卷、加密后的 `.env`、镜像版本，以及单独加密保存的 `yunlume_database_config` 卷。该卷包含明文 PostgreSQL/Redis 连接凭据、可选 CA 和实例身份标记；仅有数据库快照和上传文件仍不足以恢复应用连接。当前项目没有在该卷丢失后重新关联既有外部服务的受支持流程，恢复配置卷时必须保持目录 `0700`、文件 `0600` 与所有者 `10001:10001`。

`ops/rollback-release.sh <后端镜像摘要引用> <前端镜像摘要引用>` 仅切换已经拉取到本机的应用镜像；两个参数必须分别使用 `ghcr.io/<owner>/yunlume-backend@sha256:<摘要>` 与 `ghcr.io/<owner>/yunlume-frontend@sha256:<摘要>`，不会接受 `latest`、版本标签或其他可变引用。它不修改数据库、`yunlume_database_config` 或上传卷。执行前必须同时提供 `CONFIRM_ROLLBACK=ROLLBACK-RELEASE` 和 `CONFIRM_EXTERNAL_DATABASE_BACKUP=EXTERNAL-DATABASE-BACKUP-VERIFIED`；完成检查会严格要求后端状态为 `UP` 且安装状态仍为 `COMPLETED`，不会把可打开安装页的 `INSTALLING` 误报成成功回滚。代码回滚不能代替数据库或连接配置恢复，也不能保证新 schema 与旧应用兼容。

绝不要对需要保留的环境执行 `docker compose down -v`、`docker volume prune` 或带 `--remove-orphans` 的切换命令。备份至少应再复制一份到异机/对象存储并定期执行恢复演练；只存在同一虚拟机上的备份不能覆盖宿主机磁盘故障。

## 安全提醒

- 上线前务必为外部数据库创建专用最小权限账号，并更换数据库、Redis、管理员密码以及 `JWT_SECRET`，不要提交 `.env` 或 `yunlume_database_config` 的任何副本。
- 管理员密码应遵守账号安全页的强度规则并定期更新；轮换 `JWT_SECRET` 会使所有现有 JWT 失效。
- 公网 HTTP 仅用于无凭据的连通性诊断。首次安装前必须接入受信任域名和 HTTPS 代理，按实际即时代理地址配置可信转发头，并阻止客户端绕过代理直连应用端口；不要通过 HTTP 提交数据库、Redis 或管理员凭据。
- 定期轮换密钥、备份数据库和上传文件，并及时更新基础镜像。
- Swagger/OpenAPI 在生产 profile 默认关闭；只应在受控网络中临时设置 `OPENAPI_ENABLED=true`，使用完立即关闭。
- 背景图片上传和数据包图片预检先校验 MIME、文件魔数、图像格式、尺寸及大小，再实际解码像素；最长边 8000px、总像素 2000 万的限制在解码前执行，缺失或截断的像素数据及解码警告均拒绝。普通 `/api/` 请求体上限为 12MiB，图片业务层最高接受 10MiB。只有数据包预检精确入口允许 66MiB multipart 请求，ZIP 解析器仍限制归档和总展开量为 64MiB、单条目为 16MiB。

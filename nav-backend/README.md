# nav-backend

基于 Java 17、Spring Boot 3、MyBatis-Plus、JWT、BCrypt 的导航站后端。默认使用 PostgreSQL 兼容模式的内存 H2，启动后自动生成本地联调数据；生产环境只连接部署者提供的外部 PostgreSQL 与外部 Redis。

## 快速启动

前置条件：JDK 17+、Maven 3.9+。

```bash
mvn spring-boot:run
```

服务默认监听 `http://localhost:8080`：

- 健康检查：`GET /api/health`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- H2 控制台：`http://localhost:8080/h2-console`

以上 Swagger/OpenAPI 地址在默认 `local` profile 下可用；`prod` profile 默认关闭，只有显式设置 `OPENAPI_ENABLED=true` 才会开放。

H2 JDBC URL 为 `jdbc:h2:mem:navdb`，用户名 `sa`，密码为空。

默认管理员仅用于本地开发：

```text
用户名：admin
密码：Local!Start2026
```

密码不会以明文写入业务数据库。生产新部署默认由 `/install` 网页向导依次接管外部 PostgreSQL 和外部 Redis，重启加载后再创建首位管理员；JWT 密钥仍必须在后端启动前通过环境变量提供。数据库与 Redis 凭据保存在仅后端挂载的所有者私有配置卷中，不进入浏览器存储、URL 或日志。需要无人值守初始化时，也可显式启用 `NAV_BOOTSTRAP_ENABLED=true`，并以传统 `LEGACY_ENV` 连接来源提供外部服务参数。两种方式都复用相同的强密码策略并用 BCrypt 保存，后台改密不会回写环境变量。

生产环境只支持外部 PostgreSQL 14 或更高版本。安装向导要求专用、非超级用户角色，强制 TLS，并通过一次性连接票据、实例 UUID 与后端私有运行配置防止改库和身份漂移。项目不提供或启动 PostgreSQL 服务，也不会代替数据库服务商执行备份与恢复。

## 统一响应

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

HTTP 状态和响应中的 `code` 保持一致。参数错误、未认证、数据不存在、关联冲突和服务器异常均由统一异常处理器返回上述结构。

## API

公开接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| GET | `/api/install/status` | 首次安装状态，不返回内部连接信息 |
| POST | `/api/install/database/test` | 测试外部 PostgreSQL，并签发短期单次 ticket |
| POST | `/api/install/database/configure` | 消费数据库 ticket，按确认初始化空库并持久化后端连接配置 |
| POST | `/api/install/redis/test` | 数据库接管后测试 Redis ACL、TLS 与实际读写，并签发短期单次 ticket |
| POST | `/api/install/redis/configure` | 消费 Redis ticket，保存后端专用配置并按部署策略重启 |
| POST | `/api/install/check` | 检查已接管数据库、上传目录和 Redis |
| POST | `/api/install/complete` | 完成一次性初始化；安装完成后永久拒绝 |
| GET | `/api/public/site-config` | 站点配置 |
| GET | `/api/public/navigation` | 可见分类及书签 |
| GET | `/api/public/search-engines` | 搜索引擎 |
| GET | `/api/public/custom-links` | 头部/底部链接 |

首次安装接口不使用专用安装请求头。`NAV_WEB_INSTALL_ENABLED=false` 时所有安装写入均被拒绝；入口开启时，数据库/Redis 单次 ticket、数据库实例 UUID、完成标记和事务锁继续限制配置提交及首位管理员创建。

认证接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/admin/auth/login` | 登录并签发 JWT |
| POST | `/api/admin/auth/logout` | 无状态退出，客户端丢弃 JWT |
| POST | `/api/admin/auth/logout-all` | 递增会话版本并撤销该管理员的全部旧 JWT |
| PUT | `/api/admin/auth/password` | 校验当前密码、更新 BCrypt 密码并撤销全部旧 JWT |
| GET | `/api/admin/auth/profile` | 当前管理员资料 |

后台接口均需请求头 `Authorization: Bearer <token>`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/PUT | `/api/admin/site-config` | 查询/更新站点配置 |
| GET/POST | `/api/admin/categories` | 分类列表/新增 |
| PUT/DELETE | `/api/admin/categories/{id}` | 修改/删除分类 |
| PUT | `/api/admin/categories/{id}/visible` | 分类显隐 |
| PUT | `/api/admin/categories/sort` | 分类排序 |
| GET/POST | `/api/admin/bookmarks` | 书签列表/新增，GET 支持 `categoryId` |
| PUT/DELETE | `/api/admin/bookmarks/{id}` | 修改/删除书签 |
| PUT | `/api/admin/bookmarks/{id}/visible` | 书签显隐 |
| PUT | `/api/admin/bookmarks/sort` | 书签排序 |
| PUT | `/api/admin/bookmarks/batch-move` | 批量移动书签并追加到目标分类末尾 |
| GET/POST | `/api/admin/search-engines` | 搜索引擎列表/新增 |
| PUT/DELETE | `/api/admin/search-engines/{id}` | 修改/删除搜索引擎 |
| PUT | `/api/admin/search-engines/{id}/visible` | 搜索引擎显隐 |
| PUT | `/api/admin/search-engines/{id}/default` | 设为默认搜索引擎 |
| PUT | `/api/admin/search-engines/sort` | 搜索引擎排序 |
| GET/POST | `/api/admin/custom-links` | 自定义链接列表/新增 |
| PUT/DELETE | `/api/admin/custom-links/{id}` | 修改/删除自定义链接 |
| PUT | `/api/admin/custom-links/{id}/visible` | 自定义链接显隐 |
| PUT | `/api/admin/custom-links/sort` | 自定义链接排序 |
| POST | `/api/admin/upload/image` | 上传并返回受管背景图片 URL |
| GET | `/api/admin/data/export` | 导出版本化业务 ZIP（不含管理员） |
| GET | `/api/admin/data/bookmarks/markdown` | 导出人类可读的全部书签 Markdown 副本 |
| POST | `/api/admin/data/import/preview` | 零写入预检业务 ZIP |
| POST | `/api/admin/data/import/{previewToken}/confirm` | 确认并创建异步导入任务 |
| GET | `/api/admin/data/import/jobs/{jobId}` | 查询当前管理员的导入任务 |

登录请求：

```json
{"username":"admin","password":"Local!Start2026"}
```

站点配置读取结果包含非负整数 `version`。更新时必须把当前版本作为 `expectedVersion` 一并提交，例如：

```json
{
  "siteName": "yunlume",
  "backgroundType": "color",
  "backgroundColor": "#ffffff",
  "fontColor": "#000000",
  "expectedVersion": 3
}
```

更新成功后服务端原子地把版本加 1 并返回新配置；同一旧版本只能成功写入一次。版本缺失或非法返回 `400`，与当前持久化版本不一致或并发条件更新失败返回 `409`，不会覆盖其他会话已经保存的内容。图片背景模式必须提供非空 PC 背景图片，移动端图片可以为空并沿用 PC 图。

显隐请求：

```json
{"visible":false}
```

分类和书签排序请求均为数组：

```json
[
  {"id":1,"sortOrder":10},
  {"id":2,"sortOrder":20}
]
```

分类和书签排序会先校验完整请求，再执行任何写入：列表不能为空且单次最多 1000 项，ID 必须为正数且不能重复，排序值必须为非负整数，并且所有 ID 都必须存在。任一项失败时整批不生效；重复或非法参数返回 `400`，不存在的记录返回 `404`。

排序接口采用“部分批次更新”契约，只修改请求中列出的记录；书签 ID 可以来自不同分类。相同 `sortOrder` 合法，列表读取时继续用 ID 作为稳定的第二排序键。

批量移动书签请求：

```json
{
  "ids": [1, 2],
  "categoryId": 3
}
```

`ids` 必须包含 1–1000 个互不重复的正整数，`categoryId` 必须指向已存在的目标分类，且所有书签必须存在。接口在单个事务内只移动尚未属于目标分类的书签，并按它们在 `ids` 中的先后顺序追加到目标分类当前最大 `sortOrder` 之后，每项递增 10；已经位于目标分类的书签保留原位置且不写库，因此相同请求可以安全重试。混合请求中，原有项保持不变，真正迁入项依次追加。响应 `data` 始终包含请求中的全部书签，按 `ids` 顺序返回完整 `BookmarkVO`。任一校验或写入失败时整批回滚。批量移动和未指定 `sortOrder` 的书签新增会锁定同一目标分类行后再计算末尾位置，避免并发追加取得相同排序值。

删除仍含书签的分类会返回 `409`，防止误删关联数据。

自定义链接请求包含 `title`、`url`、`position`、可选的 `sortOrder` 与 `visible`。`position` 只接受 `header` 或 `footer`；`url` 只接受安全 HTTP(S) 地址、单斜杠站内路径或非空 `#` 锚点。创建时未提供排序值会追加到对应位置末尾，未提供 `visible` 时默认启用。公开接口只返回合法且启用的链接，并按头部、底部及各组排序值稳定排列。

## 环境配置

默认 profile 为 `local`。生产环境使用：

```bash
SPRING_PROFILES_ACTIVE=prod java -jar target/nav-backend-0.1.0.jar
```

| 环境变量 | 默认值/说明 |
|---|---|
| `SERVER_PORT` | `8080` |
| `SPRING_PROFILES_ACTIVE` | `local` |
| `DB_URL` | 仅 standalone `LEGACY_ENV` 兼容的外部 PostgreSQL JDBC URL；向导模式不使用，默认占位不可连接 |
| `DB_USERNAME` | 仅 standalone `LEGACY_ENV` 兼容；无可用生产默认值 |
| `DB_PASSWORD` | 仅 standalone `LEGACY_ENV` 兼容；无生产默认值 |
| `JWT_SECRET` | JWT HS256 密钥，至少 32 字节，生产必须覆盖 |
| `JWT_EXPIRATION_MINUTES` | `120`，仅允许 `5`–`10080` 分钟 |
| `OPENAPI_ENABLED` | `false`；仅控制 `prod` 的 Swagger/OpenAPI 开关 |
| `ADMIN_USERNAME` | `admin` |
| `ADMIN_PASSWORD` | 仅传统环境变量引导使用；必须满足强密码规则；`local` 默认为 `Local!Start2026` |
| `NAV_BOOTSTRAP_ENABLED` | Compose 默认 `false`；设为 `true` 使用环境变量创建首位管理员 |
| `NAV_DEMO_DATA_ENABLED` | `false`（生产）；仅 `local` 默认 `true` |
| `NAV_WEB_INSTALL_ENABLED` | `true`；是否允许未初始化的新库使用网页安装向导 |
| `NAV_DATABASE_SOURCE` | 新部署及向导安装站点升级保持 `UNCONFIGURED`；仅传统环境变量直连使用 `LEGACY_ENV` |
| `NAV_ALLOW_INSECURE_DATABASE_SETUP` | 是否允许 HTTP 提交 PostgreSQL 与 Redis 凭据；默认 `false`，仅可信局域网临时开启 |
| `NAV_DATABASE_CONFIG_FILE` | 安装向导保存的后端专用数据库配置文件；Compose 默认 `/app/config/database.properties` |
| `NAV_DATABASE_CONFIGURED_MARKER_FILE` | 数据库配置提交标记；Compose 默认 `/app/config/database.configured` |
| `NAV_INSTALL_COMPLETED_MARKER_FILE` | 首次安装完成标记；Compose 默认 `/app/config/install.completed` |
| `NAV_DATABASE_CA_FILE` | 外部 PostgreSQL 校验 CA 文件；Compose 默认 `/app/config/postgresql-ca.pem` |
| `NAV_DATABASE_TICKET_TTL_SECONDS` | 数据库测试 ticket 有效期，限制为 30–900 秒，默认 `300` |
| `NAV_DATABASE_AUTO_RESTART` | 配置数据库后是否自动退出并由容器重启，Compose 默认 `true` |
| `NAV_REDIS_SOURCE` | 新部署及向导安装站点升级保持 `UNCONFIGURED`；仅传统环境变量直连使用 `LEGACY_ENV` |
| `NAV_REDIS_CONFIG_FILE` | 安装向导保存的后端专用 Redis 配置文件；Compose 默认 `/app/config/redis.properties` |
| `NAV_REDIS_CONFIGURED_MARKER_FILE` | Redis 配置提交标记；Compose 默认 `/app/config/redis.configured` |
| `NAV_REDIS_CA_FILE` | Redis `CUSTOM_CA` 模式的 CA 文件；Compose 默认 `/app/config/redis-ca.pem` |
| `NAV_REDIS_TICKET_TTL_SECONDS` | Redis 测试 ticket 有效期，限制为 30–900 秒，默认 `300` |
| `NAV_REDIS_AUTO_RESTART` | 配置 Redis 后是否自动退出并由容器重启，Compose 默认 `true` |
| `CORS_ALLOWED_ORIGINS` | 逗号分隔的前端来源 |
| `CACHE_TYPE` | 本地默认 `simple`；`prod` 必须显式为 `redis` |
| `REDIS_HOST` | `LEGACY_ENV` 模式的外部 Redis 主机；安装向导模式不使用此值 |
| `REDIS_PORT` | 外部 Redis 端口，默认 `6379` |
| `REDIS_USERNAME` | ACL 用户名，可选 |
| `REDIS_PASSWORD` | 仅 `LEGACY_ENV` 的外部 Redis 密码；向导模式不使用，不会写入 URL 或日志 |
| `REDIS_DATABASE` | Redis 逻辑库编号，默认 `0` |
| `REDIS_SSL_ENABLED` | 是否启用 TLS，生产默认 `true`；仅受信任私网测试显式设为 `false` |
| `REDIS_CONNECT_TIMEOUT` | 建连超时，默认 `3s`，有效范围大于 0 且不超过 60 秒 |
| `REDIS_READ_TIMEOUT` | 读写命令超时，默认 `3s`，有效范围大于 0 且不超过 60 秒 |
| `APP_UPLOAD_MAX_BYTES` | 单张背景图上限，允许 `1`–`10485760` 字节，默认 `10485760`（10MiB） |
| `APP_UPLOAD_MAX_TOTAL_BYTES` | 受管背景图片总量上限，默认 `1073741824`（1GB） |
| `APP_UPLOAD_MAX_FILES` | 受管背景图片数量上限，默认 `500` |
| `APP_UPLOAD_ORPHAN_GRACE_MS` | 未引用图片保留时间，默认 `86400000`（24小时） |
| `APP_UPLOAD_CLEANUP_INTERVAL_MS` | 清理间隔，默认 `21600000`（6小时） |
| `APP_UPLOAD_CLEANUP_INITIAL_DELAY_MS` | 启动后首次清理延迟，默认 `60000`（1分钟） |

`src/main/resources/schema-postgresql.sql` 是打包进后端、由网页安装向导初始化用户明确确认的空白外部数据库的安装资源，包含完整结构、约束、种子数据及 `schema_migration` 登记的迁移文件名与 SHA-256。`prod` profile 不自动执行 DDL，默认 `NAV_DEMO_DATA_ENABLED=false`；网页安装只初始化站点名称和首位管理员，不会因为业务表为空而补写演示数据。传统运行时引导只有在显式启用、整个 `sys_user` 表为空且安装完成标记为空时才会创建管理员。

每个已登记迁移文件的 SHA-256 不得再修改。`20260812_0001_postgresql_baseline.sql` 是完整新结构的稳定基线标记，`20260814_0002_web_install_state.sql` 增加永久网页安装标记，`20260815_0003_install_instance_identity.sql` 增加数据库实例 UUID。后续 schema 版本变更必须先在数据库服务商侧备份，再按受控流程执行并校验实例 UUID。

后端私有配置卷属于安装状态与数据库实例身份，含 PostgreSQL/Redis 明文连接凭据、可选 CA 和实例身份，必须加密异机保存并维持目录 `0700`、文件 `0600`；仅恢复数据库服务商备份、上传卷或可移植 ZIP 不能恢复应用连接，卷丢失后当前项目也没有受支持的既有外部库重新关联流程。

生产 profile 会在启动阶段校验外部 Redis 的缓存类型、主机、端口、密码、逻辑库、超时、配置摘要、数据库实例 UUID 和文件权限。真正 fresh 的 `UNCONFIGURED` 部署可以无 Redis 环境变量启动；数据库接管后状态变为 `REDIS_REQUIRED`，完成 Redis 步骤并重启前不会开放管理员初始化。TLS 支持系统信任、安装向导上传的 `CUSTOM_CA` 和显式确认的受信任私网明文三种模式；明文模式会在测试与启动时重新校验 RFC1918/ULA 地址，并固定连接数值地址。Redis ACL 至少需要当前逻辑库的 `PING`、`SELECT`、`SET`、`GET`、`DEL` 以及 `nav:*` key 权限，无需管理命令。`/api/install/check` 与健康检查都会用短 TTL 随机键验证实际读写权限，公开健康检查会在短窗口合并探针；安装完成后 Redis 配置丢失、损坏或断线均失败关闭，绝不会重新开放安装流程。

新密码必须为 12–72 个字符、UTF-8 不超过 72 字节、不含空白，在大写字母、小写字母、数字和符号中至少包含三类，且不得包含用户名或复用当前密码。改密与 `logout-all` 均会使当前设备和其他设备上的旧 JWT 立即失效。

## 可移植数据包

版本 1 ZIP 由 `manifest.json`、`data.json` 和可选 `assets/*.jpg|png` 组成。业务数据使用包内稳定 key 表达关联，不包含数据库账号、管理员、密码哈希、令牌版本或其他运行秘密。导出会在一致性快照中读取业务表，并只打包站点配置实际引用且符合受管命名规则的背景图。

预检限制为归档 64MiB、总展开量 64MiB、单条目 16MiB、最多 100 个条目，并拒绝绝对路径、`..`、反斜杠、重复/大小写碰撞、目录条目和未知顶层文件。清单、数据与资产都核对声明大小和 SHA-256；JSON 拒绝未知字段及尾随内容；图片重新核验签名、格式、尺寸和像素数。

有效预检令牌保存 15 分钟，绑定管理员 ID、归档摘要与当前业务 revision。确认和事务开始时都会重新核对；任何并发 CRUD 都会使旧预检返回 `409`。正式导入使用 `SERIALIZABLE` 事务，保留 `sys_user`，替换站点配置、分类、书签、搜索引擎和兼容链接；写入后完整内容验证也在提交前执行。新背景资产在事务未提交时通过事务同步清理。

异步任务状态仅保留在单个后端进程内并在终态 24 小时后清理；服务重启后查询旧 job 会返回 `404`。客户端必须把这种情况显示为“结果未知并需核对”，不能推断为成功或回滚。运行环境如扩展为多个后端副本，应先把预检和任务状态改为共享持久化存储。

`GET /api/admin/data/bookmarks/markdown` 是独立的只读辅助备份。它在 `REPEATABLE_READ` 一致快照中读取全部分类与书签，包含隐藏项和空分类，并按 `sort_order, id` 稳定输出显示状态、排序、描述、图标文本及完整 HTTP(S) URL。响应为禁止缓存的 UTF-8 `text/markdown` 附件，不包含管理员、数据库 ID 或业务记录时间戳，也不能反向导入；完整恢复仍使用版本化 ZIP。

格式 v1 的列表稳定 key 由导出时数据库 ID 生成，而导入会为业务记录生成新的 identity 值；因此同一个旧包在成功导入后再次预检，列表差异可能表现为新增/删除。该限制不影响内容及分类关联恢复，但未来格式若要求跨多次导入保持语义 diff，应为业务对象增加与数据库 ID 解耦的持久 UUID。

## 上传存储生命周期

`POST /api/admin/upload/image` 只接受经过文件大小、声明 MIME（如有）、文件魔数、ImageIO 实际格式、尺寸及像素数校验的 JPG/PNG 背景图；不信任客户端原始文件名或扩展名。文件以 `/uploads/backgrounds/{32位小写十六进制}.{jpg|png}` 形式原子写入，只有这种服务端生成名称会参与容量统计和自动回收。

- 背景图片默认单文件 10MiB、目录总量 1GB、文件数 500；超过总量或数量返回 `507 Insufficient Storage`。全局 multipart 为数据包预检保留 66MB 请求余量，但图片业务层独立拒绝大于 `10485760` 字节的值。
- Compose 会把同一 `APP_UPLOAD_MAX_BYTES` 作为 `VITE_UPLOAD_MAX_BYTES` 编译进 web 镜像的前端提示；修改该值后必须同时重新构建 web 和 backend，不能只重启现有容器。
- 每次上传前先尝试清理，定时任务默认在启动 1 分钟后执行，之后每 6 小时执行。
- `site_config.background_image` 与 `mobile_background_image` 当前引用的受管文件不会删除；未引用文件经过默认 24 小时宽限期后才可回收。
- 读取站点配置引用失败时清理整次跳过；符号链接和不符合受管命名规则的文件不会被当作普通受管图片处理。

入口 Nginx 对登录 POST 设置每来源 IP 平均每分钟 5 次、允许 5 次突发的限速；PostgreSQL test/configure、Redis test/configure、安装检查/完成分别使用互相独立的平均每分钟 3 次且允许 3 次突发预算，避免合法七步流程消耗掉自己的完成额度；匿名安装状态查询限为每分钟 30 次。公开 `/api/health` 限为每分钟 60 次、允许 20 次突发，后端还会在 5 秒窗口内合并 Redis 实际读写探针。超限均返回统一 JSON `429`。这是部署网关策略，直接访问后端端口时不会生效。

`web` 镜像内的 Nginx 默认忽略客户端传入的 `X-Forwarded-For` 与 `X-Forwarded-Proto`。接入 1Panel OpenResty 等 HTTPS 代理时，应把 `APP_BIND_ADDRESS` 收窄到明确的 loopback/私网地址并阻止客户端绕过代理，再设置 `WEB_TRUST_PROXY_HEADERS=true`，将 `WEB_TRUSTED_PROXY_CIDR` 限定为 web 实际看到的即时代理 `/32`、`/128` 或最窄隔离网段。配置校验会拒绝全网信任和泛监听组合；只有可信来源的真实客户端地址与原始协议才会传给后端并参与限流。不要为了方便扩大信任范围。

## 构建与测试

```bash
mvn test
mvn clean package
docker build -t nav-backend .
```

测试覆盖健康检查、登录、改密、全会话撤销、JWT 版本鉴权、公开站点配置和导航种子数据，以及分类/书签 CRUD、显隐、关联删除冲突、原子排序、批量移动、搜索引擎、自定义链接、图片上传、PostgreSQL 兼容约束和可移植 ZIP 的安全预检/事务回滚/API 权限。

# yunlume 全仓库问题审计清单

## 1. 审计基线

- 审计日期：2026-09-01
- 审计分支：`main`
- 审计提交：`b875ea37a7ec40d68dd697c606fe82049179994a`
- 审计范围：前端、后端、数据库与迁移、Docker/宿主机安装器、运维脚本、GitHub Actions、测试和项目文档
- 审计方式：只读源码检查、关键调用链交叉复核、Shell/Python/JSON 静态检查及当前 GitHub Actions 结果核对

本次确认 19 项高置信问题或逻辑缺口，其中高优先级 4 项、中优先级 9 项、低优先级或待确认设计问题 6 项。当前未发现明显的管理员 JWT 绕过、SQL 注入、已提交真实密钥或旧品牌字符串残留。

## 2. 问题索引

| ID | 严重度 | 范围 | 摘要 | 状态 |
|---|---|---|---|---|
| W-001 | 高 | 运维 | 手工镜像回滚健康失败时不会恢复原镜像 | 待修复 |
| W-002 | 高 | 发布 | Docker Release 只固定可变标签，没有固定镜像 digest | 待修复 |
| W-003 | 高 | 后端 | 公开导航接口存在 `2N+1` SQL | 待修复 |
| W-004 | 高 | 数据导入 | 导入确认响应丢失后无法恢复已启动任务 | 待修复 |
| W-005 | 中 | Docker 安装 | 私网地址绑定与安装器健康探测冲突 | 待修复 |
| W-006 | 中 | 安装升级 | 未重复传入 `--port` 会把自定义端口重置为 8080 | 待修复 |
| W-007 | 中 | 备份恢复 | 管理 API 接受的数据不一定能通过自身 ZIP 导入 | 待修复 |
| W-008 | 中 | 公开前端 | API 黑洞时首页最长可空白约 51 秒 | 待修复 |
| W-009 | 中 | 前端上传 | 合法 10 MiB 背景图仍使用 12 秒总超时 | 待修复 |
| W-010 | 中 | 后台前端 | 分类和书签显隐开关存在请求乱序竞态 | 待修复 |
| W-011 | 中 | 发布 | 后发布的较低版本也会被强制设为 `latest` | 待修复 |
| W-012 | 中 | 安装安全 | HTTPS 判断直接信任客户端 `X-Forwarded-Proto` | 待修复 |
| W-013 | 中 | 安装安全 | 自定义安装目录存在 root 临时文件符号链接覆盖边界 | 待修复 |
| W-014 | 低 | 搜索 | 带 fragment 且无占位符的搜索模板不会发送关键词 | 待修复 |
| W-015 | 低 | 浏览器兼容 | `localStorage` 异常可能中断启动或形成半登录状态 | 待修复 |
| W-016 | 低 | 登录会话 | 退出接口失败后令牌已清除但页面不会跳转 | 待修复 |
| W-017 | 低 | 门户样式 | 背景高度锁定后无法适配横竖屏或窗口变高 | 待确认产品取舍 |
| W-018 | 低 | 架构 | Redis 是生产硬依赖，但没有实际业务缓存 | 待确认架构取舍 |
| W-019 | 低 | 文档 | 验收版本和模块版本信息已经滞后 | 待修复 |

## 3. 高优先级问题

### W-001 手工镜像回滚健康失败时不会恢复原镜像

- 位置：`ops/rollback-release.sh:62-73`、`ops/rollback-release.sh:75-95`、`ops/lib/common.sh:12-15`
- 现象：脚本只注册 `ERR` trap，健康检查失败则调用 `die`；`die` 使用显式 `exit 1`，Bash 不会因此触发 `ERR` trap。
- 触发条件：目标容器启动命令本身成功，但容器缺失、后端不健康或前端不健康。
- 影响：此时 `.env` 已切换到目标镜像，坏镜像和不健康容器会留在现场，原镜像不会自动恢复。
- 次生问题：恢复函数忽略旧版本重启失败，也不验证恢复后的健康状态，却会删除备份文件。
- 修复方向：使用统一 `EXIT` 事务清理或让所有失败路径返回非零并进入同一恢复函数；只有确认原服务恢复健康后才能删除备份，并单独报告“目标失败”和“恢复失败”。

### W-002 Docker Release 没有固定镜像内容

- 位置：`ops/create-release-manifest.py:34-46`、`.github/workflows/publish-images.yml:20-55`、`.github/workflows/publish-images.yml:214-244`
- 现象：`release-manifest.json` 只记录 `ghcr.io/...:X.Y.Z`，没有记录前后端 OCI manifest digest。
- 触发条件：标签工作流部分成功后重跑、删除同名 GitHub Release 后重跑，或 GHCR 中的版本标签被手工覆盖。
- 影响：不修改 Release 资产及其 SHA-256，也能让同一个正式安装器拉取到不同镜像；Docker 模式的可复现安装和可信回滚不成立。
- 修复方向：清单记录前后端多架构镜像的 `name@sha256:...`；安装器按 digest 拉取；发布预检同时拒绝已存在的正式版本镜像标签，版本标签仅作为展示别名。

### W-003 公开导航接口存在 `2N+1` SQL

- 位置：`nav-backend/src/main/java/com/example/nav/module/publicdata/service/impl/PublicDataServiceImpl.java:47-55`、`nav-backend/src/main/java/com/example/nav/module/category/service/impl/CategoryServiceImpl.java:181-183`
- 现象：先查询全部可见分类，分类转 VO 时逐分类执行书签 COUNT，随后公开导航组装时又逐分类查询可见书签。
- 查询量：`1` 次分类查询 + `N` 次 COUNT + `N` 次书签查询。
- 触发条件：公开访问 `/api/public/navigation`；导入校验允许单类资源最多 10,000 项。
- 影响：在受支持的数据范围内，单次匿名请求理论上可触发约 20,001 次 SQL，造成高延迟、数据库连接池耗尽和公开接口可用性下降。
- 修复方向：一次查询分类、一次批量查询所有相关书签，按 `categoryId` 内存分组；公开响应不需要的 COUNT 不应执行。缓存只能作为后续优化，不能代替消除 N+1。

### W-004 导入确认响应丢失后无法恢复任务

- 位置：`nav-frontend/src/components/admin/DataImportPanel.vue:168-215`、`nav-backend/src/main/java/com/example/nav/module/datapackage/service/PortableDataPackageService.java:199-215`
- 现象：服务端先消费预检令牌并创建随机 `jobId`，前端只有成功收到确认响应后才把 `jobId` 写入 `sessionStorage`。
- 触发条件：服务端已经创建任务，但确认请求响应超时、连接中断或响应在网络中丢失。
- 影响：导入可能仍在后台执行，前端却不知道任务 ID；重试时原预检令牌已被消费，用户无法查询结果，也无法判断是否应重新导入。
- 修复方向：确认接口增加幂等键并允许用预检令牌查询已创建任务，或提供当前管理员最近/运行中任务查询接口；前端在结果未知时进入“确认中”恢复流程，不能直接允许重复操作。

## 4. 中优先级问题

### W-005 私网地址绑定与安装器健康探测冲突

- 位置：`README.md:211-218`、`docker-compose.yml:96`、`install.sh:812`、`install.sh:947`
- 现象：文档允许把 `APP_BIND_ADDRESS` 改为明确的私网地址，但安装器升级和回滚固定探测 `127.0.0.1`。
- 触发条件：按 README 配置 1Panel/OpenResty，端口只绑定宿主机私网 IP，随后再次运行 Docker 安装器升级。
- 影响：实际服务已经在私网地址正常启动，安装器仍会误判健康失败；回滚验证也可能把已经恢复的旧服务误报为失败。
- 修复方向：从现有 `.env` 读取并规范化探测地址；`0.0.0.0` 映射到 `127.0.0.1`，明确私网地址则探测该地址，同时覆盖 IPv6。

### W-006 常规升级会重置自定义端口

- 位置：`install.sh:8-16`、`install.sh:263-265`、`install.sh:919-924`、`install.sh:1320`
- 现象：`APP_PORT` 启动时固定为 8080，没有记录用户是否显式传入 `--port`。
- 触发条件：首次安装使用自定义端口，后续按普通升级命令运行安装器而没有再次传入 `--port`。
- 影响：Docker `.env` 会被改成 8080；宿主机 Nginx 也会重新渲染为 8080。已有代理、防火墙和访问地址可能立即中断，安装器仍可能报告成功。
- 修复方向：增加 `PORT_EXPLICIT` 标记；升级且未显式指定端口时，从现有 Docker `.env` 或宿主机 Nginx 配置继承端口。

### W-007 导出与导入校验不一致

- 位置：`nav-backend/src/main/java/com/example/nav/module/bookmark/dto/BookmarkCreateDTO.java:17-20`、`nav-backend/src/main/java/com/example/nav/module/site/dto/SiteConfigUpdateDTO.java:15-39`、`nav-backend/src/main/java/com/example/nav/module/datapackage/service/PortableDataValidator.java:329-368`
- 现象：普通管理 API 可以保存带 user-info 的书签 URL，以及只经过长度限制的 `publishUrl`、`musicUrl`；ZIP 导入器会拒绝相同值。
- 触发条件：通过管理 API 保存上述数据，导出 ZIP 后再预检同一个 ZIP。
- 影响：系统生成的备份可能不能被系统恢复，灾难恢复承诺不闭环。
- 修复方向：写入 API、导出自检和导入预检复用同一套 URL 校验器；修复前应明确哪些已有数据需要兼容或迁移。

### W-008 API 黑洞时公开首页可长时间空白

- 位置：`nav-frontend/src/api/request.ts:11-17`、`nav-frontend/src/utils/publicRequestRetry.ts:1-42`、`nav-frontend/src/views/portal/PortalHome.vue:130-136`、`nav-frontend/src/views/portal/PortalHome.vue:167-207`
- 现象：首页等站点配置、导航和搜索引擎请求全部结束后才渲染任何可见 fallback。
- 最坏等待：4 次请求 × 12 秒超时 + 300/900/1800 毫秒退避，约 51 秒；路由安装状态检查还可能额外等待约 2.5 秒。
- 触发条件：API 连接被静默丢弃、上游持续无响应，而不是快速返回连接拒绝或 502。
- 影响：用户看到接近空白的页面，已有安全 fallback 也无法及时提供基本导航。
- 修复方向：首次立即渲染骨架或安全 fallback，后台异步替换；公共接口使用更短的独立超时，并限制首次重试总预算。

### W-009 背景图片上传超时与允许大小不匹配

- 位置：`nav-frontend/src/components/admin/BackgroundImageField.vue:21-27`、`nav-frontend/src/api/upload.api.ts:11-14`、`nav-frontend/src/api/request.ts:11-17`
- 现象：前后端允许最多 10 MiB 图片，但上传 API 没有覆盖 Axios 的 12 秒默认总超时。
- 触发条件：上行速度低于约 0.83 MiB/s，或服务端图片格式、尺寸检查稍慢。
- 影响：文件大小与格式均合法，仍会由浏览器主动取消，公网慢速环境无法可靠上传。
- 修复方向：为图片上传设置与大小上限匹配的独立超时，并提供上传进度；超时要覆盖上传和服务端处理时间。

### W-010 分类和书签显隐切换存在并发竞态

- 位置：`nav-frontend/src/views/admin/CategoryManageView.vue:106-113`、`nav-frontend/src/views/admin/BookmarkManageView.vue:157-163`
- 现象：切换后立即发送请求，请求期间不禁用控件，也没有请求序号、取消或响应值回填；失败时直接反转当前值。
- 触发条件：慢网环境下快速连续切换同一行，两个请求逆序完成，或旧请求在新请求后失败。
- 影响：服务端最终状态可能与界面相反；失败分支还可能把新状态错误反转。
- 修复方向：同一资源的请求串行化或使用最新请求序号；控件等待期间禁用；成功使用服务端响应，失败恢复请求发出前的明确快照。

### W-011 较低版本也能被强制设为 `latest`

- 位置：`.github/workflows/publish-images.yml:263-278`、`.github/workflows/publish-images.yml:363-382`
- 现象：标签校验只检查 `vX.Y.Z` 格式，不检查是否高于当前正式版本，但创建 Release 时无条件传入 `--latest`。
- 触发条件：当前已有较高版本，之后补发一个此前不存在的较低版本标签。
- 影响：`releases/latest/download/install.sh` 会指向较低版本；同兼容代际下，文档中的“升级最新版”可能实际执行降级。
- 修复方向：发布前比较最高正式版本并强制单调递增；确需补发旧版本时不得标记 latest，且应使用明确的人工流程。

### W-012 HTTPS 判断信任未验证的代理头

- 位置：`nav-backend/src/main/java/com/example/nav/module/install/service/DatabaseSetupService.java:157-165`、`nav-backend/src/main/java/com/example/nav/module/install/service/RedisSetupService.java:76-84`
- 现象：只要请求头第一项为 `X-Forwarded-Proto: https`，服务就把普通 HTTP 请求视为安全连接。
- 触发条件：`NAV_ALLOW_INSECURE_DATABASE_SETUP=false`，同时后端端口被直接访问或被非受信代理转发。
- 影响：客户端可以伪造请求头绕过服务层 HTTPS 强制检查。官方 Nginx 拓扑会缓解，但该安全边界未在后端自身成立。
- 修复方向：只使用经过框架可信代理处理后的安全状态，或显式校验即时代理来源；不要直接信任任意客户端请求头。

### W-013 自定义安装目录存在临时文件符号链接覆盖边界

- 位置：`install.sh:289-335`、`install.sh:885-887`、`install.sh:957-959`、`install.sh:1338-1342`
- 现象：安装目录边界检查只拒绝符号链接和非目录，不验证 root 所有权及组/其他用户写权限；受管目标检查也没有覆盖相应 `.tmp` 文件。
- 触发条件：root 把 `--install-dir` 指向其他本地用户可写的已有目录，攻击者预先创建或竞态替换 `VERSION.tmp` 等符号链接。
- 影响：root 的 shell 重定向可能截断并覆盖符号链接指向的任意文件。
- 修复方向：要求安装目录及已存在祖先满足可信所有者/权限边界；临时文件使用安全创建方式，并在写入、chmod、rename 前验证不是符号链接且仍位于受信目录。

## 5. 低优先级与待确认设计问题

### W-014 带 fragment 的搜索模板不会发送关键词

- 位置：`nav-frontend/src/utils/url.ts:21-32`
- 现象：无 `{keyword}` 时，代码直接在整个模板末尾追加 `?q=` 或 `&q=`。
- 示例：`https://example.com/search#section` 会变成 `https://example.com/search#section?q=Vue`。
- 影响：`q` 位于 URL fragment 中，只在浏览器端存在，搜索服务器收不到关键词。
- 修复方向：使用 `URL` API 修改 `searchParams`，保留原 fragment；补充带 query、fragment、空 query 和特殊字符的测试。

### W-015 `localStorage` 异常处理不完整

- 位置：`nav-frontend/src/utils/storage.ts:1-25`、`nav-frontend/src/stores/auth.store.ts:17-38`
- 现象：令牌和用户信息的读取、写入、删除均可能直接抛出 `SecurityError` 或 `QuotaExceededError`；现有 `catch` 只处理 JSON 解析失败。
- 触发条件：浏览器禁用持久化存储、受限 iframe/隐私上下文或存储配额异常。
- 影响：认证 Store 初始化可能阻断初始路由；登录成功后存储写入失败，内存状态已改变但动作抛错，形成半登录状态。
- 修复方向：封装安全存储适配器，读取失败返回空值，写入失败回滚内存认证状态并给出明确提示。

### W-016 退出失败后页面停留在后台

- 位置：`nav-frontend/src/stores/auth.store.ts:76-81`、`nav-frontend/src/components/admin/AdminHeader.vue:36-46`
- 现象：Store 在 `finally` 中清除本地会话，但接口网络错误或 5xx 会继续向调用方抛出；调用方下一行跳转不会执行。
- 影响：后台数据继续留在当前 DOM，页面看起来仍在后台，但后续请求已没有令牌，同时产生未处理 Promise 拒绝。
- 修复方向：本地退出完成后无论服务端结果如何都跳转登录页；服务端退出失败作为非阻断提示单独处理。

### W-017 背景高度锁定牺牲了真实视口变化

- 位置：`nav-frontend/src/views/portal/PortalHome.vue:54-58`、`nav-frontend/src/views/portal/PortalHome.vue:148-150`、`nav-frontend/src/styles/portal/_layout.scss:40-52`
- 现象：页面挂载时只记录一次 `window.innerHeight`，之后完全不更新背景伪元素高度。
- 已解决的问题：软键盘出现时不会重新缩放背景。
- 新问题：横竖屏旋转、分屏变化或桌面窗口变高后，背景仍使用首次高度，可能出现下部无背景图片或裁切比例错误。
- 修复方向：如果产品要求旋转适配，只在真实方向/宽度变化时更新稳定视口高度，忽略软键盘导致的纯高度变化；若接受当前取舍，应在测试和文档中明确。

### W-018 Redis 依赖与业务用途不闭环

- 位置：`nav-backend/src/main/java/com/example/nav/common/config/ProductionRedisConfigurationValidator.java:36-53`、`nav-backend/src/main/java/com/example/nav/module/health/controller/HealthController.java:94-106`
- 现象：生产环境强制 `CACHE_TYPE=redis`，Redis 读写失败会让整体健康检查失败；但业务代码没有 `@Cacheable`、`@CacheEvict`、`@CachePut` 或其他实际业务缓存读写。
- 影响：Redis 故障会阻塞服务健康与升级，而公开导航的高查询开销并未得到缓存保护；新增了硬依赖，却没有产生对应业务收益。
- 修复方向：需要明确架构选择：若 Redis 是当前必要依赖，应为公共数据建立缓存及完整失效策略；若只是未来预留，应取消生产硬依赖和健康失败绑定。

### W-019 版本与验收文档滞后

- 位置：`ACCEPTANCE.md:5-8`、`jihua.md:16`、`nav-frontend/package.json:4`、`nav-backend/pom.xml:16`
- 现象：验收文档仍把 `v1.0.8` 写成当前正式版本，实际仓库已经发布 `v1.0.10`；前端包版本仍为 `1.0.0`，后端 Maven 版本仍为 `0.1.0`。
- 影响：部署者无法从仓库文档判断当前验收基线，构建产物版本和正式 Release 版本也容易混淆。
- 修复方向：更新正式版本与验收记录；决定前后端模块版本是否跟随发行版本，并在发布流水线中自动校验，避免继续人工漂移。

## 6. 已确认的正常项

- 全仓库未检索到旧品牌字符串残留。
- Docker 前端、后端使用独立 Dockerfile、镜像名称和发布任务。
- 前后端公开/管理接口映射未发现明显错位。
- 管理员 JWT、角色与 token version 校验链路未发现直接绕过。
- PostgreSQL schema 与已登记迁移校验和一致。
- 未发现提交到仓库的真实生产密码、私钥或令牌。
- 当前工作区在审计前保持干净。

## 7. 已执行验证与边界

- `git diff --check`：通过。
- `install.sh`、`ops/*.sh`、`ops/lib/*.sh` Bash 语法检查：通过。
- `ops/create-release-manifest.py` Python 语法检查：通过。
- `package.json` 与 lockfile JSON 解析：通过。
- 当前提交的 GitHub Actions 已通过前端测试与生产构建、依赖审计、后端测试与打包、安装器回归和前后端镜像发布：<https://github.com/Kcmose/yunlume/actions/runs/33469885941>。
- 当前正式 Release 已到 `v1.0.10`：<https://github.com/Kcmose/yunlume/releases/tag/v1.0.10>。

现有 CI 通过不代表上述问题不存在：多数问题位于超时、请求乱序、响应丢失、非默认端口、私网绑定、部分发布失败及脚本错误恢复等当前测试未覆盖的运行时边界。

本轮没有执行本地依赖安装、完整构建、实际安装或生产部署，也没有修改任何业务代码。

## 8. 推荐修复顺序

1. 修复 W-001、W-005、W-006，先保证升级、失败恢复和自定义网络配置可靠。
2. 修复 W-002、W-011，建立真正不可变、单调递增的正式发布链路。
3. 修复 W-003，把公开导航查询数降为常数级。
4. 修复 W-004，为破坏性导入建立幂等确认和任务恢复能力。
5. 修复 W-007，统一在线写入、导出和导入校验，恢复备份闭环。
6. 修复 W-008、W-009、W-010，解决主要前端可用性和并发一致性问题。
7. 处理 W-012 至 W-019，并对 W-017、W-018 做明确产品/架构决策。

修复时应逐项补充针对性回归测试，不建议把全部问题合并为一次大范围重构。

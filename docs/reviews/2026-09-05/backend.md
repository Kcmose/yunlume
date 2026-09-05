# 后端与数据库只读审查

## 范围、证据与限制

审查对象为 `<repository-root>/nav-backend`、`<repository-root>/database` 的工作树现状，包含未跟踪源码，不限 diff。逐文件范围和阅读深度见 `backend-files.json`；生产 Java、SQL、配置按完整文件阅读并对候选问题查调用方，部分测试仅做结构、断言及关联路径静态扫描，**不宣称全部测试已逐行深读，也不宣称已完成动态全量验证**。覆盖清单显式列出未深读文件。

没有修改仓库、暂存、提交、推送、部署，也没有运行本地 Maven/Java 编译。以下均为源码控制流证据，不是已执行的集成复现；建议测试尚待授权远程环境执行。没有把读过的文件当作“测试通过”。

保留 5 个实质问题：1 个 P1、4 个 P2。P1 表示常见部署阻断；P2 表示条件触发的功能/数据/资源缺陷。未为了凑数报告泛化加固建议。

## B1 — P1：HTTPS 同源请求经过 HTTP 反代后会被 CORS 提前拒绝

- **位置**：`nav-backend/src/main/java/com/example/nav/common/config/CorsConfig.java:15–26`；`nav-backend/src/main/java/com/example/nav/security/SecurityConfig.java:31–33`；`nav-backend/src/main/resources/application.yml:80`。
- **触发路径**：浏览器访问 `https://nav.example`，发送 `Origin: https://nav.example` 的登录/安装 POST；TLS 终止代理以 HTTP 转发到后端，保留 Host，即使正确设置 `X-Forwarded-Proto: https`。仓库未配置 `server.forward-headers-strategy`、`ForwardedHeaderFilter` 或 RemoteIpValve。Servlet 所见 scheme 仍为 `http`，Spring `CorsUtils.isCorsRequest` 比较 scheme/host/port 后认定跨域；默认 allowed origins 只有 HTTP localhost/127.0.0.1，最终 403 `Invalid CORS request`，业务控制器不会执行。
- **影响**：按默认配置部署 HTTPS 同源站点，页面 GET 可正常加载但登录、安装及带 Origin 的写请求失败。
- **调用方反证**：`SecureTransportPolicy.java:25–36` 只在业务层判断可信 peer 的 XFP，不包装 request 或修改 scheme，无法补救前置 CorsFilter。`OPTIONS permitAll` 也不跳过 CorsFilter。`README.md:310` 所称同源仅需保留完整 Host 不足以解决 scheme 不一致。显式配置实际 HTTPS origin 或安全启用转发头处理可避开本问题；直接后端 HTTPS 不触发。**此项与某代理把 XFP 覆盖为 http 是不同原因，不能合并为同一修复。**
- **外部实现核验**：Spring Framework v6.2.8 `CorsUtils.java:41–52` 明确直接读取 request scheme/name/port；父审查保存原文于 `docs/reviews/2026-09-05/spring-CorsUtils.java`。参考 https://github.com/spring-projects/spring-framework/blob/v6.2.8/spring-web/src/main/java/org/springframework/web/cors/CorsUtils.java 。
- **建议**：在受信任、会清除客户端伪造转发头的边界代理之后，统一还原外部 scheme/host/port；或安装时明确生成实际 HTTPS origin 白名单。不要用全开放 origins 代替正确同源识别，也不要无条件信任任意公网 XFP。
- **现有测试遗漏**：`SecureTransportPolicyTest` 只直接调用业务策略；没有通过完整 Spring Security 链模拟 HTTP backend + HTTPS Origin + 正确 XFP。增加真实 Origin 的登录、数据库测试、Redis 测试和普通写接口回归。
- **confidence**：高。**风险**：高可用性风险；修复若无限信任 forwarded headers 则引入安全风险，需同时测试非可信 peer。

## B2 — P2：孤儿图片 GC 与配置保存没有共同同步点，可删除刚成为当前背景的文件

- **位置**：`nav-backend/src/main/java/com/example/nav/module/upload/service/BackgroundImageStorageService.java:315–361`；`nav-backend/src/main/java/com/example/nav/module/site/service/impl/SiteConfigServiceImpl.java:42–99`。
- **触发路径**：F 是已超过 grace 的受管文件、当前未引用但仍存在。GC 在 318 行读取旧的引用集合；管理员此时通过 `PUT /api/admin/site-config` 提交 F 为背景并成功提交；GC 随后继续用旧集合，在 361 行删除 F。也可由上传前的 `cleanupOrphansLocked` 触发，不仅是定时任务。
- **影响**：配置成功返回且数据库/公共缓存指向 F，磁盘文件却被删除，页面背景 404；之后 portable snapshot 对当前受管图片的存在性检查失败，导出/预检也会被阻断。
- **调用方反证**：`storageLock` 只围住图片存储服务，SiteConfigService 不引用此锁或服务，乐观 `expectedVersion` 只保护数据库配置，不保护文件 GC。grace 以文件 mtime 计算，不能保护一个被重新选用的旧文件。此处不是建议删除 grace、路径/引用校验等防御。
- **建议**：为“受管资产认领/解除引用/删除”建立共同的事务性资产状态与锁协议；删除时在同一协调边界重新核验引用，并阻止新引用认领已标记删除的资产。仅增加一次无锁查询仍留 TOCTOU。至少单实例需与保存配置及事务提交同步，多实例需数据库/共享锁方案。
- **现有测试遗漏**：`BackgroundImageStorageServiceTest:40–59` 只以固定 mapper 返回值验证引用保护；没有用 latch 将配置提交插入“读取引用集合—删除文件”间隙。新增并发测试断言提交引用的文件不被删除。
- **confidence**：高。**风险**：中等、存在竞态窗口的数据/文件一致性风险；修复涉及锁顺序，必须防止与 portable 事务形成锁反转。

## B3 — P2：未确认的 portable 预检只存在单进程，Redis 多副本仍会随机确认失败

- **位置**：`nav-backend/src/main/java/com/example/nav/module/datapackage/service/PortableDataPackageService.java:69–70,99,160–174,191–206`。
- **触发路径**：A 接收 `/api/admin/data/import/preview` 并返回有效 token；下一次 confirm 被负载均衡送到 B。A 只将 PreviewState 存入自己的 ConcurrentHashMap，ZIP/解压目录也在本地临时目录。B 在 DB/Redis 查不到尚未创建的 job，再读取本地 previews 得到 null，返回 404。A 在确认之前重启也相同。
- **影响**：共享 Redis 不能使预检到首次确认跨实例工作；无会话亲和的多副本部署出现不稳定的“预检不存在或已过期”。没有证据表明会错误导入其他管理员的数据，本项仅报告功能失败。
- **调用方反证**：`commitStore.findByPreviewToken` 和 `jobStore.findByPreviewToken` 能恢复的是**已确认的任务**；jobStore.claim 位于 221 行，首次确认之前尚无该索引。`nav-backend/README.md:234` 宣称生产“预检、任务…保存在 Redis”且支持多副本，与当前实现不符。重试旧已确认 token 可成功不是反例。
- **建议**：持久化预检元数据及 TTL，同时将归档放到可被执行节点访问的受控共享存储，保留用户、摘要、revision 绑定；或明确仅支持单实例/会话亲和并更正文档与部署约束，不能仅把路径字符串写到 Redis。
- **现有测试遗漏**：`PortableDataPackageRestartRecoveryTest:63–69` mock 了已经存在的 job，因此不覆盖“preview 成功、尚未 confirm、换实例”的阶段。新增双服务实例共享 Redis、不同 previews map 的首次确认测试。
- **confidence**：高。**风险**：中等可用性风险；实现共享预检时需保持单次 claim、用户绑定和文件所有权校验。

## B4 — P2：portable 预检目录在进程重启后失去清理索引，15 分钟 TTL 不会清除旧文件

- **位置**：`nav-backend/src/main/java/com/example/nav/module/datapackage/service/PortableDataPackageService.java:99,142–174,506–512,543–550`。
- **触发路径**：创建一个有效预检但不确认，在 TTL 清理前终止/重启 Java 进程且保留同一 `/tmp`（例如未删除可写层的容器 stop/start，或使用普通共享临时目录的独立 Java 进程重启；**官方 Host unit 启用 `PrivateTmp=true`，不将其默认服务重启列为本问题已确认触发方式**）。新进程 previews map 为空；cleanupExpired 只遍历 map，不扫描磁盘既有 preview-* 目录，因此旧 ZIP、解压图片和业务数据不再进入任何清理路径。
- **影响**：多次预检/重启造成临时磁盘持续泄漏，也使包含隐藏业务项的归档超出预期 TTL 留存。背景上传容量配额并不统计此目录。容器被重建且临时层销毁时不会残留，不能把这一个部署模式当作所有运行方式的兜底。
- **调用方反证**：正常无效包/异常分支和 runImport.finally 的删除确实存在，故不报告正常请求泄漏；它们都需要活着的 PreviewState/执行栈，不能处理进程死亡。全范围引用搜索未找到启动扫描或其他预检目录 reaper。
- **建议**：带进程/任务所有权的目录清单与安全启动/定时 reaper，按持久化到期时间清理孤儿；避免清理另一个正在运行的实例/导入。另给预检临时空间设置总字节/数量上限，不只限制单 ZIP。
- **现有测试遗漏**：`PortableDataPackageExpiryTest` 验证同一 service 的内存 TTL；restart recovery 只测 job 查询，没检查旧 preview 目录是否最终被删除。新增服务重建、同 previewRoot、推进时钟后的磁盘断言。
- **confidence**：高。**风险**：中等资源与数据留存风险；重建后的孤儿清理必须避免跟随符号链接及误删活跃目录。

## B5 — P2：默认 simple 缓存使用不断增长的 generation key，但从不回收旧版本

- **位置**：`nav-backend/src/main/java/com/example/nav/module/publicdata/PublicDataCacheVersion.java:63–65,98–102`；`nav-backend/src/main/java/com/example/nav/module/publicdata/PublicDataCacheInvalidator.java:19–25,52–59`；`nav-backend/src/main/resources/application.yml:17–20`。
- **触发路径**：默认 local / `CACHE_TYPE=simple` 长期运行，交替修改分类/书签/站点并读取公开接口。每次修改推进 site_config.version，Cacheable 使用新版本字符串作为 key；Spring simple 的 ConcurrentMapCache 不具有 TTL/容量淘汰。invalidator 对非 Redis 仅返回 generation，传入的 CacheManager 被忽略，不 clear/evict 旧 key。
- **影响**：旧导航列表及站点快照一直被内存 cache 引用，缓存占用随“被读取过的版本数 × 数据量”增长直至重启；不是返回陈旧数据，而是资源生命周期缺陷。
- **调用方反证**：application.yml 的 time-to-live 嵌套在 `spring.cache.redis`，不能作用于 simple。生产强制 Redis 且默认 5m TTL，因此本项不外推为默认生产 Redis 内存泄漏。全范围未发现替代 simple CacheManager、Caffeine 上限或旧 key 回收器。
- **建议**：本地/非 Redis 使用有容量/过期策略的缓存实现；或显式回收过期 generation 并处理并发旧读回填。不要简单退回无版本键破坏已建立的数据库权威版本协议。
- **现有测试遗漏**：`PublicDataCachingContractTest:27–39` 只检查注解和 invalidator 依赖，generation 单测只检查单次返回。新增多轮写/读后 native cache size 有界测试。
- **confidence**：高。**风险**：中等、限 simple 运行方式；修复风险中等（清理与迟到填充并发）。

## 已核查并排除/不升级的问题

1. **迁移先于 ApplicationRunner 身份校验，是否会写错数据库**：未报告。`PersistedDatabaseEnvironmentPostProcessor.java:157–170` 设置 Hikari connection-init-sql，对每个新连接先核验 site_config 唯一实例 UUID；仅看 DatabaseIdentityService 执行顺序会产生误报。
2. **Redis 发布失败把已提交导入报成失败**：job(jobId)/confirm(token) 已优先查数据库 commit marker，runImport 有提交后真相兜底；不忽略这些代码宣称缺失提交标记。
3. **租约失效导致两个 portable writer 无保护覆盖**：`PortableImportTransactionService.java:95–109` 的数据库 guard、site 行锁和 SERIALIZABLE/revision 检查是独立权威保护；不能仅依据 Redis TTL 宣称裸并发覆盖。
4. **导入失败双重删除文件是重复逻辑**：catch 删除覆盖方法内失败，afterCompletion 覆盖 commit 阶段失败；删除有幂等处理，不能直接删掉任一层。
5. **严格 migration 元数据/校验和检查、pending/configured/completed 文件状态检测重复**：分别承担启动/安装/损坏恢复边界，不以行数多认定冗余。SQL 源码与打包资源副本也有不同部署消费者；未建议无证据删除。
6. **认证与上传**：JWT 每请求核验活动用户与 tokenVersion、改密 CAS、UTF-8 BCrypt 长度、受管文件路径及图片校验均已纳入审查；未发现足够证据支持新增认证绕过/任意文件写入结论。不是安全认证或动态无漏洞证明。

## 验证状态

- 已执行：工作树文件枚举（含未跟踪）、完整生产源码读取、候选调用链与反证搜索、重点测试源码阅读、其他测试结构/断言扫描、Spring CORS 官方实现读取。
- 未执行：本地/远程编译、单测、PG/Redis 集成测试、负载并发复现、实际线上请求。
- 逐文件深度、文件大小、行数、内容摘要、Git 跟踪状态及仅静态扫描清单由 `backend-files.json` 给出。报告不包含运行凭据。

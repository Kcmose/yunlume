# 后端与数据导入 14 项修复记录

本轮基于 `7957b9c9c65df31fa7e717bad23bad3402901ec3`，在 `codex/fix-backend-import-14` 分支完成以下 14 项修复，保留此前尚未提交的前端 11 项修复。范围来自重新遍历源码后的后端与数据导入问题清单；编号中的 B、BB、BP、D、W 分别沿用各审查分组，不把同一问题重复计数。

## 修复对应

后端路径以 `nav-backend/src/` 为根，前端恢复涉及 `nav-frontend/src/`。

| 序号 | 原问题 | 修复后的行为 | 主要回归依据 |
| --- | --- | --- | --- |
| 1 | B1 / BP-1：可信 HTTPS 代理下同源请求被 CORS 拒绝 | 仅当直属代理受信任且明确转发 HTTPS 时，用原始 Host 验证外部精确同源；不信任任意转发 Host，不扩大全局白名单 | `CorsConfigTest`、`ProxyInstallTransportIntegrationTest` |
| 2 | B2：背景清理与配置提交竞争 | 上传、清理、配置更新和导入先获得站点数据库行锁，再进入存储操作；保存前确认受管文件存在，回滚后重新锁定引用再清理 | `BackgroundReferenceTransactionTest` |
| 3 | B3：未确认预检仅在当前实例可用 | Redis 保存绑定管理员、摘要和业务版本的预检清单及二进制归档分块，其他实例可确认；非 Redis 模式用带文件锁的持久预检存储 | `RedisPortablePreviewIntegrationTest`、`PortablePreviewStoreTest` |
| 4 | B4：进程重启后遗留预检目录 | 新工作目录含所有权锁和到期清单；启动及定时回收过期目录，跳过仍持锁的工作目录 | `PortablePreviewStoreTest` |
| 5 | B5：simple 缓存旧版本无限累积 | 四个公开缓存只保留当前代的数据；拒绝过期加载回填，事务提交后才更新缓存，回滚保留原代 | `PublicDataGenerationCacheTest`、`PublicDataSimpleCacheIntegrationTest` |
| 6 | BB-01：搜索引擎可选字段无法清空 | 显式更新 icon、placeholder，空白规范化为数据库 NULL，响应为空字符串 | `BusinessMutationRegressionTest` |
| 7 | BB-02：局部更新回写旧实体 | 分类、书签、自定义链接显隐及自定义链接排序只写目标字段，避免覆盖另一事务已提交的其他字段 | `BusinessMutationRegressionTest` |
| 8 | BB-03：带 query/fragment 的受管背景识别不一致 | 配置校验、导出、导入校验和清理共用 URI 路径识别，配置保留地址参数与片段，检查实际文件及符号链接边界 | `ManagedBackgroundReferencesTest`、`ManagedBackgroundValidatorTest`、背景事务与数据包回归 |
| 9 | BB-04：导出 JSON 大于 4MiB 后无法导回 | writer/reader 共用 16MiB JSON 限制，保留单条目和总展开量约束；中央目录改为有界读取 | `PortablePackageJsonLimitTest` 的实际大包往返 |
| 10 | BB-05：自动追加排序整数溢出 | 搜索引擎、自定义链接以 long 计算，超过整数上限返回 409；无负排序或部分写入 | `BusinessMutationRegressionTest` |
| 11 | BP-2：初始化管理员缺少 HTTPS 门禁 | `/api/install/complete` 与数据库、Redis 凭据提交共用传输保护；拒绝不可信代理伪造 HTTPS | `WebInstallIntegrationTest`、`ProxyInstallTransportIntegrationTest` |
| 12 | D1：搜索 URL 在 CRUD 与 ZIP 校验不一致 | 允许合法 HTTP(S) URL 中的单引号，保留空白、反斜杠、危险协议等既有拒绝规则 | `PortableSearchUrlRoundTripTest` |
| 13 | W-004：确认响应丢失或刷新后结果未知 | POST 前持久化确认令牌；超时或 5xx 后仅只读查询任务，刷新可恢复终态，404 不等同回滚；数据库提交标记优先于暂存任务状态 | `PortableDataPackageRestartRecoveryTest`、`DataImportPanel.test.ts`、API/存储测试 |
| 14 | 原汇总：预检总量缺少限制 | 接收受管工作副本前预留最多 8 个槽位、512MiB 加权预算；每项按两份归档加 64MiB 展开空间计费，每节点最多两个处理中数据包；超限 429，活动任务续租，按任务归属释放 | `PortablePreviewStoreTest`、`RedisPortablePreviewIntegrationTest`、`PortableDataPackageExpiryTest` |

## 验证

依赖准备、运行测试与构建全部在约定的专用打包服务器执行，本地仅编辑和静态检查；未新增依赖或修改锁文件。

- 第一轮后端：Java 17 / Maven 3.9，`mvn -B -ntp test`，313 项通过，失败、错误、跳过均为 0。
- 前端：Node.js 22，`npm ci --no-audit --no-fund && npm test && npm run build`，45 个测试文件、396 项用例通过，类型检查和生产构建通过。
- Redis 使用本轮独立网络与容器，启用真实 ACL 测试强制开关；测试覆盖 ACL、缓存恢复、跨节点预检与实际导入事务，不以跳过真实 Redis 用例取得通过。
- 最终后端：`mvn -B -ntp verify`，**70 个测试类、327 项通过，失败、错误、跳过均为 0**；JAR 及 Spring Boot 重打包成功。其中真实 Redis 的 ACL、缓存恢复、跨实例预检三个测试类共 18 项通过。
- 最终前端：`npm test && npm run build`，**45 个测试文件、396 项通过**，`vue-tsc -b` 和 Vite 构建通过。
- 收尾验证曾发现新增真实导入用例污染共享测试数据库，使 4 项既有站点配置断言失败；为该测试类配置独立 H2 数据库后完整重跑通过，未降低原有业务断言。
- 静态检查：`git diff --check` 通过，修改文件为 UTF-8 无 BOM、LF；最终验证上传 465 份源文件并保存 SHA-256，应用源码与测试快照一致，验证后仅补记本修复报告。

服务器任务 `backend-final2` 和 `frontend-final` 是最终通过记录。日志、失败阶段证据、结果摘要与源文件清单保存于本机独立的 `backend-import-fixes-7957b9c` 验证目录，不进入 Git。后端产物为 `nav-backend-0.1.0.jar`，50,495,115 字节，SHA-256 为 `4faed2f30fe9d185cd0dd02f8851dcd848c2802f0387c9b33d94d024f49cdc36`。

## 边界与交接

512MiB 是受管预检归档、工作副本与解压目录的加权预算，不代表整个 JVM 堆、HTTP multipart 临时文件或上传背景存储的总上限。Redis 部署共享容量，非 Redis 模式按同一个存储根目录协调；非 Redis 任务状态仍为进程内状态，不能用作多副本生产部署。

未确认预检自发布起有效期 15 分钟；处理中和已激活归档有活动保留期并定时续租。新版本能恢复同一持久存储中的未过期预检。容器重建丢失本地临时目录时，文件模式不能恢复；生产 Redis 归档不依赖原节点临时目录。

旧版本生成的 `preview-*` 目录没有所有权清单及锁，无法证明是否仍被旧进程使用，不在自动回收范围内。升级时应先停止旧进程，再核对并清理其遗留目录；新版本的 `work-*` 目录按清单与锁安全回收。

数据库事务测试使用 H2 的 PostgreSQL 兼容模式，Redis 测试使用真实 Redis 7.4。本轮未进行真实 PostgreSQL 并发验收、浏览器 E2E 或生产部署。第三方构建注释及分包大小警告不影响构建，未扩展到分包优化。

未暂存、提交、推送或部署；原始审查文档仍是历史基线，其他运维与发布问题不属于这 14 项。

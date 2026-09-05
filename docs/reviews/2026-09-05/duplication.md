# 全仓库跨模块重复与可精简架构审查

## 范围与结论

- 只读 `<repository-root>` 的当前工作树，包含未提交及未跟踪文件；不是仅审本次 diff。按 reuse / quality / efficiency / altitude 四个角度串行审查，没有再派生子代理。
- 对 Git 已跟踪及非忽略未跟踪文本文件执行去首尾空白的连续 8 行重复窗口扫描：417 个文件、342 个跨文件重叠窗口。**窗口不是独立问题数**，已排除 import、实体样板、测试夹具等噪声，筛选出以下 8 个独立精简点。
- `duplication-files.json` 区分全量扫描文件与人工阅读文件；“全仓库扫描”不等于每行人工审读。阅读了候选实现、调用方、配置/分发及文档上下文，并对人工阅读路径执行 `git blame --line-porcelain`。
- 大多数旧实现只可追溯至仓库边界提交 `3f90c3c`（“实现 yunlume 双模式安装与无口令初始化”）；它不足以证明更早设计意图。下文分别标记当前机制可信度与历史意图限制。未跟踪文件没有 blame 历史，不虚构提交动机。
- 未修改仓库，未编译、运行应用、测试、部署、提交或推送；建议均未实施。以下是静态可维护性结论，不声称运行时缺陷已复现。

路径均相对仓库根目录。CAREFUL 指保留行为的抽取仍需回归；RISKY 指涉及契约、并发或启动安全边界，必须单独验证。

## 1. URL 规则的公共化尚未覆盖搜索模板和自定义链接

**优先级：高；confidence：high（重复/规则差异），历史动机：low；risk：RISKY。**

证据与调用链：
- `nav-backend/src/main/java/com/example/nav/common/validation/SafeUrlRules.java:10-41` 已有 HTTP/站内路径、userinfo、反斜杠及空白控制字符规则（新未跟踪实现）。
- `nav-backend/src/main/java/com/example/nav/module/customlink/service/impl/CustomLinkServiceImpl.java:161-215` 仍重新解析 HTTP、内部路径及锚点；`nav-backend/src/main/java/com/example/nav/module/datapackage/service/PortableDataValidator.java:310-347` 有另一套锚点规则再调用 SafeUrlRules。
- `nav-backend/src/main/java/com/example/nav/module/search/service/impl/SearchEngineServiceImpl.java:177-217` 与 `nav-backend/src/main/java/com/example/nav/module/datapackage/service/PortableDataValidator.java:280-307` 重写 `{keyword}`、authority/fragment 限制及 URI 校验。
- `nav-backend/src/main/java/com/example/nav/module/search/dto/SearchEngineDTO.java:16-19` 又用正则限制搜索 URL，额外禁止单双引号；导入路径没有这条 DTO 正则。`SearchEngineController.java:45-52` 执行 DTO 验证，而 `PortablePackageReader.java:67-78` 直接走包模型验证，`PortableImportTransactionService.java:124-133` 直接写 mapper，不会复用 CRUD 服务校验。
- `nav-frontend/src/utils/url.ts:1-14,21-37` 的 `isSafeHttpUrl` 还承担自动补协议用途，只检查协议，不等价于后端拒绝 userinfo 的安全定义。

**成本/已见漂移：** 同一搜索/链接领域规则要在 DTO、CRUD、包导入三处修补。CRUD 的输入修整、自定义锚点校验和导入的原值校验并非相同语义；搜索 DTO 对引号的限制与导入谓词也不同。不能把只读取其一的代码审查当作所有入口一致。

**建议边界：** 在 `common.validation` 提供纯 `SearchTemplateRules`、`SafeUrlRules.isSafeAnchor/isSafeCustomLink`，返回结构化违规原因；CRUD 映射成 BusinessException，导入映射成带 JSON path 的 Issue。用同一输入用例表覆盖 DTO/CRUD/导入，跨 Java/TS 共享契约测试数据而非强行共享实现。

**不应合并：** 保留导入的长度、稳定 key、跨资源引用和累计错误上限；保留前端输入便利性与后端安全约束的区别。不要取消任何入口校验、不要把“补协议”用于可信性校验。默认引擎修复/唯一性锁是事务规则，不应塞进 URL helper。

历史：上述旧校验 blame 指向 `3f90c3c`；新 SafeUrlRules 的职责从当前调用上下文确认。合并之前应先明确上述差异哪些是兼容契约，不直接改变接受集合。

## 2. Redis 持久配置完整性由启动层与运行层各自解释

**优先级：高；confidence：high（重复），历史动机：medium；risk：RISKY。**

证据：
- `nav-backend/src/main/java/com/example/nav/common/config/PersistedRedisEnvironmentPostProcessor.java:96-175` 验证权限/大小、config-marker 格式、数据库身份、digest、TLS、CA、地址与字段。
- `nav-backend/src/main/java/com/example/nav/module/install/service/RedisConfigurationStore.java:93-162` 再执行基本相同的持久状态解析、身份与摘要校验；大小常量分别在前者 `:40-42`、后者 `:37-39`。
- 启动层 `:128-138,161` 显式验证主机、用户名、密码、CA 内容；运行层 `RedisConfigurationStore.java:148-158` 主要读取字段重算 digest，并非调用同一解析器。这是接受条件维护上的不对称，不等于已证实能绕过启动检查。
- `nav-backend/src/main/java/com/example/nav/module/health/controller/HealthController.java:60-65,87-92` 调用运行层判断；`RedisSetupService.java:119-129` 写出供两层解释的文件。启动层 `:177-204` 则必须在 Spring 自动配置前提供属性。

**成本：** 持久格式每扩一个字段，写入、启动验证和运行健康检查都要同步；容易出现“运行态判断可用、重启却拒绝”的语义漂移。重复磁盘读取也不能靠简单永久缓存消除，因为运行层在检测文件变动。

**建议边界：** 抽取不依赖 Spring bean 的 `ManagedRedisConfigurationReader/Validator`，输入明确文件路径及预期数据库身份，输出不可变已验证配置或结构化失败原因。启动适配器负责 Environment 注入，运行 store 负责持久化及状态展示。现有 `RedisConfigurationDigest` 应继续是唯一摘要实现。

**不应合并：** 启动时抛异常 fail-closed 与运行时返回 invalid 是不同适配行为；不得删除二次检查、PENDING 哨兵、NOFOLLOW_LINKS、权限和大小检查、数据库身份绑定或 legacy-env 禁回退规则。也不建议把 PostgreSQL/Redis 两种不同状态机揉成一个泛型“大配置 store”。

历史：两个读取路径来自 `3f90c3c`；启动处理器注释明确是 auto-configuration 前校验，运行层调用证明两次验证各有生命周期用途。可共享解释规则，不能删掉任一验证时点。

## 3. 数据库与 Redis 一次性票据可以共享机制，但必须隔离实例

**优先级：中高；confidence：high；历史动机：medium；risk：CAREFUL（误共享状态则 RISKY）。**

证据：
- `nav-backend/src/main/java/com/example/nav/module/install/service/DatabaseConnectionTicketStore.java:26-108` 与 `RedisConnectionTicketStore.java:24-111` 实质复制 token 随机生成、3 张上限、TTL 夹紧、Clock、generation、定时删除、consume-remove、清理和 shutdown。
- 有差异的是 payload：数据库 `:114-120` 包含 schema/expectedInstanceId；Redis `:117-123` 包含 configurationDigest/databaseInstanceId。
- 调用方 `DatabaseSetupService.java:189-196,212-247` 和 `RedisSetupService.java:94-95,108-131` 在消费后还重验目标并推进代次。
- 前端 `nav-frontend/src/utils/installDatabase.ts:51-75,101-106` 与 `installRedis.ts:43-61,75-80` 也重复票据 envelope 和过期判断，但数据库还需 schemaState 等字段。

**成本：** 过期竞争、重复消费、内存中凭据寿命等安全修复需复制两遍，两份 scheduled executor 生命周期也要各自维护；前端 envelope 格式同样可漂移。

**建议边界：** 内部组合式 `ExpiringOneShotTicketStore<T>` 负责存储机制，两个具名领域 store 各自构造独立实例并保留类型化 payload/错误文案；前端只抽 `parseTicketEnvelope` 和 `isTicketExpired`，各业务适配器继续验证扩展字段。

**不应合并：** 绝不能用一个全局 Map、generation 或容量池混放两类凭据；不能移除同步临界区、主动定时清除、原子 remove-before-return，不能把后续目标重验当作票据验证的重复而删除。数据库远端写入后的 PENDING 保留语义不属于此 helper。

历史：两类 store blame 均为 `3f90c3c`；调用方清楚展示凭据隔离及 schema/digest 绑定用途，原始复制原因不可进一步考证。

## 4. 排序输入检查重复，搜索排序另有重复数据库读取

**优先级：中高；confidence：high；历史动机：medium；risk：CAREFUL。**

证据：
- `nav-backend/src/main/java/com/example/nav/module/bookmark/service/impl/BookmarkServiceImpl.java:210-231` 与 `nav-backend/src/main/java/com/example/nav/module/category/service/impl/CategoryServiceImpl.java:138-169` 重复 null/empty、1000 项上限、正 ID、非负 sortOrder、去重。
- `nav-backend/src/main/java/com/example/nav/module/customlink/service/impl/CustomLinkServiceImpl.java:125-154` 另写非空/去重/批量加载；`nav-backend/src/main/java/com/example/nav/module/search/service/impl/SearchEngineServiceImpl.java:154-170` 则每个 item 在验证和更新循环中各调用一次 requireEngine（`:227-230` 即 selectById），已有 lockAll 后仍重复读取。
- `nav-backend/src/main/java/com/example/nav/module/search/controller/SearchEngineController.java:77-82` 在 HTTP 层另有 @NotEmpty/@Valid；因此不能仅凭 service 的简略检查宣称 HTTP 可接受空列表。

**成本：** 相同排序 envelope 四份验证，服务内批量上限并不一致；搜索排序对同一资源做两轮逐项 SELECT，比已存在的 map 模式多余。

**建议边界：** 抽纯 `SortItemsValidator` 返回按请求顺序保存的已验证 ID 集合（上限作为显式策略参数）；搜索在保持 lockAll 的前提下加载一次映射，再复用对象更新。此项不是推荐通用 CRUD 父类。

**不应合并：** 分类 `:160-163` 的 FOR UPDATE、书签/分类 `updateSortOrder` 定向字段更新及受影响行数检查、搜索默认引擎全局约束、各资源不存在时的错误均保留；不要为了统一换回 updateById 而扩大更新面。不同上限若要统一属于 API 契约变更，先审批与回归。

历史：基础实现为 `3f90c3c`，书签/分类部分校验和定向更新为未提交行；其当前锁与冲突检查说明不是多余防御，应在抽取时保留。

## 5. 三类公开数据加载重复状态机，搜索数据还落在页面层

**优先级：中；confidence：high（重复/错层），历史动机：medium；risk：CAREFUL。**

证据：
- `nav-frontend/src/stores/navigation.store.ts:8-15,28-43`、`nav-frontend/src/stores/site.store.ts:8-13,16-36` 各保存 loading、usingFallback、hasRemote、requestVersion，并复制 retry/成功保留/失败保留旧值/竞态丢弃。
- `nav-frontend/src/views/portal/PortalHome.vue:60-68,92-119` 实现第三份加载状态；相较两 store，没有 requestVersion 保护。当前调用是 `:130-155` 挂载加载，不能据此宣称已发生并发覆盖，但后续复用/刷新时会漏掉相同规则。
- `nav-frontend/src/composables/useBookmarks.ts:4-12`、`useSiteConfig.ts:4-7` 已让页面只消费 store；搜索资源却仍由页面直接调用 API。
- `nav-frontend/src/utils/publicRequestRetry.ts:28-42` 已统一网络重试，缺少的是重试之上的资源状态控制，而不是再造 Axios 包装器。

**成本：** “首次失败显示降级、已有远端值失败不回退、旧请求不得覆盖”需改三份；页面生命周期与持久选择偏好混在资源获取中，测试也难复用。

**建议边界：** 提供内部 `RemoteResourceState<T>`/composable 管理 requestVersion、loading、hasRemote、fallback 展示，store 保持公开 API；搜索引擎移到具名 store。资源 normalize、空响应策略用明确回调，偏好选中项仍由 picker 处理。

**不应合并：** 导航的合法空列表不等价于搜索引擎空列表；站点 config 的 fallback 字段合并也不是导航替换。不能简单让 `usingFallback = !hasRemote`，那会改变首次同步期间不显示失败提示的行为。requestVersion 不是应删的“冗余状态”。

历史：主状态机为 `3f90c3c`；导航还有 `fix: 避免刷新时闪现演示导航` 的独立历史，当前注释也明确首屏不能先展示降级警告。抽象必须以此为验收条件。

## 6. Redis 基础读写探针复制在 controller 与安装业务中

**优先级：中；confidence：high；历史动机：medium；risk：CAREFUL。**

证据：
- `nav-backend/src/main/java/com/example/nav/module/health/controller/HealthController.java:145-171` 与 `nav-backend/src/main/java/com/example/nav/module/install/service/InstallService.java:419-457` 重复随机 key/value、setIfAbsent+60 秒 TTL、读回、删除及异常清理。
- 健康入口 `HealthController.java:101-106,119-142` 另外有 5 秒成功/失败缓存和并发监视器；安装函数将探针结果转成 InstallCheckVO，而不是抛相同异常。

**成本：** 两处各自维护 key 清理和 TTL 语义；controller 直接承载连接检查底层逻辑，未来一处改 ACL 需求另一处易遗漏。

**建议边界：** 抽运行期 `RedisReadWriteProbe`，显式传 namespace（health/install），返回明确失败结果或抛标准内部异常。controller 保留缓存适配器，安装服务保留展示文案。

**不应合并：** 不把所有 Redis 验证都叫这个探针：连接向导还需 TLS/网络边界/运行 ACL 能力验证，简单 SET/GET/DEL 不能替代它。保留随机隔离、TTL、失败清理、health 缓存失败值和安装不复用过期健康结果的区别；不能借抽取扩大凭据权限。

历史：两实现都追溯至 `3f90c3c`；健康缓存和安装结果转换说明两个调用生命周期各自合理，可合并执行原语而非整个检查流程。

## 7. 新迁移 SQL 的双源码维护可改成单源打包，不动独立验证

**优先级：中；confidence：high（字节内容重复），历史动机：medium（当前上下文）；risk：RISKY。**

证据：
- `database/migrations/20260904_0004_portable_import_operations.sql:1-21` 与 `nav-backend/src/main/resources/database/migrations/20260904_0004_portable_import_operations.sql:1-21` 是相同 SQL，均未跟踪。
- `nav-backend/src/main/java/com/example/nav/common/config/PostgresqlMigrationRunner.java:27-30,53-85` 固定资源路径和 checksum、持事务 advisory lock 并检查 registry；`nav-backend/pom.xml:104-138` 尚未配置从外部 canonical migration 目录映射资源。
- `ops/package-host-release.sh:139-147` 从仓库顶层 migration 目录复制到 host 包；`ops/postgresql-migration-test.sh:74-83` 已显式 cmp 两源码、核对 checksum 及 JAR 内路径。
- `nav-backend/src/main/resources/schema-postgresql.sql:49-70` 还维护新装形态；它是 bootstrap schema，不是同一个升级脚本。

**成本：** 每次新迁移必须手动更新 canonical 和 classpath 副本。现有测试已能阻止漂移，但开发过程仍要手工同步，遗漏后才在门禁失败。

**建议边界：** 后续构建改成 canonical SQL 单源资源映射/显式复制，生成 JAR classpath 和 host 分发文件；资源步骤必须适配目前 `docker-compose.yml:6-8` 的后端子目录 build context，不能随手引用 `../database` 后破坏独立镜像构建。先作为独立构建任务设计。

**不应合并：** 不删除双分发载体；不把 checksum 从“可信固定值”改成启动时对任意当前文件计算后自认可信；保留 JAR/host 实物与 canonical 交叉检查、迁移登记、事务锁与 schema 完整性检查。bootstrap 中 IF NOT EXISTS、既有升级脚本与 H2 方言不应简单拼成一个文件，更不能改写已发布迁移。

历史：新 SQL/runner/测试没有可用 blame，当前资源加载和发布脚本明确解释双分发目的；没有把必要的分发重复误报为应删除副本。

## 8. 上传上限同时是后端运行配置和前端构建配置，文档承担人工同步

**优先级：中；confidence：high；历史动机：high（文档明确），risk：RISKY（新增能力契约）。**

证据：
- `nav-backend/src/main/java/com/example/nav/module/upload/config/UploadStorageProperties.java:13-19` 为权威业务上限，`nav-backend/src/main/resources/application.yml:81-85` 运行时读取 APP_UPLOAD_MAX_BYTES。
- `nav-frontend/src/components/admin/BackgroundImageField.vue:25-31,46-54` 同时编译绝对上限与 VITE_UPLOAD_MAX_BYTES，用于阻止浏览器上传。
- `docker-compose.yml:54,88-90` 将同一设置分别注入 backend environment 和 frontend build args；`deploy/host/app.env.template:52-57` 只描述运行值。
- `README.md:335-336`、`nav-backend/README.md:244-245` 重复要求改参数后同时重建；这不是无意遗漏，而是已有同步负担。
- `nav-frontend/src/api/upload.api.ts:11-24` 仅上传，没有获取服务端有效上限的能力接口。

**成本：** 用 runtime env 改后端时，已发布静态前端不会更新限制；靠多处文档记住构建步骤。它与发布已验证不可变制品的方式天然增加耦合。

**建议边界：** 在已认证后台能力接口提供非敏感的有效上传限制及允许格式，由前端读取并用于提示；前端保留保守默认值，后端继续最终执法。配置说明由一份配置目录说明引用，不再在多处把“运行参数变更”与“重建双方”硬绑定。

**不应合并：** 绝不能把业务上限改成仅前端校验。Nginx 的 12m 普通 API / 66m 包预检、Spring multipart 的 66MB 和业务图片 10MiB 是不同层级余量，不是冗余常量：见 `deploy/host/yunlume.nginx.conf:259-270,291-302`、`nav-frontend/nginx/nginx.conf.template:344-375`、`application.yml:10-16`。不应为统一常量放宽 ZIP 展开量、单图片或代理限制。

历史：上传上限代码及文档主说明都为 `3f90c3c`。由于旧方案有明确文档契约，此项是演进建议，不报作已复现功能缺陷。

## 明确不建议精简的相似实现

- **运维锁：** `install.sh:353-362`、`ops/migrate-docker-volumes.sh:165-174`、`ops/lib/common.sh:59-69` 的相同锁代码可追溯到 `22a4eda`（“fix: 统一安装与运维操作锁”）。这是独立入口使用同一全局锁的刻意安全边界；不要为消除复制让独立下载的安装脚本在验信前 source 远端 common.sh，也不要改成每个脚本各一个锁。今后若生成脚本可单源生成，但此处不计高价值发现。
- **导入两层协调：** `PortableImportTransactionService.java:95-109,143-145` 已明确 Redis 只做调度，数据库 guard 行锁与 commit marker 才是真实写入/提交事实。不要因“多个状态”而删掉数据库锁、幂等 marker 或 post-commit 恢复；这不是资源缓存状态机的同类冗余。
- **代理分发：** host 的 `$scheme` 与容器的 `$backend_forwarded_proto`、不同 upstream/路径是部署信任边界差异。可以约束共同路由策略的测试，不能简单用同一份文本覆盖二者。
- 不因少量重复将所有 CRUD service/controller 抽成泛型父类，也不清理兼容字段/旧迁移、测试独立断言或 fail-closed 异常。

## 验证与后续门槛

最终静态核验：与父审查 `baseline.json` 的 417 个文件 SHA-256 比对，仓库内容变化为 0；路径清单包含 417 个扫描文件、49 个人工阅读文件；报告为 8 个独立发现，41 个显式完整路径起始行引用均存在且未越界。结果写入 `duplication-verification.json`。人工阅读包含按范围读取，不代表这 49 个文件逐行读完。

本轮证据为静态文本、重复扫描与 Git 历史，不宣称任何建议已通过行为测试。实施前应至少分别覆盖：入口一致的 URL 用例；启动/运行持久配置恶意文件矩阵；票据过期/并发消费/跨类型隔离；排序异常与锁顺序；公开数据乱序返回与首次 fallback；探针失败/cleanup；canonical/JAR/host 迁移一致性；前后端上传上限与代理余量。所有实际构建/运行验证仍按项目远程执行限制进行。

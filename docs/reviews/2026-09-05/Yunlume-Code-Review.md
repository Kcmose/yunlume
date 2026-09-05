# Yunlume 全工作区代码审查

## 结论

**当前代码不适合直接发布。发现了真实功能异常和多处发布阻塞，不只是代码冗长。** 本轮只检查，不修改源码、不提交、不推送、不发版，也没有恢复整体验收。

审查对象为 `<repository-root>` 当前工作区，包含未提交和未跟踪文件；HEAD 仅作定位，不代表审查内容都已提交。417 个非忽略文件纳入基线与重复扫描。分组清单覆盖其中 411 个，剩余为 Git 属性、历史文档和已做 JSON 解析的依赖锁文件。后端部分测试只做结构/断言审查，不能宣称每个测试逐行深读；详细覆盖深度见附录。

复核前后所有基线文件内容哈希一致，未新增或删除项目路径。报告和探针仅存于项目外 `docs/reviews/2026-09-05/`。

## 一、最优先处理的确定性异常

### 1. 管理员登录成功却不跳转
- `nav-frontend/src/stores/auth.store.ts:89–111`：成功分支设置 token 和 user 后没有返回真值。
- `nav-frontend/src/views/admin/LoginView.vue:19–26`：只有 `if (accepted)` 才跳转。
- **实际效果：登录成功，但页面留在登录页。**
- 已在指定远程构建服务器提取原 action 函数体作隔离执行：`actionResult=undefined`、`callerWouldNavigate=false`、`storeAuthenticated=true`。这是实际函数控制流测试，不是真实账号登录或浏览器验收。

### 2. 发布流水线存在多道互相掩盖的硬阻塞
这些是不同原因，不应只修第一个后就认为链路已通：
- **Tag 解引用错误**：`.github/workflows/publish-images.yml:74` 及另外四处把字符串放入 Bash 算术条件。正常 `commit`、`tag` 都触发 `unbound variable`。远程复现均 exit 1。
- **Job 权限不足**：同文件 `662–665` 的 publish job 缺 `id-token: write`、`attestations: write`，且只有 `contents: read`，却在 `902–953` 执行签名和 Release 资产上传。为实际写入 job 设最小权限或拆 job，不是扩大只读 Secret 权限。
- **Host 归档路径不一致**：`ops/package-host-release.sh:164–172` 产出根级 `backend/yunlume-backend.jar`；`ops/lib/release-transaction.sh:355` 寻找 `*/backend/yunlume-backend.jar`。符合真实布局的测试归档也被实际 helper 拒绝，远程 exit 2。
- **发布后 checksum 校验必败**：工作流 `1651–1653` 将排序后结果与未排序的 expected 数组比较。远程对完整正确集合复现 exit 1；此时可能已经跨过不可变发布边界。

### 3. 签名校验失败会被误当成功
- `ops/lib/release-transaction.sh:178–180、198–199` 没有显式传播 attestation 验证失败。
- 工作流在命令替换中调用此 helper，后面的 `printf` 掩盖失败，不能靠 `set -e` 保证拒绝。
- **远程故障注入：verifier 返回 42，外层却 exit 0 并得到 digest。** 注入数据明确为测试夹具，不是声称拿到了真实签名。
- 这证明该校验门禁存在 fail-open；不等于已经证明可伪造签名或绕过整个发布链。

### 4. 首次安装失败后，正常重跑被自身状态标记拦住
- `install.sh:497–502` 提前写 `.install-mode`，失败路径未将新建标记纳入完整回滚。
- 第二次运行在 `650–652` 因存在 mode、没有 VERSION，报“已有托管部署缺少 VERSION”。
- 已使用原函数在远程临时目录复现连续两次预检：第一次通过，模拟提交前失败后第二次被拒绝。未运行安装 main、Docker 或 systemd。
- 应区分“本次新装未完成”和“已完成部署损坏”，不能简单取消缺 VERSION 的安全拒绝。

### 5. 多标签页登录存在旧请求清除新会话的竞态
- `auth.store.ts:81–87、92–109`：跨标签页身份变化不使旧登录失效，旧请求失败仍执行 `clearSession()`。
- 远程提取原 action 注入事件顺序：新标签页登录成功后，旧标签页的失败响应将新 token 清成 `null`。
- 应让登录提交和失败清理都受身份 revision 约束，而不只是限制 fetchMe。

### 6. 数据导入页面存在状态入口错误
- `DataImportPanel.vue:413` 预览取消无条件回到 `IDLE`，即使仍有不确定或未结束的 active job；应恢复为受阻/运行状态而不是开放新导入入口。
- `ImportPreviewDialog.vue:52–62` 默认覆盖模式且删除用户名确认门槛，“继续导入”点击即提交；相比独立弹窗，增加了误覆盖风险。
- 后端互斥/幂等不能替代前端保留 active-job 状态，也不能替代破坏性操作的明确确认。

### 7. HTTPS 代理链存在跨层配置冲突
- **通用 CORS**：后端 `CorsConfig.java:17` 使用默认 HTTP localhost 来源，安装器也未写实际 HTTPS 域名；框架没有受信任 forwarded scheme/port 适配。浏览器 HTTPS Origin 经内部 HTTP 代理后可能先被 CORS 拒绝。`SecureTransportPolicy` 的业务层 XFP 判断不改变 Spring 对请求 origin 的比较。官方 Spring `CorsUtils` 源码已交叉核对，但本轮未做真实 HTTPS 浏览器联测。
- **Host 独立问题**：`deploy/host/yunlume.nginx.conf:158` 等位置用 `$scheme` 覆盖外层 XFP，且手改受管配置会被安装器覆盖。Docker 的 WEB_* 配置不能直接当作 Host 的配置方法。
- 修复必须保留即时代理 allowlist 与禁止绕过代理的边界，不能无条件信任 X-Forwarded-*，也不应默认关闭凭据 HTTPS 保护。

## 二、其余需要修复或验证的实质风险

完整触发路径和定位见后面的分组报告：
- Actions “仅重跑失败 jobs”使用消费者的新 `run_attempt` 猜上游成功 job 的 artifact 名称，导致恢复失败。
- 已发布只读重跑没有验证滚动镜像别名，部分完成可能被误报为完全验证成功。
- immutable 策略混合了仓库启用与 owner 层强制要求。当前配置状态沿用此前上下文，本轮未重新查询在线 API；是否要求 owner 强制属于需明确的治理策略。
- 搜索引擎管理缺少分类/书签已有的 mutation 互斥与身份 revision，陈旧响应可能覆盖新状态。
- 上传进度回调未受任务编号/组件卸载保护，旧上传可能污染新任务进度。
- 背景资源 GC 的锁与普通站点配置保存不统一：可构造“合法性检查完成→GC 删除→数据库保存引用”的竞态，形成悬空背景 URL。
- 管理接口普通写入与数据导入不共享统一的导入快照提交边界；需明确是否承诺整批快照原子语义，再补相应并发锁/约束。
- 数据包预览的压缩字节配额与解析后的 Java 对象内存开销不等价，需要整体预算；重启后遗留预览临时目录在某些部署模式下也缺清理。官方 Host unit 启用 PrivateTmp，不把其默认重启误列为已确认泄漏场景。

## 三、最值得精简的地方

不是简单删行，而是收敛已经产生语义漂移的重复实现：

1. **发布验证代码优先合并**：五份 tag 解引用、两大份已发布验证器、多份资产/checksum 清单。现有 tag 算术错误、排序漂移、只读别名漏检已显示维护成本。保留各 mutation boundary 的检查调用，只合并实现。
2. **后端 portable 数据包按职责拆分**：格式/资源校验、preview 配额、导入事务、job 提交与恢复分别负责。生产环境里不必长期保留仅为兼容测试构造器存在的内存 job store，可移到显式测试替身。
3. **数据库/Redis 安装收敛公共安全外壳**：ticket 存储、HTTPS 门禁、锁与持久化事务形状可共享；连接验证、TLS/ACL、PostgreSQL identity 与 schema 检查仍保持专用实现。
4. **前端异步写入使用统一生命周期**：复用已有 mutation 工具的互斥、revision、过期提交拒绝；让搜索引擎、上传、登录具备相同的边界，而不是各自再写一套状态位。
5. **导入确认流程只留一个入口**：真正共享 `ImportPreviewDialog` 或共享 model/actions，清掉两套默认模式、确认门槛和格式化函数。现在重复已造成行为不同。
6. **配置收口和去掉无效依赖**：默认/生产属性重复可以只保留差异；`PublicDataCacheInvalidator` 注入但不使用 `CacheManager` 等属于低风险清理候选。对安全配置要先确认继承语义。
7. **统一前后端 URL 行为契约，不共享不合适的实现**：Java URI 与浏览器 URL 的接受范围不同，可共享测试语料和明确规范，而不是硬把两端工具揉在一起。
8. **Nginx 模板从公共路由契约生成**：共用路由、限流与接口清单，Host 与 Docker 的代理信任、路径和用户配置所有权继续分开。

详细跨模块报告列有每个抽象建议的调用位置、历史线索和不应合并的安全边界。

## 四、测试与证据边界

- 本次不是恢复验收。没有运行全套 Maven/Vitest/E2E、真实 Release 工作流、真实数据库导入或 Host TLS 部署。
- 静态解析检查覆盖 Shell、Python、JSON、YAML、XML；这些通过不代表运行正确。多处问题恰好是 `bash -n` 无法发现的语义错误。
- 登录两项控制流探针及发布/安装七项隔离探针已在用户指定远程构建机 `[private-server-redacted]` 实际执行。外部 API/签名故障使用明确标注的替身，没有发布资产、账号或业务数据变更；远程临时目录随探针结束清理。
- 前端多个组件测试主要匹配源码字符串。建议保留必要契约断言，同时增加真实组件事件、Promise 乱序完成、取消/卸载和连续操作测试。不能把现有整个测试集一概说成无效，但字符串存在不等于行为正确。
- 优先顺序：先修确定性功能与发布阻塞，再收敛异步和事务边界，最后做结构精简。不要把大规模重构和验收修复同时混成一批。

## 五、仅待明确、尚不作为确定缺陷的安全设计问题

`InstallController.java:52–58` 的 `/api/install/complete` 接收管理员初始密码，却不像 PostgreSQL/Redis 凭据端点那样调用 secure transport 门禁。代码差异成立；是否要求最终安装提交也由后端强制 HTTPS，需要明确产品安全策略并验证前置 setup 状态。建议作为单独的安全加固议题，不夸称本轮已证明远程利用。


---
# 分组审查详细记录

以下是分组逐项定位与覆盖说明。主审补充的远程复现证据优先于分组记录中的“仅本地静态探针”表述。全篇不代表正式验收通过。


---
# 附录：发布与安装

# 发布部署全工作区只读审查

## 结论与范围

**发现 10 项实质问题，当前工作区不宜进入正式发布。** 多项是独立的确定性阻塞：Git tag 的 Bash 算术判断、publish job 权限、Host 归档内 JAR 路径、发布后 checksum 集合排序。前一个失败会掩盖后一个，不代表后面的路径正确。

- 仓库：`<repository-root>`；基线 HEAD：`e5c65f08b7ea2d82317d9350e45bbcb2800dbbfa`。
- 审查的是**当前未提交和未跟踪文件的实际内容**，不是该 HEAD 的内容，也不限 diff。
- 全文件覆盖 **42 个文件、10,625 行**：`install.sh`、全部 `ops/`、`.github/`、`deploy/`、Compose、三个 Dockerfile、Docker/git ignore、frontend Nginx 入口脚本与模板、环境样例、README。逐文件范围、SHA-256、说明与问题映射见 `operations-files.json`。另交叉读取后端 `SecureTransportPolicy.java`；应用其余业务代码由其他审查范围负责。
- 未修改仓库、GitHub 或部署服务器；未运行 Maven/npm/Docker 构建、部署或验收。只在 `docs/reviews/2026-09-05` 运行 Bash/Python 最小静态缺陷探针和临时测试夹具。
- 已逐个执行 **23 个 Shell 文件的 `bash -n`，全部通过**。这不证明运行语义正确。工作流现有 `bash -n install.sh ops/*.sh ops/lib/*.sh` 只把第一个文件当作脚本，其余当参数；建议修复时改成逐文件循环，但不另列为第 11 项。
- 探针实际输出保存在 `operations-probes.json`，可复现脚本为 `operations-probes.py`。其中 tar 使用明确标为 `fixture-not-a-real-JAR` 的字节，只验证成员布局，**不是实际编译产物或 Release 验收证据**。

## OPS-01 — Git tag 解引用把字符串放入算术表达式，所有正常 tag 都在门禁退出

- **位置**：`.github/workflows/publish-images.yml:74`；重复于 `1388、1499、1747、1887`。
- **风险**：高 / P1，正常版本发布完全阻塞。**Confidence：1.00。**
- **触发路径**：tag push → `release-preflight` → API 返回正常的 `object.type=commit` 或 `tag` → `for ((depth = 0; depth < 16 && tag_object_type == "tag"; depth++))`。
- **原因/影响**：`(( ... ))` 是算术上下文；字符串变量被当作变量名再次求值，双引号不把 `tag` 变成字符串常量。`set -u` 下轻量标签报 `commit: unbound variable`，附注标签报 `tag: unbound variable`，进入发布前就退出。
- **实测**：同形 Bash 表达式对两种类型均 exit 1，上述两条错误已捕获；无需 API 或构建。父审查另在指定远程构建机对原工作流行独立复现相同结果，证据为 `docs/reviews/2026-09-05/tag-loop-repro.json`（此条依据父审查提供的结果，不冒充本子任务重新执行）。
- **具体修复**：用数字循环控制深度，在循环体用 `[[ "$tag_object_type" == tag ]] || break`；结束后显式验证已解析为 commit 且 SHA 一致。将五处代码抽成同一个返回 commit SHA 的受测试 helper，保留每个 publication boundary 的调用位置。
- **测试缺口**：现有 `publish-workflow-test.sh` 主要查源码字符串，不能发现有效 Bash 语法中的运行错误。新增轻量、单层/嵌套附注标签、超深、异常类型、远端移动等真实 shell 执行测试。

## OPS-02 — publish job 实际做签名和 Release 资产写入，但没有相应权限

- **位置**：`.github/workflows/publish-images.yml:662–665`；消费者为 `902–907、927–928、952–953`。
- **风险**：高 / P1，修复前置门禁后 tag 发布仍无法成功。**Confidence：0.99。**
- **触发路径**：tag 分支构建出候选 OCI → `Attest immutable candidate before registry publication` → `actions/attest-build-provenance`；随后上传归档和 commitment 到 draft Release。
- **原因/影响**：job 只声明 `actions: read`、`contents: read`、`packages: write`。显式 permissions 中未列出的权限为 none：候选签名缺 `id-token: write` 与 `attestations: write`；资产上传缺 `contents: write`。其他 job（`backend-attest`、`release`）有相应权限不代表 publish 可以继承。只读 administration secret 也不解决这些写权限。
- **具体修复**：优先拆出最小权限候选签名/资产提交 job，通过不可变 artifact ID、digest 和明确 producer 身份传递 OCI；若维持当前结构，应为**确实承担写入的 job**授予上述三项必需权限，并审查其执行代码/构建依赖的凭据暴露面，避免把权限放到工作流全局。
- **测试缺口**：从 YAML 解析各 job 权限，验证每种 mutation/attestation 所需权限；补一个受控真实 Actions tag 流程。此次仅审查配置，未发起远端运行。

## OPS-03 — Host JAR 校验器寻找的 tar 成员与真实打包器不一致

- **位置**：`ops/lib/release-transaction.sh:355`；生产归档布局为 `ops/package-host-release.sh:164–172`；工作流调用于 `.github/workflows/publish-images.yml:1227`。
- **风险**：高 / P1，合法 Host 包无法通过资产组装门禁。**Confidence：1.00。**
- **触发路径**：打包器以 `-C "$PACKAGE_DIR" ... backend frontend ...` 生成 `backend/yunlume-backend.jar` → 校验器执行 `tar ... --wildcards '*/backend/yunlume-backend.jar'`。
- **原因/影响**：模式要求 `backend/` 之前还有目录层级，实际归档无该层。tar 找不到成员；pipefail 将错误传出。首次发布、canonical 恢复和已发布验证均使用此 helper。
- **实测**：建立与打包器相同的根级 `backend/` 测试归档，并传入其中正确文件摘要，实际 helper 返回 **exit 2**。
- **具体修复**：按唯一正式归档契约提取 `backend/yunlume-backend.jar`，同时要求成员恰好出现一次、类型为普通文件；不要用宽泛 wildcard 接受额外同名 JAR。若要兼容其他布局，必须先列出允许的布局并排他选择。
- **测试缺口**：把实际 `package-host-release.sh` 的输出直接喂给 verifier 做契约测试；增加缺失、重复成员、错误前缀、错误摘要。现有 helper 测试不能替代 producer-consumer 联测。

## OPS-04 — 发布后 SHA256SUMS 检查将排序后的结果与未排序期望比较

- **位置**：`.github/workflows/publish-images.yml:1651–1654`；对照同文件正确实现 `204–207`。
- **风险**：高 / P1，Release 已不可变发布后 CI 必然失败，滚动别名未提升。**Confidence：1.00。**
- **触发路径**：`publish_release_by_id` 已提交 `draft=false` → `verify_published_release` → 检查 checksum 覆盖集合。
- **原因/影响**：名为 `expected_checksum_names_sorted` 的数组以 Host archive 开头，实际上没有排序；另一边 `awk | sort` 从 `install.sh` 开始。因此完整正确的五个 checksum 条目也被拒绝。此时已跨越不可逆发布边界，不能通过重建/覆盖资产撤销。
- **实测**：对实际同形五成员列表执行比较，exit 1；探针记录完整 expected/actual 顺序。
- **具体修复**：两边均以 `LC_ALL=C sort` 规范化，或在 Python 中验证唯一成员集合并显式拒绝重复。把 preflight 与 publication readback 的整套验证抽为共享模块，避免两份巨大代码再次漂移。
- **测试缺口**：目前缺“正确完整资产必须成功”的 verifier 正向执行测试，以及 `draft=false` 后失败的重跑测试；源码存在性断言检测不到这个错误。

## OPS-05 — immutable 门禁把 owner 层强制策略当作仓库启用的必需条件

- **位置**：`ops/check-immutable-releases-policy.sh:45–48`；`ops/immutable-release-policy-test.sh:17–20`；声明见 `README.md:10–13`、`ops/RELEASE-TRUST-MODEL.md:23–33`。
- **风险**：高 / P1，当前仓库治理配置与发布前提不兼容；不是“immutable 未启用”。**Confidence：0.95（当前阻塞为确定；治理策略取舍需确认）。**
- **触发路径**：已配置有效 Administration:read secret 后，真实仓库响应为任务上下文提供的 `enabled=true,enforced_by_owner=false` → helper 拒绝 → `release-reserve` 在任何 draft 写入前退出。**目前 secret 尚未配置，会更早失败；不能把两项前置失败混为一谈。**
- **原因/影响**：`enabled` 反映此仓库是否启用不可变发布；`enforced_by_owner` 反映 owner 层是否施加强制策略，不能等同“发布资产只有该字段 true 才被锁定”。文档确实主动要求两项 true，但文档重复该断言并不能独立证明产品必须依赖 owner 层治理。它会拒绝已经启用 repository immutable 的当前配置。
- **实测与边界**：把给定真实响应作为测试输入喂给**实际 JSON parser**，exit 1，错误为 `Repository immutable releases must be enabled and enforced by the owner`。本轮 GitHub 官方文档直取/浏览器/raw 源站均因网络失败，未伪称重新调用实时 API 或在线验证字段语义；API 状态来自任务上下文。
- **具体修复**：明确产品究竟要求“此仓库已启用不可变发布”还是额外要求“组织/owner 不允许仓库管理员关闭”。前者应要求 `enabled is True`，将 owner 来源单独记录，且保留最终 Release 的 `immutable:true`、签名和资产验证；后者应在发布配置中显式声明治理要求并提供当前 owner 可落实的配置途径，不能默认把 false 诊断成不可变功能无效。不要自动开启权限或替用户创建 secret。
- **测试缺口**：覆盖 enabled/owner 两个布尔值的组合、类型错误、未授权/缺secret，并用官方 API 契约或真实只读响应作为独立语义依据，不能仅以 mocked `true,true` 自证。

## OPS-06 — Actions failed-jobs 重跑使用消费者的新 attempt 查找旧成功 job 的 artifact

- **位置**：`.github/workflows/publish-images.yml:577、604、640、697、1073`；canonical attempt 约束为 `1256–1267`。
- **风险**：高 / P1，普通瞬态失败不能按 Actions 默认重跑语义恢复。**Confidence：0.98。**
- **触发路径**：attempt 1 的 backend-test 已成功上传 raw JAR，backend-attest 失败；选择“Re-run failed jobs”，后者在 attempt 2 查找名称末尾 `-2` 的 raw artifact，实际唯一产物末尾为 `-1`。同样，publish/release 单独失败时会查找不存在的新 attempt 的 attested JAR。
- **原因/影响**：`github.run_attempt` 是当前消费者执行 attempt，不是 producer artifact 的创建 attempt。成功的 upstream job 不会因仅重跑失败 job 必然重新上传产物。即使改好名称，复用的 reserve 输出仍可能宣告 canonical attempt 1，而 release 在 `1265` 比较当前 attempt 2 后退出。
- **具体修复**：producer 输出不可变 `artifact-id`、自身 run/attempt 与 SHA；consumer 按 producer 输出下载并校验 provenance，不能用自己的 attempt 猜名称。将未 finalized 的 canonical 所有权与 consumer 当前执行解耦，或明确让一个恢复编排重新运行必要依赖；禁止简单使用 `overwrite:true` 抹掉原 artifact。对已持久化 candidate 应复用同一 JAR 字节，而不是靠同源码重编译恰好相同。
- **测试缺口**：新增 backend-attest/publish/release 每个失败节点的“只重跑失败 job”和“全部重跑”矩阵；包含上游输出复用、artifact 过期和 owner finalized 前后。现有函数级事务 mock 未模拟 Actions 调度/上下文。

## OPS-07 — 首次安装失败留下 .install-mode，第二次运行被永久判为残缺部署

- **位置**：`install.sh:497–502、650–652`；退出回滚 `178–237`、Docker 回滚 `845–903` 不恢复这个新建标记。
- **风险**：高 / P1，首次安装的瞬态故障无法按 README 的重跑恢复契约恢复。**Confidence：1.00。**
- **触发路径**：空目录首次安装 → `ensure_install_mode` 写 `.install-mode` → 后续拉镜像/配置/启动失败，VERSION 尚未提交；即使其他文件已正确回滚，标记仍在 → 再运行发现 `INSTALL_MODE_WAS_PRESENT=true` 且 VERSION 不存在，直接拒绝。
- **原因/影响**：mode 标记提前提交且没有“准备中”事务状态；首次创建失败与已完成部署丢失 VERSION 被混为一类。Host 在创建模式标记之后、事务激活之前失败也会命中。
- **实测**：在 `docs/reviews/2026-09-05` 临时目录加载原脚本定义（不执行 main、无 Docker/systemd），第一次 ensure/check 成功；模拟 VERSION 提交前失败，再次 ensure/check exit 1：`已有托管部署缺少 VERSION，不能判断版本兼容性`。
- **具体修复**：将新建 mode 标记纳入事务原始状态和安全清理，或引入明确 durable pending journal 区分未完成新装；仅能自动回收本次创建且未形成已提交部署的状态，不能放开任意“mode 有、VERSION 无”的老部署。锁内安全重跑。
- **测试缺口**：现有 missing-VERSION 负向测试只要求拒绝，没有验证“首次失败→退出回滚→再次安装”。补 Docker pull 失败、Host 配置失败、首次健康失败和 signal 中断后的连续两次调用。

## OPS-08 — Host 没有可持续配置的 HTTPS 代理契约：外置 TLS 被降为 http，手改又被升级覆盖

- **位置**：`deploy/host/yunlume.nginx.conf:52、158、180、202、224`；`deploy/host/app.env.template:28–30`；`install.sh:1401–1414`。
- **风险**：高 / P1，按文档完成外层 HTTPS 后 Host 仍不能提交数据库/Redis 凭据，升级可破坏已有 TLS 配置。**Confidence：0.99。**
- **触发路径**：浏览器 HTTPS → 外置 OpenResty/TLS proxy → Host 受管 HTTP Nginx → loopback backend。Host Nginx 所有相关代理位置均写 `X-Forwarded-Proto $scheme`，这里 `$scheme=http`，覆盖外层 `https`；后端虽然信任 loopback peer，仍正确以 `SecureTransportPolicy.java:25–35` 返回 403。
- **不是默认 HTTP 诊断入口的问题**：README `64、90–105` 已明确 HTTP 仅用于 healthz，这是合理预期。实际缺陷是 README 要求 Host 也接 HTTPS，却没有 Host 专用可持久配置步骤。`README.md:215–226` 只讲 frontend **容器**、WEB_* 变量与 docker compose；这些配置不被 Host 模板消费。全文未提供 Host 的 TLS include/override 方案。直接修改 `/etc/yunlume/nginx.conf` 加 TLS/listener 或代理信任，也会在重装/升级 `1413–1414` 重新 render 覆盖。
- **具体修复**：为 Host 明确定义并实现一种受支持拓扑：原生 TLS 配置拆到管理员持有且升级不覆盖的 include，或外置 TLS + 明确即时代理 allowlist + 仅对匹配来源保留原协议/真实 IP。两者都必须限制绕过代理直连；不能简单无条件透传 XFP 或关闭 `NAV_ALLOW_INSECURE_DATABASE_SETUP`。保留受管路由/限流与管理员证书配置的所有权边界。
- **测试缺口**：缺 Host 外置 TLS→HTTP→backend 的带 Origin 敏感 POST 测试、来源伪造拒绝测试，以及配置 HTTPS 后重装/升级仍保持可用的测试。此次为静态路由推导，不冒充真实服务器 403 验收。

## OPS-09 — 候选签名校验的非零退出被后续 printf 吞掉，命令替换中变成成功

- **位置**：`ops/lib/release-transaction.sh:178–180、198–199`；调用于 `.github/workflows/publish-images.yml:976`，同类传播问题见 `1157–1163`。
- **风险**：高 / P1，镜像来源校验 fail-open；网络/签名拒绝可能被当作已验证候选继续推进。**Confidence：1.00（最小实际 helper 探针已证明）。**
- **触发路径**：工作流 `published_digest="$(publish_candidate_transaction ...)"`。默认 Bash 命令替换不继承 errexit；helper 内 `candidate_verify_attestation` 没有 `|| return`，失败后继续 printf digest，然后成功返回。`resolve_digest` 被包在 `printf 'backend=%s' "$(resolve_digest backend)"` 中，又掩盖子命令退出。
- **实测**：仅 mock registry 的已知一致 digest 与 verifier `return 42`，调用原 helper、保持工作流同形 `set -euo pipefail` 和命令替换；stderr 确实打印注入校验失败，而外层 **exit 0 且拿到 digest**。这是故障注入证据，不是伪造真实签名/API 返回。
- **影响边界**：不能据此声称可以伪造 Sigstore 签名；问题是应执行并成功的 verification gate 没有被强制。后续有资产/归档校验，但不等价于该 OCI build provenance 校验。
- **具体修复**：两处 `candidate_verify_attestation ... || return`；`resolve_digest` 每个外部调用和最终 verify 显式传播失败，先用独立赋值捕获返回状态，再把已验证值写 GITHUB_OUTPUT。不能仅靠全局 `set -e` 或最终 printf 的成功状态；`inherit_errexit` 只能辅助，不能替代函数失败契约。
- **测试缺口**：`ops/release-transaction-test.sh:62` 的 verifier mock 永远成功；增加签名不匹配、无 attestation、403、网络错误在裸调用、if 调用、命令替换三个上下文中的拒绝测试，并执行真实 workflow 提取出的 shell 而不是只检查调用文本。

## OPS-10 — 已发布只读路由未验证滚动别名，发布后中断可能被重跑误报为完整成功

- **位置**：`.github/workflows/publish-images.yml:281–296、1938–1954`；实际别名写入 `1917–1935`；失效的别名检查代码为 `1758–1785`。
- **风险**：中 / P2，发布链完成状态与 `major.minor` 镜像实际指向不一致。**Confidence：0.99。**
- **触发路径**：Release 已变 immutable，但在别名提升前或两组件之间网络失败/任务中断；重跑时 preflight 只验证正式 `X.Y.Z` tag，不读取 `major.minor`。release job 因 published_release=true 被跳过，最终 verification-only job 直接打印 `immutable aliases verified` 并成功。
- **原因/影响**：旧 release job 中虽然还有 `release_draft=false` 分支检查 alias，但 job 级条件 `993` 已排除已发布状态，且其内部 `1525` 再要求 draft=true，因此该分支实际上无法覆盖重跑。后端/前端滚动别名可以缺失、停在旧版或一新一旧，而 CI 声称完整验证通过。OPS-04 还会稳定制造“已发布但未提升”的边界。
- **具体修复**：把 alias 的只读一致性验证放到可达的 preflight/verification-only 路由。继续坚持已发布重跑零写入：检测部分完成就明确失败并输出差异，不要自动覆盖。若产品需要自动修复，另设显式授权的 alias 恢复操作，校验完整 immutable Release、两摘要和最新版本单调性后才写，不能让旧版本重跑倒退别名。
- **测试缺口**：补 `draft=false` 后、backend alias 后、frontend alias 后的中断注入，以及 missing/stale/mixed alias 场景，要求只读重跑既零 mutation 又绝不假绿。

## 两项父审查候选的裁定

1. **Host TLS：成立**，见 OPS-08。README 只有 Docker 专用 OpenResty/WEB_* 路径，没有 Host 可持久 TLS 配置契约；没有把“HTTP 仅诊断”误报为缺陷。
2. **手工 rollback 不查 manifest/epoch：作为已明示的应急例外，不另计实质问题。** README `472` 明确只切本地已拉取镜像、要求外部备份确认，并明确“不能保证新 schema 与旧应用兼容”。脚本又固定使用源码仓库的 `docker-compose.yml`（`ops/lib/common.sh:72–78`），不是正式安装目录 `compose.yml`，因此不能直接推断它承诺同步正式 installer 的 VERSION/manifest。其残余操作风险确实包括参数可跨 owner/跨版本、无 epoch 检查；建议文档显式限定只用于手工 Compose 应急，禁止把它当正式 installer 的代际安全降级入口，或另提供接收完整 Release manifest 的受支持工具。该建议不是对旧版回滚错误恢复逻辑的重复报告。

## 已核对的设计与可精简方向

- `install.sh`、手工 rollback 和卷迁移共用 `/run/lock/yunlume-operations.lock`，基本跨操作排斥方向正确。独立发行入口保留必要自包含代码是合理的，**不建议为减少重复而让 Release installer 运行时从未固定来源拉 helper**；可从共享源码生成自包含发行脚本并比较生成结果。
- Compose 不负责创建 PostgreSQL/Redis；上传/config named volume 与运行身份、healthcheck、敏感配置目录权限已核对。迁移 helper 固定本机镜像 ID、无网络、只读源/no-copy-up、目标 run 标签及失败保留是有意的数据保护措施；未运行迁移。
- OCI archive byte SHA 与 root manifest digest 已区分；canonical bundle、manifest、资产集合、JAR 的多层校验方向合理，但以上执行/权限/路径问题使这些设计尚不能等同已验证的真实发布链。
- 优先消除的是**验证逻辑重复**而非日志或安全检查：tag 解引用五份、published verifier 两大份、预期 checksum/asset 清单多份。OPS-01/04/10 已展示实际漂移后果；应合并为共享、可独立执行的 verifier。
- Host 和 frontend Nginx 大量路由/限流片段可从共同契约生成，同时保留各自 proxy 信任与文件路径的差异，避免为 DRY 破坏安全来源边界。

## 验证限制与交付

- GitHub REST 文档提取未取得有效正文，raw 源站两次各 30 秒超时；浏览器备用路径因依赖下载超时也不可用。未重新验证在线 Actions SHA、固定 gh 二进制 hash、真实 OCI/Sigstore 资产，也未把源码中的自述当作该验证证据。
- 任务给定 `enabled=true,enforced_by_owner=false`、secret 未配置均作为提供的现场上下文，报告明确区分它们与本轮探针。
- 本轮没有本地/远程编译、容器或服务启动、GitHub 写入、真实业务数据写入。若修复后需要完整流水线/Host TLS 联测，应在用户指定远程环境另行授权，不能用本报告的静态探针代替验收。
- 交付：`operations.md`、`operations-files.json`；附可复现证据 `operations-probes.py`、`operations-probes.json`。


---
# 附录：前端

# nav-frontend 全量只读审查

## 范围与结论

- 审查对象：`<repository-root>/nav-frontend` **当前工作树的全量自有文件**，不是仅审查 diff。包括未跟踪的新源码/测试。
- 覆盖 **152/152 文件**：源码 86、测试 39、配置 12、样式 15。逐文件清单、行数、内容 SHA-256 见 `frontend-files.json`；未检查文件为零。排除 `node_modules`、`dist`、依赖 lockfile、Git 内部文件和 TypeScript 构建缓存；重新核对过包含 `lock` 的文件名，只有 `package-lock.json`，未误排自有源码。
- 保留 **7 个实质问题**：1 个 P1、6 个 P2。没有将纯样式偏好、一般防御性代码或推测性安全漏洞计入问题。
- 以下均为**静态源码与调用链验证**，不是声称已运行复现。没有本地测试、编译、lint、安装依赖；没有远程构建或部署。指定远程构建约束未被触发。本轮不得据此宣称测试通过或验收通过。
- 仓库存在大量预先修改及未跟踪文件；本审查没有改写、stash、reset、提交或推送仓库。只在 `docs/reviews/2026-09-05/` 写报告和清单。读取到的 HEAD 是 `e5c65f08b7ea2d82317d9350e45bbcb2800dbbfa`，结论以清单记录的工作树内容为准，不以 HEAD 文件为准。

风险标签指**建议修改的风险**：SAFE＝局部契约修正；CAREFUL＝需覆盖异步交错、状态或跨组件行为；RISKY＝需要重新设计核心协议。本报告不建议重写认证持久化协议。

## F1 — P1：登录成功分支没有返回值，登录页始终跳过成功导航

- **定位**：`src/stores/auth.store.ts:89-111`，尤其 `103-105`；调用方 `src/views/admin/LoginView.vue:19-26`。
- **触发条件**：正常提交正确账号密码，API 成功，token 与用户信息均持久化成功。
- **调用链/证据**：`LoginView.submit → authStore.login → loginApi`。store 的成功分支设置 token/user 后直接走 finally 并结束，没有 `return result` 或其他成功标识；因此 action resolve 为 `undefined`。调用方先 `const result = await authStore.login(form)`，紧接着 `if (!result) return`。
- **影响**：正常登录也停留在登录表单，既不展示欢迎消息，也不执行 `router.replace(redirect)`。持久化实际上已经成功，导致“界面未登录/会话已登录”的矛盾体验；刷新或后续导航可能才由路由守卫带入后台。该条件不是网络竞态，正常成功路径即成立。
- **建议**：明确 action 的返回契约，例如 `Promise<LoginResult | undefined>`，只有被当前登录操作接受并成功持久化的成功分支返回 `result`；保留被 supersede 的旧请求返回 `undefined`、持久化失败抛错的设计。不要简单删掉调用方对旧请求的保护。
- **confidence**：高（确定的控制流）；**修改风险**：SAFE。
- **测试缺口**：`auth.store.test.ts` 的登录成功测试验证 token/user 持久化，未验证 action 返回值；没有实际挂载 LoginView 的成功提交→导航测试。应同时覆盖正常成功、持久化失败、同页旧请求被新请求替代三种返回/导航行为。

## F2 — P2：跨标签页新登录不更新旧登录操作版本，旧失败仍会清除新会话

- **定位**：`src/stores/auth.store.ts:52-55,70-87,89-108,166-175`；同类清理路径 `158-164`。
- **触发条件**：标签 A 的登录请求仍在等待；标签 B 登录成功写入新 token/generation/commitment；随后 A 的旧请求失败。
- **调用链/证据**：B 写 storage → A 的 `subscribeAuthStorage` → `reconcileSession(true)` 更新 A 的 token/身份，但不更新 `loginRequestVersion`。A 的 catch 只比较自身的 `requestVersion`，仍认为旧请求是当前操作，然后调用 `clearSession()`；后者直接 `tokenStorage.remove()`，删除的是此时共享 storage 中 B 的新会话。
- **影响**：新登录被另一个标签页的旧失败踢掉；持久化协议和请求拦截器中的 generation/commitment 保护未延伸到所有业务 action。`changePassword/logoutAll` 的成功回调也在 await 后无身份比较地清理会话，存在旧操作清除随后建立的新身份的同类窗口（需与服务端撤销语义区分，不能假定新身份本应被撤销）。
- **建议**：让登录操作失效与跨标签页 authority 变更关联；在失败清理和其他 await 后清理前确认当前 durable authority 仍属于本操作。保留“同一会话重新登录失败应清理”的已有策略，不能靠一律不清理来规避问题。账号页面成功后的导航也应跟随该操作是否仍属于当前会话。
- **confidence**：高（跨标签页交错可由源码直接推出）；**修改风险**：CAREFUL。
- **测试缺口**：`auth.store.test.ts:320-337` 仅模拟同一 store 连续两次 login，第二次自然递增版本；storage 同步测试独立于 pending login。补充独立操作版本、共享持久层的双标签测试：B 成功并发送 storage 事件，再让 A reject；新 token、绑定用户和页面应保持。密码修改/退出全部也需旧响应与新登录交错用例。

## F3 — P2：预检过期事件可以覆盖 CONFIRMING，提前解除导入互斥

- **定位**：`src/components/admin/DataImportPanel.vue:51-54,171-218,418-425`；`src/components/admin/ImportPreviewDialog.vue:74-90,94-96`。
- **触发条件**：在预检到期前确认导入，确认请求仍 pending 时到达 expiresAt；或任务运行期间重开旧预检对话框直到其过期。
- **调用链/证据**：ImportPreviewDialog 的计时器在可见时更新 `currentTime`，`watch(previewExpired)` 无条件 emit expired，即使 `submitting` 为 true。父组件 `@expired="state = 'BLOCKED'"` 无条件覆盖状态。`busy` 仅认 UPLOADING/PREVIEWING/CONFIRMING/RUNNING，BLOCKED 会解锁选文件、上传与对话框关闭；confirm 的 pending 状态也因此丢失。
- **影响**：已提交且不能伪取消的操作仍在服务器处理中，页面却允许换文件/再次预检。旧 confirm、进度轮询与新 preview 的回调可轮流覆盖 state/preview，UI 不再可靠代表当前实际任务。这不是“同一 token 必然重复写库”：补充检查的后端 `PortableDataPackageService.java:198-250` 有按预检 token 返回已有 job 的幂等保护，该防御应保留。
- **建议**：仅当父状态仍属于可确认的预检阶段时响应 expired；CONFIRMING/RUNNING 的互斥不能由预检时钟解除。将预检有效性作为独立派生值，并给异步操作结果加所属操作校验，而不是让预检、提交和任务共用一个可被任意事件改写的状态。
- **confidence**：高；**修改风险**：CAREFUL。
- **测试缺口**：现有 `DataImportPanel.test.ts` 仅检查源码字符串。需要真实组件与 fake timer：确认 Promise pending→推进至过期→断言仍在确认且不能替换文件；再分别 resolve/reject，并确认仅正确任务更新 UI。

## F4 — P2：卸载的导入组件仍可清除新组件保存的任务恢复记录

- **定位**：`src/components/admin/DataImportPanel.vue:155-168,190-206,221-260,343-347`；`src/utils/dataTransfer.ts:325-351`。
- **触发条件**：离开导入页时已有 poll/confirm HTTP 请求在途，随后重新进入导入页；旧响应晚于新组件的状态更新返回。
- **调用链/证据**：卸载只设 `disposed`、递增 restore 版本并清除 timer。`restoreJob()` 检查 disposed，但 `pollJob()` 的 await 后只比较旧实例自己的 jobId，`confirmImport()` 也没有 disposed/操作版本保护。旧 poll 收到终态后仍调用 `safeClearJobSession()`；工具函数不比较 jobId，直接删除固定 sessionStorage key。
- **影响**：例如旧实例任务 A 的 poll 很慢，重新进入后新实例已经观察 A 完成并提交 B、保存 B；A 的旧终态响应随后删除 B 的恢复记录。刷新后只能依赖 current-job 发现，而服务端 `currentJob()` 对已完成任务返回 404（后端 `PortableDataPackageService.java:266-273`），因而可能失去最终结果的可恢复入口。旧 confirm 的迟到写入同样可能覆盖更新的任务记录。仅阻止下一个定时器并不能阻止已发请求的副作用。
- **建议**：为 preview/confirm/poll 的成功与错误回调统一增加实例生命周期/操作版本判定；取消客户端请求只能取消观察，不能宣称取消服务端任务。session 清理应限定为仍匹配当前 jobId 的记录；在需要保留已提交任务可恢复性的地方明确移交观察责任。
- **confidence**：高；**修改风险**：CAREFUL。
- **测试缺口**：缺失 mount→poll pending→unmount→新实例写 job B→旧 poll resolve terminal 的测试。另补旧 confirm resolve/reject、存储写失败与 current-job fallback 的组合；不能只断言有 `disposed` 字符串。

## F5 — P2：搜索引擎可见性另写了一套无互斥逻辑，快速点击可覆盖最后意图并错误回滚

- **定位**：`src/views/admin/SearchEngineManageView.vue:151-159,269-272,333-337`；`src/api/searchEngine.api.ts:27-33`。已有可复用实现：`src/utils/visibilityMutation.ts:8-27`。
- **触发条件**：一次启用/停用请求未完成时，再次切换同一个引擎；网络较慢或请求乱序时更易触发。
- **调用链/证据**：两套桌面/移动 switch 都直接 v-model 改 row.visible，均未设置 pending/disabled。每次 change 都立即发 PUT；loading 遮罩只在成功后的 `load()` 才启用。错误分支用 `row.visible = !row.visible` 反转的是回调发生时的当前值，而不是该请求提交前的值。
- **影响**：后发意图可能被先发请求的迟到处理覆盖；失败回调也可能反转另一次操作的状态。分类、书签已经通过 `commitVisibleChange` 加按 ID 互斥和 captured requestedVisible 回滚，而搜索引擎的重复实现遗漏这些保护。
- **建议**：复用按 ID 的可见性提交模式，并在桌面、移动两处 switch 同步禁用/显示 pending；确认服务端对默认引擎的联动后再决定如何合并响应或重读，不能假设只修改单个字段就足够。若采用可连续点击的乐观队列，则需显式序列化，不能仅添加结果版本号而让写请求仍乱序。
- **confidence**：高；**修改风险**：CAREFUL。
- **测试缺口**：已有 `visibilityMutation.test.ts` 不覆盖该页面，因为该页面没有调用该工具。补同一 ID 双击、一成功一失败、反序响应，以及默认引擎受停用影响的联动测试。

## F6 — P2：搜索引擎任何非排序刷新都会静默丢弃排序草稿

- **定位**：`src/views/admin/SearchEngineManageView.vue:47-57,71-77,113-120,128-168`。
- **触发条件**：修改某些行的排序输入但尚未点击“保存排序”，再启用/停用一个引擎、设为默认，或新增/编辑/删除另一条记录。
- **调用链/证据**：排序修改只保存在 `sortDraft`；上述每条成功 action 都调用 `load()`，而 load 无条件用服务端所有行的 sortOrder 重建整个 `sortDraft`，没有 dirty merge、保存或放弃确认。
- **影响**：正常单用户、无并发的操作就会丢失未保存排序，且随后 `sortChanged` 变为 false、“保存排序”禁用。批量调整较多行时会造成实际编辑工作丢失。此问题独立于 F5 的网络并发。
- **建议**：分别保存服务端基线和被编辑的 ID→草稿；普通刷新对仍存在的 dirty 行保留草稿，只清除已删除 ID；仅成功保存排序后重置对应草稿，或者在会丢草稿的操作前明确确认。避免把完整 draft 每次简单重建当作通用刷新的一部分。
- **confidence**：高；**修改风险**：CAREFUL。
- **测试缺口**：缺少搜索引擎管理的组件级测试。补“改排序→设默认/切可见→排序草稿仍在”、“删除 dirty 行仅删对应草稿”、“排序保存中再次编辑不被旧响应吃掉”等用例。

## F7 — P2：卸载后的 Portal 搜索引擎请求仍会覆盖用户的新偏好存储

- **定位**：`src/views/portal/PortalHome.vue:92-119,122-127,152-169`；`src/utils/searchEnginePicker.ts:28-47,60-65`。
- **触发条件**：门户实例 A 的搜索引擎加载/重试较慢；用户离开后再进入，实例 B 先完成请求并选择新的引擎；随后 A 的请求才返回。
- **调用链/证据**：A 的 `fetchSearchEngines()` await 后未校验生命周期/请求版本，使用 A 自己的 `activeEngineId/persistedEngineId` 解析选择并调用 `persistSearchEngineId()` 写共享 localStorage。`onBeforeUnmount` 只移除 resize 和恢复 document 元信息。B 选择引擎也写同一个 storage key，因而可被 A 的旧回调覆盖。
- **影响**：当前 B 页面仍显示用户刚选的新引擎，但刷新后恢复为旧偏好，形成内存与持久化状态不一致。站点、导航 store 已有 requestVersion 保护；搜索引擎加载的另一套实现没有等价保护。
- **建议**：组件卸载后不允许旧请求写入偏好；如将搜索引擎请求移入 store，应集中处理请求版本和显式用户选择版本。保留“用户在本次加载过程中主动选择优先于默认值”的规则，不应粗暴让服务端默认覆盖用户选择。
- **confidence**：高（有明确共享存储副作用，非单纯卸载后 ref 赋值）；**修改风险**：CAREFUL。
- **测试缺口**：`PortalHome.test.ts` 仅测试源码包含关系及 viewport 逻辑。需独立 mount A/B 和可控 Promise，先让 B 成功选择、再 resolve A，断言 localStorage 和下次 mount 的选择保持 B。

## 已核查但不应当作问题删除的防御

- `storage.ts` 的 envelope/barrier、generation+commitment、canonical JSON、写后回读、失败登出本地屏障、绑定用户元数据均承担认证一致性职责，不能为了“重复/复杂”而合并成普通 JSON storage。代码明确声明 localStorage 无 CAS、全部持久化写失败时无法保证跨 reload 撤销的边界；本报告不把该已声明的平台限制冒充新缺陷。
- `request.ts` 已限制管理 token 的发送范围，区分 captured logout，并用 token+generation+commitment 阻止迟到旧 401/403 撤销新会话；F2 指的是业务 action 的清理路径，不是否定这部分拦截器防御。
- 普通偏好存储、登出网络撤销的 best-effort catch，与有任务语义的错误处理不同；不能一律把这些 catch 认作异常吞掉漏洞。
- 站点配置版本冲突锁表单、未保存离开确认、图片上传中禁止保存、安装密码/CA 内存清理及短期票据流程应保留。
- 导入响应解析、存储恢复后的服务端 current-job fallback、任务 ID 校验、任务丢失时不伪称回滚，均有实质防御价值。后端 confirm 已有幂等处理，不报告未经证实的“重复 confirm 必定重复导入”。

## 验证与后续边界

本报告提供的是静态全量审查证据和可执行的测试设计，不提供虚构的测试结果。建议先修 F1，再用真实组件测试覆盖 F2—F7 的交错；只有用户再次授权验证时，才在指定远程服务器执行测试/构建。`frontend-files.json` 可用于后续核对报告对应的文件版本；工作树若继续变化，需要重新验证相关行号和结论。


---
# 附录：后端与数据库

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


---
# 附录：跨模块重复与精简

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


---
# 机器汇总：覆盖与工作树一致性

```json
{
  "coverage": {
    "inventory_count": 417,
    "section_covered_inventory_count": 411,
    "remaining_not_in_section_manifests": [
      ".gitattributes",
      "ACCEPTANCE.md",
      "jihua.md",
      "jihua2.md",
      "nav-frontend/package-lock.json",
      "wenti.md"
    ],
    "outside_baseline_scope": [],
    "backend_depth": {
      "total": 223,
      "tracked": 177,
      "untracked": 46,
      "review_modes": {
        "full-static-read": 182,
        "partial-static-read": 2,
        "test-structure-and-assertion-scan": 39
      }
    },
    "backend_not_deep_read": [
      "nav-backend/pom.xml",
      "nav-backend/src/test/java/com/example/nav/AuthIntegrationTest.java",
      "nav-backend/src/test/java/com/example/nav/common/config/DataInitializerTest.java",
      "nav-backend/src/test/java/com/example/nav/common/config/ExternalRedisPropertiesTest.java",
      "nav-backend/src/test/java/com/example/nav/common/config/JwtPropertiesTest.java",
      "nav-backend/src/test/java/com/example/nav/common/config/PersistedDatabaseEnvironmentPostProcessorTest.java",
      "nav-backend/src/test/java/com/example/nav/common/config/PersistedRedisEnvironmentPostProcessorTest.java",
      "nav-backend/src/test/java/com/example/nav/common/config/PostgresqlMigrationRunnerTest.java",
      "nav-backend/src/test/java/com/example/nav/common/config/ProductionRedisConfigurationValidatorTest.java",
      "nav-backend/src/test/java/com/example/nav/common/config/RedisCacheTransactionConfigurationTest.java",
      "nav-backend/src/test/java/com/example/nav/common/security/SecureTransportPolicyTest.java",
      "nav-backend/src/test/java/com/example/nav/common/validation/SafeUrlDtoValidationTest.java",
      "nav-backend/src/test/java/com/example/nav/common/validation/SafeUrlRulesTest.java",
      "nav-backend/src/test/java/com/example/nav/module/category/service/impl/CategoryServiceImplTest.java",
      "nav-backend/src/test/java/com/example/nav/module/datapackage/service/PortableDataPackageExpiryTest.java",
      "nav-backend/src/test/java/com/example/nav/module/datapackage/service/PortableDataPackagePostCommitTruthTest.java",
      "nav-backend/src/test/java/com/example/nav/module/datapackage/service/PortableImportCommitStoreTest.java",
      "nav-backend/src/test/java/com/example/nav/module/datapackage/service/PortableImportJobStoreProfileTest.java",
      "nav-backend/src/test/java/com/example/nav/module/datapackage/service/PortableImportJobStoreTest.java",
      "nav-backend/src/test/java/com/example/nav/module/health/controller/HealthControllerExternalRedisTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/DatabaseConfigurationStoreTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/DatabaseConnectionTicketStoreTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/DatabaseSetupServiceGuardIntegrityTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/DatabaseSetupServiceValidationTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/InstallAccessServiceTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/InstallServiceTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/InstallTransactionServiceIdentityTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RealRedisTestGuard.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RedisAtomicProbeContractTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RedisCiEnforcementContractTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RedisConfigurationStoreTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RedisConnectionTicketStoreTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RedisConnectionVerifierTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RedisRealAclIntegrationTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RedisRuntimeAclContractTest.java",
      "nav-backend/src/test/java/com/example/nav/module/install/service/RedisSetupServiceValidationTest.java",
      "nav-backend/src/test/java/com/example/nav/module/publicdata/PublicDataCacheVersionTransactionTest.java",
      "nav-backend/src/test/java/com/example/nav/module/publicdata/service/impl/PublicDataServiceImplTest.java",
      "nav-backend/src/test/java/com/example/nav/module/site/service/impl/SiteConfigServiceSingletonTest.java",
      "nav-backend/src/test/java/com/example/nav/module/upload/service/ImageUploadServiceTest.java",
      "nav-backend/src/test/resources/application.properties"
    ],
    "duplication_scan_count": 417,
    "duplication_manually_read_count": 49
  },
  "worktree": {
    "baselineFiles": 417,
    "changedContent": [],
    "addedPaths": [],
    "removedPaths": [],
    "head": "e5c65f08b7ea2d82317d9350e45bbcb2800dbbfa"
  }
}
```

## 实际登录 action 远程隔离执行

```json
{
  "sourceSha256": "4e4904c6b24a14dc7c7ef341f897e8a19c371ac7581f0cabe416f86fc5f1b591",
  "method": "actual action bodies extracted unchanged except TypeScript function signatures; mocked API/storage fixtures; not mounted Vue/browser integration",
  "host": "[private-server-redacted]",
  "exitCode": 0,
  "stdout": "{\"case\":\"F1 extracted action successful login\",\"resolvedUndefined\":true,\"persisted\":true,\"loginPageWouldSkipNavigation\":true}\n{\"case\":\"F2 extracted actions cross-tab interleaving\",\"newSessionBeforeOldFailure\":\"fixture-B\",\"newSessionAfterOldFailure\":null}\n",
  "stderr": ""
}
```

## 发布/安装真实 helper 远程故障注入

```json
{
  "host": "[private-server-redacted]",
  "method": "isolated fixtures using current actual helper source, no GitHub/API/Docker/systemd mutations; fixture tar is explicitly not a release artifact",
  "exitCode": 0,
  "stdout": "[\n  {\n    \"name\": \"tag-arithmetic-commit\",\n    \"exit_code\": 1,\n    \"stdout\": \"\",\n    \"stderr\": \"bash: line 1: commit: unbound variable\\n\"\n  },\n  {\n    \"name\": \"tag-arithmetic-tag\",\n    \"exit_code\": 1,\n    \"stdout\": \"\",\n    \"stderr\": \"bash: line 1: tag: unbound variable\\n\"\n  },\n  {\n    \"name\": \"attestation-failure-command-substitution\",\n    \"exit_code\": 0,\n    \"stdout\": \"returned success with digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\n\",\n    \"stderr\": \"INJECTED verifier exit 42\\n\"\n  },\n  {\n    \"name\": \"packager-layout-vs-jar-verifier\",\n    \"exit_code\": 2,\n    \"stdout\": \"\",\n    \"stderr\": \"\"\n  },\n  {\n    \"name\": \"checksum-list-order\",\n    \"exit_code\": 1,\n    \"stdout\": \"expected=yunlume-host-v1.2.3.tar.gz yunlume-host-v1.2.3.tar.gz.sha256 install.sh release-manifest.json yunlume-compose.yml\\nactual=install.sh release-manifest.json yunlume-compose.yml yunlume-host-v1.2.3.tar.gz yunlume-host-v1.2.3.tar.gz.sha256\\n\",\n    \"stderr\": \"\"\n  },\n  {\n    \"name\": \"fresh-install-mode-marker-retry\",\n    \"exit_code\": 1,\n    \"stdout\": \"first preflight accepted; simulating failure before VERSION commit\\n\",\n    \"stderr\": \"ERROR: 已有托管部署缺少 VERSION，不能判断版本兼容性\\n\"\n  },\n  {\n    \"name\": \"repository-enabled-not-owner-enforced-policy\",\n    \"exit_code\": 1,\n    \"stdout\": \"\",\n    \"stderr\": \"Repository immutable releases must be enabled and enforced by the owner\\n\"\n  }\n]\n",
  "stderr": ""
}
```

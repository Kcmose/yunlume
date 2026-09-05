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

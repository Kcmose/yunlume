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

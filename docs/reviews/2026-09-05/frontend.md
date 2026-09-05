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

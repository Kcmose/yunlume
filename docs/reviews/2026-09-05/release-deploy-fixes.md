# 发布、部署与回滚 10 项修复

基线提交：`7957b9c9c65df31fa7e717bad23bad3402901ec3`。修复基于此前前端 11 项、后端及数据导入 14 项已修复但尚未提交的工作区。原审计报告保留，本文记录本轮实际修复及验证，不代表已经发布到 GitHub 或部署到生产。

## 范围与改动

本轮对应重新逐项核实的 OPS01–04、OPS06–10 以及 OH1，共 10 项。OPS05 的仓库所有者强制不可变策略是既有治理要求，不作为代码缺陷放宽。

| 编号 | 已确认问题 | 修复 |
| --- | --- | --- |
| OPS01 | Bash 算术循环把 tag 类型字符串当变量，合法 tag 不能正常解析 | 五个发布边界共用显式字符串判断，支持轻量及最多 16 层附注 tag；移动、歧义和 API 失败均拒绝 |
| OPS02 | 镜像发布 job 缺少草稿资源写入及证明签发权限 | 按实际职责声明 `contents`、`attestations`、`id-token` 写权限；测试 job 保持只读，JAR 签名 job 保持不执行仓库脚本 |
| OPS03 | Host 校验器要求多余的归档父目录，与正式打包布局冲突 | 精确校验根路径 `backend/yunlume-backend.jar` 的普通文件及流式 SHA-256，拒绝重复路径、链接和替代父目录 |
| OPS04 | 总校验和的预期排序不一致，合法发行资产无法通过复核 | 校验确切文件集合、唯一性及每项 SHA-256，不依赖 writer 顺序；遗漏、重复、额外文件或摘要错误均拒绝 |
| OPS06 | 重跑混用消费 attempt 与生产 attempt，可能下载失败或替换 JAR | 按 artifact ID、生产 run/attempt、源码及归档摘要校验 API 元数据；草稿保留 `backend-jar-producer.json`，重跑沿用原 JAR；签名完成后恢复原资产字节及原 canonical 身份 |
| OPS07 | 首次安装失败留下模式标记，后续被误判为残缺升级 | 仅在首次回滚及清理成功后创建绑定 `.install-mode` 身份的私有重试许可；下次校验后消费，支持连续失败后再试 |
| OPS08 | Host 丢失外层 HTTPS 协议，升级覆盖手工代理设置 | 从 `app.env` 读取持久 Host 配置，按原始直属代理地址接受协议头；绑定限制、原文件权限校验、旧监听回滚与未完成向导同版本配置应用保持一致 |
| OPS09 | 签名/API/镜像检查在命令替换及条件调用中吞掉失败 | 对外部命令和解析显式传播退出码；有效 stdout 伴随失败退出也拒绝，注册表权限故障不能误作不存在 |
| OH1 | 镜像恢复时忽略 `/healthz` 失败并删除备份、误报健康 | 三个 HTTP/JSON 探测均显式检查；恢复不健康退出 2 并保留 `.env` 备份，成功恢复仍保留原操作的失败状态 |
| OPS10 | 已发布重跑未检查滚动别名，却宣称验证成功 | 完整只读路径实际检查前后端 `major.minor` 摘要；缺失、过旧、混合或不可读时失败且零外部写入 |

## 恢复与兼容边界

- 候选 OCI 仍按已提交 digest 恢复；新增 JAR producer 描述属于签名资产集合。既有草稿已有后端候选却缺少 producer 描述时拒绝猜测补造来源。原 Actions artifact 在签名资产完成前过期时失败关闭；完整不可变发行的只读验证不要求 artifact 仍在保留期内。
- 未完成 canonical 签名的草稿由实际执行的当前 run/attempt 签名；`canonical-owner.json` 已存在时只恢复原资产。Release job 启动时重新读取实时发布状态，不能相信旧 attempt 缓存的 `published=false`。
- 已发布版本的滚动别名若已经指向后续版本，本版本重跑会报告不匹配。此路径只检查，不自动修改滚动标签。
- Host 代理设置保存在 root 所有且不可被组/其他用户写入的 `/etc/yunlume/app.env`，三个 `HOST_*` 字段随升级保留。使用独立外层 TLS 配置；不支持直接在生成的 Nginx 配置中手改 TLS 后继续覆盖升级。
- 首次重试不会清空已有数据目录。回滚、清理失败或 `SIGKILL` 时不授予重试许可，需核对恢复材料；不能删除 `.install-mode` 绕过恢复门禁。

## 验证

所有脚本运行、集成测试及归档操作均在约定的专用打包服务器完成。本地只编辑、审查和核对文件摘要。远端每个源文件传输均核对 SHA-256；普通脚本在无网络、无 Docker socket 的容器运行，故障注入只替换 GitHub、Docker、systemd 等外部边界。发布行为测试使用经官方 SHA-256 核验的 [jq 1.8.2](https://github.com/jqlang/jq/releases/tag/jq-1.8.2) 独立二进制，只挂载到测试容器，未安装系统包或改变项目依赖。

已通过的关键行为验证包括：

- `bash ops/release-transaction-test.sh`：正式打包器生成归档后交给真实校验器；11 类异常 tar 与错误 JAR 摘要均拒绝，原候选事务回归通过。
- `bash ops/release-failure-propagation-test.sh`：120 种调用上下文、Shell 选项及失败组合通过。
- `bash ops/publish-workflow-behavior-test.sh`：53 个真实 Bash、Python、jq 场景通过，覆盖多层 tag、checksum、生产 artifact、两类重跑、canonical 归属、实际发布后校验函数和完整已发布复核，外部写入为 0。
- `bash ops/install-first-retry-test.sh`：25 组真实安装/回滚函数场景通过，覆盖 Docker、Host、连续失败、INT/TERM/KILL、恢复失败及许可篡改。
- `bash ops/rollback-release-test.sh`：恢复路径实际执行，健康失败、有效 JSON 伴随非零退出及备份保留断言通过。
- 原模式、版本转换、兼容代际、清单、发布链接、不可变策略及工作流结构回归通过。原版本测试因校验逻辑迁出 workflow 产生一次结构断言失败，已同步读取新的 preflight 并复测通过。
- `actionlint 1.7.7 -shellcheck= -pyflakes= .github/workflows/publish-images.yml` 通过；这项不包含外部 ShellCheck/Pyflakes，Shell/内嵌逻辑另由语法及行为测试覆盖。

- 真实 Host TLS 链 15 项通过：外层 Nginx TLS → 受管 Host Nginx → 已验证的真实后端 JAR，使用临时可信 CA、隔离网络及 H2，不开放宿主机端口。直接伪造代理头和异源请求拒绝；数据库/Redis 4 个提交入口到达真实业务检查；完成管理员初始化及登录成功；实际 `app.env` 更新、模板渲染及 Nginx reload 后 HTTPS 登录仍成功，代理设置和外层 TLS 配置摘要保持不变。
- 正式 `ops/package-host-release.sh` 使用上轮已测试的真实 JAR、前端 dist 打包成功；内嵌 JAR SHA-256 与原产物一致，归档 sidecar 校验通过。本轮不改应用源码，未重复编译。

- `bash ops/host-proxy-config-test.sh` 通过；`bash ops/host-proxy-apply-test.sh` 的 27 个真实安装/恢复场景通过：11 个状态转换、10 个变更前拒绝和 6 个应用前/应用后/恢复后的三阶段检查。新增恢复夹具曾遗漏临时发行清单，按真实 `load_manifest` 契约补齐后复测通过，生产判定和断言未放宽。
- 31 个 `.sh`/`.envsh` 逐文件 `bash -n` 通过，4 个 ops Python 文件 AST 语法检查通过；最终 `git diff --check` 通过。

共 16 组脚本回归最终通过，完整命令如下：

```bash
bash ops/release-transaction-test.sh
bash ops/release-failure-propagation-test.sh
bash ops/rollback-release-test.sh
bash ops/host-proxy-config-test.sh
bash ops/host-proxy-apply-test.sh
bash ops/install-runtime-config-test.sh
bash ops/install-first-retry-test.sh
bash ops/compatibility-epoch-rollback-test.sh
bash ops/mode-protection-test.sh
bash ops/release-manifest-test.sh
bash ops/release-version-test.sh
bash ops/version-transition-test.sh
bash ops/install-release-url-test.sh
bash ops/immutable-release-policy-test.sh
bash ops/publish-workflow-test.sh
bash ops/publish-workflow-behavior-test.sh
```

Host 权限测试在 root 隔离容器中运行，CI 使用 `sudo bash`。源码比对确认本轮应用目录未发生改动，保留此前前端、后端及导入修复。独立测试容器、内部网络和临时 TLS 私钥已清理；原有服务未更换。

测试中签名服务使用可控外部故障桩，摘要、归档、JSON、jq 和调用链保持真实。未创建 tag、未推送镜像、未触发 GitHub Release，因而不把这些结果描述为在线 GitHub 权限或真实签名签发验收。

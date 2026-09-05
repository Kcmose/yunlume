# 2026-09-05 跨设备开发交接

后续已完成的前端 11 项修复及验证见 [frontend-fixes.md](frontend-fixes.md)。以下内容描述原交接快照，原问题报告保留作历史定位依据。

后端与数据导入 14 项的本轮修复及验证见 [backend-import-fixes.md](backend-import-fixes.md)。

发布、部署与回滚 10 项的修复及验证见 [release-deploy-fixes.md](release-deploy-fixes.md)。

测试脚本与 CI 检查 3 项的修复及验证见 [test-ci-fixes.md](test-ci-fixes.md)。

推送后本地复核新增 10 项的修复及验证见 [local-review-fixes.md](local-review-fixes.md)。

## 原交接状态

本提交保存此前尚未提交的代码、测试、安装/发布脚本和本轮只读审查材料，供另一台设备继续修改。**不是完成修复的发布版本，也不是验收通过声明。** 本輪审查未修改应用源码；代码修改来自审查前的工作区。审查指出的问题仍待逐项修复。

- 审查前基线 HEAD：`e5c65f08b7ea2d82317d9350e45bbcb2800dbbfa`。
- 审查对象：当时工作区的 417 个非忽略文件，包含未提交和未跟踪文件，而非仅该 HEAD。
- 完整报告：[Yunlume-Code-Review.md](Yunlume-Code-Review.md)。
- 分组定位：[前端](frontend.md)、[后端](backend.md)、[发布安装](operations.md)、[重复与精简](duplication.md)。
- 分组详报及源码是问题定位依据；总结中的额外建议不能替代逐项复现。
- 覆盖深度：[coverage-summary.json](coverage-summary.json)。部分后端测试仅做结构/断言审查，不是所有文件逐行深读。
- 最小探针：`auth-control-flow-repro.json`、`operations-remote-probes.json`、`tag-loop-repro.json`。故障注入用例不是在线 API、真实签名或整体验收证据。
- `baseline.json` 和 `worktree-verification.json` 是审查阶段的历史快照，验证报告写入前应用工作区未被审查修改，不应当作本提交的 Git 文件清单。

## 继续开发

先处理登录返回值、发布 Bash 字符串判断、缺失 job 权限、Host JAR 路径、checksum 排序、签名失败传播和首次失败重试，再处理异步/并发与结构精简。不要将现有工作区直接打 tag 发布。

本次交接提交使用 `[skip ci]`，仅同步源码与报告，避免同步时启动默认分支的构建/镜像推送。不会创建 tag 或 GitHub Release。后续修复提交须按需正常运行 CI。

构建、编译继续使用已约定的专用远程构建服务器；此目录不保存其地址、SSH 私钥、密码或真实运行环境文件。相关凭据应通过安全渠道在新设备上配置，不从 Git 获取。

报告中的本机绝对路径已替换为仓库相对路径或 `<repository-root>`，私有服务器地址已脱敏。忽略目录中的依赖、构建产物、数据库配置、上传数据和 `.env` 不在本次源码交接范围内。

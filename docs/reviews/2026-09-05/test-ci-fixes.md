# 测试脚本与 CI 检查 3 项修复

基线提交为 `7957b9c9c65df31fa7e717bad23bad3402901ec3`，实际修复基于前端 11 项、后端及导入 14 项、发布部署及回滚 10 项完成后的未提交工作区。此前应用和发布修复保留。

| 项目 | 问题 | 修复 |
| --- | --- | --- |
| OT1 | `install-e2e.sh` 共用 EXIT/INT/TERM handler，中断可能取到上一条命令的成功退出码 | INT/TERM 显式退出 130/143，再进入唯一 EXIT 清理；清理期间忽略重复信号，保留原非零退出码，清理失败不报告成功 |
| OT2 | 任意 Docker inspect 失败都当资源不存在，连接故障时删掉唯一恢复清单 | 只有完整成功的 JSON 名称列表能确认不存在；存在时单次 inspect 绑定名称、label 和 ID，容器/网络按 ID 删除，之后复核资源不存在。查询、解析、归属或删除不确定均保留状态目录 |
| CI 语法门禁 | `bash -n install.sh ops/*.sh ...` 只解析第一个文件 | 上一轮已改为逐文件调用；本轮新增真实工作流命令的故障注入测试，确认后续 ops、公用 helper、Nginx envsh 和含空格文件名的语法错误均被拦截 |

资源预检与手工 `cleanup <run-id>` 使用相同的错误处理规则。未完成资源清理时不删除 `resource-manifest`；目录删除失败也不能显示成功。未创建资源前的失败和中断仍返回原错误码。

JSON 名称输出采用 Docker CLI 官方模板接口；容器列表使用 `--all`，同时包含已停止容器。参考：[container ls](https://docs.docker.com/reference/cli/docker/container/ls/)、[network ls](https://docs.docker.com/reference/cli/docker/network/ls/)、[volume ls](https://docs.docker.com/reference/cli/docker/volume/ls/)。未新增项目依赖或修改 Docker 服务配置。

## 验证

所有脚本执行均在专用打包服务器完成，本地仅编辑、静态审查与摘要比对。

- `bash ops/ci-shell-syntax-test.sh`：8 个场景已通过。使用实际 workflow 中的检查命令，保持首文件合法，逐项破坏后续脚本；同一坏文件可复现旧命令漏检，新命令拒绝，且未执行任何 fixture 脚本。
- `bash ops/publish-workflow-test.sh`：已通过，既有发布门禁保留。
- `actionlint 1.7.7 -shellcheck= -pyflakes= .github/workflows/publish-images.yml`：已通过。外部 ShellCheck/Pyflakes 不包含在这项结果中。

- 真实 Docker 隔离资源检查：12 项通过。创建本次独占的三容器、三卷及网络；对子进程配置不可达 Docker 连接，验证失败时 7 项资源和清单原字节保留，再恢复连接执行真实 `cleanup` 入口。三类同名但归属不符的资源均拒绝删除，INT/TERM 真正异步中断后分别返回 130/143 且只清理一次。独立对照容器、卷、网络保持不变，测试完成后清理所有自建资源，原有资源保留。

- `bash ops/install-e2e-cleanup-test.sh`：以 UID 1001 在无网络、无 Docker socket 的容器中运行，140 个场景通过。覆盖三类资源、普通/条件调用、公共 cleanup 入口、有效 stdout 伴随失败退出、删除后不确定、归属冲突、目录删除失败、原错误码 7、真实 SIGINT/SIGTERM 以及清理期间再次收到信号。首次运行因测试期望底层目录删除码 9 而失败，按脚本明确归一为 1 的约定修正期望后全部通过；生产代码和原错误码/信号码断言未改。
- 33 个 `.sh`/`.envsh` 以普通用户逐文件 `bash -n` 通过；默认 `bash ops/install-e2e.sh plan` 在没有 Docker socket 的环境中正常返回。
- 最终 `git diff --check`、UTF-8 无 BOM/LF 检查及本地/远端源码 SHA-256 比对通过。与上一轮已验证快照相比，本轮只修改 E2E 脚本、CI 接线和说明文档，并新增两个回归脚本及本文。

真实资源验证使用 Docker 29.7.2。测试前后原有容器 ID 集合、网络 ID 集合和命名卷名集合一致，所有自建资源及私有测试目录清理完成。

此次验证针对测试框架的中断、清理与 CI 语法检查，不将其描述为整套 PostgreSQL/Redis 安装业务 E2E 或 GitHub 在线工作流执行验收。

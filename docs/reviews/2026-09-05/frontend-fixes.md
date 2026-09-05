# 前端 11 项修复记录

本次修复基于 `7957b9c9c65df31fa7e717bad23bad3402901ec3`，工作分支为 `codex/fix-frontend-11`。范围是重新核验后确认的 F1–F7、N1–N4 共 11 项前端问题。原审查报告保留为历史基线；本文件记录本轮实际修改和验证结果。

## 修复与验证对应

下表中的文件均位于 `nav-frontend/src/`。

| 编号 | 已修复的行为 | 主要实现 | 对应回归测试 |
| --- | --- | --- | --- |
| F1 | 登录成功返回结果，登录页能够执行默认或指定地址跳转 | `stores/auth.store.ts` | `views/admin/authSession.test.ts`、`stores/auth.store.test.ts` |
| F2 | 旧登录、改密和退出全部设备响应不能清除或接管其他标签页的新会话；调用方忽略过期操作的跳转与成功提示 | `stores/auth.store.ts`、`utils/storage.ts`、`views/admin/AccountManageView.vue` | `views/admin/authSession.test.ts`、`utils/storage.test.ts` |
| F3 | 预检过期不能把确认中的导入改成可操作状态；运行中的旧预检不能再次确认 | `components/admin/DataImportPanel.vue`、`components/admin/ImportPreviewDialog.vue` | `components/admin/DataImportPanel.test.ts` |
| F4 | 卸载后的预检、确认、轮询结果不再写入组件状态或破坏新任务恢复记录；清理记录必须匹配 jobId | `components/admin/DataImportPanel.vue`、`utils/dataTransfer.ts` | `components/admin/DataImportPanel.test.ts`、`utils/dataTransfer.test.ts` |
| F5 | 搜索引擎显隐按行保持请求互斥，读回默认引擎连带变化后再释放操作 | `views/admin/SearchEngineManageView.vue` | `views/admin/SearchEngineManageView.test.ts` |
| F6 | 普通 CRUD 刷新与排序保存均保留未提交的新输入；保存成功但读回失败时仍保持正确排序基线 | `views/admin/SearchEngineManageView.vue` | `views/admin/SearchEngineManageView.test.ts` |
| F7 | 旧首页实例和旧搜索引擎请求不能覆盖新列表、加载状态或用户搜索偏好 | `views/portal/PortalHome.vue` | `views/portal/PortalHome.lifecycle.test.ts` |
| N1 | 背景上传组件卸载前释放父组件上传计数；迟到结果不能覆盖用户随后清空或填写的地址 | `components/admin/BackgroundImageField.vue` | `components/admin/BackgroundImageField.test.ts` |
| N2 | 分类、书签和搜索引擎的旧保存或旧表单校验不能关闭、提交后来打开的草稿；继续保持页级保存互斥 | 三个管理页及 `CategoryFormDialog.vue`、`BookmarkFormDialog.vue`、`SearchEngineDialog.vue` | `views/admin/manageDialogLifecycle.test.ts` |
| N3 | 管理页允许空站点简介，名称和简介长度限制与现有安装/API 契约对齐为 50/255 | `views/admin/SiteConfigView.vue` | `views/admin/SiteConfigView.test.ts` |
| N4 | 分类和书签图标复用后台的 Unicode 码点判断，正确显示 `🇨🇳` 等合法图标 | `components/portal/CategoryCard.vue`、`components/portal/BookmarkItem.vue` | `components/portal/NavigationIcons.test.ts` |

F2 使用 `setSnapshot()` 返回本次写入生成的冻结会话身份，避免写入刚完成便出现同 token 新 generation 时误认归属。原 `set(): true` 接口及两键存储格式保持兼容。此修改没有把浏览器 localStorage 改造成原子事务。

## 实际验证

所有依赖安装、测试和构建都在约定的专用打包服务器执行，使用 Node.js 22 容器。本地仅进行源码修改、静态审查、差异和哈希检查，没有安装依赖或生成构建产物。

- 准备依赖：`npm ci --no-audit --no-fund`，通过；未改依赖清单或锁文件。
- 完整测试：`npm test -- --reporter=default --reporter=json --outputFile=/repo/validation/final-tests.json`，**45 个文件、379 个用例通过，0 失败**。
- 生产构建：`npm run build`，其中 `vue-tsc -b` 和 `vite build` 均通过。
- 静态检查：`git diff --check` 通过；修改文件为 UTF-8 无 BOM、LF。
- 版本核对：29 个本轮前端变更文件的本地 SHA-256 与最终服务器测试快照一致。
- 旧代码对照：在独立基线副本运行本轮回归用例，登录跳转、过期会话、卸载回调、排序草稿、空简介和 Unicode 图标等行为断言按预期失败。该对照用于确认回归价值，不用于重新计算问题数量。

新增测试运行真实 Pinia、Vue setup、生命周期和异步调度，仅替换外部 API、Storage 或宿主节点；站点表单使用已有 async-validator 校验规则，图标使用 Vue 服务端渲染验证真实模板输出。未运行浏览器端 E2E 或连接真实后端 API 的交互验收。

构建仍提示第三方 PURE 注释及较大分包警告，未阻断构建。本次没有扩展到后端/部署问题、导入 W-004 的完整恢复方案或分包优化，也未提交、推送或部署。

服务器验证任务名为 `stage3`，日志、JSON 结果与文件哈希清单保存在本机独立的 `frontend-fixes-7957b9c` 验证目录。该目录和服务器凭据不进入 Git。

# yunlume 前后端分离导航站开发计划

> 本文档根据用户提供的导航首页截图和后台导航配置截图整理，目标是规划一个可维护、可扩展、前后端分离的导航站系统。  
> 重点要求：**每个功能独立成文件，组件清晰拆分，接口分层明确，便于后续维护和二次开发。**

> 当前主题约束：公开首页不展示推荐快捷区、自定义头尾链接、背景音乐或背景特效；内置后台只展示当前主题真正生效的设置。相关数据库字段与后端 API 保留兼容，但不作为当前前端已实现功能。

> 当前可靠性基线：站点配置采用版本号乐观并发控制；后台配置读取失败时关闭编辑和保存并完整追踪未保存修改；临时网络/服务端故障不主动清除管理会话；总览资源独立加载；公开首页在首次降级时明确提示并支持重试。生产环境默认不补写演示业务数据、默认关闭 OpenAPI，并由 Nginx 对登录 POST 限速；受管背景图具备总量/数量配额和带宽限期的孤儿回收。

> 当前数据与运维基线：生产仅连接部署者预先提供的外部 PostgreSQL 14+，仓库不再编排数据库容器，也不保留其他数据库的旧结构或转换脚本。后台提供不含管理员和密钥的版本化 ZIP 导出、零写入预检与事务性导入；数据库灾难恢复由服务商快照和恢复演练负责，`database_config`、上传卷、加密环境配置与镜像版本必须另行保护。

> 当前首次部署基线：新部署通过 `/install` 响应式安装向导依次连接预先创建的外部 PostgreSQL 与 Redis。两个服务都以短期单次 ticket 完成测试、后端专用配置卷落盘和重启接管；PostgreSQL 只初始化用户明确确认的空白专用库，Redis 支持系统信任、自定义 CA 或受信私网明文模式。安装完成状态、数据库实例 UUID 与 Redis 配置标记交叉绑定；依赖断线、清空用户或保留任一配置文件时都不会重开换库入口。JWT 由权限为 `0600` 的 `.env` 提供，PostgreSQL/Redis 密码只通过安装向导进入后端配置卷；Compose 不启动 PostgreSQL 或 Redis 容器。

> 当前入口基线：`frontend` 镜像内的 Nginx 默认监听宿主机 `0.0.0.0`，首次部署直接通过公网 HTTP 访问 `/install`，不要求安装口令、SSH 通道、IP 白名单或 HTTPS。安装完成标记写入后，所有修改型安装接口永久拒绝再次初始化。

---

## 1. 项目目标

本项目用于开发一个类似图片中效果的个人/团队导航站系统，包含前台导航展示页和后台管理配置页。

### 1.1 前台导航页目标

前台导航页主要面向普通访问用户，展示站点信息、搜索框、分类导航和书签链接。

核心能力包括：

- 展示站点名称
- 展示站点简介
- 支持搜索框
- 支持多个搜索引擎
- 展示导航分类
- 展示每个分类下的书签链接
- 支持背景颜色配置
- 支持背景图片配置
- 支持字体颜色配置
- 服务端数据首次不可用时明确显示降级状态并提供重试，已有真实数据不被短暂故障覆盖
- 根据站点名称、简介和背景色动态同步页面标题、描述与 `theme-color` 元信息
- 右上工具栏保留分类定位与后台配置入口
- 后端保留自定义头部/底部链接兼容接口（当前主题不展示）
- 后端保留背景音乐配置字段（当前主题不播放）
- 保留站点订阅配置字段（当前主题不展示订阅快捷入口）
- 支持响应式布局

### 1.2 后台管理页目标

后台管理页主要面向管理员，用于维护导航站内容和页面配置。

核心能力包括：

- 管理站点名称
- 管理站点简介
- 管理背景颜色
- 管理字体颜色
- 管理背景图片
- 保留发布地址、背景特效和背景音乐字段兼容能力（当前后台不展示）
- 保留站点订阅配置兼容能力
- 管理顶部内容开关
- 管理导航分类
- 管理书签链接
- 管理文件夹
- 保留自定义头部/底部链接后端能力（当前后台不展示）
- 管理管理员账户
- 管理系统配置
- 站点配置读取失败时锁定表单，防止默认值覆盖线上数据
- 站点配置使用版本冲突保护，并在刷新、重载或离开前提示未保存修改
- 总览分类、书签和站点状态独立加载，可只重试失败项

---

## 2. 整体架构

项目采用前后端分离架构。

```text
用户浏览器
   |
   | 访问前台导航页 / 后台管理页
   v
Vue 3 前端项目
   |
   | Axios 请求 REST API
   v
Spring Boot 后端项目
   |
   | 读写数据
   v
外部 PostgreSQL / Redis / 本地文件存储
```

推荐最终项目结构：

```text
yunlume/
├── nav-frontend/             # 前端项目及 frontend 运行镜像
│   └── nginx/                # SPA、API、上传和可信代理入口配置
│       ├── nginx.conf.template
│       └── 05-validate-proxy-env.envsh
├── nav-backend/              # 后端项目
├── database/                 # PostgreSQL 权威结构与迁移历史
├── deploy/host/              # 宿主机安装模板
├── ops/                      # 发行打包、卷迁移、E2E 与应用回滚脚本
├── install.sh                # Docker/宿主机统一安装入口
├── docker-compose.yml        # frontend/backend 编排，PostgreSQL 与 Redis 均为外部服务
└── README.md                 # 项目说明文档
```

---

## 3. 技术栈规划

### 3.1 前端技术栈

```text
Vue 3
Vite
TypeScript
Vue Router
Pinia
Element Plus
Axios
SCSS
```

选择理由：

| 技术 | 用途 | 原因 |
|---|---|---|
| Vue 3 | 前端框架 | 适合中小型管理系统和导航类页面 |
| Vite | 构建工具 | 启动快，开发体验好 |
| TypeScript | 类型约束 | 方便后期维护，减少字段错误 |
| Vue Router | 路由管理 | 区分前台和后台页面 |
| Pinia | 状态管理 | 管理登录信息、站点配置、导航数据 |
| Element Plus | 后台 UI 组件 | 表单、表格、弹窗、开关、上传组件成熟 |
| Axios | 请求后端接口 | 统一封装请求和响应 |
| SCSS | 样式管理 | 方便拆分变量、主题、布局样式 |

### 3.2 后端技术栈

```text
Spring Boot 3
Java 17
MyBatis-Plus
PostgreSQL 17
Redis
JWT
Knife4j / Swagger
Maven
```

选择理由：

| 技术 | 用途 | 原因 |
|---|---|---|
| Spring Boot 3 | 后端主框架 | 稳定、生态成熟 |
| Java 17 | 后端语言版本 | 长期支持，适合生产项目 |
| MyBatis-Plus | ORM / 数据访问 | 快速完成增删改查 |
| PostgreSQL 17 | 主数据库 | 存储用户、配置、分类、书签等数据，并提供事务、部分唯一索引和行锁 |
| Redis | 外部运行依赖 | 首次安装与运行健康探测；为后续业务缓存扩展预留 |
| JWT | 登录认证 | 适合前后端分离认证 |
| Knife4j / Swagger | 接口文档 | 方便前后端联调 |
| Maven | 依赖管理 | Java 项目标准工具 |

### 3.3 部署技术栈

```text
1Panel OpenResty（可选外层 HTTPS 入口）
Docker
Docker Compose
Java 17 + systemd + Nginx（宿主机模式）
```

部署结构：

```text
1Panel OpenResty（可选）
└── 域名、HTTPS 与最外层反向代理

Docker Compose
├── frontend（前端静态文件 + 内层 Nginx/API 网关，统一应用入口）
└── backend

或宿主机模式
├── systemd 运行 yunlume-backend.jar
└── 系统 Nginx 提供 frontend 静态文件与 API 代理

外部服务
├── PostgreSQL 14+
└── Redis（生产默认 TLS）
```

---

## 4. 页面规划

## 4.1 前台页面

前台页面路径：

```text
/
```

前台页面对应第一张截图，主要由以下区域组成：

```text
站点标题区域
站点简介区域
搜索区域
分类卡片与书签链接区域
手机端分类卡片纵向排列，卡片内书签固定按左、中、右三列展示
右上工具栏区域
公共数据加载/降级提示区域
动态页面元信息
```

### 前台页面组件拆分

```text
src/views/portal/
└── PortalHome.vue                         # 前台首页主页面，只负责组合组件

src/components/portal/
├── SiteHeader.vue                         # 站点名称、站点简介
├── SearchBar.vue                          # 搜索框及弹出式搜索引擎网格选择器
├── CategoryGrid.vue                       # 分类网格容器
├── CategoryCard.vue                       # 单个分类卡片
├── BookmarkItem.vue                       # 单个书签项
├── TopActionBar.vue                       # 分类定位与后台配置入口
├── SiteSubscribe.vue                      # 可选订阅扩展（当前主题未挂载）
└── EmptyState.vue                         # 空数据展示
```

### 前台页面维护原则

`PortalHome.vue` 不直接写大量业务逻辑，只做页面结构组织。

推荐写法：

```text
PortalHome.vue
├── 调用 useSiteConfig 获取站点配置
├── 调用 useBookmarks 获取导航分类和书签
├── 组合 SiteHeader
├── 组合 SearchBar
├── 组合 CategoryGrid
└── 处理独立公共请求、fallback 状态与页面元信息
```

不要把搜索逻辑、分类循环、书签点击、音乐播放、主题样式全部写进 `PortalHome.vue`。

---

## 4.2 后台页面

后台页面路径：

```text
/admin
```

首次部署页面路径为 `/install`。六步流程为“PostgreSQL → Redis → 环境 → 站点 → 账号 → 确认”，发行编排与安装页只接受部署者提供的外部 PostgreSQL 和外部 Redis。只有对应来源明确为未配置、没有运行配置/完成锁、整个用户表为空、持久化安装标记未完成且网页安装开关启用时才允许提交连接；已有站点访问该地址会进入登录页。安装状态请求异常时不能把现有站点误判为未安装，安装页本身应显示失败状态与重试入口。

后台页面对应第二张截图，包含左侧菜单和右侧配置内容。

后台左侧菜单包括：

```text
总览
站点配置
搜索引擎
分类管理
书签管理
数据管理
账号安全
```

后台布局响应式约束：

```text
901px 及以上：固定侧栏，可在完整与折叠状态间切换
900px 及以下：侧栏改为带遮罩的侧滑菜单，打开时锁定页面滚动
移动菜单：支持 Esc、遮罩、菜单导航关闭，并完成焦点进入、圈闭和回交
720px 及以下：数据表格与移动卡片互斥，主要触控区域不小于 44px
弹窗与消息框：限制在 100dvh 内，内容区独立滚动，底部操作持续可达
最小视口：按 320px 宽度适配，不产生整页横向滚动
```

### 后台页面文件拆分

```text
src/views/admin/
├── AdminLayout.vue                        # 后台整体布局，包含侧边栏和顶部栏
├── DashboardView.vue                      # 总览页
├── SiteConfigView.vue                     # 站点与主题配置页
├── SearchEngineManageView.vue             # 搜索引擎管理页
├── CategoryManageView.vue                 # 分类管理页
├── BookmarkManageView.vue                 # 书签管理页
├── DataManageView.vue                     # 可移植数据导出、预检与导入页
├── AccountManageView.vue                  # 账号安全页
└── LoginView.vue                          # 管理员登录页

src/views/install/
└── InstallView.vue                        # 首次部署环境检查与管理员初始化向导
```

### 后台组件拆分

```text
src/components/admin/
├── AdminSidebar.vue                       # 后台左侧菜单
├── AdminHeader.vue                        # 后台顶部栏
├── PageHeading.vue                        # 管理页面统一标题区
├── BackgroundImageField.vue               # PC/移动端背景上传与预览
├── CategoryFormDialog.vue                 # 分类新增/编辑弹窗
├── BookmarkFormDialog.vue                 # 书签新增/编辑弹窗
├── SearchEngineDialog.vue                 # 搜索引擎新增/编辑弹窗
├── SortOrderDialog.vue                    # 分类/书签通用排序弹窗
├── DataExportPanel.vue                    # ZIP 导出范围与下载
├── DataImportPanel.vue                    # 文件选择、预检与任务恢复
├── ImportPreviewDialog.vue                # 差异、错误和最终确认
└── ImportProgressDialog.vue               # 导入任务阶段与结果
```

---

## 5. 前端目录结构规划

```text
nav-frontend/
├── public/
│   └── favicon.ico
│
├── src/
│   ├── api/
│   │   ├── request.ts                     # Axios 实例和拦截器
│   │   ├── auth.api.ts                    # 登录、退出、用户信息接口
│   │   ├── site.api.ts                    # 站点配置接口
│   │   ├── category.api.ts                # 分类接口
│   │   ├── bookmark.api.ts                # 书签接口
│   │   ├── searchEngine.api.ts            # 搜索引擎接口
│   │   └── upload.api.ts                  # 文件上传接口
│   │
│   ├── assets/
│   │   ├── images/                        # 图片资源
│   │   ├── icons/                         # 图标资源
│   │   └── audio/                         # 音频资源
│   │
│   ├── components/
│   │   ├── portal/                        # 前台组件
│   │   ├── admin/                         # 后台组件
│   │   └── common/                        # 公共组件
│   │
│   ├── composables/
│   │   ├── useSiteConfig.ts               # 站点配置逻辑
│   │   ├── useBookmarks.ts                # 导航数据逻辑
│   │   ├── useSearch.ts                   # 搜索逻辑
│   │   ├── useTheme.ts                    # 主题样式逻辑
│   │   ├── useAuth.ts                     # 登录认证逻辑
│   │   └── usePermission.ts               # 权限判断逻辑
│   │
│   ├── router/
│   │   ├── index.ts                       # 路由入口
│   │   ├── portal.routes.ts               # 前台路由
│   │   └── admin.routes.ts                # 后台路由
│   │
│   ├── stores/
│   │   ├── auth.store.ts                  # 登录状态
│   │   ├── site.store.ts                  # 站点配置状态
│   │   └── navigation.store.ts            # 分类和书签状态
│   │
│   ├── styles/
│   │   ├── reset.scss                     # 样式重置
│   │   ├── variables.scss                 # 全局样式变量
│   │   ├── common.scss                    # 公共样式
│   │   ├── portal/
│   │   │   ├── index.scss                 # 前台样式入口
│   │   │   ├── layout.scss                # 前台布局样式
│   │   │   ├── card.scss                  # 分类卡片样式
│   │   │   └── theme.scss                 # 前台主题样式
│   │   └── admin/
│   │       ├── index.scss                 # 后台样式入口
│   │       ├── layout.scss                # 后台布局样式
│   │       └── form.scss                  # 后台表单样式
│   │
│   ├── types/
│   │   ├── auth.ts                        # 用户类型
│   │   ├── site.ts                        # 站点配置类型
│   │   ├── category.ts                    # 分类类型
│   │   ├── bookmark.ts                    # 书签类型
│   │   ├── searchEngine.ts                # 搜索引擎类型
│   │   └── common.ts                      # 公共类型
│   │
│   ├── utils/
│   │   ├── storage.ts                     # localStorage/sessionStorage 封装
│   │   ├── validate.ts                    # 表单校验方法
│   │   ├── format.ts                      # 格式化方法
│   │   ├── constants.ts                   # 常量
│   │   └── url.ts                         # URL 处理工具
│   │
│   ├── views/
│   │   ├── portal/
│   │   └── admin/
│   │
│   ├── App.vue
│   └── main.ts
│
├── .env.development
├── .env.production
├── package.json
├── vite.config.ts
└── tsconfig.json
```

---

## 6. 后端目录结构规划

```text
nav-backend/
├── src/main/java/com/example/nav/
│   ├── NavApplication.java
│   │
│   ├── common/
│   │   ├── result/
│   │   │   ├── Result.java                # 统一返回结果
│   │   │   └── ResultCode.java            # 返回状态码
│   │   ├── exception/
│   │   │   ├── GlobalExceptionHandler.java # 全局异常处理
│   │   │   └── BusinessException.java      # 业务异常
│   │   ├── config/
│   │   │   ├── WebConfig.java             # Web 配置
│   │   │   ├── CorsConfig.java            # 跨域配置
│   │   │   ├── JwtConfig.java             # JWT 配置
│   │   │   ├── MyBatisPlusConfig.java     # MyBatis-Plus 配置
│   │   │   └── SwaggerConfig.java         # 接口文档配置
│   │   └── utils/
│   │       ├── JwtUtil.java               # JWT 工具
│   │       ├── PasswordUtil.java          # 密码加密工具
│   │       ├── FileUploadUtil.java        # 文件上传工具
│   │       └── BeanCopyUtil.java          # 对象复制工具
│   │
│   ├── module/
│   │   ├── auth/
│   │   │   ├── controller/
│   │   │   │   └── AuthController.java
│   │   │   ├── service/
│   │   │   │   ├── AuthService.java
│   │   │   │   └── impl/AuthServiceImpl.java
│   │   │   ├── dto/
│   │   │   │   ├── LoginDTO.java
│   │   │   │   └── RegisterDTO.java
│   │   │   └── vo/
│   │   │       └── LoginVO.java
│   │   │
│   │   ├── site/
│   │   │   ├── controller/
│   │   │   │   └── SiteConfigController.java
│   │   │   ├── service/
│   │   │   │   ├── SiteConfigService.java
│   │   │   │   └── impl/SiteConfigServiceImpl.java
│   │   │   ├── entity/
│   │   │   │   └── SiteConfig.java
│   │   │   ├── mapper/
│   │   │   │   └── SiteConfigMapper.java
│   │   │   ├── dto/
│   │   │   │   └── SiteConfigUpdateDTO.java
│   │   │   └── vo/
│   │   │       └── SiteConfigVO.java
│   │   │
│   │   ├── category/
│   │   │   ├── controller/
│   │   │   │   └── CategoryController.java
│   │   │   ├── service/
│   │   │   │   ├── CategoryService.java
│   │   │   │   └── impl/CategoryServiceImpl.java
│   │   │   ├── entity/
│   │   │   │   └── Category.java
│   │   │   ├── mapper/
│   │   │   │   └── CategoryMapper.java
│   │   │   ├── dto/
│   │   │   │   ├── CategoryCreateDTO.java
│   │   │   │   └── CategoryUpdateDTO.java
│   │   │   └── vo/
│   │   │       └── CategoryVO.java
│   │   │
│   │   ├── bookmark/
│   │   │   ├── controller/
│   │   │   │   └── BookmarkController.java
│   │   │   ├── service/
│   │   │   │   ├── BookmarkService.java
│   │   │   │   └── impl/BookmarkServiceImpl.java
│   │   │   ├── entity/
│   │   │   │   └── Bookmark.java
│   │   │   ├── mapper/
│   │   │   │   └── BookmarkMapper.java
│   │   │   ├── dto/
│   │   │   │   ├── BookmarkCreateDTO.java
│   │   │   │   └── BookmarkUpdateDTO.java
│   │   │   └── vo/
│   │   │       └── BookmarkVO.java
│   │   │
│   │   ├── search/
│   │   │   ├── controller/
│   │   │   │   └── SearchEngineController.java
│   │   │   ├── service/
│   │   │   │   ├── SearchEngineService.java
│   │   │   │   └── impl/SearchEngineServiceImpl.java
│   │   │   ├── entity/
│   │   │   │   └── SearchEngine.java
│   │   │   ├── mapper/
│   │   │   │   └── SearchEngineMapper.java
│   │   │   ├── dto/
│   │   │   │   └── SearchEngineDTO.java
│   │   │   └── vo/
│   │   │       └── SearchEngineVO.java
│   │   │
│   │   ├── customlink/
│   │   │   ├── controller/
│   │   │   │   └── CustomLinkController.java
│   │   │   ├── service/
│   │   │   │   ├── CustomLinkService.java
│   │   │   │   └── impl/CustomLinkServiceImpl.java
│   │   │   ├── entity/
│   │   │   │   └── CustomLink.java
│   │   │   ├── mapper/
│   │   │   │   └── CustomLinkMapper.java
│   │   │   ├── dto/
│   │   │   │   └── CustomLinkDTO.java
│   │   │   └── vo/
│   │   │       └── CustomLinkVO.java
│   │   │
│   │   ├── upload/
│   │   │   ├── controller/
│   │   │   │   └── ImageUploadController.java
│   │   │   ├── config/
│   │   │   │   ├── UploadStorageProperties.java
│   │   │   │   └── UploadSchedulingConfig.java
│   │   │   ├── service/
│   │   │   │   ├── ImageUploadService.java
│   │   │   │   ├── BackgroundImageStorageService.java
│   │   │   │   └── BackgroundImageCleanupScheduler.java
│   │   │   └── vo/
│   │   │       └── ImageUploadVO.java
│   │   │
│   │   ├── datapackage/
│   │   │   ├── controller/
│   │   │   │   └── PortableDataController.java
│   │   │   ├── model/
│   │   │   │   └── PortablePackageModels.java
│   │   │   └── service/                   # 快照、ZIP读写、预检、导入事务与任务
│   │   │
│   │   └── user/
│   │       ├── controller/
│   │       │   └── UserController.java
│   │       ├── service/
│   │       │   ├── UserService.java
│   │       │   └── impl/UserServiceImpl.java
│   │       ├── entity/
│   │       │   └── User.java
│   │       ├── mapper/
│   │       │   └── UserMapper.java
│   │       ├── dto/
│   │       │   └── UserUpdateDTO.java
│   │       └── vo/
│   │           └── UserVO.java
│   │
│   └── security/
│       ├── JwtAuthenticationFilter.java
│       └── SecurityConfig.java
│
├── src/main/resources/
│   ├── mapper/
│   │   ├── SiteConfigMapper.xml
│   │   ├── CategoryMapper.xml
│   │   ├── BookmarkMapper.xml
│   │   ├── SearchEngineMapper.xml
│   │   ├── CustomLinkMapper.xml
│   │   └── UserMapper.xml
│   ├── application.yml
│   └── application-prod.yml
│
├── pom.xml
└── Dockerfile
```

---

## 7. 功能模块规划

## 7.1 站点配置模块

### 功能内容

站点配置模块用于控制前台导航页的基础展示效果。

当前内置后台展示：

- 站点名称
- 站点简介
- 背景类型：纯色 / 图片
- 背景颜色
- 背景图片
- 字体颜色
- 留言板文字
- 顶部内容开关

发布地址、背景特效、背景音乐及订阅字段继续由后端保存，但当前主题不消费，内置后台不提供无效编辑控件。

当前保存契约：

- GET 返回完整配置和非负整数 `version`。
- PUT 必须携带本次读取到的 `expectedVersion`；条件更新成功后 `version + 1`。
- 版本过期或并发条件更新失败返回 `409`，旧页面不得覆盖新配置。
- 后台只有完整读取服务端配置后才允许编辑、上传和保存；加载失败或发生 `409` 时表单保持锁定，直到重新加载成功。
- 未保存判断覆盖整份当前主题表单，而不只背景字段；浏览器刷新、路由离开和手动重载前均需确认。
- 图片模式必须提供 PC 背景图；移动端图片允许留空并沿用 PC 图。

### 前端文件

```text
src/api/site.api.ts
src/types/site.ts
src/stores/site.store.ts
src/composables/useSiteConfig.ts
src/components/admin/BackgroundImageField.vue
src/utils/backgroundConfig.ts
src/utils/siteConfigState.ts
src/views/admin/SiteConfigView.vue
```

### 后端文件

```text
module/site/controller/SiteConfigController.java
module/site/service/SiteConfigService.java
module/site/service/impl/SiteConfigServiceImpl.java
module/site/entity/SiteConfig.java
module/site/mapper/SiteConfigMapper.java
module/site/dto/SiteConfigUpdateDTO.java
module/site/vo/SiteConfigVO.java
```

---

## 7.2 分类/文件夹管理模块

### 功能内容

分类用于组织书签链接，对应前台截图中的多个导航分组。

例如：

```text
Office
云资源
网盘
影视
工具
软件官网
购物
图片
阅读
装机
开发
论坛
Github
邮箱安全
```

功能包括：

- 新增分类
- 编辑分类
- 删除分类
- 分类图标
- 分类排序
- 分类显示/隐藏
- 分类下书签数量统计
- 删除前显示实际书签数量；仍有关联书签时阻止删除并引导先移动
- 桌面表格与移动端卡片互斥展示
- 排序弹窗支持上/下按钮和 `Alt + ↑/↓` 键盘操作

### 前端文件

```text
src/api/category.api.ts
src/types/category.ts
src/views/admin/CategoryManageView.vue
src/components/admin/CategoryFormDialog.vue
src/components/admin/SortOrderDialog.vue
src/utils/adminNavigationManage.ts
src/components/portal/CategoryGrid.vue
src/components/portal/CategoryCard.vue
```

### 后端文件

```text
module/category/controller/CategoryController.java
module/category/service/CategoryService.java
module/category/service/impl/CategoryServiceImpl.java
module/category/entity/Category.java
module/category/mapper/CategoryMapper.java
module/category/dto/CategoryCreateDTO.java
module/category/dto/CategoryUpdateDTO.java
module/category/vo/CategoryVO.java
```

---

## 7.3 书签管理模块

### 功能内容

书签是导航站的核心内容。

每个书签包含：

- 所属分类
- 书签名称
- 书签地址
- 图标地址
- 简介描述
- 排序值
- 是否推荐（兼容字段，当前主题和后台不展示）
- 是否新窗口打开
- 是否显示

功能包括：

- 新增书签
- 编辑书签
- 删除书签
- 根据分类筛选书签
- 跨筛选保留多选状态并可清空全部
- 批量移动分类；同目标项保持原位，失败保留选择，成功刷新完整列表与分类计数
- 仅在明确分类内对全部书签排序（含隐藏项，不受关键词筛选影响）
- 使用上/下按钮或 `Alt + ↑/↓` 排序
- 显示/隐藏书签
- 桌面表格与移动端卡片使用同一选择和管理能力
- 图标统一接受空值、1–3 字短标记/Emoji 或显式完整 HTTP(S) 图片 URL

### 前端文件

```text
src/api/bookmark.api.ts
src/types/bookmark.ts
src/stores/navigation.store.ts
src/composables/useBookmarks.ts
src/views/admin/BookmarkManageView.vue
src/components/admin/BookmarkFormDialog.vue
src/components/admin/SortOrderDialog.vue
src/utils/adminNavigationManage.ts
src/components/portal/BookmarkItem.vue
```

### 后端文件

```text
module/bookmark/controller/BookmarkController.java
module/bookmark/service/BookmarkService.java
module/bookmark/service/impl/BookmarkServiceImpl.java
module/bookmark/entity/Bookmark.java
module/bookmark/mapper/BookmarkMapper.java
module/bookmark/dto/BookmarkCreateDTO.java
module/bookmark/dto/BookmarkUpdateDTO.java
module/bookmark/dto/BookmarkBatchMoveDTO.java
module/bookmark/vo/BookmarkVO.java
```

---

## 7.4 搜索引擎模块

### 功能内容

搜索引擎模块用于控制前台搜索框。

支持：

- 百度
- Google
- Bing
- 站内搜索
- 自定义搜索引擎
- 点击搜索框左侧当前引擎打开毛玻璃网格选择面板
- 显示可用数量和当前选中项，选择后立即更新搜索引擎并聚焦输入框
- 点击外部或按 `Esc` 收起，桌面四列、移动两列，超高内容内部滚动

每个搜索引擎配置包含：

- 搜索引擎名称
- 图标
- 搜索地址模板
- 占位文字
- 是否默认
- 排序值

### 前端文件

```text
src/api/searchEngine.api.ts
src/types/searchEngine.ts
src/composables/useSearch.ts
src/components/portal/SearchBar.vue
src/components/admin/SearchEngineTable.vue
src/components/admin/SearchEngineDialog.vue
```

### 后端文件

```text
module/search/controller/SearchEngineController.java
module/search/service/SearchEngineService.java
module/search/service/impl/SearchEngineServiceImpl.java
module/search/entity/SearchEngine.java
module/search/mapper/SearchEngineMapper.java
module/search/dto/SearchEngineDTO.java
module/search/vo/SearchEngineVO.java
```

---

## 7.5 自定义链接模块

### 功能内容

自定义链接用于兼容其他主题或外部集成中的：

```text
添加至表头
添加至表尾
```

当前内置前台和后台均不挂载该模块；数据库、后端管理 API 与公开 API 继续保留，已有记录不会删除。

功能包括：

- 新增链接
- 编辑链接
- 删除链接
- 设置显示位置：头部 / 尾部
- 设置排序
- 设置是否启用

### 前端文件

```text
当前内置前端无自定义链接页面或组件
```

### 后端文件

```text
module/customlink/controller/CustomLinkController.java
module/customlink/service/CustomLinkService.java
module/customlink/service/impl/CustomLinkServiceImpl.java
module/customlink/entity/CustomLink.java
module/customlink/mapper/CustomLinkMapper.java
module/customlink/dto/CustomLinkDTO.java
module/customlink/vo/CustomLinkVO.java
```

---

## 7.6 用户与登录模块

### 功能内容

后台需要管理员登录后才能访问。

功能包括：

- 管理员登录
- 管理员退出
- 退出当前管理员的全部登录会话
- 获取当前登录用户信息
- 修改密码
- 查看当前账号资料
- JWT 鉴权
- 使用持久化令牌版本撤销旧 JWT
- 路由权限保护
- 资料接口的网络错误或 `5xx` 不清除令牌与最近一次用户资料，只有受保护接口明确返回 `401/403` 才注销
- 合并并发资料请求；已有缓存资料时以 30 秒新鲜度窗口避免故障期间重复请求

### 前端文件

```text
src/api/auth.api.ts
src/types/auth.ts
src/stores/auth.store.ts
src/composables/useAuth.ts
src/views/admin/LoginView.vue
src/views/admin/AccountManageView.vue
src/router/admin.routes.ts
```

### 后端文件

```text
module/auth/controller/AuthController.java
module/auth/service/AuthService.java
module/auth/service/impl/AuthServiceImpl.java
module/auth/dto/LoginDTO.java
module/auth/dto/ChangePasswordDTO.java
module/user/mapper/UserMapper.java
security/JwtAuthenticationFilter.java
security/JwtTokenService.java
module/auth/vo/LoginVO.java
module/user/controller/UserController.java
module/user/service/UserService.java
module/user/entity/User.java
module/user/mapper/UserMapper.java
security/JwtAuthenticationFilter.java
security/SecurityConfig.java
```

---

## 7.7 上传模块

### 功能内容

当前内置前端只用于上传 PC/移动端背景图片。站点图标、书签图标和背景音乐上传仍是后续扩展，不属于当前已实现契约。

支持：

- JPG/PNG 图片上传及多重内容校验
- 单文件大小、受管目录总容量与文件数量限制
- 临时文件写入、公开只读权限和原子移动
- 只为服务端生成的受管文件返回可访问 URL
- 精确保护 `site_config` 当前 PC/移动端引用
- 未引用文件经过宽限期后自动清理；配置引用读取失败时整次清理跳过
- 默认策略：单文件 10MiB、总量 1GB、最多 500 个文件、孤儿宽限 24 小时；启动 1 分钟后首次清理，之后每 6 小时清理，上传前也会清理
- `APP_UPLOAD_MAX_BYTES` 只允许 1–10485760 字节；即使可移植数据包入口拥有更大的 multipart 额度，背景图片业务层仍独立执行此上限。同一值以 `VITE_UPLOAD_MAX_BYTES` 编译进 frontend 镜像，修改后必须同时重建 frontend 与 backend

### 前端文件

```text
src/api/upload.api.ts
src/components/admin/BackgroundImageField.vue
```

### 后端文件

```text
module/upload/controller/ImageUploadController.java
module/upload/config/UploadSchedulingConfig.java
module/upload/config/UploadStorageProperties.java
module/upload/service/ImageUploadService.java
module/upload/service/BackgroundImageStorageService.java
module/upload/service/BackgroundImageCleanupScheduler.java
module/upload/vo/ImageUploadVO.java
```

---

## 7.8 数据管理与灾难恢复模块

### 功能内容

- 管理员导出版本 1 ZIP：站点配置、分类、书签、搜索引擎、兼容链接及当前引用背景图
- 独立导出人类可读的 Markdown 书签副本：包含隐藏项和空分类，按后台排序稳定输出，支持预览、复制与下载，但不承担系统恢复
- 明确排除管理员、密码哈希、会话版本、环境变量和运行密钥
- 导入前零写入预检：ZIP 路径/大小、严格 JSON、SHA-256、图片签名、业务约束、引用与差异统计
- 15 分钟预检令牌绑定管理员、归档摘要和业务 revision；任何并发变更都要求重新预检
- 二次确认后异步执行，业务数据与提交前完整验证位于同一 `SERIALIZABLE` 事务；失败回滚数据库并清理新资产
- v1 列表 stable key 来自导出时数据库 ID；全量导入生成新 identity 后，旧包再次预检可能显示新增/删除。内容恢复不受影响，后续格式可用持久 UUID 改善跨导入语义 diff
- 外部 PostgreSQL 使用服务商的加密备份、时间点恢复与定期恢复演练；外部 Redis 同样需要服务端持久化、备份与恢复演练；后台 ZIP 只承担业务内容迁移，不替代外部服务备份
- `database_config` 需加密异机保存明文连接凭据、可选 CA 与实例身份，卷丢失后没有受支持的既有外部库重新关联流程
- 上传卷、加密后的 `.env`、镜像版本、PostgreSQL 服务商备份和 Redis 服务端备份共同构成整站恢复材料

### 前端文件

```text
src/views/admin/DataManageView.vue
src/components/admin/DataExportPanel.vue
src/components/admin/DataImportPanel.vue
src/components/admin/MarkdownBookmarkBackupPanel.vue
src/components/admin/ImportPreviewDialog.vue
src/components/admin/ImportProgressDialog.vue
src/api/data.api.ts
src/types/dataTransfer.ts
src/utils/dataTransfer.ts
src/utils/clipboard.ts
```

### 后端与运维文件

```text
module/datapackage/controller/PortableDataController.java
module/datapackage/model/PortablePackageModels.java
module/datapackage/service/Portable*.java
ops/rollback-release.sh
```

---

## 8. 数据库表规划

`nav-backend/src/main/resources/schema-postgresql.sql` 是安装向导初始化已确认空白外部库的唯一权威资源，包含完整结构、约束、种子数据及迁移文件名/SHA-256 登记。所有主键使用 `BIGINT GENERATED BY DEFAULT AS IDENTITY`，便于正常自增和经过校验的显式迁移；布尔字段使用原生 `BOOLEAN`，时间字段使用 `TIMESTAMP`，统一触发器维护 `updated_at`。以下仅列核心字段与约束，避免复制会漂移的完整建表脚本。

## 8.1 用户表：sys_user

- 唯一用户名、BCrypt 密码、昵称/头像、角色、启用状态、JWT `token_version`、创建/更新时间。
- 可移植业务 ZIP 永不包含此表；整站 PostgreSQL 灾难恢复备份才包含管理员数据。

## 8.2 站点配置表：site_config

- 名称、简介、兼容发布地址、背景类型/颜色、PC/移动图片、字体色、兼容开关、公告、乐观锁 `version` 与时间戳。
- `background_type` 由检查约束限制为 `color` 或 `image`；生产数据必须且只能有一行。

## 8.3 分类表：nav_category

- 名称、图标、非负排序、显隐和时间戳；`(sort_order,id)` 联合索引保证稳定读取。

## 8.4 书签表：nav_bookmark

- 分类外键、名称、URL、图标/描述、排序、兼容推荐、新窗口、显隐与时间戳。
- 外键 `ON UPDATE CASCADE ON DELETE CASCADE`；应用删除分类前仍强制关联检查，数据库级联仅作最终一致性约束。

## 8.5 搜索引擎表：search_engine

- 名称、图标、搜索 URL 模板、占位文字、默认状态、排序、显隐和时间戳。
- PostgreSQL 部分唯一索引 `WHERE is_default IS TRUE AND visible IS TRUE` 从数据库层保证最多一个启用默认引擎。

## 8.6 自定义链接表：custom_link

- 标题、URL、位置、排序、显隐和时间戳；位置检查约束只接受 `header/footer`。
- 当前主题不渲染，但导出/导入仍保留该兼容数据。

## 8.7 迁移登记表：schema_migration

- `filename` 为主键，`checksum` 必须是 64 位小写 SHA-256，`applied_at` 记录应用时间。
- 安装向导只在用户明确确认的外部空白 PostgreSQL 中执行权威 schema，并登记相应基线。后续外部数据库升级不由 Compose 自动执行，必须先取得服务商可恢复快照，再按照对应版本的受控迁移说明执行；已经发布并登记校验和的迁移文件不可改写。

---

## 9. 接口规划

## 9.1 前台公开接口

前台公开接口不需要登录。

```text
GET /api/install/status
只获取首次安装状态；不做文件/Redis 探针，不返回组件、连接地址、版本或文件路径

POST /api/install/check
在安装未完成且网页安装启用时执行数据库结构、站点单例、上传目录和 Redis 粗粒度检查

POST /api/install/database/test
测试结构化的外部 PostgreSQL 配置；只返回短期单次 ticket 和脱敏 schema 状态

POST /api/install/database/configure
消费数据库 ticket，按明确确认初始化空库并把连接安全写入后端专用配置卷；完成后重启接管

POST /api/install/redis/test
数据库已接管并验证身份后测试结构化 Redis 配置；只返回短期单次 ticket

POST /api/install/redis/configure
消费 Redis ticket，把连接和可选私有 CA 安全写入后端专用配置卷并绑定数据库实例 UUID；完成后重启接管

POST /api/install/complete
一次性创建站点与首位管理员；完成、禁用、环境未就绪或已有用户时拒绝

GET /api/public/site-config
获取站点配置

GET /api/public/navigation
获取分类和书签数据

GET /api/public/search-engines
获取搜索引擎列表

GET /api/public/custom-links
获取头部/底部自定义链接
```

### 前台导航数据返回结构示例

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "name": "Office",
      "icon": "office",
      "sortOrder": 1,
      "bookmarks": [
        {
          "id": 101,
          "name": "精品PPT",
          "url": "https://example.com",
          "icon": "",
          "description": "PPT资源网站",
          "isExternal": true
        }
      ]
    }
  ]
}
```

---

## 9.2 后台认证接口

```text
POST /api/admin/auth/login
管理员登录

POST /api/admin/auth/logout
管理员无状态退出，客户端丢弃当前 JWT

POST /api/admin/auth/logout-all
递增当前管理员的会话版本，使全部旧 JWT 失效

PUT /api/admin/auth/password
校验当前密码并更新 BCrypt 密码；成功后撤销全部旧 JWT

GET /api/admin/auth/profile
获取当前管理员信息
```

---

## 9.3 后台站点配置接口

```text
GET /api/admin/site-config
获取站点配置；响应包含 version

PUT /api/admin/site-config
更新站点配置；请求必须携带 expectedVersion，成功后 version 加 1
```

`expectedVersion` 缺失或小于 0 返回 `400`；与持久化版本不一致或条件更新未命中返回 `409`。客户端收到 `409` 后必须停止继续编辑/保存并重新读取最新配置。

---

## 9.4 后台分类接口

```text
GET /api/admin/categories
获取分类列表

POST /api/admin/categories
新增分类

PUT /api/admin/categories/{id}
编辑分类

DELETE /api/admin/categories/{id}
删除分类

PUT /api/admin/categories/sort
分类排序

PUT /api/admin/categories/{id}/visible
显示/隐藏分类
```

---

## 9.5 后台书签接口

```text
GET /api/admin/bookmarks
获取书签列表

GET /api/admin/bookmarks?categoryId=1
根据分类获取书签列表

POST /api/admin/bookmarks
新增书签

PUT /api/admin/bookmarks/{id}
编辑书签

DELETE /api/admin/bookmarks/{id}
删除书签

PUT /api/admin/bookmarks/sort
书签排序

PUT /api/admin/bookmarks/batch-move
批量移动书签到目标分类；请求体为 { ids, categoryId }

PUT /api/admin/bookmarks/{id}/visible
显示/隐藏书签
```

---

## 9.6 后台搜索引擎接口

```text
GET /api/admin/search-engines
获取搜索引擎列表

POST /api/admin/search-engines
新增搜索引擎

PUT /api/admin/search-engines/{id}
编辑搜索引擎

DELETE /api/admin/search-engines/{id}
删除搜索引擎

PUT /api/admin/search-engines/{id}/default
设置默认搜索引擎
```

---

## 9.7 后台自定义链接接口

```text
GET /api/admin/custom-links
获取自定义链接列表

POST /api/admin/custom-links
新增自定义链接

PUT /api/admin/custom-links/{id}
编辑自定义链接

DELETE /api/admin/custom-links/{id}
删除自定义链接
```

---

## 9.8 上传接口

```text
POST /api/admin/upload/image
上传 JPG/PNG 背景图片；成功返回受管 `/uploads/backgrounds/...` URL，超过总容量或文件数上限返回 `507`
```

---

## 9.9 数据管理接口

```text
GET  /api/admin/data/export
导出不含管理员和密钥的版本化 ZIP

GET  /api/admin/data/bookmarks/markdown
导出全部分类和书签的人类可读 Markdown 副本；包含隐藏项但不包含管理员、数据库 ID 和内部时间戳

POST /api/admin/data/import/preview
上传 ZIP 并进行零写入预检，返回差异、错误、警告、15 分钟令牌与当前/导入计数

POST /api/admin/data/import/{previewToken}/confirm
复核管理员、包摘要和业务 revision 后创建异步导入任务

GET  /api/admin/data/import/jobs/{jobId}
查询当前管理员创建的任务阶段；服务重启后旧任务可能返回 404，客户端按结果未知处理
```

约束：归档/总展开量 64MiB、单条目最多 16MiB 且背景资产还受 `APP_UPLOAD_MAX_BYTES` 限制、条目最多 100 个；导入事务不修改 `sys_user`。预检后任何业务变更、令牌过期或重复确认都必须拒绝。

---

## 9.10 当前可靠性与生产约束

### 前端状态保护

- 站点配置加载失败时禁止编辑、上传和保存，不能以 fallback 或旧快照覆盖线上值。
- 完整配置快照用于 dirty 判断；浏览器刷新、路由离开和主动重载前提示放弃修改。发生 `409` 后锁定旧表单并引导重新加载。
- 用户资料请求只有明确的 `401/403` 才清除会话；网络错误和 `5xx` 保留令牌及最近资料。并发请求复用同一个 Promise，已有缓存资料时以 30 秒新鲜度窗口避免反复请求。
- Dashboard 的分类、书签和站点配置三个请求独立结算；单项失败不抹掉其他成功项，可只重试失败资源。公开展示数只统计“分类可见且书签可见”的交集，不使用伪造的实时状态。
- 公开首页首次请求失败时显示内置示例数据、降级说明和重试入口；已经成功加载的真实数据在短暂故障时继续保留。站点配置实时同步 `document.title`、description 与 `theme-color` 元信息。

### 后端与入口保护

- `site_config.version` 是配置写入的乐观锁；PostgreSQL 权威 schema 已包含该列。仓库不再包含其他数据库的旧 schema 或转换脚本。
- `JWT_EXPIRATION_MINUTES` 只允许 5–10080 分钟；生产超出边界应在启动校验阶段失败。
- 首次管理员密码复用账号安全的结构规则。`local` 默认密码为 `Local!Start2026`；生产没有默认管理员密码。Compose 默认关闭环境变量引导并启用网页安装，只有用户表为空、安装标记未完成且环境检查通过时才创建首位管理员，不再要求安装口令。
- 网页安装提交在事务内锁定站点单例并再次检查用户表和持久化完成标记，成功后永久关闭；已有用户的升级迁移会回填完成标记。安装状态与错误消息不得返回连接信息、内部版本或路径。
- 生产 `NAV_DEMO_DATA_ENABLED=false`，运行时不补写演示站点、分类、书签、搜索引擎或自定义链接；需要无人值守初始化时可显式启用 `NAV_BOOTSTRAP_ENABLED=true` 并提供强 `ADMIN_PASSWORD`。
- `prod` 默认 `OPENAPI_ENABLED=false`；接口文档只在受控环境显式开启。
- `prod` 只允许 `CACHE_TYPE=redis`。新部署必须使用安装向导测试并保存外部 Redis 的主机、端口、可选 ACL 用户名、密码、逻辑库与超时；旧部署可用 `NAV_REDIS_SOURCE=LEGACY_ENV` 继续读取环境变量。TLS 默认使用系统信任证书，也可保存独立私有 CA；只有解析到可信私网地址并显式确认风险时才允许关闭。
- 数据库尚未接管时 Redis 配置接口不开放；数据库身份验证成功后状态进入 `REDIS_REQUIRED`，Redis test/configure 完成并重启接管后才进入站点环境检查。配置阶段 `/api/health` 返回 `INSTALLING` 以保持安装页可达；安装完成后必须同时探测数据库与 Redis，任一不可用都不得报告 `UP`，也不得重新开放连接配置入口。
- Nginx 对登录 POST 按来源 IP 限制为平均每分钟 5 次；PostgreSQL test/configure、Redis test/configure、安装检查/完成各使用互相独立的平均每分钟 3 次且允许 3 次突发预算，匿名安装状态查询另有每分钟 30 次的 GET 限流；公开健康接口限为每分钟 60 次、允许 20 次突发，后端将 Redis 实际读写探针合并缓存 5 秒。OPTIONS 不消耗安装预算，连接凭据接口不写访问日志。
- `frontend` Nginx 默认监听公网 HTTP 并忽略客户端转发头；直接公网使用不要求代理、SSH 通道或 IP 白名单。后续接入外层 TLS 代理时，可再启用 `WEB_TRUST_PROXY_HEADERS` 并按即时代理来源配置 `WEB_TRUSTED_PROXY_CIDR`。
- `nav-backend/src/main/resources/schema-postgresql.sql` 是安装向导初始化已确认空白外部库的唯一权威 schema。外部数据库升级不由 Compose 自动执行，必须先取得服务商可恢复快照，再按每个版本的受控迁移说明执行。

### 背景图生命周期

- 只管理服务端生成的 32 位小写十六进制 JPG/PNG 文件；外部 URL、非法路径、符号链接和其他文件名不进入自动删除范围。
- PC 与移动端当前配置引用被精确保护。引用查询失败时整次清理跳过；未引用文件只有超过宽限期后才回收。
- 默认单文件 10MiB、受管目录总量 1GB、最多 500 个文件、宽限 24 小时；启动 1 分钟后首次清理，之后每 6 小时清理，并在每次上传前尝试清理。
- 背景图片单文件配置只允许 1–10485760 字节；数据包预检虽然拥有 66MB multipart 入口余量，图片存储仍执行独立上限。该值同时注入前端构建参数，变更后必须重建前后端镜像。

---

## 10. 开发阶段规划

> 以下阶段保留最初的开发规划语境；背景音乐、自定义链接前端等条目代表历史目标或可选扩展，不表示当前内置主题仍挂载这些功能。当前已经生效的可靠性契约以第 9.10 节为准。

## 第一阶段：项目初始化

目标：前后端项目能够正常运行，接口能够联通。

任务：

```text
1. 创建 Vue 3 + Vite + TypeScript 前端项目
2. 创建 Spring Boot 3 后端项目
3. 配置 PostgreSQL 数据库与迁移登记
4. 配置 Redis
5. 配置统一返回结构 Result
6. 配置全局异常处理
7. 配置跨域
8. 配置 Axios 请求封装
9. 配置前端路由
10. 配置后端 /api/health 健康检查接口
```

验收标准：

```text
1. 前端可以访问 /
2. 后台可以访问 /admin
3. 后端可以访问 /api/health
4. 前端能够成功请求后端测试接口
```

---

## 第二阶段：前台页面静态还原

目标：先还原第一张截图中的前台导航页面效果。

任务：

```text
1. 编写 PortalHome.vue
2. 编写 SiteHeader.vue
3. 编写 SearchBar.vue
4. 编写 CategoryGrid.vue
5. 编写 CategoryCard.vue
6. 编写 BookmarkItem.vue
7. 编写 TopActionBar.vue
8. 编写 BackgroundMusic.vue
9. 编写前台 SCSS 样式文件
10. 使用 mock 数据渲染页面
```

验收标准：

```text
1. 页面整体结构接近截图
2. 搜索框居中展示
3. 分类卡片按网格展示
4. 书签链接正常排列
5. 桌面端展示正常
6. 移动端基础适配正常
```

---

## 第三阶段：后台页面静态搭建

目标：搭建第二张截图中的后台管理页面。

任务：

```text
1. 编写 AdminLayout.vue
2. 编写 AdminSidebar.vue
3. 编写 AdminHeader.vue
4. 编写 NavigationConfig.vue
5. 编写 SiteBaseForm.vue
6. 编写 ThemeConfigForm.vue
7. 编写 MusicConfigForm.vue
8. 编写 SubscribeConfigForm.vue
9. 编写 CustomLinkTable.vue
10. 编写后台 SCSS 样式文件
```

验收标准：

```text
1. 左侧菜单展示正常
2. 右侧内容区域展示正常
3. 表单布局接近截图
4. 开关组件、颜色选择器、输入框展示正常
5. 自定义链接表格展示正常
```

---

## 第四阶段：后端基础接口开发

目标：完成数据库表和基础 CRUD 接口。

任务：

```text
1. 创建数据库表
2. 完成用户登录接口
3. 完成站点配置接口
4. 完成分类管理接口
5. 完成书签管理接口
6. 完成搜索引擎接口
7. 完成自定义链接接口
8. 完成上传接口
9. 生成接口文档
```

验收标准：

```text
1. local/受控测试环境可查看 Knife4j / Swagger，生产默认关闭
2. 登录接口正常返回 token
3. 站点配置可以新增和修改
4. 分类可以增删改查
5. 书签可以增删改查
6. 上传接口可以返回文件 URL
```

---

## 第五阶段：前后端联调

目标：前台和后台均使用真实接口。

任务：

```text
1. 前台读取站点配置
2. 前台读取分类和书签
3. 前台读取搜索引擎
4. 后台修改站点配置
5. 后台新增/编辑/删除分类
6. 后台新增/编辑/删除书签
7. 后台上传背景图
8. 后台配置背景音乐（可选扩展，当前主题未挂载）
9. 后台配置自定义链接（兼容后端保留，当前内置前端未挂载）
```

验收标准：

```text
1. 后台修改站点名称后，前台刷新能看到变化
2. 后台修改背景颜色后，前台刷新能看到变化
3. 后台新增分类后，前台能展示
4. 后台新增书签后，前台能展示
5. 后台隐藏分类后，前台不展示
6. 后台上传背景图后，前台能应用
```

---

## 第六阶段：权限、安全与优化

目标：系统可以稳定部署使用。

任务：

```text
1. 后台路由登录保护
2. 后端接口 JWT 鉴权
3. 密码加密存储
4. 表单参数校验
5. 上传文件格式限制
6. 上传文件大小限制
7. 接口异常统一处理
8. 管理员强密码策略与当前密码校验
9. JWT 持久化版本校验与全部会话撤销
10. 外部 Redis 安装检查与运行健康探测
11. 站点配置与导航数据 Redis 缓存（后续扩展）
12. 页面加载状态优化
13. 移动端适配优化
14. 站点配置版本冲突、失败关闭和完整未保存提示
15. 管理会话在网络/5xx 时保留，仅 401/403 注销
16. 登录入口限速、生产 OpenAPI 默认关闭与 JWT 时长边界
17. 上传总量/数量配额和孤儿文件安全回收
18. Dashboard 独立失败与准确公开统计
```

验收标准：

```text
1. 未登录无法访问后台管理接口
2. 未登录访问 /admin 会跳转登录页
3. 错误接口返回统一格式
4. 上传非法文件会被拒绝
5. 前台加载速度正常
6. 移动端基本可用
7. 弱密码、复用密码、错误当前密码和不一致确认均被拒绝
8. 改密后旧密码不能登录，当前及其他设备的旧 JWT 均返回 401
9. 退出全部会话后密码保持不变，但此前签发的全部 JWT 均失效
10. 缺少 `ver` 的历史 JWT 仅在数据库令牌版本为 0 时兼容
11. 旧站点配置版本写入返回 409 且不覆盖新值
12. 临时网络或 5xx 不清除仍可恢复的管理会话
13. 配置引用查询失败时不删除任何上传图片
14. 生产默认不补写演示业务数据且不开放 Swagger/OpenAPI
```

---

## 第七阶段：部署上线

目标：项目可以通过同一个安装脚本选择 Docker 或宿主机模式部署。

任务：

```text
1. 编写前端 Dockerfile 与独立 yunlume-frontend 镜像
2. 编写后端 Dockerfile 与独立 yunlume-backend 镜像
3. 编写只包含 frontend/backend 的 docker-compose.yml
4. 编写宿主机 JAR/静态文件发行包、systemd 与 Nginx 模板
5. 编写 Docker/宿主机统一 install.sh、版本清单和 SHA-256 校验
6. 配置外部 PostgreSQL/Redis 安装初始化与服务商备份边界
7. 配置上传、日志和安装配置持久化
8. 增加公网 HTTP 六步安装向导和持久化完成标记，不增加安装口令、SSH 通道或 IP 白名单
9. 为两种模式实现显式升级、健康检查和失败回滚
```

验收标准：

```text
1. install.sh 默认 Docker 模式可以启动 frontend/backend
2. install.sh --mode host 可以启动 JAR、静态前端、systemd 与系统 Nginx
3. 两种模式均可通过公网 HTTP 访问前端、后台和 API
4. 上传文件不会因容器或服务重启丢失
5. 新库自动进入六步安装向导，环境检查与强密码校验通过后可创建唯一管理员
6. 已安装站点和并发重复提交均不能再次初始化
7. 新版本健康失败时恢复上一组应用文件与运行配置
```

---

## 11. 推荐开发优先级

建议先做最小可用版本，不要一开始就做太复杂。

### 11.1 第一版必须完成

```text
1. 前台首页展示
2. 后台登录
3. 站点配置
4. 分类管理
5. 书签管理
6. 前台读取真实导航数据
7. Docker 基础部署
```

### 11.2 第二版再增加

```text
1. 背景图片上传
2. 背景音乐配置
3. 搜索引擎配置
4. 自定义头部/底部链接
5. 分类排序
6. 书签排序
7. 移动端适配优化
```

### 11.3 第三版可扩展

```text
1. 访问统计
2. 多主题切换
3. 书签图标自动识别
4. 站点可用性检测
5. 多用户权限
6. 操作日志
```

已提前完成：可移植数据导入导出、外部 PostgreSQL 首次连接与空库初始化、外部 Redis 连接安装、安装实例身份校验，以及需要显式确认服务商备份的镜像回滚。`database_config` 仍需独立加密备份；外部 PostgreSQL 的整库备份/恢复/版本迁移与 Redis 的服务端持久化/备份/恢复均依赖服务商能力和对应演练。

---

## 12. 维护规范

为了方便后期维护，项目开发时必须遵守以下规则。

### 12.1 前端维护规范

```text
1. 页面文件放 views，不写太多业务细节
2. 组件文件放 components，一个组件只负责一个功能
3. 请求接口统一放 api
4. 类型定义统一放 types
5. 状态管理统一放 stores
6. 复用逻辑统一放 composables
7. 工具方法统一放 utils
8. 样式按 portal/admin/common 拆分
9. 不在组件里硬编码接口地址
10. 不在页面里直接写复杂请求逻辑
```

### 12.2 后端维护规范

```text
1. 按业务模块拆包，不把所有 Controller 写在一起
2. Controller 只负责接收请求和返回结果
3. Service 负责业务逻辑
4. Mapper 负责数据库访问
5. Entity 对应数据库表
6. DTO 用于接收前端参数
7. VO 用于返回前端数据
8. 统一返回 Result
9. 统一异常处理
10. 统一参数校验
```

### 12.3 文件拆分原则

```text
一个页面 = 一个 view 文件
一个功能块 = 一个 component 文件
一个接口模块 = 一个 api 文件
一个数据类型 = 一个 type 文件
一个业务模块 = 一个后端 module 包
一个数据库表 = 一个 entity 文件
一个请求对象 = 一个 DTO 文件
一个返回对象 = 一个 VO 文件
```

---

## 13. 前台数据流设计

```text
PortalHome.vue
   |
   | 调用 useSiteConfig
   v
site.store.ts
   |
   | 调用 site.api.ts
   v
GET /api/public/site-config

PortalHome.vue
   |
   | 调用 useBookmarks
   v
navigation.store.ts
   |
   | 调用 category.api.ts / bookmark.api.ts
   v
GET /api/public/navigation
```

渲染流程：

```text
1. 页面加载
2. 获取站点配置
3. 应用背景颜色、字体颜色、背景图
4. 获取导航分类和书签
5. 渲染分类卡片
6. 渲染书签链接
7. 初始化搜索框
8. 同步页面标题、描述和 theme-color 元信息
9. 任一首次请求失败时展示可见的 fallback 状态和重试入口
```

---

## 14. 后台数据流设计

```text
SiteConfigView.vue
   |
| 完整读取配置并记录 version/快照
   v
基础信息 / 背景图片 / 页面功能表单
   |
| 提交完整当前主题配置 + expectedVersion
   v
site.api.ts
   |
   | PUT 请求
   v
/api/admin/site-config
   |
   | 后端保存
   v
site_config 表
```

保存成功后使用服务端响应替换表单和快照；`409` 时锁定当前旧表单并要求重新加载。加载失败、背景图片上传中或图片模式缺少 PC 图时不得提交。

分类管理流程：

```text
FolderManage.vue
   |
   | 加载分类列表
   v
category.api.ts
   |
   | GET /api/admin/categories
   v
FolderTable.vue 展示
```

书签管理流程：

```text
BookmarkManage.vue
   |
   | 加载书签列表
   v
bookmark.api.ts
   |
   | GET /api/admin/bookmarks
   v
BookmarkTable.vue 展示
```

---

## 15. UI 设计方向

### 15.1 前台 UI 方向

根据第一张截图，前台应保持：

```text
后台可配置的纯色或 PC/移动端独立图片背景
文字完整使用后台配置的字体色，不派生灰色层级或文字光晕
公开正文和书签保持清晰可读，固定字号不低于 12px
居中标题
居中搜索框
分类卡片网格布局
卡片圆角边框
卡片标题带图标
书签链接横向排列
右上角工具按钮
整体简洁、偏工具站风格
首次公共数据失败时展示明确降级提示和重试按钮
```

### 15.2 后台 UI 方向

根据第二张截图，后台应保持：

```text
浅色背景
桌面左侧固定菜单，移动端使用侧滑菜单
右侧表单内容
顶部显示当前配置页面标题
桌面表单保持清晰分组，移动端改单列并保证 44px 触控高度
左侧品牌与导航栏保持原来的紧凑字号；右侧辅助文字固定字号不低于 12px，常规正文、表格、表单和按钮约为 14–16px
字体与组件高度同步放大，避免表格、弹窗和窄屏卡片发生裁切
开关使用醒目颜色
配置项分组展示
桌面使用表格，移动端使用等价管理卡片
侧滑菜单具备遮罩、滚动锁、键盘焦点管理和 Esc 关闭能力
整体偏管理系统风格
```

---

## 16. 最小可用版本 MVP

第一阶段不要追求所有功能，先完成一个能用的版本。

MVP 功能范围：

```text
1. 管理员登录
2. 修改站点名称
3. 修改站点简介
4. 修改背景颜色
5. 修改字体颜色
6. 新增分类
7. 编辑分类
8. 删除分类
9. 新增书签
10. 编辑书签
11. 删除书签
12. 前台展示分类和书签
```

MVP 之后仍暂缓的功能：

```text
1. 背景音乐
2. 背景特效
3. 站点订阅
4. 多用户权限
5. 访问统计
6. 主题市场
```

数据导入导出与备份恢复已在第五部分完成，不再属于暂缓项。

---

## 17. 预计开发周期

以一个人开发为例，建议周期如下：

| 阶段 | 内容 | 预计时间 |
|---|---|---|
| 第一阶段 | 项目初始化 | 0.5 - 1 天 |
| 第二阶段 | 前台静态页面 | 1 - 2 天 |
| 第三阶段 | 后台静态页面 | 1 - 2 天 |
| 第四阶段 | 后端接口开发 | 2 - 4 天 |
| 第五阶段 | 前后端联调 | 1 - 2 天 |
| 第六阶段 | 权限和优化 | 1 - 2 天 |
| 第七阶段 | Docker/宿主机双模式部署 | 1 - 2 天 |

预计总时间：

```text
7 - 14 天
```

具体时间取决于页面细节、交互复杂度、是否需要移动端深度适配。

---

## 18. 最终交付物

```text
1. 前端项目 nav-frontend
2. 后端项目 nav-backend
3. 数据库初始化 SQL
4. Nginx 配置
5. Docker Compose 部署文件
6. 项目 README
7. 接口文档（生产默认关闭，仅受控环境开启）
8. 管理员引导说明（local 为 `admin / Local!Start2026`；生产默认直接使用公网 HTTP 六步网页安装，也可显式启用环境变量引导）
9. Docker/宿主机统一安装脚本、发行清单与宿主机发行包
```

---

## 19. 总结

本项目建议按以下思路推进：

```text
先静态页面
再数据库设计
再后端接口
再前后端联调
最后权限、优化和部署
```

开发过程中最重要的是保持文件结构清晰：

```text
页面归页面
组件归组件
接口归接口
类型归类型
业务归业务
样式归样式
```

这样后面无论是增加搜索引擎、主题切换、访问统计、数据导入导出，还是扩展多用户权限，都不会造成代码混乱。

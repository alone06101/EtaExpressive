# EtaExpressive

<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
" alt="EtaExpressive" width="128" height="128">
</div>

[English](README_EN.md)

---

<p><a href="https://github.com/alone06101/EtaExpressive/releases"><img src="https://img.shields.io/github/downloads/alone06101/EtaExpressive/total?logo=github&amp;label=%E4%B8%8B%E8%BD%BD%E9%87%8F&amp;color=1677FF" alt="GitHub Releases 累计下载量"></a> <img src="https://img.shields.io/badge/minSdk-34-3DDC84?logo=android&amp;logoColor=white" alt="minSdk 34"> <img src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin 2.4.20"> <img src="https://img.shields.io/badge/AGP-9.4.0-3DDC84?logo=android&amp;logoColor=white" alt="AGP 9.4.0"> <img src="https://img.shields.io/badge/Assistant%20Integrations-ColorOS%20%26%20HyperOS-1677FF" alt="Assistant integrations for ColorOS and HyperOS"></p>

## 本 Fork 的变化
本项目在 [Eta 上游](https://github.com/Mangi-11/Eta) 的基础上，主要围绕 **Material Design 3 Expressive（M3E）** 的视觉风格重新设计了界面外观，并补充了一批功能改进。以下列出与上游相比的核心差异（完整改动见提交历史）：

### 相对 Sam-Fic/EtaExpressive 的改动

本仓库是 [alone06101/EtaExpressive](https://github.com/alone06101/EtaExpressive) 本身，向上溯源为 [Sam-Fic/EtaExpressive](https://github.com/Sam-Fic/EtaExpressive) → [Mangi-11/Eta](https://github.com/Mangi-11/Eta)。在继承 M3E 界面与既有改进的基础上，本仓库额外包含以下改动：

- **服务商系统提示词恢复**：上游提交 `c0dc9aac` 以「运行时已使用助手提示词，提供商页的系统提示词只是残留设置」为由，整块删除了「AI 服务提供商」页的系统提示词输入，但 `ProviderSetting.systemPrompt` 与数据库 `system_prompt` 列始终保留，结果是「有值却看不见、改不了也清不掉」。本仓库恢复该入口与完整保存链路（草稿、状态保存、三语言文案），空串落库为 `null`，与「从未设置」等价。
- **助手侧「跟随服务商」开关**：助手人格页新增开关（默认开启）。开启且当前服务商填写了系统提示词时，以它作为提示词基座，人格设定追加在后；关闭或服务商留空时完全沿用原行为。
- **提示词优先级统一**：新增唯一合流点 `AssistantPrompt.runtimeBase`，优先级为「服务商提示词（跟随时）→ 助手人格 → 内置默认」，并接入 `buildRuntimeConfig`、外部入口归一化、派发工作会话回落与 `AssistantRepository.systemPrompt` 四处。老档案缺字段按默认值解码，无需数据库迁移。
- **WebDAV 云端备份**：备份页新增云端通道。备份包上传到你自己的 WebDAV 服务器（Basic Auth），支持每天或每周自动备份，也可从云端逐条恢复。密码用 AndroidKeyStore 的 AES-GCM 硬件加密存储，密钥不出 TEE；界面上可临时切换明文视图核对，但落盘始终是密文。云端恢复与本地导入共用同一套校验链（format、schema 版本、体积上限），非本应用生成的包会被直接拒绝；上传与下载都走 cacheDir 临时文件并在完成后删除。云端备份与本地导出共用同一套内容选项（Linux 环境、模型等按页面勾选打包），WebDAV 配置本身也随备份包迁移。
- **会话副本**：侧栏会话长按菜单可「创建会话副本」——克隆出包含全部消息、附件与模型历史的独立会话，标题加「副本」后缀，保留原分类与模型绑定。实现复用「导出会话分析 → 导入为新会话」这条链路，因此消息 id、run id 重映射与附件引用都由已验证的代码处理；克隆时一并复制模型历史文件，副本继续对话不会丢上下文。
- **自动化流程**：`auto-release.yml` 在 main 推送后按 `versionName` 自动发版——仅当版本号变化且对应 tag 不存在时构建并发布，版本号没变则整轮跳过（checkout 需带 `fetch-tags: true`，否则 tag 检查恒失败；创建前先确认同名 Release 不存在，避免与 tag 触发的发版路径撞车）；`sync-upstream.yml` 每天 UTC 02:30 比对上游，无新提交直接结束（不建分支、不开 PR），有更新则建分支合并并开 PR，冲突则中止合并、开 issue 交人工处理。
- **能力协助（跨提供商协作）**：首选模型缺少本轮所需能力时，按可配置顺序临时交给其他提供商完成这一轮，下一轮自动回到首选。覆盖六个维度：视觉输入（看图）、视频输入（看视频）、图像输出（出图）、视频输出（出视频）、工具调用、上下文窗口。入口在「模型提供商」列表顶部：总开关 + 逐项勾选参与 + 上移/下移排优先级。判定刻意保守：普通对话不触发切换；首选自身未配置密钥时不接管（那是配置问题）；工具能力只在模型明确标注不支持时接管（未知按支持处理）；窗口协助只在上一轮实测已顶到当前窗口 90% 时介入，没有实测锚点就不动。语音合成不纳入——朗读是独立子系统，已有专门的朗读模型设置，纳入会与用户的显式选择冲突。
- **运行中插话**：生成过程中发送的内容立即作为引导注入当前运行（轮次边界注入补充，流式正文中途打断当前请求但保留已写出的内容），不再排队等本轮结束。此前只有带附件、引用或处于暂停态才立即送入，纯文字会被静默排到下一轮。
- **分析文件导入导出**：导入前按文件大小与消息条数做闸门（64MB / 10 万条 / 单条 16M 字符），读取时不再复制整份字节；导出「原始模型 I/O」时运行时事件也走凭据脱敏（此前只脱敏了 transcript）；导入的图片会写进附件目录并换成文件引用，跨设备导入后模型仍能读到历史图；超大会话的模型历史编码失败时降级为不带 checkpoint，而不是整次导入失败；导入时丢弃上一次运行的状态型通知（runtime_failed / stopped / model_retry / interrupted），避免新会话一打开就显示「可继续断连运行」；导入完成后弹窗选择「沿用导出时的模型与助手」还是「使用当前的」，沿用项逐项做存在性校验；导入与导出都有阻塞式进度提示（此前只有按钮防重，没有任何可见反馈）；空白会话长按可「导入分析文件到此会话」，保留该会话的标题与分类，只填充内容（非空会话不提供，避免覆盖）。
- **备份与调度健壮性**：自动备份失败后按 15 分钟窗口重试，不再等满一个周期；上传加互斥且临时文件用随机名，避免闹钟与「保存后立即备份」并发时写坏同一个包；密码加密不可用时明确提示而不是静默沿用旧密文；云端列表解密失败会报错，不再伪装成「云端没有备份」；网络判定只看 INTERNET 能力，需登录的公共 Wi-Fi 与企业网不再被误判为无网络；仅修改备份周期或内容选项时只重排闹钟，不重复上传整包。会话副本重复克隆不会叠加「（副本）」后缀。
- **编译前静态预检**：`tools/preflight.py` 在构建开始前检查 DAO 注解是否紧跟声明、注释是否被拆开（代码插进注释与声明之间）、图标与 withContext 是否漏 import、业务文件是否直接实例化进度指示器、高频跨包符号是否已 import、`@Query` 的 `:name` 与函数参数是否一致、三语文案 key 是否齐全，几十秒失败而不是等一轮 8 分钟的构建。失败模式与处理步骤记录在 [docs/engineering-failure-modes.md](docs/engineering-failure-modes.md)。
- **测试稳定性**：`DispatchSchemaDriftMigrationTest` 与 `EtaDatabaseIdentityRecoveryTest` 验证的是「生产入口打开固定库名 `eta.db`」的行为，而 Robolectric 让同一个 JVM 内的所有测试类共享应用私有目录；别的测试类留下的 SQLite 连接不归单例管，清库后仍可能把旧版本 checkpoint 写回同一路径，CI 上表现为不同用例轮流随机失败。新增 `DatabaseTestRetryRule` 做确定性重试（最多 3 次、失败后退避并关闭单例），只有持续失败才会真正变红。
- **构建链路修复**：修正 `build-debug` 工作流下载的 Compose AAR 版本（与 `ComposeSemanticsAarTransform.VERSION`、`libs.versions.toml` 对齐到 `1.13.0-alpha01`）、`buildSrc` 版本断言、以及 traversal 补丁测试的过期期望；签名校验改为校验本仓库自己的证书指纹。

> 提示词行为：服务商提示词留空时，行为与上游完全一致。

### 外观与 UI
- **Material Design 3 Expressive（M3E）适配**：对话气泡、卡片、按钮与过渡动效均参考 [Material Design 3 Expressive](https://m3.material.io) 的示例规范重新设计，整体视觉更贴近 Google 最新的 Material 表达风格。
- **细节打磨**：界面细节仍在持续优化中，欢迎体验。如有建议，请随时提交 Issue 或 Pull Request。

### 功能改进
- **MCP 自主配置**：新增 `eta_configure_mcp` 工具，允许 Agent 通过对话自行配置 MCP 服务器（增、删、改、查、刷新发现、启用/停用工具），配置将于下一轮对话生效。
- **本地 Skill 安装**：新增 `skills_install_local` 工具，支持直接从手机本地目录（如下载文件夹、解压后的 ZIP、Linux 工作区路径）安装 Skill；自动校验目录内是否仅含单一 Skill，并支持覆盖已有版本。
- **CI 与测试稳定性**：修复了多组单元测试中的时序敏感断言与 Lint 错误，确保 `verify` 作业在 GitHub Actions 中稳定通过。
- **Release 工作流**：跟进实际发版流程，补充了中英双语更新日志的自动生成与 Release Assets 上传功能。

如需完整的新增功能列表，请参阅本仓库的 [Releases](https://github.com/alone06101/EtaExpressive/releases) 与 [提交历史](https://github.com/alone06101/EtaExpressive/commits/main)。

## 关于本项目

**越过沙盒的 Android 系统级 AI 助手**

EtaExpressive 不仅是一个聊天框。将您自己的模型接入手机：它可以调用系统 API、查看屏幕、运行终端、读写文件，也能检索通知、日程、相册等本机信息。在同一轮对话中，提问与任务执行可无缝衔接。

本项目基于 [Eta](https://github.com/Mangi-11/Eta)。上游项目已构建了 Agent Runtime、系统入口和工具层；本 Fork 致力于进一步优化日常使用体验，包括压缩策略、备份与还原、打开方式、视觉模型、浏览器以及 Linux 环境。模型与服务商由您自行选择，**需要自备 API Key（BYOK）**。

**它能实际访问的系统范围**：
- **系统**：闹钟、音量、媒体播放与设备状态，直接通过 Android API 调用，无需逐步操作界面。
- **屏幕**：基于无障碍服务的 GUI Agent，支持点击、滚动和文本输入；您可随时停止或接管控制权。
- **终端**：支持 Android Shell 以及 Alpine / Debian Linux，可运行文件、脚本及守护进程任务。
- **本机数据**：通知、相册、日历、短信等；部分数据源需要 Root 权限，以及对应 ROM 与应用的支持。
- **系统入口**：在配备 LSPosed 的环境下，可接管电源键、小布助手和超级小爱，将原生助手入口的任务路由至 EtaExpressive。

支持 **Android 14 及以上的 ARM64（arm64-v8a）设备**。APK 仅打包 ARM64 原生库，不提供 32 位 ARM 与 x86 构建。App 本体不限手机品牌，基础功能无需 Root。Root 和 LSPosed 可进一步解锁系统级访问与助手入口，具体取决于设备授权和 ROM 支持情况。

[下载 APK](https://github.com/alone06101/EtaExpressive/releases/latest) · [快速开始](#快速开始)

## 界面预览
| GUI Agent | 作为系统语音助手使用 |
| :-------: | :-----------: |
| ![EtaExpressive GUI Agent 执行演示](docs/Screenshots/demo_gui_agent.gif) | ![从小布助手入口发起 EtaExpressive 任务](docs/Screenshots/demo_tools.gif) |

更多界面：聊天、设备工具与设置
| 聊天首页 | 调用子代理 | 系统 API 调用 |
| :---: | :---: | :---: |
| ![聊天首页](docs/Screenshots/chat_home.jpg) | ![调用子代理](docs/Screenshots/call_subagents.jpg) | ![系统 API 调用](docs/Screenshots/chat_device_direct.jpg) |
| 使用 Decision 模型 | 自建工具能力 | Skills |
| :---: | :---: | :---: |
| ![设置](docs/Screenshots/decision_model.jpg) | ![工具能力](docs/Screenshots/tools.jpg) | ![Skills](docs/Screenshots/skills.jpg) |

## 核心能力
### 执行工具
- **系统 API 调用**：通过 Android API 与系统 Intent 设置闹钟、控制媒体、调整音量、读取设备状态，无需逐步操作界面。
- **GUI Agent**：结合无障碍 UI 树、控件定位与按需截图，执行点击、滚动和输入；通过浮层展示执行状态，支持随时停止和接管。
- **内置浏览器**：通过 WebView 加载页面、提取正文、操作 DOM 与截图；支持桌面/手机模式。您也可以打开同一浏览器会话手动接管。
- **终端与文件**：支持 Android user/root Shell、Alpine / Debian Linux、文件读写与脚本执行，支持会话保持、异步命令和守护任务。

同一项任务可组合多种工具：例如先读取网页资料，再用脚本整理文件；或从通知中找到订单线索，再打开应用确认状态。

### 上下文与扩展
- **个人上下文**：按需检索通知、应用使用情况与位置信息；相册、日历、短信、录音、健康摘要、聊天图片等专用检索需要 Root 权限，部分来源还要求对应 ROM 与应用支持。
- **长期记忆**：使用本机 `MEMORY.md` 保存跨对话背景，核心内容按预算加入上下文，其余按需读取；支持编辑、清空和关闭。
- **Skills**：按需加载任务方法、参考资料与脚本资源，支持从公开 GitHub 仓库安装和本地 ZIP 导入；安装过程不会执行脚本或开启额外权限。
- **MCP**：通过 Streamable HTTP 连接远程工具，支持 Bearer Token；工具逐项启用，与本机工具共同参与任务。

### Agent Runtime
Agent Runtime 运行在 EtaExpressive App 内，来自聊天页面和系统助手的请求共用同一个 Agent Loop。模型通过 Tool Calling 选择工具，执行结果返回至上下文后，再决定下一步操作。工具调用按 JSON Schema 校验，并在执行前检查权限；Hook 进程仅负责入口拦截与结果回传。

Runtime 同时管理流式事件、Steering（引导）、取消和增量 Transcript（转录）。追加指令在当前 Turn 完成后进入下一轮，会话与结果在本机归档；中断后尝试恢复已有记录，不自动重放操作。详细设计见 [Agent Runtime](docs/AGENT_RUNTIME.md)。

## 为移动设备重新设计的终端
EtaExpressive 的终端可由 Agent 调用，也可由您直接操作。多个会话各自保留工作目录与环境；简洁模式按命令展示输入输出，PTY 控制台支持 TUI、快捷键与 ANSI 渲染。异步命令和守护任务均可查看日志并主动停止。

- **Linux 环境**：可选 Alpine 或 Debian。普通设备使用 PRoot，Root 设备还可选择 chroot。两种后端独立安装，不自动迁移数据；PRoot 中的模拟 root 不提供 Android 系统权限。
- **开发工具**：Python、Node.js、SSH、APK 分析与 Kimi Code 可按需安装。
- **文件管理**：私有工作区支持导入、导出；已授权的 Android 目录可共享至 Linux 的 `/workspace/mounts/`，也可在 App 内直接浏览 Linux 文件。

EtaExpressive 本体可读取项目、修改代码、运行命令并验证结果。若想在手机上持续进行编程工作，[Kimi Code](https://github.com/MoonshotAI/kimi-code) 的 **Kimi Web** 提供了更适合移动端的 Web UI，可在浏览器中持续对话、查看代码修改与执行结果，享受完整的 Coding Agent 工作体验，随时随地进行 Vibe Coding。

在 EtaExpressive 中安装 Linux、Node.js 与 Kimi Code 后，即可从首页一键启动 Kimi Web，或在终端运行 `kimi`。Kimi 使用独立的模型配置与会话，需单独完成登录或配置；离开页面后可返回继续使用，也可从 EtaExpressive 主动停止。

## 模型与 BYOK
使用 EtaExpressive 的 AI 功能需要自备模型服务的 **API Key**。可添加 OpenAI-compatible、Anthropic Messages 等自定义服务，也可拉取或手动添加模型。没有内置提供商，密钥和配置均保存在您自己的备份中。

Provider 层支持 OpenAI-compatible Chat Completions、Responses API 和 Anthropic Messages，包括 SSE、Tool Calling、图片输入、视频封面帧与推理内容。您可以自定义服务地址、请求头和请求体，调整上下文长度与思考档位。视觉能力按模型 ID 自动判断，也可在编辑模型时手动覆盖；文本模型默认不发送图片。

提供商配置中的“自定义请求头”默认折叠，可添加、编辑和删除名称/值对，保存后用于模型列表与对话请求；“测试连接”会使用尚未保存的配置。支持覆盖 `User-Agent`，认证和传输请求头仍由 EtaExpressive 管理。连接 OpenCode 官方端点时，EtaExpressive 会自动为每段对话发送稳定的 `x-opencode-session`，无需手动填写；默认客户端标识仍为 `Eta`。

## 系统助手入口
- **长按电源键**：选择唤起系统默认助手、Gemini 或 EtaExpressive。
- **EtaExpressive 系统助手**：从电源键入口打开 EtaExpressive 文字对话面板，支持屏幕上下文与连续追问。
- **小布 / 超级小爱接管**：保留厂商助手的电源键入口，并将请求路由至 EtaExpressive，使用您自行配置的模型。

电源键接管需要 LSPosed 与对应系统的支持。

## 解锁 Gemini 与一圈即搜
- **Gemini 解锁**：补全 Gemini 系统助手能力，支持将 Google App 设为系统应用、锁屏与亮屏状态下的语音输入，以及息屏状态下的热词唤醒补偿。
- **一圈即搜**：解锁一圈即搜功能，可通过长按手势条或双指识屏触发。

需要 LSPosed 与对应系统支持，具体功能与适配说明见 [技术实现](docs/TECHNICAL.md)。

## 权限与数据边界
系统工具、敏感读取、敏感操作、终端与文件、网页浏览、记忆均有独立开关，当前默认开启。Runtime 在执行前会重新检查权限，撤权或断连不会覆盖已保存的配置。

- **数据去向**：任务所需的对话、图片和工具结果会发送给配置的模型服务；本地 Runtime 不代表本地推理。自定义 HTTP 地址会明文传输 API Key 与请求内容。
- **本机记录**：敏感工具及 MCP 的原始参数、结果不写入持久化会话，模型回复仍会保存。通知历史在授权后保存最近 7 天、最多 1000 条；MCP 认证令牌加密保存。
- **会话与备份**：支持消息复制、编辑、从某轮删除和回复重新生成。备份为 ZIP 格式，可覆盖对话、助手、技能、MCP、设置、聊天附件，以及可选的完整 Linux 环境；备份包含 API Key。
- **运行边界**：任务可随时停止或接管。后台运行受 Android 与厂商进程管理影响，强停或重启后需手动启动；系统与应用更新也可能需要重新适配 Hook。

## 快速开始
1. 从 [Releases](https://github.com/alone06101/EtaExpressive/releases/latest) 下载 APK，安装后在“模型提供商”中填写 API Key 并选择模型。执行任务需要 Tool Calling，理解图片还需模型支持图片输入。
2. 按任务需要配置工具开关与权限：GUI Agent 需要无障碍服务；通知、应用使用情况需分别授权；位置工具需要“始终允许”。工具页可查看当前设备的可用能力。
3. 开始对话。需要 Linux 时，在“Linux 工具环境”中安装发行版、基础工具及所需开发工具；需要系统入口时，参见 [系统助手入口](#系统助手入口)。
   - **普通设备**：Android 14+ 的 ARM64 机型，可使用聊天、浏览器、记忆、Skills、MCP、普通终端与私有工作区；GUI 和本机信息读取按需授权。Linux 支持对应的 64 位设备。
   - **Root 设备**：进一步开放系统设置修改、应用管理、受保护文件与专用个人数据检索，以及 Root Shell 和 chroot。
   - **LSPosed 与适配 ROM**：开放厂商助手接管、系统快捷入口及 Google 能力增强；部分功能另需 Root。

联系人、短信、日历等专用检索目前仍需要 Root。完整条件与验证范围见 [设备支持说明](docs/ROOTLESS_SUPPORT.md)。

## 深入了解
- [设备支持与权限边界](docs/ROOTLESS_SUPPORT.md)：普通设备、Root、文件工作区与后台运行。
- [技术实现](docs/TECHNICAL.md)：设备工具、数据检索、浏览器、终端与系统集成。
- [Agent Runtime](docs/AGENT_RUNTIME.md)：Agent Loop、Provider、Steering、Transcript 与结果恢复。
- [HyperOS 系统入口](docs/HYPEROS_SYSTEM_ENTRY.md)：电源键、一圈即搜的适配条件与验证边界。
- [终端原生组件](docs/TERMINAL_NATIVE.md)：PTY、PRoot 及随包源码的构建方式。

## 参考与致谢
- [Eta](https://github.com/Mangi-11/Eta)：本项目基于的上游项目。
- [y2485871697/Eta](https://github.com/y2485871697/Eta)：参考仓库，大量实用功能均来自此分支。
- [Number444/Eta](https://github.com/Number444/Eta)：参考分支，小米超级岛的实现来源：焦点通知 param_v2 官方协议、双通道岛展开机制（新 ID 首发才展开、8 秒自动撤销）与真机全链路测试文档。
- [liyunkkk/Eta](https://github.com/liyunkkk/Eta)：参考分支，灵动岛思维流的实现来源：思考/输出流式预览、工具语义化与通知卡片呼吸动效计时。
- [NatumRagnag/Eta](https://github.com/NatumRagnag/Eta)：参考分支，小米宿主桥接的实现来源：HyperOS 4 UIAgent 工具桥、内部 ToolsBridge 与 Vision / 外部 Agent 服务。
- [yooma1/Eta666](https://github.com/yooma1/Eta666)：参考分支，米家直连的实现来源：mijia 云客户端（OAuth2 授权 / 设备同步 / 属性读写 / 动作）、MIoT 规格解析与中文语音匹配核心零改造移植，`mijia_control` 工具目录原样引入；设置界面按本仓 M3 分段列表口径重写。
- [badnng/Hyper-pick-up-code（澎湃记）](https://github.com/badnng/Hyper-pick-up-code)：取餐码 / 取件码识别功能的实现参考，核心识别与规则引擎逻辑移植自该项目（AGPL-3.0-only），归属与许可证说明见 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。
- [Pi Coding Agent](https://github.com/earendil-works/pi)：Agent Runtime 的核心参考，包括 Agent Loop、Tool Calling、Steering 与 Transcript 状态管理。
- [dsh-superagent](https://github.com/deepseek-ai/dsh-superagent-prototype)：DeepSeek 开源的主动式 Agent 研究原型。「调度智能体」的理念来源：主 Session 仅负责路由与研判、工单/授权/准入流水线，以及结构化提问、如实汇报等行为律。
- [TaiJi（太极）](https://github.com/zhushanwen321/tai-ji)：AI Agent 桌面工作台。「调度台」的会话形态与机制参考：受限工具白名单的调度模式、自包含 Brief、Notify-once 完成通知债权与防注入规范。
- [OmniBot](https://github.com/omnimind-ai/OmniBot)：Android AI Agent 方向的参考项目。
- [opencode](https://github.com/sst/opencode)：终端 AI Coding Agent。远程 OpenCode 接入的协议参考，第二版协议 wire 格式核证自其 serve 实现（v2.2.5）。
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) / [sherpa-ncnn](https://github.com/k2-fsa/sherpa-ncnn)：离线语音运行时。两个项目的 Kotlin JNI 绑定原样引入并随包分发（Apache-2.0），支撑本地语音识别、合成与 VAD。
- [OpenMinis](https://github.com/OpenMinis/OpenMinis)：内置浏览器的改编来源。浏览器源码改编自该项目（GPL-3.0），并做包/资源隔离、Linux 访客路径映射、Cookie 卸载与用户接管等适配，详见 `third_party/openminis-browser/NOTICE.md`。
- [lzhhhhc/imagine](https://github.com/lzhhhhc/imagine)：图像生成协议参考。参考图/遮罩编辑、尺寸档位与 JSON 编辑协议的接口行为核证自其 ApiModels、ImageRepository 等实现。
- [st-chatu8（智绘姬）](https://github.com/damoshen123/st-chatu8)：SillyTavern 插件，NovelAI 工作台的设计思路借鉴来源；未复制源码，仅以其公开接口行为为兼容目标。
- [libxposed API](https://github.com/libxposed/api)：现代 Xposed API。
- [miuix](https://github.com/compose-miuix-ui/miuix)：页面切换转场专用库——NavDisplay 导航容器提供栈式转场动画与系统屏幕圆角适配；界面组件本体为 Material Design 3 Expressive，不使用 miuix 组件。
- [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)：聊天消息与悬浮内容的 Markdown 渲染库，提供 Compose 多平台渲染与 Material 3 样式适配。
- [Zafiro](https://github.com/niki914/zafiro)：用户对话气泡的样式参考。其气泡设计与 Google Material Design 3 Expressive 的示例气泡高度相似，因此采用并适配到本项目。
- [Material Design 3 Expressive](https://m3.material.io)：Google Material Design 3 Expressive 官方站点，本程序的界面设计语言与视觉风格均参考自其中的示例与规范。
- [KernelSU](https://github.com/tiann/KernelSU)：本应用“外观与主题”子设置页面的设计参考。
- vd-preview-gateway：开发期取证的本地预览网关（MIT，版权归 AcidGr，全文见 `tools/vd-preview-gateway/LICENSE`），不随 APK 分发。
- 此外，感谢所有在手机上本地部署运行的开源模型——包括语音合成与识别模型、Embedding 模型等——它们让 EtaExpressive 能够在不依赖云端的情况下完成语音交互、语义检索与本地推理。

## 社区
- 反馈问题：[GitHub Issues](https://github.com/alone06101/EtaExpressive/issues)
- 源码与发版：[alone06101/EtaExpressive](https://github.com/alone06101/EtaExpressive)

## 许可证
本项目与上游 Eta 一样采用 [GPL-3.0](LICENSE) 开源，详情请参阅 [LICENSE](LICENSE)。
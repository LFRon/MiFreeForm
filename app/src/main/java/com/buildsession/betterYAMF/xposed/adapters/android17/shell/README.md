# Android 17 Shell 归属隔离

源码基线：PixelOS `android_frameworks_base` seventeen `ebc25c0cf9c8`。相关 Task/Organizer/Shell 路径与已核对的 LineageOS 24.0 同源；具体构建仍需原生签名检查与设备验收。这里只管理 Android 原生机制，不承载小窗模式、尺寸或运动策略。

## 进程与启动

- `HookShell` 仅处理 SDK 37、`com.android.systemui` 主进程，沿用 ROM 排除规则；在 `application.impl.SystemUIApplicationImpl.onCreate` 前安装接收器，固定 Hook 在包加载时预先安装。
- SystemUI 的 `android.uid.systemui` 与 system_server 的 `android.uid.system` 不是同一身份。安装入口核对当前用户、进程 UID 与 ApplicationInfo；系统端通过 PM 取得投递用户下的 SystemUI UID，并严格核验真实广播 UID、包名和权限。不得把 SystemUI UID 写死成 1000 或设备样本中的 10188。系统端发出的 OWNER 消息仍须是实际 UID 1000 / android。
- 必须启用 SystemUI 作用域。新协议仅在系统用户运行，显式指定包名、接收权限、投递用户，并通过 BroadcastOptions 分享和检查真实发送 UID/包名。它与 Launcher v2 对端表分开。
- system_server 发布自己的 Binder cookie；SystemUI 的 QUERY / READY 均携带协议和构建号，缓存 cookie 后返回 cookie 和进程存活 Binder。系统端验证并监听死亡，确认后才创建根。缺少确认时不会假报后端能力，也不会阻塞主线程等对端回复。握手身份、构建和 cookie 拒绝原因按变化输出 Warning，普通成功诊断仅在 Debug 输出。
- cookie 标识本次 system_server 拥有者。根创建前即已声明，根通过 `TaskCreationParams.Builder.setLaunchCookie` 携带它；子任务在 `Task.fillTaskInfo(TaskInfo,boolean,TaskDisplayArea)` 完成时按当前归属添加标记。认领先备好隐藏/几何记录，再发布 pending 归属，覆盖 WCT 配置早于 reparent 的时段。归属退出后不再添加，不使用可复用 taskId 的粘滞缓存。

## 选择性处理

`Android17ShellBindings` 在注册前缓存完整签名。对 `FreeformTaskTransitionObserver.onTransitionReady` 和独立的 `DesktopTasksTransitionObserver` 提供本次调用专用的 TransitionInfo 视图：去掉已标记任务及其容器子项，保留其他变化、顺序、根、flags、track、debug id。原始共享对象不修改；Change、Root、事务和 leash 都借用，适配器不释放它们。转场起始、合并、结束和全局完成回调继续原生执行。

Freeform 观察器把同一份视图交给 DesktopInOrder 和 FreeformFallback，因此不依赖这些单调用 helper 在 R8 后仍存在。桌面请求拦截选择实际注册的 `desktoptaskshandlers.DesktopTasksTransitionHandler.handleRequest`，也不依赖 Controller helper 未被内联。Freeform / DesktopMixed 的动画入口同样排除米窗变化；其他任务正常执行。

Caption / Desktop 两种 WindowDecorViewModel 的公开生命周期方法阻止为米窗任务创建或重新布局原生装饰，并走真实 destroy 方法清理旧标题栏、输入和表面。对外部任务及归属已经返回的任务照常处理。

系统端 `ShellTaskTransactionGuard` 在 WM 全局锁内、原生四参数 `WindowOrganizerController.applyTransaction` 应用前复核当前任务归属。它只处理经协商 Shell 进程捕获的 CallerInfo，按调用对象记录其身份，因此延迟执行不依赖届时的 Binder calling PID，也不会永远信任某个可复用 PID。阻止对受管任务的尺寸、密度、重排和向 freeform desk 重挂载，保留其他操作和原始函数/完成链。显式 PiP 请求、应用任务移除以及向非 freeform 父级的真实全屏/分屏交还继续原生执行，由后续实际控制权丢失终结米窗宿主；内部承载根仍受保护。移除配置中属于服务端传输副本的 bounds transaction 会直接关闭，不施加画面。

层级操作并非都携带 WindowContainer token。与此源码版本的 `WindowOrganizerController.sanitizeTransaction` 一致，`FINISH_ACTIVITY`（14）和 `ADD_TASK_FRAGMENT_OPERATION`（17）继续交给原生 ActivityRecord / TaskFragment 校验，不传入任务归属解析；`WindowContainer.fromBinder` 内部直接强转 `RemoteToken`，不能拿它解析任意 Binder。普通配置、任务重排和 reparent 仍按当前归属隔离。

Shell 对端以同一份存活记录中的真实 UID + PID 匹配 CallerInfo，不能只比较 PID 或假设 UID 1000。负向能力通知只撤销对应对端；死亡回调比较本次注册对象，旧回调不能清空替换后的新注册。能力撤销后，仍存活且已认证的旧 Shell 继续受任务写入隔离，以保护已有米窗。

## organizer 注册也要隔离

`registerTaskOrganizer` 即使已被 Task 路由钩子改写归属，仍会枚举所有 organized 任务并调用 `TaskOrganizerState.addTaskWithoutCallback`。只拦 `Task.setTaskOrganizer` 会污染内部任务清单和 `mTaskAppearedSent`；Shell 重建时也有相反方向的问题。

米窗保留原生注册流程，但错属状态只准备快照副本，不写其任务清单；返回列表按快照中的归属标记分流。重定向给米窗的任务不能继承 Shell 注册的 skipTaskAppeared=true；原生显式清空 organizer 仍正常执行，不能阻止任务/根的合法移除。每次注册独立管理快照副本：米窗本地结果、被过滤结果和失败途中副本由模块释放，正常返回其他 organizer 的副本交给原生调用方。注册范围使用嵌套 ThreadLocal，源码 `TransitionController.Lock.runWhenIdle` 在调用线程运行注册动作。

## 能力不可用时的诊断

初始化异常及隔离失效的原始异常保存在进程内；根任务记录扫描、创建、持有 leash、验证 organizer、配置层级等阶段。Launcher 查询能力且两项入口能力均不可用时，由 Android 内部后端端口输出当前事实和保存的异常，最多每 5 秒一次 Warning。只读缓存，不在输入路径重试注册、创建任务或等待 IPC。成功初始化后清除旧初始化异常；隔离失效仍保持原有关闭入口的行为。

Shell 负向 READY 可以携带最多 2,048 字符的可选故障摘要，系统端先按原协议验证身份、构建、cookie 和对端归属，再缓存诊断。该字段不参与能力授权，也不改变协议版本或公共 JVM 接口。缺少启动日志时，查询期 Warning 可以区分未确认、初始化失败、句柄失效与隔离撤销；不能仅凭两项能力为 false 推断某个 Hook 没有执行。

## 验证边界

身份回归测试覆盖 PM 分配的 UID、主进程和系统用户、真实发送 UID / 包名缺失或不符、UID 1000 冒充 SystemUI，以及 UID / PID 复用。设备检查另行核对实际 SystemUI 的包 UID、进程名和握手所需权限。

JVM 测试覆盖入口的短上滑、暂未就绪、UP 更新、取消与任务变化，以及容器过滤、快照副本移交和失败清理。`ShellNativeBindingTest` 另行提供真实 SystemUI/服务签名、混合转场视图和混合 WCT 的设备检查；测试只构造值，不安装 Hook、不操作真实任务或重启进程。

真实验收仍需检查首次进入、原生装饰清理、桌面恢复开关两种状态、混合转场、已有原生窗口与米窗并存、反复 mini/全屏、取消，以及 SystemUI/Launcher 正常重建。签名存在和 JVM 测试成功不构成这些时序保证。

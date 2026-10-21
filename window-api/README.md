# 米窗事件与执行端口

适配新 Launcher、Android 任务后端或 Hook 框架时，以本模块的契约为边界。这里的类型只依赖 Kotlin/JVM，不持有 `View`、任务 token、leash、事务、缓冲区、反射对象或 Hook 参数。

## 模块依赖

```text
app（Android / Launcher / 任务 / 绘制 / Hook 适配器）
  └─ implementation(window-runtime)
       ├─ api(window-api)
       └─ implementation(window-core)
                              └─ implementation(window-api)
```

`window-runtime` 提供 `WindowRuntimes.createWindow()` 与 `LauncherEntries.create()`，返回本模块定义的句柄。`app` 的编译依赖不包含 CORE；适配器不能直接创建控制器、调用策略或解释核心内部命令。CORE 的状态和运动求解可以调整，而无需让所有 Hook 跟着修改。

EVL 和 EXL 在运行时内形成闭环：EVL 把端口结果和外部输入串行入核，EXL 执行核心效果并关联结果。两者没有各自的产品状态机。原有弹簧、mini 比例、全屏预览与内容替换轨迹仍由 CORE 统一计算。

## 接入位置

| 契约 | 适配器需要提供的事实或执行能力 |
|---|---|
| `LauncherEntryHandle.onInput(EntryInput)` | 归一后的输入阶段、当前显示坐标、DOWN 坐标下的上滑位移，以及可选的原生导航起点事实 |
| `LauncherEntryPort` | 当前候选任务与原生跟踪阶段、投放区显示、实际后端能力、源交接的本地预备、原生结束后的降级入口 |
| `WindowRuntimeHandle.post(WindowEvent)` | 用户输入、保留画面可用、任务表面可用、控制权丢失、源控制器释放、尺寸/可见性/颜色等事实 |
| `TaskSessionPort` | 任务认领、固定逻辑视口准备、全屏交还、置前 |
| `ScenePresenter` | 完整场景帧和输入区域的同步落实、帧率请求、投放区绘制、保留像素释放 |
| `SourceHandoffPort` | 目标桥接帧结果通知，以及源释放后的真实目标布局就绪观察 |
| `WindowLifecyclePort` | 控制权释放通知、失败诊断、最终原生资源清理 |
| `RuntimeScheduler` | 串行宿主、取消任务和外部单调帧时钟 |

当前 Android 组合根在 `AppWindow`；Launcher 入口适配在 `LauncherGestureAdapter`；任务和帧机制分别在 `SmoothWindowBackend`、`SurfaceFrameSynchronizer`。注册、原始调用和 Hook 参数转换集中在 `NativeHooks`。这些 Android 内部接口可传原生对象，但不能替代或污染上述公共契约。

## 坐标、时间与布局

- 场景和投放坐标使用 `SceneMetrics.displayId` 所指显示器在 `rotation`（0～3）下的像素坐标。原点在当前显示区域左上角，包含系统栏区域；安全区域由上下 inset 表达。输入适配器负责从 Launcher 或原生局部坐标转换。
- `EntryInput.gestureY` 保持在 DOWN 时的坐标系，用于位移判定；`x/y` 则跟随当前显示方向，用于命中投放区。跨显示器的输入会取消当前候选。
- 输入时间、帧时间使用同一单调 uptime 毫秒基准。速度统一为像素/秒；例如 Quickstep 的像素/毫秒必须先转换。
- 几何运动保持浮点精度；在 Android 事务和输入区域边界使用同一个 `SceneRect.rounded()` 规则。
- `TaskLayout.normalContent` 是显示区域；`logicalViewport` 是应用逻辑视口。CORE 在认领前选择尺寸；EXL 保留既有任务实际使用的固定视口。每帧只缩放、裁剪和呈现，不每帧重排应用，不修改应用 DPI。
- `WindowSceneFrame` 是完整、不可变的当前期望场景；`input` 已包含命中区域和内容穿透区域。适配器不得重新推导 mini、全屏或输入开放条件。`snapshot()` 返回期望帧，不是物理显示查询。

## 侧边图标收起

左右侧边投放沿用现有投放区域，不依赖甩动速度；四角仍为显示应用内容的 mini。侧边收起、图标位置、内容与外壳到图标的过渡、恢复目标和触摸许可都由 CORE 决定。`SideStashPolicy` 使用屏幕安全区域保存纵向锚点，旋转后仍留在原侧边。

`WindowSceneFrame.sideStash` 给出同一任务的图标矩形、圆角和透明度；`shellAlpha` 控制外壳，`taskAlpha` 控制内容。AD 仅绘制当前帧，不另启 View 动画、计时器或根据速度判断收起。`taskVisible=false` 要在场景同步事务中隐藏整个内容子树及其原生触摸目标，保留认领与固定逻辑视口；不能把它转换成 WM 任务可见性变化、关闭、全屏或重启应用。

`taskFocusable=false` 暂时禁止该任务获焦；恢复 `true` 只撤销同一原生认领的焦点抑制，不强行把任务置前。适配器应使用原生生命周期/输入刷新路径，不能仅写字段，也不能按每帧重新执行焦点事务。控制权丢失时，按原生任务身份和当前覆盖值清理本会话的抑制。

收起后 `input` 只覆盖屏内露出的图标，使用 `STASH_HANDLE`。输入适配器转发 Press/Move/Up/Cancel；CORE 判定点按并恢复，无需额外的点击确认延迟，也不把图标拖动/甩动作用到隐藏任务。动画中的点按从当前姿态和速度续接；取消点按不停止收起动画。恢复终态的同步提交确认前保留触摸屏障与焦点抑制，迟到、合并和隐藏帧的确认不能提前开放输入。

## 会话和异步约束

1. 进程为每个窗口分配 `SessionKey(epoch, windowId)`。活动窗口 ID 在该进程中唯一，进程重新建立时更换 epoch。原生任务 ID 不能充当会话身份。
2. 进程内的窗口共用一个串行宿主。创建、`bindTask()`、`activateTask()`、`normalLayout()` 在该宿主调用；`post()`、`close()` 可从其他线程调用。帧和端口执行都在宿主进行。
3. `bindTask()` 只接受正任务 ID，在第一次原生认领和回调前完成，之后不能换成另一个任务。组件启动先建立未绑定会话，等真实任务出现后再绑定；缺省或非法 ID 不能当作已有任务。进入关闭阶段的会话不能重新绑定或激活。同一任务的新窗口会先终结旧窗口；旧资源回调不得通过任务 ID 查找并操作新窗口。
4. 端口完成可以同步发生，也可以来自其他线程。运行时排队后再推进核心，不在同步认领调用栈内准备应用。帧、准备、时钟、目标就绪等待和全屏交还都核对所属操作；重复及过期结果不能推进新操作。
   布局和投放区几何使用 CORE 已处理的屏幕指标，不另设可提前影响执行的尺寸缓存。事件泵末尾的帧率提示若同步产生新事实，也必须在本轮处理完毕，不能等下一次外部事件唤醒。
5. 每次异步命令/帧提交都必须最终返回一次 `DONE`、`FAILED` 或 `SUPERSEDED`。合并帧要明确回报 `SUPERSEDED`；核心会按需要重新发布交接屏障。取消句柄须幂等，并只清理自身资源。逻辑取消不能撤销已经提交的原生事务。
6. 窗口终结后，运行时丢弃晚到事件并取消自己的等待。平台仍负责清理晚到回调持有的原生资源。`WindowLifecyclePort.finish()` 恰好调用一次，必须清理该会话的输入浮层、监听器、保留像素等；不能因任务已交还而跳过浮层清理。
7. 一个取消操作或源通知抛错，不得阻断其余清理。运行时会继续终结宿主并向生命周期端口报告异常；适配器也要在自己的资源清理内部保留这一保证。
8. `TaskOwnershipLost` 表示该会话已失去任务控制权，包含任务移除或外部拥有者接走。运行时先停止旧操作的执行，再由 CORE 终结场景，最终清理宿主而不再次释放任务。AD 须在取消旧准备操作前交付此事实；任务 `onTaskVanished` 不能直接当成任务被删除或需要再次全屏化。仍属本会话的布局覆盖由 AD 核对原生任务身份与当前值后清理，不覆盖新拥有者的布局。
   AD 在通知到达串行宿主前也须记录原生控制权已失去，避免先到的旧失败回调进入释放兜底。旧原生回调跨过新认领时，应重新核对当前拥有者；原生布局清理不能依赖旧宿主仍存活。

## 入口交接顺序

`EntryInput.navigationStart` 仅用于 DOWN：`true` 表示输入拥有者已确认本次导航起点，`false` 明确拒绝，`null` 保留现有屏幕底部 5% 的几何判定。适配器可以先保存原始坐标，再在原生 DOWN 完成后报告事实；不得用上一手势残留的 consumer 或任务 ID 推断本次已接管。这个事实不代表任务跟踪、源画面或后端就绪，CORE 继续分别检查这些条件。原生对象身份和组件代次留在 AD，不通过此字段进入核心。

`EntryCandidate.tracking` 报告输入拥有者事实：`WAITING` 可以在本次手势后续输入中重试；`TRACKING` 表示原生已经越过自己的触摸阈值并开始跟踪该应用手势；Home、拥有者更换或 `REJECTED` 终结本次资格。`null` 也仅表示暂不可用。适配器按当前 MOVE / UP 读取本地事实，不能同步等待 Binder。CORE 不再额外要求上滑超过屏高 20%，也不依赖 Launcher Activity 恰好 resumed。

跟踪成立不代表源像素、原生控制器或目标任务已准备好。提示还要求至少一种真实执行能力；UP 会用最后坐标和当前候选重新核查，允许此前尚未就绪，也会拒绝失去的拥有者、变化的任务或撤回的能力。`retainedSource` 与 `legacyAfterNative` 独立声明，后者不能固定为 true。坐标应在原生修改输入对象前采样；输入事实可以在 MOVE 原生识别完成后读取，UP 交接预备仍须在原始结束决策前完成。

普通窗口：创建句柄 → 绑定任务 → `activateTask()` → 当前表面 `TaskAvailable` → 准备结果 → 场景提交。

保留源画面的入口：

```text
Launcher 原始 DOWN / MOVE
  → CORE 判定投放，UP 前本地预备源交接
  → 原始 UP 完整执行，更新最后位移并清理输入跟踪器
  → 保留已同步的源画面，发送 EntryRequest
  → VisualAvailable，CORE 生成源桥接帧
  → 目标桥接帧真实产出缓冲区并同步提交，acknowledge(DONE)
  → 捕获的原生控制器真正结束，源 UI 退场，SourceReleased(handoffId)
  → awaitDestination 报告真实旋转/过渡就绪指标
  → CORE 选择目标尺寸，运行时认领和准备真实任务
  → CORE 驱动飞行和源/实时内容替换
  → 无保留画面的承接帧提交，releaseEntryImage()
```

`beginRetained()` 只能预备本地交接，不能吞掉原始 UP，也不能同步跨进程等待。可以暂缓原生的最终去向决策，但不能跳过 `OtherActivityInputConsumer` 的最后位移更新和输入清理。原生拥有者释放前，外部调用 `activateTask()` 也不能绕过 CORE 的布局/交接屏障。

`EntryRequest` 在任务认领前仅发送一次。保留画面入口必须提供有效源矩形并启用 `awaitSourceRelease`；`VisualAvailable` 仅在像素安装成功后发出。`SourceReleased` 只有在桥接 ACK 后、捕获的原生控制器和源 UI 真正退场时才能发送。后续取消/释放事件必须匹配 `handoffId`。

`awaitDestination()` 返回 `DestinationResult.Done(metrics)`、`Failed` 或 `Superseded` 中恰好一个结果。观察失败必须显式回报；异步帧回调中的异常不能逃逸到平台线程。取消终止原生观察，并回报 `Superseded`。失败与取消由 CORE 决定终结等待；晚到的成功结果不能再认领任务。

Launcher 候选查询和交接预备期间也允许同步取消。取消后的查询结果不得重新显示投放区或发起降级入口；已成功预备的原生门闩仍要收到原始 UP 完成回调，由其原生拥有者清理。

源交接通知中的 `DONE` 只确认目标桥接帧，尚未确认源释放或任务认领。源释放前的取消、故障仍可终结该交接。超时是故障收敛机制，不是正常动画延时。

能力不足时，CORE 只选择端口明确声明的降级能力。当前降级机制在原始 UP 清理之后发送入口，并观察原生过渡稳定；不能把“类名能找到”当作保留源画面交接已经可用。

v2 能力响应以可选 `entryLegacyAfterNative` 字段声明后端确实支持原生结束后的认领；缺省视为未声明。后端资源和 Shell 协商状态变化会更新能力。该扩展不改 action、协议号或像素 Parcel 布局，Shell 自身的拥有者协商仍留在 Android 适配器中。

## 完成结果的含义

| 操作/事实 | 保证 |
|---|---|
| `adopt()` 返回 true | 已接收任务认领；不等于应用缓冲区已经准备好 |
| `TaskAvailable` | 当前认领任务的有效表面可用；不等于视口准备完成 |
| `awaitDestination(... Done)` | 源已释放后的真实目标旋转与原生过渡稳定，携带最终候选屏幕指标；失败/取消有独立终结结果 |
| `prepare(... DONE)` | 选定视口及其真实任务表面已具备呈现条件 |
| `present(... DONE)` | 外壳缓冲区已产出，且对应内容事务与之同步提交；不承诺物理扫描输出 |
| 全屏 `released()` | 当前会话实际失去任务控制权，旧重施加必须停止 |
| 全屏 `DONE` | 新全屏布局的绘制事务已提交，原生运动已结束且任务已交还 |
| `releaseEntryImage()` | CORE 已确认可见承接帧提交，允许释放该会话的保留像素 |

后端不能用函数返回、引用为空、透明度阈值或固定等待代替这些保证。全屏失败通过 `FullscreenHandoffResult` 区分仍持有、已释放和控制权未知的情况。

## 兼容和验证

公共端口保持稳定的语义和源码接入边界，CORE 内部事件/效果不作为适配 API 发布。新增事实、改变完成条件或修改签名时，必须同时更新本文、对应端口注释、所有现有适配器及契约测试。此工程尚未发布独立二进制 SDK，不承诺未经版本管理的二进制兼容。

跨进程广播继续使用现有 v2 action、协议号和字段值；源/目标进程 epoch、查询 ID 和异步快照能力是可选扩展。保留画面的入口只在双方协商异步像素传输后启用，否则使用已声明的原生结束后入口。旧 READ 的 Parcel 布局保留，新系统接收端不调用同步 READ，也不通过关闭 Binder 警告规避阻塞。请求和像素回调均为 oneway，缓冲区不进入广播 extras。

窗口建立前的像素请求也须关联来源、用户、进程、任务和交接 ID，处理取消先于 OPEN、重复请求、进程死亡及超时。取消后的迟到像素只释放资源，不能创建窗口。当前 Launcher 的交接 ID 由单调纳秒时钟分配；平台传输据此按来源/用户/进程/任务维护去重水位，不将此机制传入 CORE。

接收地址使用实际登记的 Launcher 包名、所属用户和进程 epoch。系统接收器保留发送权限校验并读取真实投递用户，回复显式指定 UserHandle；Launcher 所属用户与应用任务所属用户不得混用。这些地址与 Binder 身份属于 AD，不进入公共核心端口。

运行 `:window-core:test :window-runtime:test :app:testDebugUnitTest :app:testReleaseUnitTest` 验证公共策略、同步/异步端口、取消、过期结果、旋转、内容交接与架构依赖。源码边界测试禁止 Android、反射和系统时钟进入 JVM 模块，禁止 `app` 引用 CORE，禁止 Hook 回调类型逃出框架桥接。

原生机制仍需根据目标源码和设备验证。当前按 Android 大版本 / Launcher 的独立适配入口保留 LineageOS 24.0 的 Quickstep / organizer / HWUI 基线；PixelOS 17 / Pixel Launcher 17、907 为源码核对候选，尚未完成真机验证。Android 15/16 目标入口处于待适配状态，不自动借用 Android 17 后端。具体匹配、原生机制和 ROM 排除规则见 `app/src/main/java/com/buildsession/betterYAMF/xposed/adapters/README.md`，这些信息不得进入本模块或 CORE。

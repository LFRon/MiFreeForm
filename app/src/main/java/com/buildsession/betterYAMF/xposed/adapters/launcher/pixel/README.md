# Pixel Launcher 接入核对（轴 2）

此目录是三条适配轴中的 **Launcher 实现**轴：`com.google.android.apps.nexuslauncher` 的 Quickstep 机制，当前入口是 `pixel/17`（待适配：`pixel/16`）。主进程、SDK、APK 版本先匹配；安装前解析整组原生成员。公共入口判定、窗口几何、弹簧和交接策略继续由现有 API / CORE / runtime 承担。

这一轴与发行版名字无关：Pixel Launcher 可以出现在 LineageOS 上，PixelOS 的 manifest 也同时构建 AOSP `platform/packages/apps/Launcher3` 并携带 `vendor/pixel/launcher` 预置。实际使用哪一份由运行时默认 Home 与最近任务宿主决定；框架侧形状见 `adapters/baseline`，发行版审阅记录见 `adapters/rom/pixelos`。

## 证据范围

- PixelOS `android_manifest` seventeen：`c16cfd0c95c5`。
- PixelOS `android_frameworks_base` seventeen：`ebc25c0cf9c8`。
- `proprietary_vendor_pixel_launcher` seventeen：`67ed56a43d89`，`NexusLauncherRelease.apk` 手机构建，版本名 17 / 版本号 907 / 目标 SDK 37。
- 审查 APK 的 SHA-256：`3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56`。

源码仓库位于工作区的 `域外源码库`。APK、DeJared MCP 的完整导出、全 DEX 反汇编与固定源码证据保存在 `decompile-folder/PixelOS-17-DeJared`；JADX 的五种完整导出、补充重试、逐类交叉索引及核验说明保存在 `decompile-folder/PixelOS-17-JADX`，不将厂商 APK 打包进工程。签名核对使用原 DEX；例如 JADX 显示的 `mo67getCreatedContainer()` 在 DEX 中仍叫 `getCreatedContainer()`。手机/平板 APK 的 1,580 个解压条目一致，共含 15,495 类、56,545 方法，平板副本按清单去重。反编译输出不能等同于原始源码：JADX 的 `onInputEvent` 容器判空条件曾被错误反编译，控制流以 Smali 与其他引擎交叉核验。

`SOURCE_REVIEWED` 表示源码 / DEX 核对状态，不表示已经完成 PixelOS 真机动画验收。只检查版本号不能证明后续 APK、平板构建或其他 ROM 具有相同行为。

## 原生事实与接入链路

| 环节 | 已核对的实际机制 | 米窗接入 |
|---|---|---|
| 组件与输入 | Quickstep 服务在 Launcher 主进程；服务的 `handler` 来自当前 SysUI 连接组件；`TouchInteractionHandler.onInputEvent(InputEvent)` 按显示器获取 `InputResource` | 只接收当前服务 handler 的回调；本机投放区只接收对应默认显示器的输入，其他显示器不会重置它；归一后调用 `LauncherEntryHandle` |
| 导航起点 | 原生按旋转后的 swipe-up region 判断 DOWN；接受后创建新 `GestureState`，传给当前 `OtherActivityInputConsumer.mGestureState` | 在原生 DOWN 前保存坐标和旧对象，完成后核对 handler、新状态与 consumer 的对象身份；通过 `navigationStart` 报告事实，不再让固定底部 5% 代替 Pixel 导航区域 |
| 当前任务与控制器 | `gestureState` / `consumer`、显示器的 `TaskAnimationManager.mController`、当前活跃 `OtherActivityInputConsumer.mInteractionHandler` | 反射成员初始化时缓存，真实对象每次手势重新读取；不跨手势缓存控制器 |
| 原生跟踪资格 | 原生越过触摸阈值后调用 `AbsSwipeUpHandler.onGestureStarted(boolean)` 并设置 `mGestureStarted`；失效回调会清空 consumer 的 handler | MOVE 在原生识别后读取阶段，坐标在原生修改事件前采样；候选暂未就绪可重试，拥有者变化拒绝；不要求 Launcher resumed 或再上滑屏高 20% |
| 原始 UP | `finishTouchTracking()` 先 `updateDisplacement()`，随后调用 `onGestureEnded`，最后清理速度与输入跟踪。PixelOS/LoS 为 `(float, PointF, boolean)`；Google 系统镜像额外传一个 `PointF`，即 `(float, PointF, boolean, PointF)`。私有的 `handleNormalGestureEnd` 在 Google 构建中为 6 参数，在 LoS 中为 5 参数 | `QuickstepNativeAccess` 同时解析并 Hook 两个 public 重载，以实际调用 `finishTouchTracking` 的重载为准；`handleNormalGestureEnd` 作为内联兜底也按 5/6 参数解析。原始输入方法完整执行，只暂缓捕获处理器的最终决策；复制 px/ms 速度后归一为 px/s |
| 源画面 | `SwipeUpAnimationLogic.mRemoteTargetHandles` → 当前任务的 simulator、params、leash | `QuickstepSourceAccess` 只读取事实，不决定 mini 或动画目标 |
| 源提交 | `TaskViewSimulator.apply()` 调用 `TransformParams.applySurfaceParams(SurfaceTransaction)`；后者可能延迟到 RT 帧提交 | `RecentsSourceFrame` 观察原生事务的 committed listener；不主动提交或释放借用的事务 |
| 位置与像素 | `getCurrentRect()` 是显示空间矩形；`getCurrentCropRect()` 是缓冲区裁剪；`getScaledCornerRadius()` 为视觉圆角 | 提交确认后捕获任务内容，交给现有 v2 / 单向 Binder 像素传输 |
| 原生结束 | `finishController(toLauncher, callback, sendUserLeaveHint, reason)`，强制结束另有 boolean 参数 | 捕获当前控制器的 normal / force API；使用 `true / false`，等待原生 result receiver 回调，超时仅作故障收敛 |
| 源 UI 退场 | 只有 `createAnimationToNewWorkspace(BaseState, long, int)`；默认 flags 为 0；`dispatchOnStart()` 返回 void | 本目录显式选择三参数 ABI，保留正 duration 归一、原生开始监听和 `reapplyState(true)` 的最终清理 |
| 受管任务过滤 | `onVisibleTasksChanged()` 在本 APK 中为空；`getCachedTopTask(boolean,int)` 构造真实候选缓存；`GestureState.getRunningTaskIds(true)` 直接读取前两个缓存任务；`TopTaskTracker.handleTaskMovedToFront` 的谓词是 `getWindowingMode() != 5` | `QuickstepTaskFilterAccess` 过滤缓存中所有受管任务，保留 context、display、desk 和原始 TaskInfo；不能只过滤空回调或栈顶。剔除条件还要求任务仍为 freeform/multi-window：广播集合是异步的，下拉全屏的释放要几帧才生效，仅凭集合会在交接瞬间把真正的前台任务剔掉，只剩 home 候选，动画以 HOME 结束（表现为上滑无反应）。过滤结果为空时保留原生候选，受管集合变化时清空 `mOrderedTaskList` |

源变换提交、目标桥接帧提交、原生完成回调、源 UI 退场、真实任务准备仍使用已有的独立确认条件。平台适配没有增加自有弹簧、位移轨迹、mini 判定或固定交接延时。

Pixel 组合显式启用原生 DOWN 确认；LineageOS 24.0 组合继续使用既有 DOWN 时序与缺省几何契约。MOVE / UP 重新核对本次状态与活跃 consumer；忽略的 DOWN、consumer 替换或旧组件晚到回调不能复用上一手势资格。组件销毁或绑定异常会关闭当前输入租约、清理交接并隐藏投放区，异常清理也须核对手势及组件代次。原始输入始终执行，不接管不属于本次手势的资源。

再次核对原始调用链（2026-10-21）：`InputResource` 为当前 handler 注册输入 listener，listener 调用合成桥接方法，再进入私有 `TouchInteractionHandler.onInputEvent(InputEvent)`；本适配 Hook 最终输入方法，不依赖合成类名。`newConsumer → newBaseConsumer → createOtherActivityInputConsumer` 沿用本次新建的手势对象；consumer 越过原生阈值后调用 `onGestureStarted`，设置 `mGestureStarted=true`。全 APK 的 15,495 类中，三个实际 swipe handler 子类均未重写 `onGestureStarted(boolean)` 或 `onGestureEnded(float,PointF,boolean)`，最终决策不会绕过基类 Hook。42 个完整成员签名及 24 处关键调用／赋值核验记录保存在工作区 `域外源码库/diagnostics/pixel17-hook-audit-20261021`；这些静态检查不能替代运行时 Hook 和设备验证。

任务集合同步接收器的生命周期属于进程，使用 Application context 注册；服务销毁时释放该服务的手势与覆盖层。避免 `ActivityThread` 清理 Service 接收器后，进程中的“已注册”标记仍为 true，导致服务重建后收不到任务集合。

## 系统侧与权限

核对的 `TaskOrganizerController`、`Task`、`WindowContainer`、`WindowState`、`Transition`、`ActivityRecordInputSink`、`TaskCreationParams`、`TaskOrganizer`、`SurfaceControl`、`MotionEvent`、`ScreenCaptureInternal` 在当前 PixelOS 与 LineageOS 基线中 blob 相同，因此显式复用 Android 17 系统适配。

`ActivityTaskManager.supportsMultiWindow()` 的差异在于 LineageOS 的低内存强制多窗选项。这里不把它当成 PixelOS 已有能力，也不为 PixelOS 注入对应设置或修改系统权限。

APK 请求 `START_TASKS_FROM_RECENTS`、`INTERNAL_SYSTEM_WINDOW`、`SYSTEM_APPLICATION_OVERLAY`、`READ_FRAME_BUFFER`。PixelOS 的 recents 组件覆盖与框架 `signature|recents` 等授权对应；`PhoneWindowManager.checkAddPermission()` 对系统应用覆盖层有专用权限分支。跨进程广播仍验证特权发送者，Binder 像素回调仍核对协商的 UID。

本适配不改 SELinux、系统属性、权限授予、AppOps 或默认 Launcher。AVIUM / Project Infinity X 等自带魔改小窗的 ROM 不在支持范围。

PixelOS 的产品配置已声明 freeform feature；开发者设置不是本修复的开关前提。米窗复用 Android 17 的 `SmoothWindowBackend` / `SmoothTaskOrganizer`，不会调用 Pixel Launcher 的原生 freeform/desktop 启动接口。SystemUI 的装饰和孤立 freeform 恢复由独立 [Shell 适配](../shell/README.md) 排除米窗任务；保留 Android mode 5 等基础能力。

系统后端通过 AIDL getter 获取 TaskOrganizerController，按步骤维护注册、首帧 staging、有效根/leash、Shell 确认等事实。注册成功后根创建失败仍可补齐；重试回收或复用同 cookie 的根，已有步骤不会重复 Hook/注册。能力变化会推送给 Launcher，legacy 能力由实际后端提供，不再固定 true。

当前方案要求模块作用域同时包含 `android`、`com.android.systemui`、`com.google.android.apps.nexuslauncher`。SystemUI 与 system_server 中驻留的模块构建必须匹配；仅替换磁盘 APK 不等于已更新驻留代码。输入 Hook 成功也不代表后端已经可用：缺少 Shell 确认时会报告 `waiting-shell-ack`，两项入口能力均为 false，CORE 不显示投放区。先区分 `native binding failed`、覆盖层创建失败、原生导航未接管和后端能力未就绪，再定位对应环节，不能把所有“没有入口”都归因于手势 Hook 没有执行。

Launcher 类型按实际 SDK、APK 和当前用户的 Home / Recents 身份核对，不按 ROM 名称推断。LineageOS 24.0 加装 Pixel Launcher 时，只要默认 Home 和系统配置的最近任务宿主均为核对范围内的 Pixel APK，就使用本目录的输入适配；若仅默认 Home 为 Pixel、Quickstep 仍由 Launcher3 提供，则属于尚需适配的分离组合。服务初建及系统变更广播刷新这些事实，输入和动画只读缓存，没有轮询。切换监听与原生服务同生命周期，详见上级接入文档。

2026-10-21 的反馈 `2.txt` 已确认一条实际输入链路：13:30:33.848 的原生 DOWN 被接受，随后 task 18 从 WAITING 进入 TRACKING；两项后端能力始终为 false，因此 CORE 没有显示投放区。同一日志中米窗 Organizer 转发了 add/removeStartingWindow，说明它至少曾完成原生注册，不能将这次问题简单归为未注入或从未握手。日志保留的 main 缓冲区仅覆盖约 20 秒，缺少启动异常；具体根任务失败或后续隔离撤销原因尚不能从旧日志唯一确定。

本次补齐能力查询时的后端事实与缓存异常重报，并按原生事务类型修正非 WindowContainer token 的处理，显式开放注册快照 getter 的反射访问。源码确认的缺陷与本次复现的唯一根因应分开记录；重新编译通过也不代表 PixelOS 已恢复可用。工作区 `域外源码库/diagnostics/pixel17-log2-20261021` 保存本次日志证据及改动前基线。

## 可选入口

当前启用最近任务手势小窗入口。Pixel 的最近任务菜单和任务栏入口仍未启用：

- `RemoteActionShortcut` 已被 R8 删除所有构造器，图标实现还固定为原生 hourglass；不能照搬 Launcher3 的构造器或假定设置 RemoteAction 就能得到正确菜单。
- `TaskbarActivityContext.startItemInfoActivity(Task, ItemInfo)` 与 Launcher3 的参数顺序不同。

这些入口的缺失不会阻断手势接入；后续若适配，也应在此组合目录提供原生实现，不在 CORE 增加 Pixel 分支。

原生入口另作处理：`PixelNativeFreeformAccess` 在最终 `TaskOverlayFactory.getEnabledShortcuts(TaskView,TaskContainer)` 结果上，按 `DesktopShortcut` / `TaskShortcutFactory$FreeformSystemShortcut` 类型过滤冲突项。只在米窗后端已可用时生效，保留其他快捷项；这不等于安装米窗自己的菜单项。

## 可重复的结构与缓存验证

在 Android 17 设备，将上述 SHA-256 对应 APK 复制到米窗的 `code_cache/pixel17-review.apk` 并设为只读，然后运行：

```sh
adb shell am instrument --no-hidden-api-checks -w -r \
  -e class com.buildsession.betterYAMF.xposed.adapters.common.launcher.LauncherNativeBindingTest \
  -e pixel17Apk /data/user/0/com.buildsession.betterYAMF/code_cache/pixel17-review.apk \
  com.buildsession.betterYAMF.test/androidx.test.runner.AndroidJUnitRunner
```

测试使用生产适配器选择的退场 ABI，解析输入、源事务、normal / force 控制器、任务过滤所需成员，并用原 APK 的简单缓存构造器验证受管任务过滤。测试不安装或启动 Pixel Launcher，不注册 Xposed Hook，不操作真实任务。缺少外部 APK 参数时仅跳过该外部样本检查，不把跳过算成验证通过。

最终 PixelOS 验收仍需验证模块作用域与权限实际生效，以及重复进入 / 退出、横竖屏、mini 恢复、下拉全屏、手势打断和资源清理。

此前的结构验证基线（2026-10-20）：在 LineageOS 24.0 / SDK 37 设备，以原 Home APK 和上述 SHA-256 固定的 Pixel APK 运行旧版绑定、缓存与像素传输检查，9 项通过。该记录不覆盖当前新增的导航起点、输入归属、跟踪字段、菜单过滤和 Shell 机制。`LauncherNativeBindingTest` 的完整构造解析已包含新字段，另有 `ShellNativeBindingTest`；编译设备测试不能替代设备执行或 PixelOS 动画验收。

本轮验证（2026-10-21）：Debug / Release 及设备测试 APK 编译通过，551 次 JVM 测试执行通过，Lint 无 Error / Fatal，仍有项目 Warning。对原始 DEX 的 23 类、42 个完整成员签名重新核验，并确认 consumer 构造器保存同一个手势参数。新增普通诊断消息在 Release APK DEX 中消失，故障日志保留；本轮 ADB 无连接设备，新增设备测试尚未执行。

后续身份核对验证（2026-10-21）：新增默认 Home / Quickstep 选择用例后，565 次 JVM 测试通过，Debug / Release、设备测试 APK 和 Lint 检查通过（既有 Warning 保留）。在新连接的 SDK 37、Pixel Launcher 17/907 设备上，当前 Home / Recents 查询、已安装 Launcher 绑定和 7 项 Shell 结构 / 值测试共 9 项通过；未提供外部 APK 路径的固定样本测试跳过。Debug 已安装，未重启手机或系统组件；这些只读检查不代表驻留 Hook 已更新或完整小窗交互已验收。证据保存在工作区 `域外源码库/diagnostics/launcher-environment-20261021`。

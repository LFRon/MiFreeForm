# PixelOS 17 / Pixel Launcher 接入核对

此目录负责 Android 17（SDK 37）与 `com.google.android.apps.nexuslauncher` 的具体适配。主进程、SDK、APK 版本先匹配；安装前解析整组原生成员。公共入口判定、窗口几何、弹簧和交接策略继续由现有 API / CORE / runtime 承担。

## 证据范围

- PixelOS `android_manifest` seventeen：`c16cfd0c95c5`。
- PixelOS `android_frameworks_base` seventeen：`ebc25c0cf9c8`。
- `proprietary_vendor_pixel_launcher` seventeen：`67ed56a43d89`，`NexusLauncherRelease.apk` 手机构建，版本名 17 / 版本号 907 / 目标 SDK 37。
- 审查 APK 的 SHA-256：`3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56`。

源码、原 APK、jadx 输出和 DEX 索引位于工作区的 `域外源码库`，不将厂商 APK 打包进工程。签名核对使用原 DEX；例如 jadx 显示的 `mo67getCreatedContainer()` 在 DEX 中仍叫 `getCreatedContainer()`。

`SOURCE_REVIEWED` 表示源码 / DEX 核对状态，不表示已经完成 PixelOS 真机动画验收。只检查版本号不能证明后续 APK、平板构建或其他 ROM 具有相同行为。

## 原生事实与接入链路

| 环节 | 已核对的实际机制 | 米窗接入 |
|---|---|---|
| 组件与输入 | Quickstep 服务在 Launcher 主进程；`TouchInteractionHandler.onInputEvent(InputEvent)` 按显示器获取 `InputResource` | 组合入口安装共享 Quickstep 机制，`LauncherGestureAdapter` 归一后调用 `LauncherEntryHandle` |
| 当前任务与控制器 | `gestureState` / `consumer`、显示器的 `TaskAnimationManager.mController`、当前活跃 `OtherActivityInputConsumer.mInteractionHandler` | 反射成员初始化时缓存，真实对象每次手势重新读取；不跨手势缓存控制器 |
| 原始 UP | `finishTouchTracking()` 先 `updateDisplacement()`，随后调用 `onGestureEnded(float, PointF, boolean)`，最后清理速度与输入跟踪 | 原始输入方法完整执行，只暂缓捕获处理器的最终决策；复制 px/ms 速度后归一为 px/s |
| 源画面 | `SwipeUpAnimationLogic.mRemoteTargetHandles` → 当前任务的 simulator、params、leash | `QuickstepSourceAccess` 只读取事实，不决定 mini 或动画目标 |
| 源提交 | `TaskViewSimulator.apply()` 调用 `TransformParams.applySurfaceParams(SurfaceTransaction)`；后者可能延迟到 RT 帧提交 | `RecentsSourceFrame` 观察原生事务的 committed listener；不主动提交或释放借用的事务 |
| 位置与像素 | `getCurrentRect()` 是显示空间矩形；`getCurrentCropRect()` 是缓冲区裁剪；`getScaledCornerRadius()` 为视觉圆角 | 提交确认后捕获任务内容，交给现有 v2 / 单向 Binder 像素传输 |
| 原生结束 | `finishController(toLauncher, callback, sendUserLeaveHint, reason)`，强制结束另有 boolean 参数 | 捕获当前控制器的 normal / force API；使用 `true / false`，等待原生 result receiver 回调，超时仅作故障收敛 |
| 源 UI 退场 | 只有 `createAnimationToNewWorkspace(BaseState, long, int)`；默认 flags 为 0；`dispatchOnStart()` 返回 void | 本目录显式选择三参数 ABI，保留正 duration 归一、原生开始监听和 `reapplyState(true)` 的最终清理 |
| 受管任务过滤 | `onVisibleTasksChanged()` 在本 APK 中为空；`getCachedTopTask(boolean,int)` 构造真实候选缓存；`GestureState.getRunningTaskIds(true)` 直接读取前两个缓存任务 | `QuickstepTaskFilterAccess` 过滤缓存中所有受管任务，保留 context、display、desk 和原始 TaskInfo；不能只过滤空回调或栈顶 |

源变换提交、目标桥接帧提交、原生完成回调、源 UI 退场、真实任务准备仍使用已有的独立确认条件。平台适配没有增加自有弹簧、位移轨迹、mini 判定或固定交接延时。

任务集合同步接收器的生命周期属于进程，使用 Application context 注册；服务销毁时释放该服务的手势与覆盖层。避免 `ActivityThread` 清理 Service 接收器后，进程中的“已注册”标记仍为 true，导致服务重建后收不到任务集合。

## 系统侧与权限

核对的 `TaskOrganizerController`、`Task`、`WindowContainer`、`WindowState`、`Transition`、`ActivityRecordInputSink`、`TaskCreationParams`、`TaskOrganizer`、`SurfaceControl`、`MotionEvent`、`ScreenCaptureInternal` 在当前 PixelOS 与 LineageOS 基线中 blob 相同，因此显式复用 Android 17 系统适配。

`ActivityTaskManager.supportsMultiWindow()` 的差异在于 LineageOS 的低内存强制多窗选项。这里不把它当成 PixelOS 已有能力，也不为 PixelOS 注入对应设置或修改系统权限。

APK 请求 `START_TASKS_FROM_RECENTS`、`INTERNAL_SYSTEM_WINDOW`、`SYSTEM_APPLICATION_OVERLAY`、`READ_FRAME_BUFFER`。PixelOS 的 recents 组件覆盖与框架 `signature|recents` 等授权对应；`PhoneWindowManager.checkAddPermission()` 对系统应用覆盖层有专用权限分支。跨进程广播仍验证特权发送者，Binder 像素回调仍核对协商的 UID。

本适配不改 SELinux、系统属性、权限授予、AppOps 或默认 Launcher。AVIUM / Project Infinity X 等自带魔改小窗的 ROM 不在支持范围。

## 可选入口

当前启用最近任务手势小窗入口。Pixel 的最近任务菜单和任务栏入口仍未启用：

- `RemoteActionShortcut` 已被 R8 删除所有构造器，图标实现还固定为原生 hourglass；不能照搬 Launcher3 的构造器或假定设置 RemoteAction 就能得到正确菜单。
- `TaskbarActivityContext.startItemInfoActivity(Task, ItemInfo)` 与 Launcher3 的参数顺序不同。

这些入口的缺失不会阻断手势接入；后续若适配，也应在此组合目录提供原生实现，不在 CORE 增加 Pixel 分支。

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

本次验证记录（2026-10-20）：Debug / Release 编译成功，JVM 契约测试与架构检查通过，Lint 无错误。在当前 LineageOS 24.0 / SDK 37 设备上，以原 Home APK 和上述 SHA-256 固定的 Pixel APK 运行绑定、缓存与像素传输检查，9 项全部通过，无跳过。缓存检查覆盖受管任务位于首位、第二位、全部受管和无需过滤的情况，并验证其余 TaskInfo、context、display、desk 保留。没有把这项结构与数据验证记为 PixelOS 真机动画验收。

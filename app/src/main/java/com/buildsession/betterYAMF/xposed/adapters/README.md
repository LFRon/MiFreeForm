# Android / Launcher 适配入口

具体 Hook 是普通 Kotlin 实现，由 `HookSystem` / `HookLauncher` / `HookShell` 选择后执行。这里的选择信息只属于 AD，不传入 `window-api`、CORE 或运行时。

## 目录与接入

最小适配和验收单位是 **Android 大版本 + Launcher 实现**。组合目录中保留明确的安装入口；已经核对等价的原生机制可以调用 `common`，公共小窗策略和动画继续留在 CORE。

```text
adapters/
  android15/launcher3/       LineageOS 22.2 目标入口，待适配
  android16/launcher3/       LineageOS 23.2 目标入口，待适配
  android16/pixelLauncher/   PixelOS 16 目标入口，待适配
  android16/system/          保留的表面差异参数，不代表完整后端
  android17/launcher3/       现有 LineageOS 24.0 机制的安装入口
  android17/pixelLauncher/   PixelOS 17 / Pixel Launcher 17、907 的源码核对候选
  android17/system/          经核对的任务组织器与绘制同步实现选择
  android17/shell/           SystemUI / Shell 转场、装饰及任务归属隔离
  android17/notification/    LOS 24 通知点击、原生启动与按记录身份清理
  common/launcher/           Quickstep Hook 注册、输入归一、源画面与控制器交接
  common/organizer/          共享任务/表面机制
  common/system/             绘制、Android 事实、广播与像素传输
  common/freeform/           共享表面与输入变换
```

`AdapterCatalog` 用精确 SDK、实际加载的包名、主进程及已核对的 APK 版本范围选择唯一组合，再由安装器核对整组原生签名。包加载仅安装绑定；服务建立后还要核对当前用户的默认 Home 和系统配置的最近任务宿主，才能启用入口。`referenceRom` 记录源码 / 真机证据的 ROM 基线，不参与 Launcher 类型选择，也不是对任意 ROM 的兼容承诺。当前核对范围是 LineageOS 24.0 / PixelOS 17；其他组合即使签名相同也需要独立验收。每个组合的 `install()` 真正调用原生 Hook 安装代码，不存在描述语言、脚本解释器或自动生成 Hook。

`LauncherEnvironmentMonitor` 在 TouchInteractionService 建立时读取当前用户的首选 Home（`PackageManager.getHomeActivities`）、`android:string/config_recentsComponentName`、SDK、实际包名 / 进程及 APK 版本。前两项分别对应 Launcher 的 `OverviewComponentObserver` 和 SystemUI 的 `LauncherProxyService`，不能用 ROM 品牌、已安装的 Launcher 列表或 GApps 是否存在代替。Android 17 的 LineageOS 加装 Pixel Launcher，若 Home 和最近任务宿主都指向已核对的 Pixel APK，就选择 `android17/pixelLauncher`；不会因为 ROM 名字仍为 LineageOS 而使用 Launcher3 的 Hook。

监听采用系统事件：初始化读取一次，此后仅在首选桌面变更、用户解锁、相关包或 framework overlay 变更时刷新。没有轮询、定时器或后台常驻任务；输入和动画回调只读缓存，不查询 PackageManager。监听随原生服务销毁注销。切走后撤销旧入口并清理手势；切回已绑定组合后，从新的 DOWN 开始，进行中的手势不切换原生实现。可选菜单、任务栏及 Pixel 原生菜单过滤使用相同的缓存资格。

默认 Home 和承担 Quickstep 的包可能不同。当前只启用二者与已绑定 Launcher 一致的集成路径；无默认 Home、查询失败、未核对的 APK，或 Home / Recents 分属不同包时保留原生输入并输出 Warning，不猜测兼容实现。此限制不影响系统后端按 SDK 独立选择。新增分离 Home / Overview 支持时，须另外核对 fallback 容器、旋转和源退场保证；这组 Android 事实不进入公共 JVM 端口。

系统侧通过 `SystemAdapters` 选择 Android 后端，`YAMFManager` 不再直接指定 `SmoothWindowBackend`。在 `systemReady` 前核对关键任务、表面迁移和绘制接口，再安装原生 Hook；窗口执行仍通过 `WindowBackend` 和现有公共执行端口连接。

Android 17 还需启用 `com.android.systemui` 的模块作用域。Shell 与系统使用独立的拥有者协商，确认相同构建和归属标记后才创建根；SystemUI 死亡使入口能力失效，正常重建后重新协商。它不占用 Launcher 的 v2 对端登记表，也不改变像素协议。隔离机制、源码点位与资源规则见 [Shell 接入说明](android17/shell/README.md)。

通知入口单独预解析并安装在 `android17/notification`。输入侧保留通知行检查和原生解锁，包装保留下来的解锁后接口；执行侧关联唯一 cookie、原生 Task 对象及 token，经稳定事件和执行端口恢复场景。是否接管、目标布局、模式、轨迹、失败后的去向由 CORE 决定；临时启动容器、权限、绘制就绪与通知记录清理留在 AD。签名不匹配只撤回通知能力，不影响已安装的 Shell / Recents 路径。公共契约见 [`window-api/README.md`](../../../../../../../../../window-api/README.md) 的横屏通知入口说明。

Android 17 的 `AutomaticTaskFocusGuard` 还隔离自动选焦点：LOS 24.0 的 `RecentsController.setInputConsumerEnabled()` 调用 `ATMS.focusTopTask(displayId)`，原生会选到米窗置顶根内的可聚焦叶任务，产生额外 `TO_FRONT` 并使最近任务取消。适配器关联同线程的自动请求，只在原生 WM 锁内的 `setFocusedTask(int, ActivityRecord)` 选择阶段重读候选归属，经 `WindowRuntimes.acceptsTaskFocus(TaskFocusFacts)` 调用 CORE 策略跳过所有受管任务。保留原生权限检查、Binder 身份、锁及非米窗候选顺序；用户触摸、显式置前和全屏交还不受自动请求影响。调用结束或抛错即清除关联，没有轮询、手势计时或焦点定时恢复；选择异常报告 Error 并放弃该次自动焦点变更。

Launcher 侧由组合入口选择 `QuickstepNativeAccess` 和具体退场 ABI，再通过 `QuickstepHookInstaller` 注册方法。固定成员在初始化阶段解析，候选任务、控制器和表面归属仍在每次手势中读取。输入回调通过 `LauncherGestureAdapter` 接入 `LauncherEntryHandle`，不直接访问 CORE。

Android 17 的启动前解析覆盖输入、源事务、任务过滤缓存和控制器 normal / force 完成接口；结构解析与 Hook 注册分开，可以用原 APK 验证完整绑定而不启动 Launcher。Pixel 构建的 DEX 调用链、权限、R8 差异和验证方式见 [PixelOS 17 接入核对](android17/pixelLauncher/README.md)。

## 当前状态与范围

| 组合 | 状态 | 允许的行为 |
|---|---|---|
| Android 17 + Launcher3 | 迁移既有 LineageOS 24.0 真机基线 | 结构检查通过后安装手势、菜单和任务栏实现；本次重构仍需驻留代码生效后复验 |
| Android 17 + Pixel Launcher | 源码/DEX 核对候选，未完成 PixelOS 真机验证 | 仅 APK 版本号 907；手势接入公共端口，后端可用时过滤原生 desktop/freeform 菜单；米窗菜单与任务栏入口尚不安装 |
| Android 16 + Launcher3 / Pixel Launcher | 待适配 | 不安装原生 Hook，不借用 Android 17 后端 |
| Android 15 + Launcher3 | 待适配 | 不安装原生 Hook，不借用 Android 17 后端 |
| 其他 SDK / Launcher 组合 | 未登记 | 不安装原生 Hook；无 `SDK >=` 或包名包含 launcher 的默认匹配 |

APK 的最低 SDK 33 是应用安装下限，不是小窗支持声明。旧表面参数定义也不证明旧版任务后端已经成立。此次明确收紧以前未经验证的通用入口，Android 15/16 的完整适配属于后续工作。

AVIUM、Project Infinity X 等已经集成魔改小窗的 ROM 不在适配范围。系统与 Launcher 入口均会检查公开 `Build.PRODUCT` / `FINGERPRINT` / `DISPLAY` 中的对应品牌标识，识别到时跳过安装。该检查是尽力排除，不声称可以识别隐去品牌或伪装构建信息的 ROM，也不接管这些 ROM 的小窗。

不要使用私有 ROM 版本属性作为安装前提：当前设备把 `ro.lineage.version` 等标为 `custom_version_prop`，实际 SELinux 策略仅授权部分域读取，Launcher 的 `priv_app` 和 `system_server` 都没有对应读取权限。属性为空不能证明 ROM 不兼容；增加跨进程查询也不能补出接收进程本身没有的权限。当前使用公开构建信息和原生结构检查，既有 v2 协商继续只协商交接能力。

## 完成与失败约束

- 原始 UP 完整执行；仅暂缓已捕获处理器的最终去向决策，保持最后位移和输入清理。
- 源变换提交、目标桥接帧提交、原生控制器结束、源 UI 退场、目标准备分别确认；保留既有 v2 广播与单向 Binder 像素协议。
- `bridgeAvailable()` 同时要求跨进程协商和本地源事务同步机制成立。方法存在只是结构条件，不能替代真机时序验证。
- 同一进程只安装一组 Launcher 入口。安装中途失败会撤回已注册的该组 Hook；不在进行中的手势里切换实现。
- 菜单、任务栏独立安装。可选入口不匹配时保留原生行为，不影响已安装的手势路径。
- 公开端口不变：`TaskSessionPort`、`ScenePresenter`、`SourceHandoffPort`、`WindowLifecyclePort`。版本、包名、反射和平台资源始终留在 AD。

## 新增一个组合

1. 核对对应 ROM 源码与实际 Launcher APK，记录分支、提交和 APK 构建范围，并确认当前用户的默认 Home、实际 Quickstep 宿主及二者是否一致。
2. 在对应 Android / Launcher 目录编写具体安装代码，优先复用已证明等价的公共机制。
3. 声明确切匹配范围和证据状态，在 `AdapterCatalog` 登记唯一入口；需要不同系统机制时显式新增系统实现。
4. 补齐选择、原生签名及交接契约验证，再进行该组合的真机重复进入/退出、旋转、全屏、打断和清理验证。
5. 更新本表和接入文档。新 Android 大版本、ROM 分支或 APK 变化不能自动升级支持状态。

## 本次核对依据

- 本地 LineageOS 24.0 `Launcher3`、`frameworks_base`；原始 UP、StateManager 与系统后端按现有源码基线迁移。
- PixelOS `android_manifest` seventeen：`c16cfd0c95c5`；`android_frameworks_base`：`ebc25c0cf9c8`。
- Pixel Launcher 仓库 seventeen：`67ed56a43d89`，手机 APK 17/907；两参数播放包装方法在 DEX 中不存在，三参数方法的默认 flags 对应开源包装中的 0。LineageOS 的 `dispatchOnStart()` 返回控制器本身，Pixel 的 R8 版本返回 `void`，公共退场机制支持这两种已核对签名并忽略返回值。
- ROM 私有属性权限核对：LineageOS / PixelOS 的 device sepolicy、AOSP seventeen 属性宏，以及当前设备只读导出的 plat / system_ext CIL；未改变 SELinux 或设备属性。

`LauncherNativeBindingTest` 可以在当前设备上只解析 Home APK，验证实际成员签名；不启动 Launcher、不注册 Hook，也不证明原生交接时序。使用 `am instrument --no-hidden-api-checks` 放开测试进程的隐藏成员访问检查；这不改变该进程的 SELinux 权限，也不等同于完整的注入运行环境。

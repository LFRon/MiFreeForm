# Android / Launcher 适配入口

具体 Hook 是普通 Kotlin 实现，由 `HookSystem` / `HookLauncher` 选择后执行。这里的选择信息只属于 AD，不传入 `window-api`、CORE 或运行时。

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
  common/launcher/           Quickstep Hook 注册、输入归一、源画面与控制器交接
  common/organizer/          共享任务/表面机制
  common/system/             绘制、Android 事实、广播与像素传输
  common/freeform/           共享表面与输入变换
```

`AdapterCatalog` 用精确 SDK、包名、主进程及已核对的 APK 版本范围选择唯一组合，再由安装器核对整组原生签名。`referenceRom` 记录源码 / 真机证据的 ROM 基线，不是运行时对任意 ROM 的兼容承诺。当前核对范围是 LineageOS 24.0 / PixelOS 17；其他 ROM 即使签名相同也需要独立验收。每个组合的 `install()` 真正调用原生 Hook 安装代码，不存在描述语言、脚本解释器或自动生成 Hook。

系统侧通过 `SystemAdapters` 选择 Android 后端，`YAMFManager` 不再直接指定 `SmoothWindowBackend`。在 `systemReady` 前核对关键任务、表面迁移和绘制接口，再安装原生 Hook；窗口执行仍通过 `WindowBackend` 和现有公共执行端口连接。

Launcher 侧由组合入口选择 `QuickstepNativeAccess` 和具体退场 ABI，再通过 `QuickstepHookInstaller` 注册方法。固定成员在初始化阶段解析，候选任务、控制器和表面归属仍在每次手势中读取。输入回调通过 `LauncherGestureAdapter` 接入 `LauncherEntryHandle`，不直接访问 CORE。

Android 17 的启动前解析覆盖输入、源事务、任务过滤缓存和控制器 normal / force 完成接口；结构解析与 Hook 注册分开，可以用原 APK 验证完整绑定而不启动 Launcher。Pixel 构建的 DEX 调用链、权限、R8 差异和验证方式见 [PixelOS 17 接入核对](android17/pixelLauncher/README.md)。

## 当前状态与范围

| 组合 | 状态 | 允许的行为 |
|---|---|---|
| Android 17 + Launcher3 | 迁移既有 LineageOS 24.0 真机基线 | 结构检查通过后安装手势、菜单和任务栏实现；本次重构仍需驻留代码生效后复验 |
| Android 17 + Pixel Launcher | 源码/DEX 核对候选，未完成 PixelOS 真机验证 | 仅 APK 版本号 907，通过完整结构检查后启用手势；菜单与任务栏尚不安装 |
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

1. 核对对应 ROM 源码与实际 Launcher APK，记录分支、提交和 APK 构建范围。
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

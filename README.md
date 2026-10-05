# Pixel VoLTE/5G 助手（Shizuku）

无需 Root，通过 [Shizuku](https://shizuku.rikka.app/zh-hans/) 在 Google Pixel（含 **Pixel 9 Pro XL**）上开启 **VoLTE / VoNR / 5G（NSA+SA）/ VoWiFi** 等运营商能力。

适用于 Google 未对当地运营商做 5G/VoLTE 认证的情况（例如国行外版 Pixel 插大陆卡没有 VoLTE/5G 开关）。

## 原理

本应用与知名的 Pixel IMS（vvb2060 v3.1 方案）同一原理：

1. Shizuku 以 shell（ADB）身份运行服务，本应用通过 Shizuku 获得 shell 权限；
2. 应用通过 `IActivityManager.startInstrumentation`（以 shell 身份发起）在自己的进程内启动一个 Instrumentation；
3. Instrumentation 内先调用 `startDelegateShellPermissionIdentity` 把 shell 的权限身份（`MODIFY_PHONE_STATE` 等）委托给应用 uid，再调用隐藏 API `CarrierConfigManager.overrideConfig` 写入**运营商配置覆写（carrier config override）**：
   - `carrier_volte_available_bool` → VoLTE 可用 + 设置里显示“增强 4G LTE 模式”开关
   - `carrier_nr_availabilities_int_array = [NSA, SA]` → 开放 5G NSA/SA
   - `vonr_enabled_bool` → VoNR（5G 语音）
   - `carrier_wfc_ims_available_bool` → WiFi 通话
   - 以及 VT / 跨 SIM / UT 等
4. 同时通过 `ITelephony.setImsProvisioningInt` 打开 IMS 语音开通位（部分运营商在开通层拦截 VoLTE）。

> 为什么不能直接用 shell 调 overrideConfig？2025 年 10 月的 Android 安全补丁（CVE-2025-48617）封堵了 shell 身份直接调用该接口，所以必须走“Instrumentation + 权限委托”这条链路。

覆写支持两种模式（运行时按系统补丁级别自动选择）：

| 模式 | 行为 |
| --- | --- |
| 持久化覆写 | 重启后仍然生效（系统允许时自动使用） |
| 非持久化覆写 | 立即生效，但重启或“电话”进程重启后失效，需重新点一次“应用配置” |

## 针对最新系统（Android 17 QPR1，如 CP3A.260905.009）的须知

你的 Pixel 9 Pro XL 若为 `CP3A.260905.009`（Android 17 QPR1，2026-09-05 补丁级别）：

- 该补丁级别已包含 CVE-2025-48617 修复：老的 `setprop persist.dbg.*` 方法与“shell 直接调 overrideConfig”均不可用，本应用已按 v3.1 委托机制实现，无需额外操作。
- **持久化覆写大概率显示“不支持”**：最新补丁封堵了持久化路径，覆写为非持久化——重启后需重新应用。Shizuku 重连时应用会自动恢复覆写；建议保持 Shizuku 开机自启（无线调试开关保持开启）。
- Shizuku 请使用 **13.6.0 以上**版本（适配 Android 16/17 QPR 的行为变化）。
- 系统大版本更新后可能再次失效（历史上 2025-10、2025-12 更新都断过）：在 Shizuku 中**停用服务并重新授权**，再重新“应用配置”。参见 [Pixel IMS issue #423](https://github.com/kyujin-cho/pixel-volte-patch/issues/423)。
- **5G 已开启但不注册**：把应用内“5G 组网模式”切为**仅 SA**（`carrier_nr_availabilities_int_array` 仅含 SA），重新应用并重启。部分运营商在 NSA+SA 组合下 NR 无法注册，这是社区验证过的解法。
- Google Play 系统更新（Mainline）不涉及 `com.android.phone`/CarrierConfigLoader（随 OTA 更新），对本机制无影响。

## 环境要求

- Pixel 设备（Tensor 机型，Pixel 6 及以后；Pixel 9 Pro XL 完全支持），Android 12+（14/15/16/17 已按新版补丁适配）
- [Shizuku](https://github.com/RikkaApps/Shizuku/releases)（Play 商店或 GitHub 安装），通过**无线调试**启动（无需电脑），或 ADB 启动
- Android 11+ 的“无线调试”开发者选项

## 使用步骤

1. 安装并打开 Shizuku → 按引导开启“无线调试”并启动 Shizuku 服务；
2. 安装本应用，打开后授予 Shizuku 权限（弹窗），并允许“电话权限”；
3. 选择要作用的 SIM（默认全部）；
4. 按需开关功能（VoLTE / VoNR / 5G / VoWiFi / VT / 跨SIM / UT，默认全开）；
5. 点击 **应用配置**，等待提示完成；
6. 开关一次飞行模式（或重启），到 **设置 → 网络和互联网 → SIM 卡** 确认“增强 4G LTE 模式”“5G”等开关已出现；
7. VoLTE/5G 图标未出现时：确认运营商套餐已开通 VoLTE/5G（大陆三大运营商需在运营商侧开通 VoLTE），稍等几分钟让 IMS 注册。

> 应用内 Shizuku 连接成功后会自动应用一次配置（开机自启 Shizuku 的情况下也能自动恢复非持久化覆写）。

## 构建

```bash
# Android Studio 直接打开，或命令行：
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- AGP 8.7.0 / Gradle 8.9 / Kotlin 2.0.21 / compileSdk 35 / minSdk 31
- 依赖：`dev.rikka.shizuku:api|provider:13.1.5`、`org.lsposed.hiddenapibypass:hiddenapibypass:6.1`
- `:stub` 模块是编译期桩（compileOnly，不打包），运行时直接使用系统真实类
- 国内网络构建慢时，可把 `settings.gradle.kts` 里的 `GoogleMaven` 仓库换成 `https://maven.aliyun.com/repository/google`、`mavenCentral()` 换成 `https://maven.aliyun.com/repository/public`

## 项目结构

```
app/src/main/java/com/zt/volte5g/
  ShizukuProvider.kt   # Shizuku 集成：binder 接收、startInstrumentation 发起、版本指纹探测
  PrivilegedProcess.kt # Instrumentation：shell 身份委托 + CarrierConfig 覆写 + 校验
  Hidden.kt            # HiddenApiBypass 封装（豁免隐藏 API 限制）
  Keys.kt              # CarrierConfig 键名 / IMS 常量（字面量，规避 @hide）
  MainActivity.kt      # 界面：开关、SIM 选择、状态显示
stub/                  # IActivityManager / ITelephony / ServiceManager 等编译期桩
```

## 常见问题

- **应用配置后没有变化**：先开关一次飞行模式；仍不行就重启。非持久化模式在重启后需重新“应用配置”。
- **“持久化覆写：不支持”**：系统是最新安全补丁，持久化路径被封堵，只能非持久化（每次重启后重新应用）。应用已改为走沙箱路径尽量保住持久化。
- **VoLTE 开关出现但打不通电话**：VoLTE 需要运营商开通（大陆：发送短信或去营业厅开通 VoLTE）；另外确认 5G/VoLTE 套餐正常。
- **清除覆写**：把所有开关关掉后点“应用配置”，再开关一次飞行模式即可恢复系统默认配置。
- **安全提示**：本应用仅修改本机运营商配置，不会联网、不需要 Root；所有写入在 Shizuku 授权范围内进行。

## 免责声明

本项目利用 Android 调试接口修改设备侧运营商配置，仅供学习与个人设备使用。由此产生的任何后果（包括但不限于功能异常、保修问题）由使用者自行承担。请遵守当地法律与运营商条款。

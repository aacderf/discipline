# 自律 · Discipline

[![Android build](https://github.com/aacderf/discipline/actions/workflows/android.yml/badge.svg)](https://github.com/aacderf/discipline/actions/workflows/android.yml)

Android 本地自律工具，基于 Kotlin / Java 与 Jetpack Compose。本仓库公开 0.12.0 的完整 App 源码、测试、资源与构建流程。

## 功能

- 今天 / 这周计划、重复任务、置顶、完成、移除与计划管理。
- 可编辑课表、CSV 导入、日程 / 可缩放周网格、课程和计划提醒。
- 沙漏专注计时、番茄钟预设、轮次与休息接续、通知及小型悬浮面板。
- 手绘地点范围、停留计时、60 秒短暂定位漂移宽限、可选融合定位。
- 每日 / 每周应用限额、任务未完成限制、无障碍拦截与尽力备用模式。
- 统计、本地备份、浅深色与减少动态效果支持。
- 本机近七天应用使用分析、可选 DeepSeek V4.1 建议，以及经用户确认后生效的下周应用限额。

公开版使用合成课表示例，不包含个人课表、定位记录、备份、截图、地图 Key 或原始签名。示例学期固定为 2026-08-31 起 18 周；当前课表结构仍按这个学期校验，这是已知限制。

## 0.12.0 更新

- 在“统计”及“专注 → 应用限额”进入“应用使用与下周计划”，查看近七天前台时长、每日趋势及应用分布。系统缺失的日期显示为未知，不作为零使用；汇总可能延后，也不代表完整行为记录。
- 可配置自己的 DeepSeek API Key，使用官方 `deepseek-flash`（V4.1 Flash）生成下周建议。发送前确认所选应用名称、每日分钟及目标；不发送包名、位置、任务或消息内容。建议可编辑，确认后才保存为周一开始的一周限额。模型命名以 [DeepSeek 官方文档](https://api-docs.deepseek.com/)为准。
- 周计划与长期限额分开保存，重叠时取更严格的有效限额，到期恢复长期规则；不会自动启用权限或拦截服务。Key 使用 Android Keystore 加密，仅存于本机，不进入 App 备份；卸载后须重新配置。
- 默认持续至少 24 小时的地点任务仅按已记录停留结算，记录缺口不算完成；不足 24 小时的任务缺口仍待核验。后台晚发现截止时，未完成限制从实际结算时开始；显式指定且尚未到达的限制钟点仍保留。每日上限、试运行和受保护应用规则继续有效，既有已结算历史不补罚。
- 旧版本尚未结算的长地点待核验任务也采用上述规则；已完成或已经产生处罚的任务不重新执行。
- 应用限额共享一次系统汇总，切换应用仍重新读取，接近限额保留更频繁查询；分析页按需在 IO 线程读取并短暂缓存，不新增周期上传或后台分析工作。

## 0.11.9 更新

- 专注关联任务失效时清理旧选择；开始时再检查任务状态和执行窗口。自由专注始终显示本次会话的上下文，避免误显示下一项任务。
- 暂停休息可在计划地点之外继续，回到专注仍须确认到达指定地点。
- 地图删除、清空之后旋转屏幕保留撤销；清空的边界不会被旧地点重新带回。
- 课程导入与确认基于最新状态写入，保留其他课程和提醒记录；导入结束显示实际成功及冲突数量。
- 通知或对应渠道关闭时，不再为无法发送的提醒安排每秒重试；恢复权限后按现有窗口重新安排。
- 旧任务缺少手动进度字段时按零处理，拒绝备份中的非法进度，并避免非有限值进入进度控件。数据库与备份仍为版本 4。

## 0.11.8 更新

- 地图支持选中边界点删除、清空与一次撤销，保存后才更新地点；已存地点列表提供直接删除入口，并保护关联计划及历史记录。小屏确认范围按钮不再被辅助操作挤到换行。
- 计划／课程“提前 0 分钟”在到点或五分钟内短暂调度延迟时也可提醒，已发送事件不重复，已完成和请假不提醒。
- 课程编辑支持连续周次、单双周并检查时间冲突；保存使用最新课表状态以保留提醒历史，旧编辑窗口不会重新创建已删除课程。数据库及备份仍为版本 4。

## 0.11.7 更新

- 专注开始、恢复与补记检查在同一锁或事务中完成，防止并发操作覆盖已结束或已结算状态。
- 文件导入、导出与恢复改在 IO 线程执行，显示进度并拒绝重复数据操作，导航仍可响应。
- 统计按数据快照、纯计算和界面拆分；完成率排除请假与待核验，待核验单列。有效专注段合并相邻片段，暂停或休息后另计，避免将服务记录条数当作专注次数。
- CSV 支持连续周次、单双周、BOM 及带引号／换行的字段，同时检查同批课程冲突；确认时重新校验当前课表。

未更改数据库或备份版本。模块边界、统计口径和后续限制见 [架构与数据操作](docs/architecture.md)。

## 构建环境

| 项目 | 版本 |
| --- | --- |
| JDK | 21（Java 源码目标 17） |
| Android SDK Platform | API 37.0：`platforms;android-37.0` |
| Android Build Tools | 36.0.0 |
| Gradle Wrapper | 9.3.1 |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.4.20 |
| Compose BOM | 2026.09.00 |
| 最低 / 目标 Android | API 26 / 36 |

首次构建需要联网。使用仓库中的 Gradle Wrapper，无需全局安装 Gradle。Android Studio 中安装相应 SDK，或执行：

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
```

SDK 路径由 Android Studio 生成的 `local.properties` 或 `ANDROID_HOME` 提供，参考 `local.properties.example`。依赖版本固定，Wrapper 配有官方发行包 SHA-256 校验。

### Windows / PowerShell

```powershell
git clone https://github.com/aacderf/discipline.git
cd discipline
.\build.ps1 -Online
```

此脚本用临时盘符处理 Windows 中文路径，并在结束后移除映射；不会移动或删除源码。默认构建 Debug 并运行单元测试。依赖缓存齐全后可以省略 `-Online` 使用离线模式。

```powershell
.\build.ps1 -Online -Tasks @('assembleDebug','testDebugUnitTest','assembleDebugAndroidTest')
```

### Linux / macOS

```sh
git clone https://github.com/aacderf/discipline.git
cd discipline
./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。包名 `com.hl.discipline.qa`，用于独立试用。单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。

## 地图与融合定位

不配置 Key 也可构建、运行非地图功能及单元测试；地图和融合定位会显示未配置提示，仍可使用系统定位。实际地图功能需要你自己的高德 Android Key：

1. 复制 `amap.properties.example` 为 `amap.properties`，填写自己的 Key；或在构建环境设置 `AMAP_API_KEY`。环境变量优先。
2. 在高德控制台登记构建包名与签名证书 SHA-1。Debug 为 `com.hl.discipline.qa`，Release 为 `com.hl.discipline`；证书须使用你自己的构建证书。
3. 地图与融合定位另需 App 内同意相应隐私说明，以及系统定位授权。

Key 文件被 Git 忽略，不要提交自己的 Key。高德 SDK 与服务遵从其独立条款，不随本项目代码一并变为 MIT 授权。

## Release 与签名

```powershell
.\build.ps1 -Online -Tasks @('assembleRelease')
```

未配置签名时生成 `app/build/outputs/apk/release/app-release-unsigned.apk`，不能直接安装。需要签名时自行生成密钥，并按 `keystore.properties.example` 配置本地 `keystore.properties`，再构建 Release。签名材料只保存在你自己的环境中。

本仓库不含作者的原私有签名；你的签名无法覆盖作者原签名安装的正式 App。不要为了测试清除正在使用的正式 App 数据，使用 `.qa` 测试包。

## GitHub Actions

Push 到 `main`、Pull Request 或手动运行 [Android build](https://github.com/aacderf/discipline/actions/workflows/android.yml)：安装 SDK、构建 Debug、运行单元测试并检查未签名 Release 构建。完成后在 Actions 的 Artifacts 下载 `discipline-qa-apk` 和 `unit-test-reports`。

默认 CI 不使用任何地图或签名 Secret，因此 Fork PR 也能执行编译和单元测试。上传的是独立 QA 包，不是作者签名的正式发行版。所有 Action 固定到提交 SHA。

## 设备测试与运行边界

支持 Android 8.0（API 26）及以上的 APK 系统，不绑定 iQOO，也不要求 Google Play 服务。使用通用 APK，保留 ARM64、ARMv7 与 AndroidX 提供的 x86 / x86_64 库；地图 SDK 原生渲染库提供 ARM64 / ARMv7。

0.11.6 更新高德合包至 `11.3.100_loc11.3.000_sea9.8.1`，替换旧版仅 4KB 对齐的地图库。`tools/check-native-pages.ps1 -Apk <apk-path>` 检查 APK 内 64 位 ELF 的 LOAD 对齐；这不替代 ZIP 对齐与实际 16KB 设备测试。

启动先呈现页面再更新任务和提醒；后台服务被拒绝时记录运行问题，不要求用户先授予全部权限才能打开。精确闹钟授权在调度时被撤销则回退普通闹钟。部分厂商授权页不可用时回退到应用详情。

到“设置 → 运行与权限 → 本机后台运行”查看对应品牌的提示。小米 / Redmi / POCO 检查自启动、后台省电“不限制”，悬浮功能还需厂商后台弹出权限与 Android 悬浮窗授权。vivo、OPPO / 一加 / realme、华为 / 荣耀安卓系统、三星和其他品牌有各自提示；菜单会随系统版本变化。拒绝这些后台权限不妨碍前台计划、课表和计时。

设备测试源码在 `app/src/androidTest`。包含清空 QA 测试数据的用例，只在独立 `.qa` 包与专用模拟器运行：

```sh
./gradlew :app:connectedDebugAndroidTest
```

0.11.6 的 Android 8 / API 26、Android 16 / API 36 模拟器各通过四项兼容专项测试：无特殊授权切页、定位失败保留计划、无悬浮窗授权的前台计时与暂停恢复、失效授权入口回退。现有 28 项单元测试通过，lintDebug 为 0 错误；[公开构建记录](https://github.com/aacderf/discipline/actions/runs/37813574417)成功。Release 与 Debug 已通过 64 位 ELF LOAD 和 16KB ZIP 对齐静态检查，未实测 16KB 页设备。

部分测试需要使用情况访问、悬浮窗、定位或无障碍授权及可用地图底图；不会在默认 CI 中声称通过。小米真机启动、实地 GPS 精度、厂商后台保活与完整手绘仍需真机验证。

0.11.7 本地通过 37 项单元测试、Debug／测试 APK／Release 构建及 lintDebug（0 错误、55 项警告）。Android 16 / API 36 模拟器通过四项优化专项测试：后台文件操作与导航、失败后数据保留、并发专注及配置恢复、浅深色统计口径；这些在作者带个人课表的工作副本中运行，公开版保留对应源码并使用合成示例。0.11.7 没有重复执行 0.11.6 的双版本兼容矩阵，也未实测小米真机或量化帧耗时改善。

0.11.7 的[公开独立构建](https://github.com/aacderf/discipline/actions/runs/38113400051)已通过：37 项单元测试、无私有配置的 Debug／未签名 Release 与 64 位原生库对齐检查。

0.11.8 通过 44 项单元测试及构建，lintDebug 为 0 错误、55 项警告；[公开独立构建](https://github.com/aacderf/discipline/actions/runs/38114723510)通过。作者工作副本在 API 36 模拟器通过 7 项不同的地图数据操作、提醒和课程编辑专项测试；原生地图投影不可用的笔迹／拖点用例跳过 1 项，实际地图手势仍须真机验证。最终小屏布局只重跑受影响的两条地图路径，均通过。正式包以原签名覆盖升级并冷启动成功，未逐项核对全部历史数据。

0.11.9 的[独立构建](https://github.com/aacderf/discipline/actions/runs/38345715211)通过，44 项单元测试通过，lintDebug 0 错误、55 项警告。作者工作副本在 API 36 模拟器通过 12 项不同的专注、课程、通知关闭、地图旋转撤销与旧进度备份专项测试。最终组合中备份夹具曾缺少引用计划，补全后单独重跑通过；连续 UI 用例也已通过。正式包同签名覆盖安装并冷启动成功，未逐项核对全部历史数据，也未重新执行全机型或长期后台矩阵。

0.12.0 的[独立构建](https://github.com/aacderf/discipline/actions/runs/38383984588)通过，54 项单元测试通过；本地 lintDebug 0 错误、58 项警告。作者工作副本在 API 36 模拟器共通过 12 项不同的使用分析、审核限额、长任务结算与备用模式专项，最终增加旧待核验任务回归并单独重跑通过。320dp 浅深色及 1.3 倍字号检查、原签名覆盖与冷启动完成；API 使用模拟响应验证，未进行计费调用或真机耗电／帧耗时测量，详见[验收说明](docs/validation-0.12.0.md)。

计划、记录和配置本地保存，无自建服务器；地图、搜索与可选融合定位使用高德网络服务。使用分析默认在本机完成，主动请求 AI 建议时，经确认的摘要发送至 DeepSeek 官方服务，使用用户自己的 API 账户并遵从其计费与隐私条款。备份包含建议草稿及已确认周计划，不包含 API Key 或系统原始使用历史。无障碍权限或服务被撤销时拦截可能失效，备用模式是尽力拦截。普通应用不能阻止系统强行停止或卸载。

## 源码结构

- `app/src/main/java/com/hl/discipline/`：Compose 页面、计时状态、规则、SQLite Store、定位和拦截服务。
- `app/src/main/res/`：主题、图标、有限状态动画和插画。
- `app/src/test/`：本地业务规则测试。
- `app/src/androidTest/`：界面及服务的设备测试。
- `tools/icons/`：可选开发图标生成器；Android 构建直接使用已提交 XML，无需 Node。

许可证：[MIT](LICENSE)。第三方资源和 SDK 例外见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。构建依据：[GitHub Actions Gradle 文档](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-gradle)、[Gradle Wrapper 校验](https://docs.gradle.org/current/userguide/best_practices_security.html)。

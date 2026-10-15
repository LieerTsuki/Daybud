# V1 / 1.0.0 交付验收

日期：2026-10-15至16（Asia/Shanghai）。承接 E6 源码基线 `8e9ce14`，在 `feat/v1-unified-plans` 执行 F 候选交付。应用身份继续为 `com.example.lifemanager`，版本 `1.0.0 / code3 / Room6`。本记录只描述本轮实测；历史 E6 与 Phase 5 数字不充当本轮结果。

## 本轮修正

- 首轮 67 项设备测试实际失败 12 项，未计为通过。原始结果留于 `.tools/f-baseline-evidence/results.xml` 与 `.tools/f-device-baseline.log`。
- 六项通知导航测试在收尾时报告 Activity 未销毁。logcat 证明同一 Activity 已收到 DESTROYED，但 `setIntent` 改变 MAIN/LAUNCHER 身份后，ActivityScenario 忽略事件。`onNewIntent` 改为直接将参数交给保留的导航所有者，不替换启动 Intent；冷启动、严格新通知、旋转及一次消费分别回归。此修复不承诺进程死亡后的未消费通知恢复。
- 计划主按钮和“全部优先级”筛选补足 48dp 最小布局高度，保留大字体自然增长；不是全面无障碍合规声明。
- 真实文件选择器取消测试改为核对当前完整提示与 UNCHANGED 回执；设置测试等待主题控件实际可用再点击，保留保存／旋转／恢复断言。
- 新增 `UnifiedPlanActivityDeviceTest` 三项：真实 UI 新增无日期→同 ID 添加日期→筛选→完成／撤销→全库统计／日历；草稿旋转与切页确认；严格新通知等待草稿、旋转保留票据与消费后不重放。UUID 合成数据逐项精确删除，不清库。
- 定向 15 项设备测试通过，0 failure/error/skipped；日志 `.tools/f-device-fix.log`、结果 `.tools/f-fix-evidence/results.xml`。

## 设备隔离与升级样本

设备均为API35 / Android15 / x86_64 / 1080×2400 / 4 vCPU / 2GiB。首轮设备测试使用仓库已有 `Rizhiya_Branding` 专用AVD的 `emulator-5580` 只读会话（`-read-only -no-snapshot -no-window -no-audio -gpu host`）。另一个旧升级采证会话中脚本漏读WAL的结果已隔离，不能作为最终V2往返证据；中断退出后的会话修改不再保留，不把相同AVD名称推断为共享写盘。

2026-10-16重新建立全新专用 `Rizhiya_F_Upgrade_20261016`，配置与数据目录均在 `.tools/f-avds/`，使用 `emulator-5582`，参数 `-no-snapshot -no-window -no-audio -gpu host`；该目录为本任务新建，不覆盖既有AVD，未执行connectedAndroidTest或卸载升级样本。最终升级／文件／性能证据均来自此会话。没有连接个人设备、读取个人数据库或保存既有AVD快照。

主机为 Windows / JDK25 / Gradle9.4.1 / AGP9.2.0，设备字节码目标17。模拟器系统时区实测为 `GMT`；旧待办迁移／V1恢复因此使用 GMT，并不是猜测原用户设备时区。日程原存储 `+08:00` 与 `Asia/Shanghai` 逐值保留。

1. 从既有 Release 下载 `v0.1.1-rizhiya-preview-20261009/app-debug.apk`。SHA-256 为 `65340e0b33e4976722836c9cdaa8ab2e2ba73f9d8b84d04ffe46a108e88631bf`，与既有文档一致；不替换历史资产。GitHub 未认证元数据请求曾遇限流，改用已知发布资产直链下载，未绕过文件校验。
2. 两个 APK 的证书 SHA-256 均为 `55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c`。只使用本机既有调试证书，不生成新密钥，不更换包名来规避升级。
3. 安装旧版并由真实 Activity 创建生产 `life-manager.db`。确认版本0.1.1/code2、schema5及十张业务表为空后，用 `tools/verify-v1-upgrade.py prepare --serial emulator-5582` 写入合成数据：2待办、2日程，以及其余八表各1行。覆盖同号来源、前向父引用、标签、全天非活动字段、自定义重复原文、例外、历史打卡／支付、不同币种及 `9007199254740993` 金额；设置为非默认值，世代73。
4. 由旧版真实设置页→明文确认→Android CreateDocument→Downloads 保存 V1 备份，页面报告“备份已导出并验证”。文件2450字节，十表均非空；最终副本 `.tools/f-upgrade-20261016/old-v1.json`，旧 SQLite 与逐值期望另存于同目录。它是旧版实际导出文件，不是测试代码伪造 wire 文件。
5. `adb -s emulator-5582 install -r <新Debug APK>` 成功，未卸载／清数据／降级。实际包名与版本由 aapt 核对，Activity 首次打开实际生产库完成迁移。首次独立 Python/SQLite 核对：schema6，4计划×27字段全部一致，TODO/SCHEDULE同号来源映射不混淆，父子／标签／例外准确，六张未变表逐值相同，设置按OR合并且其他偏好保留，世代仅73→74，旧业务表退役，foreign_key_check无结果。不是仅调用 Migration helper 的装置测试。
6. 强制停止后重开，再次核对世代74及全部原值；使用真实OpenDocument选择旧版V1文件，预览显示GMT转换、4计划／4来源映射和真实十表数量。另存3508字节V2保护备份，页面明确重读验证后进入独立最终确认；确认恢复后同样逐值核对及重开，世代75稳定。
7. 真实设置页导出V2，CreateDocument保存3508字节文件并报告已重读验证。随后选择该V2文件，预览不伪造旧V1时区说明；另存新文件 `daybud-before-v2-restore.json` 作独立保护备份，未覆盖旧备份，再最终确认。恢复及重开均逐值通过，世代76稳定；native设备SQLite与主机快照每轮一致。

最终目录中的 `upgraded`／`reopened` 为74，`v1-restored`／`v1-reopened` 为75，`v2-restored`／`v2-reopened` 为76；各自有 `verified.json` 和未修改的 `raw` 主库／WAL及SHA清单。保护文件、导出V2、最终确认与成功页XML也保留于同一目录。业务及关系共16行（旧十表12行，转换后含4行来源映射）。

升级复核命令：

```powershell
python -X utf8 tools/verify-v1-upgrade.py verify --serial emulator-5582 --zone GMT --label <本次唯一标签> --generation <期望世代>
```

脚本拒绝物理设备、非指定新建AVD和Python `-O`；prepare必须空库。每次使用全新快照目录，存在的主库／WAL必须成功读取，并与设备原生SQLite的世代交叉核对；checkpoint只作用工作副本，原始字节与SHA单独保留。备份与SQLite只保存在Git忽略的 `.tools`。

旧采证失败诊断：首次host核对V2恢复期待76却得到74，不能计通过。旧快照与升级后main逐字节相同；实测 `adb exec-out ... ls databases` 返回一行多名，脚本按行判断遗漏WAL。改为 `ls -1`、增加native/host一致断言和raw保留后，以上全新AVD完整重跑通过。中间一次远程shell条件引用失败也立即中止，未产生通过记录；不修改生产世代逻辑来迎合脚本。任务中断时的首轮全任务日志只证明已完成的70项设备测试，不称整条构建成功。

## 最终验证与文件往返

| 验证 | 本轮结果 | 证据 |
| --- | --- | --- |
| JVM强制重跑 | 1122项／92 suites，0 failure/error/skipped | `.tools/f-resumed-validation.log`、`app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| API35设备全量 | 70项，0 failure/error/skipped | `.tools/f-final-validation.log`、`app/build/outputs/androidTest-results/connected/debug/TEST-*.xml` |
| Debug／androidTest | 构建检查成功，恢复后的命令为缓存检查 | 同一源码此前已重新打包；本轮Debug SHA见交付清单 |
| Release | 重新打包成功；unsigned构建产物另签既有证书 | `.tools/f-resumed-validation.log`；3m33s／155 tasks（8 executed／147 up-to-date） |
| lint | 0 error／47既有warning | 首轮最终命令已生成当前源码报告；恢复命令为缓存检查，XML在`app/build/reports/lint-results-debug.xml` |
| 覆盖升级／文件 | 新AVD的真实生产库及系统选择器闭环通过 | 上述73→74→75→76原始证据 |
| 独立最终代码复审 | 9/10，无可操作遗留问题 | 主Activity／三项设备测试／采证脚本；代码复审不替代性能／真机 |

最终JVM/构建命令：

```powershell
$env:JAVA_HOME='D:\jdk'
.tools/gradle-9.4.1/bin/gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug --console=plain
```

设备全量仅在指定测试串号使用 `ANDROID_SERIAL=emulator-5580` 执行 `:app:connectedDebugAndroidTest --rerun`；最终70不是历史迁移fixture14项，也不是仅编译androidTest。保留AGP旧API/Kotlin插件、kapt选项、测试源废弃字段和Robolectric资源警告边界，不宣称所有构建警告已清零。

## 发布与性能边界

Release Gradle 配置不引入正式签名，也不把 unsigned APK 当成可安装包。交付采用原有证书签署的非 debuggable 预览 APK，需核对证书、安装身份、体积与 SHA-256。1.0.0 是产品版本，应用商店正式发布资格未验收。

最终unsigned Release为12,416,492字节，已签名Release为12,435,895字节，Debug为18,931,664字节，均低于30MB。zipalign及APK v2/v3签名验证通过，aapt确认1.0.0/code3/min26/target35。最终签名包再次 `install -r` 成功，package flags不含DEBUGGABLE；既有证书摘要与0.1.1一致。APK摘要与安装说明见[交付说明](v1-release-notes.md)。

性能只在最终非调试 APK 上执行，测量时不并发 Gradle。`tools/measure-v1-performance.py` 保留五次系统 COLD/TotalTime 原文与切页 gfxinfo；每次切页核对真实 selected 状态，确认帧数非零。UIAutomator 和等待有额外开销，这不是 Macrobenchmark，不由控件耗时推导 FPS。物理设备、稳定60fps、大库／云文件提供方与厂商省电策略仍需另外实测。

### 2026-10-16 非调试实测

最终目录 `.tools/f-performance-final/`，GPU host，SurfaceFlinger原文另存。五轮系统均明确报告COLD／Status ok，TotalTime分别 **496、483、543、631、474ms**，中位数496ms、最大631ms；本模拟器小库样本满足启动小于2秒，不能推导真机达标，也不与不同环境的历史数字作A/B优化结论。

打卡→订阅→设置→计划，循环5轮／20次，逐次保存真实层级并核对目标tab选中。每次至少等待0.9秒，包含UIAutomator空闲／层级采集的实际轮次约3.08–3.20秒；该时间不是应用切页响应时延。gfxinfo共 **1052帧，31 janky（2.95%）**；系统legacy口径另为188（17.87%），两种口径不能相加。延迟直方图P50/P90/P95/P99为17/22/22/26ms，GPU为13/18/18/21ms。原文同时包含全局与窗口重复统计，只计同一1052帧，不相加为2104。不用这些延迟的倒数宣称FPS，稳定60fps仍未验收。

首个测量尝试五次COLD原文有效，但切页校验因错误假设“content-desc和selected在同一XML节点”中止，无完成结果。真实XML证明入口描述在选中tab的子节点；修正为选中祖先必须包含该确切目标，独立复审后在全新目录重测，20次全通过。旧失败目录 `.tools/f-performance/` 保留，未用旧样本补齐新结果。

## 交付结论

本轮完成1.0.0/code3的**本地预览Release候选交付**：设备回归、实际旧APK覆盖升级、真实Downloads与系统选择器V1/V2闭环、Release构建／既有证书签名／安装／体积与模拟器性能采样均有本轮证据。结构化汇总见[evidence/v1-1.0.0-validation.json](evidence/v1-1.0.0-validation.json)，文件与SHA见[交付说明](v1-release-notes.md)。按F范围提交推送功能分支，不自动合并main或改写历史Release；本次未在GitHub公开发布新资产。

物理设备、稳定60fps、大库／云文件提供方、厂商后台策略、正式签名与商店发布资格仍未验收。此结论不关闭这些项目，也不将产品版本号等同于正式发布资格。

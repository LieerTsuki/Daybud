# 计划月历已完成任务灰色显示验收

验收日期：2026-10-27。正式版本：1.4.2/code18。状态：已发布正式Latest Release，公开下载核对通过。

## 实现范围

- 月历日期格中的任务名称标签以完成状态选择配色：已完成时使用中性灰，未完成时沿用原四象限配色。
- 浅色主题为`#E0E0E0`背景、`#424242`文字；深色主题反转这两种颜色。使用当前应用主题判断明暗，支持应用主题设置。
- 撤销完成后恢复原象限颜色。仅调整显示，不改变任务象限、日期、完成状态的存储或查询逻辑；Room8与V4备份格式不变。
- 四象限列表和当天详情的配色保持原行为。

## 开发阶段验证

- 现有`PlanCalendarTitlesComposeTest`（8项）、`PlanOverviewComposeTest`（8项）、`PlanDayDetailsComposeTest`（2项）全部通过：共18项，failure/error/skipped均为0。
- `:app:assembleDebug`构建成功，生成`app/build/outputs/apk/debug/app-debug.apk`。
- `tools/check_project_structure.py`通过。
- 在独立API35模拟器中，通过真实界面创建“重要且紧急”的有日期任务并完成：浅色月历显示浅灰标签，深色月历显示深灰标签；返回浅色主题撤销完成后，标签恢复原珊瑚色。

本地日志位于`.tools/calendar-completed-colors/validation.log`；已查看的截图为同目录的`verified-month-completed-light.png`、`verified-month-completed-dark.png`和`verified-month-undone-light.png`。这些本地构建与验证产物不入库。

## 1.4.2发布验证

以下为更新版本号后的本轮结果，[验证摘要](evidence/calendar-1.4.2-validation.json)包含签名和覆盖升级回执。

| 项目 | 结果 |
| --- | --- |
| JVM测试 | 121套件、1279项，失败/错误/跳过均为0 |
| 结构与签名工具 | 24项结构测试、17项签名工具测试及当前结构检查通过 |
| 构建 | Debug、Release和DebugAndroidTest构建成功；编译androidTest不等于设备测试通过 |
| Lint | Debug与Release各0错误、35警告；保留警告，不称为零警告 |
| 正式签名 | 沿用旧→新轮换链，API26/27/28/32/33/35六个检查点、非调试包、包名/版本和16KiB对齐通过；最终签名包的所有ZIP条目内容与最终未签名构建一致 |
| 覆盖升级 | API35独立模拟器安装已发布1.4.1，再以`install -r`覆盖1.4.2，不卸载、不清数据；UID10148保持，全部表结构、类型及逐字段值、NULL、序列和Room身份一致 |
| 签名包界面 | 四象限各一条已完成/未完成的有日期合成任务，共8条；月历中已完成标签在明暗主题均为灰色，未完成标签保留象限色；从真实界面撤销完成后恢复原象限色 |
| 审查 | 独立只读代码审查未发现阻断问题 |

正式APK：12706144字节，SHA256为`713ffe9aa877e6acf4f9223e042926919ac5b390a431782f03dd21b60ec3438a`。APK、签名回执、升级快照、构建日志与`signed-light.png`、`signed-dark.png`、`signed-undone-dark.png`保存在本地`.tools/release-1.4.2/`，不入库。正式发布不包含私钥或口令。

最初Lint因本机`local.properties`的盘符冒号未转义而失败；修正后普通重跑复用了旧分析结果，显式重跑两个Lint分析任务后通过。失败日志保留，不将其作为成功记录。首次模拟数据传输失败，基线数量校验拒绝继续；修正传输并逐字节校验后，旧包真实启动且8条任务存在，才记录升级基线。

## 限制

- 本轮未运行完整设备自动化、API26/28实际覆盖升级或真机回归；六个SDK签名检查不等于六种设备实测。
- 升级为合成数据样本，任务表非空，其他业务表多为空；空表逐值相等不能证明真实非空日记、附件、习惯或订阅数据覆盖。未核对所有私有文件/偏好、附件或厂商系统行为。
- 远程CI、公开发布与下载核对另行记录，不沿用历史版本的测试数字作为本轮结果。

## 公开发布结果

已于2026-10-27T08:30:37+08:00发布正式Latest Release，发布源码`408f0871e0c89a9bffffff26b0e34dae239c6f45`已推送main。APK、UPDATE.md、SIGNING.json和SHA256SUMS.txt均经匿名完整回下载，与本地字节及SHA256一致。[下载APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.4.2/rizhiya-1.4.2.apk) · [正式Release](https://github.com/LieerTsuki/Daybud/releases/tag/v1.4.2) · [公开发布回执](evidence/calendar-1.4.2-publication.json)。

发布回执记录时，发布源码的[远程CI](https://github.com/LieerTsuki/Daybud/actions/runs/40426861323)仍在运行，尚不作通过结论；上方测试与构建数字来自本地本轮报告。远程状态以运行页面为准。

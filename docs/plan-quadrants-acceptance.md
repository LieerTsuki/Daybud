# 四象限计划改版验收

日期：2026-10-16。分支：`feat/plan-quadrants`，基线：`17a562cf958a5792d47b40791bc100ff87d319a8`。本轮候选为1.0.1/code4；当前公开Release仍为1.0.0，本轮不覆盖历史发布。

## 范围与数据兼容

用户要求去掉计划页中的分类和筛选条，改为四象限，每格可添加计划；日历每天显示名称。用户明确确认手动选择象限。

| 象限 | 沿用存储值 |
| --- | --- |
| 重要且紧急 | HIGH |
| 重要不紧急 | MEDIUM |
| 紧急不重要 | LOW |
| 不重要不紧急 | NONE |

旧记录直接按原优先级显示，不重写存储。日期变动、逾期和完成均不改变象限。每格默认展示前三项未完成计划，可展开其余项；已完成计划在原格展开查看并撤销。搜索不改变归属。

编辑器新增目标携带所选象限，脏草稿确认后仍保留目标预设；旧世代动作不得打开替换数据。未设置日期的计划只在象限中显示；月历按既有日期、时区、跨日和重复规则显示前两项名称及剩余数量，点日期查看完整列表。

安装身份`com.example.lifemanager`、Room schema6、备份V2/旧V1严格解析、提醒身份与数据世代保护保持。无数据库迁移、无清空数据。升级前保留独立完整备份，同签名覆盖，不通过卸载解决签名冲突。

## 实施与验证记录

采用 brainstorming 确认手动归属，以 Buddy 分工完成首页、编辑器和月历，串行运行Gradle并独立复审。该机器缺少Buddy仓库状态脚本，流程和证据记录于本文。

- RED：`.tools/quadrants-red.log`，新增8项UI测试在旧代码编译通过后全部因缺少象限/月历名称节点失败。
- 定向GREEN：`.tools/quadrants-green.log`，计划UI包128项/12 suites通过，0 failure/error/skipped。包含新增预设、脏草稿、日期不改象限和旧世代回调测试。
- 首轮全量：`.tools/quadrants-full.log`，1137项中9项失败。7项仍等待已移除的新增按钮/状态入口；2项Room用例仍要求完成后从默认列表消失。移植为象限新增及完成展开，同ID、实际数据库、标签、重复例外、草稿、旋转与通知断言保留。
- 独立复审发现首页遗漏重复系列完成提示。增加真实DAILY计划的回归测试，在`.tools/quadrants-repeat-red.log`中5项/1失败确认缺提示后，恢复「重复系列 · 勾选完成将结束整个系列」，复审关闭。
- 后续全量1138项/2失败（`.tools/quadrants-final.log`）：默认ALL保留旧行后，Room测试在切COMPLETED仍加载时立即撤销，被正常的加载保护拒绝。补充等待新快照`!isLoading`。再次全量1138项/1失败（`.tools/quadrants-jvm-final.log`）：日历旧回调测试在页面出现刷新时滚动不存在的完整列表；改为等已读取且包含目标计划再执行。生产加载保护保持。
- 最终全量：`.tools/quadrants-jvm-verified.log`，1138项/95 suites，0 failure/error/skipped，强制重跑成功。对比基线新增16项/3 suites；既有用例按新界面迁移。
- 独立复审已复核重复提示修复、Room默认ALL及加载等待、原世代回调、草稿与旋转/通知测试迁移；无遗留重要问题。
- 本轮API35设备：`.tools/quadrants-device.log`，专用`Rizhiya_Branding`模拟器（Android15/x86_64）实际执行70项，0 failure/error/skipped。含真实Room新增/同ID日期添加与移除/手动象限保持/完成撤销/日历统计、旋转、通知与草稿保护，以及光暗320dp/200%字体和48dp触控检查。未在个人设备运行。
- Debug、androidTest、Release及lint最终单独复核见`.tools/quadrants-build-verified.log`；0 error/47既有warning。签名采用原预览证书，APK v2/v3和zipalign验证通过，非debuggable。

## 实际覆盖与界面采证

设备回归完成并自动卸载测试应用后，重新安装已发布1.0.0/code3，植入六条明确合成计划（四档优先级、重复、无日期、已完成），然后以`install -r`覆盖为1.0.1/code4。使用设备SQLite读取全部应用数据库表，升级前后所有行逐值相同；Room仍schema6，不触发迁移、不改世代、不卸载新旧版本之间的数据。

原始采证位于`.tools/quadrants-upgrade`：`before.json`、`after.json`、`verified.json`、实际UI XML及PNG；`.tools/quadrants-preview-evidence.py`对命名测试AVD、版本、root和空计划库有显式保护，拒绝对非空库植入样例。四象限按旧值显示；月历当天显示两个名称和「还有2项」，点日期下方可达当天完整列表，无日期目标不混入月历。第一次界面采证因目标日期格的剩余数量位于视口外而失败，滚动露出该行后检查通过，未改业务代码或减少断言。

- [四象限真实界面](assets/plan-quadrants-board.png)
- [月历计划名称及剩余数量](assets/plan-quadrants-calendar.png)
- [空库四象限](assets/plan-quadrants-empty.png)

本轮不是旧版备份格式重做，1.0.0的真实文件提供方V1/V2往返仍属于历史验收；本轮全量备份及兼容自动测试重跑通过。

## 候选交付与性能边界

本地安装包：`.tools/delivery-quadrants/rizhiya-1.0.1-quadrants-preview.apk`，1.0.1/code4，12,435,895字节。SHA-256：`b2bae413c70e7ed6046d2b79a6cfe87d34c715a2fd8970b562878840c9762ef0`。虽与旧包字节数相同，内容、版本和摘要不同；旧包不覆盖。证书SHA-256：`55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c`，与1.0.0预览包一致。

最终非调试候选在同一专用API35模拟器、六条合成计划下采样，期间无Gradle并发。五次系统COLD/TotalTime为651／998／1033／765／1028ms，中位998ms、最大1033ms；两轮共8次切页均核对真实选中入口，gfxinfo采样414帧、21帧janky（5.07%），legacy统计173帧（41.79%）。这些是模拟器小库和系统口径采样，不作为稳定60fps或比1.0.0更快的结论；不与不同数据、轮数及宿主负载的历史采样直接比较。原始启动、XML、gfxinfo及脚本位于`.tools/quadrants-performance`和`.tools/quadrants-performance.py`。

结构化证据：[plan-quadrants-1.0.1-validation.json](evidence/plan-quadrants-1.0.1-validation.json)。交付压缩包位于`.tools/rizhiya-1.0.1-quadrants-preview-delivery.zip`，含APK、摘要、本文、截图、验证汇总和可复查原始采证。当前仅功能分支本地候选，未公开发布、未合并main。

历史1.0.0的1122项JVM、70项设备、升级及性能不作为本轮新结果。真机、TalkBack人工朗读、大库及商店正式签名仍不在本轮验收范围。

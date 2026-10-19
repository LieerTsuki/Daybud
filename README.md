# 日子芽 · 离线生活管理 Android App

日子芽是 Kotlin + Jetpack Compose 的离线生活管理工具；V1 将待办与日程统一为计划，同时保留习惯打卡和订阅费用管理。

更新日期：2026-10-19。

每次版本更新附带[更新内容文档](CHANGELOG.md)；开发中分批结果与已发布版本分开记录，此约定已写入仓库 `AGENTS.md`。

## 1.1.4正式候选验收通过，待发布

本轮1210项JVM/107套件、17项签名工具测试、六个精确SDK签名检查及API26/28/35三条真实覆盖升级通过，16张表和序列一致；构建成功，lint0错误/50警告。正式GitHub Release待发布，当前公开下载仍为下方1.1.3预览版。本轮无界面或数据库变更，用户已在会话中确认此前真机验证完成。[正式发布验收](docs/stable-release-acceptance.md) · [签名与密钥恢复](docs/release-signing.md)。

## 历史设置页精简 · 1.1.3预览版已发布

设置主页精简为主题、提醒、数据操作和两个详情入口，沿用现有奶油白、薄荷绿与圆角卡片。取消日期格式调整，日期统一YYYY-MM-DD；通知权限、关于和许可证在点击后查看。旧日期偏好保留于存储和备份，不再改变当前展示。当前1.1.3/code10，Room7及format3/schema7保持。本轮1210项JVM/107套件、74项API35设备测试/27套件全部通过，failure/error/skipped均0；Debug/androidTest/Release及lint成功（0错误/29警告）。真实1.1.2覆盖升级核对16张表所有字段和自增序列一致，历史DMY设置原样保留。已于2026-10-19T00:54:02+08:00公开发布，已验收源码`86e228c3d7b0feb50e9a6afd31712cb13904ac32`直接推送main，公开APK及校验文件回下载一致；[验证汇总](docs/evidence/settings-polish-1.1.3-validation.json)，本轮验证见[设置改版验收](docs/settings-polish-acceptance.md)。[发布回执](docs/evidence/settings-polish-1.1.3-publication.json)。

[下载日子芽1.1.3简洁设置预览APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.1.3-rizhiya-preview-20261019/rizhiya-1.1.3-settings-polish-preview.apk) · [Release说明](https://github.com/LieerTsuki/Daybud/releases/tag/v1.1.3-rizhiya-preview-20261019)

## 历史订阅账单 · 1.1.2预览版已发布

本轮将订阅页改为简洁费用手账，新建固定人民币，移除CSV、币种选择、分类、备注及提前提醒选项；统计改为可切换月份的账单，按日期查看预计扣费，实际记录单独显示。所有有效订阅统一在设备当地扣费当天09:00提醒，保留设置总开关与系统权限要求。

当前1.1.2/code9，Room7和format3/schema7备份保持；旧外币金额、隐藏字段和旧提醒数据保留。本轮1206项JVM/107套件、74项设备测试/27套件全部通过，Debug/androidTest/Release构建及lint成功（0错误/29警告），实际同签名升级和09:00通知已核对。已于2026-10-18T23:56:24+08:00公开发布，源码`0bc4d0661b5731e924893a087cd5b33f9effc167`直接推送main；公开APK与校验文件回下载一致。[本轮验收与截图](docs/subscription-bills-acceptance.md) · [验证汇总](docs/evidence/subscription-bills-1.1.2-validation.json)。 [发布回执](docs/evidence/subscription-bills-1.1.2-publication.json)。

[下载日子芽1.1.2订阅账单预览APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.1.2-rizhiya-preview-20261018/rizhiya-1.1.2-subscription-bills-preview.apk) · [Release说明](https://github.com/LieerTsuki/Daybud/releases/tag/v1.1.2-rizhiya-preview-20261018)

## 历史互动森林 · 1.1.1预览版已发布

所有任务的种子、成长树和历代成树共同种在一片连续的卡通森林里，点击树木后打开该任务的浇水面板；每次打卡获得本任务1滴水，20次浇水成熟，成树保留后可再种。森林支持拖动、双指缩放、缩放/复位按钮和树木查找，沿用奶油白、薄荷绿与珊瑚色的日子芽风格。森林优先展示，原任务统计与月历保留。

1.1.1/code8/Room7完成1194项JVM/104套件和73项API35设备测试（均0 failure/error/skipped），Debug/androidTest/Release构建及lint核对通过（0 error/29 warning）。真实1.1.0同签名覆盖升级保留16张表的全部字段与自增序列，备份格式保持format3/schema7。截图、实际APK摘要、失败轮修正与限制见[互动森林验收](docs/interactive-forest-acceptance.md)。已于2026-10-18T13:44:10+08:00公开发布，源码`8cdf1c25800a8758121363feb244ec9d29326dc0`已合并main；公开APK回下载字节数与SHA-256核对一致。[发布回执](docs/evidence/interactive-forest-1.1.1-publication.json)。

[下载日子芽1.1.1互动森林预览APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.1.1-rizhiya-preview-20261018/rizhiya-1.1.1-interactive-forest-preview.apk) · [Release说明](https://github.com/LieerTsuki/Daybud/releases/tag/v1.1.1-rizhiya-preview-20261018)

## 历史打卡林场 · 1.1.0预览版已发布

本轮将打卡彩色文字图标改为松树、银杏、樱花、枫树、橡树和白桦插画；新增/编辑选择树种。统计保留月历，以共享林场取代53周热力图。每条打卡获得本任务1滴水，需手动浇给本任务的树；20次浇水成熟后可再种一棵，成树保留。撤销打卡收回对应水滴或回退原树；普通编辑保留打卡与成长。删除任务会删除其全部打卡、水滴及树木，须明确确认。

已发布1.1.0/code7/Room7，6→7只新增树木与浇水关联表；历史打卡折算为未使用水滴。完整备份升级为[format3/schema7](docs/backup-format-v3.md)，包含林场，严格校验后导入旧V1/V2。新格式需使用新版恢复，旧版应用不支持。升级仍使用原安装身份与非破坏性迁移；本轮实际测试和交付状态见[林场验收](docs/habit-forest-acceptance.md)。该版历史下载保留在下方；当前新版入口见顶部1.1.3。

本轮1187项JVM、72项API35设备测试及1.0.3→1.1.0真实APK覆盖升级通过，Debug/androidTest/Release和lint完成（0 error/50 warning）。已按用户要求将源码[8bc71db](https://github.com/LieerTsuki/Daybud/commit/8bc71db5f0de03c3c46677a559ddf21139f219ed)快进合并main并推送GitHub；本机候选APK及完整交付归档路径、摘要、截图和核对记录见上述验收页。已于2026-10-17T21:19:20+08:00发布[1.1.0预览Release](https://github.com/LieerTsuki/Daybud/releases/tag/v1.1.0-rizhiya-preview-20261017)；公开APK回下载字节数与SHA-256一致，[发布核对](docs/evidence/habit-forest-1.1.0-publication.json)。

[下载日子芽 1.1.0 打卡林场预览 APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.1.0-rizhiya-preview-20261017/rizhiya-1.1.0-habit-forest-preview.apk) · [版本说明](https://github.com/LieerTsuki/Daybud/releases/tag/v1.1.0-rizhiya-preview-20261017) · [主分支代码](https://github.com/LieerTsuki/Daybud/tree/main)

> 历史1.0.3计划精简预览已于2026-10-16发布并合并main，源码`bc260a9c240b60b5354c1ce12591c6e1fb65a721`；APK为1.0.3/code6/schema6，沿用预览证书。[本轮验收与截图](docs/planner-polish-acceptance.md)。

> 历史1.0.2内嵌月历预览已于2026-10-16发布并合并main，源码`e6fdde5984ab61116ad6563a78c993a9c4ef20ee`；APK为1.0.2/code5/schema6，沿用预览证书。[本轮验收与截图](docs/inline-month-acceptance.md)。

> 历史1.0.1四象限预览已于2026-10-16发布，APK为1.0.1/code4/schema6，非调试预览证书构建；源码`1f68e3308464e6e664a590d6720c96600e0194b9`，已合并main，[PR #1](https://github.com/LieerTsuki/Daybud/pull/1)已合并。[本轮验收与发布记录](docs/plan-quadrants-acceptance.md)。

> 历史预览版本 `v1.0.0-rizhiya-preview-20261016`，于 2026-10-16T01:24:20+08:00 发布，对应已验收源码 [`4db24de`](https://github.com/LieerTsuki/Daybud/commit/4db24deb56d9562fede555d7d3aa7100f5013cee)。V1功能分支已合并到main；APK为1.0.0/code3/schema6，使用既有预览证书的非调试Release构建。历史0.1.1仍保留：[旧版下载](https://github.com/lieer-12/app/releases/tag/v0.1.1-rizhiya-preview-20261009)。发布/合并核对见[发布记录](docs/v1-publication.md)。

## 计划界面精简 · 1.0.3预览版已发布

本轮移除搜索和成功操作记录，月历周日首列、任务统一字号并保留单行象限配色，修复同月选日短暂隐藏月历。详情/编辑改为分组卡片和日期、时间选择器，上海时间不显示时区，仅展示有效安排、备注、重复与编辑闹钟开关。1.0.3/code6/schema6已完成1161项JVM、70项API35设备与覆盖升级验收，本轮结果见[验收记录](docs/planner-polish-acceptance.md)，该版已直接合并main并公开发布；当前顶部入口为1.1.3。

## 内嵌月历 · 1.0.2 预览版已发布

1.0.2将月历收进四象限页的下拉区域，只保留月视图；移除独立日历和计划统计界面。日格每条任务严格单行并按四象限配色，先缩字再省略，双击日期查看当天完整计划详情。安装包版本1.0.2/code5，schema6与备份兼容保持；该版已公开发布并合并main，发布时曾将顶部下载更新为1.0.2。本轮过程见[内嵌月历验收](docs/inline-month-acceptance.md)。

## 四象限计划 · 1.0.1 预览版已发布

1.0.1将计划首页改为四象限，每格可添加计划并手动选择归属，移除旧筛选条；月历直接显示每天的计划名称。旧优先级 HIGH／MEDIUM／LOW／NONE 依次对应四个象限，日期变化不自动改归属。安装包版本1.0.1/code4，仍为Room6及原备份格式。该版曾将顶部下载更新为1.0.1；对应验证与交付见[四象限改版验收](docs/plan-quadrants-acceptance.md)。

## V1 统一计划改版 · 1.0.0 预览版已发布

F 本轮已将源码提升至 **1.0.0 / code3 / Room6**，完成1122项JVM/92 suites和70项API35设备测试（均0 failure/error/skipped）、Debug/androidTest/Release构建，以及lint检查（0 error/47既有warning）。补齐真实计划操作、同ID日期转换、完成/撤销、统计/日历、严格新通知及草稿旋转设备闭环，并修正设备回归发现的问题。实际覆盖升级、备份往返、签名、性能和交付文件以[1.0.0交付验收](docs/v1-delivery-acceptance.md)及[交付说明](docs/v1-release-notes.md)为准。

以下分批记录保留各批当时状态；其中“版本暂留0.1.1”属于历史，该批源码为1.0.0。该批曾将顶部链接更新为1.0.0，main已合并V1；历史Release和旧APK继续保留。1.0.0候选使用既有预览证书，正式签名、真机、稳定60fps和商店分发资格仍需另行验收，不将模拟器通过扩大为这些结论。

第十九批 E6 源码接线验收通过（2026-10-15）：Room v6、计划/打卡/订阅/设置四入口、新版设置、V2 备份及统一提醒共用生产数据库单例。旧独立待办/日程正常写入与页面退役，历史迁移、V1备份验证和旧通知安全兼容保留，不使用 destructive migration。最终1122项JVM/92 suites强制重跑通过（0 failure/error/skipped），Debug重新打包、androidTest构建检查通过，重新生成的lint报告0 error/47 warning，独立源码复审无遗留问题。测试条数变化与旧用例移植/退役见[覆盖映射](docs/e6-test-coverage-mapping.md)，完整失败过程和限制见[验收记录](docs/v1-unified-plans-acceptance.md)。

E6历史边界（当时未发布）：源码应用版本仍暂留0.1.1/code2，main与顶部下载的0.1.1/schema5 APK不变。本批未执行设备测试、实际覆盖升级、真实文件提供方、Release或性能验收，F完成后再交付1.0.0/code3；不要将E6本地包当作已验收的手机升级包，不安装旧版来降级已升级的数据库。历史各批“未接生产”的说明是当时状态，不是当前源码状态。

E6前置基线修复（2026-10-15）：统计工作区测试在切页刷新尚未完成时立即查找结果，现以测试专用延迟调度稳定覆盖该窗口，等待新鲜读取就绪后执行原有断言。定向4项及全量1400项/120 suites通过，生产代码未变；这是测试同步修复，不是E6生产接入完成，过程见[验收记录](docs/v1-unified-plans-acceptance.md)。

第十八批E5已完成新版备份设置与导航保护的局部准备验收：接入已有V2文件与维护流程，提供旧V1转换预览、独立保护备份及最终确认；备份期间保护切页与偏好写入，取消保留草稿，提交结果未知时禁止重复操作。新增32项，最终1400项JVM/120 suites强制重跑通过（0 failure/error/skipped），Debug重新打包、androidTest编译/构建检查通过，lint0 error/55既有warning；独立审查发现的3处时序问题均经回归修正并复审关闭。详见[验收记录](docs/v1-unified-plans-acceptance.md)。生产0.1.1/code2/schema5、main和下载APK不变，仍不是手机已启用V1；生产原子接线及F设备/覆盖升级/Release/性能验收尚未完成。

第十七批 E4 已完成统一导航与新版设置的局部准备验收（2026-10-14）：四个真实页面「计划 / 打卡 / 订阅 / 设置」显式绑定持有的模块所有者，切页保护计划草稿、保留日历选择；新计划通知严格验证并等待草稿决策，旧通知只回首页提示失效，不使用旧 ID 定位新记录。新版设置通过独立领域接口与完整 v6 Room 装置读取/更新，只显示计划与订阅提醒，保留主题、日期、币种及默认提醒日；提交后取消/提醒失败不会重复保存。TDD 新增51项/5 suites，最终1368项 JVM/116 suites强制重跑通过（0 failure/error/skipped），Debug重新打包、androidTest编译/构建检查、lint0 error/55既有warning；独立复审无遗留问题，详情见[验收记录](docs/v1-unified-plans-acceptance.md)。这仍是准备代码：生产0.1.1/code2/schema5、main和下载APK不变，新版备份设置、生产原子接线及设备/覆盖升级/Release/性能验收尚未完成；不是手机已经启用V1四入口。

已确认以统一计划模型和表替代独立待办 / 日程：无日期归待办，有日期归日程；添加截止日期更新同一条记录，两类均可完成 / 撤销，重复日程完成表示整个系列结束。目标导航为「计划 / 打卡 / 订阅 / 设置」，目标版本 1.0.0 / versionCode 3。

V1功能分支已合并到 `main`，当前源码和顶部已发布APK均为1.0.0/code3/schema6。A至E6历史结果见下文，F本轮实测结果和未验收限制见[交付验收](docs/v1-delivery-acceptance.md)，本次公开发布/合并见[发布记录](docs/v1-publication.md)。不提供destructive migration或降级安装方案。

第一批领域底座局部验收通过：按 TDD 实现分类、完成 / 撤销、日期转换及全库统计规则，新增 34 项测试；该批 703 项 JVM / 70 suites 使用 `--rerun` 全量重跑通过，Debug 构建检查成功，lint 0 error / 55 warning。独立审查问题已关闭；未运行新迁移 / 新 UI 的设备验收，不把领域测试当作整个 V1 已完成。

第二批 B1 局部验收通过：共享转换器逐值保留旧字段，以来源区分同号待办/日程并映射父、标签及例外；历史空白标题和合法固定偏移时区保留，新建/编辑仍严格校验。按 TDD 新增 26 项测试；该批最终 729 项 JVM / 72 suites 强制重跑通过，Debug 构建检查通过，lint 0 error / 55 warning，独立审查意见已关闭。B1 不包含 Room 仓库/事务或 SQL 迁移，不能将 helper 测试当作迁移验收。

第三批 B2 局部验收通过（2026-10-11）：实现统一实体、关系 DAO、Flow 快照和事务 CRUD / 完成 / 撤销，添加日期保留身份，旧编辑不能覆盖最新完成状态或复活删除项，父删除只解绑子项；标签保留历史原始名称对应的 ID/颜色，坏日期/关系读取与原世代错误明确上报。按 TDD 新增 31 项测试；最终 760 项 JVM / 74 suites 强制重跑通过，Debug 重新打包成功，lint 0 error / 55 warning，独立审查意见已关闭。测试使用独立内存 Room fixture，包含取消和故障回滚，不是完整 v6 或设备升级验收；全局维护准入由后续用例层接线，SQL 迁移链与设备验证的 B3 结果见下文。

第四批 B3 局部验收通过（2026-10-11）：使用全部 12 个目标实体验证同一 SQL 迁移实现；旧来源分开映射、逐值保留 27 个计划字段及关联、合并提醒设置、世代严格推进一次，打卡/订阅等无关数据在迁移末尾再次核验。坏源、SQL 故障和旧表删除后的最终 schema 校验失败恢复原数据库；4→5→6 失败链恢复原始 v4。新增 15 项 JVM 和 10 项 API 35 专用模拟器设备测试；最终 775 项 JVM / 75 suites 强制重跑、Debug/androidTest 构建检查与 lint 通过（0 error / 55 warning），独立审查问题均已关闭。设备结果只覆盖本批指定类，未重跑旧 57 项用例。v1 缺少历史 schema 文件，测试沿用 v2 未变的三张实体重建，不虚称原始 v1 快照；未做大库性能/真机或新版生产覆盖安装验收。

第五批 C1 局部验收通过（2026-10-11）：统一点/日期截止、真实区间、全天闭区间及系列完成标记；共享重复发生、窗口冲突和下一个未来提醒 trigger 计算。保留原锚点、月末/闰年、DST、取消例外与历史读取兼容，NONE/已完成不排提醒；不追补、不套固定两年搜索上限。计算预算严格限 1–10,000，取消及窗口外预选也计入，耗尽/坏数据/运算溢出显式失败，不返回假空结果。新增 69 项 JVM 测试，最终 844 项 / 78 suites 强制重跑通过，Debug 重新打包成功，lint 0 error / 55 warning，独立审查意见均关闭。本批未运行设备或 Android 闹钟验收；冲突只证明指定窗口，提醒函数不是完整投递授权。下一批 C2 接入维护用例、新提醒身份/排程/Receiver/校准的隔离实现；备份、统一 UI、生产接线和正式版本仍待后续批次。

第六批 C2a 局部验收通过（2026-10-11）：统一保存、完成/撤销、删除和例外用例，按原世代维护准入与共享模块锁串行；真实 Room 写入失败/提交前取消回滚，提交后先交接持久化回执，再同步提醒。提醒失败与“未保存”分离，只重试副作用、不重复插入；实际 Job 取消的已提交异常在外层维护作用域退出后仍携带回执。完整 Long 身份、世代、发生日期与毫秒 trigger 组成严格 canonical URI，解析不代表投递授权。新增 43 项 JVM（用例 13、真实 Room 20、值身份 10），最终 887 项 / 81 suites 强制重跑通过，Debug 重新打包成功，lint 0 error / 55 warning，计划/代码复审意见全部关闭。当前提醒 port 只定义协议，测试记录当前资格，不是 Android 闹钟实现；本批无设备/UI/Release/性能验收。下一批 C2b 实现隔离 Android 排程/Receiver/资格与去重，C2c 完成旧身份清理/校准；真实 UI 回执消费及生产接线仍归 E，整个 C2 和 V1 未完成。

第七批 C2b 局部验收通过（2026-10-12）：Room 保存完整提醒身份及持久决定，严格广播/点击元数据与当前 Room 资格交叉校验；隔离实现 Android 闹钟排程、权限兜底、通知清理和 Receiver 异步投递。完成/删除/改期/开关/例外重算当前事实，重复、旧世代、篡改、提前或过期广播不能消费；读取/系统故障及取消不伪装成功。候选在挂起读取后取时钟，安装前再次拒绝过去 trigger；可观察通知阻止时精确撤本次卡片、保留 pending。新增 56 项 JVM（协议/资格 8、真实 Room/系统服务 Robolectric 集成 48），最终 943 项 / 83 suites 强制重跑无 failure / error / skipped，Debug 为缓存构建检查，lint 测试源重新分析通过（0 error / 55 既有 warning），独立审查意见关闭。技术表仅在隔离 13 实体装置使用，完整升级链及旧身份清理/统一校准留 C2c，生产接线归 E；本批没有设备/UI/Release/性能验收。通知提交不等于用户已看到，notify 与 Room 标记间崩溃可能再次提示，但使用同 tag 替换同一张卡片；不承诺跨系统 exactly-once。整个 C2、后续备份/页面/升级交付及 V1 仍未完成，APK 不变。

第八批 C2c1 局部验收通过（2026-10-12）：完整 v6 隔离目标由 12 扩展为 13 实体，唯一迁移 SQL 与 JVM/Android Room delegate 纳入同一提醒技术表；历史 1–5 升级链与新安装均验证空库存及完整五列/主键/无业务外键。真实库存 CAS、Long 极值、文件重开和删除业务项后保留清理身份通过；建表中途及世代推进/旧表删除后的库存 schema 故障恢复完整 v4/v5。新增 5 项 JVM、4 项设备用例；最终 948 项 JVM / 83 suites 全量重跑和专用 API35 模拟器指定类 14 项测试全部通过，Debug/androidTest 重新打包、lint 三类源重新分析通过（0 error / 55 既有 warning）。独立审查问题关闭，目标 fixture 的 6.json 更新，生产 v5 与历史 2–5 快照不变。v1 仍由未变 v2 实体合成重建，不声称有原始 v1 asset；没有真机、生产用户库覆盖升级、UI、Release 或性能验收。C2c2 旧身份清理/统一校准、D 备份、E 页面/生产接线和 F 交付仍待完成，本子批不是整个 V1 发布。

第九批 C2c2 局部验收通过（2026-10-13）：完整隔离 v6 目标库中的旧身份清理、计划/订阅统一校准、精确维护清理及独立后台 Worker 已实现。严格通知归属、安装/到达/决定状态、时钟回拨与有限重复边界、失败后未来修复、并发创建及维护排空通过；后台运行期间的新请求实际排队执行，并重新读取当前 Room。新增53项测试，最终1001项 JVM /87 suites 强制重跑通过，Debug/androidTest重新打包，lint三类源重新分析通过（0 error /55既有 warning），独立复审问题关闭。生产 Hilt/Manifest/Boot/旧 Worker/页面尚未切换，当前手机APK仍为0.1.1、不包含统一计划；本批没有设备执行、真机/UI/Release/性能验收，不复用第八批设备数字。下一批D完成新版备份与维护适配，E才启用页面及生产v6，F交付版本；不是整个V1完成。详情见[分批验收](https://github.com/lieer-12/app/blob/feat/v1-unified-plans/docs/v1-unified-plans-acceptance.md)。

第十批 D1 局部验收通过（2026-10-13）：实现隔离formatVersion2/schemaVersion6备份raw契约、十业务表白名单及新六字段设置、严格校验与canonical UTF8/SHA256编解码。原文/Long/固定偏移/非活动字段/历史完成组合保留，主键/来源唯一/引用/父树/类型/版本/重复键/hash/64MiB和100k总行数检查通过；技术库存和本机世代不进入文件。新增29项测试，最终1030项 JVM /89 suites强制重跑通过，Debug及设备测试包构建检查、lint通过（0 error /55既有warning），复审意见关闭。当前生产仍导出v1，旧v1codec默认和历史格式文档不变；本批无实际文件导入/Room恢复/设备/UI/Release/性能验收。下一批D2为旧v1严格校验转换与版本分派，完整D及V1仍未完成。见[v2格式说明](docs/backup-format-v2.md)及[分批验收](docs/v1-unified-plans-acceptance.md)。

第十一批 D2 局部验收通过（2026-10-13）：实现隔离的v1/v2版本分派和旧备份无损转换；先验证旧文件原hash/字段/关联，再复用数据库迁移的字段和关系规则，校验目标v2。双来源同号/Long极值/父子标签例外、自定义重复/非活动字段、历史空标题/固定偏移、原元数据和设置保留；转换后的来源映射计入100k行及完整64MiB字节上限，超限拒绝不截断。返回真实十表数量及旧待办缺失时区的来源说明，v2直读不伪造说明，导出只产生v2；默认生产v1路径未变。新增31项测试，最终1061项 JVM /91 suites强制重跑通过，Debug/设备测试包构建检查通过，lint0 error/55既有warning，独立复审无遗留问题。本批无真实文件/保护备份/Room恢复/设备/UI/Release/性能验收，仍是受限内存编解码。下一批D3为目标库快照及原子恢复，E生产启用和F版本交付仍待完成，不等于V1发布。见[v2兼容说明](docs/backup-format-v2.md)与[分批验收](docs/v1-unified-plans-acceptance.md)。

第十二批 D3 局部验收通过（2026-10-13）：完整隔离v6 Room目标库的十表原值快照、设置读取和原子恢复/业务清空已实现；恢复保留历史字段及前向父引用，清空保留设置，技术提醒库存和自增高水位不重置。恢复前拒绝六张自增表MAX主键及目标序列耗尽，其他Long极值不受影响，极端库仍可只读救援导出。世代只推进一次，故障、悄改及提交前取消完整回滚。新增35项真实Room测试，并用实质RED/GREEN修复旧日程真实线程测试过早resetMain的清理竞态，新增1项清理回归；1097项 JVM /93 suites强制重跑通过，Debug/androidTest构建检查及lint通过（0 error/55既有warning），独立复审问题关闭。运行中仍观察到归属待定位的Robolectric SQLite资源警告，未宣称消除；重复复验及全部过程见[验收记录](docs/v1-unified-plans-acceptance.md)。本批无实际文件/保护备份/设备/UI/Release/性能或用户库操作；生产仍0.1.1/schema5，main和发布APK不变。下一批D4处理文件与维护流程，E整体启用、F版本交付，完整D和V1尚未完成。见[v2持久化边界](docs/backup-format-v2.md)。

第十三批 D4 局部验收通过（2026-10-13）：实现隔离的新版文件/维护流程：v2导出关闭后重读并匹配完整文档，v1/v2受限私有暂存预览，原库保护备份和独立最终确认，目标库原子恢复/清空及提交后精确提醒清理。SQL闹钟库存和系统卡片分开捕获，系统枚举不占SQL事务；取消前后与丢返回/异常世代区分，不把未完成CAS当作提交证据、不重放写入。新增42项测试，包含真实私有文件/完整Room/v1转换/8秒校准超时及现有统一提醒组件集成；77项D4+D3组合和全量1139项 JVM /96 suites强制重跑通过，Debug/androidTest构建检查、lint0 error/55既有warning，独立复审全部关闭。提交前重复验证及失败诊断见[分批验收](docs/v1-unified-plans-acceptance.md)。仍有归属待定位的Robolectric SQLite资源警告；没有本轮设备/SAF或云提供方/真机/Release/大库性能验收，不同URI的文件别名仍是已知限制。生产Hilt/页面/schema5/0.1.1/code2/main与APK不变，下一批E实现统一页面并分批完成生产接线，F再交付版本与升级验收。见[v2流程边界](docs/backup-format-v2.md)与[文件安全说明](docs/backup-file-safety.md)。

第十四批 E1 局部验收通过（2026-10-13）：统一计划列表、分类/状态/日期/标签/优先级/搜索/稳定排序、完成/撤销及原生手账主题组件已实现；无日期待办不混入今日，重复与例外共用发生规则。StateFlow 读写隔离维护和世代，旧页面回调固定渲染令牌，提交成功后提醒失败只重试提醒、不重复写入。新增52项查询/VM/Compose/完整Room测试，最终1191项 JVM /100 suites强制重跑通过，Debug/androidTest构建检查及lint通过（0 error/55既有warning），独立审查三项反馈均关闭。README、CHANGELOG、实施计划及[验收记录](docs/v1-unified-plans-acceptance.md)同步；资源关闭警告归属仍待定位。本批是可编译页面准备，尚未接生产Hilt/导航/v6，不开放未实装入口、不更新手机APK。下一批E2为详情/编辑与同身份待办↔日程转换；后续日历/统计、完整E启用、F版本/设备升级交付仍未完成。

第十五批 E2 局部验收通过（2026-10-14）：共用新建/详情/编辑、同身份待办↔日程转换、四时间模式、重复/提醒等真实配置、完成/撤销与确认删除已接入准备工作区，返回保留列表筛选。草稿保护、失效世代/旧确认回调拒绝、保存前冲突重查与锁内删除快照复核、提交后只重试提醒通过；历史字段保留，新编辑严格校验。新增84项/6 suites（草稿26、保存核查11、编辑VM25、编辑/详情Compose11、工作区Compose5、完整Room6），最终1275项 JVM /106 suites强制重跑、Debug/androidTest构建检查和lint通过（0 error/55既有warning）；独立审查反馈全部关闭，失败过程和资源警告见[验收记录](docs/v1-unified-plans-acceptance.md)。日期/时间目前为有格式提示的文本输入，冲突只检查明确有限窗口；设备/进程重建/Release/性能仍未验收。生产0.1.1/schema5/Hilt/根导航、main与顶部手机APK不变；下一批E3日历/全库统计，最终E统一生产切换与F版本升级交付尚未完成。

第十六批 E3 局部验收通过（2026-10-14）：准备工作区新增统一计划月/周/日日历及独立全库统计。日期截止/全天保留保存日期；区间按展示时区真实跨日、结束排他；重复取消过滤，已完成系列只保留一条完成标记。统计独立观察全库，今日完成含长期待办，今日日程按真实窗口/计划身份去重、不计系列完成标记，不受列表搜索/状态筛选影响；前台恢复及可见时每60秒重新计算。日历打开同一计划详情，返回保留日期/视图，编辑时不开放绕过草稿保护的页面捷径。TDD新增42项/5 suites，最终1317项 JVM/111 suites强制重跑通过（0 failure/error/skipped），Debug重新打包、androidTest编译/构建检查与lint通过（0 error/55既有warning），独立复审无遗留问题。页面加载同步问题、RED/GREEN和资源警告见[分批验收](docs/v1-unified-plans-acceptance.md)。生产0.1.1/schema5/Hilt/根导航、main及手机APK不变，V1完整接线/设备升级交付仍后续；不是小时拖拽时间轴，跨时区日期格与今日真实窗口统计可以不同。没有设备/TalkBack实机/进程重建/Release/性能验收，归属待定位的Robolectric SQLite资源警告未宣称消除。

## 名称与图标 · 0.1.1 预览版

新名称「日子芽」寓意把生活中的小事整理好，让每一天慢慢长出新芽。图标是圆润的笑脸手账、两片新芽与珊瑚色书签，使用与 UI 一致的奶油白、薄荷绿和暖墨绿。

图像生成服务返回 429 后，用户明确选择手工绘制 Android 原生矢量方案；本图标不是 AI 生成图片。提供自适应、圆形入口和 Android 13+ 单色主题图层。可查看[圆角预览](https://github.com/lieer-12/app/blob/main/docs/assets/rizhiya-icon-rounded.png)与[圆形预览](https://github.com/lieer-12/app/blob/main/docs/assets/rizhiya-icon-circle.png)；预览是裁剪示例，实际外框由手机桌面的图标形状与主题决定，不能保证所有设备一致。

已提交并推送名称与图标改动，发布 0.1.1 / versionCode 2 预览 APK；applicationId、Debug 签名、数据库 schema 和业务逻辑不变。旧 0.1.0 Release 保留，不覆盖其安装包；当时顶部下载入口指向0.1.1；现在已更新为1.0.0。验证结果与覆盖安装边界见[名称与图标验收](https://github.com/lieer-12/app/blob/main/docs/branding-acceptance.md)。

本批全量验证与 2026-10-09 推送前复验：669 项 JVM / 68 套件、57 项 API 35 设备测试 / 24 套件，均无 failure / error / skipped；两类测试均使用 `--rerun` 重新执行，Debug/Release 构建检查通过，lint 0 error / 55 warning。在项目专用模拟器覆盖安装后，一条旧版合成待办仍可见；不将此局部检查等同于全部真实数据或真机验收。

以下能力清单、UI第一批和Phase 5章节描述已发布0.1.1及其历史基线，不是当前V1分支的入口/格式清单。V1当前状态与未验收项以上方F及发布记录为准。

0.1.1实现范围：Phase 1（待办事项核心闭环）、Phase 2（日程安排）、Phase 3（习惯打卡）、Phase 4（订阅费用管理），以及 Phase 5 的设置和完整备份维护。四个业务模块共享同一个 Room 数据库，底部导航另保留设置页。Phase 5 功能回归通过，稳定 60 fps / 真机等未验收项仍明确保留；不代表原文档全部高级功能和发布要求均已完成。

已包含的目标能力：

- Kotlin + Jetpack Compose + Material 3
- MVVM + `data / domain / ui` 分层
- Room 本地数据库与待办/标签关系
- 待办创建、编辑、删除、完成/取消、优先级、标签、搜索和基础日期筛选
- 今日完成数、待完成数和完成率
- 本地截止提醒、通知权限处理和确定性提醒 ID
- Room 持久化的跟随系统/浅色/深色主题选择
- 日期展示格式，以及只影响新建订阅的默认币种与提前 1/3/7 天提醒组合
- 日程月/周/日视图、日程 CRUD、重复规则、冲突确认与本地提醒
- 习惯 CRUD、每日打卡/撤销、按月打卡日历、频率感知的连续统计和最近 53 周热力图
- 订阅 CRUD、取消/恢复、独立的手工实际扣费记录、周/月/季/年周期预测
- CNY 本月预计/实际费用、最近 6 个月对比、分类占比和周期对比图（原生 Canvas，带文字汇总）
- 扣费前 1/3/7 天本地 09:00 提醒、取消/删除清理、重启及后台校准
- 隐私确认后通过系统文件选择器导出 UTF-8 BOM CSV，包含全部币种

Phase 5 功能回归通过：完整 JSON 导出/预览/恢复，保护备份与独立最终确认，原子业务清空（保留设置），全应用维护会话/本机世代保护，三类提醒开关及恢复校准，权限状态刷新和关于/离线第三方许可。顶层导航限制重复条目、保留草稿/子页，维护及备份锁涵盖顶部入口、返回键与通知跳转。2026-10-08 已推送相关功能与第一批 UI，并发布预览 APK；这不意味着 Phase 5 性能已验收。各批历史结果见 [Phase 5 验收记录](https://github.com/lieer-12/app/blob/main/docs/phase5-acceptance.md)。

仍未实现：打卡提醒、习惯暂停和归档，以及以前阶段报告明确保留的高级功能；没有这些功能的假按钮、假数据或空白占位 Tab。

## UI 改版

已批准卡通手账方向，第一批范围为 Material 3 预设浅/深色主题、系统字体层级、圆角底部导航和待办页面。保留原有五个入口、真实统计、搜索筛选和数据维护保护，不插入示例业务数据。清单插画使用小型原生 Canvas，不将 AI 预览整图当作界面；完成状态仅有轻量短时颜色反馈。

第一批已完成并发布预览版，覆盖读屏进度、筛选恢复及 320dp / 200% 字体；浅深色实际模拟器截图已检查。打卡、日程、订阅、设置的整页视觉优化仍待后续批次；深色模式的系统三键导航区域仍呈浅灰色。本次 UI 的稳定 60 fps / 真机性能仍未验收。详情见 [UI 验收记录](https://github.com/lieer-12/app/blob/main/docs/ui-redesign-acceptance.md)。

## 已有验证与待验收项

以下为已发布 0.1.0 预览源码的历史验证证据；0.1.1 名称/图标预览版的本轮结果单独列于上文与[名称与图标验收](https://github.com/lieer-12/app/blob/main/docs/branding-acceptance.md)，不混用日期和范围。

- 第一批 UI 的全量验证：665 项 JVM / 67 套件、54 项 API 35 设备测试 / 23 套件，均无 failure / error / skipped；Debug/Release 构建通过，lint 0 error / 57 warning。
- 随后的推送前复验：665 项 JVM 使用 `--rerun` 重新执行通过；构建与 lint 通过。该轮设备复验因模拟器实例冲突未执行，不把此前 54 项结果当作重新运行。
- 历史 Phase 5 非调试模拟器冷启动 545–625 ms，切页卡顿率汇总 4.49%；这不是新版 UI 性能结论，也不证明稳定 60 fps。真机、完整适配和发布验收待完成，方法与限制见 [性能测量](https://github.com/lieer-12/app/blob/main/docs/phase5-performance.md)。

## 手机安装预览版

最低支持 Android 8.0（API 26），无需 Android Studio。

1. 用手机浏览器点击 [下载1.0.0预览Release APK（约12.44MB）](https://github.com/LieerTsuki/Daybud/releases/download/v1.0.0-rizhiya-preview-20261016/rizhiya-1.0.0-preview-release.apk)，也可在[预览版页面](https://github.com/LieerTsuki/Daybud/releases/tag/v1.0.0-rizhiya-preview-20261016)的Assets中下载。
2. 下载后打开 APK，按系统提示允许当前浏览器/文件管理器安装来自此来源的应用，然后安装。
3. 安装完成后打开应用；可关闭刚才授予的安装权限。不要下载 Source code ZIP 或未签名 Release APK，它们不能作为此预览安装包使用。

此包为既有预览证书签署的非debuggable Release构建，不是应用商店正式签名版。文件大小12,435,895字节；SHA-256：`acd7349f8445500fd6928952c8d602db89c0a92eb295a769f7ee0b410d34db94`。真机、稳定60fps及商店资格仍未验收。

用手机浏览器打开 APK，按系统提示仅允许该浏览器/文件管理器安装来自此来源的应用，安装后可关闭此权限。无需关闭 Play Protect 等全局安全保护；不同厂商菜单名称可能不同，参见 [Android 安装说明](https://support.google.com/pixelphone/answer/7391672?hl=zh-Hans)。GitHub 访问不畅时，可在电脑下载 APK 后用 USB 文件传输到手机安装，无需开启 USB 调试。

如果手机已有旧版，先在设置中导出完整备份，再尝试覆盖安装。出现签名冲突时不要直接卸载或清除数据；先保留备份并核对版本。调试签名与未来正式签名可能不同，不保证届时能直接覆盖升级。预览包不含个人业务数据；数据保存在安装设备的本地 Room 数据库。

## 完整备份与数据维护

当前1.0.0只导出V2，并严格校验后兼容导入旧V1。真实系统选择器、保护备份和恢复结果见[新版格式与边界](docs/backup-format-v2.md)与[交付验收](docs/v1-delivery-acceptance.md)。下方Phase5章节保留历史V1契约，不应混用字段。

- 设置 → 导出完整备份：先确认明文隐私说明，再选择系统文档保存位置。写入、关闭并重新读取校验一致后才报告成功。
- 设置 → 从备份恢复：受限复制来源到私有临时区，验证版本、SHA-256、全部业务表字段及关联（V1先按旧契约验证再转换）；展示真实数量，确认替换语义；必须另外保存并验证当前数据的保护备份，再进行独立最终确认。
- 支持JSON V2/schema6及经严格校验转换的旧V1/schema5，完整替换、不合并、不导入本机世代；恢复包含设置，清空业务数据则保留设置。使用单个 Room 事务，不使用 destructive migration。
- 取消或事务失败保留原数据和未保存草稿；提交成功后旧草稿/旧通知/旧文件回调不能作用于恢复后的同 ID 记录。进程重建不自动重放未完成操作。
- Room 提交和 Android 闹钟不是同一事务；提醒处理失败时明确区分“数据已提交”，并请求从当前 Room 数据重新校准。权限拒绝不影响离线 CRUD。
- 备份为明文；SHA-256 不是加密或身份认证。默认上限 64 MiB / 100,000 行；峰值内存可能高于文件大小。文件提供方失败可能留下输出残片，应用不会删除用户选择的文件。
- 当前仅核对来源与保护备份的确切 URI；不同 URI 指向同一底层文件的提供方别名尚未做通用判定。建议使用新文件名保存保护备份。

## 构建环境

- Android SDK Platform 35
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- JDK 25（Gradle 运行时）；Android 字节码目标仍为 Java/Kotlin 17，以保持设备兼容性

先准备 Android SDK、JDK 25 与 Gradle，再从主分支获取源码：

```powershell
git clone --branch main https://github.com/lieer-12/app.git
cd app
```

本项目尚未提供 Gradle Wrapper，以下命令依赖已安装的 Gradle 9.4.1；`.tools` 和 `local.properties` 是本机文件，不随 Git 克隆。

```powershell
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

本项目已在 Android SDK Platform 35、Gradle 9.4.1 与 JDK 25 环境下完成 debug APK 构建。项目使用项目内 Gradle 安装时，可运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-phase4-structure.ps1
.\.tools\gradle-9.4.1\bin\gradle.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

请在 `local.properties` 配置本机 `sdk.dir`，并令 `JAVA_HOME` 指向 JDK 25。首次构建需要下载依赖；缓存齐全后可添加 `--offline`。旧 Phase 结构脚本是对应历史阶段的边界检查，当前代码使用 Phase 4 脚本。只连接独立测试模拟器后可运行 `gradle :app:connectedDebugAndroidTest`；该任务会卸载测试应用，不得在有个人业务数据的手机或用户原会话上运行。编译测试 APK 不等于设备测试已通过。

Robolectric 4.17 在主机上执行真实 Room DAO/仓库和 Compose 交互，不替代真机测试。首次运行还需下载 Android 28、31、33 测试运行时；默认缓存于用户目录的 `.m2/repository`，与 Gradle 依赖缓存分开。只有两类缓存均齐全时才能完整离线测试，Gradle 的 `--offline` 本身不会阻止 Robolectric 首次下载运行时。Robolectric 与 Compose 测试依赖仅用于测试，不加入正式 APK。

## 权限与提醒行为

- Android 13+ 需要用户授予通知权限；拒绝后仍可使用待办 CRUD。
- 目标时间前 15 分钟创建本地提醒；精确闹钟权限不可用时使用安全的非精确兜底。
- 完成或删除待办时取消对应提醒。
- 待办截止日期改为过去时也取消旧闹钟；接收前核对 Room 中的最新完成状态、截止时间和标题。缺少校验信息的旧广播、已删除/已完成/已改期/过期或提前到达的广播不发通知。
- 待办通知可打开被搜索/筛选隐藏的任务；保存或关闭现有草稿后再查看通知目标。连续点击同一任务使用独立事件，已消费事件不因旋转重放。
- 待办数据写入成功而闹钟服务失败时，明确提示“已保存但提醒设置失败”，不保留可重复插入的新建草稿。数据库写入失败则保留草稿并在编辑框内显示错误。
- 日程编辑保留创建时间、时区、未展示的自定义重复参数和已有例外记录；已删除日程的旧编辑表单不能复活记录。非空提醒分钟必须为非负整数，留空关闭提醒。
- 日程提交期间保护草稿并拒绝重复保存；数据库成功而提醒失败时提示“已保存/已删除”，数据库失败保留草稿。修改表单或冲突记录变化后须重新确认。
- 日程通知使用可消费事件及 Activity 级 ViewModel：跨模块返回、旋转和连续点击不覆盖草稿，保存或关闭后再查看等待目标。不承诺系统杀进程后的草稿恢复。
- 订阅提醒支持多选提前 1/3/7 天，本地时间 09:00；精确闹钟权限不可用时使用非精确兜底，可能延迟。
- 编辑订阅不改变取消/恢复状态；取消保留实际扣费历史，删除会清除该订阅及关联扣费记录和提醒。
- 金额保存为最小货币单位 `Long`。统计仅汇总 CNY，其他币种可管理和导出，不做汇率换算。实际扣费须手工记录，不会更新预测锚点。
- 读取失败时禁用 CSV 导出；提醒通知到达正在编辑的页面时，会保留草稿并明确提示，保存或关闭当前表单后再打开目标详情。

## 阶段状态

- Phase 1：待办核心闭环已实现，已纳入后续可靠性与通知自动回归；真实设备提醒验收和高级功能仍有边界。
- Phase 2：日程核心模块已实现，后续补充数据完整性与通知回归；完整重复规则、时间轴交互等限制见下文。
- Phase 3：打卡核心模块已实现，后续修复历史记录保护与打卡资格，并纳入 Room/界面回归；打卡提醒、暂停与归档尚未实现。
- Phase 4：订阅模块已实现；验证结果、已完成与待验收事项见 [Phase 4 验收记录](https://github.com/lieer-12/app/blob/main/docs/phase4-acceptance.md)。
- Phase 5：约定功能开发及纳入范围的 API 35 回归通过；已随预览版推送，稳定 60 fps / 真机与正式发布验收尚未完成，见 [Phase 5 验收记录](https://github.com/lieer-12/app/blob/main/docs/phase5-acceptance.md)。
- UI 改版：第一批主题、导航与待办完成；其余模块整页改版尚未完成，不增加假数据或未实现入口。

## Phase 5 设置行为

- 数据库 v5 显式新增设置和本机维护元数据表；保留 v1 到 v5 的迁移链，不重建或改写既有十张业务表。
- 设置读取失败时不可编辑；保存失败保留已保存值。“重新读取设置”只刷新保存状态，失败的选择需要再次选择，不声称自动重试保存。
- 日期格式只影响四模块的完整日期展示；月标题、时间与日历中的日号保持原语义。订阅表单日期输入及 CSV 文件仍为 ISO `YYYY-MM-DD`，存储与计费/打卡计算不变。
- 默认币种 CNY、默认订阅提醒为空集，保持旧新建行为。只用于之后新建的订阅；已有订阅和手工扣费的币种、金额、提醒不会被覆盖，统计仍仅汇总 CNY。
- 新建订阅读取偏好期间禁用保存、币种和提醒编辑；读取失败保留输入并要求关闭后重试，不静默使用默认值保存。
- 三类提醒开关已接入保存、后台校准和投递路径；关闭时取消模块闹钟及通知，开启时从当前 Room 重建。开关不等于系统授权，返回前台或权限申请结束时重新读取真实权限状态。习惯提醒未实现，不展示占位开关。
- Android 仪器测试的 Room schema 读取依赖 serialization 1.8.1；BOM 同时对齐主 APK 的 Navigation 间接依赖，避免测试 APK JSON 与主 APK core 混用版本。没有启用新的序列化编译插件。

## Phase 5 备份格式与文件安全

- 已实现 Room 单事务完整快照，以及 format v1 / schema v5 的 UTF-8 JSON 编解码与严格校验；包含十张业务表全部 75 字段和用户设置，保留 Long 精度、原始文本、关联及历史记录。
- 校验覆盖重复 JSON 字段、缺失/未知表或字段、版本、类型、日期/时区、SHA-256、唯一键、孤儿关联及待办父子环；超出 64 MiB 或合计 100,000 业务行时明确拒绝，不截断、不修复原库。
- 文件格式和校验规则见 [备份格式 v1](https://github.com/lieer-12/app/blob/main/docs/backup-format-v1.md)。SHA-256 不是加密或来源认证，文件是明文个人数据。
- Android 文件流导出关闭后重新读取、完整校验并比对本次文档；输入按实际字节限流，先复制到私有临时区再校验，在成功、失败和取消时只清理自己创建的临时文件。文件 IO 不持有普通数据库维护许可。
- 设置页已接线系统文件选择、真实预览、保护备份、独立最终确认与原子恢复/清空。应用启动只清理本应用已失去所有者的数字命名临时文件；同一进程重复初始化不删活跃输入，进程重建不重放待确认操作。测试层级及实际未验收边界见 [文件安全契约](https://github.com/lieer-12/app/blob/main/docs/backup-file-safety.md)。

## Phase 5 维护保护

- 已实现全局维护协调器：冻结后拒绝新操作，等待已有许可结束；维护会话不持有 Mutex 等待用户选择文件或确认。取消/失败可安全释放，外来、已失效会话和重复获取许可会被拒绝。
- 世代仓库提供严格读取及 Room 原子事务：业务变更与世代推进一起提交，写入失败或提交前取消一起回滚；旧令牌不能修改已更换的同 ID 数据。缺失/损坏元数据及 Long 上限明确报错，不静默归零。
- 数据库由应用 Hilt 单例通过创建工厂及 v5 初始化回调打开；后台 Worker 取得同一组仓库和维护协调器，不再自行开库或关闭应用数据库。只在新建/显式升级时初始化，普通设置读取/更新不补写维护世代。
- 已接入四个业务模块与设置 ViewModel：页面快照/草稿携带读取时的世代，真实写入及 Main 结果发布使用全局许可；维护中拒绝新写入，取消保留草稿，成功推进世代使旧草稿及旧页面回调失效。设置本地币种草稿随世代变化重置。
- 读取世代失败时停止写入，不使用默认 0；习惯保存防重复提交，不复活已被删除的记录。设置失败读取/重试、事务内提醒天数组合和延迟观察语义继续保留。
- 设置观察、有限读取及更新均不补建缺失默认行；默认值只由数据库新建/迁移产生，缺失设置明确报错。仅拒绝的读取可等待维护结束后重新查询，写入仍不排队重放；旧错误回退不能禁用更换后的新页面。
- 待办、日程通知在派发时绑定原世代；订阅详情、手工扣费和 CSV 选择回调同时核对原世代/请求。CSV 选择关联跨重组/旋转保存；已经准入并开始写入的旧 CSV 文件可在维护后完成，但不会发布旧成功状态或修改新库。
- 第六批已完成 Activity 导航事件原世代、三类 Receiver 的到达/派发准入、Worker 冻结重试及启动/系统事件校准的局部验收。导航等待图初始化时不占用许可，实际切换在原世代许可内执行，并把原世代交给业务模块；迟到的中间世代观察不会误取消更新世代通知。即时校准串行保留后续请求，每轮重新读取当前库；队列代价及依赖限制见维护契约。
- 三类闹钟、广播和通知点击源头携带原世代；未知/旧世代拒绝投递，不按到达时间重标新世代。日程投递重新读取真实记录、提醒开关、发生时间、例外及结束资格。维护成功、取消、失败退出均请求当前数据校准；非取消正常退出还立即校准，提交数据与提醒处理结果分别报告。
- 被维护挡住的一次性订阅/日程提醒保留匹配的操作提示，释放后重新核对当前 Room 资格再排程；临时闹钟服务或读取失败不提前抹掉待恢复发生记录。这些 SharedPreferences 只存 OS 操作索引，不保存业务内容或用户设置，不属于备份数据。
- 协调器接线契约见 [维护接线说明](https://github.com/lieer-12/app/blob/main/docs/maintenance-coordination.md)；当前分批验收见 [Phase 5 验收记录](https://github.com/lieer-12/app/blob/main/docs/phase5-acceptance.md)。

2026-10-02 第一批审计修复仅处理习惯数据完整性和打卡资格，见 [修复与回归验收记录](https://github.com/lieer-12/app/blob/main/docs/2026-10-02-habit-integrity-fixes.md)。编辑习惯保留历史打卡；开始日期之前或未选星期不允许新增打卡，但已有错误记录仍可撤销。当时数据库保持 v4；当前主分支已纳入 Phase 5 到 v5 的显式迁移，未使用破坏性迁移。旧版本已经误删的记录无法由本修复恢复。

第二批处理待办可靠性，历史结果见 [待办修复验收记录](https://github.com/lieer-12/app/blob/main/docs/2026-10-02-todo-reliability-fixes.md)。沿用第一批的 Robolectric 测试环境，本批未新增依赖、业务表或占位页面。

第三批处理日程数据完整性、编辑/冲突确认和通知草稿交接，见 [日程修复验收记录](https://github.com/lieer-12/app/blob/main/docs/2026-10-02-schedule-integrity-fixes.md)。该历史修复阶段数据库为 v4，无 schema 变更；不代表日程全部需求或设备验收完成，旧版本已经误删的例外记录无法自动恢复。

## 当前已知限制

- 尚未加入子任务、批量操作和手动拖拽排序，这些属于待办模块的后续完善项。
- 日程不含拖拽改期、双指缩放和“仅取消某次重复日程”的编辑入口。
- 验证证据同时参考 [Phase 5 历史验收](https://github.com/lieer-12/app/blob/main/docs/phase5-acceptance.md) 与 [UI 预览验收](https://github.com/lieer-12/app/blob/main/docs/ui-redesign-acceptance.md)，区分测试日期和范围。合成 Room、Downloads 文档、真实系统选择器取消测试分别说明，不把局部装置通过当作真实用户数据或云文件提供方验收。
- 订阅的通知点击、权限切换、CSV 文件提供方行为和深浅色交互仍需设备验收；没有接入银行卡/支付自动扣费或汇率服务。
- CSV 为消费数据明文文件，请选择可信保存位置，谨慎分享；不提供 CSV 导入，完整备份/恢复使用 JSON，不是 CSV。进程被系统终止时，进行中的导出不保证恢复。
- 待办修复不改变统计口径；日程的投递前数据库校验已在 Phase 5 接入，但全天提醒策略、完整重复提醒续排、重复发生冲突窗口、自定义重复参数 UI 和例外编辑展示仍有历史限制。周/日视图尚非按小时的时间轴；不能将局部通过等同于全部需求验收。
- 习惯整体今日进度、周/月打卡率和庆祝动画，订阅年度/同比及逐订阅占比，国际化、后台隐私遮罩和发布配置仍不完整；Gradle Wrapper、发布签名、SDK 发布要求、真机性能/适配及完整传递依赖许可证审计待完善。UI 卡通改版第一批已完成，其余页面与性能验收仍待后续工作。

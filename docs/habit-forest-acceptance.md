# 打卡林场改版验收

日期：2026-10-17；基线bec8c8a；功能分支feat/habit-forest。用户确认手动浇水方案后要求继续实现。1.1.0/code7/Room7已于2026-10-17T21:19:20+08:00公开发布预览版；历史1.0.3 Release和APK保留。

## 需求与实现

任务彩色文字图标改为六树种、五成长阶段的原生插画；编辑移除图标名称/颜色，替换为可视树种选择。保留名称、频率、目标、日期、备注及当前/最长连续与月历。53周热力图替换为汇集所有任务的林场。

每条成功打卡获得对应任务1滴水，仅能手动浇给该任务的树。种子0、萌芽1–4、幼树5–9、小树10–19、成树20。所有树成熟后可种下一棵，成树保留。撤销已消费记录通过外键级联移除对应浇水，使原树回退，即使已经种下后续树。删除任务沿用历史级联删除，确认框明确包括记录、水滴、树木；普通编辑不清空历史。

## 数据兼容

新增habit_trees/habit_waterings，Room6→7显式迁移仅增加两表并为旧任务初始化种子。原业务字段、ID和打卡记录保留，旧打卡成为未使用水滴。未来日期记录到对应日期才可使用。新备份format3/schema7完整包含林场，严格校验旧V1/V2后转换，包含总行/字节限制、跨任务拒绝、每树20次上限、故障回滚和新增表自增高水位保护。见[格式说明](backup-format-v3.md)。

## 本轮验证过程

- 首次UI接口测试在生产功能缺失时编译失败，记录.tools/forest-persistence-red.log；该轮不当作运行通过。
- 排除尚未实现UI接口后，.tools/forest-red.log真实执行19项，7项失败；覆盖缺少种子、format3导出、旧V2种子转换、林场恢复/快照/限制，既有习惯Room回归通过。
- .tools/forest-watering-red.log实际为首轮GREEN：4项生产Room测试通过，0 failure/error/skipped，包含20次成熟与重种后撤销原树、跨任务/未来记录拒绝、编辑/删除及并发/故障回滚。文件名沿用预先选定的命令日志名，不将其当作额外RED证据。
- 首轮全量1187项有8项失败：跨午夜水滴刷新、空树任务种植和普通编辑保留旧图标的3项真实行为；另有迁移夹具未容许schema中缺省indices、编辑字段移到可滚动区域、以及旧V2按钮定位。修复实际行为并保留原断言，日期测试滚动到对应字段，备份选择器匹配实际V3文案，未删减业务检查。记录.tools/forest-first-full.log。
- 独立审查指出跨午夜水滴资格缺少刷新，现注入已有Clock并每60秒检查civil date，日期前进/回退都会重新查询。可控时钟回归不写数据库也验证0→1→0；空树恢复入口、旧图标/隐藏颜色保留均覆盖。修复后独立复审8/10，无遗留问题。

## 全量、设备与视觉结果

.tools/forest-verification.log强制重跑1187项JVM/103 suites全部通过，0 failure/error/skipped；Debug、androidTest和非debuggable Release构建通过。lint重新分析为0 Fatal/0 Error/50 Warning，主要为既有API/构建依赖提示，未通过suppress清零；与历史47条差异包含重新检查的依赖版本提示，不冒用旧数字。

专用API35 Android15模拟器Rizhiya_Branding最终72项设备测试全部通过，0 failure/error/skipped，记录.tools/forest-device-verified.log。首轮72项有1项仍按旧恢复按钮文案查找，更新为实际V1/V2/V3文案后复验；期间一次编译因会话中断被终止，不记为通过。最终运行覆盖真实Activity新增樱花任务、打卡仅获水不成长、手动浇水1/20、用尽水滴禁用、撤销回退原种子，以及生产迁移、备份往返和维护/导航。

已目视检查真实界面截图：[树种编辑](assets/habit-forest-editor.png)、[打卡任务](assets/habit-forest-tasks.png)、[手动浇水后的林场](assets/habit-forest-watered.png)。六树种在种子、萌芽、成树阶段有不同轮廓；保留实际月历，热力图已移除。深浅色及大字号由Compose主机回归覆盖，不扩大为全部真机适配。

## 实际APK覆盖升级

在全新专用Rizhiya_Forest_Upgrade_20261017（emulator-5558）安装并启动旧1.0.3/code6/schema6，在验证全部业务/技术表为空、设置与世代为默认值后植入3个合成习惯与5条记录。先保留完整旧库/原始WAL及逐字段快照，再install -r覆盖同签名1.1.0；两次安装之间未卸载或清空数据。

升级后13张旧应用表及Android的android_metadata逐字段一致，自增序列不回退，设置和维护世代0保持；保留原名字/图标/颜色/备注/主键/原始星期字符串及5条记录。新增3棵种子分别映射白桦、枫树、银杏，浇水关联为空，过去记录对应可用水滴2/1/1，未来日期记录不能提前消费，外键检查无问题。原始证据.tools/forest-upgrade-20261017-final，验证结果verified.json。

首次采证脚本将Android平台android_metadata误当未知应用表，保护检查在植入前拒绝执行；未修改原库。更正为额外逐字段保留此平台表后，在新证据目录完成全过程；不把失败轮算作升级通过。

## 安装包与交付边界

候选1.1.0/code7/schema7，安装身份com.example.lifemanager不变，沿用既有预览证书；非debuggable、APK v2/v3签名与16KiB zipalign检查通过。APK为.tools/delivery-habit-forest/rizhiya-1.1.0-habit-forest-preview.apk，12,472,560字节，SHA-256 `0be059f914b5bf36916783da7e660261c9aef8d95fab62e49d4198b4ae56ff1d`；证书SHA-256 `55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c`，与1.0.3一致。

完整交付归档为.tools/rizhiya-1.1.0-habit-forest-delivery.zip，包含初次候选验收时的APK、更新说明、验收、截图、变更源码与本轮原始测试证据。[机器可读验收](evidence/habit-forest-1.1.0-validation.json)。本轮原候选APK已作为[1.1.0预览Release](https://github.com/LieerTsuki/Daybud/releases/tag/v1.1.0-rizhiya-preview-20261017)公开发布；归档继续保留初次候选时的文档快照，当前发布状态见下文及[发布回执](evidence/habit-forest-1.1.0-publication.json)。

## 源码同步main

用户在本轮候选交付后另行要求推送GitHub并指定main。推送前远程main与开发基线bec8c8a一致，59个代码/构建/schema/测试文件与已验收交付归档逐文件摘要一致，无新增代码或合并冲突；沿用同一源码的1187项JVM与72项设备证据，不将其称作再次执行。

源码[8bc71db5f0de03c3c46677a559ddf21139f219ed](https://github.com/LieerTsuki/Daybud/commit/8bc71db5f0de03c3c46677a559ddf21139f219ed)已快进合并本地main并成功推送GitHub；服务端确认bec8c8a..8bc71db，随后对正式仓库地址执行ls-remote，refs/heads/main与本地源码提交完全一致。GitHub确认原lieer-12/app仓库已迁移至[LieerTsuki/Daybud](https://github.com/LieerTsuki/Daybud/tree/main)。本次补充同步记录仅修改文档，不改变已验收APK对应的应用源码。该阶段授权仅涉及源码同步；随后用户明确要求发布APK Release，结果见下文。历史Release/APK保留。

手机升级前保留独立完整备份并同签名覆盖；不卸载清数据解决签名冲突。新导出V3需新版恢复，旧V1/V2仍严格兼容导入。水滴日期资格最多60秒刷新；真机、手动TalkBack、大林场/稳定60fps、全部云文件提供方、正式商店签名尚未验收。

## GitHub Release发布核对

用户明确要求“apk发布release”，已于2026-10-17T21:19:20+08:00发布[日子芽1.1.0打卡林场预览版](https://github.com/LieerTsuki/Daybud/releases/tag/v1.1.0-rizhiya-preview-20261017)，非草稿、prerelease=true。tag `v1.1.0-rizhiya-preview-20261017`固定在main提交`3811291f6254aac6e4cd00e2b886c10fb6d6b4c1`，应用源码与已验收`8bc71db5f0de03c3c46677a559ddf21139f219ed`一致，后续仅补充文档。

上传原已验收APK及SHA256SUMS.txt，不重新签名或替换中间包；本次现场检查1.1.0/code7、包名、非debuggable、v2/v3签名、原证书、16KiB对齐通过，SHA-256与候选一致。GitHub返回资产size/digest匹配；匿名从公开APK地址实际回下载12,472,560字节，SHA-256再次相同；远程tag等于发布提交，59个归档文件的原始字节摘要与归档manifest一致；应用/构建/schema/测试的已提交blob、候选归档和本地文件经CRLF/LF换行归一化后全部一致。初次直接摘要比较因core.autocrlf检出和归档中的换行差异而拒绝，明确核对换行归一化内容后通过，未修改应用文件。

1187项JVM、72项设备、构建/lint和真实覆盖升级沿用同应用源码的前序验收，不宣称发布时再次运行。当前README和CHANGELOG入口更新到1.1.0，既有预览签名和前述未验收限制保持。[机器可读发布回执](evidence/habit-forest-1.1.0-publication.json)。

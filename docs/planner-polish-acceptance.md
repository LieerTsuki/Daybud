# 计划精简与月历交互验收

日期2026-10-16；基线de8b5c8，功能分支feat/planner-polish；按用户既有“发布并合并”授权直接交付，1.0.3/code6/schema6已完成验收并公开发布。

## 需求与实现

成功操作消息与搜索移除；失败、数据替换和提醒异常仍可处理。周序日一二三四五六，月格任务统一字号，按整月宽度适配四字目标，每条单行及象限配色。选择同月日期不重新查询或隐藏月历；跨月和世代变化清空旧事实，刷新时禁用旧动作。

详情与编辑使用分组卡片，只展示计划、备注、有效日期或区间、重复，编辑附闹钟开关和紧凑手动象限菜单；日期及24小时选择器可用。显示与新计划固定上海时间，不显示偏移/时区。既有实际时刻、重复时区与隐藏字段不做批量改写，编辑可见时间按上海时间解释后转换为原存储时区，以保持重复和日期历史语义。提醒开启且旧提前量为空时补0分钟；旧有效提前量保持。

## 验证过程

- RED `.tools/planner-polish-red.log`：43项，6项预期失败，覆盖旧元数据、时区展示、周日对齐、刷新消失与同月选日重新读取。
- 字号测试初在Robolectric默认文本测量下未产生有意义失败，改为原生图形测量后`.tools/planner-font-red.log`8项/1失败，确认短/长名称不同字号。
- 搜索与成功消息RED `.tools/planner-board-red.log`10项/1失败。
- 完整回归、设备、升级与发布结果见下方本轮记录。

- 首轮GREEN149项/2失败：选择器已正确给出完整日期时间，测试assertTextContains需指定substring=true；另一断言同时匹配弹窗和背景输入框，改为限定弹窗祖先，未删除实际时间断言。
- 审查发现旧enabled=true/minutes=null的无效提醒、历史固定偏移时区无法编辑、Shanghai历史夏令时空洞被自动调整。各补现有API回归：`.tools/planner-alarm-red.log`1项/1失败，`.tools/planner-compatibility-red.log`2项/2失败。
- 修复：闹钟显示有效状态，开启补0分钟；旧固定偏移仅在真实持久化同ID/原时区不变时容许编辑，事务内重复验证，新建和更换固定时区仍拒绝；上海输入严格验证偏移数量，错误跨字段编辑保留直到改正，不被原存储时区重新解释。

## 全量、设备与覆盖升级

`.tools/planner-polish-full.log`强制重跑1161项JVM/97 suites全部通过，0 failure/error/skipped。Debug、androidTest、Release构建与lint通过，0 error/47既有warning。`.tools/planner-polish-device.log`本轮70项API35 Android15设备测试通过，0 failure/error/skipped；真实Room、操作、旋转、通知和双击流程保留。独立复审9/10，三项发现均已关闭。

在专用Rizhiya_Branding上重装旧1.0.2并初始化空测试库，植入六条合成计划，然后install -r覆盖1.0.3，中间不卸载、不清数据；升级前后15张应用表逐值相同。包含四象限、时间区间、重复、已完成、无日期、备注及旧无效提醒配置。真实双击仍打开当天全部5项，日一二三四五六周序及无搜索核对通过。实际打开详情/编辑，隐藏字段不可见，旧无效提醒显示关闭，不改动返回后月历保留；连续点选17至20日后均选中且月历仍展开。原始JSON/XML/PNG在`.tools/planner-polish-upgrade-verified`。

采证首轮未先启动旧安装创建数据库，空库检查失败，未植入数据；后续新版为空的安装经明确AVD/零记录保护后重置，并在新证据目录重新从旧版开始，不将失败轮作为升级通过。原生UI脚本第一次误以为编辑返回详情（实际返回计划页），另一次将Compose选中态误查为UIAutomator selected（实际父节点checkable/checked），均修正采证选择器后通过，不改生产行为；月历保留验证是每次点击后的层级检查，不宣称逐视频帧验收。

[四象限首页](assets/planner-polish-board.png) · [周日首列与统一字号](assets/planner-polish-month.png) · [简洁详情](assets/planner-polish-detail.png) · [编辑表单](assets/planner-polish-editor.png) · [提醒开关](assets/planner-polish-alarm.png)

## 安装包

1.0.3/code6/schema6，非debuggable Release，沿用原预览签名；APK v2/v3及zipalign核对通过。大小12,435,895字节，SHA-256 `1c9cf8ace0274e0a8eb8f371bc3075607a651cb7a8c244410655e34076a14701`。升级前另存独立完整备份，同签名覆盖，不卸载清数据处理签名冲突；历史Release/APK保留。真机、手动TalkBack、大库、云提供方、稳定60fps及商店正式签名未验收。

[机器可读验收记录](evidence/planner-polish-1.0.3-validation.json)。真实Release链接与公开下载核对见下方。

## 性能采样

本轮同一非debuggable候选，API35模拟器、六条合成计划：五次真实冷启动TotalTime为665/733/746/927/919ms，中位746ms、最大927ms；8次实际确认的四入口切换，405帧、janky35帧（8.64%，legacy口径43.46%）。原始dumpsys、导航XML与启动记录在`.tools/planner-polish-performance`；这是系统启动与切页冒烟，不能将自动化总耗时当页面响应时间，也不作为真机/稳定60fps或月历逐帧性能结论。月历选日修复依据回归证明不再重读整月及原生连续选日保留月历。

## 已合并与公开发布

源码`bc260a9c240b60b5354c1ce12591c6e1fb65a721`已直接快进合并main并推送，同一已验收源码树不额外重复测试。公开[1.0.3预览Release](https://github.com/LieerTsuki/Daybud/releases/tag/v1.0.3-rizhiya-preview-20261016)，发布时间`2026-10-16T09:09:58Z`（UTC）；[下载APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.0.3-rizhiya-preview-20261016/rizhiya-1.0.3-planner-polish-preview.apk)。服务器资产摘要和实际公开下载均核对12,435,895字节及SHA-256一致，旧Release/APK保留。[发布核对](evidence/planner-polish-1.0.3-publication.json)。

交付归档`rizhiya-1.0.3-planner-polish-delivery.zip`（14618123字节），SHA-256 `f54c1fcd0e7a6ce855c3f19f248f37f7c31e5bdb4f68bfe8c1220c5a228705ef`，含本轮APK、验收文档、截图、原始测试XML/日志及合成数据采证。

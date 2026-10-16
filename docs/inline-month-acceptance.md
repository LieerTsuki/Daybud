# 内嵌月历与单行彩色任务验收

日期：2026-10-16。基线：`ae07a522fdfb8dcdcadc2982058cf82ee69f8e41`，功能分支`feat/inline-month-calendar`，候选1.0.2/code5。本轮已按用户既有授权直接合并和发布，当前公开版本为1.0.2。

## 最终需求与实现

- 四象限页只有一个月历下拉按钮，默认收起，展开后在同一滚动容器展示月历；无独立日历页、周/日视图或计划统计入口。打卡自身统计保持。
- 用户后续明确禁止任务名称换行：日格每条任务严格单行、四象限彩底，11至8sp按可用宽度及用户字体缩放测量，仍超宽才省略；原名保持可访问语义。每格最多四条及剩余数量，空格56dp、忙日期行自然等高。
- 双击日期显示当天完整计划（含已完成），长按与无障碍自定义操作也可打开；完整标题单行横向查看，备注、时间、地点等不按月格截断。进入计划详情再返回保留月份和展开状态。
- 数据世代、加载、维护、草稿、通知及旋转保护保持。无日期计划不进日历；原日期、跨日、时区、重复取消及系列完成规则继续复用原查询。安装身份、schema6与备份格式无变更。

## 验证与覆盖映射

- RED：`.tools/inline-month-red.log`，6项/4失败；旧代码缺展开入口、当天明细动作、第三/四名称，长名称仍为两行，均为实际需求失败。
- 第一次GREEN编译发现测试使用当前Compose无`assertHeightIsAtMost`，改为读取未裁剪边界并比较bottom-top；后一次比较DpRect的height属性亦不支持，修为bottom-top，未调整生产布局来规避断言。日志`.tools/inline-month-green.log`、`.tools/inline-month-targeted.log`保留。
- 删除原`PlanStatisticsScreen`及生产统计实例。原统计UI四例转为内嵌月历、紧凑日期格、无旧模式入口及大字体可达；统计domain/内部VM的旧数据事实验证保留，但不作为当前产品有统计界面的依据。
- 原工作区切日/统计等测试迁移为展开/收起、实际双击、全量明细、返回月份保留、旧世代动作拒绝及草稿保护，保留真实身份与备注断言。设备首例去计划统计入口，保留实际SQL、同ID日期转换、完成撤销、旋转与通知两例。
- 独立复审发现合法标题中的CR/LF在明细单行文字中会截断后段，新增回归后修复；具体结果在本轮完成后补入。

- 定向159项/4失败（`.tools/inline-month-targeted-2.log`）：CR/LF明细回归确认缺少归一化后段，修复后通过；两个单击测试需让虚拟时钟经过双击仲裁等待，不改生产手势；长名同时存在背景月格与弹层，断言限定明细弹层祖先，完整原名断言不删。
- 首轮全量1144项/1失败（`.tools/inline-month-full.log`）：实际Room测试还按默认隐藏已完成日程预期1项，改为默认含完成、按实际ID集合断言2项，取消例外后仍保留已完成截止项；保留原系列完成标记、例外与同ID断言。
- 最终`.tools/inline-month-verified.log`：1144项JVM/97 suites强制重跑通过，0 failure/error/skipped；Debug/androidTest/Release和lint通过，0 error/47既有warning。独立复审发现的CR/LF问题已关闭，无遗留重要问题。
- `.tools/inline-month-device.log`：专用`Rizhiya_Branding` API35模拟器实跑70项，0 failure/error/skipped。真实生产Room身份、日期变化、完成撤销、真实触摸双击、日格TextLayoutResult单行、返回月份/展开保留、草稿旋转与通知保护均通过。

## 覆盖升级与实际界面

设备回归结束自动卸载测试应用后重新装已发布1.0.1/code4，植入六条明确合成计划，再以`install -r`覆盖1.0.2/code5；新旧版本之间无卸载/清数据。设备SQLite读取的15张应用数据库表升级前后逐值一致。实际展开月历只含月视图、当天四种配色任务严格单行及「还有1项」；真实ADB双击打开当天全部5项详情（含已完成），未设日期的第6条不混入。

原始JSON/XML/PNG与检查脚本为`.tools/inline-month-upgrade`及`.tools/inline-month-evidence.py`，仅允许明确命名AVD和空计划库植入样例。第一次采证同时匹配了按钮description和其子文本两个节点，改为优先精确description后通过，不改界面或减少业务断言。

[默认收起](assets/inline-month-collapsed.png) · [展开月历及单行四象限颜色](assets/inline-month-expanded.png) · [双击当天完整详情](assets/inline-month-day-details.png)

## 安装包与性能

非调试Release候选`.tools/delivery-inline-month/rizhiya-1.0.2-inline-month-preview.apk`，1.0.2/code5；APK v2/v3与zipalign核对通过，沿用原预览证书SHA-256 `55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c`。APK SHA-256 `00441dfed4803cf07df06a45b58431429ce477818e656042e0e3546d7940058b`。

同一专用模拟器、六条合成计划、最终非调试APK，且无Gradle并发的五次系统冷启动为815/789/759/689/828ms，中位789ms、最大828ms；两轮8次真实选中核对的切页，gfxinfo采样412帧、24帧janky（5.83%），legacy172帧（41.75%）。仅模拟器小库系统口径，不作为稳定60fps、真机或比历史版本更快的结论。原始日志及脚本在`.tools/inline-month-performance`和`.tools/inline-month-performance.py`。

[结构化验证记录](evidence/inline-month-1.0.2-validation.json)。预览证书沿用，升级前另存完整备份并同签名覆盖；真机、手动TalkBack、大库、云提供方与稳定60fps不属于本轮通过结论。以上为本轮同一源码树的实际验收，公开发布与合并记录如下。

## 已合并与公开发布（2026-10-16）

已验收源码`e6fdde5984ab61116ad6563a78c993a9c4ef20ee`直接快进合并main并推送，不再停在PR等待授权。本轮1144项JVM/97 suites、70项设备、构建/lint、覆盖升级和性能均在该源码树完成，main快进前后代码完全相同，不额外重复相同测试或冒充新增执行。

[1.0.2预览Release](https://github.com/LieerTsuki/Daybud/releases/tag/v1.0.2-rizhiya-preview-20261016)于`2026-10-16T05:42:24Z`（UTC）公开；[下载APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.0.2-rizhiya-preview-20261016/rizhiya-1.0.2-inline-month-preview.apk)。服务器资产摘要与本地已安装过的签名APK一致，再从公开链接实际下载核对12,435,895字节及SHA-256一致；1.0.1及更早Release保留。[发布核对](evidence/inline-month-1.0.2-publication.json)。

[完整交付压缩包](https://github.com/LieerTsuki/Daybud/releases/download/v1.0.2-rizhiya-preview-20261016/rizhiya-1.0.2-inline-month-delivery.zip)含APK、摘要、验收与发布记录、截图及原始采证，13,314,645字节，SHA-256 `3cfe1deab0c96a1f66888988b13196299f7b80c008e820b65f74d2b769a9bc06`，已核对服务器摘要与本地一致。

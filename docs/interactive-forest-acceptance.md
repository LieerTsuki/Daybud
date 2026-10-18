# 互动森林验收

日期：2026-10-18；基线`3c9af21`；功能分支`feat/interactive-forest`；版本1.1.1/code8/Room7。用户确认统一森林、点树浇水、拖动缩放设计，并授权完成后推送main及发布APK。本页记录本轮候选验收；发布结果在确认成功后另行补充。

## 实现与兼容

所有任务的种子、成长树和历代成树共同放在一个连续场景中，替换逐任务树木卡片。原生Canvas绘制奶油色天空、薄荷绿山丘、草地、小路、池塘、花草和笑脸太阳；六树种去除独立徽章背景，保持日子芽插画风格。统计页优先展示森林，任务统计与月历保留，月历的任务/月选择不筛掉其他树木。

单击树木打开底部面板，显示所属任务、树种、代次、成长进度和该任务可用水滴；点击浇水后仍走原世代保护及Room事务，实际写入成功后才播放有限时长水滴动画。20次成熟后保留成树；该任务所有树成熟时可再种一棵。撤销打卡回退原树、同任务水滴隔离与删除确认保持。没有树的已恢复任务仍可种第一棵。

支持单指拖动、双指缩放、缩放/复位按钮和查找定位；按树ID稳定排序，查找请求带独立序号，重复定位同一棵树也会重新居中。视角与选中仅属于UI，按数据世代失效，不写库。只组合可见树木节点，远处的树可通过查找访问。单指点击保留原生点击语义；变换在Initial事件阶段先行处理，超过触摸阈值或多指操作会取消树木点击，避免跨树缩放被子项提前消费。

仅提升应用版本，包名`com.example.lifemanager`、Room7、format3/schema7备份和既有迁移链保持；不改任务/记录/树木/浇水数据格式。新V3备份仍需兼容版本恢复，旧V1/V2严格导入保持。

## 本轮验证

- 全量JVM：1194项/104套件，failure/error/skipped均0。命令包含testDebugUnitTest、assembleDebug、assembleDebugAndroidTest、assembleRelease、lintDebug；最终BUILD SUCCESSFUL，日志`.tools/interactive-forest-full.log`。其中7项新森林Compose测试覆盖先选树后浇水、任务隔离、成熟与重种、维护/恢复失效、60棵树查找与重复定位、深色大字号实际文本布局、单击/从树上拖动/跨树缩放和恢复后缩放。
- 设备全量：专用API35 Android15模拟器`Rizhiya_Interactive_Forest`/`emulator-5560`，通过ADB对已构建Debug与androidTest APK运行整个AndroidJUnitRunner测试包，73项/26类，failure/error/skipped均0。日志`.tools/interactive-forest-device.log`及解析出的`.tools/interactive-forest-device-results.json`；不是复用历史Gradle connected XML。先前单项定位复验包含在73项中，不累加为74项。
- 真实Activity设备闭环：六个不同树种/成长阶段的任务共同出现在森林；真实指针点击种子打开面板，浇水后独立Room句柄读到对应树1/20、仅该任务水滴减少；真实双指缩放改变视角，拖动/复位后仍可点树；成熟松树再种后，数据库保留20与0两棵树。
- Debug、androidTest及非debuggable Release构建/复核通过。lint为0 Fatal/0 Error/29 Warning；与上一轮50条之差为AndroidGradlePluginVersion、GradleDependency、NewerVersionAvailable三类依赖版本检查，本轮离线检查不将这21条差异宣称为修复或抑制。
- 独立只读审查发现重复查找、恢复后手势旧捕获及大字号标签空间问题，已修正并复审。实际文本按同一渲染样式测量，保留高度溢出断言；最终无遗留重要问题。

## 失败轮次与修正

初始5项新交互测试在旧卡片界面均失败，日志`.tools/interactive-forest-red.log`。后续回归复现缩放不更新与大字号文本高度溢出；仅增加世代手势键仍不能解决初始缩放。核对实际Compose1.7.8事件处理后，确认第二根手指落到另一可点击树上会在Main阶段消费事件，改为Initial阶段协调点击和变换；真实指针测试验证单击、拖动和跨树缩放同时可用。过程保留于green、focused及build日志，不把失败轮标为通过。

首次全量1194项有1项旧导航测试未等待异步离开确认弹窗，补充等待确认出现/取消完成后再进行下一次操作；保留草稿、目标页及零数据库写入断言，不修改计划导航生产代码。首次设备单项因Activity已启动后从第二个Room连接植入夹具，生产单例未收到跨连接失效通知而超时；改为createEmptyComposeRule，先完成合成数据，再启动真实Activity。最终单项及完整73项均通过，仍独立读生产文件验证写入。原日志`.tools/interactive-forest-first-full.log`、`.tools/interactive-forest-device-focused.log`与`.tools/interactive-forest-device-retry.log`保留。

## 实际签名APK覆盖升级

在全新专用AVD安装并启动上一版已发布1.1.0/code7，确认业务表为空、维护世代0后，植入六个合成任务、七棵树、59条记录及46个浇水关联，包括成熟历史、下一代种子和未来日期记录。保留原始数据库/WAL、完整字段与自增序列快照，再通过install -r覆盖同签名1.1.1；两次安装之间未卸载或清空应用数据。

新APK启动后，16张表（含Android元数据）的结构和逐字段值、自增高水位全部一致，六任务、七树和46次浇水保留，外键检查无错误，schema仍为7。证据`.tools/interactive-forest-upgrade-20261018/verified.json`；受AVD名称、序列号、版本与空库保护的脚本`.tools/verify-interactive-forest-upgrade.py`。升级采证后只删除该脚本创建的六个合成ID，再运行设备UI测试；未操作个人设备或个人数据。

## 安装包与截图

APK：`.tools/delivery-interactive-forest/rizhiya-1.1.1-interactive-forest-preview.apk`，12,501,431字节，非debuggable、v2/v3签名、16KiB zipalign核对通过。

SHA-256：`105eaa52fe81ca632fa947e2ce005a68258ecd1b30f2266eefbb00c896600acf`。

证书SHA-256：`55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c`，与1.1.0相同的既有预览证书。实际界面已目视检查：[森林全景](assets/interactive-forest-overview.png)、[选树浇水](assets/interactive-forest-watering.png)、[成熟后重种](assets/interactive-forest-replanted.png)。[机器可读验收](evidence/interactive-forest-1.1.1-validation.json)。

升级前另存独立完整备份，同签名覆盖，不卸载清数据、不降级旧schema。真机、手动TalkBack、超大林场稳定60fps、全部云文件提供方和正式商店签名未验收；本轮不扩大前述模拟器/字体/文件格式结果的适用范围。

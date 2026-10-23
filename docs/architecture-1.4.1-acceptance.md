# 1.4.1 架构改进版发布验收

发布日期：2026-10-23。目标版本：**1.4.1 / code17**。状态：**已发布正式Latest Release，公开下载核对通过**。

本页记录1.4.1本次构建、签名、覆盖升级和公开下载核对，正式APK包含本轮架构改进。

## 本次内容

- 关闭Android系统云备份，接线旧版full-backup规则与Android12+云备份、标准设备迁移排除规则，换机继续使用应用内完整归档。
- 日记失效通知与数据读取分开，日记/草稿同事务读取，批量附件查询替代N+1，保留关联变化刷新与缺失引用检测。
- 导航通过编辑器端口与真实ViewModel适配器工作，保持通知原世代、会话、草稿确认和消费顺序；媒体接口、Android导入、平台解码职责分开。
- 提供Gradle9.4.1 Wrapper、官方分发包摘要、当前架构检查与GitHub Actions，更新架构/代码索引。
- README首页明确区分当前main发布进度与已发布下载，突出本轮改进；此前README全文逐字节复制到根目录README.history.md，保留相对链接所在目录和全部历史记录。

计划、习惯打卡、普通日记、订阅和设置均保留。没有重新引入AI功能，也没有将各功能改为独立Gradle模块。

## 数据兼容与升级要求

- 保持包名`com.example.lifemanager`、既有正式签名身份/轮换链、Room8及V4归档；本次没有数据库schema迁移，不使用清库或卸载实现升级。
- 正式APK必须使用更高的versionCode17覆盖1.4.0/code16，升级前保存完整备份；不得将Debug包签名冲突当作升级验证。
- 核对升级前后的数据库表、字段值、类型、NULL、序列及Room身份；日记、草稿、附件与历史兼容数据的验证注明是否存在非空业务记录。
- 对实际覆盖升级的设备分别记录UID、签名、应用版本和数据；没有覆盖的私有文件/偏好或真实用户数据，不作保留结论。
- 关闭系统备份不删除本地记录、旧归档或以前已存在的系统备份。厂商专有迁移、云端备份/跨设备恢复没有实测前保持“未验证”。

## 本次发布验证结果

以下结果来自版本更新后的本轮执行。[本轮验证摘要](evidence/architecture-1.4.1-validation.json) · [非空合成升级快照](evidence/architecture-1.4.1-upgrades.json) · [443个源文件清单](evidence/architecture-1.4.1-source-manifest.json)。此前架构阶段不计入本次测试总数。

| 项目 | 本次检查内容 | 状态/证据 |
| --- | --- | --- |
| 版本与源码 | 1.4.1/code17、main源码及实际构建源文件一致性 | 已核对1.4.1/code17，443个源文件规范化清单与发布提交逐项一致 |
| Wrapper与结构检查 | 版本/SHA校验、正负结构检查、依赖边界 | 24项正负测试及当前结构检查通过 |
| JVM回归 | 日记/草稿、附件、世代、导航、备份与迁移；记录报告实际总数 | 1279项 / 121套件，0失败/错误/跳过 |
| 设备回归 | 独立测试设备上的业务与日记/备份路径；注明API和测试范围 | API35独立模拟器77项，0失败/错误/跳过 |
| 构建与Lint | Debug、androidTest、Release及相应Lint；分别记录错误/警告 | 三种APK构建通过；Debug与Release各0错误/23警告 |
| APK边界 | 版本、权限、AI退役边界、正式非调试APK与16KiB对齐 | 最终签名APK验证通过；关闭系统备份、规则已打包；无INTERNET和活动AI；16KiB通过 |
| 签名 | 签名工具回归、既有正式证书/轮换链、各SDK检查点 | 17项工具测试；六个SDK点及既有轮换链通过 |
| 覆盖升级 | 1.4.0→1.4.1，不卸载、不清数据；注明真实数据与空表边界 | API26/28/35非空合成fixture；23表及两份PNG、UID和签名保留 |
| 日记查询 | 本轮若重跑则记录20篇fixture查询数；不推断真机耗时 | 本轮设备回归重跑20篇含附件fixture：日记内容2次、联合快照4次；不推断真机耗时 |
| 公开发布 | Release/tag/源码一致，匿名完整回下载核对APK及其他资产SHA256/字节数 | 正式Latest Release；四个资产匿名完整回下载核对通过 |

本轮正式APK为12706144字节，SHA256：`5f768bd950f3918b82711eb3aa391d7f8ff9b05d81778a41a8e7293badfdcad2`。本轮Room设备测试重跑20篇含附件fixture，日记内容2次、联合快照4次；42次来自旧实现的对照，不是本轮速度测评。首次设备执行因测试设备选择失效和SystemUI无响应作废，保留失败记录；隔离为单台API35、重启系统后重新完整执行77项通过。受影响的API26升级样本废弃，改用新建独立模拟器。没有将失败轮次计为通过。

覆盖升级样本均为专用模拟器上的合成数据：API26/28的23张表均非空；API35旧版启动校准后提醒缓存表`plan_reminder_state`已为空，其余22张表非空，新版保留这一真实基线。API35空缓存不能证明该平台非空提醒缓存升级保留。两份PNG附件均逐字节核对；未验证视频附件或所有私有文件/偏好。

## 已知限制

远程CI另行核对：此前`77f48f7`的[Actions运行](https://github.com/LieerTsuki/Daybud/actions/runs/39887601511)有1项Compose测试在种子记录尚未发布时提前查找控件失败；本轮只修正测试等待目标ID/标题及世代，重新完整执行1279项JVM测试通过。发布源码`e536de47571798484eeee5ddd389a64aca6ae0db`的[远程CI](https://github.com/LieerTsuki/Daybud/actions/runs/39970376329)已全部通过，包括Wrapper、结构正负检查、JVM测试、Lint和Debug/androidTest编译；[CI回执](evidence/architecture-1.4.1-ci.json)另列本机冻结JSON与Git规范化JSON的原始字节摘要。源文件核验使用相同的路径/摘要映射，文本CRLF规范化为LF。

- 日记仍全量读取正文，未实现摘要列表/分页；统计、日历及“那年今日”维持原完整列表语义。
- 当前仍是单Gradle模块，接口与包边界隔离不等于功能模块独立编译。
- 系统备份Manifest/XML接线可由测试核对；OEM迁移、真实云端备份与跨设备恢复需另行实测。
- 远程CI、设备测试、模拟器覆盖升级与ARM真机性能分别记录，不能相互代替。
- 历史Release、APK、模型资产和验收记录保留，不覆盖或删除旧版本。

## 发布结果

已于2026-10-23T22:40:40+08:00发布正式Latest Release，发布源码`e536de47571798484eeee5ddd389a64aca6ae0db`。APK、UPDATE.md、SIGNING.json及SHA256SUMS.txt均经匿名完整回下载，字节数与SHA256一致。[下载1.4.1正式APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.4.1/rizhiya-1.4.1.apk) · [正式Release](https://github.com/LieerTsuki/Daybud/releases/tag/v1.4.1) · [公开发布回执](evidence/architecture-1.4.1-publication.json)。

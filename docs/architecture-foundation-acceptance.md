# 架构基础改进验收

日期：2026-10-23。开发分支：`improve/architecture-foundation`；基线：`6c7b031`。本轮为未发布源码改进，版本保持1.4.0/code16，Room8、备份V4与应用身份不变。

## 实际改动

- 关闭系统云备份并接线旧版fullBackupContent和新版cloud-backup/device-transfer的全域排除规则；应用内完整归档与保护备份继续使用原流程。
- 日记失效信号覆盖记录、草稿、媒体及关联表；内容在同一事务读取，批量LEFT JOIN替代N+1查询。共享媒体顺序与缺失引用拒读保持。
- 导航通过小接口与实时ViewModel适配器处理确认/通知；旧generation、session、ticket和完整编辑状态比较保留。
- 领域媒体端口、data导入实现、platform图片解码分离，UI不再直接依赖具体存储；新增domain/data/ui依赖护栏。
- 固定Gradle9.4.1 Wrapper及官方摘要，CI执行结构、JVM、Lint和APK编译；Phase脚本明确为历史检查。
- 当前架构和代码索引重写，原文完整保存在docs/history；README与CHANGELOG未发布区同步。

## 回归证据

### 改动前复现

运行旧生产代码和新增回归：1273项测试、121个套件，4项预期失败、0错误、0跳过；原有1269项通过。

- 系统备份flag仍开启：期望0，实际32768。
- 只改变日记/草稿附件关联时，观察流5秒内没有更新。
- 20篇带附件日记读取执行42条内容SELECT。

[结构化RED记录](evidence/architecture-foundation-red.json)，本机日志`.tools/architecture-baseline.log`。

### 定向回归

11个套件、96项测试通过，失败/错误/跳过均为0；覆盖真实Room、日记ViewModel、迁移、V4仓库、系统备份配置及导航Compose/所有者回归。

20篇带附件日记读取实际为**2条内容SELECT**；日记/草稿联合快照为**4条内容SELECT**。这是查询数量证据，不是大数据或真机耗时基准。

[定向结果](evidence/architecture-foundation-focused.json)，本机日志`.tools/architecture-focused-green.log`。

### 工程护栏

24项正负向fixture及真实项目结构检查通过，含非法依赖、破坏性迁移、系统备份排除缺失、Wrapper JAR/URL/摘要及Unix换行篡改。CI YAML经actionlint1.7.12静态检查通过；远程workflow结果未纳入本地验收，推送后以实际运行记录为准。

Wrapper JAR SHA256：`55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`。

Gradle9.4.1-bin SHA256：`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`。

首次Wrapper在线下载遇到Java网络超时；随后将已有分发包与官方SHA256核对后建立本机Wrapper缓存，定向构建由Wrapper离线执行。仓库仍固定官方HTTPS地址，未使用跳过校验或私有镜像。

### 全量构建与设备验证

本轮Wrapper离线完整命令成功，包含Debug APK、androidTest APK、Lint、全部JVM和独立API35模拟器回归；Gradle结果`BUILD SUCCESSFUL`。

| 检查 | 本轮结果 |
| --- | --- |
| JVM | 121个套件、1279项通过，失败/错误/跳过均0 |
| 独立API35模拟器 | 77项通过，失败/错误/跳过均0 |
| Debug Lint | 0错误、23警告；没有将警告写成清零 |
| Debug/测试APK | 构建通过 |
| Room导出schema | 无变更 |
| 最终Debug APK | 无联网权限或已退役AI运行组件；allowBackup=false且两套规则真实打包 |

[完整结构化验证与APK摘要](evidence/architecture-foundation-validation.json)，本机完整日志`.tools/architecture-full-green.log`。首次全量启动的PowerShell设备参数被拆分，在进入业务任务前失败；加引号后重新完整执行通过，失败启动日志保留为`.tools/architecture-full-validation.log`，未将该次失败计作通过。

## 复现命令

```powershell
py -3 -m unittest discover -s tools -p test_project_structure.py
py -3 tools/check_project_structure.py
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
```

设备回归须使用新建的独立测试模拟器。`connectedDebugAndroidTest`会卸载测试应用，不得指向保存个人记录的设备。本轮使用全新`Architecture_API35` AVD、`emulator-5580`，启动前设备列表为空，并在运行前再次校验AVD名称；未复用已有业务数据会话。

验证结束后已停止测试模拟器。递归删除临时AVD目录被自动审批以“blocked by policy”拒绝，因此保留`.tools/architecture-20261023/avds`，没有尝试绕过审批；测试日志和结果仍可核对。

## 限制与后续工作

- 日记仍全量读取正文，未实现分页/摘要列表；后续需将统计、日期索引与详情读取一起设计。
- 仍为单Gradle模块，导航端口仍引用业务UI快照；本轮完成包边界与接口隔离，没有宣称功能模块完全独立编译。
- 系统备份验证覆盖Manifest与XML规则接线，未实测云端备份、跨设备恢复或厂商专有迁移；关闭备份不会删除以前已存在的系统备份。
- 没有发布APK、正式签名/Release构建、覆盖升级或ARM真机性能验收；远程CI结果独立记录。公开1.4.0安装包不包含本轮改动。
- AGP内建Kotlin、KSP和增量编译迁移属于后续工程工作，现有构建兼容开关及依赖版本未在本轮批量调整。

设计和实施范围见[设计](superpowers/specs/2026-10-23-architecture-foundation-design.md)与[计划](superpowers/plans/2026-10-23-architecture-foundation.md)。系统备份策略依据[Android官方文档](https://developer.android.com/identity/data/autobackup)，Wrapper依据[Gradle官方文档](https://docs.gradle.org/9.4.1/userguide/gradle_wrapper.html)。

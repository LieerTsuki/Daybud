# 日子芽 · Android 本地生活手账

把**计划、习惯打卡、普通日记和订阅费用**放在一起。日子芽用手机本地数据库和文件保存记录，不需要账号，也不提供应用内云同步。写日记无需 API Key、模型或电脑。

## 当前版本与正式下载

**当前正式版：1.4.2 / code18，2026-10-27发布。** 计划月历中的已完成任务标签改为灰色，撤销完成后恢复原四象限配色；正式APK已完成签名、回归及API35覆盖升级验证。

[下载1.4.2正式APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.4.2/rizhiya-1.4.2.apk) · [1.4.2版本说明](https://github.com/LieerTsuki/Daybud/releases/tag/v1.4.2) · [本次更新内容](CHANGELOG.md) · [发布验收](docs/calendar-completed-colors-acceptance.md) · [历史版本与验收记录](README.history.md)

本次配色适配明暗主题，任务原有象限和日期保持。APK及三个说明/校验附件已完整回下载核对字节数与SHA256，见[公开发布回执](docs/evidence/calendar-1.4.2-publication.json)。

## 可以做什么

| 功能 | 用途 |
| --- | --- |
| 计划手账 | 按重要、紧急四象限安排任务，设置日期、时间、重复和提醒，用月历查看安排；未完成计划完整展示，已完成计划可以折叠。 |
| 习惯打卡 | 记录每天的习惯，打卡获得水滴、给树木浇水，在互动森林中查看成长。 |
| 普通日记 | 写文字、添加图库照片或视频、选择心情、保存草稿；按日期倒序或日历浏览，查看连续记录、本月记录和“那年今日”。 |
| 订阅费用 | 手动管理订阅和实际扣费，按月查看费用；不绑定银行卡，不自动扣款。 |
| 设置与备份 | 调整主题和提醒，导出完整备份、从备份恢复，或在确认后清空业务数据。 |

日记没有 AI 总结、AI 建议或模型准备步骤；旧版相关说明仅保留在历史文档中。提醒依赖 Android 通知权限和系统调度，厂商后台限制可能影响到达时间。

## 1.4.1 已完成的改进

- **明确本地数据的迁移方式：** 关闭 Android 系统云备份，配置旧版备份和新版云备份、标准设备迁移的排除规则。换机使用应用内完整备份；关闭系统备份不会删除本地记录或以前已有的系统备份。厂商专有迁移工具需另行验证。
- **减少日记的重复查询：** 日记和草稿在同一事务读取，附件使用批量关联查询，附件关联单独变化也会刷新。在此前真实 Room 的20篇含附件测试数据中，日记内容查询由42次降至2次，日记和草稿联合快照为4次；这是查询数量验证，不是手机速度测评。列表仍读取完整正文，统计和“那年今日”保持原语义，尚未实现分页。
- **收拢导航和媒体依赖：** 导航通过编辑器接口与适配器处理草稿离开确认及通知，继续核对原世代、会话和完整编辑快照；媒体接口归入领域层，Android 导入与平台解码各有明确位置。项目仍是单个 Gradle 模块。
- **补齐工程检查：** 提供固定 Gradle 版本和官方分发包摘要的 Wrapper、当前结构检查与 GitHub Actions 流程，覆盖依赖边界、JVM测试、Lint及安装包编译。设备测试与远程 CI 结果分别记录。

以上实现和此前验证见[架构基础改进验收](docs/architecture-foundation-acceptance.md)；**1.4.1 本次发布的验证结果以[发布验收](docs/architecture-1.4.1-acceptance.md)为准**，不沿用上轮测试数字。

## 安装与升级

需要 **Android 8.0（API26）或更新版本**。普通用户无需配置开发环境或开启 USB 调试。

1. 用手机浏览器下载上方正式 APK。Release 的 Assets 中请选择 `.apk`；`Source code` 是源码，不能直接安装。
2. 打开下载的 APK，按系统提示允许浏览器或文件管理器“安装未知应用”，完成安装后即可使用，也可关闭刚才授予的权限。
3. 已安装旧版时，先在“设置”导出完整备份并妥善保存，再用同签名的新版正式 APK **覆盖安装**。不要先卸载或清空应用数据。

如果提示签名不一致，先保留备份并确认安装包来源与版本；自行编译的 Debug 包和正式包签名不同。GitHub 访问不畅时，可在电脑下载 APK 后传到手机，日常使用不需要电脑。

## 保存数据与换机

定期到“设置”导出完整备份。当前沿用 **Room8 / V4完整归档**，包含计划、打卡、订阅、日记、草稿、日记附件与设置，保留历史兼容字段；1.4.2不升级数据库结构或备份格式。

恢复前会校验文件，并要求保存当前数据的保护备份和独立确认；恢复是完整替换，不合并。备份文件未加密，SHA256校验不等于加密，请保存在可信位置。换机后在新手机恢复归档，手机之间不会自动同步。[备份格式与兼容说明](docs/backup-format-v4.md)

## 开发与构建

技术栈为 Kotlin、Jetpack Compose / Material3、Room、Hilt 和 WorkManager，沿用 domain / data / ui 分层。[当前架构](ARCHITECTURE.md) · [代码索引](CODEBASE_MAP.md) · [编码约定](CODING_STANDARDS.md)

准备 **JDK25、Android SDK Platform35、Build Tools36.0.0**。在 `local.properties` 配置 `sdk.dir`，将 `JAVA_HOME` 指向JDK25。仓库 Wrapper 固定 Gradle9.4.1并校验官方分发包SHA256，无需另装 Gradle；AGP9.2.0，Java/Kotlin字节码目标为17。

```powershell
git clone https://github.com/LieerTsuki/Daybud.git
cd Daybud
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
py -3 -m unittest discover -s tools -p test_project_structure.py
py -3 tools/check_project_structure.py
```

Gradle命令按顺序执行。macOS/Linux用 `bash ./gradlew` 和 `python3` 替换对应命令。首次运行需要下载 Gradle、依赖及 Robolectric 运行时；缓存齐全后才能完整离线测试。结构检查需要Python3.10+，历史Phase脚本不作为当前验收入口。

Debug APK位于 `app/build/outputs/apk/debug/app-debug.apk`。正式发布按[签名流程](docs/release-signing.md)执行，私钥和口令不入库。`.github/workflows/android.yml` 在推送、PR或手动触发时运行检查；编译 androidTest APK 不等于设备测试已通过。设备测试使用独立测试模拟器，避免对保存个人记录的手机运行会卸载应用的测试任务。

## 更新与历史

[CHANGELOG.md](CHANGELOG.md)记录每次更新、兼容性和实际发布状态。[README.history.md](README.history.md)按原文完整保存此前README，包括旧Release、下载链接、AI历史说明和各阶段验收记录；内容反映当时状态，不作为当前安装指南。

# 当前代码索引

更新：2026-10-23，工作分支未发布。路径均相对仓库根目录。

## 生产入口与装配

- `app/src/main/java/com/example/lifemanager/MainActivity.kt`：Activity所有者、通知Intent消费。
- `LifeManagerApp.kt`：主进程依赖入口、提醒校准、旧AI工作退役、媒体GC。
- `di/DatabaseModule.kt`：共享Room数据库。
- `di/AppModule.kt`：业务仓库、Clock/dispatcher、备份与维护工作流。
- `di/DiaryModule.kt`：日记仓库、领域媒体端口的Android实现与归档装配。
- `di/V1ReminderModule.kt`：提醒实现和领域端口绑定。

下列Java/Kotlin路径省略公共前缀`app/src/main/java/com/example/lifemanager/`。

## 界面与领域

| 功能 | 界面/所有者 | 业务端口/实现 |
| --- | --- | --- |
| 导航 | ui/navigation/V1NavGraph、PlanNavigationViewModel | NavigationEditors为端口，NavigationEditorAdapters接真实所有者 |
| 计划 | ui/plan/PlanWorkspace、PlanViewModel、PlanEditorViewModel、PlanCalendarViewModel | domain/plan/PlanUseCases、PlanRepository；data/plan/PlanRepositoryImpl |
| 打卡/森林 | ui/habit/HabitScreen、HabitViewModel、ForestSection/Scene | domain/repository/HabitRepository；data/repository/HabitRepositoryImpl |
| 日记 | ui/diary/DiaryScreen、DiaryViewModel、DiaryViewModelFactory | domain/diary/DiaryRepository；data/diary/RoomDiaryRepository |
| 订阅 | ui/subscription/SubscriptionWorkspace、SubscriptionViewModel | domain/repository/SubscriptionRepository；data/repository/SubscriptionRepositoryImpl |
| 设置/备份 | ui/settings/V1SettingsViewModel、V1BackupSettingsViewModel | domain/backup与domain/maintenance；data/backup |

## 日记、媒体与备份

- `domain/diary/DiaryMediaAccess.kt`：媒体端口与导入pin。
- `data/diary/AndroidDiaryMediaAccess.kt`：内容提供方导入、媒体校验；不属于UI层。
- `platform/media/DiaryImageDecoder.kt`：共用有界图片解码。
- `data/diary/DiaryDao.kt`：日记/草稿/媒体SQL与批量关联读取。
- `data/diary/RoomDiaryRepository.kt`：观察失效、事务内容快照、修订校验和维护准入。
- `data/diary/FileDiaryStorage.kt`：不可变附件、gate、pin及安全回收。
- `data/backup/V4ArchiveContainer.kt`、`V4BackupManifest.kt`、`V4ArchiveFiles.kt`：有界归档容器、严格清单与文件租约。
- `data/backup/V4BackupRepository.kt`：跨旧业务表/日记的同事务快照及替换。
- `domain/backup/V2BackupMaintenanceWorkflow.kt`：保护备份、恢复/清空与世代推进；内部V2名称是共享载体，不表示生产导出格式降级。
- `data/diary/ai/DiaryAiWorker.kt`、`LegacyCloudRetirement.kt`：仅旧版本退役兼容。

## 数据与平台

- `data/local/LifeManagerDatabase.kt`、`LifeManagerDatabaseFactory.kt`：Room8、迁移注册、初始化。
- `data/local/migration/`：计划6、森林7、日记8迁移；旧迁移保留在数据库定义。
- `domain/maintenance/MaintenanceCoordinator.kt`：准入/冻结/维护会话，禁止重复嵌套。
- `ui/common/GenerationAccess.kt`：当前世代读取和安全UI发布。
- `notification/UnifiedReminderReconciler.kt`及Worker：统一提醒校准。
- `app/src/main/AndroidManifest.xml`、`res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml`：系统组件及系统备份策略。
- `app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/2.json`–`8.json`：已提交Room schema。

## 构建与验证入口

- `gradle/libs.versions.toml`：依赖版本目录；`gradle/wrapper/`与`gradlew*`：固定Gradle9.4.1。
- `.github/workflows/android.yml`：结构、JVM、Lint与APK编译；不运行设备测试。
- `tools/check-project-structure.ps1`、`check_project_structure.py`：当前边界和兼容护栏。
- `tools/test_project_structure.py`：护栏负向fixture。
- `app/src/test/`：真实Room/Robolectric、领域、ViewModel、Compose与备份回归。
- `app/src/androidTest/`：实际设备交互、媒体归档、数据库迁移及维护回归。
- `tools/verify_diary_only_apk.py`：普通日记APK退役边界；`tools/release/`：正式签名工具。

本轮验证见[验收记录](docs/architecture-foundation-acceptance.md)。[历史代码索引全文](docs/history/codebase-map-before-2026-10-23.md)保留旧AI和阶段实现的原始记录。

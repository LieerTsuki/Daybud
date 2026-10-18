# 代码索引

- ui/settings/SettingsScreen.kt、V1SettingsScreen.kt、V1BackupSettingsSection.kt：紧凑主题、模块提醒、数据操作卡片，保留维护/备份状态保护。
- ui/settings/SettingsPlatformSection.kt、DatePresentation.kt：按需打开权限/关于/完整离线许可证，固定YYYY-MM-DD展示并保持旧偏好存储兼容。

- ui/subscription/SubscriptionWorkspace.kt、SubscriptionBillCards.kt、SubscriptionBills.kt：订阅列表与按月/日期账单、人民币预计/实际分离，替代旧统计图表。
- ui/subscription/SubscriptionDialogs.kt、SubscriptionDateInput.kt、SubscriptionViewModel.kt：精简表单、日期选择、人民币新建及历史字段保护。
- notification/SubscriptionReminderRules.kt、SubscriptionReminderScheduler.kt、SubscriptionNotificationHelper.kt：自动扣费当天09:00、旧提前身份清理和通知点击；不向持久偏好写0。
- data/repository/SubscriptionRepositoryImpl.kt：生产Room订阅/付款事务和历史隐藏字段保留；本轮结果见docs/subscription-bills-acceptance.md。

- domain/model/HabitForest.kt、domain/repository/HabitRepository.kt：树种、树与水滴读模型及持久化接口。
- data/local/entity/HabitTreeEntity.kt、HabitWateringEntity.kt、dao/HabitForestDao.kt、migration/HabitForestMigration.kt：Room7新增表、聚合查询及6→7非破坏性迁移。
- data/repository/HabitRepositoryImpl.kt：任务与种子原子创建、手动浇水、成熟重种、撤销级联和树种编辑。
- ui/habit/HabitScreen.kt、HabitViewModel.kt、ForestSection.kt：打卡、树种编辑、月历与统一森林、选树浇水面板和世代保护。
- ui/habit/ForestScene.kt、ForestLandscape.kt、TreeIllustration.kt：连续卡通草地、共享树木场景、拖动/缩放/查找、六树种五阶段插画与写入后水滴动效。
- domain/backup/V3BackupSchema.kt、V3BackupValidator.kt、data/backup/V3BackupJsonCodec.kt、ForestBackupConverter.kt：当前format3/schema7和旧备份严格转换；历史V1/V2契约不改写。

- app/src/main/java/com/example/lifemanager/ui/navigation/V1ViewModelFactory.kt：生产计划与设置所有者接线。
- ui/plan/PlanWorkspace.kt、PlanScreen.kt：四象限及月历下拉、导航与旧动作保护。
- ui/plan/PlanCalendarViewModel.kt、PlanCalendarScreen.kt：月范围读模型、周日首列、统一字号与日期手势。
- ui/plan/PlanDayDetails.kt、PlanDetail.kt、PlanEditor.kt、PlanDateInput.kt：当天明细、详情、编辑及日期/24小时选择器。
- ui/plan/PlanPresentation.kt、PlanDraft.kt、PlanEditorViewModel.kt：上海展示/输入、历史字段草稿与严格保存。
- domain/plan/PlanRules.kt、PlanEditPolicy.kt、PlanUseCases.kt：校验、持久化快照及冲突核对、写入和提醒回执。
- data/plan/PlanRepositoryImpl.kt：受世代保护的Room事务及关系保存。

以上相对Java路径均位于app/src/main/java/com/example/lifemanager；对应测试在app/src/test及app/src/androidTest同包下。本轮结果与图片见docs/planner-polish-acceptance.md；完整历史见CHANGELOG.md。

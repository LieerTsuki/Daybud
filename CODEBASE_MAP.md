# 代码索引

- app/src/main/java/com/example/lifemanager/ui/navigation/V1ViewModelFactory.kt：生产计划与设置所有者接线。
- ui/plan/PlanWorkspace.kt、PlanScreen.kt：四象限及月历下拉、导航与旧动作保护。
- ui/plan/PlanCalendarViewModel.kt、PlanCalendarScreen.kt：月范围读模型、周日首列、统一字号与日期手势。
- ui/plan/PlanDayDetails.kt、PlanDetail.kt、PlanEditor.kt、PlanDateInput.kt：当天明细、详情、编辑及日期/24小时选择器。
- ui/plan/PlanPresentation.kt、PlanDraft.kt、PlanEditorViewModel.kt：上海展示/输入、历史字段草稿与严格保存。
- domain/plan/PlanRules.kt、PlanEditPolicy.kt、PlanUseCases.kt：校验、持久化快照及冲突核对、写入和提醒回执。
- data/plan/PlanRepositoryImpl.kt：受世代保护的Room事务及关系保存。

以上相对Java路径均位于app/src/main/java/com/example/lifemanager；对应测试在app/src/test及app/src/androidTest同包下。本轮结果与图片见docs/planner-polish-acceptance.md；完整历史见CHANGELOG.md。

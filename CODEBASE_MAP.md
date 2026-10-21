# 代码索引

- domain/diary、data/diary：日记、持久草稿、媒体引用、统计/回忆、修订及Room8仓库；FileDiaryStorage实现私有流导入、暂存/pin/恢复和回收。
- ui/diary、ui/diary/DiaryViewModelFactory.kt：列表/日历、详情/编辑、图库/图片/视频与Activity所有者；AI独立页面在ui/diary/ai。
- ui/diary/ai/AiReviewDateInput.kt：AI回顾只读日期框、右侧日历按钮和确认/取消；DiaryRules.ordered与DiaryDao按记录日期、创建时间、ID倒序。
- domain/diary/ai、data/diary/ai：AI五段策略、来源适配、HTTPS客户端、Keystore/noBackup配置和受授权的周期Worker。
- data/diary/ai/AiModeStore.kt、CloudSendGate.kt：1.3.0候选的模式/模型修订持久化、旧自动授权暂停及与实际云端发送边界共享的准入；DiaryAiEngine统一云端与手机离线路由，沿用来源及原子保存保护。
- data/diary/ai/local/LocalModelCatalog.kt、LocalModelStore.kt、LocalModelDownload.kt、LocalDeviceCapability.kt：固定模型来源/摘要、流式校验导入、只读FD租约/删除保护、noBackup存储和设备资源检查。
- data/diary/ai/local/LocalGroundedPipeline.kt、LocalAiGateway.kt、PhoneAiRunnerAdapter.kt：实际分词与UTF8预算、全材料有界分片、AI选择原文索引、仅EOS完整结果接收；domain/diary/ai/GroundedAiResultPolicy.kt严格核对原句与心情标签。摘选分类和代表性仍有限。
- data/diary/ai/local/LocalInferenceHost.kt、LocalInferenceService.kt、AndroidLocalInferenceClient.kt、NativeLlama.kt：1.3.0候选的私有进程会话、Binder限额、取消/进程死亡处理及JNI模型FD所有权；LifeManagerApp.kt、di/DiaryModule.kt提供主进程业务图隔离与共享模式/发送依赖。
- app/src/main/cpp/：固定llama.cpp源码归档与SHA-256的CMake接线、64位CPU真实推理、32位明确不支持兼容层、原生边界测试及第三方许可证。四ABI编译/16KiB检查不代表手机推理质量或兼容性通过。
- data/backup/V4ArchiveContainer.kt、V4BackupManifest.kt、V4ArchiveFiles.kt、V4BackupRepository.kt：完整媒体归档、严格清单、租约、历史格式保留及原子替换；详见docs/backup-format-v4.md和docs/diary-acceptance.md。


- tools/release/release.py、VerifySignature.java、test_release.py：仓库外密钥签名、固定正式身份、精确SDK签名矩阵及发布保护测试；公开证书和轮换链同目录，私钥禁止入库。流程见docs/release-signing.md。

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
- domain/backup/V3BackupSchema.kt、V3BackupValidator.kt、data/backup/V3BackupJsonCodec.kt、ForestBackupConverter.kt：冻结format3/schema7和旧备份严格转换；历史V1/V2契约不改写。

- app/src/main/java/com/example/lifemanager/ui/navigation/V1ViewModelFactory.kt：生产计划与设置所有者接线。
- ui/plan/PlanWorkspace.kt、PlanScreen.kt：四象限及月历下拉、导航与旧动作保护。
- ui/plan/PlanCalendarViewModel.kt、PlanCalendarScreen.kt：月范围读模型、周日首列、统一字号与日期手势。
- ui/plan/PlanDayDetails.kt、PlanDetail.kt、PlanEditor.kt、PlanDateInput.kt：当天明细、详情、编辑及日期/24小时选择器。
- ui/plan/PlanPresentation.kt、PlanDraft.kt、PlanEditorViewModel.kt：上海展示/输入、历史字段草稿与严格保存。
- domain/plan/PlanRules.kt、PlanEditPolicy.kt、PlanUseCases.kt：校验、持久化快照及冲突核对、写入和提醒回执。
- data/plan/PlanRepositoryImpl.kt：受世代保护的Room事务及关系保存。

以上相对Java路径均位于app/src/main/java/com/example/lifemanager；对应测试在app/src/test及app/src/androidTest同包下。本轮结果与图片见docs/planner-polish-acceptance.md；完整历史见CHANGELOG.md。

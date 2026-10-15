# 完整备份格式 v2 · 统一计划

历史D阶段说明（2026-10-13）：D1定义格式与纯编解码，D2新增旧v1转换和版本分派，D3目标库快照/原子恢复和D4文件/维护隔离流程局部验收通过（实际测试、失败历史与限制见分批验收）。该阶段未接生产文件选择、用户库或Hilt，整体启用留E；当前接线状态以下方E6说明为准，已发布手机包仍导出v1/schema5。v2文件不支持在旧应用导入或把v6设备数据库降级。

2026-10-15 E6 源码接线验收通过：生产入口已接到本文的 v2-only 导出和严格 v1/v2 导入，使用同一个 v6 Room 单例与维护协调器。上述 D 批描述是历史准备状态；本批实际运行证据与限制见 [V1 分批验收](v1-unified-plans-acceptance.md)。未发布新APK，下载的0.1.1仍使用旧格式；真实设备、文件提供方与覆盖升级验收留F，不应把schema6应用降级安装回旧版。

## 信封与校验

F当前状态（2026-10-16）：源码版本1.0.0/code3，已在全新专用API35 AVD通过生产设置页、真实系统选择器和Downloads的V1恢复／V2导出恢复及独立保护备份／最终确认，逐值与原生世代74→75→76核对通过。旧V1文件由已发布0.1.1实际导出。此前采证脚本遗漏WAL的失败和修复后完整重跑均见[F交付验收](v1-delivery-acceptance.md)。这不是云提供方或真机验收；历史0.1.1下载入口不变，不向旧应用导入V2或降级schema6库。

UTF-8 JSON（无BOM）；必须且只允许七个顶层键：

- `format`：字符串 `life-manager-backup`。
- `formatVersion`：整数2；`schemaVersion`：整数6。
- `appVersion`：非空白字符串；`exportedAt`：可解析的ISO Instant字符串。
- `payload`：下列十张业务表数组及唯一 `settings` 对象，空表也必须存在。
- `payloadSha256`：小写64字符十六进制SHA-256。

hash只覆盖原payload的canonical JSON UTF-8字节：所有对象键按Kotlin字符串自然顺序排序，数组保留顺序，无额外空白，整数不转Double，字符串采用JSON转义。设置的默认提醒天数导出按升序；读取数组可乱序但不可重复。顶层版本/元数据不在hash中，因此hash是完整性校验而非身份认证、加密或签名。明文内容含个人数据，生产文件流程仍须隐私确认和保护备份。

合法空payload的canonical文本如下；其SHA-256为 `70530df1d173be20aba4bcff857f5c1c0b69ea6f4e014445a9504f48f82e427b`。

```json
{"habit_records":[],"habits":[],"plan_exceptions":[],"plan_legacy_refs":[],"plan_tag_cross_ref":[],"plans":[],"settings":{"dateFormat":"YMD","defaultCurrency":"CNY","defaultReminderDays":[],"planReminders":true,"subscriptionReminders":true,"theme":"SYSTEM"},"subscription_payments":[],"subscription_reminders":[],"subscriptions":[],"tags":[]}
```

## 明确业务字段

每行必须且只允许该表下列字段，标为nullable的字段也必须出现（可为JSON null）。Integer是完整signed 64位JSON整数；Int还必须在signed 32位范围。Millis是Instant epochMillis，Day是合法LocalDate epochDay，不使用浮点、数字字符串或当地午夜代替日期。

| 表 | 字段 | 主键/约束 |
| --- | --- | --- |
| plans | id,title,description,priority,color,location,participants,timeMode,dueAt,dueDate,startAt,endAt,allDayStartDate,allDayEndDate,timeZone,isCompleted,completedAt,repeatRule,repeatInterval,repeatDaysOfWeek,repeatEndDate,reminderEnabled,reminderMinutes,parentId,sortOrder,createdAt,updatedAt | id正Long；parentId引用plans.id、可null；父树无环 |
| tags | id,name,color,createdAt | id；name唯一 |
| plan_tag_cross_ref | planId,tagId | 二者联合主键；均需有效引用 |
| plan_exceptions | planId,occurrenceDate,isCancelled | planId/occurrenceDate联合主键；引用plans.id |
| plan_legacy_refs | source,legacyId,planId | source/legacyId联合唯一；planId另唯一且引用plans.id |
| habits | id,name,iconKey,color,frequencyType,frequencyValue,customDaysOfWeek,startDate,note,createdAt,updatedAt | id |
| habit_records | id,habitId,date,createdAt | id；habitId/date另唯一；引用habits.id |
| subscriptions | id,appName,amountMinor,currency,billingCycle,nextBillingDate,startDate,category,note,isActive,cancelDate,createdAt,updatedAt | id |
| subscription_payments | id,subscriptionId,amountMinor,currency,paidAt,note | id；引用subscriptions.id |
| subscription_reminders | subscriptionId,daysBefore | 联合主键；引用subscriptions.id；daysBefore为1/3/7 |

`plans` 的27列类型：

- Long：id、parentId（nullable）；Int：color、repeatInterval、reminderMinutes（nullable）、sortOrder。
- 字符串：title、timeZone；description/location/participants/repeatDaysOfWeek为nullable字符串。
- 枚举字符串：priority=NONE/LOW/MEDIUM/HIGH；timeMode=NONE/DEADLINE/TIMED/ALL_DAY；repeatRule沿用NONE/DAILY/WEEKLY/MONTHLY/YEARLY/CUSTOM。
- 布尔：isCompleted、reminderEnabled；Millis：createdAt、updatedAt和nullable dueAt/startAt/endAt/completedAt。
- nullable Day：dueDate、allDayStartDate、allDayEndDate、repeatEndDate。

有效时间按模式校验：NONE的保留字段不参与日程；DEADLINE必须恰有dueAt或dueDate之一；TIMED必须有startAt/endAt且结束晚于开始；ALL_DAY必须有两个日期且闭区间结束不早于开始。repeatInterval≥1，非空reminderMinutes≥0。所有非活动日期/时区/星期字段也必须具有合法类型和值，不能以未启用为由接受坏字段；但不自动删除或重写合法保留参数。星期原文为可null/空或逗号分隔1..7，保留合法顺序/重复/前导零，不重编码为编辑器形式。

timeZone须可由ZoneId解析，兼容有效固定偏移；历史空白title及历史完成字段组合保留，不强迫导入时“修复”。新建/编辑依然使用严格规则。来源仅TODO/SCHEDULE，legacyId允许完整signed Long；相同旧数字ID的两种来源是不同身份。新计划可以没有来源映射；不存在的目标、重复映射或一个计划被两来源复用均拒绝。

其余六张未变表沿用[v1字段类型与null契约](backup-format-v1.md)。金额保持Long最小货币单位，不转浮点；原打卡/订阅主键、文本和历史字段不归一化。nullable：habits.customDaysOfWeek/note、subscriptions.category/note/cancelDate、subscription_payments.note。其余字段不可null；标签/金额/日期/枚举/星期/币种类型由静态白名单检查。旧表的合法signed ID和历史记录语义保留，不把编辑器规范强加给文件。

## 新设置

`settings`必须恰好六个字段：theme（SYSTEM/LIGHT/DARK）、dateFormat（YMD/MDY/DMY）、defaultCurrency（有效有小数精度的ISO货币代码）、planReminders与subscriptionReminders（布尔）、defaultReminderDays（无重复的1/3/7整数数组，允许空数组 `[]`，不允许JSON `null`）。不允许旧todoReminders/scheduleReminders、id、SQLite mask或本机世代混入。数据库mask转换属于后续目标库适配，不在本codec中做SQL。

## 拒绝与资源边界

- 64MiB上限包括完整UTF-8信封、字段键、转义、hash和元数据；编码先计数再分配输出，解码先检查输入字节数。测试可指定更小边界，但不可提高默认上限。
- 所有十表行数合计≤100,000，包含来源/关系/例外/记录行；设置对象不算业务行，其默认天数数组最多3项。父链使用迭代线性检查；转换后的新增来源行同样必须计入，D2超限拒绝、不截断。
- strict解析拒绝decoded重复键（包括转义同名）、非法UTF-8/Unicode、浮点/指数/溢出整数、缺失/未知键、尾部内容、过深JSON、错误hash及版本。对象字段总预算有界：v1保留原20/行预算，v2按27列白名单预算，另50个信封/设置/表键余量；预算来自代码中的版本契约，不由文件指定。此预算不授权未知业务键。
- 不包含app_maintenance、plan_reminder_state、PendingIntent、通知到达/投递决定或本机世代；这些设备状态不可导出/导入。后续恢复事务必须保留设备技术库存直到精确清理成功。

## 旧v1兼容与版本分派（D2隔离接口）

`VersionedBackupCodec.encode`只导出2/6；`decode(bytes, legacyTodoZone)`接受1/5或2/6。先检查完整输入字节数和严格UTF-8，再用固定v2成员预算的strict解析识别格式；随后对应codec再次完整验证。旧v1第二遍仍使用原20成员/行预算，v2为27，不由文件版本自行提高预算。此为受限内存解析（额外解析一次），不是流式文件或低内存/性能保证。未知版本、错配schema、重复decoded键、未知字段、校验错误立即拒绝，不以失败后fallback尝试别的格式。

v1的原payload hash必须先通过原codec；转换不拿目标hash冒充原hash，也不修复原文件。`LegacyBackupConverter`直接调用时也严格检查原文档、转换后v2语义和完整canonical信封字节数；它不能对没有原文件字节的对象认证原hash。版本入口已完成原字节hash校验再调用转换器。

旧表总行数增加 `todos.size + schedules.size` 条来源映射，预检目标总数≤100,000；50000条单独旧待办恰好转换成100000行业务记录，再多一条拒绝。原文件可能在64MiB内、但转换后的信封因27列与来源映射增大而超限，此时拒绝、不截断；目标字节预检复用v2编码的信封/转义规则，不为计数分配输出字节数组。

转换复用数据库迁移的LegacyPlanConverter/References/Relations：TODO signed旧ID升序，然后SCHEDULE signed旧ID升序分配1起的正Long，保留来源ID（含Long极值）。相同数字ID或标题不合并；父、标签、例外按来源映射，父可以在子后面。原文本、未活动时间/重复字段、历史完成组合、六张未变业务表、设置和源appVersion/exportedAt无损保留。合法空标题/固定偏移时区不归一化；v1结构合法但转换后有效时间、重复间隔或提前分钟无效时明确拒绝，不修复。

新计划总提醒为旧TODO/SCHEDULE开关OR，单项继承对应来源；旧TODO提前分钟15，旧全天单项关闭但保留分钟，空分钟不补值。旧TODO缺少时区，只使用调用方显式传入的恢复设备ZoneId；旧SCHEDULE原时区不变。返回`BackupImportResult`包含v2文档、来源V1/V2、转换后十表真实行数（含空表和映射），仅存在旧TODO时给出`legacyTodoTimeZone`，供E展示来源说明。这些来源元信息不是认证、不进入导出payload；v2直接读不制造旧时区说明。不得导入技术库存或本机世代。

原生产v1codec/Hilt/文件工作流未变；D2未执行实际文件导入、保护备份写入、恢复确认、事务替换或设备测试。后续目标库与维护适配、E整体接线仍待完成，实际验证结果见[V1分批验收](v1-unified-plans-acceptance.md)。

## 目标库快照与恢复（D3隔离接口）

`V2BackupRepository.snapshot`在完整13实体v6目标Room装置中使用同一事务读取十业务表及设置。先计数（含映射），在SQLite中计UTF8文本字节（含设置），再读取严格类型的原始字段。设置只允许id=1单行，mask为0..7，主题/日期格式/币种和两种布尔值严格检查；本机世代同样必须id=1单行、非负整数，仅读取健康检查、不导出。读取失败不返回假空库，不补默认值。

快照/恢复payload检查与完整文件限额分开：repository不制造appVersion/exportedAt，只检查原字段/设置、100k业务行及canonical payload字节≤64MiB；真实文件codec再检查带版本/hash/元数据的完整信封。文本大小合法仍可能因转义或结构超限而拒绝；payload刚好通过不保证完整文件也通过。这些是受限内存表示，不是Android低内存、流式或大库性能保证。

`V2BackupMutationRepository`只执行持久化事务，不是用户授权入口。恢复前验证源并复制归属到操作内的行/字段Map、列表和提醒Set，重验自有原始快照；调用方随后改值不能改变要恢复的内容。最终确认/保护备份/维护准入/提醒清理与提交后回执由D4/E连接。

恢复和清空均在事务内核对原世代、当前合法设置和自增序列。拒绝旧世代或已耗尽的本机世代；不导入文件中的本机状态。按静态外键依赖顺序完整删除/插入，不按schema展示顺序盲插（tags须先于关系），plans先插null父值再第二遍恢复前向父引用；不关闭外键。恢复包括十表与新设置，清空只清十表并保留设置。

### 用户确认的自增边界（2026-10-13 · 方案1）

plans/tags/habits/habit_records/subscriptions/subscription_payments为AUTOINCREMENT表：恢复前拒绝其`id=Long.MAX_VALUE`，避免提交后对应表无法再自动新建。备份编解码仍保留完整Long契约（可读取/导出该值用于救援），不是修改文件格式；legacyId、金额、毫秒等非自增主键的Long极值不受影响，其他合法signed历史ID不归一化。旧v1的TODO/SCHEDULE旧ID先经D2映射，只有转换后的自增主键适用此检查。

目标库的序列必须类型正确、无重复、不低于当前最大正ID、没有耗尽；非空表缺失序列也拒绝，空表没有序列合法。已耗尽时恢复/清空明确报错并保留原库，不把删除该行当作修复，不重编号、不手动修改sqlite_sequence。正常SQL插入自然推进高水位，后置检查为原高水位与导入最大正ID之max；清空保持原序列，不存在和已有零值保持各自状态。

成功只推进本机世代一次。提交前重新核对实际十表原字段与设置（按记录内容，不以文件数组位置表达数据库排序）、世代、自然序列高水位和五列技术库存的流式固定宽度指纹；SQL错误、触发器悄改、故障或提交前取消整体回滚。所有业务行/表和最后提交前检查取消。本机plan_reminder_state保留，即使业务ID重用，也不从备份导入它或清除它；直到后续精确系统身份清理成功才由提醒组件处理。数据库事务不等于系统闹钟/通知已清理。

D3的真实Room证据限隔离合成测试库，不是个人数据库恢复、实际文件导入/保护文件写入、设备覆盖升级或生产接线。D4测试文件/维护证据见下文，当前生产路径与已发布APK不变，E/F仍待完成。

## 文件和维护流程（D4隔离实现）

`V2BackupFileUseCases`消费同一目标库快照、VersionedBackupCodec和既有BackupFileStore。普通导出在原世代许可内读一致快照，释放许可后编码/写文件；提供方缓慢不阻塞维护准入。输入预览先使用应用私有、有界临时副本，完整验证v1/v2后返回真实十表数量及来源说明。旧TODO的缺失时区由调用方明确注入，v2不制造旧时区说明；原文件不修改，临时文件成功/失败/取消均清理。

直接写文件和恢复准备在第一次挂起前验证并拥有表Map、行List、字段Map和默认提醒Set，再验证自有文档；调用方挂起后改值不改变确认/写入的快照。codec/文件工作在注入后台dispatcher；直接构造的恢复文档同样检查完整信封字节上限，限额失败不打开保护文件。受限集合复制/校验及多份内存表示不是低内存或UI性能保证，实际UI后台调用/隐私/操作身份接线仍归E。

输出成功必须flush/close后独立重开、有界读取、完整校验，并且来源确为v2且整个文档等于本次自有文档。另一份合法v2，或转换后看似相同的v1，都不能替代本次保护备份。失败不删除用户选定输出，可能留下残片或未确认成功的完整文件；再次恢复仍须重新验证。

`V2BackupMaintenanceWorkflow`维持同一冻结会话：排空已准入操作→核对原世代→捕获旧提醒身份→当前库快照/保护另存→重读验证→独立最终确认→D3原子替换或清空→精确提醒清理→释放冻结/请求新鲜校准。选择器、确认和文件I/O不持Room事务/模块锁。恢复必须保护备份；清空可明确选择不备份，但仍独立最终确认、保留设置。相同确切来源URI在打开输出之前拒绝；不同URI指向同文件的提供方别名仍无法通用识别，应另存新文件。

`V2BackupReminderCaptureImpl`在短Room事务中严格捕获双来源signed旧ID、订阅ID及全部五列技术tokens（含已删除业务项），结束SQL事务后才通过领域端口捕获系统计划卡片tokens，仍在同一冻结会话/提交前。闹钟B与卡片A分开保存，无技术行的卡片不漏掉，枚举失败不当空列表。PlanBackupNotificationSource复用严格归属的现有系统库存；清理消费C2c2现有UnifiedMaintenanceReminderEffects，不扩大通知归属范围，同ID新世代不会被旧精确身份误撤。技术行在D3提交时保留，直到对应平台取消成功才由提醒组件处理。

短提交完成交接采用V2BackupWriteCancellation：原writer控制D3每行/元数据/最终SQL-body取消guard，仅Room实际endTransaction及已提交证据交接受NonCancellable保护。SQL体取消回滚；提交体完成后的返回取消不虚称回滚，不再次写入。提交证据保存于维护/dispatcher返回之外，丢返回时在冻结尚未释放前短读同库的已完成事务世代：原值表示本次未推进，防溢出的精确原+1证明提交；不可读或其他值明确为不确定。取消分别有已提交/不确定标记，E需据此先重新读取数据，不自动重放恢复。早期raw证据读取曾看到未提交CAS导致误报，完整诊断和修复证据见分批验收。

进入会话后的成功/失败/取消出口均请求读取当前数据的新鲜校准；正常出口还在释放冻结后立即8秒限时校准。清理/请求/校准失败报告提醒pending，数据库提交不等于系统效果完成，不承诺跨系统exactly-once。

D4真实文件证据限测试私有临时副本/字节流与完整合成Room，另有Robolectric系统卡片/精确清理集成；不是真实SAF选择器、Downloads/云提供方、个人库恢复、设备执行或覆盖升级验收。该批验收时生产0.1.1/schema5/default v1与APK不变，E/F尚未完成；当前接线状态见页首E6说明，实际结果与运行警告见[分批验收](v1-unified-plans-acceptance.md)。

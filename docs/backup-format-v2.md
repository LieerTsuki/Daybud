# 完整备份格式 v2 · 统一计划隔离接口

日期：2026-10-13。D1定义格式与纯编解码，不接文件选择、实际Room恢复或生产Hilt；当前手机应用仍导出v1/schema5。D2才校验并转换旧v1文件，后续子批验证目标库恢复/维护，E整体启用。v2文件不支持在旧应用导入或把v6设备数据库降级。

## 信封与校验

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

当前v2codec只接受2/6；原生产v1codec只接受1/5。D2的版本分派必须先校验旧原payload/hash/关系，再共享转换、再验证v2，不能直接把v1对象交新DAO。D1没有实际文件导入、保护文件写入、恢复确认、事务替换或设备执行证据；实际测试结果见[V1分批验收](v1-unified-plans-acceptance.md)。

# V1 统一计划实施计划

日期：2026-10-10。依据：已获用户全文确认的 [设计](../specs/2026-10-10-v1-unified-plans-design.md)。
分支：`feat/v1-unified-plans`，用户选择在当前干净分支开发，不创建工作树。

`writing-plans` 技能不可用，使用文件级任务、明确依赖与验收记录替代。Buddy 默认的新分支 / PR / 网页验收流程不直接套用原生 Android；不新增 Linear、网页依赖或流程文档。沿用现有 Gradle、TDD、独立审查及 Android 设备验收。总体路线不是一次可交付的单任务，每批在执行前细化并检查覆盖；A/B1/B2/B3/C1/C2a/C2b/C2c1/C2c2/D1/D2/D3/D4/E1/E2/E3/E4 已完成局部验收，新版备份设置及实际生产接线仍在最终 E 激活关口。

## 目标与关键连接

| 必须成立的用户结果 | 实现与连接 | 验收 |
| --- | --- | --- |
| 待办加日期成为日程，身份和内容不变，两类可完成/撤销 | Plan 编辑 / 完成用例 → PlanRepository → 同一 plans 行 | 规则、Room、Compose 测试 |
| 旧待办/日程、标签、父子、例外无损升级，打卡/订阅不变 | 共享 LegacyPlanConverter → 5→6 Migration / v1 备份转换 → v6 验证 | 字段比对、完整迁移链、故障回滚 |
| 日期不是假区间，长期目标不挤入今日，重复完成结束系列 | PlanTimeRules / PlanOccurrences → 列表、日历、冲突、统计、提醒 | 确定性时区 / DST / 边界测试 |
| v1/v2 备份严格验证再恢复，旧令牌不能写新 ID | 版本分派 → 校验 → 转换 → 保护备份 / 维护事务 → 世代 | codec、真实 Room / 文件 / 旧草稿回归 |
| 完成或关提醒后不会继续投递，也不会与旧广播重号 | PlanReminderScheduler / Receiver → 当前仓库、设置、世代、例外 | 延迟广播、升级校准、权限设备测试 |
| 四入口均为实装功能，可离线运行并保留草稿 | Hilt 单数据库 → 列表/编辑/日历/统计独立状态 → Compose / 导航 / Settings | JVM、设备 CRUD / 导航 / 适配 |

## 激活关口

旧 DAO、旧备份十表契约、旧 Receiver 和页面仍共同依赖 v5。A–D 可以开发底层并在独立测试装置验证，不提前改变生产数据库版本、不提前开放计划入口。未接入生产的构件明确标注为底座，不宣称用户已可使用。

E 是原子切换批：生产 Room v6、Hilt、统一仓库消费者、备份/维护、提醒、导航与设置一起切换；移除正常业务路径中的旧实现，仅保留旧文件 / 旧 Intent 兼容。若规模超出单批，先拆 E 的可编译准备提交，激活提交必须同时满足全部连接，不能留下双表写入、空 Tab 或不可运行 APK。

版本 1.0.0 / code 3 仅在统一功能接通并验收后修改。A–D 不安装到个人设备，不删除旧表、不清除现有数据。源码撤回不等于设备数据库可降级。

## A · 统一领域规则（本轮）

四个顺序任务，限于五个 Kotlin 文件及文档，不修改 Room / Hilt / UI / APK 安装身份。
下文 Kotlin 生产路径相对 `app/src/main/java/com/example/lifemanager/`；测试相对 `app/src/test/java/com/example/lifemanager/`，设备测试相对 `app/src/androidTest/java/com/example/lifemanager/`。

1. **模型和时间分类**
   - 新建 `domain/plan/Plan.kt`：统一身份、内容、四种时间模式、完成、重复、提醒、关联字段；领域不依赖 Room/Compose/Data。
   - 新建 `domain/plan/PlanRules.kt` 及 `test/.../domain/plan/PlanRulesTest.kt`：先测试 NONE/DEADLINE/TIMED/ALL_DAY 分类和有效时间校验；inactive 字段保留，不凭它们分类。明确重复在 NONE / DEADLINE 不生效。
   - 验收：缺少日期、两个截止值、逆序区间、同日全天、非法时区、非活动参数行为。
2. **完成和移除日期**，依赖 A1
   - 同一规则文件实现完成幂等、撤销清时间；移除日期只改模式 / updatedAt，保留原参数；添加日期保留 ID / 内容 / 完成 / 审计。
   - 同一测试文件覆盖重复系列参数和例外不被清除、重复点击不改完成时刻、编辑不撤销完成。确认 UI 属于 E，不把领域函数当作已完成交互。
3. **全库统计**，依赖 A1–2
   - 新建 `domain/plan/PlanStatistics.kt`、`test/.../domain/plan/PlanStatisticsTest.kt`。
   - 全库分母不套当前列表筛选；重复计一条；今日完成按 injected now / displayZone，不要求有截止日期。
   - 逾期仅未完成、非重复、有效日期：精确截止/区间按 Instant，日期截止/全天按计划时区日界，NONE 不逾期。今天日程展开在 C，不在 A 伪算。
   - 验收：空集有限零值、混合状态与重复系列只计一条、无日期完成 / 展示时区跨午夜、精确 Instant 相等和刚过、仅日期 / 全天闭区间与计划时区 / DST，以及无效记录不能产生成功统计。
4. **验证与记录**，依赖 A1–3
   - 定向真实 RED/GREEN，全量 JVM `--rerun`，Debug 构建与 lint；独立规则审查，修复后再复验。
   - 修改 README，新增 `docs/v1-unified-plans-acceptance.md`，记录实际数量、底座未接线 / 迁移未执行等限制。
   - 只提交本批文件，提交前 `git diff --check`；不把旧 57 项设备结果写成本轮验收。

## B · 无损转换、v6 数据实现与迁移装置（依赖 A）

拆为字段转换、存储事务、迁移验证三子批；本批开始时补充逐文件子计划。

### B1 · 第二批的字段与身份底座（2026-10-10）

1. 新建 `data/plan/LegacyPlanRows.kt` 定义统一原始存储行、来源映射、转换输出、标签/例外行与旧提醒偏好；输入直接使用现有旧实体的原始字段，不经领域集合归一化。Migration Cursor 与严格已验证的旧备份都可构造相同旧输入。
2. 先写 `test/.../data/plan/LegacyPlanConverterTest.kt`，再新增 `LegacyPlanConverter.kt`：传入新主键，不做旧 ID 加偏移；旧待办 parent 先 null，独立保存 parent 来源键；逐值保留标题/备注/Long/空值/重复星期字符串，初始化新增字段和旧四组合提醒资格。不是全库迁移或备份解析器。
3. 先写 `LegacyPlanReferencesTest.kt`，再新增 `LegacyPlanReferences.kt`：以 `(source, legacyId)` 解析父/标签/例外；纯 lookup 契约供 SQL 实现，内存映射供受限文件转换。拒绝映射重复、目标 ID 复用、找不到父或关系，不假定父 ID 小于子。全库环 / 标签外键检查留 B2 的事务装置，不能用局部 mapper 声称全库安全。
4. 按用户已确认的历史值兼容策略，先补领域读取/统计测试，再拆 `PlanRules` 的存量读取与新写入校验，统计使用读取校验。旧固定偏移时区、空白标题保留；新写入仍严格，不放宽坏时区或坏有效区间。
5. 定向真实 RED/GREEN、完整 JVM `--rerun`、Debug / lint、独立审查及最终复验；同步 CHANGELOG / README / 验收、提交并按本次推送授权交付当前功能分支。不改生产 schema、Hilt、UI、APK 或用户数据。

B1 对应设计 3/4/7 的共享转换规则与用户历史兼容补充。未包含 B2 存储事务和 B3 SQL 迁移链/故障回滚/设备升级，不能将 B1 验收当作 v6 已生效。

### B2 · 第三批：统一存储与事务底座（2026-10-10）

1. 在 `data/plan` 新增 PlanEntity、PlanTagCrossRef、PlanExceptionEntity、PlanLegacyRefEntity、PlanConverters、PlanDao 与关系快照；27 个 raw 字段保留，父外键 SET NULL、其他关联 CASCADE，来源联合主键和目标唯一。不改生产数据库注册和旧 Converters。新增 `test/.../data/plan/PlanRepositoryTestDatabase.kt`，只包含本批存储与维护表、fixture version 1 / exportSchema=false；它不是完整 v6，B3 再验证完整 schema 和 Migration。
2. 新建 `domain/plan/PlanRepository.kt`、`data/plan/PlanMapping.kt`；Flow 从同一 Room 事务读取标签和例外，读取错误向上传递。日期含非活动字段均严格解析，合法历史空白标题/固定偏移保留。原始重复星期串在无语义改动时保留，不在编辑内容、勾选或转换模式时重编码。
3. 先写 `PlanRepositoryRoomTest.kt`，再实现 `PlanRepositoryImpl.kt`：区分 insert 与 update，不使用 REPLACE/upsert 复活删除记录；保存与标签替换原子化，保留数据库当前的完成/创建状态，即使旧草稿保存前发生完成操作也不能覆盖完成。单独完成/撤销在事务内按存量读取规则验证，仅修改完成字段及必要的 updatedAt，幂等且不传播到子项；合法历史空白标题/固定偏移以及原完成字段组合不阻碍勾选，内容编辑仍严格。校验父存在、非自身及环；删除父解除子关联并清理自身标签/例外/来源。所有业务写在同一事务内核对原世代，普通写不推进世代；全局 MaintenanceCoordinator 准入仍由 C/E 用例外层负责，避免重复获取全局许可。
4. 测试真实 Room 而非假仓库：CRUD/转换同身份、原文本/非活动字段/星期串、标签通知 Flow、完成和重复例外、关系级联、坏关系/环/原世代与元数据拒绝、插入/更新/标签故障及协程取消回滚、旧草稿与完成交错保存、读错误。先观测实质 RED 再 GREEN；全量 JVM --rerun、Debug/lint、独立审查、文档与当前分支提交/推送。不注册新库、不安装 APK、不操作用户数据。

B2 是可复用仓库底座，不负责 B3 的迁移/世代推进或 C/E 的提醒、副作用、全局准入/结果发布、页面接线。测试装置只创建合成本批表；不能据此宣称真实 v6 或设备升级通过。

### B3 · 第四批：隔离的完整 v6 迁移装置（2026-10-11）

沿用已批准的架构设计；以下四项顺序执行、TDD 与独立审查，不改变生产 v5 注册或 APK 版本。迁移代码删除旧表只发生在测试专用数据库；未来 E 接通生产时才执行已验证的迁移。

1. 新增 `data/plan/PlanSettingsEntity.kt` 和 `data/local/migration/PlanV6Schema.kt`：目标设置合并为 planReminders，其他设置保留；定义统一表/索引 SQL 与新建默认初始化回调，测试专用 v6 配置使用真实全部实体（计划、标签、打卡、订阅、设置、维护），不是 B2 的子集 fixture。不注册 Hilt、不修改旧 Converters。
2. 先新增 `test/.../data/plan/PlanMigrationTest.kt` 与完整 `PlanMigrationTestDatabase.kt`，再实现 `data/local/migration/LegacyPlanSqlRows.kt` / `PlanMigration.kt`：严格 SQLite 类型/字段值读取；TODO 再 SCHEDULE 的旧 ID 升序分配新 ID，调用 B1 共享转换与关系规则，二次父/标签/例外映射。逐行比较转换前后全部字段，校验行数/来源一一对应/FK/树及未触及表；设置/世代损坏或耗尽拒绝，推进一次，最后删除旧关联及旧主表。Room 持有事务，迁移本身不管理提交，不把整库放进内存、不套用外部文件 100k 上限。
3. JVM 覆盖四开关组合、同号/极值/负旧 ID、父 ID 大于子、原文/空值/重复星期/非活动字段/完成、标签/例外、打卡订阅逐字段、坏源/孤儿/环/设置/世代/SQL 故障后原 v5 完整回滚；包括世代已推进、旧表已删除后的最终 Room schema 校验失败，以及至少一条 4→5→6 失败链恢复原始 v4（不得残留新增设置/维护行或 v6 表）。历史 1–5 链及新建 v6 schema 校验。v1 无导出 asset，沿用现有设备测试已说明的 v2 三张未变实体构造，不虚造官方 v1 快照。
4. 新增 Android `PlanMigrationDeviceTest` 与完整设备 fixture，项目专用无个人数据 AVD 验证迁移/新建/故障回滚；只指定测试串号/类，不连接个人设备。全量 JVM、Debug/androidTest 构建、lint、独立审查及最终复验；README/CHANGELOG/验收/schema README 同步，提交并推送当前功能分支，不合并 main 或发布包。若真实设备验证未完成明确保留 B3 未完成状态。

计划依赖检查：任务 1 的实体/SQL 是 2–4 的单一目标契约；任务 2 的同一 Migration 被 3/4 使用；测试 fixture 不切换生产；历史链产生 v5 默认设置/世代后仅推进一次；故障由 Room 整体事务回滚。任务内均先实质 RED 再实现，设备结果不替代字段级 JVM 回归。全局维护准入、v2 备份与生产接线仍归 C/D/E。

### B 总纲（非 B2 交付清单）

- 新建 `data/plan/LegacyPlanConverter.kt`：原始字段 DTO / 纯映射共享给 SQL Migration 和旧备份；不经旧 ViewModel，不将原重复星期 String 重编码损失表示。ZoneId 与旧提醒偏好由调用方注入。
- 新建 `data/plan` 下 PlanEntity、PlanTagCrossRef、PlanExceptionEntity、PlanLegacyRefEntity、PlanDao、PlanRepositoryImpl；新建 `domain/plan/PlanRepository.kt`。事务内 CRUD / parent detach / 防旧草稿复活，Flow 真实读错误向上传递。
- 新建 `data/local/migration/PlanMigration.kt`：逐行顺序 ID 分配、来源映射、二次 parent / tag / exception 重映射、源字段 / 行数 / FK / 环检查、设置合并和世代推进。先完整校验后 drop 旧表，全部 SQL 结束再次核验未触及表；失败回滚。
- 修改 Converters；生产 `LifeManagerDatabase`、工厂 / DI 的激活留给 E。测试专用 v6 Room 配置引用同一实体 / Migration，不能另写一套简化迁移测试冒充生产实现。
- 测试 `LegacyPlanConverterTest`、`PlanRepositoryRoomTest`、`PlanMigrationTest` / `PlanMigrationDeviceTest`：同 ID、Long 极值、父 ID 大于子、标签/例外、全天/跨区、原文本/完成、设置四组合、缺失/损坏设置与世代、插入故障、打卡/订阅逐值不变。历史 1–5 链与新安装 v6 均验证。

## C · 发生、冲突与提醒资格（依赖 A/B 契约）

### C1 · 第五批：共享发生计算（2026-10-11）

沿用已批准设计，任务紧密依赖同一发生表示，由主代理顺序 TDD、只读独立审查执行；不切换生产消费者。C2 再处理维护用例、Android 排程/Receiver/身份/校准/通知；C1 不代表整个 C 完成。

1. 新增 `domain/plan/PlanOccurrences.kt`：半开 Instant 查询窗口、点截止/日期截止/真实区间/闭区间全天/系列完成标记的明确类型，使用计划时区比较日期；NONE 不进入日历，已完成单次按原日期、已完成系列按 completedAt 一次显示，缺少历史 completedAt 不捏造。坏读取显式抛错，非活动字段不参与计算。
2. 新增 `domain/plan/PlanRecurrence.kt`：按原锚点直接跳到查询附近，DAILY/CUSTOM 无星期按天，WEEKLY/CUSTOM 有星期沿用旧锚点起算的七日块（不是改成 ISO 周）；WEEKLY 空星期集合回退到原锚点星期，包含多周间隔。MONTHLY/YEARLY 每次由原月日计算并夹到月末/闰年合法日；TIMED 保持原 elapsed duration，ALL_DAY 保持原 LocalDate 闭区间跨度，不按固定小时平移。非重复/首个锚点保留原 Instant；后续本地时间按 java.time 默认 gap 顺延/overlap 较早偏移解析。结束日期含起始当天，取消例外只过滤重复发生且按来源 ID/起始日。最多 10,000 个候选，超过明确抛展开限制异常，绝不截断成成功或套用旧固定两年提醒 horizon。
3. 新增 `domain/plan/PlanConflicts.kt` / `PlanReminderRules.kt`：冲突只对窗口内未完成 TIMED/ALL_DAY，按各自时区化为半开比较区间，排除编辑自身，触边不冲突，双方例外均生效。提醒使用同一发生器和锚点，日期截止/全天本地 09:00 减提前分钟，只返回未来 trigger；总/单项/权限/完成/空分钟/例外共同约束，不重设原重复锚点、不追补。世代和广播重新读取属于 C2，调用方不得将本函数视为完整投递授权。
4. 测试 `PlanOccurrencesTest` / `PlanConflictsTest` / `PlanReminderRulesTest` 的真实 RED/GREEN，覆盖窗口/跨日区间/跨时区/闭区间/DST、月末/闰年/多间隔/自定义、例外/完成/非活动参数、古老锚点跳跃/数值边界/预算、未来 trigger 和两年以上下一次提醒；全量 JVM --rerun、Debug/lint、独立审查、文档及功能分支提交推送。纯领域批不运行设备升级或 Android 闹钟验收，不借用 B3 的设备数字。

依赖核对：发生类型是任务 2–3 的共同事实；预算计数包含被取消的候选，避免全被取消时无界循环。窗口是调用方明确限定的范围，冲突结果不声称所有无限未来均无冲突。10,000 候选仅为计算工作边界，异常向上传递；后续 UI 必须处理，不等同于数据库/备份行数限制。输入只读，不修改 Plan、配置或持久化数据。

提醒接口明确为 `next(plan, now, ...)`，不是截取日历发生窗口：按 now + 提前量推导最早候选，懒加载至下一个未来 trigger、有效重复结束或日期表示边界，不设两年 horizon。返回 null 仅表示关闭/无资格/确实没有后续合格发生；预算耗尽或时间运算溢出必须抛错，不能返回 null。覆盖次日 00:05 发生、提前 15 分钟在前日 23:50 触发的跨窗口边界，以及 ALL_DAY 跨 DST 的日期跨度。

### C2 · 统一提醒与用例（分批实施）

#### C2a · 第六批：维护安全用例与提醒身份底座（2026-10-11）

紧耦合的准入/提交交接/副作用由主代理顺序 TDD；只读独立审查并行，不切换生产消费者。C2a 不是整个 C2 的 Android 提醒验收。

1. 新增 `domain/plan/PlanOperationCoordinator.kt`、`PlanReminderEffects.kt`、`PlanUseCases.kt`。顺序为共享 MaintenanceCoordinator 原世代准入 → 计划模块锁 → Room 事务 → 已提交回执交接 → 提醒同步。保存、完成/撤销、删除和例外均传原 token；普通写不推进世代，不重取 token 或排队重放。领域只依赖领域接口，提醒 Android 实现留 C2b。
2. 保存回执含持久化 planId/原世代，必须在第一次提醒挂起前交接给调用方；短交接采用 NonCancellable，可用于 E 的实际状态发布，不在其中做文件/网络/用户确认。数据库失败无回执/无提醒；交接失败异常携带已提交回执，取消仍是 CancellationException 且携带回执。提醒普通失败返回“已保存但提醒失败”，专门 retryReminders 仅重新读取当前身份，不再次保存/完成/删除。必须拒绝维护忙、旧世代，维护等待已准入操作及交接/提醒结束。E 不得再次套全局许可导致嵌套；真实 UI 的主线程发布和草稿消费仍须 E 验收。
3. 新增 `domain/plan/PlanReminderToken.kt`、`notification/PlanReminderIdentity.kt`。身份含完整正 Long planId、原世代、发生 LocalDate 与毫秒 trigger；专用 `lifemanager://plan-reminder/v1/` 命名空间，canonical 数字路径严格读取，不接纳旧 namespace、未知版本/路径、缺失/溢出/非 canonical 元数据或不精确的亚毫秒时间。完整 data URI 参与未来 PendingIntent 区分，requestCode hash 不充当唯一身份。无标题/个人内容；解析成功不等于 Room/权限/世代投递授权。此批不创建或注册 Receiver。
4. 先写真实 RED，再 GREEN：维护准入/顺序/交接/取消、提交后提醒失败与只重试副作用、模块串行、读错误；使用真实隔离 Room 与同一 PlanRepositoryImpl/MaintenanceCoordinator 验证身份转换、完成/撤销、例外与 C1 提醒计算共用事实、删除清理、世代和故障回滚。身份测试使用独立 literal、极值/collision、旧协议及坏输入。全量 JVM --rerun、Debug/lint、独立审查、README/CHANGELOG/验收同步，提交推送当前功能分支，不合并 main 或发包。

任务连接检查：事务返回 ID 是交接/提醒/重试唯一身份；准入包住模块锁及副作用，不能嵌套全局准入；提醒 port 消费当前仓库值而非旧表单，缺失项应撤销。真实库 fixture 的世代由同一库查询，合成故障仅作用于测试库。协议仅是值身份，C2b 才提供 Android Intent 的类型交叉检查、当前 Room 资格、投递去重及排程实现；不把纯协议测试算成 Android 行为验收。

计划审查补充：所有用例实例和 retryReminders 共用同一 PlanOperationCoordinator，锁覆盖当前事实读取、回执交接和 effects 完成；维护外层不得提前释放。PlanReminderEffects 的义务是撤销此计划的旧发生/trigger 闹钟与通知，再按当前 Room/开关/权限/例外安排合格候选；完成、删除、改时间、关闭单项或变 NONE 均适用，读失败不能当作无资格。C2b 实现/测试真实 Android 行为，C2a 的记录型 port 只验证当前事实和调用顺序。

取消边界补充：新增领域内部 PlanWriteCancellation 标记，由用例捕获原 writer Job；Room 的 mutate 仅在此标记存在时把事务 body 放回原 writer 的可取消上下文。事务外的提交确认、ID 返回和短回执交接在 NonCancellable 交付桥中完成，提交前的 body 取消/异常仍由真实 Room 回滚。body 成功后的取消不把已提交行当未保存；返回后仍传递带回执的 CancellationException，不吞取消或继续提醒。无标记的直接仓库路径保持 B2 语义。测试增加真实 SQL 执行时取消，以及真实事务已返回、用例尚未接收到 ID 时取消的确定性边界；并验证共用锁的完成/删除/retry 与维护排空。

异常交付补充：已提交回执保存在整个 maintenance.run 之外，取消在该外层调用退出后包装，防止内部可取消 withContext 保留原始 Job 取消原因而丢失 receipt；retry 在同一外层边界用已有回执包装。交接/提醒挂起/retry/已提交返回测试均实际取消 Job，在 writer 内直接捕获用例异常并检查真实 ID/原世代；取消后的 Deferred.await 可返回原始取消，不把它作为包装类型保证。提醒仍在 NonCancellable 交付桥之外，未提交取消无回执。

协议固定为 `lifemanager://plan-reminder/v1/<planId>/<generation>/<epochDay>/<epochMillis>`。planId 为 1..Long.MAX_VALUE，generation 为 0..Long.MAX_VALUE，日期必须是有效 LocalDate epochDay，触发毫秒为完整 signed Long；十进制 canonical 表示（只有负值带减号，无加号、前导零、负零或空白），不接受 percent 编码别名、尾斜杠、query/fragment、额外字段或其它 scheme/host/version。固定 literal `lifemanager://plan-reminder/v1/17/4/20454/1767260700000` 对应 2026-01-01 / 09:45Z，不以实现生成期望值。

#### C2b · 第七批：隔离 Android 排程与投递（2026-10-12）

沿用已批准设计与下一批授权，紧耦合的持久化身份/排程/投递由主代理顺序 TDD、独立审查。本批不注册 Manifest/Hilt，不激活新业务入口；C2c/E 再统一迁移和接线。旧提醒的 SharedPreferences 技术缓存不扩展到新版；新增 Room 技术表只有一条当前身份/状态对应每个 planId，无业务外键，删除计划后仍可撤销系统身份。技术状态不作为备份业务数据或投递授权。

1. `domain/plan/PlanReminderStore.kt`、`data/plan/PlanReminderStateEntity.kt` / Dao / RoomPlanReminderStore：保存完整 token 与 pending/delivered，匹配状态原子更新；读错显式失败。新增 `test/.../notification/PlanReminderTestDatabase.kt` 包含已有 12 个完整目标实体及此技术表，使用同一 PlanRepositoryImpl 与真实世代/计划设置。既有 B3 的 12 实体迁移装置维持历史证据，本批不声称新增技术表已通过升级链；C2c 必须扩展统一 v6 Migration/fixture，E 才注册生产。
2. `notification/PlanReminderIntents.kt`、`PlanReminderSchedulerContract.kt` / Scheduler、`PlanNotificationHelper.kt`：严格 action/component/canonical data + Long extras 交叉匹配，标题不进入闹钟。调度者只接受 ID/原世代，调用方已有全局准入和模块锁；重新读世代/设置/当前行/例外/权限，复用 C1 future trigger。撤旧闹钟和通知，再写技术库存、创建 immutable PendingIntent，沿用精确闹钟拒绝时的非精确兜底。系统调用失败保留可清理身份并向上传递；不追补。通知以完整 URI tag / 固定 ID 和独立点击 URI 区分 Long/hash 碰撞，使用当前 Room 标题；点击消费者实际激活归 E。
3. `domain/plan/PlanReminderEligibility.kt`、`notification/PlanReminderDelivery.kt` / Receiver：Receiver 解析后通过现有 ReminderBroadcastRunner 原世代准入，再取共享 PlanOperationCoordinator；只处理 Room 中 pending 完整身份。资格复用 C1 发生/trigger，核对未完成/有效模式/开关/例外/当前世代/权限，拒绝提前、过期、旧/篡改/重复广播；截止 Instant 到点过期、日期截止/全天到当地日结束、TIMED 到 end 过期。发前再次核对开关/世代/权限，通知成功后标记决定并排后续未来发生，后续排程不撤刚发通知。失败不伪造“已消费”，请求新校准而非保存旧 payload；取消仍传播，异步 pending result 所有路径结束。缺少 Application 依赖实现时安全拒绝且 finish；Receiver 当前未注册，E 必须提供真实统一依赖。
4. 实质 RED/GREEN 与独立复核：真实 Room/AlarmManager/NotificationManager 的 Robolectric 集成；日期转换/完成/撤销/删除/改期/总和单项开关/例外，完整 URI/hash 碰撞、strict extras、早到/延迟/过期/世代/维护/取消/故障，数据库重开与重复投递，精确权限兜底。全量 JVM --rerun、Debug/lint；更新 README/CHANGELOG/验收，提交推送当前功能分支。无设备/UI/Release/性能或新增完整升级链验收，不复用旧设备数字。

边界：Room 与通知/闹钟不是跨系统原子事务，不能承诺任意崩溃点严格 exactly-once。成功决定持久化后重复广播不再 notify；notify 成功、标记提交前崩溃/故障时，以相同完整 tag 替换同一通知，不新增第二张卡，但可能再次提示。本批使用固定 Clock / 原世代，不重取 token。技术库存仅保存授权校验所需值，不含标题或个人内容；真正事实始终是当前业务 Room、设置和权限。

计划审查补充：通知清理由系统 activeNotifications 的 canonical tag 与严格 Long 身份 extras 定位此 plan 的全部通知，不仅依赖已推进的单行库存。业务 reconcile 撤全部；delivery advance 保留刚发完整 tag、撤此计划其它旧 tag。测试 A 已发/B 已排后重开数据库再完成/删除，同时核对 A 通知和 B 闹钟。

投递故障契约：notify 返回成功或未发送，普通失败抛错；只有成功后才按完整 token/pending 做 CAS 标记，未命中抛明确故障且不推进。未发送不消费，返回 NOT_SENT 请求新校准；mark 提交失败保留身份/同 tag 卡片，advance 失败保留已决定状态或已先记录的新可清理身份，均不撤刚发通知。Receiver 普通异常和 NOT_SENT 调用独立排队回调，禁止直接嵌套 retry/准入；取消不转普通成功，finally finish。到达资格用 C1 在 token.triggerAt 前 1ns 搜索的同一发生/trigger 进行精确比对，再验证 trigger≤now 及发生 expiry；不以 next(plan,到达时间) 排除延迟广播。全天 expiry 含 lastInclusive 整个本地日。

代码审查补充：候选计算的 now 在例外等挂起读取后采样；技术库存写完、建立 PendingIntent/安装闹钟前再次核对 trigger 严格晚于当前 Clock。若已经越过则明确提醒失败，保留可撤销身份和 advance 的 keep 卡片，重试从当前事实计算下一个未来候选，不安装过去 trigger、不把失败当成功。

平台语义澄清：[NotificationManager.notify](https://developer.android.com/reference/android/app/NotificationManager#notify(java.lang.String,%20int,%20android.app.Notification)) 无投递确认返回值。show 的 true / DELIVERED 只表示请求提交未抛错且提交前后没有可观察权限/频道阻止，不代表用户已看到通知；提交后发现阻止则撤本次完整 tag、返回 NOT_SENT，不 CAS/推进。系统异步拒绝、配额、勿扰/厂商策略或最后一次检查之后的撤权无法由此 API 完全确认，不用立即查询 activeNotifications 冒充用户收到的回执，也不对未确认请求无限重发。库存 delivered 表示这一尝试的持久决定，不是已读状态。此限制与 notify/Room 崩溃窗口分别记录；业务计划/配置不受影响。

#### C2c · 目标迁移、旧身份清理与统一校准（分批实施）

##### C2c1 · 第八批：提醒技术表完整迁移（2026-10-12）

C2c 分为迁移契约和校准/旧身份清理两个子批，先消除 C2b 技术表与 B3 目标库不一致。本批沿用已批准的隔离策略，紧耦合 SQL/Room fixture 由主代理顺序 TDD、只读独立审查；不更换生产 v5、旧提醒、Hilt/Manifest/UI/备份或版本。

1. 先扩展 `test/.../data/plan/PlanMigrationTest.kt`：真实文件历史 1–5→6 与新安装都必须有空 `plan_reminder_state`，五个 INTEGER 字段/主键与无业务外键严格符合 C2b 实体；技术表建表故障的事务回滚需到达证明。观察实质 RED 后，修改唯一 `PlanV6Schema.createPlans` 增加技术 DDL，沿用 `PlanMigration(5,6)`，不新增第二套 Migration 或改变世代推进次数。
2. 修改完整 JVM `PlanMigrationTestDatabase` 和 Android `PlanMigrationDeviceDatabase`，均纳入同一 `PlanReminderStateEntity` / Dao。KAPT 导出更新目标 fixture 的 6.json（未激活 v6 可细化；生产 2–5 历史文件不改）；使用真实 `RoomPlanReminderStore` 验证迁移后写入/匹配 CAS/文件重开、Long 极值、删除业务项不删可清理身份。补充库存建表中途失败及旧表删除/世代推进后库存最终 schema 故障恢复 v5/v4，核对原表/行/类型/自增及版本，无残留目标表。
3. 扩展同一 `PlanMigrationDeviceTest`：五条历史链与新建显式核对空技术库存、真实 Dao 持久化重开，以及库存目标 schema 故障恢复 v5/v4；保留原有字段/打卡订阅/坏源回归。在确认为项目专用、无个人数据的 API35 AVD 上只运行指定类；未运行或失败不能写成本批完成。
4. 全量 JVM --rerun、Debug/androidTest 构建、lint、独立代码复审及最终复验；README/CHANGELOG/本验收/schema README/计划同步，提交推送当前功能分支，不创建 PR/合并 main/发布 APK。不复用 B3 十项历史设备数字。C2c2 再完成隔离统一校准、旧身份清理及后续生产连接准备，本子批不是整个 C2c/C/V1 完成。

依赖/一致性检查：任务1提供唯一 SQL，任务2完整 Room delegate 验证实体/schema与SQL一致，两者构成同一 GREEN 检查点（只增加迁移 SQL 不会改变 Room 新建实体集合）；任务3消费同一 Migration/实体，不复制迁移逻辑；任务4汇总本轮真实结果。技术库存默认空且不伪造历史已投递状态，不含标题，不纳入业务备份；无FK是删除后保留可清理系统身份的既定契约。新安装由 Room 生成表而非初始化回调手写第二份技术DDL。生产 v6/schema与消费者切换仍只在 E，校准/旧通知点击/拒绝旧 Receiver 仍未实现。

##### C2c2 · 第九批：隔离统一校准与旧身份清理（2026-10-12–13，局部验收通过）

沿用已批准架构，五项紧耦合任务顺序 TDD；独立只读计划/代码复核。实现者主代理，现有分支不建工作树；不改生产 Hilt/Manifest/BootReceiver/旧 Worker/旧 Receiver 路由，不激活生产 v6 或发布版本。Buddy 状态脚本缺失时以本计划和验收记录追踪，不另建流程依赖。

1. 新增 `domain/plan/PlanReminderSources.kt` 与 `data/plan/RoomPlanReminderSources.kt`：只读 v6 总开关和完整带来源旧 ID，严格 SQLite 类型/布尔/单行设置/合法来源/有效映射；读错不返回默认关闭或空集合。PlanRepository Flow 的首个真实快照供当前计划集合，C2c1完整库与世代为共同事实。不修改业务行、世代、schema或备份。
2. 新增 `notification/LegacyPlanReminderCleaner.kt`：来源映射、旧 todo/schedule 技术库存及 schedule occurrence hints 的并集，复用旧平台身份协议（原component/hash/完整URI及最早无data），支持完整 signed Long及hash碰撞。新清理器明确实现严格 `cachedIds` / `occurrenceIds` 枚举，不使用会默默忽略坏key的旧ids()授权成功；`cancelForms`逐个尝试两种身份，一种普通失败仍尝试另一种，只有本ID全部取消成功才忘记库存/例外hint，不修改旧scheduler生产路径。旧通知只按实际旧频道/无tag/旧模块或历史未标记卡片清理，不用hash整数ID盲删订阅/新计划/其它通知。坏缓存明确失败；普通清理故障保留可重试线索，尝试其它旧身份后报告，取消立即传播。新版清理调用只在测试装置，生产旧消费者尚未退役。
3. 新增 `notification/UnifiedReminderContracts.kt`，扩展 C2b `PlanReminderScheduler` / `PlanNotificationHelper`：普通校准与系统重置分开，编辑用 reconcile 不变。普通校准保持当前世代/当前Room资格的延迟pending，不重装过去trigger；系统重置丢弃过去候选、只安装严格未来触发。未来pending可重装；已决定身份不再次notify。系统通知库存用完整canonical tag+四Long元数据识别，只保留最新且仍对应当前事实的卡片，旧世代/删除/完成/变时间/关提醒应清理；未知标签/频道/坏元数据不冒充所有权。取消捕获token只忘记匹配库存，不能误删同ID的新世代状态。
4. 新增 `UnifiedReminderReconciler`、`UnifiedSubscriptionCalibration`、`UnifiedMaintenanceReminderEffects` 和 `UnifiedReminderReconciliationWorker`：一次原世代全局准入覆盖旧清理、计划锁及后续订阅锁（计划锁释放再取订阅锁），不嵌套准入。全部权威设置/计划/来源/技术库存/通知token快照及孤儿判断都在同一PlanOperationCoordinator内，不用锁外预检快照授权清理；读错先阻止副作用。旧清理失败阻止本次新排程。计划普通副作用失败继续其它计划及订阅，汇总故障给Worker重试，取消不吞。订阅沿用既有仓库/算法/排程/去重；SubscriptionReminderScheduler仅增加显式可选v6状态reader，默认v5路径不变，不伪造旧Todo/Schedule开关。SubscriptionRepositoryImpl保留原@Inject LifeManagerDatabase构造，增加RoomDatabase+SubscriptionDao通用构造复用同一SQL业务逻辑，不切换当前Hilt。维护DTO以默认空字段追加闹钟tokens和已发通知tokens（全部捕获在提交前），必须包括A通知/B闹钟和没有技术行的通知；清理仅按捕获完整token，不bulk clear(planId)，不误清同ID新世代卡片/库存。维护清理无普通准入，恢复/清空之后再独立校准。新Worker独立未调度，默认缺Application依赖时retry；保留Java两参构造、普通/重置reason、APPEND_OR_REPLACE新鲜Room排队，无旧payload。
5. 新增真实完整Room/系统服务Robolectric测试及test-only fixture；覆盖读错/旧缓存/来源ID/无data/完整URI/hash碰撞、旧通知与其它模块隔离、pending延迟/重置/当前卡片/重复投递、完成/删除/设置/例外、旧世代/维护排空/并发写/实际取消、系统和单项错误后重试、订阅真实v6配置、维护提交后捕获身份清理、Worker依赖/反射构造/队列路由。先实质RED再GREEN，原C2b和全量JVM --rerun、Debug/androidTest/lint、独立复审；同步README/CHANGELOG/本记录后提交推送当前分支。不把生成身份或记录型port当Android验收；无UI/Release/性能/个人设备升级，设备若未运行明确不引用C2c1数字。

关键连接：全局准入→Plan锁内权威读源→旧清理（必须成功）→同一Plan锁内C1/C2b刷新与孤儿身份清理→释放Plan锁但保持同一外层许可→订阅锁/校准→汇总/Worker重试。补确定性并发创建用例，证明计划集合与库存之间不能插入同模块写入。系统重置只改变排程策略、不改变业务数据；普通校准不把已安装但等待平台延迟交付的pending误当已错过，不承诺Android可枚举或确认闹钟已存在。Room库存、来源和旧缓存全部缺失时无法发现任意未知旧闹钟，这是平台限制；E仍必须退役旧Receiver，确保清理失败/未知旧广播不会投递或写新ID。旧通知安全落首页、实际草稿/一次消费、新Application依赖/Hilt/Boot和Worker切换只在E；E还必须取消旧周期/即时Work，不能让新旧校准并行。D在同一维护会话的事务前捕获全部旧技术tokens和系统中已识别的通知tokens，事务后才调用V1维护adapter；A/B、无技术行卡片、同ID新世代保留及清理失败后retry均需测试。本批验证这一边界但不替换旧BackupMaintenanceWorkflow。通知提交非用户收到回执/跨系统原子事务的限制继续有效。

任务一致性：1读源是2/4输入；2清理是4排程前置；3同一库存/资格与系统实现被4消费，不建第二套发生器；4维护DTO的默认字段保留旧消费者兼容，实际v6捕获与生产绑定仍需D/E；5验证真实构件及缺绑定安全拒绝。五项共同验收，不分配修改同一文件的并行实现者。计划审查2 Important/1 Minor已据实际现有锁/单行技术库存/旧helper边界细化，上述新增构造只抽取已有依赖，不改变订阅业务行为，须全量旧回归。

维护重试连接补充：D的业务替换/清空事务不得删除本机 `plan_reminder_state`；它不从备份导入且无业务外键，旧token保留直到平台取消成功，使提交后清理失败/进程重开能由新鲜Room校准发现，不能只靠内存DTO跨进程重放。捕获DTO清理也须能在技术行意外缺失时取消已知精确身份，但不以此鼓励丢弃设备库存。通知卡片通过系统库存再次识别，原世代必拒绝投递。系统时间回拨可能让单次旧触发点重新变未来，须验证已决定身份不会由普通/重置校准重新授权；不靠已被用户划掉的卡片冒充持久决定。

代码审查/TDD细化：在既有五列技术DDL中严格兼容读取0/1并增加状态2（0未确认/到达待修复、1已决定、2安装请求返回并匹配CAS后等待到达）；每次安装/重装以及取消接受身份前先持久失效2，平台返回后仅全token+预期0可CAS到2，失败精确取消且保留清理线索。匹配到达先2→0再读当前事实，早到/NOT_SENT也请求新鲜未来修复，重复/不匹配/旧世代不可改另一身份。校准依据当前发生规则/同世代保留最新时间边界（1排除自身，0/2可修复同一未来token）；有限系列耗尽也不丢失pending B对已决定A的回拨边界。不是全历史exactly-once账本；明确业务编辑可重新授权。Plan Receiver使用opt-in取消/超时修复hook，许可退出之后仅enqueue重置reason（绕过晚到2保留），旧Runner调用签名和默认行为不变。E把Plan requestReconciliation绑定resetSystem=true，普通启动/周期false，boot/时间/系统重置true；禁止旧payload/ID/title进WorkData。新订阅清理限定canonical订阅tag/频道/实际id0/module/Long世代，不再调用组合旧频道stale sweep。

- 复用 C1 的 `PlanOccurrences` / `PlanRecurrence` / `PlanReminderRules` 和 C2a 用例/身份协议；发生集成测试验证共用算法，不重建第二套发生/重复计算。C2a port 的完整 Android 提醒实现和真实 UI 发布接线不得遗漏。
- 复用 C2b 的 Scheduler/Receiver/严格 Intent/资格与技术库存实现，不新建第二套；不提前注册未接线 Receiver。
- 消费并保留 C2c1 技术表迁移/schema/完整 JVM 与设备契约的回归，不重复实现第二套迁移。E 激活生产时使用同一实现；C2c1 未通过的验证不能标成已完成。
- 先新增隔离可测试的统一校准与旧身份清理实现，不更换当前 v5 的 Hilt / Worker / Receiver 调用路径。准备 ReminderReconciler / BootReceiver / Worker / Inventory / Notifications / AndroidMaintenanceReminderEffects 的接线变更；实际替换与旧 Receiver 协议拒绝只在 E 激活提交执行。C 结束时旧应用提醒仍正常运行，清理失败重试和不发双份的新版行为在独立装置验证。
- 测试发生规则、DST / date-only 09:00 / 全天、custom / leap / 月末、未来 trigger 而非仅未来 occurrence、完成/撤销、延迟广播、权限撤销、旧 ID / URI / 世代、旧通知安全落首页与新通知一次消费。
- reminder 数据失败明确区分“已保存但提醒失败”，不重插记录；全局开关不会重开单项关闭配置。

## D · v2 备份与维护适配（依赖 B/C 字段与身份）

C2c2五项任务于2026-10-13局部验收通过：53项新增测试，119项定向及1001项/87 suites全量JVM强制重跑均0失败；Debug/androidTest重新打包、lint0 error/55既有warning，计划及核心/Worker/Runner独立复审问题关闭。实际结果、RED/GREEN及状态语义细化见 `docs/v1-unified-plans-acceptance.md` 第九批，不引用历史设备测试为本轮结果。本批未激活生产v6/旧消费者/UI，D尚未开始；后续继续本分支，不提前切换默认codec或发布V1。

- 保留当前 `domain/backup/BackupSchema.kt` 的 v1 常量与现有 codec 消费路径；新增隔离的 V2BackupSchema / 验证器 / codec / LegacyBackupConverter，准备 BackupDocument / validator / mapping / canonical codec 的版本化接线。v1 先校验原 hash / 字段 / 类型 / 关系再转换，v2 单独严格验证，不放宽未知键。默认 codec / 导出版本只有 E 才切换。
- 新增隔离的 v6 snapshot / backup repository / mutation 实现并在测试专用 v6 数据库验证，不在 D 替换当前 BackupSnapshotDao、BackupRepositoryImpl、BackupMutationRepositoryImpl 的生产调用与 Hilt 绑定；BackupMaintenanceWorkflow 的新版表 / 来源 / 设置 / 提醒接线也只在 E 激活。
- 准备设置备份预览 / 计数与旧待办无时区说明，E 才更换生产消费者；保持保护备份、最终独立确认、原子完整替换、清空保留设置、本机世代不导入和所有失败/取消边界。
- 新建 v2 格式说明，保留 v1 历史文档；测试两版本往返 / 恢复、未知版本/hash/关系/环/Long 精度、转换增行后 100k 上限、64MiB、来源与保护文件、事务故障和旧草稿 / 文件回调 / 通知失效。
- 设备文件测试只在项目专用合成库上运行。

### D1 · 第十批：v2备份格式底座（2026-10-13，局部验收通过）

用户确认本子批范围。继续当前分支、不建工作树；紧耦合契约/校验/codec由主代理顺序TDD，独立只读复审。writing-plans技能不可用、Buddy状态脚本仍缺失，以本文件和验收记录跟踪。D2再实现旧v1转换与版本分派，后续子批处理真实Room快照/替换及维护文件；本批不得改默认BackupCodec/Hilt/UI/schema/版本或触碰用户文件。

1. 新增domain/backup/V2BackupDocument.kt与V2BackupSchema.kt：原始BackupValue/Row复用，独立六字段设置和文档/payload；十业务表白名单包含plans27列、标签/例外/双来源映射、原打卡订阅表。新plan ID为正Long，来源legacyId完整signedLong，映射联合唯一且planId唯一；技术库存/维护世代绝不纳入。旧BackupSchema和旧文档默认不变。
2. 新增V2BackupValidator，抽取旧BackupValidator通用字段/类型/唯一/FK/父树检查为共享内部工具，原v1入口语义不变；v2核对有效时间模式、repeatInterval/reminderMinutes及所有保留日期/星期/时区字段，兼容历史空标题/固定偏移和完成字段组合，不归一化。引用和设置严格，100k计入来源映射；父树线性迭代、不递归。
3. 新增data/backup/V2BackupJsonMapping.kt、V2BackupJsonCodec.kt；共用canonical UTF8/SHA256编码和strict JSON语法，但解析器字段预算按显式v2契约适配27列，v1默认20预算保持。固定envelope formatVersion2/schemaVersion6、严格六字段设置；hash针对canonical原payload，不转Double，重复键/非法UTF8/深度/字节/行数/未知字段版本拒绝。v2仅读v2，旧v1codec仅读v1；D2才分派并转换。
4. 实质RED/GREEN测试V2BackupValidatorTest、V2BackupCodecTest和test-only原始fixture：完整字段/Long极值/历史文本/非活动字段/四时间模式、设置/主键/唯一/孤儿/环/损坏日期/类型/enum、hash/独立canonical预期/重复键/未知版本/UTF8/64MiB及100k边界（缩小limits用于精确边界，加真实100k/深父链/27字段预算测试）。原v1codec golden与全量回归必须通过。
5. 新增docs/backup-format-v2.md并保留v1历史文档；全量JVM --rerun、Debug/androidTest构建、lint及独立复审，README/CHANGELOG/验收同步后提交推送feature，不合并main/PR/发布APK。无设备执行/实际文件导入/Room恢复/用户库升级验收，不把D1当整个D或V1完成。

连接：独立raw文档→静态白名单契约→共享通用检查+v2有效模式→mapping/strict/canonical codec；codec输出和输入均校验，相同完整原字段往返。字段预算是解析防护而非允许未知业务键；保持v1的既有读取/行数/字节/hash行为，无暂存API或空实现提交。五项于2026-10-13局部验收通过：29新增、最终1030项/89 suites JVM强制重跑均0失败，Debug/androidTest构建检查、lint0 error/55既有warning；计划复审和完整代码/parser/codec/文档2 Minor关闭。无设备/文件/Room恢复/生产激活验收，实际证据见分批记录，D2再做v1转换。

### D2 · 第十一批：旧备份转换与版本分派（2026-10-13，局部验收通过）

用户确认隔离实现，继续当前feature分支、不建工作树；生产v1 codec/Hilt/文件/Room/UI/schema/版本均不变。writing-plans不可用、Buddy脚本缺失，沿用本计划、TDD和独立只读复审。紧耦合的转换与分派顺序实施，不委派重叠代码写入。

1. 新增domain/backup/VersionedBackupCodec.kt：v2-only导出、v1/v2导入契约，返回v2原始文档、来源版本、仅旧待办实际存在时的注入时区说明及转换后逐表数量；来源元信息不进入备份payload，本机世代不进入文件。旧接口不替换。
2. 新增data/backup/LegacyBackupConverter.kt：直接调用也先BackupValidator校验；原表总行数加todos/schedules数的映射增行预检，超限即拒绝。TODO旧signed ID升序再SCHEDULE升序分配正Long，复用LegacyPlanConverter/References/Relations，父关系第二遍解析。原未变六表与文本/Long/非活动字段逐值保留，不引用领域编辑归一化；设置按OR及单项/全天契约转换。调用方显式注入ZoneId，只给旧TODO使用；源元数据保留。
3. 新增data/backup/VersionedBackupJsonCodec.kt：输入字节限额/严格UTF8、以代码固定V2预算完整strict解析识别版本，随后对应原codec独立再校验（v1原20成员预算保留）。只支持1/5、2/6，任何校验失败不fallback；v1原hash完整通过后才共享转换，目标V2Validator再检查及canonical信封字节限额预检，不为计数分配输出。encode只委托v2，不改变生产默认。
4. 新增真实converter/dispatcher测试，反射测试入口仅在RED阶段让缺实现成为断言失败，GREEN改用类型安全直接调用：原字段/双来源同号/极值/无父ID顺序假设、关系/设置四组合/全天空分钟、时区说明、未变表独立预期、输入不修改、旧原hash/完整验证优先、未知版本/字段/重复键/UTF8/数字/限制、转换增行精确及真实100k边界、转换后字节边界、版本路由与v2往返。保留两个golden及全量回归。
5. JVM --rerun、Debug/androidTest构建、lint和独立代码复审；同步README/CHANGELOG/格式v2/验收/本计划真实结果，再提交推送feature并核对远端。无实际文件操作/Room恢复/设备执行/生产激活/Release/性能验收，D3及E/F仍未完成。

连接：受限版本识别→版本专用严格原codec→旧原hash/字段/关系验证→共享纯字段/来源映射→v2验证及编码字节预算→带来源/真实数量的导入结果；转换器直接调用也校验目标v2和canonical字节限制，不返回未经验证的中间文档。v2直读不制造旧时区说明，导出只产生2/6。无SQL或文件副作用。严格识别会额外解析一次受限输入，不声称流式大文件或性能验收。计划复审两项Minor：明确直接转换保证；补源v1合法但目标有效时间/重复/分钟无效的拒绝，以及元数据/未变设置/逐表（含空表）计数验证，已纳入测试并独立关闭。五项局部验收完成：31项新增，最终1061项/91 suites JVM强制重跑全部通过，Debug/androidTest构建检查及lint通过（0 error/55既有warning），完整代码及补强复审均无遗留问题；真实证据见分批验收，不包含真实文件/Room恢复/设备或生产激活。

### D3 · 第十二批：目标库快照与原子恢复（2026-10-13，局部验收通过）

用户确认隔离v6持久化子批，沿用当前分支、不建工作树，不替换生产v5/旧备份/Hilt/文件/UI或版本。writing-plans与Buddy脚本仍不可用，使用本文件跟踪五项；顺序TDD实现共享SQL边界，独立只读复审。D4再准备保护文件/最终确认/维护效果，E整体激活。

1. 新增domain/backup/V2BackupRepository.kt，明确snapshot与replace/clear领域接口，使用V2BackupPayload和DataGeneration；新增data/backup/V2BackupSql.kt共享固定十表白名单、严格SQLite原值/设置/本机世代读取。继续复用BackupSnapshotDao通用只读接口，测试完整13实体PlanMigrationTestDatabase添加getter，实体/schema/生产绑定不变。
2. 新增V2BackupRepositoryImpl(RoomDatabase, BackupSnapshotDao, BackupLimits)：同一Room事务预检全表行数（含refs）及SQLite UTF8文本字节（含设置）后才加载原始字段；逐字段SQLite类型严格检查，再V2BackupValidator验证并检查canonical payload字节限额。无本机世代/技术库存输出。snapshot只有payload、不含appVersion/exportedAt；完整信封限额由已有v2codec在导出时再检查，不宣称低内存或流式。
3. 新增V2BackupMutationRepositoryImpl：replace在任何删除前验证目标payload/限制；事务内严格核对当前单行设置与本机世代、拒绝旧世代/Long上限。按静态FK依赖顺序删除/插入（不能沿V2Schema展示顺序；tags先于links），plans先以parentId=null插入，再第二遍恢复前向父ID；不关闭外键、不REPLACE清库、不覆盖文件、不修改sqlite_sequence。清空业务保留设置；plan_reminder_state完全保留，不导入或改写技术状态。
4. 同一事务推进本机世代一次，最终读取/核对当前世代及恢复后的原始十表/设置，发现SQL错误/触发器悄然改值/故障或提交前取消全部回滚；仅清空时检查业务为空与设置不变。每表/每行及最终提交前ensureActive；维护准入、保护文件、提交后回执与精确提醒效果属于D4/E，不把纯repository当完整维护授权。
5. 新增真实Room/Robolectric快照与mutation测试：全字段/Long/历史标题偏移/完成/自定义原文、双来源/父标签例外、设置mask/四时间模式、空库/空恢复/清空、FK与自增后续写入、v1经D2转换及v2往返恢复，超行/文本预检、坏库类型/设置/世代、坏输入、旧世代/耗尽、早晚SQL故障与悄改/真实取消回滚、同expected并发只一提交/一致快照、库存完整保留。RED仅测试adapter让缺实现成断言；GREEN后typed真实接口。全量JVM --rerun/Debug/androidTest/lint、独立复审及README/CHANGELOG/验收/格式说明同步，提交推送feature并核对远端；不执行设备或用户库操作/Release/性能，不合并main/PR/发布APK。

连接：同库rawDAO+Room事务→SQL预检及原值→v2验证/canonical payload预算→snapshot；经D2验证/转换的payload→输入验证→本机状态/世代核对→依赖有序完整替换→CAS推进世代→完整复核→commit。外部键只作为绑定值，标识符来自静态白名单。顺序/typed读取和后置验证复用同一数据helper，完整预期从独立fixture/SQL核对，不只让写/读同时丢字段也通过。计划复审Minor已核对：新增V2BackupValidator的payload重载，document入口继续先校验真实元数据，不制造storage元数据；显式测文本合法但JSON转义/结构超限、精确payload字节及payload合法而完整信封超限。五项局部验收通过：35项新增Room测试，输入所有权/设置COUNT预检两项Important经RED/GREEN关闭；另1项清理回归经RED/GREEN修复旧日程测试Main生命周期，1097项/93 suites全量强制重跑、Debug/androidTest构建检查和lint通过（0 error/55既有warning），代码独立复审全部关闭。完整重复复验、缓存范围、SQLite资源警告及失败历史见分批验收，不作为设备/文件/生产升级验收。

已由用户选择解决的Important平台边界：现有v2允许plans正Long.MAX_VALUE，其他五张自增表也保留signed Long极值；实际Room schema均为INTEGER PRIMARY KEY AUTOINCREMENT。SQLite在提交过MAX主键后，后续自动新建报SQLITE_FULL，删除该行也不清除sqlite_sequence上限。2026-10-13的纯内存SQLite合成探针及[SQLite官方说明](https://www.sqlite.org/autoinc.html)验证，不是Android设备测试。因此“逐值恢复所有合法主键”与“保证随后自动新建”不能对极端文件同时承诺。

2026-10-13用户选择方案1：恢复边界在任何删除前拒绝plans/tags/habits/habit_records/subscriptions/subscription_payments六张自增表的MAX主键；恢复和清空均核对目标库已有序列，耗尽时报错保留当前库。原codec/raw契约不变，legacyId/金额/毫秒等非自增主键Long极值不受影响，不重编号或重置sqlite_sequence。将正常SQL插入带来的自增高水位变化与禁止手工重置分清；后置核对序列等于原高水位和本次导入最大正ID之max（清空保持），流式指纹核对五列提醒技术库存未被触发器改动。补六表拒绝/既有耗尽/坏序列、可导出但不可恢复极端payload、正常高ID后续自增、取消和晚期触发器完整回滚测试。

### D4 · 第十三批：文件与维护流程（2026-10-13，局部验收通过）

用户确认五项范围，沿用当前feature；不激活生产v6/Hilt/旧codec/旧工作流/页面/Manifest/版本，不操作个人库或文件，不合并main/PR/发布APK。writing-plans/Buddy状态脚本仍不可用，使用本计划和验收记录跟踪。文件、会话和提交交接紧耦合，主代理顺序TDD，独立只读计划/代码审查，不并行修改同一接口或运行Gradle。

1. 新增domain/backup/V2BackupFileUseCases.kt：消费D2 VersionedBackupCodec和D3 snapshot，普通export只在原世代许可内读快照，释放许可后进行编码/文件操作；writeVerified无嵌套许可、输出关闭后有界重读、必须v2来源及完整文档一致。preview通过既有BackupFileStore受限私有副本严格校验v1/v2，显式注入旧TODO时区，返回真实计数/来源说明。复制验证后的自有文档以避免挂起期间caller修改；文件操作在注入后台dispatcher，隐私和操作UUID的真实UI接线归E。
2. 新增domain/backup/BackupReminderCapture.kt及data/backup/V2BackupReminderCaptureImpl.kt，目标库事务严格读取旧来源TODO/SCHEDULE ID、全部订阅ID及五列技术token（包括业务已删除项），从领域回调端口独立读取系统计划通知tokens；notification层提供现有PlanNotificationInventory适配器。维护冻结前已准入任务须先排空，捕获在同一会话/提交之前；不得只用一份单行技术库存代替卡片库存，不删除技术行。读错/坏类型/孤儿映射明确失败，不归一化signed旧ID。
3. 新增domain/backup/V2BackupMaintenanceWorkflow.kt：独立v2请求/已提交取消回执，复用维护stage/interaction/result。先验证并拥有恢复文档；session核对原世代→捕获旧身份→一致快照/保护另存→关闭后重读严格v2校验→独立最终确认→D3原子replace/clear→清理捕获旧精确身份。选择器/确认/文件I/O仅持冻结、不持Room事务/模块锁；restore必保护备份，clear显式可选不备份但仍最终确认。确认取消/异常/真实Job取消不提交；返回交接取消以维护期间世代核对证明已提交，不误报回滚或重插。成功/失败/取消会话出口都请求新鲜校准，正常出口释放冻结后立即8秒限时校准；提交后提醒失败报告pending，不重试数据写入。
4. 新增文件用例、捕获Room和完整工作流集成测试：真实codec/v1转换、私有临时文件/字节流及完整13实体Room，原值/设置/世代/序列/库存核验。覆盖不同合法输出/截断/关闭失败/权限/未知长度超限、临时清理、来源相同确切URI拒绝、选择/最终确认取消、维护排空与拒写、旧世代、源可变性、坏库存/映射、SQL失败/提交前取消、真实Job提交后取消/dispatcher交接、清理/重试/校准失败；技术B闹钟与A卡片分开捕获、同ID新世代不可误撤。旧V0全量回归不得省略；合成文件不进入Git，设备/真实SAF/云提供方或性能未执行则明确保留限制。
5. JVM --rerun、Debug/androidTest构建、lint及独立复审；同步README/CHANGELOG/格式v2/文件安全/本计划/验收，提交推送feature并核对main未变。不同URI的提供方别名、协作取消/跨系统非原子和受限内存限制沿用，不扩大到UI/生产切换/发布。

连接：D2校验转换→自有文档/真实预览→D4维护会话→D3快照与原子写入→C2c2精确清理与新鲜校准。纯SQL身份捕获和系统卡片通过领域端口拼合，domain不引用Android/Room/notification具体类；外部URI不得成为SQL或本地路径。D3用户方案1的六表MAX/目标序列耗尽拒绝保持，readonly极端救援导出允许，文件codec完整Long不变。实际进度和测试证据见分批验收。

独立计划复审补强（已纳入）：SQL捕获短事务结束后才调用系统通知端口，但仍在同一冻结会话、提交之前；枚举失败中止，不当空列表，测试端口断言不在DB事务内/准入仍冻结。已提交取消证明必须在会话释放前NonCancellable短读同一目标库，精确expected+1且检查溢出；证据保存在外层dispatcher/会话返回之外，读不到世代不等于回滚，明确不确定结果、不得重放写入。自有文档在第一次挂起前验证有界源、深拷贝外层表Map/行List/行字段Map/defaultReminderDays，再验证自有值；文件直接写入及恢复选择器挂起时修改caller集合仍使用原快照。

五项局部验收通过：42新增（文件12/捕获6/维护24），77项D4+D3组合及全量1139项/96 suites JVM强制重跑、Debug/androidTest构建检查和lint通过（0 error/55既有warning）；代码复审2 Important/1 Minor及异常世代补强全部关闭。精确事务Job取消与原caller/外层返回取消均保留；诊断确认曾在取消早返回时raw读取未完成CAS6但最终库回滚5，故采用V2BackupWriteCancellation短Room完成/回执桥、D3原writer+Room双guard、事务世代reader，不误报提交/回滚。保护快照以独立原值/关系比较，不把SQL行排序要求误写成业务身份；codec成功验证仍要求本次完整文档一致。所有失败过程、重复验证、运行资源警告及缓存范围见分批验收。未执行设备/真实SAF/云提供方/UI/Release/大库性能，生产仍0.1.1/schema5；E真实消费者、UUID/隐私/进程重建/导航接线与F交付仍未完成。

## E · 页面实现及统一生产切换（依赖 B/C/D）

准备子批分别完成列表、编辑/详情、日历/统计和导航集成，每子批 ≤5 个任务，生产切换最后一次完成。

### E1 · 第十四批：统一列表准备（2026-10-13，局部验收通过）

用户已确认本批边界；在当前功能分支顺序执行紧密耦合的查询、状态与组件，独立只读复审。真实生产接线仍留最终 E 激活提交，不创建空编辑/日历/统计按钮。

1. 查询规则与测试：`domain/plan/PlanListQuery.kt`，分类/状态/搜索/标签/优先级/日期/排序，复用 PlanOccurrences 与取消例外，展示时区窗口，日期排序无日期最后且 ID 稳定兜底。
2. 状态与用例接线：`ui/plan/PlanUiState.kt`、`PlanViewModel.kt`，真实仓库 Flow 失效后在 GenerationAccess 许可内重新读取；维护拒绝、读取失败禁写、世代切换清旧状态、完成回执与提醒失败分别发布，不重放写入。
3. 列表与筛选组件：`PlanScreen.kt`、`PlanList.kt`，复用原生卡通主题，真实空/载入/错误状态、可访问完成操作、滚动筛选、长文本与浅深色，不虚构统计或未实装操作。
4. JVM 查询/ViewModel/Compose 与真实 Room 集成验证、独立复审；全量 JVM 强制重跑、Debug/androidTest 构建、lint。设备/旋转/系统恢复/性能验证明确留后续，不以编译代替设备验收。
5. 同步 README、CHANGELOG、验收及本计划；只提交/推送功能分支，不合并 main，不变更 0.1.1/code2/schema5 或发布 APK。

### E2 · 第十五批：详情与编辑准备（2026-10-14，局部验收通过）

用户在范围说明后恢复“继续”，按已确认 V1 设计实施本批。继续当前分支；生产 schema/Hilt/导航/版本及发布 APK 不变。任务紧密耦合，由主代理顺序 TDD，独立只读源审查。

1. 共用编辑草稿与纯转换规则：四时间模式、仅日期/带偏移时间输入、严格 IANA/非空标题/数值校验；未编辑 nullable/旧标签/隐含参数无损，模式变更明确确认停用/重新启用，保留父子/完成/审计字段。
2. 保存前核对领域策略：在既有全局准入→Plan 锁内重新读取当前记录/完成/冲突/例外，改动或冲突集合变化要求重确认，不复用旧确认。冲突窗口包含首区间并至少其起始日起30天，UI明确仅检查该有限窗口；不宣称无限重复无冲突。
3. 独立 PlanEditorViewModel/StateFlow：详情、草稿、保存/删除/完成、关闭/切换确认；维护取消保留草稿、世代替换失效、已提交回执先消费新建草稿，再重试提醒，不排队重放被拒写入。当前设置总开关读取失败禁写，关闭时明确提示。
4. 原生卡通详情/编辑/工作区：仅接入已实现的新建/详情/编辑/删除操作，列表筛选不变；返回保护草稿，四模式、颜色/地点/参与者/重复/提醒字段实装，不显示日历/统计空入口。补 JVM/Compose/完整 Room 回归及独立审查，全量 JVM、Debug/androidTest、lint 验证；设备/进程重建与生产导航交付仍后续。
5. 同步 README/CHANGELOG/验收/计划，只提交并推送当前功能分支，不合并 main、变更版本或安装个人设备。

五项局部验收完成：新增84项/6 suites，最终1275项 JVM /106 suites强制重跑通过，Debug重新打包、androidTest构建检查及lint0 error/55既有warning；独立审查反馈全部关闭。当前输入用格式提示文本，确认展示具体参数和截止互斥表示；同ID重编辑/当前草稿再激活/保留例外/冲突与跳转旧确认/锁等待删除都有真实回归。提交回执先消费草稿，多个提醒失败分别重试。失败历史、缓存范围与归属待定位的资源警告见验收；不是生产v6/设备/进程重建或V1发布。

### E3 · 第十六批：日历与独立全库统计准备（2026-10-14，局部验收通过）

用户确认本批范围，沿用已批准设计和当前功能分支；不切生产 schema/Hilt/根导航，不更改版本、main 或发布 APK。`writing-plans` 与 Buddy 运行脚本仍不可用，使用以下文件级五任务和真实验收记录。

1. `domain/plan/PlanCalendarQuery.kt` 与测试：月/周/日有限窗口、日分组/稳定身份，复用 PlanOccurrences 与取消例外；日期截止/全天保留 LocalDate，按计划时区的日期窗口准入并按保存日期截取，不先用展示时区 Instant 窗口误删远距离时区日期项。Instant 按展示时区分组，跨日真实区间覆盖相交日期、排他结束不多占一天；无日期排除，已完成系列仅完成日一标记，整个查询共享计算预算且超限不返回部分成功。
2. `ui/plan/PlanCalendarViewModel.kt` / 日历状态与 `PlanStatisticsViewModel.kt` / 统计状态：独立全库 Flow 失效后在 GenerationAccess 中重读，维护取消保留选择、替换清旧事实，旧回调固定世代；错误不是成功空列表/零统计。全库统计复用已有规则，今天真实日程按展示时区今日 Instant 窗口真实相交且按计划 ID 去重，不把系列完成标记当日程；仅日期项在其计划时区比较日范围，因此该统计不是日历日期格的机械计数。注入 Clock，页面显示/恢复前台及可见时每60秒重算当前 now/系统展示时区，仍同许可内查询/发布；覆盖无数据库变化的截止跨越、午夜及改区。
3. `PlanCalendarScreen.kt` / `PlanStatisticsScreen.kt` / `PlanWorkspace.kt`：原生卡通 Material3，浅深色/长文本/320dp/200% 字体/语义；真实已实现页面在准备工作区切换，月格选日、前后窗口/回今天/显示已完成，周日为按日列表而非小时拖拽时间轴；日历打开统一详情、返回保留日历选择和列表筛选，编辑页不提供绕过草稿保护的切页入口。
4. JVM/Compose/完整 v6 Room 测试、独立只读审查；全量 JVM 强制重跑、Debug/androidTest 构建及 lint。设备执行/进程重建/生产接线/性能和 Release 仍后续，不能以本批 Robolectric 验证代替。
5. 同步 README/CHANGELOG/验收/计划，局部验收完成才提交并推送当前功能分支，核对远端；不合并 main、不操作个人设备或用户库。

五任务局部验收完成：新增42项/5 suites（查询16、状态11、Compose6、工作区4、完整Room5），最终1317项 JVM/111 suites强制重跑通过（0 failure/error/skipped），Debug重新打包、androidTest编译/构建检查、lint0 error/55既有warning，独立复审无遗留问题。跨时区民用日期/今日真实窗口有实质RED/GREEN；新旧工作区9项定向复验通过，加载同步修正和首次全量失败保留在验收记录。只读日历状态单独拆分，避免把窗口/选择状态混入已有列表写入VM；维护许可和统一编辑身份共用现有边界。无生产/设备/进程重建/Release/性能验收，资源关闭警告仍待定位。

### E4 · 第十七批：统一导航与新版设置准备（2026-10-14，局部验收通过）

用户确认范围；沿用既有设计和当前分支。任务为：①独立新版设置领域接口与完整v6 Room实现，严格读取、同世代原子更新且保留其他偏好；②独立设置StateFlow、读取失败禁写、保存回执与提醒失败分离，复用主题/日期/币种/权限控件，只显示计划和订阅提醒；③四入口准备导航与通知所有者，原世代准入、保留计划筛选/草稿、过期确认拒绝、维护拒绝不重放，新通知定位同身份，旧通知仅回首页提示失效；④真实交互、VM和完整Room测试，独立审查及全量JVM/Debug/androidTest/lint；⑤同步README/CHANGELOG/验收并提交推送功能分支。生产Room5/Hilt/MainActivity/默认v1备份/旧提醒绑定/版本/APK不变，生产统一激活及F验收仍后续。

导航准备组件必须使用已实现页面和显式模块绑定，不使用空页或假业务数据。通知等待草稿保存或显式放弃，消费后不因重新组合重放；新通知严格核对action、URI、Long类型元数据和源世代，旧ID绝不解释成planId。新版设置不是v2备份DTO冒充应用领域模型；数据库事务同时核对本机世代，提醒效果在提交之后执行，不因效果失败重复写入。

五任务局部验收完成：新增51项/5 suites，最终1368项 JVM/116 suites强制重跑（0 failure/error/skipped），Debug重新打包、androidTest重新编译/包构建检查、lint0 error/55既有warning。独立源审查反馈全部关闭，最后“被拒的新请求遗留旧弹窗”有17项/2失败RED与修复后17项GREEN；最终全量在该修复后重跑，不以较早1366项结果替代。冻结Back/旧关闭确认、延迟到达旧确认、错误详情恢复导航、原SQL取消/暂停Main交付取消/效果在途取消、实际Room失效均有回归。完整过程和限制见验收；无生产切换或设备/Release/性能验收。

### E5 · 第十八批：新版备份设置与导航保护（2026-10-15，局部验收通过）

用户确认既有设计下的本批边界。①独立V1备份设置StateFlow所有者，使用D4的V2文件/维护流程，导出隐私确认、V1/V2真实转换预览、保护文件重读验证与独立最终确认，清空保留设置；②真实Compose设置与四入口准备导航显式持有该所有者，原世代/操作UUID/阶段检查、旧文件回调、重建、忙碌切页/偏好/Back保护；③不确定提交禁用新备份操作，显式读取当前严格快照后才解除，不自动重试SQL；④VM/Compose/真实完整Room测试、独立审查与全量JVM/Debug/androidTest/lint；⑤更新README/CHANGELOG/验收并提交推送当前功能分支。生产Room5/Hilt/MainActivity/默认v1备份/旧提醒/0.1.1-code2/APK/main不变。

五任务局部验收完成：新增32项/4新suites（VM17/Dialog4/Picker2/完整Room3，既有导航suite新增6项），最终1400项JVM/120 suites强制重跑通过，0 failure/error/skipped；Debug重新打包、androidTest编译/包构建检查、lint0 error/55既有warning。独立审查3处时序问题均有实质RED/GREEN并复审关闭；早期测试接收者/接口编译问题、通知阻塞错误位置超时及后续正确复现均保留验收记录。真实文件提供方/设备进程死亡/生产覆盖升级/Release/性能不由Robolectric替代；完整E生产激活和F仍后续。

### E6 · 第十九批：生产整体接线（2026-10-15，源码验收通过）

用户确认原子切换范围，原基线5b24435；统计工作区基线同步修复已独立验证/提交3ddbef6，功能分支继续原地开发。Buddy项目状态脚本缺失，沿用Gradle/TDD、原计划和独立审查记录；不创建新工作树、PR或更改版本。E6任务：

1. 生产LifeManagerDatabase注册13个v6实体、PlanConverters、统一DAO和维护专用DAO，工厂注册完整迁移链；分离历史v5初始化与新建v6默认值，生成真实生产6.json。先用现有工厂API验证新库/完整v5与v4升级链，再切实现，不清库、不提供破坏性降级、不重置世代。
2. Core DI绑定同一生产实例上的PlanRepository/UseCases、V1Settings、V2快照/原子恢复/文件/维护；提醒DI、Application、Manifest/Boot/Worker和订阅当前设置读取同批切换。旧排程任务保留安全兼容处理、旧Todo/ScheduleReceiver拒绝旧记录身份，不能访问已退役表。
3. MainActivity保留真实模块VM，四入口统一导航，计划和订阅通知都使用原世代/可消费事件，继续草稿/维护/backup-busy保护；订阅默认值迁移到V1设置。
4. 移除旧Todo/Schedule正常CRUD/仓库/VM/页面及旧设置/备份生产路径，只保留旧文件/迁移/通知身份兼容所必需的DTO、枚举、校验和清理类型。旧CRUD/UI/成功旧通知投递测试按新统一链路移植或明确退役映射，不为维持1400数字而留第二套业务；保持旧schema、迁移回滚和v1原hash/字段契约测试。
5. 新增实际生产工厂与绑定/入口整链路测试，同步全部仍有效的JVM/androidTest装置；独立源审查后串行全量JVM/Debug/androidTest/lint。设备覆盖升级、Release/性能及1.0.0-code3交付仍F，不在本批操作个人设备。
6. README/CHANGELOG/本计划/验收与schema说明同步，核对当前feature分支、已完成/未完成和测试数量变化，完整接线验证前不提交/推送半切换状态。最终仅提交推送功能分支，不合并main、不发布APK、不更改origin。

六项任务在本批范围完成：生产13实体schema6、同库Hilt/备份/设置/统一提醒、保留九VM的真实四入口及旧正常业务退役完成接线；历史迁移/wire/hash证据和兼容组件保留。最终1122项JVM/92 suites强制重跑通过（0 failure/error/skipped），Debug重新打包、androidTest构建检查和fresh lint报告通过（0 error/47 warning）；独立源复审无遗留问题。测试数量变化见覆盖映射，RED/失败诊断与最终命令见验收。应用版本仍0.1.1/code2，不合并main或发布APK，F仍未完成。

### E 生产接线总纲（E1–E6范围，非E3单批承诺）

- 新建 `ui/plan/PlanViewModel.kt` / PlanUiState / PlanScreen / PlanList，`PlanEditorViewModel.kt` / editor state / PlanEditor / PlanDetail，`PlanStatisticsViewModel.kt` / PlanStatisticsScreen，日历分拆组件。StateFlow、维护准入和原世代结果发布沿用 GenerationAccess。
- 共用新增/编辑，时间模式与隐藏参数保留、移除重复区间明确确认；长文本、保存错误 / 提醒错误分开，同一草稿不可重复创建。分类转换保留筛选并提示去向，不隐式退出未保存页。
- 列表分类 / 状态 / 日期 / 标签 / 优先级 / 搜索 / 稳定排序；统计独立全库观察。日历只显示真实窗口，重复完成只一完成标记；周日首批为按日列表，不声称小时拖拽时间轴。
- 修改 `MainActivity.kt`、`ui/navigation/TopLevelNavigation.kt` / 新 PlanNavigationViewModel、AppModule / DatabaseModule / database factory，设置模型 / Entity / DAO / repository / SettingsViewModel / SettingsScreen、Manifest。四入口计划 / 打卡 / 订阅 / 设置。
- 生产数据库切到 v6、注册迁移；生成并检查 `app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/6.json`。旧 Todo/Schedule 正常 DAO/仓库/VM/页面移除，保留兼容 DTO/枚举或显式重命名通用枚举，不留两条写路径。
- 同批更新全部旧数据库装置、备份装置、导航 / 提醒 / 设置测试：用真实 v6 路径测试旧契约的迁移兼容，不为保留旧测试而保留旧业务系统。
- 新 JVM / Compose / AndroidTest 覆盖统一闭环、维护拒绝/恢复、旋转 / 切页 / 通知草稿、冲突确认过期、无假数据、320dp / 200% 字体 / 浅深色 / 读屏；打卡 / 订阅回归不省略。

## F · 升级、版本及交付（依赖 E 全部接线）

- 项目专用无个人数据设备安装已发布旧 APK 并加入合成完整十表数据，保留旧备份；覆盖安装新 Debug 同签名 APK，逐值核对转换、重启 / 提醒校准与 v1 文件恢复，再执行 v2 导出 / 恢复。connectedAndroidTest 会卸载，只在独立测试 AVD 操作，不拿个人设备升级样本跑卸载测试。
- 修改 `app/build.gradle.kts` 为 1.0.0 / 3 后重跑 JVM / instrumentation / Debug / androidTest / Release / lint，检查签名、体积和冷启动 / 切页测量；模拟器不能替代真机和稳定 60fps 证明。
- README、schema README、v1 验收和格式说明同步：实际数量、已完成 / 未完成 / 已知限制，不混用历史证据。
- 独立最终审查，检查依赖方向、未接线构件、secret / 用户数据 / 被误删功能。
- 按用户阶段交付授权提交 / 推送功能分支并核对远端；不自动合并 main、改写远端历史、覆盖已有 Release 或正式签名。发布新 APK需同签名策略和单独交付说明，历史预览保持不变。

## 命令与证据

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --tests 'com.example.lifemanager.domain.plan.*' --rerun --console=plain
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain
# 完整接线后，先核对指定串号为项目专用 AVD，再执行设备测试
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest --rerun :app:assembleRelease --console=plain
git diff --check
```

测试结果从实际 XML suite 求和，failure/error/skipped 单独记录；BUILD SUCCESSFUL 不能替代运行数量。UP-TO-DATE 构建 / lint 说明为缓存检查，JVM 用 `--rerun` 强制重跑。第一批不运行迁移和新 UI 设备验收。

## 风险与撤回

- 非活动字段 / 原文本在 domain 集合表示中损失：迁移/备份保存 raw DTO，领域计算另行解析，禁止 trim 或清空隐藏参数。
- 中间 schema 断开旧消费者：通过E6原子接线避免；此前新数据装置隔离于生产v5，不将中间状态当作可发布版本。
- 跨设备时区 / DST：旧待办注入 ZoneId 并说明来源；date-only 保留 LocalDate，固定 Clock 测边界。
- 超大主键 / 世代：新顺序 ID，checked increment，溢出拒绝；SQL 事务失败不修复源库。
- SQL 与 AlarmManager 非原子：世代 / 资格复核、可重试校准、保存结果分离，旧广播不可写新身份。
- 源码回退保留用户改动；已升级设备只作更高版本前向修复，可在 V1 恢复有效 v1/v2 保护备份，不安装 V0 降级，不提供破坏性 fallback。

## 进度

- 完整设计与当前分支开发方式已确认。
- 独立计划审查发现 C/D 提前替换生产消费者与 E 激活关口冲突：已将现有绑定 / 默认格式 / 旧协议拒绝明确延后至 E；A 的统计边界测试清单补充。
- A1–4 已完成领域底座局部验收：最终 703 项 JVM / 70 suites 全量重跑通过（新增 34 项），Debug 构建检查与 lint 通过（0 error / 55 warning）；计划和代码审查意见已独立复审关闭。
- B1 已完成旧字段、双来源身份、关系映射及用户批准的存量读取兼容底座；最终 729 项 JVM / 72 suites 全量重跑通过（本批新增 26 项），Debug 构建检查通过，lint 0 error / 55 warning。独立审查的非法时区测试缺口补齐并复审关闭。详见验收记录。
- B2 四项任务已完成局部验收（2026-10-11）：统一 Room 存储与事务仓库，历史标签身份保留、取消/故障回滚和 Flow 读取错误覆盖；新增 31 项，最终 760 项 JVM / 74 suites 强制重跑通过，Debug 重新打包成功，lint 0 error / 55 warning。计划及代码审查问题均复审关闭。
- B3 四项任务已完成局部验收（2026-10-11）：完整 12 实体 v6 迁移装置、历史链/新建/坏源及晚期故障回滚，新增 15 项 JVM / 10 项专用模拟器设备测试。最终 775 项 JVM / 75 suites 强制重跑、Debug/androidTest 构建检查、lint 通过（0 error / 55 warning），计划和代码审查已复审关闭。详见验收中的 TDD、故障点证明和设备串号记录。
- C1 四项任务已完成局部验收（2026-10-11）：共享发生/重复/窗口冲突/未来 trigger，新增 69 项 JVM；最终 844 项 / 78 suites 强制重跑通过，Debug 重新打包成功，lint 0 error / 55 warning，独立计划与代码审查问题已复审关闭。预算上限绕过有真实 RED/GREEN；本批不运行设备验收。
- C2a 四项任务已完成局部验收（2026-10-11）：维护安全统一写入、已提交回执/只重试提醒与值身份协议；新增 43 项 JVM，最终 887 项 / 81 suites 强制重跑、Debug 重新打包和 lint 通过（0 error / 55 warning），计划 3 Important / 1 Minor、代码 1 Important 均独立复审关闭。真实 Room 提交交付桥和外层取消回执均有实质 RED/GREEN；无设备或 Android 闹钟验收。
- C2b 四项任务已完成局部验收（2026-10-12）：Room 技术库存、严格 Intent/资格、隔离 Android 排程/通知及 Receiver；新增 56 项 JVM（协议/资格 8、真实 Room/系统服务集成 48）。最终 943 项 / 83 suites 强制重跑通过，Debug 缓存构建检查与 lint 测试源重新分析通过（0 error / 55 既有 warning）；计划 2 Important / 2 Minor、代码 2 Important / 1 Minor 均复审关闭。过去 trigger 与可观察通知阻止有实质 RED/GREEN，post 后精确撤回有 mutation RED；Receiver 私有权限修正限测试装置。本批无设备/UI/Release/性能或新增完整升级链验收。
- C2c1 四项任务已完成局部验收（2026-10-12）：唯一迁移 SQL/完整目标实体与导出 schema 纳入技术库存，历史1–5链/新建、真实库存重开/CAS/业务删除身份保留、早晚故障回滚完成；新增5项JVM/4项设备用例。最终948项 / 83 suites JVM强制重跑、专用API35 AVD指定类14项测试全部通过，Debug/androidTest重新打包、lint三类源分析通过（0 error / 55既有warning），计划2 Minor和代码复审全部关闭。生产v5/旧提醒/Hilt/UI/版本不变，无真机/生产用户库覆盖升级/UI/Release/性能验收。
- C2c2五项任务已完成局部验收（2026-10-13）：隔离旧身份清理、统一校准、精确维护及独立Worker完成；53项新增，1001项/87 suites JVM强制重跑、Debug/androidTest重新打包、lint0 error/55既有warning，计划及核心/Worker/Runner复审问题关闭。本批没有设备执行/UI/Release/性能验收。
- D1五项任务已完成局部验收（2026-10-13）：隔离v2raw契约、严格验证/编解码、格式说明，29项新增、最终1030项/89 suites JVM强制重跑及Debug/androidTest构建检查通过，lint0 error/55既有warning，复审问题关闭；当前生产v1默认未变，无实际文件/Room恢复或设备执行验收。
- D2五项任务于2026-10-13局部验收完成：旧v1原hash严格验证后共享转换及版本分派，31项新增，最终1061项/91 suites JVM强制重跑、Debug/androidTest构建检查和lint通过（0 error/55既有warning），独立复审无遗留问题；默认生产v1仍未变，无实际文件或Room恢复/设备验收。
- D3五项任务于2026-10-13局部验收完成：35项Room测试和1项旧日程清理回归新增，1097项/93 suites全量强制重跑、Debug/androidTest构建检查、lint0 error/55既有warning，独立复审问题关闭。快照/原子恢复/清空只在隔离目标库实现；源码读取/恢复的MAX边界按用户方案1，文件codec/raw契约不变。重复复验、运行资源警告与失败过程见验收记录。
- D4五项任务于2026-10-13局部验收完成：文件/维护隔离实现、42项新测试，77项D4+D3组合及1139项/96 suites全量JVM强制重跑、Debug/androidTest构建检查、lint0 error/55既有warning、独立复审无遗留问题。取消误报与异常世代的完整诊断、RED/GREEN、重复验证和运行资源警告保留；没有生产激活/设备执行或用户库/文件操作。
- E1：统一列表查询、PlanViewModel/StateFlow、原生手账筛选/列表及完成/撤销已实现准备；维护许可与原世代发布、回执后提醒失败/取消仅重试效果、旧UI回调固定渲染令牌。新增52项/4 suites，1191项 JVM /100 suites强制重跑通过，Debug/androidTest构建检查、lint0 error/55既有warning，独立审查全部反馈关闭。实际命令、失败历史、资源警告及边界见验收；未接生产入口/v6。
- E2五项任务于2026-10-14局部验收完成：详情/编辑、同身份日期转换与准备工作区；新84项/6 suites，最终1275项 JVM /106 suites强制重跑、Debug/androidTest构建检查、lint0 error/55既有warning、独立复审全部反馈关闭。锁内保存/删除核对、确认修订、原世代/编辑会话和回执失败重试都有回归；未接生产入口/v6。
- E3于2026-10-14局部验收完成：日历/独立全库统计和准备工作区接线，新增42项/5 suites，最终1317项 JVM/111 suites强制重跑、Debug/androidTest构建检查及lint0 error/55既有warning；计划/代码复审关闭，失败过程和限制见验收记录。
- E4于2026-10-14局部验收完成：统一四入口/草稿与通知所有者、新版领域设置/Room事务/独立状态及共享展示控件；新增51项/5 suites，最终1368项 JVM/116 suites强制重跑、Debug/androidTest构建检查、lint0 error/55既有warning，独立复审无遗留问题。
- E5于2026-10-15局部验收完成：新版备份设置、独立StateFlow所有者及导航/偏好/文件回调保护；新增32项/4新suites，最终1400项JVM/120 suites强制重跑、Debug/androidTest构建检查及lint0 error/55既有warning通过，独立复审关闭。
- E6于2026-10-15完成生产源码原子接线验收：同一Room6/Hilt数据库上的计划/打卡/订阅/设置、V2备份及统一提醒；旧正常双入口业务退役，历史兼容保留。最终1122项JVM/92 suites强制重跑、Debug重新打包、androidTest构建检查及新lint报告通过（0 error/47 warning），独立源码复审无遗留问题。测试退役/移植有映射，不把1400→1122当作新增数量。
- F版本和设备/覆盖升级/Release/性能交付仍未完成；分支源码schema6、应用版本暂留0.1.1/code2，main和已发布APK仍旧schema5，不等于整个V1已发布。

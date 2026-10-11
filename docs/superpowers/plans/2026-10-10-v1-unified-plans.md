# V1 统一计划实施计划

日期：2026-10-10。依据：已获用户全文确认的 [设计](../specs/2026-10-10-v1-unified-plans-design.md)。
分支：`feat/v1-unified-plans`，用户选择在当前干净分支开发，不创建工作树。

`writing-plans` 技能不可用，使用文件级任务、明确依赖与验收记录替代。Buddy 默认的新分支 / PR / 网页验收流程不直接套用原生 Android；不新增 Linear、网页依赖或流程文档。沿用现有 Gradle、TDD、独立审查及 Android 设备验收。总体路线不是一次可交付的单任务，每批在执行前细化并检查覆盖；A/B1/B2/B3/C1/C2a 已完成局部验收，下一批 C2b。

## 目标与关键连接

| 必须成立的用户结果 | 实现与连接 | 验收 |
| --- | --- | --- |
| 待办加日期成为日程，身份和内容不变，两类可完成/撤销 | Plan 编辑 / 完成用例 → PlanRepository → 同一 plans 行 | 规则、Room、Compose 测试 |
| 旧待办/日程、标签、父子、例外无损升级，打卡/订阅不变 | 共享 LegacyPlanConverter → 5→6 Migration / v1 备份转换 → v6 验证 | 字段比对、完整迁移链、故障回滚 |
| 日期不是假区间，长期目标不挤入今日，重复完成结束系列 | PlanTimeRules / PlanOccurrences → 列表、日历、冲突、统计、提醒 | 确定性时区 / DST / 边界测试 |
| v1/v2 备份严格验证再恢复，旧令牌不能写新 ID | 版本分派 → 校验 → 转换 → 保护备份 / 维护事务 → 世代 | codec、真实 Room / 文件 / 旧草稿回归 |
| 完成或关提醒后不会继续投递，也不会与旧广播重号 | PlanReminderScheduler / Receiver → 当前仓库、设置、世代、例外 | 延迟广播、升级校准、权限设备测试 |
| 四入口均为实装功能，可离线运行并保留草稿 | Hilt 单数据库 → 三个 Plan VM → Compose / 导航 / Settings | JVM、设备 CRUD / 导航 / 适配 |

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

#### C2b / C2c · 后续 Android 提醒与校准（尚未实施）

- 复用 C1 的 `PlanOccurrences` / `PlanRecurrence` / `PlanReminderRules` 和 C2a 用例/身份协议；发生集成测试验证共用算法，不重建第二套发生/重复计算。C2a port 的完整 Android 提醒实现和真实 UI 发布接线不得遗漏。
- 新建 `notification/PlanReminderSchedulerContract.kt`、`PlanReminderScheduler.kt`、`PlanReminderReceiver.kt`、资格 / identity 实现；规划字段和协议，不提前注册未接线 Receiver。
- 先新增隔离可测试的统一校准与旧身份清理实现，不更换当前 v5 的 Hilt / Worker / Receiver 调用路径。准备 ReminderReconciler / BootReceiver / Worker / Inventory / Notifications / AndroidMaintenanceReminderEffects 的接线变更；实际替换与旧 Receiver 协议拒绝只在 E 激活提交执行。C 结束时旧应用提醒仍正常运行，清理失败重试和不发双份的新版行为在独立装置验证。
- 测试发生规则、DST / date-only 09:00 / 全天、custom / leap / 月末、未来 trigger 而非仅未来 occurrence、完成/撤销、延迟广播、权限撤销、旧 ID / URI / 世代、旧通知安全落首页与新通知一次消费。
- reminder 数据失败明确区分“已保存但提醒失败”，不重插记录；全局开关不会重开单项关闭配置。

## D · v2 备份与维护适配（依赖 B/C 字段与身份）

- 保留当前 `domain/backup/BackupSchema.kt` 的 v1 常量与现有 codec 消费路径；新增隔离的 V2BackupSchema / 验证器 / codec / LegacyBackupConverter，准备 BackupDocument / validator / mapping / canonical codec 的版本化接线。v1 先校验原 hash / 字段 / 类型 / 关系再转换，v2 单独严格验证，不放宽未知键。默认 codec / 导出版本只有 E 才切换。
- 新增隔离的 v6 snapshot / backup repository / mutation 实现并在测试专用 v6 数据库验证，不在 D 替换当前 BackupSnapshotDao、BackupRepositoryImpl、BackupMutationRepositoryImpl 的生产调用与 Hilt 绑定；BackupMaintenanceWorkflow 的新版表 / 来源 / 设置 / 提醒接线也只在 E 激活。
- 准备设置备份预览 / 计数与旧待办无时区说明，E 才更换生产消费者；保持保护备份、最终独立确认、原子完整替换、清空保留设置、本机世代不导入和所有失败/取消边界。
- 新建 v2 格式说明，保留 v1 历史文档；测试两版本往返 / 恢复、未知版本/hash/关系/环/Long 精度、转换增行后 100k 上限、64MiB、来源与保护文件、事务故障和旧草稿 / 文件回调 / 通知失效。
- 设备文件测试只在项目专用合成库上运行。

## E · 页面实现及统一生产切换（依赖 B/C/D）

准备子批分别完成列表、编辑/详情、日历/统计和导航集成，每子批 ≤5 个任务，生产切换最后一次完成。

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
- 中间 schema 断开旧消费者：激活关口 E；新数据装置隔离于生产 v5。
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
- 下一批 C2b：统一 Android 排程/Receiver/严格 Intent 交叉校验/资格/去重的隔离实现；C2c 负责旧身份清理和校准，执行前细化子计划。C2b–F 未完成，生产应用仍为 V0.1.1 / schema v5，已发布 APK 未改；底座和完整目标 fixture 不等于生产接线、真机升级、新页面或整个 V1 已完成。

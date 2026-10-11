# V1 统一计划分批验收

开始日期：2026-10-10；B2 最终复验日期：2026-10-11。分支 `feat/v1-unified-plans`。全文设计与当前分支开发方式已获用户确认。

## 本轮基线

在应用修改前执行：JDK `D:\jdk`（25）、Gradle 9.4.1、现有网络 init 脚本，`:app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain`。

- BUILD SUCCESSFUL，2 分 23 秒；65 tasks，2 executed / 63 up-to-date。
- 实际 XML：669 项 JVM，68 suites，0 failure / error / skipped。JVM 任务强制重跑。
- Debug 构建和 lint 为缓存构建检查；lint XML 0 error / 55 warning，未假称全量重新编译 / 重新分析。
- Gradle 仍报告 AGP 旧 DSL / Kotlin plugin 弃用提示；本批不为消除提示改变构建架构。
- 本轮尚未执行设备测试，历史 57 项设备通过不能作为 V1 验收。

## 批次 A · 领域底座

范围：统一 Plan 模型、四种时间模式有效字段校验、完成 / 撤销、日期转换与非活动参数保留、全库完成率 / 展示时区今日完成 / 非重复逾期。领域仅依赖 Java time 与现有纯领域枚举，不依赖 Room / Android UI / Data。

状态：领域底座局部验收通过，最终新增 34 项 / 2 suites，完整结果见下文。迁移、备份、提醒、页面不在本批；不修改生产 schema / version / manifest / Hilt，不安装、清空或改变真实用户库。不是整个 V1 验收。

初轮定向 GREEN：32 项 / 2 suites，0 failure / error / skipped，BUILD SUCCESSFUL（1 分 20 秒）。

TDD：先编写测试与类型契约，以尚未实现行为的临时空规则运行真实断言。首轮编译成功，32 项运行 / 22 项 assertion failure，明确捕获校验、完成、转换和统计缺失；不是把编译错误算 RED。补强“坏数据不得产生成功统计”的契约后，统计 15 项运行 / 12 项 assertion failure。随后替换临时规则为实质实现；临时空规则不会提交或交付。

独立计划审查发现 C/D 提前替换提醒 / 备份生产绑定会违反 E 的激活关口。已核对当前 Hilt 与数据库依赖，并修改路线：C/D 使用隔离可测试实现，现有生产绑定、默认格式和旧协议拒绝只在 E 切换。补充 A 的具体统计边界测试清单。此为计划修正，不是数据库 / 提醒已升级。

计划两项问题已独立定向复审关闭，无新增发现。

本批首次全量命令与基线相同，BUILD SUCCESSFUL（2 分 24 秒，65 tasks：13 executed / 52 up-to-date）：

- 701 项 JVM / 70 suites，0 failure / error / skipped，`:app:testDebugUnitTest --rerun` 实际执行；新增 32 项 / 2 suites。
- Debug APK 重新打包完成；lint 三类源分析重新执行成功，实际 XML 为 0 error / 55 warning，新增 domain/plan 无 lint issue。
- 本批无 Android instrumentation、真实 v5→v6 升级或新版 UI 验收；历史设备结果不混用。

独立代码审查无 Critical / Important，确认 A 的范围与实现一致。两项 Minor：进度文档要跟随验证更新，以及直接覆盖历史 `pending + completedAt` 的完成与 `completed + null` 的撤销；已补 2 项实质断言。这两项属于已有正确实现的覆盖补强，不虚称另一次功能修复 RED。定向独立复审确认两项关闭，未发现新增 Critical / Important / Minor。

最终复验（补强后的相同全量命令）：BUILD SUCCESSFUL，1 分 42 秒，65 tasks：8 executed / 57 up-to-date。

- 实际 XML：703 项 JVM / 70 suites，0 failure / error / skipped；JVM 强制重跑。PlanRules 19 项、PlanStatistics 15 项，总新增 34 项。
- Debug 构建为本批首次重打包后的 UP-TO-DATE 检查；lint 本次重新分析 UnitTest，production / AndroidTest 分析和最终 report 复用未变内容。实际 XML 0 error / 55 warning，新增 domain/plan 无 issue。
- `git diff --check` 通过；Git 提示后续 checkout 的 LF→CRLF 转换不是内容错误。
- 仅准备本地功能分支提交，不推送、合并或更新已发布 APK；不存在未实现方法、假页面或合成业务数据注入。

## 后续及已知限制

- A、B1 与 B2 底座局部验收通过；B3–F 未完成：完整 Room v6 SQL 迁移和生产接线、发生/提醒、v2/v1 备份兼容、统一页面/导航/设置、覆盖安装和版本交付。
- 本批构件尚未接入生产业务，不能在已发布软件中使用新的转换 / 完成规则；不以单元测试代替设备迁移验收。
- 独立统计页、今天真实日程、重复展开 / 例外 / 冲突、UI 移除日期确认均在后续实现；本批没有占位入口。
- V1 目标为 1.0.0 / code 3 / schema v6，当前仍为 0.1.1 / code 2 / schema v5。main 及已发布 APK 本轮未更新；功能分支交付见下文，不等于发布 APK。
- 真机、稳定 60fps、Gradle Wrapper、正式签名等历史发布限制仍保留；不声称改版自动解决。

依据：[设计](superpowers/specs/2026-10-10-v1-unified-plans-design.md)、[计划](superpowers/plans/2026-10-10-v1-unified-plans.md)。

## 2026-10-10 · 第一批推送与更新文档规则

- 用户明确要求推送，并且每次版本更新附带更新内容文档。新增 CHANGELOG 与仓库 AGENTS 交付约定，README 提供入口；未发布子批不虚标已发布版本。
- 本轮推送前复验：相同三任务命令，BUILD SUCCESSFUL（46 秒，65 tasks：2 executed / 63 up-to-date）。703 项 JVM / 70 suites 实际重跑，0 failure / error / skipped；Debug / lint 为缓存检查，未执行设备验收。
- 已推送 `feat/v1-unified-plans` 到 `3aefb0158ff46952b83927e657463000a2ea3d55`；`git ls-remote` 与本地 HEAD 一致。包含第一批 `21a4dc2` 及规则/更新日志提交 `3aefb01`，未合并 main、创建 PR 或发布 APK。

## B1 · 第二批：无损转换及来源关系

范围：原始存储行契约、旧实体字段转换、新旧来源身份与父/标签/例外映射、旧提醒资格转换、用户批准的历史标题/时区读取兼容。输入使用旧实体原始字段，不经领域时间或重复集合归一化；SQL 与经过严格验证的旧备份以后可共用同一转换器。

TDD RED：新增测试和最小 API 声明，运行 `data.plan.*` 与 `domain.plan.*`；编译成功，59 项 / 4 suites，24 项 assertion failure（包含 2 项已有领域统计的兼容问题），不是编译错误。随后用实际转换/映射替换临时未实现方法。定向 GREEN：59 项 / 4 suites，0 failure / error / skipped，BUILD SUCCESSFUL（1 分 20 秒，39 tasks：11 executed / 28 up-to-date）。

首次全量验证：相同三任务命令，BUILD SUCCESSFUL（2 分 7 秒，65 tasks：13 executed / 52 up-to-date）；实际 XML 为 728 项 JVM / 72 suites，0 failure / error / skipped。JVM 强制重跑，Debug 重新打包，lint 三类源分析执行，XML 为 0 error / 55 warning。

独立代码审查无 Critical / Important。唯一 Minor 是缺少对读取路径非法时区的直接回归；已补 1 项覆盖已完成 NONE 与 Instant DEADLINE，直接检查读取校验拒绝、逾期为 false、统计抛异常。原实现正确，此为覆盖补强，不虚称一次功能修复 RED。定向独立复审确认关闭，无新增问题；补强后全量复验结果另列。

最终复验：相同三任务命令，BUILD SUCCESSFUL（4 分 4 秒，65 tasks：8 executed / 57 up-to-date）。

- 实际 XML：729 项 JVM / 72 suites，0 failure / error / skipped；JVM 强制重跑。本批新增 26 项：Converter 12、References 11、Statistics 新增 3；统一计划定向共 60 项（Rules 19、Statistics 18）。
- Debug 为首次重打包后的 UP-TO-DATE 检查；lint 本次重新分析 UnitTest，production / AndroidTest 分析与最终 report 复用未变内容。XML 0 error / 55 warning，新增 data/plan 与 domain/plan 无 issue。
- `git diff --check` 通过；新文件无临时未实现方法或破坏性迁移，只有测试装置使用合成数据，不注入业务库。未运行设备迁移/新版 UI 测试，不混用旧设备结果。
- B1 转换底座局部验收通过，不代表 B2/B3、整个 V1 或正式版本已完成。README、CHANGELOG、设计兼容补充和实施进度同步，交付当前功能分支，不合并 main 或发布 APK。

边界：转换器是字段转换，不是完整旧备份/数据库验证器；新 ID 由调用方分配，不能把 helper 当成已执行顺序 SQL 自增。父先 null，保留旧来源供二次修复；全库环/外键、设置/世代损坏、逐表快照、事务回滚和设备迁移在 B2/B3 验证。纯 lookup 可由 SQL 实现；内存 registry 只用于受限文件，不在迁移中要求整库入内存。

生产 v5 / Hilt / UI / 备份格式 / 提醒调用均未切换，未操作用户库。用户选择的兼容修正只在新的统一领域底座，不改变已发布 V0 运行路径。

## B2 · 第三批：统一存储及事务（局部验收通过）

下列保留本批 RED/GREEN 与审查的时间顺序；最终复验结论在本节末尾，不将中间状态当作最终状态。

开始基线：相同三任务命令，BUILD SUCCESSFUL（43 秒，65 tasks：2 executed / 63 up-to-date）。729 项 JVM / 72 suites 强制重跑，0 failure / error / skipped；Debug/lint 为缓存检查。工作分支 HEAD 为 `9d87a73`；开始时只有本批实施计划改动。

范围：新增隔离的 Room 实体/DAO、统一仓库及原世代事务检查。存储 fixture version 1、exportSchema=false，只含本批表，绝不将此装置当作完整 schema v6 / 真实迁移验证。生产数据库、Hilt、旧业务、备份、提醒和页面维持现状。

初次运行在 kapt 编译阶段发现挂起 RawQuery 返回 Cursor 被当成数据类；与现有 BackupSnapshotDao 对比，改为相同非挂起 Cursor 查询，在事务线程中使用并关闭。该编译错误不算 TDD RED。实质 RED/GREEN 与审查尚在执行，不宣称本批验收通过。

首轮实质 RED：`data.plan.* --rerun` 编译成功，48 项 / 4 suites，22 项 assertion failure / 0 error / 0 skipped，BUILD FAILED（3 分 43 秒，40 tasks：14 executed / 26 up-to-date）。缺失的映射、真实保存/勾选/世代行为被断言捕获；已有旧转换测试和 Room 约束不假称新失败。计划审查提出完成操作存量校验边界及取消/交错保存覆盖，已补明确契约和测试，正复跑 RED。

补充后 RED：51 项 / 4 suites，25 项 assertion failure / 0 error / 0 skipped，编译成功，BUILD FAILED（1 分 1 秒，40 tasks：5 executed / 35 up-to-date）。本批 28 项新增测试（Mapping 2、Room 26）；随后替换临时声明为实际实现，GREEN 执行中。

独立计划审查：1 项 Important（完成/撤销采用存量校验）及 2 项 Minor（取消/交错场景、批次说明歧义）已核对设计并补契约/测试；定向复审确认全部关闭，无新增发现。仅表示计划闭环，不把它算作实现验收。

首次定向 GREEN：`data.plan.*` + `domain.plan.* --rerun`，BUILD SUCCESSFUL（1 分 43 秒，40 tasks：11 executed / 29 up-to-date）。88 项 / 6 suites，0 failure / error / skipped，含本批 28 项及 A/B1 回归。独立代码审查随后发现普通编辑归一化历史标签的 Important，以及 Flow 首值同步和关系读取失败测试的两项 Minor；正在补复现和修复，不以这轮 GREEN 宣称最终完成。

审查修复 RED：新增历史三种标签（带空格/规范名/空白）同时关联的 Room 回归，并补关系 SQL/例外日期错误与同一 Flow 订阅的首值同步。29 项 Room / 1 suite，只有历史标签回归失败，0 error / skipped，编译成功（1 分 27 秒，40 tasks：7 executed / 33 up-to-date）。断言明确捕获 ID 被换绑，而不是编译问题。关系失败覆盖属于已有正确实现的测试补强，不虚称另外两次功能 RED。

修复采用原始名称精确匹配已关联历史标签，保留其 ID/颜色，仅对新选择的标签规范化；去重按 ID。Flow 同一订阅收到旧值后才触发改名，测试专用改名 DAO 留在 fixture；DROP TABLE 故障只操作独立内存测试库。本批最终 31 项新增测试（Mapping 2、Room 29）。独立定向代码复审确认上述 1 项 Important / 2 项 Minor 全部关闭，无新增 Critical / Important / Minor；此为静态复审，不替代真实测试。

中断后检查：上一轮定向 XML 为 91 项 / 6 suites，0 failure / error / skipped；终端会话已经失效，不能凭 XML 补称进程退出成功。2026-10-11 重新运行完整 JVM / Debug / lint，最终结果另列，不复用旧测试计数或设备结果。

最终复验（2026-10-11）：JDK 25 / Gradle 9.4.1，`:app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain`（沿用网络 init 脚本）。BUILD SUCCESSFUL，5 分 6 秒，66 tasks：15 executed / 51 up-to-date，进程退出 0。

- 实际 XML：760 项 JVM / 74 suites，0 failure / error / skipped，JVM 强制重跑。本批新增 31 项（Mapping 2、Room 29）；统一计划定向共 91 项 / 6 suites，包含 A/B1 回归。
- Debug 本轮重新打包；lint production / AndroidTest / UnitTest 分析及 report 本轮执行，实际 XML 0 error / 55 warning，data/plan 与 domain/plan 无 issue。既有 AGP/Kotlin 弃用警告仍在，未声称零警告或整库无缓存重编译。
- 31 项新增测试覆盖字段/原文/历史标签身份、存量读取与严格编辑、同身份转换、完成幂等/父子独立、旧草稿、FK/环/来源唯一、原世代及元数据拒绝、真实事务故障/取消回滚、同订阅标签 Flow 通知和关系/日期读失败。计划与代码独立复审均关闭，无未解决 Critical / Important。
- `git diff --check` 通过。没有未实现方法、占位入口、破坏性迁移或个人数据；合成故障仅在独立内存测试库。版本仍为 0.1.1 / code 2，生产 Room v5 和 schema 文件 2–5、Hilt、导航、备份及提醒调用不变。
- B2 是可复用底座，不是生产业务激活：未运行设备测试、历史数据库 SQL 迁移、新 UI 或覆盖安装验收，没有重新发布 APK。完整 v6/迁移链/故障回滚装置属于 B3，全局维护准入/提醒/备份/页面接线属于后续批次。
- README、CHANGELOG 与实施进度同步；按用户已有授权仅提交/推送 `feat/v1-unified-plans`，不合并 main、不创建 PR、不操作个人设备。远端查询默认 OpenSSL 两次 TLS EOF，同一代理临时使用 `git -c http.sslBackend=schannel ls-remote` 成功；交付命令沿用单次 Schannel 设置，不关闭证书校验或修改全局配置。

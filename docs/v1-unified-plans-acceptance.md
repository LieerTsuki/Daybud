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

- A、B1、B2、B3 底座局部验收通过；C–F 未完成：发生/提醒、v2/v1 备份兼容、统一页面/导航/设置、生产 v6 原子接线、覆盖安装和版本交付。B3 的完整隔离迁移结果另列，不等于生产激活。
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

## B3 · 第四批：完整 v6 隔离迁移装置（局部验收通过）

以下按开发顺序保留中间失败与修复记录；以本节末尾最终全量结果及限制为准。

开始 HEAD `a2073c8`，用户要求下一批开始；工作区原先干净，继续当前功能分支。B2 已推送，远端 SHA 与本地一致，main 未改。已批准设计中的架构改版按细化 B3 四项推进，不重复改变产品决定。

本轮基线：JDK 25 / Gradle 9.4.1，相同 JVM / Debug / lint 三任务命令 BUILD SUCCESSFUL（2 分 37 秒，66 tasks：2 executed / 64 up-to-date）。实际 XML 760 项 / 74 suites，0 failure / error / skipped，JVM 强制重跑；Debug/lint 为缓存检查，不作为 B3 新测试证据。

计划独立审查发现一项 Important：回滚必须覆盖旧表已删后的最终 schema 校验失败及跨 4→5→6 恢复原始 v4，而非只验证 v5 或早期失败。已补明确契约和两条真实 Room 测试，定向复审中。测试先写，Migration / 初始化仅为临时 API 声明，实质 RED 后替换；临时未实现方法不交付。生产 v5/Hilt/版本/UI/提醒/备份尚未切换，未操作用户设备。

计划定向复审已确认该 Important 关闭，无剩余问题；仅为计划审查，不替代实现验证。Buddy 本地流程脚本不可用，沿用项目实施计划、原生 Android TDD 与只读独立审查，不引入 Linear / 新分支 / PR 流程。

TDD 过程：前两轮 fixture 失败分别是历史 JSON 缺少可选 indices 和单列 android_metadata 的 ORDER BY；这两轮不算迁移 RED。修复测试装置后，13 项真实 Room 测试运行，5 项行为断言失败（1 分 1 秒，40 tasks：4 executed / 36 up-to-date），明确捕获缺失迁移和新建初始化；当时 8 项拒绝路径不能因临时未实现异常而算作验证完成。随后实现逐行 SQL 迁移；一次 Kotlin 混合数组推断编译失败不算 GREEN，显式绑定 Any/Any? 后，13 项 / 1 suite 全部通过，androidTest 编译通过（2 分 5 秒，77 tasks：23 executed / 54 up-to-date）。

独立代码审查提出 3 项 Important、1 项 Minor：全部 SQL 结束后的无关表保护、故障阶段到达与原因断言、每条 fixture 的独立 27 列 / 数值边界断言，以及保留 SQLite 类型和 sqlite_sequence 的回滚快照。补强后新增世代触发器篡改打卡的实际回归 RED：15 项 / 1 suite，1 failure / 0 error / skipped（1 分 14 秒，40 tasks：5 executed / 35 up-to-date），其余加强断言通过。迁移结尾增加第二次无关表指纹校验；JVM 和设备故障装置均核验推进世代、旧表已删、注入点到达及预期异常，回滚对比包含类型、自增状态和 schema。修复后复验与定向复审执行中，不将中间 GREEN 当作最终结论。

修复后定向 GREEN：15 项 JVM / 1 suite，0 failure / error / skipped，BUILD SUCCESSFUL（1 分 29 秒，92 tasks：19 executed / 73 up-to-date）；Debug 和 androidTest APK 本次重新打包。只读定向代码复审确认原 3 项 Important / 1 项 Minor 全部关闭，未发现修复引入的新问题，未把静态审查当作测试证据。

设备局部验收：先核对 adb 唯一串号 `emulator-5580` 对应项目 AVD `Rizhiya_Branding`，API 35 / x86_64、启动完成；未连接或操作个人设备。使用 `adb -s emulator-5580 install -r` 覆盖安装本轮两个测试 APK，不卸载 / 清库 / wipe；随后只运行 `PlanMigrationDeviceTest`，AndroidJUnitRunner 原始结果 `OK (10 tests)`、3.54 秒，10 个结束 status 均为 0。这些测试只创建并删除自身唯一命名的合成 DB，使用全部 12 个真实目标实体和同一迁移实现。不是手机真机、正式数据库生产切换或新版覆盖安装验收，旧 57 项设备用例本轮未重跑。

设备命令（指定串号与类，不使用会遍历所有设备的 connected 任务）：

```powershell
.\.tools\android-sdk\platform-tools\adb.exe -s emulator-5580 shell am instrument -w -r -e class com.example.lifemanager.data.plan.PlanMigrationDeviceTest com.example.lifemanager.test/androidx.test.runner.AndroidJUnitRunner
```

最终全量复验：JDK 25 / Gradle 9.4.1，沿用网络 init 脚本，命令如下。BUILD SUCCESSFUL（2 分 55 秒，101 tasks：9 executed / 92 up-to-date），进程退出 0。

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

- 实际 XML 汇总：775 项 JVM / 75 suites，0 failure / error / skipped；JVM 强制重跑，新增迁移 suite 15 项。Debug/androidTest 为本批前次重新打包后的 UP-TO-DATE 检查；lint 三类源分析和报告本轮执行，XML 0 error / 55 warning，新增 plan/migration 路径无 issue。AGP/Kotlin/KAPT/旧 Robolectric 弃用警告仍在，不声称零警告或全库无缓存重编译。
- KAPT 导出 `PlanMigrationTestDatabase/6.json`：12 个实体、plans 27 列，identityHash `69d421d86d38020570784322041cbe38`；同一目标实体和同一 `PlanMigration` 在 JVM/设备真实 Room 文件开库时验证，而非手工简化 schema。生产 `LifeManagerDatabase` 的 2–5 快照、版本/Manifest/Hilt 未改，无破坏性 fallback、未实现方法或用户数据。
- 四项 B3 任务局部验收通过，计划审查与代码审查均复审关闭。按用户授权仅交付当前 `feat/v1-unified-plans`，不合并 main、不创建 PR、不发布 APK；README/CHANGELOG/schema README/计划同步。
- 限制：v1 未导出原始 schema，沿用 v2 三张未变实体的合成构造；不是恢复到原始 v1 asset。未执行大库吞吐/峰值内存测试，不能从流式实现推断性能达标；未做真机/生产 v5→v6 用户库升级或新版覆盖安装，旧设备 suite 未重新运行。全局维护、提醒、副作用、备份及页面未接线，整个 V1 和目标 1.0.0 尚未完成。

提交前复验：暂存检查发现新 Kotlin 文件末尾多余空行，清理后重新执行相同全量命令，BUILD SUCCESSFUL（3 分 33 秒，101 tasks：29 executed / 72 up-to-date），退出 0；实际 XML 仍为 775 项 / 75 suites，0 failure / error / skipped，lint 0 error / 55 warning。Debug/androidTest 本轮重新打包，lint 三类源重新分析、最终报告复用未变结果。同一专用 AVD 再次核验并覆盖安装本轮包，指定迁移类再次 `OK (10 tests)`、4.363 秒、结束 status 全为 0；此为重新运行，不借用前次设备证据。暂存内容仅本批 15 个代码/schema/文档文件，`git diff --cached --check` 通过，工作分支与远端起点一致；交付使用单次 Schannel 配置，不关闭证书校验、不强推。

## C1 · 第五批：共享发生与触发点计算（局部验收通过）

基线 HEAD `2227772`，工作区原先干净，继续用户选择的当前功能分支；已批准 C1 范围后用户中断仅停在阅读，随后明确继续。本批顺序实现紧耦合的纯领域任务并派发只读独立审查；Buddy 本地脚本仍缺失，不引入新分支/PR/Linear 或替换现有流程。

开始基线全量命令：JDK 25 / Gradle 9.4.1，JVM --rerun + Debug/lint，BUILD SUCCESSFUL（1 分 11 秒，66 tasks：2 executed / 64 up-to-date）。实际 XML 775 项 / 75 suites，0 failure / error / skipped；构建/lint 为缓存检查，不能当 C1 实现验证。

细化四项任务并计划独立审查：2 项 Important（WEEKLY 空星期回退、提醒查询完整性）、2 项 Minor（TIMED elapsed 与 ALL_DAY 日期跨度、C2 重复新建归属）已核对批准设计和旧规则后补契约；定向复审确认全部关闭，无新问题。提醒接口 next(now) 不截取日历窗口，无固定两年 horizon，预算/运算耗尽不能报无提醒；C2 复用算法，不再新建另一套。

TDD 首轮实质 RED：57 项新测试 / 3 suites，46 assertion failure / 0 error / skipped，编译成功（1 分 48 秒，40 tasks：12 executed / 28 up-to-date）。补全天跨 DST 日期跨度和跨午夜 trigger、强化多周空集合及古老锚点受限工作预算后，59 项 / 3 suites，48 assertion failure / 0 error / skipped（33 秒，40 tasks：5 executed / 35 up-to-date）。空结果/拒绝路径不能因临时默认返回而算作实现已完成；随后替换 API 临时声明，实现共享内核。当时 GREEN 和代码审查尚未完成，最终结论以以下复验为准。

隔离边界：新增文件仅 domain/plan 与 JVM 测试；生产 Room v5、Hilt、旧 ScheduleRules/提醒、Manifest/页面、版本和备份调用不切换，不写入业务数据、不运行设备升级。10,000 是计算候选工作预算，不是数据库或备份行数上限；取消/窗口外预选也消耗预算，冲突调用共享预算；超限显式抛错，不截断。

首次领域 GREEN 命令执行成功（1 分 25 秒，40 tasks：12 executed / 28 up-to-date），59 项新测试及已有领域规则回归通过。随后补强原始极值时间、古老月锚点、月间隔、冲突共享预算和数值溢出；再次定向 BUILD SUCCESSFUL（30 秒，40 tasks：5 executed / 35 up-to-date），实际 XML 为 103 项 / 5 suites，0 failure / error / skipped，含 66 项本批新增测试。这些测试补强已有正确实现，不虚称修复 RED。

代码独立审查提出 1 项 Important（调用方可传超过 10,000 的预算）和 1 项 Minor（多日重复跨度与跨日提前提醒组合覆盖）。先加三个公共入口 10,001 拒绝断言及两项组合测试，真实 RED 为 106 项 / 5 suites，1 assertion failure / 0 error / skipped（33 秒，40 tasks：5 executed / 35 up-to-date）；失败来自预算上限断言，两个组合用例通过。随后统一预算构造校验限制为 1–10,000，最终全量复验和定向复审另列。

最终复验（2026-10-11）：JDK 25 / Gradle 9.4.1，沿用网络 init 脚本，以下命令 BUILD SUCCESSFUL（2 分 2 秒，66 tasks：19 executed / 47 up-to-date），进程退出 0。

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain
```

- 实际 XML：844 项 JVM / 78 suites，0 failure / error / skipped，JVM 强制重跑。本批新增 69 项（发生 36、冲突 11、提醒 22）；共享领域定向 106 项 / 5 suites，包括已有规则/统计 37 项回归。
- Debug 本轮重新编译领域实现并打包；lint 三类源分析和报告本轮执行，实际 XML 0 error / 55 warning，domain/plan 与 data/plan 无 issue。既有 AGP/Kotlin/KAPT 弃用警告仍在，不声称零警告或全库无缓存重编译。
- 计划审查的 2 Important / 2 Minor 及代码审查的 1 Important / 1 Minor 全部定向复审关闭；无未解决 Critical / Important，静态审查不替代真实测试。69 项覆盖非活动字段、完成、例外、半开/闭区间、跨日/时区/DST、原始模糊偏移、月末/闰年/间隔/古老锚点、极值、操作共享预算、未来触发及无固定两年 horizon。
- 四项 C1 任务局部验收通过，README / CHANGELOG / 计划同步；仅提交推送当前功能分支，不合并 main / 新建 PR / 发布 APK。不更改生产 schema、Hilt、AndroidManifest、旧提醒、备份、导航或应用版本，不写个人数据，无占位业务入口、破坏性迁移或未实现方法。
- 限制：纯领域批未运行设备测试、生产升级、真实 Android 闹钟/通知、UI、真机或性能测试；不复用 B3 的 10 项设备结果。窗口冲突只覆盖明确范围；预算/日期运算失败向调用方传递，后续页面须处理。提醒函数只计算候选，尚无完整身份/世代/维护和投递重新读取协议；C2 再复用内核接线，整个 C 和 V1 尚未完成。

提交前检查：暂存仅本批 12 个 Kotlin/测试/文档文件，`git diff --cached --check` 通过；远端功能分支起点为 `2227772`，main 仍为 `b016bcb`。推送沿用单次 Schannel 配置，不关闭证书验证或强推；没有待处理的用户改动被覆盖。

## C2a · 第六批：维护安全用例与提醒身份底座（局部验收通过）

用户要求先推送再下一批；本次已成功推送 C1 `4c9b417`，随后远端查询 SHA 一致、main 仍为 `b016bcb`。工作区原先干净，继续当前功能分支，不创建新工作树。C2 按维护/身份、Android 排程/Receiver、升级校准分批验收；不把子批当全部 C2 完成。

基线 JDK 25 / Gradle 9.4.1，JVM --rerun + Debug/lint 三任务 BUILD SUCCESSFUL（1 分 45 秒，66 tasks：2 executed / 64 up-to-date）。实际 XML 844 项 / 78 suites，0 failure / error / skipped；Debug/lint 为缓存检查，不当作本批实现验收。旧 using-superpowers 本机文件及 Buddy 流程脚本不可用，沿用项目实施计划、原生 TDD 和只读独立审查；任务紧密耦合，由主代理顺序实施。

计划独立审查提出 3 Important（Room 提交/回执返回取消边界、共享锁生命周期与 retry、旧提醒及通知撤销范围）和 1 Minor（canonical URI 完整语法）。已核对仓库与设计后补精确契约，计划定向复审中。取消不能仅保护回调，测试必须覆盖真实事务已提交但用例尚未获得 ID 的交付窗口；提交前取消仍回滚，不采用整段写入不可取消来绕过原约束。

首轮测试先写，API 仅临时声明：33 项 / 3 suites，28 failure（3 分 15 秒，40 tasks：11 executed / 29 up-to-date），成功编译；失败包含缺失 API 抛错及身份/精度断言，不是 fixture/编译失败。临时默认抛错或空解析使部分拒绝路径通过，不能据此声称实现或回滚已验证；随后替换临时声明并开始实现复验。新的 Room 取消边界再做独立 RED/GREEN，不把单次通过当整批完成。

首次实现复验：33 项 / 3 suites 中 32 项通过，一项测试过度要求跨协程异常对象完全相同（2 分 22 秒，40 tasks：11 executed / 29 up-to-date）。查看实际堆栈后确认是 coroutine stack-trace recovery 的异常复制，测试改为核对异常类型及原因链保留原始故障；不改变错误处理实现，不将这一测试装置修正声称业务修复。

计划的 3 Important / 1 Minor 定向复审均已关闭。补六项真实 Room 的 SQL 插入取消、提交后 dispatched return 取消、交接/effects 取消，以及并发完成/删除/retry 与维护排空测试；加强后的 39 项 / 3 suites，只有提交后返回取消这一项 assertion failure（52 秒，40 tasks：5 executed / 35 up-to-date）。实际行已提交，但期望回执列表含已存 ID、实际为空，证明仅保护回调不足；其它 38 项通过。随后加原 writer Job 标记与提交交付桥，真实 Room body 仍可取消，已有无标记 B2 直接仓库行为不变；修复后同时运行本批与 B2 仓库回归，结果另列。

提交桥定向 GREEN：本批 39 项及已有 B2 Room 仓库 29 项，共 68 项 / 4 suites，实际 XML 0 failure / error / skipped；BUILD SUCCESSFUL（1 分 44 秒，40 tasks：12 executed / 28 up-to-date）。随后增加真实 Room 改时间/关闭/重新打开/变 NONE 的当前资格覆盖，以及 URI 负零/前导零拒绝断言；全量 JVM + Debug/lint 候选检查成功（2 分 48 秒，66 tasks：17 executed / 49 up-to-date），但独立审查仍发现下述取消异常边界，因此该候选不是最终验收。

代码独立审查提出 1 Important：实际 writer Job 取消会使外层 MaintenanceCoordinator 的 withContext 保留原始取消根因，内部包装的异常可能丢失 receipt。此前测试证明短交接保存了 receipt，但未证明用例调用者的异常类型。核对实际外层实现后，新增交接/提醒挂起/retry 的真实 Job 取消测试，并强化已提交 dispatched return 测试；直接在 writer 内捕获用例异常，不要求已取消 Deferred.await 保留包装异常。RED/GREEN 与定向复审结果另列。

该边界实质 RED：20 项真实 Room 用例 / 1 suite，4 assertion failure / 0 error / skipped，编译成功（1 分 23 秒，40 tasks：5 executed / 35 up-to-date）；失败分别来自交接、提醒挂起、retry 和已提交返回的实际 Job 取消。随后将 committed 保存在整个维护调用外，并在外层退出后包装取消；retry 同样使用外层已有回执，避免重复包装。事务 body 可取消和提醒所处上下文未变。

修复定向 GREEN：实际 XML 72 项 / 4 suites（本批 43 项：用例 13、真实 Room 20、值身份 10；已有 B2 Room 仓库 29），0 failure / error / skipped；BUILD SUCCESSFUL（1 分 57 秒，40 tasks：11 executed / 29 up-to-date）。独立复审的 spec / quality 均 PASS，原 1 Important 已关闭，无新增 Critical / Important / Minor；静态复核不替代下列最终全量结果。

隔离与剩余边界：本批用例、模块锁和 URI 值协议只构建底座；Room 测试使用完整 B3 内存 fixture、同一真实仓库和本库世代。记录型提醒 port 复用 C1 规则验证当前行/例外的计算结果和顺序，不声称实际 Android AlarmManager 排程/通知清理已通过。C2b 提供 Android effects、严格 Intent 元数据交叉检查、当前资格/投递去重；C2c 提供旧身份清理和校准。Hilt/生产 Room v6/页面/备份/通知入口/版本等消费者统一激活仍归 E。本批未运行设备、个人手机升级、UI、Release 或性能验收，不借用 B3 设备结果，也不新增未实现模块入口、修改现有业务数据或使用 destructive migration。

最终全量复验（2026-10-11）：JDK 25 / Gradle 9.4.1，以下命令 BUILD SUCCESSFUL（3 分 5 秒，66 tasks：12 executed / 54 up-to-date），进程退出 0。

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .tools/phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain
```

- 实际 XML：887 项 JVM / 81 suites，0 failure / error / skipped；JVM 强制重跑。本批新增 43 项，定向连同已有 B2 Room 回归共 72 项 / 4 suites。
- Debug 本轮重新 dex/打包最终修复，生产 Kotlin 编译为此前修复后定向命令已执行的 UP-TO-DATE 检查；lint 三类源分析及最终报告本轮执行，XML 0 error / 55 warning，domain/plan、data/plan、PlanReminderIdentity 无 issue。AGP/Kotlin/KAPT/旧测试弃用警告仍存在，不声称全库无缓存重编译或零警告。
- 四项 C2a 任务局部验收：原世代全局准入→共享模块锁→真实事务→提交回执→提醒；同身份日期转换/完成/撤销/例外/删父解绑，取消/故障回滚及维护排空；失败后只重试当前事实，不把坏读取当空结果；完整 URI literal/极值/碰撞/拒绝路径。计划 3 Important / 1 Minor 和代码 1 Important 均复审关闭，无未解决 Critical / Important。
- README、CHANGELOG、实施计划及本记录同步；交付仅当前 `feat/v1-unified-plans`，不合并 main / 创建 PR / 发布 APK。实际仍为 0.1.1 / code 2 / schema v5；本批不修改 Manifest/Hilt/Gradle/生产数据库实体或旧提醒消费者，不提交密钥/个人数据，不覆盖用户改动。整个 C2、D–F 和 V1 未完成，下一批 C2b。

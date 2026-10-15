# V1 统一计划分批验收

## F · 1.0.0候选交付（2026-10-15至16）

承接E6提交8e9ce14。当前源码1.0.0/code3，最终1122项JVM/92 suites及70项API35设备测试通过，0 failure/error/skipped；Debug/androidTest/Release构建成功，lint0 error/47既有warning。首轮设备67项/12失败、定向15项通过、采证脚本WAL遗漏的诊断，以及覆盖升级、真实文件往返、签名/性能/交付边界均集中记录于[本轮F交付验收](v1-delivery-acceptance.md)。以下E6及以前章节是历史结果，不将其版本/设备状态当作当前结论。

## E6 · 第十九批：生产整体接线（2026-10-15，源码验收通过，未发布）

范围已获用户确认，前置统计测试同步修复独立提交3ddbef6，远端已核对，main仍b016bcbc。首次schannel推送两次TLS握手失败；改用单次命令OpenSSL后成功，保持证书校验且未改origin/持久Git配置。

### 执行过程（中间状态；最终结论另列）

测试先行：V1ProductionDatabaseTest新增3项，通过实际LifeManagerDatabaseFactory打开新库、完整v5合成库及v4升级链；首编译因RuntimeEnvironment泛型调用和混合SQL绑定数组推断失败，修正后新库1项有效RED，其余2项测试fixture错误（旧schema实体可省略indices），不算业务RED；改为可选indices后3项全部有效RED（1m7s，40 tasks：4 executed/36 up-to-date，均期望v6实际v5）。尚未切换实现。

并行分工限定文件：父任务负责生产库/维护DAO/Core DI/备份设置/旧源退役及文档；入口所有者负责Activity/统一导航/订阅默认值及对应测试；提醒所有者负责通知/Application/Boot/Manifest/提醒DI及对应测试；测试装置所有者仅移植或明确映射退役的旧业务测试，保留v1原始wire/hash/schema/迁移回滚契约。所有Gradle由父任务串行，编译/测试运行期间冻结源文件写入，不提交半切换状态。首次入口+提醒组合编译被测试直接调用protected onNewIntent阻止（22s），属于测试调用错误，修正将使用系统生命周期入口，不改变生产方法可见性。

修正为Instrumentation.callActivityOnNewIntent后，组合44项/16失败（1m31s，40 tasks：5 executed/35 up-to-date）：提醒新增12项有10项失败，其中9项因缺失生产Application依赖、Receiver注册、Boot队列、旧Worker/Receiver退役得到有效行为RED；另1项最初错误期望过期token删除库存，现按已有C2契约核对保留未确认屏障、旧到达被拒且不追补，不把该期望修正记作产品修复。订阅新增3项因仍读旧设置得到有效RED。Activity3项均为Robolectric未运行AndroidX Startup导致WorkManager未初始化的测试环境错误，不计行为RED；用测试专用Application shadow先初始化WorkManager再直接执行真实LifeManagerApp.onCreate，不添加生产hook。其后进入原子接线，未为重跑旧Activity而重建已退役业务系统，因此不声称Activity专用RED已完成；最终必须在实际Hilt/Room/Activity图上验证GREEN。

实现阶段补充实际生产工厂v2/v3及由未变v2三表重建v1的完整升级回归；v1缺失原始asset限制不变。旧纯Todo/Schedule规则、DTO与入口退役，保留稳定枚举、原始wire实体和迁移/codec；原始测试数字1400不作为本批目标。旧源码和测试仅在工作分支中删除，Git历史可恢复，不删除个人数据库或历史schema/APK。

首次接线集成命令定向生产工厂/Activity/订阅VM/提醒激活：3m29s，35 tasks：20 executed/15 up-to-date；生产Kotlin与Hilt Java编译通过，生成生产schema6（13实体，identityHash f664a1eb646c605ac7e47de42a6fa4c2，与完整目标fixture一致）。JVM尚未执行：旧TopLevelNavigationTest仍引用已退役navigateTopLevel扩展、Activity测试Shadow.directlyOn泛型参数数量错误使测试编译失败。另出现3处导航Kotlin冗余空值警告，统一修正后重跑；不把本次编译失败称为测试RED或通过。设备TopLevelNavigationActivityTest尚待端口移植，本轮没有设备执行。

独立源码审查：DB/CoreDI分项通过；完整生产接线未发现可操作正确性问题，要求修正旧TopLevelNavigationTest残留及SubscriptionScreenTest设备装置构造旧SettingsRepositoryImpl两处编译阻断。已纳入入口所有者的测试移植修复；仍需实际Gradle结果，不能以源审查代替运行证据。

修正测试编译阻断并移植设备装置后，定向53项/6失败（2m31s，40 tasks：13 executed/27 up-to-date）。实际生产工厂6项、订阅VM29项、提醒激活12项全部通过；6项Activity仍未进入业务断言，自定义Application shadow未继承ShadowApplication，Robolectric在安装应用时ClassCastException。该失败仅计测试装置错误，不计业务RED，也不缩减真实Activity/Hilt验证范围。

shadow继承修正后的首次全量：1122项/12失败（3m6s，99 tasks：28 executed/71 up-to-date）。Debug与androidTest已重新打包，设备测试Kotlin/Java编译通过，lint三个源分析已执行。恢复时核对最终lint XML时间仍为本批之前，说明失败命令未刷新最终报告；0 error/55 warning是历史报告，不能算本次lint完成证据，须等最终成功命令重新生成。JVM失败集中为Activity6项（首项切页后尚未出现目标控件，后五项WorkManager静态实例重复初始化）、生产工厂装置的DataGeneration5项native SQLite文件打开错误，以及既有PlanWorkspaceComposeTest旧回调用例在新列表发布前查找旧行的同步窗口。正在分别验证根因；不以构建/部分lint分析掩盖JVM失败，也不将1122与历史1400差值冒充新增数量。

下一轮定向在Activity显式scenario.close清理中阻塞；jcmd线程栈证明SDK28主线程在ActivityScenario.waitForActivityToBecomeAnyOf等待自身生命周期关闭。核对PID为本任务GradleWorkerMain后只停止该测试JVM，未停止其他Java进程或删除文件；命令3m19s异常退出，仅12项已开始（5失败、1跳过），是中止诊断而非完整执行。后续改用外层RuleChain资源清理，让Compose规则先完成Activity生命周期，之后关闭WorkManager与实际Hilt数据库；真实Application.onCreate仍运行。

诊断日志确认5项SQLite错误来自Windows测试临时路径：UUID库名47字符，失败项数据库路径252–263字符，journal路径260–271；成功项的journal路径238–256，所有父目录均存在。保留生产工厂及WAL行为，仅将测试临时库名缩短并移除诊断日志，不关闭WAL、不改生产路径/持久化策略。旧回调用例另以测试专用Flow闸门固定初始空快照/新行发布窗口，等待实际行为RED后补正确同步；不引入生产测试hook。

第二次定向诊断表明RuleChain不能解决关闭阻塞：jcmd再次定位SDK28主线程经ActivityScenarioRule.after进入ActivityScenario.close等待生命周期。核对PID37428确为本任务GradleWorkerMain后仅停止该测试进程；上下文恢复后旧PTY会话已不可用，不补造该中止命令的数量或耗时，也不计作通过。接下来先独立运行非Activity两套测试，再根据本地Robolectric API改用直接驱动生命周期的测试夹具；保留真实MainActivity/Application/Hilt/Room及全部业务断言，设备ActivityScenario测试不变。

非Activity定向15项/1失败（2m25s，40 tasks：1 executed/39 up-to-date）：DataGenerationRepositoryRoomTest的10项全部通过，验证短文件名装置；PlanWorkspaceComposeTest的5项仅闸门用例失败，实际为未发布的“旧数据”行不存在，得到预期同步RED。测试修正为放行后等待当前世代、非加载且指定ID/标题行真正发布，再捕获原回调；全部过期回调断言保留，独立只读复审无可操作问题。该GREEN源的立即放行不单独证明延迟，确定性窗口证据来自前述实际RED。

Activity JVM装置在核对本地Robolectric API后改为createEmptyComposeRule和ActivityController直接setup/recreate/close，Compose环境存活至Activity销毁，外层资源规则随后清理WorkManager和实际Hilt Room。六项业务用例、九个真实VM保留断言、真实Application.onCreate和设备ActivityScenario测试均保留，不改变生产生命周期或方法可见性。正在执行组合验证，尚不据源码声称通过。

直接生命周期装置组合58项/3失败（4m15s，40 tasks：7 executed/33 up-to-date），完整结束且无关闭阻塞。DataGeneration10、PlanWorkspace5及三套导航37项全部通过；Activity6项有3项通过，余下两项对尚未组合的LazyColumn“保存计划/返回计划”调用performScrollTo找不到节点，一项首次草稿就绪等待未驱动暂停主线程而超时（实际栈在切页循环之前，不是切页后断言）。核对已通过装置的滚动/等待方式后，改为懒列表容器滚至节点、VM等待时调用waitForIdle，并复用已有目的地等待；不据此改业务草稿/导航保护。

修正后同一组合58项/1失败（2m40s，40 tasks：5 executed/35 up-to-date），九VM保留/草稿通知重建均通过。剩余真实Activity保存闭环已经成功写入并完成，失败位于返回列表、点击已完成筛选后的指定行等待；不把helper行号当作保存失败。继续采集列表状态和真实行诊断，确认点击/列表发布边界后再修改，尚未提交。

单项诊断1项/1失败（1m54s，40 tasks：5 executed/35 up-to-date）确认并非数据库或列表读取错误：点击前后列表available=true、loading=false、status=UNFINISHED，真实Room行的ID/标题/完成值均正确。语义树显示被hasText("已完成")选中的是编辑器回执纯文本（无OnClick），实际状态筛选Chip尚未滚入；文字重复造成测试误点，并非筛选不生效。修正测试匹配为同文字且hasClickAction的真实筛选控件，断言enabled后点击并等待COMPLETED及指定行；去除临时状态/语义/数据库诊断，不添加生产hook或修改查询/保护规则。

### 实现边界

- 生产工厂继续打开同名life-manager.db，注册完整1→6迁移与新建默认值；历史1→5保留独立数组和旧列初始化，5→6复用既有事务迁移。坏源/溢出/转换失败明确拒绝，不清库或重置本机世代。新建世代0；升级推进一次。生产schema6为KAPT生成的13实体，identityHash与目标装置一致，历史2–5资产不改。
- 统一计划、打卡、订阅、新偏好、V2快照/原子维护与提醒共享Hilt生产数据库单例和维护协调器；维护专用DAO不依赖设置DAO，本机世代严格检查唯一id=1、INTEGER且非负，异常不修复。V2是唯一新导出，V1先原hash/原始字段严格验证再转换，不将旧payload写入新表。
- MainActivity保留七个新版VM和两个既有Hilt模块VM，四入口为计划/打卡/订阅/设置。订阅默认值读新版偏好；计划/订阅通知均核对原世代和消费身份，混杂的无效计划信封不能降级套用订阅元数据，旧ID只安全回首页。草稿、备份busy、维护和过期确认保护继续有效。
- Application注册通知渠道、统一周期/即时校准与私有暂存初始化；Boot只提交原因，后台重新读取当前事实。旧持久Worker可反射创建并安全排空；旧Todo/ScheduleReceiver保留组件身份但拒绝访问退役表，精确旧闹钟清理仍保留，不保留第二条旧业务写入/通知投递路径。
- 旧正常CRUD/仓库/VM/页面、旧设置与旧备份维护所有者退役，保留迁移/原V1 wire/hash/枚举/通知兼容必要类型。测试移植及退役保证见[覆盖映射](e6-test-coverage-mapping.md)；不声称每个旧所有者的协程交错逐项等价，也不为维持历史条数保留第二套产品。

### 最终验证与结论

最后匹配修正和三处过时源码注释更新后，冻结所有测试/生产源，串行执行：

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
git diff --check
```

BUILD SUCCESSFUL，8m47s，101 tasks（23 executed/78 up-to-date）。实际最终XML汇总1122项/92 suites，failure/error/skipped均0；生产工厂6项、提醒激活12项、真实Activity6项、PlanWorkspace5项在全量中全部通过。相比前置基线1400项/120 suites的差异来自旧正常所有者用例退役、移植与新增，详见覆盖映射，不冒充新增数量或声称所有协程交错等价。JVM强制重跑，不使用定向结果或编译成功代替全量。

Debug重新打包；androidTest本轮KAPT执行，Kotlin/Java与包均UP-TO-DATE构建检查，首次E6集成时已重新编译/打包，但本批没有设备执行。lint三类源重新分析且最终报告成功生成；实际XML更新时间2026-10-15 15:11:23，0 error/47 warning，不使用11:02旧报告的55数字。警告包括依赖/AGP/KAPT建议、兼容API/旧用法、图标与未使用资源，不声称零警告；既有AGP/KAPT和测试deprecated编译提示仍保留。资源CloseGuard日志仍可在测试XML中观察到，不由日志所在测试名推断资源归属，不宣称消除泄漏。

独立只读审查覆盖DB/CoreDI、生产整体接线、设备装置移植、测试库路径/Flow同步及真实Activity生命周期/最后控件匹配，最终无可操作遗留问题。审查不能替代运行；前述源码修改后已执行本次全量。没有放宽世代/草稿/备份边界、删除六项Activity业务断言或增加生产测试hook。README、CHANGELOG未发布区、计划、schema/备份格式与覆盖映射同步，git diff --check通过；仅按既有授权提交推送当前功能分支，不能据此称F完成或V1已发布。

最终文档复审另独立核对实际XML、lint更新时间、schema实体/指纹及历史结果边界，无阻断问题；README备份章节一处“正在接入”遗留描述已改为“已接入（源码验收通过，未发布）”。这一步仅修改文档，不改变已验证生产/测试源。提交前路径范围、禁止提交产物/常见密钥模式扫描未发现问题；这不是对所有潜在敏感内容的绝对保证。

### 本批不包含

应用版本仍0.1.1/code2，本分支源码schema6不等于已发布0.1.1/schema5。没有main合并、APK发布/安装、个人数据操作、connectedAndroidTest、实际设备旋转/进程死亡、生产覆盖升级、真实SAF/Downloads/云提供方或Release/正式签名/性能验收；这些留F。JVM直接驱动生命周期不是设备证明，v1缺失原始schema asset仍是历史限制；不同URI文件别名、受限内存编解码和资源CloseGuard警告等既有边界不因本批消除。已升级库不允许安装V0降级，恢复/前向修复不使用destructive migration。

## E6 前置：统计工作区测试同步修复（2026-10-15，验证通过）

E6已获用户确认；初始工作区干净、HEAD5b24435。开始前1400项JVM全量强制重跑失败1项（1m44s，40 tasks：1 executed/39 up-to-date）：PlanOverviewWorkspaceTest的统计口径/返回保留筛选用例，在统计页面切换后立即查找完成率文本；该时刻页面只渲染加载状态。按基线要求暂停生产切换，用户同意先定位修复再继续E6。没有数据库/入口/提醒/版本改动。

根因验证：PlanStatisticsScreen显示时RefreshPlanOverview触发新鲜读取，refresh/retryRead同步设置isLoading，IO读取并非Compose空闲即可完成。添加仅测试使用、线程安全的StatisticsDispatchGate，初始就绪后暂停统计调度，切到真实统计页并验证加载指示可见；保持原来立即滚动结果的步骤，稳定得到4项/1失败RED（1m50s，40 tasks：5 executed/35 up-to-date），与原缺失文本一致。修复只在测试中放行调度后等待isAvailable&&!isLoading，保留原UI统计文本和列表筛选回归断言；不加固定sleep、不延长超时、不删除/弱化断言、不修改生产查询或页面时钟。闸门在After清理时释放以免失败遗留暂停任务。定向/全量和独立复审结果待补，不能把测试修复记成E6已接入。

最终测试源定向4项/0失败（1m11s，40 tasks：4 executed/36 up-to-date），随后执行E5相同全量命令：testDebugUnitTest --rerun、assembleDebug、assembleDebugAndroidTest、lintDebug，BUILD SUCCESSFUL 2m57s，101 tasks：4 executed/97 up-to-date。实际XML1400项/120 suites，failure/error/skipped均0；本次没有增加测试条数，只加强现有统计工作区用例。Debug/androidTest全为缓存构建检查；lint仅测试源重新分析，报告为缓存检查，保持0 error/55既有warning，不称重新打包或设备执行。独立只读审查无可操作问题，确认闸门线程安全、失败后释放、实际状态同步、原断言保留及无生产hook。README和CHANGELOG同步，E6生产切换尚未开始。

## E5 · 第十八批：新版备份设置与导航保护（2026-10-15，局部验收通过）

用户已确认本批范围，基线145ae17；继续当前功能分支。不切生产Room5/Hilt/MainActivity/默认备份或版本，不发布APK、不合并main、不接触个人数据。沿用已批准的V2文件/维护底座，新增独立备份设置所有者及真实准备页面；完整生产原子激活和F验收仍后续。

过程记录：基线旧备份/准备导航定向测试命令成功（13s，40 tasks：1 executed/39 up-to-date；未在当时汇总数量，不补造数字）。新所有者首次测试编译发现调度器接收者错误，修正后仅因缺失新API编译失败（20s），这两次不算行为RED。首实现16项VM测试通过（3m23s；40 tasks：11 executed/29 up-to-date，0 failure/error/skipped），随后用户中断，保留未提交文件；恢复后继续页面与安全边界测试，不以16项定向结果替代完整验收。独立审查及最终运行证据将在完成后补充。

页面行为RED：14项/3失败（3m30s，40 tasks：5 executed/35 up-to-date），缺失V2入口、保留旧偏好回调仍写入、保留旧导航回调仍切页；接入真实准备页面及实时busy保护后36项/3 suites通过（3m27s，40 tasks：12 executed/28 up-to-date）。没有将通过的原有用例冒充新增RED。

独立审查首次服务容量错误无审查结论；重新只读审查发现3项Important：lazy任务尚未进体即取消后无清理；重建时已返回文件结果清空pending票据但VM仍处PICK阶段，重复启动选择器；已在世代读取等待的通知交付可跨越备份开始。回归36项/4失败（1m30s），其中提前取消1项与选择器两项均实质RED（选择器期望启动1次实际2次），通知用例最初拦住Legacy接收第二次读取，尚未发布pending而超时，不计有效RED。改为第三次/最终交付读取后单项1失败（1m41s；期望IDLE实际PREPARING），正确捕捉竞争。

按根因修正：原worker以ATOMIC进入try且第一步ensureActive，取消前无I/O但保证清理；source/output分别持有rememberSaveable已启动标记与未归还票据，结果消费不抹去本操作启动历史，新UUID不能覆盖旧未归还结果；设置备份回调同步核对live destination==SETTINGS、pending/confirmation均空和编辑/偏好状态，与最终Main通知交付互斥，交付完成后旧设置回调也失效。补充完整13实体Room的设置所有者→真实文件暂存/保护→单事务恢复/取消三项测试。当前复验进行中，最终数量与限制另补。

修复后43项/5 suites定向通过（3m3s，40 tasks：10 executed/30 up-to-date，0 failure/error/skipped）：VM17、原导航新增回归后17、Dialog4、Picker2、完整Room3。独立只读复审确认3项Important均关闭，无新增Critical/Important/Minor；审查只认可隔离准备，不替代运行证据。最终源随后仅补齐ATOMIC的DelicateCoroutinesApi显式opt-in（取消安全理由及入口ensureActive不变），避免新增生产编译警告；全量命令在该最终源上运行，结果待补。

### 实现与安全边界

- `V1BackupSettingsViewModel` 独立StateFlow，用实际版本/时间生成新V2文档，隐私确认先于输出；旧V1先严格验证再共享转换，展示真实新表数量、源格式和旧待办本次设备时区来源。一次操作固定原世代与UUID，文件/确认回调还校验阶段；旧回调不能改投新操作。恢复的保护文件必须另存并重读成功，之后才显示独立最终确认；清空可明确跳过保护，但仍需最终确认并保留设置。
- 复用D4原子维护，不新写事务/闹钟旁路；数据成功、未变更、结果未知分开。已提交取消及提醒失败不提示回滚、不重复SQL；未知结果禁止新导出/恢复/清空，只有用户显式重读本机严格世代与完整快照成功才解除，不能把读失败当回滚。维护许可内读取/发布当前事实，普通慢文件读写不持有SQL事务。
- 新版设置和准备导航显式绑定真实备份所有者，无缺省占位模块。备份busy期间切页/返回/偏好写入有实时检查；通知交付与备份开始互斥。源/输出分别保存已启动标记与未归还票据，重建不重新创建VM操作，不把旧系统结果改绑新UUID；进程新所有者从IDLE开始，过期回调被拒绝。E5应用代码不将URI或文档字节写入自有UIState、rememberSaveable/自定义Bundle或日志，也不在弹窗展示；文件位置仅在活动操作及工作流内存中使用。此保证不覆盖Android/AndroidX文件结果传输、框架持久化或框架日志，不作跨平台绝对隐私承诺。
- 新增32项（4个新suite：VM17/Dialog4/Picker2/Room3，既有导航suite新增6项）。完整目标Room证据包含13实体、实际snapshot/mutation、codec、私有暂存文件与workflow；提醒端在本套接线测试中替换为无系统效果端口，既有真实系统提醒测试仍由全量回归覆盖，不声称本批已操作真实AlarmManager或文件提供方。

### 最终验证

最终源（含三处修正、补齐opt-in）执行：

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
git diff --check
```

BUILD SUCCESSFUL，4m25s，101 tasks（19 executed/82 up-to-date）。实际XML汇总1400项/120 suites，failure/error/skipped均0；相比基线1368项/116 suites新增32项/4 suites，未用历史结果或只用BUILD SUCCESSFUL替代数量。Debug重新打包；androidTest KAPT/Kotlin/ASM重新执行，下游包和assemble为UP-TO-DATE构建检查，没有设备执行。lint三类源重新分析并生成报告，实际XML为0 error/55 warning，本批修改/新增文件无lint问题。既有AGP/KAPT/旧测试deprecated警告仍存在，本批新ATOMIC已显式说明并opt-in。资源CloseGuard日志本轮出现在TodoReminderAdmissionTest和LauncherBrandingTest结果中，不证明资源归属于这两个类，不宣称消除泄漏。

独立最终源复审无Critical/Important/Minor遗留；文档复审发现“Bundle/日志不含URI”的绝对说法超出应用控制，已缩小为E5自有状态/存储/日志范围。README/CHANGELOG未发布区/原实施计划/本验收同步；仅按用户授权提交并推送当前feat/v1-unified-plans，不修改origin、不合并main、不提交密钥或个人数据。五任务局部验收完成，不等于生产V1启用。

### 限制与后续

本批不切生产Room6/Hilt/MainActivity/旧Worker/Receiver/默认v1备份，不调整0.1.1/code2，不合并main、不发布或安装APK。Robolectric保存状态重建不是实际设备旋转或强杀进程证明；没有本批connectedAndroidTest、真实SAF/Downloads/云文件提供方、个人库恢复、生产覆盖安装、Release/正式签名、真机/大库内存或性能验收。仍保留D4文件提供方别名/协作取消/不保证跨系统原子/64MiB及100k受限内存表示/资源关闭警告等限制。新版生产数据库、全部消费者和提醒/备份绑定须在最终E关口一起激活，F交付后续，不能把本批准备状态当手机已启用功能。

## E4 · 第十七批：统一导航与新版设置准备（2026-10-14，局部验收通过）

用户确认范围；基线2ad6096、当前feat/v1-unified-plans初始干净。按E4局部准备，不切生产Room5/Hilt/MainActivity/默认v1备份/旧提醒绑定/版本0.1.1-code2，不发布或安装APK，不合并main，不接触个人数据。完整生产激活及F仍后续。

### 执行过程（中间结果；进行中/待补描述当轮状态，最终结果在节末）

基线导航/设置定向JVM：102项/9 suites、0 failure，BUILD SUCCESSFUL 2m9s，40 tasks（1 executed/39 up-to-date），测试强制重跑。首轮建测试接口期间继续新增文件，导致该次编译的生产源集合不含新接口、后续测试编译引用失败（4m22s），没有行为测试，不计RED；后续固定文件集合后运行。有效行为RED27项/4 suites、22失败（4m8s，40 tasks：11 executed/29 up-to-date），涵盖实际字段、事务拒绝/回滚、通知协议与草稿导航；5项无效/维护拒绝特征先通过，不冒充RED。首实现编译失败34s：SQL参数混合String/Int的arrayOf推断交叉类型，改显式arrayOf<Any>，不是行为RED。当前核心复验和新UI RED实施中，最终证据另补。

只读计划审查指出草稿确认与提交后取消、导航Busy重放、SQL严格原始值和UI旧标签等风险。实现采用独立导航确认，不提前设置编辑器confirmClose；原许可内最终ticket/精确编辑stamp核对，维护拒绝不排队，旧通知不读取旧ID。设置使用独立领域模型和事务接口、原writer控制SQL体与NonCancellable短提交交付桥，提醒效果在准入释放后执行。该记录不是完成验收。

第二轮31项/5 suites，核心27项全部通过，UI4项缺节点的实质RED（2m50s，40 tasks：12 executed/28 up-to-date），0 error/skipped。替换UI桩为真实四入口准备导航与新版设置；真实PlanWorkspace/HabitScreen/SubscriptionScreen/V1SettingsScreen接线，模块所有者显式传入并保留，不用空目的页或示例数据；设置共用控件提取成纯展示模型，不将旧AppSettings当新版领域模型。另补提交后取消、提醒重试时坏世代，以及完整Room中原writer取消的回归，复验中。

第三轮44项/6 suites（34新+10旧设置界面），43通过/1失败，4m9s、40 tasks（11 executed/29 up-to-date）。四入口真实页面和草稿交互、独立提醒保存、读取错误重试、原设置界面、提交后取消与真实SQL取消回滚均通过；剩余“提醒重试遇坏世代”实际assertion failure，XML同时有未捕获metadata异常作为suppressed。定位为retryReminders准入无catch，补异常分支并禁写，不增加超时或重复保存。后补同Tab点击、日历跨Tab保留、旧根导航回调、320dp/200%字体深色验证；首次测试编译因缺PlanCalendarMode import失败2m9s，不计行为RED，修正明确import后复验中。

第四轮48项/6 suites，47通过/1失败（2m48s，40 tasks：5 executed/35 up-to-date），坏世代提醒重试回归、日历跨Tab保留、旧根回调、深色320dp/200%字体均通过。新同Tab实质RED：点击已经选中的计划Tab仍弹放弃当前草稿确认；改为已选目的地无操作，不改变通知打开详情的独立路径。补强真实打卡/订阅控件断言，以及旧设置回调和同源总开关更新后的计划提示验证。

独立UI源审查1 Important（根BackHandler早于目的地子BackHandler，维护中会被详情/编辑返回抢先处理）、1 Minor（目的地测试缺真实内容断言，已补控件）。将补系统Back冻结详情/编辑及旧确认的实质回归，再修正目的地后的维护Guard和实际关闭时的实时维护拒绝。生产路径仍不切换。

第五轮50项/6 suites，49通过/1失败（4m5s，40 tasks：10 executed/30 up-to-date）：同Tab、旧提醒控件及真实其他模块控件通过；同源总开关更改后留存计划草稿提示仍旧值，实际assertion RED。Root在偏好变化/返回计划时重新准入读取编辑状态，不清草稿；修复后该回归通过。第六轮55项/6 suites，5实质RED（4m8s，40 tasks：11 executed/29 up-to-date）：两项真实系统Back、一项维护中旧关闭确认、两项缓冲旧世代/实时metadata读取。添加目的地后BackGuard和PlanEditor实时维护检查；导航Flow改为失效通知、许可内严格重新读取并支持重读，不把观察到的旧值直接作为导航令牌。

核心复审另3 Important：新请求到达但尚未发布期间旧确认仍有效、错误详情阻挡安全恢复导航、提醒重试准入失败仍安排后台；将补延迟metadata读取/实际错误详情/后台请求边界回归。另补Main交付暂停与效果在途取消覆盖；Room失效测试加首次真实发射屏障，避免“更新后才订阅”伪通过。修正和最终复验未完成前不交付。

第七轮57项/6 suites，3实际assertion failure（4m14s，40 tasks：11 executed/29 up-to-date），前三项核心复审问题均复现；第五/六轮问题已通过。修复最后效果同时核对到达序号与待处理ticket；身份打开才要求计划可读，安全恢复导航保留草稿决策但不因错误详情禁用；提醒仅在实际准入之后安排后台。补充暂停Main交付时取消仍发布已提交事实、效果在途取消仅重试效果，不新增生产测试钩子。结合旧E2编辑/工作区与旧设置回归验证中。

覆盖修复GREEN89项/8 suites（49新+40旧），0 failure/error/skipped，4m8s、40 tasks（10 executed/30 up-to-date）。首次全量复验1366项/116 suites、0 failure/error/skipped，完整JVM/Debug/androidTest/lint命令BUILD SUCCESSFUL 4m56s、101 tasks（20 executed/81 up-to-date）：JVM强制重跑，Debug重新打包，androidTest KAPT/Kotlin重新编译、包为缓存检查，lint三类源重新分析/报告重新生成，实际0 error/55既有warning、本批受影响路径无新增issue。既有SQLite CloseGuard警告日志位于SubscriptionReminderAdmissionTest与LauncherBrandingTest的XML，不证明归属这些类；未宣称消除。

最终源复审已有问题全部已验证关闭，但另发现1 Important：新输入提前推进到达序号、随后被拒时旧确认仍留在UI，确认/取消因序号过期均无效。补“旧世代通知被拒”和“目的地排队准入失败”两项回归，必须清过期弹窗所有权但不清草稿，修复及最终复验后才提交。首次全量是该修复前的中间证据，不等于最终交付。

两项最后回归实质RED：导航17项/2 failure，2m26s，40 tasks（5 executed/35 up-to-date）。nextTicket在新到达时立即结束旧弹窗/等待/待处理所有权，原编辑草稿不变；保留许可内到达序号检查。导航17项GREEN（0 failure/error/skipped），4m7s，40 tasks（11 executed/29 up-to-date）。独立定向源复审关闭该问题，无遗留Critical/Important/Minor；审查者没有执行测试，结果由主代理核对。最终全量复验中。

### 实现与验收边界

新版设置使用V1AppSettings/领域仓库及独立V1SettingsViewModel/StateFlow，不借用备份DTO或伪造旧领域值。完整v6 Room装置严格核查settings/metadata单行、SQLite类型、Boolean/枚举/币种/提醒mask；原世代检查贯穿事务，缺行不补默认、无REPLACE/破坏性迁移。更新只改设置，订阅/计划单项偏好与记录不被改写。原writer控制SQL体，提交返回与短Main交付免于取消；提交之后才校准，错误/取消仅重试效果，未获准重试不安排后台。旧设置UI通过纯展示模型共享控件，旧标签/回调/模块说明/返回文案不变；新设置只有计划/订阅提醒，保留主题/日期/币种/订阅默认日/系统权限控件，没有打卡假开关或未接通的备份按钮。

V1准备导航要求显式传入实际Plan列表/编辑/日历/统计、Habit、Subscription和新版Settings所有者，四个真实页面无占位；持有VM与saveable目的地状态，返回保留日历模式/日期、计划草稿及筛选。导航Flow仅失效，许可内严格读取元数据并发布；到达序号/原世代/编辑精确stamp共同防旧回调与旧确认，维护拒绝不重放，系统Back及关闭实时检查冻结。错误详情不阻挡设置等安全恢复导航，身份打开仍要求可读；计划提醒总开关变化/返回重新查询提示，保留当前草稿。

新的计划点击适配器严格验证action/Activity组件/规范URI/Long元数据，在未来Main接线时使用；消费Intent避免重组/旋转重放。新通知等待草稿保存或明确放弃，只在编辑器确实接受新的详情会话后消费；被拒新到达不留下不可关闭的旧对话框。旧Todo/Schedule通知仅产生HOME与失效说明，根本不读取或映射旧数字ID，订阅Intent不被适配器吞掉。

本批全部为合成数据JVM/Robolectric/Compose/完整v6内存Room。未运行设备测试、实际旋转/进程重建、系统通知点击、生产覆盖升级、Release/签名或性能验收。ViewModel/saveable保留测试不是这些设备证明。生产数据库5/Hilt/MainActivity/备份/提醒仍旧路径；新版备份设置界面、完整生产统一激活与F仍未完成。不是手机已启用四入口、v6迁移已发生或V1已发布；继续功能分支，不合并main、不改版本、不发布APK。

### 最终验证与交付

新增51项/5 suites：V1SettingsRoom7、PlanNavigationViewModel17、PlanNotificationClick4、V1NavigationCompose11、V1SettingsViewModel12。另保留旧设置10、E2编辑25/工作区5的89项组合GREEN（最后两项新增前）；最后导航17项单独GREEN不替代全量。最终源复审针对所有Important/Minor及最后弹窗修正关闭，无遗留意见；审查未执行命令，以下实际输出/XML由主代理读取。

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
git diff --check
```

最后修复后完整命令退出0，BUILD SUCCESSFUL 5m12s，101 tasks（16 executed/85 up-to-date）。实际XML1368项/116 suites、0 failure/error/skipped，JVM测试强制执行；Debug重新打包，androidTest Kotlin/KAPT重新编译，包为up-to-date构建检查，未运行connectedAndroidTest。lint三类源重新分析/报告重新生成，实际0 error/55既有warning，本批受影响路径无新增issue。仅文档更新发生在此后，代码与测试未改。

既有AGP/KAPT/旧测试弃用提示仍在；最终Robolectric SQLite CloseGuard警告出现在TodoReminderRegressionTest与LauncherBrandingTest的XML，不能凭日志类名确定资源泄漏归属，不宣称消除。README、CHANGELOG未发布区、实施计划同步结果与上述边界。交付按既有授权提交/推送feat/v1-unified-plans并核对远端，实际SHA由回复确认；main与已发布APK不变。

## E3 · 第十六批：日历与独立全库统计准备（2026-10-14，局部验收通过）

用户已确认范围，继续 `feat/v1-unified-plans`，基线 `aa6da6789141acabcbe5082560d02fca62681ab6`，工作区初始干净。本批仅准备页面/状态/查询及测试，不切生产 schema5/Hilt/根导航/设置/备份/提醒，不改 0.1.1/code2、不合并 main、不发布 APK 或操作个人数据。

已批准完整设计继续有效，文件级 E3 五任务补入原实施计划；`writing-plans` 与 Buddy 状态/进度脚本不可用，沿用项目原生 TDD/Gradle/独立只读审查，不引入网页/Linear/新分支流程。独立计划审查要求明确仅日期准入与时钟刷新：日期日历按计划时区的民用日期窗口筛选并保留原日期，统计今日集合仍按展示时区真实窗口相交；页面前台恢复及可见时每60秒刷新 Clock/展示时区，数据库无改动也重算。

中间记录（不是最终交付）：

- 定向基线 `domain.plan.*` + `ui.plan.*` 使用 `--rerun`，244项/14 suites，0 failure/error/skipped，BUILD SUCCESSFUL；构建相关任务为缓存检查。
- 日历查询编译用最小空结果骨架：14项中13项预期失败，缺少真实日期/记录/预算检查；随后实现查询，旧14项通过。随后追加远时区月末和日历/今日统计分离测试。
- 状态编译骨架下，11项 ViewModel 测试中10项预期失败（可读状态、日期选择、世代及统计事实缺失）；该轮总25项中14项日历通过，不能把该轮整体失败写成通过。随后实现状态管理并继续验证。
- 新 Compose 装置首次编译因 JUnit/Kotlin `Test` 两个星号导入歧义失败；比对已有显式 JUnit 导入后仅修正导入重跑，尚不是 UI 行为 RED 或 GREEN。
- Room 装置等待函数与模型属性同名，`::calendar` 初始化检查编译歧义；仅将等待函数改为 `awaitCalendar` 后重跑。
- 随后42项行为执行：11项状态、5项真实Room均通过；16项查询有1项真实RED（UTC+14日期格与UTC−12今日真实窗口混算），6项空页面与3项缺少入口的工作区为预期RED，原草稿保护工作区测试通过。据此将今日统计改为单独真实窗口相交，实装页面/工作区后重跑，未以编译缺失代替行为RED。
- 47项定向实现后执行46通过，1项工作区超时定位在“进入日历后立即直接调用 selectMode”：页面初次刷新处于加载保护中，VM正确拒绝操作；测试改为等待真实读取完成、点击已启用日视图按钮，4项工作区定向强制重跑通过（0 failure/error/skipped）。未增加超时或放宽应用门禁；全量验证另行执行。
- 首轮全量1317项/111 suites有1项既有E2工作区测试失败，本批42项均通过；命令在测试失败后停止，不能声称该轮构建/lint完成。XML定位“仅已收到回执且非忙”先于详情已加载，滚动找到正在退出的编辑页返回按钮、点击时加载详情页尚无该节点；改为等待实际详情/非载入/可读/pane及回执后操作，不延长超时、不改生产代码。只读线程检查显示Robolectric等待测试沙箱而非证明死锁，随后全量正常结束，最终复验见下文。
- 修正后新旧工作区9项/2 suites定向强制重跑通过，XML 0 failure/error/skipped。独立只读源审查关闭计划两项口径澄清，无遗留 Critical/Important/Minor 代码问题；明确确认上述同步修正匹配真实提交回执→详情准入读取顺序，不增加等待时限或减弱验证。

### 实现与最终验证

日历只读状态由独立 PlanCalendarViewModel/StateFlow 管理，月/周/日窗口、选日、前后窗口、回今天及显示已完成实装；月份日期格保留仅日期/全天原日期，真实时间区间按展示时区分组且结束排他。重复复用原锚点/取消例外；已完成系列仅一完成标记，无日期待办不进日历。查询共享候选预算，错误/超限不返回部分成功。周/日为日期分组事件列表，不是小时拖拽时间轴。

PlanStatisticsViewModel 独立全库观察，完成率/今日完成/待完成/逾期复用已有规则；今天日程按实际窗口相交、计划去重，包含已完成单次计划而不把系列完成标记计入，不受列表过滤影响。民用日期日历与真实窗口今日统计在跨时区时可以不同，页面说明口径。前台出现/恢复及可见时每60秒重新查询 Clock/展示时区，未修改数据库数据或仅日期持久化语义。

两个读取状态都经过 GenerationAccess 许可内重读并发布，Flow 只作失效通知；维护拒绝选择/打开，不自动排队重放，替换清旧事实、旧令牌失效，读取失败不显示成功零值。准备工作区提供实装列表/日历/统计切换；日历打开同一身份详情，返回保留日期/模式和列表筛选，详情/编辑不显示绕过草稿的切页入口。复用原生卡通主题、Material3、日期展示设置及浅深色；320dp/200%字体、长文本、语义选择和真实空/错状态有 Compose 验证。

新增42项/5 suites：查询16、状态11、Compose6、工作区4、完整v6 Room5。Room覆盖例外表实际失效、系列完成、同ID日期转换、坏SQL读取/恢复、真实世代替换和维护取消；实例数据均合成且内存数据库隔离。另修正1项旧E2工作区测试的真实读取等待条件，未新增生产测试专用方法。

最终命令（主机JDK25，设备字节码17）：

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
git diff --check
```

BUILD SUCCESSFUL，5m29s、101 tasks（17 executed/84 up-to-date）。XML求和1317项/111 suites，0 failure/error/skipped，JVM实际强制重跑。Debug重新打包；androidTest Kotlin/KAPT重新编译、打包为缓存检查，没有 connectedAndroidTest。lint三类源码重新分析及报告生成，0 error/55既有warning，本批日历/统计/工作区无新增lint问题。独立计划两项澄清、代码及旧测试同步修正复审均关闭，无遗留审查意见。

仍有既有AGP/KAPT弃用提示和归属待定位的Robolectric SQLite CloseGuard资源警告，最终日志见 TodoReminderAdmissionTest、UnifiedBroadcastRepairTest、LauncherBrandingTest 的XML；日志所在测试类不证明资源泄漏归属于它们，本批不宣称消除。没有设备执行/TalkBack实机/旋转与进程重建/生产升级/Release/性能验收。本批只是可编译准备工作区，不是手机已启用功能；完整E生产激活、F升级交付及整个V1尚未完成。

交付按授权仅提交/推送 `feat/v1-unified-plans`，远端SHA另由交付回复确认；main、版本/schema及历史APK保持不变。

## E2 · 第十五批：详情与编辑准备（2026-10-14，局部验收通过）

用户恢复“继续”，按已说明的 E2 范围在当前 `feat/v1-unified-plans` 开发。生产仍 0.1.1/code2/schema5；不切 Hilt/根导航/备份/系统提醒、不发布 APK、不合并 main、不处理个人数据。页面通过独立准备工作区连接真实领域用例；不是手机已开放的新版入口。

实现：共用新建/编辑、四时间模式、仅日期截止、显式模式切换确认、地点/参与人/颜色/标签/优先级/重复/单项提醒配置；同身份日期转换保留完成、创建、父关系、排序和非活动参数，原始标签与 nullable/空串不自动归一化。历史固定偏移和空白标题继续可读，新编辑要求注册 IANA 与非空标题。带偏移输入保留毫秒与 DST 歧义选择，越界毫秒拒绝，不把 LocalDate 变成午夜 Instant。

保存核查在全局准入→共享 Plan 锁内完成：当前记录、并发完成状态、真实冲突与取消例外重新读取；内容改变拒绝旧草稿覆盖，完成变化保留最新事实。确认证明绑定候选/窗口/冲突内容/相关例外，变化后重确认。核查含首区间及从首日本地日期起至少30天，仅证明有限窗口，不宣称无限重复无冲突。取消例外含无日期旧记录被重新激活的候选。

PlanEditorViewModel 独立 StateFlow，回调绑定世代、编辑会话和必要的草稿修订；维护取消保留草稿，世代替换使旧草稿失效，读取/设置失败禁写，关闭提醒总开关不阻塞离线 CRUD。提交回执先消费新建草稿，再执行提醒；失败/取消仅按明确记录 ID 重试效果，跨返回/再次新建保留待处理回执。详情完成/撤销、确认删除（子计划仅解绑）及列表→详情→编辑→返回接线；分类变化提示且保留列表筛选，不增加日历/统计空入口。

### TDD 与中间验证记录（不作为最终交付）

- 新基线：JDK25/Gradle9.4.1/既有 network init，`:app:testDebugUnitTest --rerun --console=plain` 退出0，BUILD SUCCESSFUL 3m11s，40 tasks：1 executed/39 up-to-date；实际 XML1191项/100 suites、0 failure/error/skipped。
- 缺契约反射 RED4项全部 assertion failure，1m17s，40 tasks：5 executed/35 up-to-date；建立最小临时类型后用 typed 真实行为测试替代，最终删除反射检查与空行为，不把测试桩留作功能。
- 草稿23项与领域契约1项组合：24项/23 failure，2m48s，40 tasks：12 executed/28 up-to-date；一项未编辑草稿特征断言通过。最小草稿实现后，核心50项/21 failure，3m35s，40 tasks：11 executed/29 up-to-date，其中草稿23全部通过、领域5失败、VM16失败（缺行为等待超时不代替具体正确性断言）。
- 核心首轮 GREEN83项/5 suites：草稿23、保存核查9、编辑VM18、既有用例13、真实用例Room20，0 failure/error/skipped；3m56s，40 tasks：12 executed/28 up-to-date。
- UI首轮测试编译因双 `Test` import 失败19s，未计行为RED。修正测试 import 后界面9项全为缺节点/工作区 assertion failure；组合34项/9 failure、草稿25全部通过，1m13s，40 tasks：5 executed/35 up-to-date。另两项草稿测试覆盖精确 Long 毫秒上下界及越界1ms拒绝。
- 界面与完整13实体v6合成Room装置：14项/2 suites（8实际交互+1临时契约、Room5）0 failure/error/skipped，2m40s，40 tasks：12 executed/28 up-to-date。临时UI契约随后移除，工作区由 typed 交互覆盖；不是设备执行。
- 独立核心审查3 Important：再次编辑同ID的旧回调、当前草稿保留区间重新启用漏确认、NONE候选保留例外漏查。新增4项回归与工作区3项得到34项/7 failure，1m19s，40 tasks：5 executed/35 up-to-date，均真实复现。修复为每次编辑新会话、核对当前四区间字段、例外读取含持久候选ID。修复组合56项/6 suites（保存11、编辑VM20、界面8、工作区3、Room5、旧列表界面9）全部通过，2m45s，40 tasks：11 executed/29 up-to-date。
- 另补两项回归：冲突弹窗返回修改必须保留草稿而不陷入关闭/冲突循环，详情已更新后旧删除确认必须失效；30项/2 failure，1m4s，40 tasks：5 executed/35 up-to-date。明确冲突取消动作仅清证明，详情刷新更新修订并关闭旧删除确认；弹窗正文支持长内容滚动。修复后界面9/编辑VM21共30项/2 suites、0 failure/error/skipped，2m27s，40 tasks：11 executed/29 up-to-date。
- 审查第二轮修复前全量：`:app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain` 退出0，2m59s，101 tasks：21 executed/80 up-to-date；XML1267项/106 suites、0 failure/error/skipped。Debug重新打包，androidTest重新编译/构建检查（包为up-to-date），lint三类源重新分析并生成报告0 error/55既有warning。工作区5项含双向日期转换与旧新建/详情语义回调在世代替换后拒绝；此为中间结果，后续确认令牌/删除锁内复核修正后必须全量重跑。
- 独立整合复审3 Important/2 Minor：冲突证明替换未推进修订、取消关闭后旧确认可套用新待跳转目标、删除等待锁期间记录改变仍删除；切换确认未展示实际参数、Long合法边界缺直接解析路径。新增3条VM与1条UI实质RED：60项/4 failure，1m33s，40 tasks：5 executed/35 up-to-date；另1条纯草稿测试用两个独立字面日期字符串及null旧值证明精确毫秒解析，先通过且不冒充RED。修正每次确认请求/证明发布/取消的修订，删除在全局准入→Plan锁内重新比较已确认的完整记录快照，切换弹窗展示草稿具体日期、重复、自定义星期/结束、单项提醒与时区，复验中。
- 第二轮修复定向 GREEN90项/6 suites：VM24、界面10、草稿26、Room5、工作区5及旧用例Room20，0 failure/error/skipped，2m42s，40 tasks：11 executed/29 up-to-date。此后再补完整Room“确认后内容改变不删除”和VM“两次新建失败提醒按各自ID重试不重复写入”的集成覆盖；不是把既有正确行为冒充首次RED。
- 再次全量1274项/106 suites、0 failure/error/skipped，完整命令同前，3m14s，101 tasks：19 executed/82 up-to-date；Debug重新打包、androidTest重新编译/包构建检查、lint三类源重新分析（报告内容未变，lintReportDebug为up-to-date，实际仍0 error/55既有warning）。源复审已无Critical/Important，剩余1 Minor：模式确认不能以保留的dueDate替代当前选中的dueAt显示；补最后界面回归中，此数字仍不是最终状态。
- 最后一项提示回归 RED：界面11项/1 failure，1m19s，40 tasks：5 executed/35 up-to-date。切换弹窗分别展示截止日期/时刻并标识所选表示，表单与弹窗明确截止模式保存的互斥规则；不误称两个有效截止字段同时保留。最终复验中。

### 验收限制

本批仅合成数据 JVM/Robolectric/Compose/Room；设备/旋转/进程重建、真实通知草稿、系统文件提供方、生产切换、覆盖升级、Release/签名/大库性能留后续 E/F。时间/日期使用有格式提示的文本输入，不声称已实现系统日期选择器；未做小时拖拽或单次重复完成。AGP/KAPT与旧测试弃用警告、既有归属待定位的 SQLite CloseGuard 警告不隐藏。E2局部验收不等于完整E或V1生产交付。

### 最终验收与交付边界

- 新增84项/6 suites：PlanDraft26、PlanCheckedSave11、PlanEditorViewModel25、PlanEditorCompose11、PlanWorkspaceCompose5、PlanEditorRoom6。最终执行 `:app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`，退出0，BUILD SUCCESSFUL 2m52s，101 tasks：18 executed/83 up-to-date；实际XML1275项/106 suites、0 failure/error/skipped，JVM强制重跑，最后提示回归GREEN。
- Debug重新编译/打包成功；androidTest经过构建检查（KAPT执行，编译和包为up-to-date），没有connectedAndroidTest或个人设备操作。lint三类源重新分析，报告内容未变因此lintReportDebug为up-to-date；实际报告0 error/55既有warning，本批新增/修改计划UI及编辑源无lint issue，不把缓存检查虚称重新生成的报告。
- 独立源审查/定向复审：核心3 Important、整合3 Important/2 Minor、补充1 Minor及初次边界测试补强均已逐项验证、修正、复审关闭；最终无遗留Critical/Important/Minor。审查者未运行测试，以上命令/XML由主代理独立核对。最终日志仍出现Robolectric SQLite CloseGuard警告，记录在TodoReminderAdmissionTest/LauncherBrandingTest日志不代表确定泄漏来源，不宣称已消除。
- README、CHANGELOG未发布区、实施计划与本验收同步。交付仅提交/推送 `feat/v1-unified-plans`；不合并main、改origin、变更0.1.1/code2/schema5、发布/覆盖APK或提交密钥/个人数据。下一批E3日历/全库统计；最终E生产统一激活与F设备/版本升级交付尚未完成。

## E1 · 第十四批：统一列表准备（2026-10-13，局部验收通过）

用户确认范围后在 `feat/v1-unified-plans` 实施；既有完整 V1 设计不变。本批不接生产 Hilt/Room/nav，不开放新建/编辑/日历/统计空入口，0.1.1/code2/schema5、main、发布 APK 及个人数据不变。

实现范围：真实统一仓库 Flow、列表分类（全部/待办/日程）、默认未完成和三状态、标题/备注搜索、优先级/标签身份筛选、今天/明天/周一至周日/全部日期、创建时间降序/优先级降序/截止发生升序并 ID 升序兜底。NONE 分类日期不适用且保持筛选；无日期在日期排序末尾，重复按共享规则和例外寻当前/下一发生，结束系列不虚构未来。完成/撤销保留筛选，系列整体完成；维护拒绝不重放，旧世代/失效行回调拒绝，读取失败禁写而非伪造空成功。提交回执先于提醒，失败/取消只重试提醒；原生手账主题、滚动筛选、长文本与可访问文字复用既有 UI。

### 已执行证据（中间结果，不作为最终验收）

- 本批新基线：JDK25、Gradle9.4.1、既有网络 init，`:app:testDebugUnitTest --rerun --console=plain`，退出0，BUILD SUCCESSFUL 2m36s；40 tasks：1 executed/39 up-to-date。XML 1139项/96 suites，0 failure/error/skipped。
- 首轮缺契约 RED：测试源反射检查缺失组件，3项全部 assertion failure，BUILD FAILED 1m44s；40 tasks：5 executed/35 up-to-date。建立最小类型/临时空行为后改为直接 typed 行为测试；反射检查随后移除，不保留生产桩或源码文本断言。
- 行为首轮编译失败1m31s：测试夹具外部访问 `lateinit` 初始化状态，未计行为 RED。把测试清理移入夹具后重跑，43项执行/42 failure（查询20、UI8、VM15；一个拒绝旧行断言在临时 no-op 下通过），BUILD FAILED 2m29s；40 tasks：6 executed/34 up-to-date。缺行为的 VM 等待屏障用例发生测试超时，不将超时当完成正确性证明；实质查询/状态/UI 断言确实失败。
- 首轮实现组合46项/4 suites（增加3真实Room），41通过/5 Compose失败，BUILD FAILED 2m22s；40 tasks：12 executed/28 up-to-date。XML栈均为未组成的离屏 LazyColumn 项调用 `performScrollTo`；对比旧页面测试，改为父列表 `performScrollToNode` 先定位，不改变生产 UI 来迎合测试。
- 新增3项 VM 回归后18项执行/2 failure，BUILD FAILED 56s；40 tasks：5 executed/35 up-to-date。真实复现世代观察失败后重试不重订阅、提交后发布世代核对失败误称未保存；展示时区刷新通过。修复重试外层观察边界和跨返回/发布边界保留的提交回执，组合复验中。
- 修复后49项组合48通过/1失败，BUILD FAILED 2m13s；40 tasks：11 executed/29 up-to-date。剩余失败是维护提示在滚动到下方记录后已离开懒列表组成区域，先检查提示再滚动验证禁用；不修改真实维护行为。随后UI8项全部通过，另1项加强的时区状态断言实质RED（共9项/1 failure，2m12s；40 tasks：11 executed/29 up-to-date）。明确定位为查询结果相同可能不触发重组且展示时区未被状态发布；新增状态字段，由许可内有限查询发布展示时区、页面读取同一状态值。再跑完整E1组合中。

### 验收边界

本批只有合成数据的 JVM/Robolectric/Room/Compose 验证。真实设备、生产导航/Hilt/备份提醒统一切换、旋转/进程重建、系统通知草稿、Release、覆盖升级及大库性能仍留后续 E/F；编译 androidTest 不等于设备测试。既有 AGP/KAPT/旧测试弃用警告和归属待定位的 SQLite 资源警告不隐藏，待最终日志检查。

### 定向 GREEN

最终定向命令为 `:app:testDebugUnitTest --tests '*PlanListQueryTest' --tests '*PlanViewModelTest' --tests '*PlanScreenTest' --tests '*PlanListRoomTest' --rerun --console=plain`。实际XML49项/4 suites（查询20、VM18、UI8、完整Room3）、0 failure/error/skipped；BUILD SUCCESSFUL 1m3s，40 tasks：8 executed/32 up-to-date。临时空行为全部替换，缺契约反射测试已移除。保持行为不变将观察重试拆为私有 `observeList` 后，执行全量 JVM/Debug/androidTest/lint 复验中；独立审查与最终交付结果未确认前不宣布本批完成。

首轮全量复验（审查修复前）：实际XML1188项/100 suites、0 failure/error/skipped；`:app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain` BUILD SUCCESSFUL 4m1s，101 tasks：27 executed/74 up-to-date。Debug重新打包，androidTest重新编译/构建检查、lint重新分析报告0 error/55既有warning，新列表源无issue。仍观察归属待定位的Robolectric SQLite CloseGuard资源未关闭警告，记录于UnifiedPlanCalibration/HabitScreenRegression等日志不等于已确定这些类是泄漏源。此数字是修复前中间结果，不是最终交付验收。

独立源审查：1 Important（Compose delegated state使旧回调调用时取得新世代）、2 Minor（错误空态测试文案不匹配、真实Room装置未覆盖世代替换/系列往返）。逐项核对后补强：真实PlanScreen适配层保留旧checkbox语义action，停住重组，维护替换同ID，再调用旧action。首轮1项5秒等待超时（BUILD FAILED 1m27s）尚未进入断言，不计实质RED；沿用既有Compose测试显式pump idle后重跑，1项实际 assertion failure（57s，40 tasks：4 executed/36 up-to-date），新记录确实被旧action完成。页面在组合时捕获不可变renderedGeneration，三个数据/筛选/提醒回调均使用原令牌。错误空态断言改为实际文案并定位列表尾部；Room观察真实metadata失效且事务推进世代，补同ID替换与在途结果隔离、系列完成/撤销保留例外及锚点两项；修复组合复验中。

修复定向复验：`:app:testDebugUnitTest --tests '*PlanScreenTest' --tests '*PlanListRoomTest' --rerun --console=plain`，XML14项/2 suites（UI9、Room5）、0 failure/error/skipped，BUILD SUCCESSFUL 2m37s，40 tasks：10 executed/30 up-to-date。补强测试现已运行通过；同ID维护场景仅更换合成内存库，未接触个人库。独立定向复审及最终全量复验中。

### 最终验收与交付边界

- 本批新增52项/4 suites（查询20、VM18、Compose9、完整Room5）。最终全量命令同前，退出0、BUILD SUCCESSFUL 3m10s，101 tasks：13 executed/88 up-to-date；实际XML1191项/100 suites、0 failure/error/skipped，JVM强制重跑。
- Debug重新打包成功；androidTest经过构建检查（最终KAPT执行，Kotlin编译/包为up-to-date），没有执行connectedAndroidTest。lint三类源重新分析，报告内容未变故lintReportDebug up-to-date，实际XML0 error/55既有warning，新列表源无issue。不将缓存报告描述成重新生成的不同报告。
- 独立源审查及定向复审确认1 Important/2 Minor全部ADDRESSED，修复范围无新增Critical/Important；审查者未运行测试，运行证据由主代理独立读取。不是整个E/V1的生产验收。SQLite CloseGuard资源关闭警告仍在全量日志出现，归属未定位；日志记录测试类不构成归属证明，不宣称已消除。
- README、CHANGELOG、实施计划及本验收文档同步。本批只提交/推送现有功能分支，不合并main、改origin、升级版本/schema、发布APK或操作个人数据。下一批E2为详情/编辑与同身份日期转换；剩余E页面及生产接线、F版本和设备交付仍待完成。

## 历史批次记录

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

- 截至2026-10-13，A、B1–B3、C1–C2c2及D1–D4底座局部验收通过；完整V1未完成：统一页面/导航/设置、生产v6原子接线、真实文件/设备与覆盖安装和版本交付。目标库快照/原子恢复、备份格式/旧v1转换/文件维护/发生/提醒及完整隔离迁移结果另列，不等于生产激活。
- 本批构件尚未接入生产业务，不能在已发布软件中使用新的转换 / 完成规则；不以单元测试代替设备迁移验收。
- 重复展开/例外/冲突等领域规则已验证，独立统计页、今天真实日程和UI移除日期确认等页面接线仍在E实现；没有占位入口。
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

## C2b · 第七批：隔离 Android 排程与投递（局部验收通过）

2026-10-12（Asia/Shanghai），基线 HEAD `2bd38dc`、工作区干净，继续用户选择的当前功能分支。C2a 已推送，main 和已发布 APK 不切换。Buddy 本地 state 脚本仍不存在；沿用已批准架构和仓库执行计划，紧耦合流程顺序 TDD，独立计划/代码审查。

基线复验：JDK 25 / Gradle 9.4.1，JVM --rerun + Debug/lint，BUILD SUCCESSFUL（3 分 24 秒，66 tasks：2 executed / 64 up-to-date），退出 0。实际 XML 887 项 / 81 suites，0 failure / error / skipped；Debug/lint 为缓存检查，不当本批实现验收。

本批新增 Room 技术库存，保存每个 planId 的当前完整 token 与状态，不保留标题或个人内容、不作为授权或备份业务数据。现有 12 实体 B3 迁移结果保持历史范围；新增技术表先在隔离完整目标装置验证，C2c 必须扩展同一 v6 SQL/迁移/fixture，E 才激活生产与 Receiver 依赖。Room 与系统提醒不是原子事务，不承诺跨任意崩溃点 exactly-once；成功标记后持久去重，notify 与标记之间故障仅替换同 tag 卡片，可能再次提示，不能假称完全消除该窗口。

计划独立审查的 2 Important（库存推进后的旧通知撤销、notify/mark/advance 故障恢复）与 2 Minor（C1 严格未来查询和到达资格的边界、C2c 技术表迁移责任）已明确精确契约，定向复审 spec / 可执行性均 PASS，无新增问题。Receiver 故障由独立排队回调申请新校准，不嵌套准入或保留旧 payload。

首组实质 RED：8 项 Android 协议/领域资格用例，7 assertion failure / 0 error / skipped，编译成功（5 分，40 tasks：11 executed / 29 up-to-date）。一项拒绝测试因临时默认 false 通过，不能当实现证明；随后替换协议/资格声明并新增真实 Room/系统服务集成测试，继续独立 RED/GREEN。

协议实现后的第二轮：30 项 / 2 suites，21 failure（3 分 2 秒，40 tasks：13 executed / 27 up-to-date），协议 8 项全部通过；集成 22 项中多数因缺少排程/API 实现、空闹钟或回执错误失败，一项坏库存拒绝路径因临时默认抛错通过，不能当实现证明。其中三项文件重开测试发生 SQLiteCantOpenDatabaseException，不算业务 RED；按 systematic-debugging 查看 native SQLite 打开堆栈，并比较已有真实文件迁移装置。父目录初始化及预建文件的两次假设均未解决失败，不将猜测当根因；继续补路径长度/存在/可写性诊断。新增 CAS 未命中、notify/advance 故障和 Receiver 实际流程覆盖后的 40 项 / 2 suites 仍 31 failure，协议 8 项通过，核心 API 尚为临时声明；本阶段不能提交或声称局部验收。

文件装置诊断实际输出：父目录存在/可写，数据库绝对路径长度 259；原生 SQLite 在打开或设置 WAL 时失败。改用测试专用短名称（保留唯一性与严格限定清理范围）、移除无效的预建文件尝试后，三个重开测试到达业务断言且通过；不修改正式数据库配置，不把宿主测试路径修正算作应用修复。中断前的诊断进程没有可恢复 session；恢复后重跑单项诊断，再复验实现。

核心实现第一轮 GREEN：40 项 / 2 suites（协议/资格 8、真实 Room/系统集成 32），0 failure / error / skipped；BUILD SUCCESSFUL（2 分 56 秒，40 tasks：11 executed / 29 up-to-date）。SQL 技术库存、当前事实排程/资格、通知/决定/推进故障、数据库重开、hash 碰撞和 Receiver 真实异步路径通过；随后再补实际取消、维护排空/并发完成、最终设置重读和 API31/33 权限覆盖，不以此定向结果当最终全量验收。

补强后的 50 项 / 2 suites（协议 8、集成 42），0 failure / error / skipped；BUILD SUCCESSFUL（4 分 28 秒，40 tasks：4 executed / 36 up-to-date）。包含实际 Job 取消、Receiver 8 秒超时 finish、并发真实完成与维护排空、最终 Room 设置重读、后续发生只保留最新卡片、API31 非精确兜底、API33 通知拒绝、禁用频道、其它频道/坏元数据不误清和未接线 Application 安全拒绝。此前一次测试编译使用实例调用 static 的 ShadowAlarmManager API，按本机 javap 核对后修正为类调用；这一编译失败不当业务 RED。

代码独立审查提出 2 Important：挂起读取/库存写入耗时可能把未来候选变成过去闹钟，以及 notify 正常返回但可观察权限已关闭时不应消费。已核对公开 AlarmManager/NotificationManager API；通知提交没有用户收到确认，不能用 activeNotifications 的即时快照伪造回执。先新增实质 RED：46 项真实集成 / 1 suite，3 assertion failure / 0 error / skipped（2 分 46 秒，40 tasks：5 executed / 35 up-to-date）；分别为预计 Failed 实际 Applied、预计次日未来 trigger 实际当日过去 trigger、预计 NOT_SENT 实际 DELIVERED。其它 43 项通过，延迟的既有未来闹钟仍可在发生未过期时投递。

修复限定：例外读取结束后再取 Clock，写库存之后/系统安装之前再次要求严格未来；越过触发点则显式提醒失败，保留可清理身份及 advance 的刚发卡片，重试复算当前候选。notify 前/后检查系统权限及频道，提交后可观察阻止时撤本次完整 tag、返回 NOT_SENT，不 CAS/推进。增加不允许主线程查询的真实 Room 装置验证库存读 IO。DELIVERED/delivered 仅表示持久决定的提交尝试，不保证视觉送达/已读；系统异步策略/配额/最后检查后撤权不可完全确认，此平台限制和跨系统崩溃窗口分别记录，业务数据与设置不丢失。

修复定向 GREEN：55 项 / 2 suites（协议 8、真实集成 47），0 failure / error / skipped；BUILD SUCCESSFUL（2 分 8 秒，40 tasks：12 executed / 28 up-to-date）。第一次全量候选检查中，JVM 942 项 / 83 suites 实际强制重跑均无失败/错误/跳过，Debug 重新打包；但 lint 的测试 Receiver 缺少显式导出标志导致整条命令 FAILED（2 分 57 秒，66 tasks：12 executed / 54 up-to-date），实际 1 error / 57 warning，其中两条新增 UseKtx 提示。使用 ContextCompat.RECEIVER_NOT_EXPORTED 和等价 toUri 扩展修正，不增加 baseline 或抑制安全检查。

独立复审已关闭两项 Important，spec PASS / quality PASS；新增一项 Minor：已有静默阻止装置在真实 shadow post 前返回，尚不能证明 post 后的精确撤回。新增实际 post 后再暴露阻止、同时保留另一计划真实卡片的回归；临时移除本次撤回调用做单项 mutation RED，再恢复调用重新验证，结果另列。不提交临时 mutation。

撤回覆盖 mutation RED：单项用例 / 1 suite，1 failure / 0 error / skipped（2 分 15 秒，40 tasks：11 executed / 29 up-to-date）。failure 原因明确为 activeNotifications 包含多于一张卡片，触发 single() 的 IllegalArgumentException；前面的 NOT_SENT 与 pending 状态断言已通过，不是数据库/协议装置故障。随后立即恢复本次完整 tag / ID=0 的 cancel 调用并全量重跑；不存在待提交的临时 mutation。

Receiver 装置修正后的首次全量命令失败（2 分 3 秒，64 tasks：16 executed / 48 up-to-date）：实际 XML 943 项 / 83 suites，9 failure / 0 error / skipped。九项均在 ContextCompat 注册非导出 Receiver 时因模拟安装没有授予私有签名权限而失败，未进入业务路径。核对实际合并 Manifest 已声明且使用 `com.example.lifemanager.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；仅在 Robolectric helper 中授予对应包名的这项权限，不更改正式 Manifest、不放宽 RECEIVER_NOT_EXPORTED，不额外授予通知权限。随后启动的检查被用户中断，session 无法恢复，XML 仍是上述失败结果，不能当通过；2026-10-12 恢复后重新执行完整命令及本次修正的独立只读复审。

恢复后的定向独立复审已通过，无新增 Critical / Important / Minor：确认 post 后精确撤回只作用于本次完整 tag / ID=0，另一计划真实卡片保留、pending 不消费/不推进；私有签名权限授权仅修正模拟安装装置，实际非导出广播/异步 finish 路径和通知权限拒绝测试保留；toUri 的编译结果仍调用等价 Uri.parse。此复核关闭原 Minor，仅是静态检查，不替代下列全量命令与实际 XML。

最终全量复验（2026-10-12 16:39–16:45，Asia/Shanghai）：JDK 25 / Gradle 9.4.1，以下命令 BUILD SUCCESSFUL（6 分 10 秒，66 tasks：7 executed / 59 up-to-date），进程退出 0。

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain
```

- 实际 XML：943 项 JVM / 83 suites，0 failure / error / skipped；本批新增 56 项，协议/资格 8、真实 Room/Android 系统服务 Robolectric 集成 48，全部通过。前述 9 项 Receiver 装置失败已消除，post 后精确撤回回归也通过。
- JVM 强制重跑，测试 Kotlin 编译、转换及 lint 测试源分析实际执行；正式源编译和 Debug 为缓存构建检查，不能描述为本轮全部重新编译/打包。最终 lint XML 0 error / 55 warning，新提醒文件无 lint issue，不增加 baseline 或抑制；既有 AGP/Kotlin/kapt 与测试 shadow API 弃用提示仍保留。
- 独立计划 2 Important / 2 Minor、代码 2 Important / 1 Minor 均已复审关闭；恢复后的定向只读审查无新增问题。审核不替代测试，不声称实际设备已验收。
- 新增技术库存使用全部 13 实体隔离 Room 装置；三项短名称文件重开测试为真实 SQLite/Room 持久化，不是完整历史升级链。C2c 必须扩展统一 v6 SQL、新建、schema、完整迁移/设备装置与回滚验收，不能复用 B3 的 12 实体历史数字。
- 本批未运行设备、个人手机覆盖安装、UI、Release 或性能验收；Room/通知非原子窗口和无法确认用户收到的限制仍存在。正式生产仍为 0.1.1 / code 2 / schema v5，不修改 Manifest/Hilt/Gradle/生产数据库或旧业务入口，不新增空页面、不使用 destructive migration、不提交密钥或个人数据。
- README、CHANGELOG、实施计划及本记录同步；交付仅当前 `feat/v1-unified-plans`，不合并 main / 创建 PR / 发布 APK。整个 C2、D–F 和 V1 未完成；下一批 C2c 旧身份清理、统一校准及提醒技术表完整迁移。

## C2c1 · 第八批：提醒技术表完整迁移（局部验收通过）

2026-10-12（Asia/Shanghai），基线 HEAD `d1effb3`、工作区干净，继续用户指定功能分支。C2c 分为本批目标库/完整迁移契约和后续 C2c2 校准/旧身份清理；未减少已批准需求。Buddy 本地状态脚本仍缺失，紧耦合 SQL/Room 装置主代理顺序 TDD、只读独立计划/代码审查。

基线复验：JDK25 / Gradle9.4.1，JVM --rerun + Debug/lint，BUILD SUCCESSFUL（2 分 29 秒，66 tasks：2 executed / 64 up-to-date），退出0。实际 XML 943 项 / 83 suites，0 failure / error / skipped；Debug/lint 为缓存检查，不作为本批实现验收。当前 adb 无连接设备；设备验证仅使用已有项目专用 AVD，不操作个人手机。

首组实质 RED：18 项 / 1 suite，3 assertion failure / 0 error / skipped（3 分 10 秒，40 tasks：5 executed / 35 up-to-date）。两个新增用例在真实历史升级/Room 新建后查 sqlite_master，预期技术表存在而实际空；建表故障用例未到达拦截点且升级成功，触发“bad source must be rejected”断言。旧 15 项通过，不把编译或宿主装置故障当业务 RED。计划独立审查无 Critical/Important，两项 Minor 已明确任务1–2共同 GREEN 检查点、C2c2只消费已验证迁移而不重复负责。

定向 GREEN：20 项 / 1 suite，0 failure / error / skipped；BUILD SUCCESSFUL（4 分 41 秒，77 tasks：20 executed / 57 up-to-date），同一命令还实际重新构建 androidTest。新增五项 JVM 中三项是前述实质 RED/GREEN，两项是迁移后真实库存持久化/Long/CAS/业务删除不级联及晚期库存 schema 故障回滚的覆盖补强，不虚称已存在的 Room 机制也经历新功能 RED。KAPT 导出完整 13 实体 fixture 6.json，identityHash `f664a1eb646c605ac7e47de42a6fa4c2`；生产 2–5 快照不变。新建仍由 Room 实体生成，INITIALIZE 仅初始化既有设置/世代，不手写第二份技术 DDL。

复审已关闭计划两项 Minor；本次代码/任务 spec 与 quality 均通过，Critical/Important/Minor 均0。只读复核确认五列/主键/无外键、同一迁移、JVM/设备装置、真实 store 和早/晚回滚的故障点/原库比对；不替代最终运行结果。初次专用模拟器进程退出、原计划审查代理不可恢复，日志未提供退出原因；重新启动同一 AVD（不 wipe/清库）并由新只读代理完成复审，不借用旧运行结果。

最终全量复验（2026-10-12）：JDK25 / Gradle9.4.1，以下命令 BUILD SUCCESSFUL（2 分 53 秒，101 tasks：15 executed / 86 up-to-date），退出0。

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

- 实际 XML：948 项 JVM / 83 suites，0 failure / error / skipped；迁移 suite 20项，其中新增5项。JVM强制重跑，Debug/androidTest本轮重新打包，lint三类源重新分析和报告执行，0 error / 55既有warning，新增迁移/技术表路径无lint issue。正式/测试Kotlin编译为前次GREEN后缓存检查，不描述为本轮全部重新编译。
- 设备身份再次核验 `emulator-5580` / 项目专用 `Rizhiya_Branding` / API35、boot completed=1；仅用 `adb install -r` 覆盖安装本轮两个测试APK，不卸载/清库。指定类原始 AndroidJUnitRunner 输出 `OK (14 tests)`、6.229秒，14个结束status均0；这是本轮实际重跑（原10项+新增4项），不借用B3十项历史数字，未重跑旧57项用例。

```powershell
.\.tools\android-sdk\platform-tools\adb.exe -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
.\.tools\android-sdk\platform-tools\adb.exe -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
.\.tools\android-sdk\platform-tools\adb.exe -s emulator-5580 shell am instrument -w -r -e class com.example.lifemanager.data.plan.PlanMigrationDeviceTest com.example.lifemanager.test/androidx.test.runner.AndroidJUnitRunner
```

- 四项任务局部验收通过，计划2 Minor/代码意见已独立关闭；目标13实体schema、早晚故障完整回滚与库存持久化由真实Room/SQLite证明，不把生成JSON当验收本身。测试仅操作唯一命名合成数据库，并限定清理自身文件；生产数据库仍v5、旧提醒/Manifest/Hilt/UI/备份/版本和已发布APK不变，不使用destructive migration，不提交密钥/个人数据。
- 同步 README/CHANGELOG/schema README/实施计划/本记录后，仅提交推送当前功能分支，不合并main/创建PR/发布APK。限制：v1缺少原始schema，仍从未变v2三实体合成重建；无真机、生产用户库覆盖升级、大库性能、UI/Release验收。下一批C2c2仍需旧身份清理/统一校准，D–F与整个V1未完成。

## C2c2 · 第九批：隔离统一校准与旧身份清理（局部验收通过）

2026-10-12（Asia/Shanghai），基线HEAD `2cee697`、工作区干净，沿用用户指定的 `feat/v1-unified-plans`。本批按已批准架构开发未接线构件，不激活生产v6或修改旧Worker/Boot/Receiver路径。Buddy状态脚本仍缺失，使用顺序TDD及独立只读计划/代码审查。

基线复验：JDK25 / Gradle9.4.1，JVM --rerun + Debug/lint，BUILD SUCCESSFUL（2分32秒，66 tasks：2 executed / 64 up-to-date），退出0；实际XML 948项 / 83 suites，0 failure / error / skipped。Debug/lint为缓存检查，不作为本批实现验收。先新增真实Room开关/来源读取与系统旧身份清理测试；临时API声明用于编译观察RED，不得带占位实现提交。

第一轮未进入RED：测试fixture误用SubscriptionRepositoryImpl通用构造，实际仓库仅接受LifeManagerDatabase；compileDebugUnitTestKotlin失败（1分48秒，35 tasks：9 executed /26 up-to-date）。移除首组尚未使用的订阅getter，后续按已读取的原@Inject构造显式保留生产兼容，并抽取Room+Dao通用依赖以测试同一实现，不把此编译失败当业务RED。计划独立审查2 Important/1 Minor：权威计划/库存读源须在同一Plan锁内；维护须另捕获A卡片及无技术行卡片的完整通知token、不可bulk按ID误清新世代；新清理器需严格枚举旧cache并分别尝试两种旧PI，均已细化并补对应测试要求。

首组实质RED：12项 / 1 suite，12 assertion failure /0 error/skipped（1分24秒，40 tasks：7 executed/33 up-to-date）。实际错误为Room开关/来源未读取、旧闹钟/三张旧通知未移除、坏配置/缓存和平台失败/取消未传递；完整URI/无data及碰撞闹钟由真实系统shadow持有，不是装置故障。计划定向复审关闭2 Important/1 Minor，spec/可执行性PASS，无新增问题；随后替换首组临时声明，继续GREEN与下游集成。

首组GREEN：12项 /1 suite，0 failure/error/skipped，BUILD SUCCESSFUL（1分48秒，40 tasks：11 executed/29 up-to-date）。严格IO读取和旧平台清理已实现，原v5调度路径不变；随后扩展校准/维护集成测试，暂不把定向结果当本批整体完成。订阅仓库仅抽取既有Room+Dao依赖、保留原@Inject构造和全部业务方法，实际v6校准行为由后续真实Room/订阅测试验证。

第二组实质RED：17项 /1 suite，15 assertion failure /0 error/skipped（1分53秒，40 tasks：13 executed/27 up-to-date）。失败是延迟pending被朴素reconcile忘记、已发A卡片误撤、孤儿/旧世代/维护捕获未清理、缺订阅排程和故障未报告；两项未来重装/重置仅未来行为由现有C2b实现已经通过，是覆盖而非新增RED。随后实现同一C1/C2b校准、锁内读源、精确捕获清理和显式v6订阅reader；后续Worker及并发/取消/文件重开补强尚待验证。

第二组首轮GREEN命令未执行测试：订阅校准表达式尾部的可空throw分支推导为Nothing?而非接口Unit，compileDebugKotlin失败（51秒，25 tasks：3 executed/22 up-to-date）；明确Unit返回后重跑，不计为新的业务RED或测试通过。

第二组及既有回归GREEN：实际XML 95项 /5 suites（本批29、C2b56、被同一glob匹配的C2a身份10），0 failure/error/skipped，BUILD SUCCESSFUL（2分11秒，40 tasks：11 executed/29 up-to-date）。已按真实suite清单纠正最初只按C2b名字估算的85/4，不把遗漏的旧身份suite算作新增。这不是最终全量验收；继续补并发创建、提交后文件重开新鲜校准及已决定单次在系统时间回拨后的防重排边界。

补强首轮RED：20项 /1 suite，1 assertion failure/0 error/skipped（56秒，40 tasks：5 executed/35 up-to-date），实质失败是单次已提交决定、用户划掉卡片、文件重开并时钟回拨后重新创建闹钟；并发创建模块锁及提交后保留设备库存的新鲜校准两项通过。代码独立审查提出3 Important：这一回拨重排、订阅adapter的旧全频道stale清理越界、以及只有pending无法区分安装失败/广播已消费。先继续补真实系统失败用例，再更新技术状态与限定通知命名空间，不借审查当运行证明。

后续实际XML：23项 /1 suite，4 assertion failure/0 error/skipped，覆盖安装失败、实际NOT_SENT后修复及订阅清理归属。追加三项审查边界时首轮编译因测试字段名误写 repeatUntil 失败（21秒，35 tasks：3 executed/32 up-to-date），改为已有 repeatEndDate 后实质RED 26项 /1 suite、7 assertion failure/0 error/skipped（58秒，40 tasks：4 executed/36 up-to-date）。新增三项分别证明重装失败也需失效安装状态、匹配的早到广播也已消耗系统闹钟、有限重复的未来B在耗尽后仍需保存回拨边界。

技术语义细化但不改变五列DDL：delivered INTEGER 的0代表未确认安装/已到达待修复，1代表已决定提交，2代表安装请求返回且匹配CAS成功、等待广播；旧0/1仍能严格读取，不把2当OS交付保证。每次重装/取消已接受身份前先持久失效2，安装成功才由全token及预期状态CAS到2；匹配到达在当前事实读取前CAS到0，早到/NOT_SENT请求未来校准、旧世代/不匹配/重复不改另一条身份。决定及最新pending的时间边界在同事实/世代内保存；耗尽不丢弃，业务编辑仍可重新授权，不创建全历史投递账本。新订阅清理仅识别订阅自己的canonical tag/channel/module/Long世代，原生产helper默认不变。

上述修复后定向104项 /5 suites 实际运行（2分49秒，40 tasks：12 executed/28 up-to-date）：校准26、旧清理12、协议8、身份10全通过，C2b 48项中2断言失败。超时仍未请求独立修复是新增RED；单次原断言库存0与新保留决定标记冲突，需按已批准防回拨语义更新，不隐藏失败。Worker补齐中；应用重启后原Worker测试进程/审查代理不可恢复、未取得该命令终态及XML，因此不写成通过；2026-10-13重新运行并由新只读复审继续同一三项问题闭环。

2026-10-13 Worker首个可恢复运行因fixture漏用WorkManager `close`扩展而编译失败（1分28秒，35 tasks：1 executed/34 up-to-date），修正装置后9项 /1 suite 实质RED、6 failure/0 XML error/skipped（1分47秒，40 tasks：4 executed/36 up-to-date）。3项为断言失败（未执行当前Room成功路径/取消传播），3项因未创建队列而没有预期WorkInfo（NoSuchElement），并非平台fixture崩溃；缺依赖retry/坏输入拒绝等3项由临时失败关闭行为通过。随后实现实际Worker，临时空实现不得提交。

新只读核心复审确认回拨和安装/到达状态两项已闭合；组合频道sweep已去除，但发现cancelAll仍按tag无条件删除不满足所有权的订阅卡片。补真实canonical tag/id0/Int世代的重置、关闭、维护清理用例；同时补计划同canonical tag/合法元数据但实际ID9的bulk清理一致性用例。继续先验证RED再限定新版取消路径，旧生产默认不变。新增Runner opt-in取消hook测试验证许可退出后请求未来重置、取消传播及finish，另补全token/预期状态CAS和安装后CAS故障精确取消的实际Room用例。

上述定向命令实际119项 /7 suites、2 assertion failure/0 error/skipped（2分55秒，40 tasks：12 executed/28 up-to-date），仅两项新增所有权用例失败；9项Worker（包含实际Processor运行和第二轮新鲜Room事实）、4项广播修复、全部旧C2b/协议/身份及旧清理通过。不把尚未GREEN的校准suite写成本批通过。其后新版订阅取消共享严格owned predicate，维护用alarm-only cancel再owned通知清理、计划bulk清理共享实际id0判定；旧生产订阅cancelAll默认不变，继续重跑。

定向最终GREEN（2026-10-13）：119项 /7 suites，0 failure/error/skipped，BUILD SUCCESSFUL（2分25秒，40 tasks：11 executed/29 up-to-date），退出0。实际suite：UnifiedLegacyCleanupTest 12、UnifiedPlanCalibrationTest 28、UnifiedWorkerRoutingTest 9、UnifiedBroadcastRepairTest 4、旧PlanReminderIntegrationTest 48/Protocol 8/Identity 10；本批新增53项，旧测试的状态预期按新协议更新，不把重命名算作新增。仍需全量JVM/构建/lint、独立复审及最终文档/推送核对。

### 实现范围与后续接线

- 严格只读总开关/双来源ID和技术库存，权威计划/来源/库存/通知快照及孤儿决策共享Plan锁，原世代全局许可贯穿计划及后续订阅锁；维护排空、并发创建和取消均由实际Room装置验证。
- 旧闹钟清理合并来源、完整signed Long技术缓存和发生hint，逐个取消原component/hash的完整URI及无data形式；失败尝试另一形式、保留清理线索并报告，取消立即传播。旧通知只按其频道/无tag/合法旧module或历史无module，hash碰撞不撤订阅/新计划闹钟。
- 当前计划排程复用C1/C2b。普通校准只保留状态2且资格仍匹配的延迟pending，重置只安装未来；安装/到达/决定状态分开，时钟回拨与有限系列耗尽保留最新边界，真正业务编辑仍可授权新的时间。计划通知bulk/库存/捕获均共用完整canonical token、四Long及实际id0归属判断。
- 订阅复用原仓库、偏移、重复、金额、投递去重算法；原@Inject LifeManagerDatabase构造保留，仅增加RoomDatabase+Dao构造及显式目标库状态reader。新版stale/单项取消/重置/关闭/维护清理全程限定自己的合法通知，旧生产默认行为不变。
- 维护DTO默认空追加闹钟和通知tokens；实际A通知/B闹钟、无技术行卡片及同ID新世代不误撤通过。D须在提交前同时捕获两种库存、事务内保留设备技术行，提交失败不得平台清理、提交后副作用失败可由新鲜校准恢复。本批仅实现并测试adapter，不更换生产BackupMaintenanceWorkflow。
- 独立新Worker实际读取同一当前Room，缺Application绑定/维护忙/普通故障retry，CancellationException传播；Java两参反射构造可用，输入仅严格Boolean reset_system或空，不接受旧id/title/token。即时独立唯一队列APPEND_OR_REPLACE，实际RUNNING期间的新请求排队并执行下一轮；终态不会毒化新请求，周期独立一天KEEP。新任务不自动调度，E须同时提供依赖、切换启动/系统事件策略及取消旧唯一队列。
- Plan Receiver启用取消/8秒超时的opt-in future-repair enqueue hook，默认旧Runner调用不变；许可退出后修复且finish/取消保留，不同步重进许可、不重放旧payload。E的Plan失败/忙/取消回调必须绑定resetSystem=true，启动/周期ordinary=false，boot/时间变更/重置true。

### 验收边界

本批不激活生产v6、Hilt、Manifest、BootReceiver或旧Worker/Receiver替换，不创建新页面/假数据，不修改版本/已发布APK/main。没有本批设备执行、真机、用户库覆盖升级、UI/Release/性能验收；androidTest包构建不等于运行设备测试，不能沿用第八批14项设备数字。平台没有未知旧闹钟枚举；全部来源/缓存/技术库存丢失时无法发现未知身份，E必须退役旧广播。安装状态2不是OS交付回执；系统调用与Room不跨系统原子，硬杀进程/入队失败等边界不保证交付或exactly-once，不建立全历史账本。即时请求会串行累积，Worker系统运行时间/权限和调度延迟仍受Android限制。D备份/原子维护、E页面及生产整体激活、F版本发布仍待完成。

### 最终验证 · 2026-10-13

JDK25 / Gradle9.4.1，以下完整命令退出0、BUILD SUCCESSFUL（3分14秒，101 tasks：22 executed/79 up-to-date）：

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

- 实际XML全部本轮更新（Asia/Shanghai 00:40:32）：1001项 /87 suites，0 failure/error/skipped，比基线新增53项 /4 suites。包含旧v5业务、备份、迁移、ViewModel及旧广播/后台任务回归；不是只跑119项定向集。
- Debug及androidTest重新打包，设备测试源重新编译；正式/test JVM Kotlin编译为前次定向GREEN后的缓存检查，不把本轮所有任务都描述为重编译。
- lint三类源重新分析与报告执行（00:42:26），实际报告0 error /55既有warning；新增/修改提醒目标路径无lint issue。AGP兼容选项、旧KAPT/Robolectric/test opt-in等已有编译提示保留，不等于无warning。
- 只读复审确认原3项Important、后续订阅cancelAll旁路及计划实际id所有权问题关闭，当前范围Critical/Important/Minor均0；复审显式包含新Worker默认缺绑定retry/Java两参/严格reason-only队列和Runner取消/超时hook/Receiver绑定，只认可当前隔离，不授权生产激活。计划2 Important/1 Minor早先已关闭；前代理重启丢失后的新代理按同一三项闭环，不借复审替代运行结果。
- README/CHANGELOG/schema README/实施计划/本记录同步。推送前远端功能分支仍为基线 `2cee697`，远端main和本地main均为 `b016bcbc0f146ea6d9fe257b734700689de5cb51`；仅提交推送当前 `feat/v1-unified-plans`，不创建PR/合并main/发布APK。

五项C2c2任务局部验收已完成；技术迁移、统一校准、旧身份清理等隔离提醒底座具备后续连接基础。下一批D开始v2备份/旧v1校验转换/目标库快照及维护适配，生产默认codec和BackupMaintenanceWorkflow到E才切换；V1页面/整体升级与版本交付仍未完成。

## D1 · 第十批：v2备份格式底座（局部验收通过）

2026-10-13，用户确认范围，基线HEAD `1e667d5`、当前分支干净。D1仅新增隔离raw契约/严格校验/v2codec，D2才做v1转换，后续处理Room恢复与维护文件；默认v1生产格式和Hilt/UI/schema/0.1.1保持。当前正在复验基线及细化测试，不引用上一批结果当本批验收。

本轮基线：JDK25/Gradle9.4.1，全量JVM --rerun及Debug/lint，BUILD SUCCESSFUL（3分1秒，66 tasks：2 executed/64 up-to-date）；实际1001项/87 suites、0 failure/error/skipped。构建/lint为未变源码缓存检查，基线测试编译完成后准备了新测试文件，未把它们计入基线。计划独立只读复审Critical/Important/Minor均0，数据格式→校验→codec依赖及v1隔离边界明确。

首组实质RED：13项/1 suite、12 assertion failure/0 error/skipped（2分55秒，40 tasks：12 executed/28 up-to-date）。失败来自尚未实现的坏字段、有效模式、关系、环、来源重复、设置和总行数拒绝；100k完整父链装置正常建立，不因递归或装置异常失败。历史合法值用例可由临时空检查通过，不声称它证明实现；临时声明将在GREEN前替换，不可提交占位。

实现raw契约/静态白名单/共享通用检查及v2有效模式后，第二组实际46项/3 suites、15 assertion failure/0 error/skipped（2分10秒，40 tasks：11 executed/29 up-to-date）。V2Validator13及原v1Codec17已GREEN；V2Codec16中15项因缺少编码/校验失败，是编解码实质RED。默认64MiB坏输入用例由临时拒绝通过，不单独声称限流实现；随后替换codec空声明，保留旧v1默认parser预算20，仅显式V2使用白名单27列预算。

定向GREEN：46项/3 suites（29新增、17原v1），0 failure/error/skipped，BUILD SUCCESSFUL（2分整，40 tasks：11 executed/29 up-to-date）。真实100k完整父链和环/超限、来源增行计数、raw历史/Long极值/27字段、exact UTF8字节边界、独立literal canonical SHA256黄金值、新旧codec版本隔离均通过。全量构建/lint及独立代码/文档复审进行中；无实际文件读取、Room恢复、设备执行或生产格式切换。

核心及codec/parser/格式文档只读复审无Critical/Important，2项Minor：原文保留用例原expected仅浅copy，改用独立raw行/集合装置；文档明确defaultReminderDays可为[]而不可为null，并在现有坏设置测试补null拒绝。既有正确拒绝行为的覆盖补强，不伪称另一次功能修复RED。原v1公开入口、schema/codec默认、golden不改；继续最终复验。

首次全量验收命令（补强前已编译的测试快照）退出0、BUILD SUCCESSFUL（3分21秒，101 tasks：16 executed/85 up-to-date），1030项/89 suites、0 failure/error/skipped；Debug重新打包，AndroidTest源重新编译且测试包为缓存检查，lint三类源重新分析通过。随后复审确认两项Minor已关闭，当前静态范围Critical/Important/Minor均0；补强后的同一完整命令再次强制重跑中，不把前次数字替代最后复验。

### 最终验证 · 2026-10-13

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

完整复验退出0、BUILD SUCCESSFUL（2分40秒，101 tasks：7 executed/94 up-to-date）；实际XML本轮08:44:32（Asia/Shanghai）更新，1030项/89 suites、0 failure/error/skipped。比基线新增29项/2 suites（V2Validator13、V2Codec16），包括补强后的独立原文预期及null拒绝。保留v1原黄金值与全部旧业务/备份/维护/提醒/迁移/ViewModel回归，不把17项旧codec算新增。

- JVM强制重跑，UnitTest Kotlin因补强重新编译；正式源码及Debug/androidTest为首次全量后的缓存构建检查，首次Debug已重打包、AndroidTest源编译通过，未执行设备安装或测试。
- 本次lint重新分析UnitTest，正式/AndroidTest分析和报告复用前次未变内容；实际0 error/55既有warning，新/修改备份路径无lint issue。既有AGP/KAPT/Robolectric/opt-in等编译提示保留。
- 独立计划复审均0问题；完整代码/codec/parser/格式文档复审2 Minor按真实行为补齐并关闭，最终Critical/Important/Minor均0，只认可D1隔离范围，不替代实际运行。
- README/CHANGELOG/本记录/实施计划/v2格式说明同步，保留v1历史格式与BackupSchema/BackupJsonCodec默认。无占位实现、生产Hilt/UI/schema/版本或旧表删除，不读写用户文件/真实数据库；当前0.1.1/code2/schema5及已发布APK不变。
- 推送前远端feature与本地基线均为 `1e667d5`，main仍 `b016bcbc0f146ea6d9fe257b734700689de5cb51`。仅提交推送当前feature，不创建PR/合并main/发布APK。

本批五项任务局部验收完成。限制：codec是受限内存编解码而非流式文件/数据库恢复；64MiB/100k与纯JVM父链测试不是Android大库内存/性能证明。没有实际文件选择/重读、保护备份、原子Room恢复、设备/UI/Release/性能或用户库升级验收。D2旧v1先校验原hash/字段/关系再共享转换及版本分派，后续目标库/维护文件仍待完成，E才更换默认codec和生产消费者；D1不是整个D或V1发布。

## D2 · 第十一批：旧备份转换与版本分派（局部验收通过）

2026-10-13，用户确认范围，基线HEAD `3d1c2a4`，当前功能分支干净；继续原分支，不建工作树、不改生产v1/Hilt/文件/Room/schema/版本。源原hash/字段/关联严格通过才共享转换，目标v2验证及含来源映射的行/字节上限也必须通过；来源说明及十表数量为后续UI准备，不接真实文件恢复。

采用已批准V1设计/D2五项计划，TDD顺序实现紧耦合契约；独立只读计划复审2 Minor明确直接转换的目标验证保证及源合法/目标非法/元数据与计数覆盖，复审关闭。Buddy脚本缺失、writing-plans不可用，以本计划与验收记录跟踪；不引入额外流程/Linear/PR。

真实证据：

- 基线`:app:testDebugUnitTest --rerun --console=plain`，BUILD SUCCESSFUL 1m28s，40 tasks：1 executed/39 up-to-date；实际XML 1030项/89 suites，0 failure/error/skipped。
- converter RED：先测试，经仅测试源反射adapter让缺实现产生断言（无生产stub）；15项/1 suite，15 assertion failures、0 error/skipped，BUILD FAILED 1m20s、40 tasks：5 executed/35 up-to-date。缺类是功能缺失，不把编译错误当RED。
- converter GREEN与dispatcher RED联合运行：63项/4 suites，converter15、原v1 codec17、v2 codec16均通过；dispatcher15 assertion failures，0 error/skipped，BUILD FAILED 2m2s，40 tasks：11 executed/29 up-to-date。原两格式golden不变。实现dispatcher后测试全部改用类型安全直接调用，无反射adapter提交。
- dispatcher GREEN：相同四类63项全部通过，0 failure/error/skipped，BUILD SUCCESSFUL 1m52s，40 tasks：11 executed/29 up-to-date。之后补一项现有正确行为的CUSTOM原始重复/非活动日期/Long毫秒极值覆盖（非新增功能修复，不另称实质RED），进入全量复验。

### 实现与验证范围

- 领域VersionedBackupCodec/BackupImportResult分离来源信息与业务payload，输出十表数量含空表；仅存在旧TODO时返回注入时区。数据层LegacyBackupConverter严格校验源及目标，适配原DTO后复用唯一LegacyPlanConverter/References/Relations，不使用编辑器归一化。
- 顺序新ID独立于完整signed旧ID；TODO后SCHEDULE、父关系第二遍、标签和例外按来源解析。逐值保留27列、新字段明确缺省；原六表、设置、元数据不变。全局OR/单项继承/旧全天关闭/空分钟保留；历史空标题、固定偏移、完成组合和自定义星期原文保留。
- 原v1 hash先验证，源合法而目标时间/间隔/分钟无效明确拒绝，不修复、不fallback。转换增行预检与目标canonical信封字节预检，50000条旧TODO→100000行业务表通过、50001拒绝；缩小限额精确字节/行边界及64MiB+1输入解析前拒绝。技术库存与世代不在文件内；原两格式golden及预算保持。
- 静态完整版本识别在固定v2预算内，选中codec按各自契约重新解析；v1仍20成员/行，v2为27。没有生产Hilt绑定或实际文件/Room消费者替换。缺实现RED的反射adapter全部删除，提交的测试直接调用真实组件。

首次完整命令（JDK25/Gradle9.4.1/既有网络init）：

```powershell
$env:JAVA_HOME='D:\jdk'
.\.tools\gradle-9.4.1\bin\gradle.bat -I .\.tools\phase5-test-network.gradle :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

首次退出0、BUILD SUCCESSFUL 2m30s，101 tasks：20 executed/81 up-to-date；实际XML 1061项/91 suites、0 failure/error/skipped，新增31项/2 suites（转换16、分派15）。Debug重新打包，AndroidTest源重新编译、测试包无内容变化为缓存构建检查；lint三类源重新分析及报告成功，0 error/55既有warning，新增/修改备份路径无issue。

完整复审无Critical/Important/Minor；补强超大零填充输入必须返回字节超限而不是泛JSON语法错误的断言，独立复审认可其验证解析前guard（覆盖补强，未改实现）。最终相同命令退出0、BUILD SUCCESSFUL 2m29s，101 tasks：7 executed/94 up-to-date；实际XML10:21:10（Asia/Shanghai）更新，1061项/91 suites、0 failure/error/skipped。JVM强制重跑/UnitTest重新编译；Debug/androidTest/正式源码为缓存构建检查，lint本次UnitTest重新分析、正式/AndroidTest/报告缓存未变结果，仍0 error/55既有warning。保留AGP/KAPT/Robolectric/opt-in既有提示，不虚称无warning。

- 计划2 Minor及转换器/分派/完整格式文档复审全部关闭，独立审查不代替真实执行。
- README/CHANGELOG/本记录/计划/格式v2同步；原v1历史格式、BackupSchema/BackupJsonCodec默认、生产Hilt/Manifest/UI/schema/0.1.1/code2和已发布APK不变。无密钥/个人数据、占位生产实现或假入口，不读写用户文件/真实数据库。
- 提交前远端feature等于基线 `3d1c2a450741d38ca419ac48ef19c5c9d4534b1d`，main为 `b016bcbc0f146ea6d9fe257b734700689de5cb51`。按授权仅提交推送当前feature并核对远端，不创建PR/合并main/发布APK。

D2五项局部验收完成。限制：受限内存编解码/额外解析及纯JVM合成上限测试不是Android大库性能或低内存保证；没有真实文件选择/重读、保护备份、原子Room恢复、设备/UI/Release/性能或生产用户库升级验收。下一批D3实现隔离目标库快照/原子恢复，维护文件适配及E整体生产启用、F交付仍待完成，非完整D或V1发布。

## D3 · 第十二批：目标库快照与原子恢复（局部验收通过）

2026-10-13，用户确认隔离范围，基线HEAD `c5c29bd44b2667acc7113d213e1ac443379632a7`，原工作区干净。首次推进仅细化五项计划并进行基线/平台检查，未写实现；随后因下述主键策略确认关口暂停，无D3提交或推送。

- 基线JDK25/Gradle9.4.1/网络init，`:app:testDebugUnitTest --rerun --console=plain`退出0、BUILD SUCCESSFUL 1m24s，40 tasks：1 executed/39 up-to-date；实际XML1061项/91 suites、0 failure/error/skipped。这是D2代码的本轮复验，不是D3验收，无新增测试。
- 独立计划复审提出payload-only验证重载、精确payload/完整信封字节区分的Minor，已补计划；Important为自增主键极值策略，不擅自改变已批准Long原值兼容。
- 实际完整Room目标schema中plans/tags/habits/habit_records/subscriptions/subscription_payments均AUTOINCREMENT；提交Long.MAX_VALUE后后续自动插入失败，删除记录也不会解除序列耗尽。纯内存SQLite合成探针保存MAX主键成功，后续INSERT返回OperationalError `database or disk is full`，原记录仍1条；[官方SQLite说明](https://www.sqlite.org/autoinc.html)确认算法。该探针不是Room或Android设备验收，不触及用户库。
- 按安全约束与brainstorming确认关口暂停实现，等待用户选择：恢复前拒绝危险自增MAX（含已有目标序列耗尽检查，原库/原文件不变），或原样接受并明确随后新建受限。非自增字段的Long极值和旧codec/raw格式不因此改写，不私自重编号、修改sqlite_sequence或采用destructive migration。

用户于2026-10-13选择方案1，继续开发：六张自增表MAX主键在任何删除前拒绝；目标序列耗尽拒绝恢复/清空，不重编号或重置序列。其余Long精度/原codec与golden不变，readonly救援导出不因MAX主键或世代耗尽而禁止。计划Important及payload/字节Minor已复审关闭，剩余一项状态措辞Minor修正。

- 恢复开发后的新基线命令同上：退出0、BUILD SUCCESSFUL 1m23s，40 tasks：1 executed/39 up-to-date，实际1061项/91 suites、0 failure/error/skipped。
- 首轮测试源编译失败29s（35 tasks：3 executed/32 up-to-date），原因RuntimeEnvironment无泛型参数与混合arrayOf的reified交叉类型；根据现有API与显式Any数组修正，不计为RED。
- 实质RED：真实Room装置与测试编译通过，28项/2 suites（snapshot11、mutation17）全部断言失败，0 error/skipped，明确捕获缺实现而非编译错误；BUILD FAILED 1m22s，40 tasks：6 executed/34 up-to-date。缺实现adapter只在测试源，GREEN全部替换为直接typed调用。
- 初轮GREEN：snapshot11、mutation17、原v1codec17、v2codec16、V2Validator13合计74项/5 suites，0 failure/error/skipped；BUILD SUCCESSFUL 2m12s，40 tasks：11 executed/29 up-to-date。随后增加一致快照、caller可变源所有权和设置行数预检覆盖，继续RED/GREEN验证，不以首轮GREEN宣称最终完成。
- 强化RED：31项/2 suites运行，2 assertion failures、0 error/skipped，BUILD FAILED 1m12s，40 tasks：5 executed/35 up-to-date；实际发现restore跟随调用方在事务挂起后改过的源Map、非法设置多行在拒绝前已加载字段，一致快照测试通过。改为先验证有界源、深拷贝行/字段Map和提醒Set，再验证自有快照；设置先COUNT单行预检。无文本/数值归一化。
- 修复GREEN：34项/2 suites全部通过，BUILD SUCCESSFUL 2m4s，40 tasks：10 executed/30 up-to-date；包含额外四时间模式、八mask写入、signed负主键/零序列覆盖。独立复审2 Important关闭，1 Minor补独立SQL验证clear保持不存在与零序列，新增一项覆盖（未另称功能修复RED），进入全量复验。

- 首次全量命令为D2所列四任务（JVM --rerun、Debug、androidTest构建、lint）：退出0、BUILD SUCCESSFUL 3m40s，101 tasks：22 executed/79 up-to-date；实际XML1096项/93 suites、0 failure/error/skipped，其中D3新增35项（snapshot13、mutation22）。Debug重新打包，androidTest源重新编译/包内容未变为缓存构建检查，lint三类源及报告重新分析成功（0 error/55既有warning）。独立D3复审无遗留Critical/Important/Minor。
- 首次全量观察到Robolectric异步SQLiteConnection的CloseGuard未关闭警告，记录在旧LauncherBrandingTest的system-err；日志落点不证明资源归属，D3两个测试XML未出现该警告，不宣称运行输出完全无warning，也不据此认定一定是旧代码的问题。
- 相同命令再次强制执行：退出1、BUILD FAILED 1m25s，40 tasks：1 executed/39 up-to-date；XML1096项/93 suites、1 failure、0 error/skipped。D3新增35项通过，旧ScheduleMaintenanceTest.saveDrainsPendingNotificationWithoutNestedGlobalAdmission在runTest开始前捕获UncaughtExceptionsBeforeTest。该失败中断后续构建/lint，不把首次成功当作最终验收。
- 按systematic-debugging回溯suppressed栈：后台ScheduleViewModel.saveSchedule的finally进入finishEditorOperation时Main已reset。旧ScheduleEditorRegressionTest唯一真实线程schedule-save-io的finally仅cancel作用域/close执行器/resetMain，没有等待scope完成；生产收尾使用NonCancellable+Main是正常需求，不删除该业务保护或吞掉异常。补确定性真实线程清理回归，再修正测试生命周期，后续结果单独记录。
- 清理回归RED：抽取原cancel/reset测试helper，新回归使用真实单线程执行器和暂停的测试Main，finally捕获测试dispatcher避免旧行为向后续测试泄漏异常；断言返回时scope与Main收尾已完成。单项执行退出1、BUILD FAILED 3m58s，40 tasks：5 executed/35 up-to-date，XML1项/1 suite、1 assertion failure、0 error/skipped，错误明确为“Cleanup must join the cancelled scope”。随后仅改测试helper为NonCancellable内cancelAndJoin，结束后才resetMain/关闭执行器；真实线程与原标准dispatcher helper均复用，未改生产ScheduleViewModel。
- 定向GREEN：Editor与Maintenance两类51项/2 suites全部通过（21/30），0 failure/error/skipped；退出0、BUILD SUCCESSFUL 1m43s，40 tasks：4 executed/36 up-to-date。独立复审确认生命周期修复及原D3批准；Minor为新回归自身的超时fallback仍可取消，已改为复用同一NonCancellable join/reset helper、外层finally关闭执行器，进入最终全量/重复复验。
- 最终代码的首次全量复验：上述四任务命令退出0、BUILD SUCCESSFUL 4m17s，101 tasks：7 executed/94 up-to-date；14:53（Asia/Shanghai）实际XML1097项/93 suites、0 failure/error/skipped（D3业务35项、测试清理回归1项新增）。正式源码/Debug/androidTest为缓存构建检查，UnitTest重新编译并强制执行，lint本轮测试源重新分析、正式/AndroidTest/报告为未变缓存（0 error/55既有warning）。清理Minor独立复审关闭，代码无遗留审查问题。
- 该轮仍有异步SQLiteConnection CloseGuard警告，日志落在UnifiedPlanCalibrationTest/HabitScreenRegressionTest；D3两类XML没有此警告。归属尚未定位，不把测试通过描述成所有运行警告已消除；不把本次Main清理修复说成也修复了SQLite资源警告。第二次全量强制重跑结果待下文记录。

- 相同最终代码第二次全量强制复验：退出0、BUILD SUCCESSFUL 2m29s，101 tasks：2 executed/99 up-to-date；14:55（Asia/Shanghai）XML更新，1097项/93 suites、0 failure/error/skipped，包含35项D3 Room和1项清理回归。JVM实际重跑，Debug/androidTest/正式和测试编译/lint分析与报告均为未变缓存构建检查，lint0 error/55既有warning。两次连续成功不等于所有时序或设备路径已证明。
- 最后一轮仍观察到同类SQLite CloseGuard警告，记录落在LauncherBrandingTest/TodoScreenRegressionTest；归属未定位，作为已知验证限制保留，不通过关闭检查或吞异常消除提示。D3两个测试XML无此提示不证明其他日志中的资源一定不来自D3。
- 独立D3代码及测试清理复审无遗留Critical/Important/Minor；修复仅涉及测试helper，没有修改生产旧Schedule行为。README/CHANGELOG/格式v2/计划/本验收同步，保留所有RED、失败和缓存范围，不把不完整的中间全量成功当最终证据。
- 提交前远端feature仍为基线`c5c29bd44b2667acc7113d213e1ac443379632a7`，main为`b016bcbc0f146ea6d9fe257b734700689de5cb51`。按授权仅提交推送当前feature并核对远端；不建PR/合并main/发布APK，不读写用户个人文件/真实数据库，不提交密钥或个人数据。

D3五项任务局部验收完成。已实现完整13实体隔离目标库十表快照、原子恢复/业务清空、原值/关系/设置保留及世代/技术库存/自然自增序列核验；按用户方案1拒绝危险自增MAX和目标序列耗尽，不重编号或reset序列。真实Room证据只来自合成内存测试库，不是个人数据恢复、实际文件选择/保护文件写入/确认/系统提醒维护、设备覆盖升级或生产启用验收；无设备执行/UI/Release/真机/Android大库内存或性能验证。资源警告归属仍待定位，64MiB/100k受限内存表示不是流式或低内存保证。下一批D4实现文件与维护流程，E整体接线/激活v6，F版本与升级交付；当前生产0.1.1/code2/schema5、main及已发布APK未改，完整D–F/V1尚未完成。

## D4 · 第十三批：文件与维护流程（局部验收通过）

2026-10-13，用户确认五项范围，基线HEAD `d8183bfd968ef17a647ade63e29bc560538098b4`、工作区原先干净，沿用当前feature、不建工作树。本批只新增隔离文件用例/捕获/维护流程及合成集成测试，实际UI和Hilt/生产v6/默认codec/旧工作流/版本保持，完整E/F尚未开始。本计划和本记录替代缺失writing-plans/Buddy状态脚本；顺序TDD与独立只读复审，不提前称完成或发布。

- 新基线JDK25/Gradle9.4.1/既有网络init，`:app:testDebugUnitTest --rerun --console=plain`退出0、BUILD SUCCESSFUL 1m27s，40 tasks：1 executed/39 up-to-date；实际XML1097项/93 suites、0 failure/error/skipped。这是上一批代码本轮重跑，不是D4新增结果。
- 独立计划复审的一项事务边界及两项交接/所有权细化纳入D4计划，复审确认全部关闭，不是实现验收。
- 文件测试首轮编译失败1m34s（35 tasks：3 executed/32 up-to-date），匿名对象由公开属性暴露时丢失特有字段类型，修正为具名Streams；未计功能RED。随后12项文件测试全部运行并以缺V2BackupFileUseCases的AssertionError失败，0 error/skipped，BUILD FAILED 2m16s（40 tasks：5 executed/35 up-to-date）。RED缺实现adapter仅在测试源，开始实现后改直接typed调用，不保留生产桩。
- 文件实现后首次组合执行18项：12文件测试通过，6提醒捕获用例在Robolectric启动阶段因默认生产Application触发未初始化WorkManager而失败，未进入捕获断言，不计捕获RED。BUILD FAILED 4m17s（40 tasks：12 executed/28 up-to-date）。回溯LifeManagerApp.onCreate栈，与既有隔离Room装置比较，仅为新测试显式指定空Application；未修改生产启动或WorkManager配置。开始组合验证文件/捕获/维护测试。
- 修正装置后的组合实质RED：36项/3 suites，文件12项通过，捕获6项与维护18项因缺实现断言失败（24 failure、0 error/skipped）；BUILD FAILED 3m39s（40 tasks：11 executed/29 up-to-date）。开始实现SQL捕获/独立通知枚举及冻结维护流程，测试adapter改直接typed真实构件，未保留假生产实现。
- 初轮实现组合36项，34通过/2失败，BUILD FAILED 3m33s（40 tasks：11 executed/29 up-to-date）：保护文件断言误用fixture行数组顺序（SQL来源排序为SCHEDULE/TODO），改按既有独立sameRows逐值/关系比较，不改存储/编码；原caller在CAS后的读取点取消却观察到已提交标记，不能据此简单宣布提交前取消通过。先加入与D3相同精确事务operation Job取消装置，保留原caller场景另作强化回归。
- 39项组合GREEN（文件12、捕获6、维护21）退出0、BUILD SUCCESSFUL 2m12s（40 tasks：5 executed/35 up-to-date），0 failure/error/skipped；新增完整私有暂存v1→真实转换→保护→Room恢复、8秒校准超时及真实PlanNotificationHelper/PlanBackupNotificationSource/UnifiedMaintenanceReminderEffects/PlanReminderScheduler精确清理集成，验证同ID新世代卡片保留与技术行取消后清理。原caller取消边界仍待下轮强化，不把该中间GREEN当最终验收。
- 代码复审两项Important（一项原caller取消、一项行数组断言）及File取消进入屏障Minor进入修复；文件回归加entered-write后再真实Job取消，原caller取消SQL终点场景保留并要求完整回滚，另补“可读但既不是原世代也不是原+1”的普通/取消不确定结果回归，进入42项强化RED。
- 强化RED实跑42项/3 suites、3 assertion failure、0 error/skipped，BUILD FAILED 2m29s（40 tasks：5 executed/35 up-to-date）。实际复现原caller已取消但标记仍报已提交（当时尚未核对最终持久化状态），以及可读异常世代被当作未推进；屏障和行序修正通过。先针对原caller修复隔离D3mutation/SQL helper：进入Room事务前捕获原CoroutineContext，每表/行/元数据读取和最后提交前同时检查原上下文与Room当前上下文。不改旧生产路径、迁移/schema/原值或自增规则；执行原caller与22项既有D3 mutation回归，异常世代分类修复待下一步验证。
- 原上下文guard后的聚焦23项：22项既有D3 mutation通过，caller标记断言仍失败，BUILD FAILED 2m12s（40 tasks：11 executed/29 up-to-date）。不能把此失败单凭标记当作数据库已提交；按复审建议先记录最终持久化状态及证据读取时事务状态，核对测试世代reader（原为直接raw读取）与生产reader（Room事务读取）差异，调查C2a已有提交完成交接桥。当前取消修复尚未通过，不据局部D3回归宣布完成。
- 原caller聚焦诊断1项失败（1m55s、40 tasks：5 executed/35 up-to-date），但在标记断言前完整unchanged断言通过：最终持久化generation=5/plans=2；原证据raw读取序列为(false,5)四次、(true,6)一次，证明曾在原事务仍活动时看到未提交CAS6，误报已提交，不能将早先标记误报写成真实数据已提交。增加与C2a同类的短提交完成交接桥：NonCancellable仅保护Room完成/回执，V2BackupWriteCancellation携带原writer供D3每行/最终guard，原writer仍可使SQL体回滚；等待实际endTransaction后才核对结果/释放冻结。测试世代reader改为与生产同样的Room事务读取；不确定取消装置保留真实Job取消并显式注入丢返回异常，避免已收到回执的取消被错误期待为“不确定”。进入25项提交前/后/外层交接及旧D3回归验证。
- 提交交接GREEN：25项/2 suites全部通过，0 failure/error/skipped，BUILD SUCCESSFUL 3m51s（40 tasks：11 executed/29 up-to-date）；原caller最终完整状态回滚且不报已提交，真正已提交的Job取消/外层dispatcher返回仍有已提交标记，22项既有D3 mutation全部通过。独立桥/marker/body guard源审查无新增问题。再单独修复异常世代分类为原值/精确且防溢出原+1/未知三分支，最后一支通过不确定异常返回，不声称回滚、不重放；开始77项D4+D3组合GREEN。
- 最终组合GREEN：77项/5 suites全部通过（D4文件12、捕获6、维护24；D3 snapshot13/mutation22），0 failure/error/skipped；BUILD SUCCESSFUL 2m25s（40 tasks：8 executed/32 up-to-date）。异常可读世代的普通/取消不确定结果通过；原值/精确原+1、真实caller/事务operation/外层返回取消与提交后身份清理回归通过。生产Hilt/Manifest/schema/Gradle/版本/旧文件工作流无改动，开始最终全量JVM/Debug/androidTest/lint复验。
- 最终代码首次全量四任务命令（JDK25/Gradle9.4.1/既有网络init，与D3命令相同）退出0、BUILD SUCCESSFUL 7m24s，101 tasks：17 executed/84 up-to-date；实际XML1139项/96 suites、0 failure/error/skipped（本批新增42项/3 suites）。JVM强制执行；Debug dex/重新打包，androidTest源重新编译而包内容未变为缓存构建检查；lint三类源及报告重新分析（0 error/55既有warning），本批新构件无lint issue。独立代码最后的异常世代分类复审无遗留Critical/Important/Minor；审查不是执行结果的替代。
- 首次全量仍观察到Robolectric异步SQLite CloseGuard警告，日志落在UnifiedLegacyCleanupTest/LauncherBrandingTest，资源归属未定位，不能仅凭日志落点归咎旧代码或宣称D4没有关系；作为已知验证限制保留。第二次全量强制复验结果待记录，当前不提交/推送。
- 第二次相同四任务全量强制复验退出0、BUILD SUCCESSFUL 3m47s，101 tasks：2 executed/99 up-to-date；17:35（Asia/Shanghai）核对更新XML1139项/96 suites、0 failure/error/skipped。JVM实际重跑，编译/Debug/androidTest/lint分析与报告均为未变缓存构建检查，lint0 error/55既有warning。两次连续全量成功，不当作全部取消时序或设备交付保证。
- 最后一轮仍有同类SQLite资源警告，日志落在TodoReminderDeliveryTest/LauncherBrandingTest/UnifiedPlanCalibrationTest；归属仍未定位，不能据日志落点断言资源属于哪个批次，不通过关闭检查/吞异常抹除提示。计划与最后完整代码/提交桥/异常世代复审无遗留Critical/Important/Minor，审查没有代替真实执行。
- README/CHANGELOG/格式v2/文件安全/本计划/本验收同步，保留失败诊断、缓存范围与限制；生产Hilt/Manifest/数据库schema/Gradle版本/旧backup codec与workflow/UI/0.1.1/code2/签名不变。本轮未读写任何个人库/用户文件，不提交密钥或个人数据。
- 提交前远端feature=`d8183bfd968ef17a647ade63e29bc560538098b4`，main=`b016bcbc0f146ea6d9fe257b734700689de5cb51`；仅提交推送当前feature并核对远端，不建PR/合并main/发布APK。

D4五项局部验收完成：v2导出/严格关闭重读、v1/v2受限私有暂存预览、原库保护备份/独立确认、同一冻结下的原子恢复/清空及现有C2c2精确清理/新鲜校准。原writer控制SQL体取消，短Room完成/回执桥避免读未完成CAS或提前释放冻结；实际回滚、已提交取消及异常/不可读世代不确定状态均区分，不重放写入。新增42项测试，1139项全量连续两次通过。真实文件证据限合成测试私有副本/字节流、完整13实体Room与Robolectric系统服务；无本轮设备执行/真实SAF/Downloads或云提供方、个人库恢复/覆盖安装/UI/Release/真机/磁盘耗尽/真实强杀/大库内存性能验收。提供方别名/协作取消/跨系统非原子/64MiB多份内存/资源警告限制保留。E统一页面与生产消费者/UUID/隐私/进程重建接线、F正式版本和升级交付仍待完成；当前生产0.1.1/schema5、main及已发布APK不变，不等于V1正式发布。

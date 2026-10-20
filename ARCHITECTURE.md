# 项目架构

更新：2026-10-20；已发布正式1.2.0/code12日记与AI回顾，为当前Latest Release；历史1.1.4正式签名延续。

1.2.1/code13页面调整正在验证：PlanScreen完整渲染未完成列表，仅已完成保留折叠状态；DiaryDao两个查询与DiaryRules.ordered统一按记录日期、创建时间、ID倒序，DiaryViewModel沿用该规则。AI回顾由AiReviewDateInput提供只读框与右侧日期选择器，UTC转换civil date，禁用时清除临时选择；确认仍调用原range流程清除旧预览与未保存结果。Room8、V4及签名身份不变。当前验收见docs/page-polish-1.2.1-acceptance.md。

当前版本使用Room8、计划/打卡/日记/订阅/设置五入口。7→8仅新增七张日记表，旧15张Room表定义不变；DiaryViewModel与DiaryAiViewModel由Activity保留的独立工厂创建。DiaryScreen组合列表/日历、草稿箱、详情、编辑和媒体查看，草稿落Room且1秒空闲/5秒连续输入保存；回调捕获原世代/会话/修订，普通写入由仓库内部获取全局准入，调用方不得再嵌套。

FileDiaryStorage统一持有私有不可变媒体、临时文件与pin，锁序为全局准入或维护会话→storage gate→Room事务。完整快照在同一Room事务中读取旧模块与日记，gate内pin后释放普通准入进行慢I/O；恢复媒体先完整准备、摘要校验和promote，数据库在同一维护事务替换并推进一次世代。文件回收读取提交后的真实引用，pin保护并行导出与预览。

生产完整备份为format4/schema8流式ZIP，V4ArchiveContainer处理有界流/ZIP条目，V4BackupManifest处理严格清单，V4ArchiveFiles处理文档提供方与本机媒体租约，V4BackupRepository/Mutation扩展既有维护端口。内部V2类型名继续作为共享载体，但日记只允许V4导出；V1–V3冻结校验，旧恢复保留日记/草稿/媒体/回顾。详见docs/backup-format-v4.md。

AI采用独立domain/data/ui端口，RoomDiaryAiDataSource仅暴露确认范围的正式文字与心情，保存时原子核对整个来源快照。HTTPS Chat Completions客户端禁止重定向、限制请求/响应与超时；Key由Keystore AES/GCM保护并放noBackup，授权和调用账本不进入归档。周期Worker最多每轮一个、只看最近两个完整周期，默认关闭；授权绑定配置来源/版本及数据世代，未知发送结果不重试。来源编辑使回顾过期，删除任一来源移除整份回顾；生成中失效清除未保存结果。具体已验证范围见docs/diary-acceptance.md。


1.1.4正式版仅调整版本与发布流程，业务架构不变。tools/release在Gradle输出之外完成16KiB对齐、旧→新证书轮换签名和独立apksig校验；私密配置及新旧密钥均在仓库外。API26/27保留旧v2证书，API28+采用固定专用v3证书；详见docs/release-signing.md。

设置主页由PreferenceContent的主题/提醒卡片、V1BackupSettingsControls的数据操作行与SettingsPlatformSection的权限/关于入口组成。详细权限状态、版本与许可证只在弹窗展示；权限恢复重读、真实系统Intent、备份会话票据和独立确认保持。DatePresentation固定YYYY-MM-DD及HH:mm，仍按指定ZoneId转换Instant；旧DateFormat枚举、持久值和历史备份严格契约保留，当前导航不再注入旧格式。

Android Kotlin + Jetpack Compose；1.1.x历史版本使用Room数据库schema7，计划、打卡、订阅、设置四个入口。应用身份com.example.lifemanager。已发布1.1.0为schema7；互动森林不改数据库或备份格式。6→7只增加habit_trees/habit_waterings，历史迁移保持。历史交付见docs/v1-delivery-acceptance.md。

订阅改版1.1.2沿用原SubscriptionViewModel/Repository及世代保护。SubscriptionWorkspace组合使用中列表、取消项目与月份账单，SubscriptionBills按指定月份从原锚点推导CNY明细，以Long精确累加预计/实际；图表退役。新建固定人民币，旧币种与隐藏字段保留；日期选择器在独立SubscriptionDateInput中。提醒为所有有效订阅派生扣费日0天/当地09:00策略，0只属于调度身份，不写入Room或备份；历史1/3/7数据仍原样往返，旧闹钟及通知退役。权限、总开关、维护及当日等待/消费协议保持。

互动森林由ForestSection组合共享ForestScene、树木查找和底部详情；ForestLandscape绘制连续山丘草地、小路池塘，TreeIllustration在场景中省去独立徽章背景。视角和选中状态只属于UI，按数据世代失效，不写数据库；所有浇水/种植仍走原ViewModel世代保护与Room事务。

打卡沿用HabitRepository与受世代保护的HabitViewModel。树种选择保存在既有iconKey的tree:前缀值中，未展示颜色保留。树与打卡记录通过habit_waterings(recordId主键)关联，一条记录只能浇水一次，成长由COUNT关联记录推导，上限20；撤销记录级联回退对应树。新建任务和种子、手动浇水、成熟后重种均用Room事务，水滴不能跨任务使用。统计保留月历并用全部任务的林场替换热力图。Compose Canvas绘制六树种及五阶段，不依赖网络图片或字母占位。

1.1.x历史完整备份为format3/schema7，覆盖新增两表、同任务引用和20次上限。历史V1/V2 codec与白名单冻结，旧文件先按原hash严格验证，再转换为含未浇水种子的当前结构。SQL快照/恢复/清空/序列与总字节行数保护扩展至林场；内部V2命名DTO/port沿用但当时生产入口消费V3契约。详情见docs/backup-format-v3.md。

计划展示由PlanWorkspace组合四象限列表、内嵌月历、详情和编辑。Activity ViewModelStore保留各ViewModel；写入通过PlanUseCases、维护世代和Repository事务核对，界面回调携带不可变世代/会话戳。PlanDraft保留未展示的历史字段，保存前严格验证。

PlanCalendarViewModel按范围、模式、完成状态和展示时区去重查询；同月选日仅更新选中态，跨月/世代清空旧事实，刷新期间保留已核对月历但禁用动作。PlanPresentation固定上海时间展示，既有存储时区及实际时刻保持。有效固定偏移历史记录仅同ID且原时区未改时允许编辑，最终在事务内以真实记录复核；新计划仍严格IANA。

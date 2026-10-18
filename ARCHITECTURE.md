# 项目架构

更新：2026-10-19；已公开发布订阅账单预览1.1.2，源码已合并main；设置精简预览1.1.3已公开发布。

设置主页由PreferenceContent的主题/提醒卡片、V1BackupSettingsControls的数据操作行与SettingsPlatformSection的权限/关于入口组成。详细权限状态、版本与许可证只在弹窗展示；权限恢复重读、真实系统Intent、备份会话票据和独立确认保持。DatePresentation固定YYYY-MM-DD及HH:mm，仍按指定ZoneId转换Instant；旧DateFormat枚举、持久值和历史备份严格契约保留，当前导航不再注入旧格式。

Android Kotlin + Jetpack Compose；当前源码Room数据库schema7，计划、打卡、订阅、设置四个入口。应用身份com.example.lifemanager。已发布1.1.0为schema7；互动森林不改数据库或备份格式。6→7只增加habit_trees/habit_waterings，历史迁移保持。历史交付见docs/v1-delivery-acceptance.md。

订阅改版1.1.2沿用原SubscriptionViewModel/Repository及世代保护。SubscriptionWorkspace组合使用中列表、取消项目与月份账单，SubscriptionBills按指定月份从原锚点推导CNY明细，以Long精确累加预计/实际；图表退役。新建固定人民币，旧币种与隐藏字段保留；日期选择器在独立SubscriptionDateInput中。提醒为所有有效订阅派生扣费日0天/当地09:00策略，0只属于调度身份，不写入Room或备份；历史1/3/7数据仍原样往返，旧闹钟及通知退役。权限、总开关、维护及当日等待/消费协议保持。

互动森林由ForestSection组合共享ForestScene、树木查找和底部详情；ForestLandscape绘制连续山丘草地、小路池塘，TreeIllustration在场景中省去独立徽章背景。视角和选中状态只属于UI，按数据世代失效，不写数据库；所有浇水/种植仍走原ViewModel世代保护与Room事务。

打卡沿用HabitRepository与受世代保护的HabitViewModel。树种选择保存在既有iconKey的tree:前缀值中，未展示颜色保留。树与打卡记录通过habit_waterings(recordId主键)关联，一条记录只能浇水一次，成长由COUNT关联记录推导，上限20；撤销记录级联回退对应树。新建任务和种子、手动浇水、成熟后重种均用Room事务，水滴不能跨任务使用。统计保留月历并用全部任务的林场替换热力图。Compose Canvas绘制六树种及五阶段，不依赖网络图片或字母占位。

完整备份当前为format3/schema7，覆盖新增两表、同任务引用和20次上限。历史V1/V2 codec与白名单冻结，旧文件先按原hash严格验证，再转换为含未浇水种子的当前结构。SQL快照/恢复/清空/序列与总字节行数保护扩展至林场；内部V2命名DTO/port沿用但生产入口消费当前V3契约。详情见docs/backup-format-v3.md。

计划展示由PlanWorkspace组合四象限列表、内嵌月历、详情和编辑。Activity ViewModelStore保留各ViewModel；写入通过PlanUseCases、维护世代和Repository事务核对，界面回调携带不可变世代/会话戳。PlanDraft保留未展示的历史字段，保存前严格验证。

PlanCalendarViewModel按范围、模式、完成状态和展示时区去重查询；同月选日仅更新选中态，跨月/世代清空旧事实，刷新期间保留已核对月历但禁用动作。PlanPresentation固定上海时间展示，既有存储时区及实际时刻保持。有效固定偏移历史记录仅同ID且原时区未改时允许编辑，最终在事务内以真实记录复核；新计划仍严格IANA。

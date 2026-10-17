# 项目架构

更新：2026-10-17；已公开发布计划界面精简预览1.0.3，当前打卡林场1.1.0候选未发布。

Android Kotlin + Jetpack Compose；当前源码Room数据库schema7，计划、打卡、订阅、设置四个入口。应用身份com.example.lifemanager。已发布1.0.3为schema6；6→7只增加habit_trees/habit_waterings，历史迁移保持。历史交付见docs/v1-delivery-acceptance.md。

打卡沿用HabitRepository与受世代保护的HabitViewModel。树种选择保存在既有iconKey的tree:前缀值中，未展示颜色保留。树与打卡记录通过habit_waterings(recordId主键)关联，一条记录只能浇水一次，成长由COUNT关联记录推导，上限20；撤销记录级联回退对应树。新建任务和种子、手动浇水、成熟后重种均用Room事务，水滴不能跨任务使用。统计保留月历并用全部任务的林场替换热力图。Compose Canvas绘制六树种及五阶段，不依赖网络图片或字母占位。

完整备份当前为format3/schema7，覆盖新增两表、同任务引用和20次上限。历史V1/V2 codec与白名单冻结，旧文件先按原hash严格验证，再转换为含未浇水种子的当前结构。SQL快照/恢复/清空/序列与总字节行数保护扩展至林场；内部V2命名DTO/port沿用但生产入口消费当前V3契约。详情见docs/backup-format-v3.md。

计划展示由PlanWorkspace组合四象限列表、内嵌月历、详情和编辑。Activity ViewModelStore保留各ViewModel；写入通过PlanUseCases、维护世代和Repository事务核对，界面回调携带不可变世代/会话戳。PlanDraft保留未展示的历史字段，保存前严格验证。

PlanCalendarViewModel按范围、模式、完成状态和展示时区去重查询；同月选日仅更新选中态，跨月/世代清空旧事实，刷新期间保留已核对月历但禁用动作。PlanPresentation固定上海时间展示，既有存储时区及实际时刻保持。有效固定偏移历史记录仅同ID且原时区未改时允许编辑，最终在事务内以真实记录复核；新计划仍严格IANA。

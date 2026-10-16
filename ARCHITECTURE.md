# 项目架构

更新：2026-10-16；当前已发布计划界面精简预览1.0.3。

Android Kotlin + Jetpack Compose；生产Room数据库schema6，计划、打卡、订阅、设置四个入口。应用身份com.example.lifemanager。历史迁移、备份和统一提醒约定见README及docs/v1-delivery-acceptance.md。

计划展示由PlanWorkspace组合四象限列表、内嵌月历、详情和编辑。Activity ViewModelStore保留各ViewModel；写入通过PlanUseCases、维护世代和Repository事务核对，界面回调携带不可变世代/会话戳。PlanDraft保留未展示的历史字段，保存前严格验证。

PlanCalendarViewModel按范围、模式、完成状态和展示时区去重查询；同月选日仅更新选中态，跨月/世代清空旧事实，刷新期间保留已核对月历但禁用动作。PlanPresentation固定上海时间展示，既有存储时区及实际时刻保持。有效固定偏移历史记录仅同ID且原时区未改时允许编辑，最终在事务内以真实记录复核；新计划仍严格IANA。

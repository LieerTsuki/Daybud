# 项目编码与交付约定

- 沿用Kotlin、Compose、StateFlow及现有domain/data/ui分层；生产Room升级不可破坏数据，不能用清库替代迁移。
- 可见回调捕获渲染时的世代/会话戳；异步结果必须复核世代，不把旧动作借用到新数据。写入事务核对真实持久化记录。
- 时间内部保留Instant与civil date语义；上海本地输入严格检查有效偏移，不自动修正不存在/歧义时间。未展示的历史字段保持。
- 用户界面使用简洁中文、MaterialTheme与既有配色，日期/任务单行约定以当前验收文档为准；失败提示和草稿保护不可被成功提示精简一并删除。
- 变更先复现有意义失败，再运行相应回归；使用真实Room/Android测试覆盖跨层行为。Gradle命令串行，构建证据与限制如实记录。
- 版本/APK交付须同步CHANGELOG、README、验收与实际发布状态；保留历史Release和证据，详见AGENTS.md。
- domain不得依赖Android、data、ui或notification；UI不得直连Room/SQLite/具体持久层。媒体端口属于domain，Android实现属于data，共用平台解码属于platform。静态许可证清单是明确的只读例外。
- 导航状态机依赖编辑器端口；具体业务ViewModel只在组合根与适配器装配。通知必须保留原generation，离开确认继续比较原session/ticket及完整编辑快照。
- 日记失效通知与数据读取分开；数据在同一事务读取并在原准入范围发布。优化查询不得取消世代检查、丢失媒体引用错误，或用分页截断统计/那年今日。
- 使用仓库Wrapper运行Gradle，命令串行；当前结构检查使用tools/check_project_structure.py，Phase脚本只为历史记录。CI编译androidTest不等于设备测试通过。

# 纯离线总结与建议实施计划

依据：已确认的 [设计](../specs/2026-10-22-offline-only-fast-reviews-design.md)。截至计划建立时仅有可行性实验，没有应用改动或新 APK。

## 必须形成的用户行为

- AI 设置不含云端地址、Key、模式切换或云端定时生成；旧任务不能继续发送，旧凭据损坏不妨碍离线。
- 已导入的模型仍可使用，模型准备保留完整校验而减少单字节磁盘读取。
- 回顾生成短总结和两条具体建议，来源与已填写心情可核对；材料过长在开始前拒绝，不能漏掉一部分日期或截断。
- 运行阶段与错误清楚，取消/失败不丢日记、不保存残缺回顾。
- 原业务、历史回顾、Room8/V4、签名、模型资源和历史 Release 保留。真机速度及质量未达标不宣称已修复或适配多数手机。

## 任务 1：模型准备与错误诊断

文件：`data/diary/ai/local/LocalModelStore.kt`、新的有界文件读取器、`LocalAiGateway.kt`、`LocalInferenceSession.kt`、对应单元/设备测试。

先写并运行读取边界、跳读、EOF、重复获取与同长度损坏测试，以及超时与通用运行失败应有不同错误的测试。实现有界缓冲 GGUF 元数据读取，完整 SHA256 和结构校验不变；区分超时、加载/进程退出和输出不完整，不增加隐藏重试。模型租约和取消终态保持。

验证：独立 Kotlin/Robolectric 模型检查、真实 0.6B/1.7B 导入/获取，再由主任务串行执行 Gradle 回归。性能数值限于实际测试环境。

## 任务 2：云端退役与离线设置

文件：`AiModeStore.kt`（或新的独立离线配置）、`DiaryModule.kt`、`AppModule.kt`、`LifeManagerApp.kt`、`DiaryAiWorker.kt`、`DiaryAiViewModel.kt`、`AiSettingsScreen.kt`，相应测试。`DiaryAiEngine.kt`由主任务统一修改，避免与任务 3 冲突。

先测试旧 CLOUD/损坏凭据仍能选择本地、旧 Worker 无网络依赖、维护失效独立于云端。离线配置不委托旧云端凭据；生产 DI 不提供网络客户端；精确退役旧唯一 WorkManager 工作；移除设置页面云端入口并清理旧凭据。保留历史纯数据类型用于旧回顾和备份往返，不为删除旧类破坏兼容。

验证：配置/升级/维护单元与 Android 集成测试，Hilt 编译，生产入口和旧 Worker 网络依赖检查。任何失败的凭据清理不得恢复云端。

## 任务 3：短总结与建议协议

文件：新增 `LocalSummaryPipeline.kt` 与 `PhoneSummaryResultPolicy.kt`，`DiaryAiEngine.kt`、`RoomDiaryAiDataSource.kt`、`DiaryAiPolicy.kt`、回顾显示组件及对应测试。旧 `LocalGroundedPipeline` 与原文策略保留为历史协议，不作为新生成默认。

先测试 13 篇短材料可同一请求、实际 token/UTF8 超预算不调用生成、不完整/空洞结构不保存、日期和来源状态失效、旧摘选不被新策略放宽。新摘要协议显式版本化，生成/Engine/Room 三个边界校验；输入摘要使用本地规范编码，不再调用云端请求编码。新 UI 将保留的五个底层段类型聚合成总结/建议/心情；来源入口与保存一致。

候选输出只生成必要短文字，模型字段不能发出任何操作。程序核对心情与来源，不伪装为能自动证明所有语义准确。运行候选对完整 12 组合成材料检查代表性、事实、状态、建议相关性；失败必须修正或明确保留为未通过，不能把 JSON 通过当作功能完成。

验证：协议/预算/保存边界的红绿测试，实际 CPU 生成，Room/V4 混合历史往返和来源失效测试。端到端性能单独测，不引用可行性探针数字当作产品结果。

## 任务 4：兼容 ARM 可选加速

文件：`app/src/main/cpp/CMakeLists.txt`、新增独立 CPU 能力探测 JNI、原生引擎加载器、`app/build.gradle.kts` 或辅助打包脚本，相关原生/JVM 测试。

先验证 CPU 选择规则，基线探测不包含可选指令，不同时加载两个具有相同 JNI 导出的方法库。独立构建 dotprod 候选和基线，明确 16KiB 对齐、目标架构和资源打包；使用同一固定 b5501 源码。不得只开启 GGML_CPU_ALL_VARIANTS 就宣称 ARM 自动加速。没有可用 ARM 真机时只能称候选，不能启用未验证的性能承诺。

具体接线：`libdiary_cpu_features` 独立基线 JNI 只查询 `getauxval(AT_HWCAP) & HWCAP_ASIMDDP`，在 `NativeLlamaBindings` 首次 JNI 解析前选择且只加载一个完整引擎。`EngineLibraryLoader` 以 ARM64、硬件能力和候选构建开关共同判定；探测失败走基线，选定完整引擎加载失败则报错，不尝试加载第二个同名 JNI 实现。默认 `-PdiaryDotprodCandidate=false`，不构建/打包大体积候选；显式 `true` 的测试 APK 才在独立 CMake 目录构建 `libdiary_llama_dotprod`，通过 generated jniLibs 和 merge JNI 任务依赖打包。BuildConfig 记录候选开关；普通版本保留基线，其他 ABI 不启用 dotprod。未有 ARM 真机证据前不能把候选写为已验证的手机加速。

验证：原生契约、NDK 两种 ARM 构建、APK 库清单/指令要求、基线路径与选择逻辑；物理机单独比较 2/4 线程与冷启动，并记录不能完成的验证项。

## 集成、交付及风险

任务 1/2/4 可按不重叠文件并行，任务 3 由主任务负责；所有 Gradle 命令由主任务串行执行。每项先核验失败再实现，结束后独立审查跨组件接线。

集成回归覆盖日记原有流程、完整备份/恢复、来源变更、取消、私有进程退出、模型卸载、同签名升级和旧 Worker 退役。重新运行本轮测试，构建 Debug/androidTest/Release、lint 和签名/16KiB 检查；不复用历史测试计数。

最终同步 CHANGELOG/README/架构/代码索引/验收证据，再推送 GitHub、用新版本发布 APK 和 Release，完整公开回下载验证。旧 APK 和模型文件不覆盖。若手机速度或摘要质量未过门槛，提交明确未发布进度，不能创建声称已修复的正式版本。

主要风险是小模型事实/代表性不足、ARM 设备不可连机验证、可选原生引擎构建成本与升级后的旧后台工作。回退采用新修正提交恢复旧生成实现并保持已写数据/模型文件，不能清库、回退 schema 或覆盖历史发布。

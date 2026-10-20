# 日记模块实施计划与进度

依据：[已批准设计](../specs/2026-10-19-diary-design.md)。用户已确认全部设计，并选择自填AI配置；沿用直接main、正式签名与发布偏好。不再逐阶段询问是否继续。

writing-plans未列入可用技能且本机未找到对应目录，本计划采用已安装Buddy planner的文件级规划与验证要求。使用Buddy及subagent-driven-development执行，构建串行，独立文件并行。

## 必须达到的结果

- 五入口导航中可以创建、阅读、编辑、删除文字/照片/视频日记；列表与日历、统计、那年今日真实接线。
- 日记草稿落入Room，停止输入1秒/持续输入最多5秒写入；恢复、并发及旧回调均不会覆盖新数据。
- 7→8不破坏原15张Room表。完整format4包含全部旧模块、日记、草稿、媒体及回顾；旧v1–v3恢复不删除日记。
- AI服务可配置且Key不进入备份/日志。周/月/自选总结五段、来源检查、空输入不请求、有限内容提示，可选自动周期有授权起点与调用上限。
- 实际验证UI、草稿/图库/视频、备份恢复与正式覆盖安装，公开下载与本地APK摘要一致。

## 模块接口约定

新增domain/diary、data/diary、ui/diary。日期用LocalDate（SQLite epochDay），时间用Instant（SQLite millis），ID为Long（日记）及随机String（草稿、媒体、回顾）。列表按createdAt DESC/id DESC，不按更新时间重排。

核心公开类型：DiaryEntry、DiaryDraft、DiaryMedia、DiaryMood、DiarySnapshot、DiaryReview、DiarySourceRevision、DiaryStatistics。日记/草稿携带revision，媒体携带id、mimeType、bytes、sha256及position；路径只属于storage层。核心代理应先公布精确Kotlin签名供其余模块接线，不得自行改变已公布接口而不通知。

核心仓库支持：observeEntries、observeDrafts、observeReviews、readEntry、createDraft、readDraft、saveDraft(expectedRevision)、commitDraft(expectedRevision/baseRevision)、discardDraft、deleteEntry(expectedRevision)、snapshotRange、saveReview(snapshotRevisionGuard)、deleteReview、backupSnapshot/replaceWithinExistingTransaction/clearWithinExistingTransaction。所有正常写入使用MaintenanceCoordinator的原世代准入及Room事务；维护适配器不得嵌套准入。

Room最终新增：diary_entries、diary_drafts、diary_media、diary_entry_media、diary_draft_media、diary_reviews、diary_review_sources。entry/draft与媒体分离，允许编辑草稿和正式记录共享不可变文件。回顾来源保存id/revision，删除任一来源时删除整份相关回顾与衍生缓存。

DiaryStorage使用应用私有目录，随机存储ID，流式staging/promote/open；全局准入→storage gate→Room事务为统一锁序。固定快照时建立导出pin，写文件期间删除不能回收被pin文件；失败后只清理无任何引用的本应用文件。核心仓库与存储所有者在同一模块实现此协议，不由UI自行删除文件。

AI独立包domain/diary/ai、data/diary/ai、ui/diary/ai。定义DiaryAiDataSource端口，向AI提供纯文字来源快照并持久化经过来源修订检查的回顾；root负责适配到核心仓库。AI任务状态/授权/秘密配置位于noBackup专用存储，避免恢复旧备份自动重发任务；Key使用Keystore AES/GCM。网络不得长时间持有维护许可。

AI界面独立DiaryAiViewModel和DiaryAiScreen，服务配置独立AiSettingsScreen/对话框，root负责Activity owner与DI及设置入口；UI代理的DiaryScreen通过onOpenAi打开回顾页，不复制实现AI。

## 工作分配和依赖

### D1 核心、存储与迁移（核心代理）

创建domain/diary核心类型/仓库/规则；data/diary存储、Room实体/DAO/仓库；7→8迁移及工厂注册、schema8导出。根DatabaseModule/DI由root接线；AppModule及导航不由代理修改。

先写日记排序/日期/统计/闰日回忆、修订保存及迁移测试，root运行RED；再实现。仓库测试覆盖真实Room事务、失败不丢草稿、重复保存/旧revision拒绝、媒体引用和删除回顾。给root提供维护事务快照/替换接口，说明媒体promote、pin与回收边界。

### D2 AI模块（AI代理，可与D1并行）

自有DTO/端口、HTTPS Chat Completions兼容客户端、加密配置、五段结果/引用验证、分段范围处理、周期规划/去重/有限重试，Worker、ViewModel及AI/配置UI。写请求脱敏、空输入、来源/结构、协议错误、自动授权起点和失效测试，再实现；不修改manifest、依赖、主DI、根Room、导航或备份。

### D3 日记UI与图库导入（UI代理，等待D1接口）

DiaryViewModel/Screen、卡片、日历、详情/编辑、草稿入口、图片缩略图/查看和本地视频播放、ActivityResult图库选择、导入进度。Activity保留owner，所有回调捕获世代+会话+修订；正常切页尽力flush，维护Busy保留未提交输入，恢复时旧回调不可借用新世代。先验证有意义的自动保存与旧选择结果失败用例，再实现。新依赖需求先通知root，代理不改公共build/DI/nav。

### D4 流式备份与整合（root，与代理并行）

保留冻结v1/v2/v3 codecs，新增V4容器/严格清单/媒体摘要、流式文件适配器。复用原维护会话、保护备份、独立确认、取消与UNKNOWN保护；旧备份恢复保留日记。V4提供完整旧模块快照和日记原子替换；原世代推进一次，维护事务数据库外只做预先准备/提交后回收。

集成MainActivity、PlanNavigationViewModel、V1NavGraph、DiaryModule、settings AI入口、AndroidManifest INTERNET及媒体依赖、backup UI文件名/MIME/完整清单。网络Key与任务状态不得进入V4。更新过时backup safety说明。

先写容器损坏/路径穿越/超限/重复/缺失/错误哈希和旧备份保留日记的失败测试；迁移与维护覆盖真实Room；保护备份重新打开校验才能认可。

### D5 验证和修正

root串行Gradle。局部验证→Debug/androidTest/Release和lint→完整JVM；独立代码审查与目标复核。专用模拟器验证实际图库、视频、草稿进程重建、日历/详情、AI配置（受控协议服务）、备份恢复及旧1.1.4签名覆盖，保留旧表全部字段与序列。

不得将受控服务代替真实供应商验收；没有用户Key时如实记录。大文件边界用有界流与实际容器测试，8GiB目标提供方未实际测试时注明范围，不虚称全部云提供方通过。

### D6 交付

同步未发布CHANGELOG、README、验收与迁移/备份文档。功能全接线且验证通过后升为下一正式版本（拟1.2.0/code12），捕获384基线及新增文件完整源快照，沿用固定签名与轮换链，复核API26/28/35升级。按现有授权推送main、发布新tag和APK+SHA256SUMS，公开回下载、Latest和源码核对，追加发布回执，保持历史版本。

## 持久进度（跨上下文恢复依据）

- 2026-10-19：完整设计已批准；原main基线7a2cef41784db99c1b3461fa3aeffd2bc1bf3a30，Room7/format3，1.1.4/code11。
- D1：核心/存储/7→8代码已接入，生成schema8与旧15表定义逐项一致；纯规则/媒体测试已取得RED→GREEN，真实Room迁移测试等待本轮Gradle。
- D2：AI配置、协议、授权、自动队列与UI已接入；25项纯策略/协议/受控HTTPS测试通过，来源失效另有1项VM测试通过。实际供应商尚未验证，无用户密钥。
- D3：五入口、日记/AI页面、列表日历详情和图库已接线；草稿/Activity测试已补，正在验证慢附件导入中途持久化。
- D4：V4容器8项与清单4项纯测试通过；完整流式文件、媒体租约和旧备份保留日记的真实Room集成测试正在运行。
- D5：第一轮Gradle于compileDebugKotlin发现无序列化插件时的serializer引用错误，已改显式JSON树；第二轮串行构建/局部回归进行中。首次中断的构建不作通过证据。
- 第三轮82项/19套件集成测试全部通过；独立审查1项P2经RED→GREEN修正并限定复核关闭。源码已升1.2.0/code12候选并同步CHANGELOG/README/验收文档，全量构建、JVM、lint及设备验收继续；尚未发布。

Ruling：直接在当前main共享工作区完成，不另建分支或PR——用户已明确要求main直推；只暂存各任务具体文件，保留原正式签名与所有历史证据。

Ruling：各里程碑不单独发布——避免schema8已写日记而完整备份尚未覆盖；所有最终表在一次7→8中纳入。

Ruling：后台AI队列/授权记录使用独立noBackup状态，而非可恢复日记用户数据——防止恢复旧归档后重复计费或复用旧授权；回顾文本及来源仍纳入V4。

Ruling：若无法找到writing-plans技能，使用Buddy planner完成相同规划，不阻塞已批准工作——避免为了非必需工具安装重复征求用户许可。

- 本轮V4容器/清单/文件17项局部回归已通过（含合法摘要的坏回顾结构、短读取提供方）；完整Gradle正在重跑全部测试，最新日志.tools/diary-full-validation.log。

- 2026-10-20最终应用回归：1290项JVM/124套件全部通过，Debug/androidTest/Release成功，lint0错误/51警告；独立审查累计三项问题关闭。媒体GC先有真实失败与回滚回归，日期默认标题修正后29项AI回归通过。
- 最终API35专用模拟器78项全部通过（94.197秒），包含实际系统图库双选、设备编码视频的播放/后台暂停/返回与旋转释放、草稿/Keystore/V4媒体恢复与GC。新增设备测试首轮因未驱动Compose帧而超时，测试同步修正后完整重跑通过，生产播放器未改。实际截图及失败记录见[验收](../../diary-acceptance.md)。D1–D5源码验证完成，D6待正式签名、API26/28/35覆盖升级及公开发布回下载。
- D6正式候选已签名，API26/28/35三条1.1.4覆盖升级及精确数据清理通过，原16表全部字段/类型/NULL和序列保留，新增七表。完整验证与440文件清单已汇总；辅助脚本字段/目录识别修正及API26只读ADB中断均保留失败过程，不改变APK。待main推送、公开发布和匿名回下载回执。

- D6完成：2026-10-20T08:52:36+08:00正式发布v1.2.0，main直推源码`f05cced242fad87e8a3d108d4195db348b862c9c`；公开APK/校验文件匿名回下载一致，Latest和tag核对通过。发布回执为后续纯文档提交，应用源码与验收清单保持一致。

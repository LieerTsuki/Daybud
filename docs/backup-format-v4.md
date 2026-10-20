# 完整备份V4 · 日记与媒体

1.2.0/code12候选使用ZIP归档，首项必须为UTF-8 `manifest.json`，其他项只能是清单中声明的 `media/<canonical-UUID>`。不使用文件名、URI或标题拼接私有路径，不把视频Base64嵌入JSON。旧V1–V3 JSON编解码及校验预算保持冻结。

清单固定字段为 `format="life-manager-backup"`、`formatVersion=4`、`schemaVersion=8`、`appVersion`、`exportedAt`、`payload`、`payloadSha256`。规范化payload摘要与V3相同规则；不是加密或身份认证。

payload固定包含 `legacy` 与 `diary`。legacy是独立完整V3/schema7对象，其历史摘要、关系及原始字段仍需通过原编解码器。内外appVersion/exportedAt必须相同。diary含entries、drafts、media、reviews与entrySequence：

- entries：id/date/title/body/mood/createdAt/updatedAt/revision/media。
- drafts：id/entryId/baseRevision及与entry相同的日期、内容、时间、修订和有序媒体。
- media：id/mimeType/bytes/sha256/position。全局媒体清单position为0，entry/draft中的position是各自连续顺序，多个拥有者可共享同一不可变文件。
- reviews：id/from/to/createdAt/sections/sources/needsUpdate/provider/model/inputHash。五个固定唯一段落为生活回顾、心情变化、重要时刻、个人成长、温暖寄语；每段保存文字和来源ID，sources保存日记ID与revision。来源删除不留下衍生回顾；过期回顾允许来源日期已移出原范围，但必须明确needsUpdate。
- 日期以SQLite epochDay整数、时间以epochMillis整数原样保存。草稿和回顾关系、序列、媒体引用、哈希、实际流读取字节数、总量均需验证。

清单上限64MiB，业务和关联行总计100,000；归档媒体最多8GiB，每篇20个附件/合计1GiB，图片50MiB、视频500MiB。导出前校验元数据预算，写出时复核实际大小与摘要；关闭输出后重新打开，验证清单语义、全部附件与ZIP尾部再认定成功。拒绝重复/未知/缺失条目、路径越界、未知字段、重复JSON键、错误类型、坏关系、压缩膨胀及截断归档。提供方短读和取消按实际流处理，不依赖声明大小。

导出在全局准入→storage gate→Room事务内固定同一快照并pin文件；慢文档I/O释放全局准入后进行，期间删除不能回收被pin文件。预览先在私有随机临时文件中校验完整媒体，再promote并持有只在本机有效的租约；租约不写入清单，取消/错误/恢复结束释放。数据库替换与世代推进在单一Room事务内完成，提交结果未知仍禁止重复执行。

恢复V4替换全部归档覆盖数据，包括日记、持久草稿、媒体和回顾；恢复V1–V3只替换历史格式覆盖模块，保留现有日记数据。恢复前必须另存并重读验证当前完整V4保护备份，再独立最终确认。清空允许用户主动跳过备份，但需独立确认日记、草稿、媒体及回顾的删除范围，不能因保护备份失败自动降级。

不导出本机世代、媒体路径、API Key、AI服务配置、自动授权或调用账本。恢复/清空暂停自动总结，授权还需匹配数据世代；换机重新配置服务。文件未加密，包含个人日记与媒体，请放在可信位置。旧应用不能读取V4，不能通过降级安装读取新数据库。

候选设备和提供方实际验证范围见[日记验收](diary-acceptance.md)，不将流上限校验等同于所有云提供方8GiB实测。

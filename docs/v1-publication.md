# 1.0.0预览版发布与main合并

发布于 **2026-10-16T01:24:20+08:00**（Asia/Shanghai），用户已明确授权“发布并合并”。

- Release：[v1.0.0-rizhiya-preview-20261016](https://github.com/LieerTsuki/Daybud/releases/tag/v1.0.0-rizhiya-preview-20261016)，GitHub标记为公开prerelease，非草稿。
- [下载签名Release APK](https://github.com/LieerTsuki/Daybud/releases/download/v1.0.0-rizhiya-preview-20261016/rizhiya-1.0.0-preview-release.apk)，版本1.0.0/code3/schema6，包名`com.example.lifemanager`。
- 已验收APK源码：[`4db24deb56d9562fede555d7d3aa7100f5013cee`](https://github.com/LieerTsuki/Daybud/commit/4db24deb56d9562fede555d7d3aa7100f5013cee)；main由b016bcbc无冲突快进到该源码，随后仅追加发布文档。发布使用原已验收签名APK，不用复验产生的unsigned中间包替换。tag固定在已验收源码。
- APK为12,435,895字节，SHA-256 `acd7349f8445500fd6928952c8d602db89c0a92eb295a769f7ee0b410d34db94`。GitHub上传完成后返回的size/digest与本地一致，未覆盖历史Release或旧APK。
- 匿名访问公开下载地址回下载成功，实际12,435,895字节及SHA-256再次匹配；新tag指向4db24de，旧0.1.1 tag仍为777e1e9。
- 沿用0.1.1预览调试证书，应用为非debuggable Release构建；预览发布不等于正式签名或应用商店资格。

## 合并后追加核对

本次在main强制重跑JVM1122项/92 suites，0failure/error/skipped；Debug/androidTest/Release构建及lint检查通过。日志`.tools/v1-publication-main-validation.log`。本次没有重跑70项API35设备测试：这些来自相同源码4db24de的[候选验收](v1-delivery-acceptance.md)，不能改写为本轮新设备证据。

覆盖升级、V1/V2真实Downloads/系统选择器往返、73→74→75→76世代、非调试体积与模拟器性能保留原证据。物理设备、稳定60fps、大库/云提供方、厂商后台策略、正式签名及商店发布资格仍未验收。

本次只追加发布状态与下载入口；更新内容、升级备份要求和已知限制见[CHANGELOG](../CHANGELOG.md)与[交付说明](v1-release-notes.md)。结构化发布回执见[evidence/v1-1.0.0-publication.json](evidence/v1-1.0.0-publication.json)。候选阶段记录中的“未发布/main未合并”是其当时状态，以本发布记录为准。

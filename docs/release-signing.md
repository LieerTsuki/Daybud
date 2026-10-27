# 发布签名与密钥恢复

日子芽从1.1.4/code11开始使用专用发布密钥。包名为`com.example.lifemanager`，minSdk26；1.4.2/code18沿用Room8及V4完整归档，签名身份和轮换链保持。1.1.4当时采用Room7和format3/schema7，属于历史版本状态。

## 固定证书与兼容边界

| 平台 | 有效签名 | SHA-256 |
| --- | --- | --- |
| Android8.0/8.1，API26/27 | 旧预览v2证书 | `55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c` |
| Android9及以上，API28+ | 专用发布v3证书 | `04d67f1f2d46129cd3ec33ce26eabf53b86e4149dc870dd13ab081a78a5cbb8a` |

APK携带旧→新证书的签名轮换链，显式`--rotation-min-sdk-version 28`。旧证书保留installed-data能力以支持覆盖升级，rollback关闭；其余能力沿用工具默认值。Android8无法识别v3轮换，仍需旧密钥签署v2，因此不能称所有支持平台均已摆脱旧证书。旧密钥和轮换链必须与新密钥一起长期保留。将来是否停止Android8支持需单独决策，不得为了换签强迫用户卸载。

Android官方提示较旧平台存在签名比较相关限制；本应用未配置shared UID或跨应用签名权限/认证器集成。AndroidX生成的应用自身动态Receiver签名权限保留，不将其误称为“完全没有签名权限”。指定模拟器结果只证明所记录平台和用例，不代表全部厂商系统。[apksigner参数](https://developer.android.com/tools/apksigner) · [v3轮换说明](https://source.android.com/docs/security/features/apksigning/v3)。GitHub正式Release与应用商店审核、Play App Signing是不同流程。

## 私密文件与备份

本机主目录：`C:\Users\LENOVO\.daybud-signing\release-20261019`。

本机另一磁盘副本：`D:\DaybudSigningBackup\release-20261019`。已逐文件核对，目录ACL只授予当前Windows用户与SYSTEM。它仍在同一台电脑，需将整个目录另存一份离线备份。

目录包含`daybud-release.jks`、`legacy-preview.keystore`、`signing-private.json`、`daybud-signing-lineage.bin`、公钥证书和恢复说明。配置含密钥口令，严禁提交Git或上传Release；只有公钥证书和轮换证明可以公开。恢复到新位置后修改配置中的三个绝对路径，保留原密钥、别名、口令及证书，勿重新生成。新密钥为RSA3072/SHA256withRSA，有效期10950天。

仓库`tools/release/daybud-release-certificate.pem`与`daybud-signing-lineage.bin`分别是公开证书、公开轮换证明，不含私钥或口令，可用于独立核对身份。

## 重复发布

先完成对应版本构建、测试和更新验收文档，再执行：

```powershell
$env:JAVA_HOME='D:\jdk'
python tools/release/release.py `
  --config C:/Users/LENOVO/.daybud-signing/release-20261019/signing-private.json `
  --sdk C:/Users/LENOVO/AppData/Local/Android/Sdk/build-tools/36.0.0 --java-home D:/jdk `
  --input app/build/outputs/apk/release/app-release-unsigned.apk `
  --output .tools/release-1.4.2/delivery/rizhiya-1.4.2.apk --version 1.4.2 --code 18
```

后续版本需替换版本、code与输出名，保留证书配置。工具拒绝仓库内私密配置/密钥、现有输出、调试包、错误包名/版本/SDK和不同发布证书；口令只经子进程环境传递，失败不回显原始工具输出。签名前验证外部轮换链；签名后独立验证APK内证明链、六个精确SDK点26/27/28/32/33/35的有效证书以及16KiB对齐，再以独占创建写出APK和公开签名回执。

```powershell
python -m unittest discover -s tools/release -p test_release.py
```

签名成功不等于发布成功。还需实际旧包覆盖升级和数据库核对、锁定源码提交、推送main、创建非预览Release并回下载校验。历史Release和APK保留，禁止覆盖同名不同内容的资产。

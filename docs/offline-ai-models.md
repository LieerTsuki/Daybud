# 离线AI底层与模型来源

本文记录1.3.0候选的固定源、真实产物与验证限制。正式公开状态以[本轮验收](offline-ai-acceptance.md)为准，模型与APK在同一Release发布。

## 推理底层

- llama.cpp标签b5501，源码提交`cdf94a18023c92f41808ec874ba577d914674717`。
- 固定源码归档SHA256：`142fbf66ca4945bedb74aa0fce555f09f1be73becd064e3b30a53cee92b1b194`；CMake同时校验网络归档及本地缓存。
- Android NDK27.2.12479018、CMake3.22.1、最低API26，静态C++运行库。禁用网络推理、GPU与宿主特定指令；ARM64按armv8-a编译。
- APK的离线许可证入口包括llama.cpp、minja与nlohmann JSON完整MIT通知，Qwen3模型使用Apache-2.0。

## 已校验小模型与候选

| 权重 | 固定官方源 | 字节数 | SHA256 |
| --- | --- | ---: | --- |
| Qwen3 0.6B Q8_0 | Qwen/Qwen3-0.6B-GGUF，修订23749fefcc72300e3a2ad315e1317431b06b590a，Qwen3-0.6B-Q8_0.gguf | 639446688 | 9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031 |
| Qwen3 0.6B Q4_K_M（本项目二次量化） | 上述Q8_0，llama.cpp b5501 `--allow-requantize` | 396704416 | 15e434d8ddf0af38fe3f7003af6ce6eac4edada6df34324aa45979f9cc79f02e |
| Qwen3 1.7B Q8_0（候选） | Qwen/Qwen3-1.7B-GGUF，修订90862c4b9d2787eaed51d12237eafdfe7c5f6077，Qwen3-1.7B-Q8_0.gguf | 1834426016 | 061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a |
| Qwen3 1.7B Q4_K_M（本项目二次量化候选） | 上述Q8_0，同版量化器`--allow-requantize Q4_K_M 2` | 1107408544 | 54c0f1203a724e9f33e76916beab3bdfaffef56cf7b42a93b1bc21319fc0bf97 |

四个文件均已实际计算完整SHA。1.7B官方Q8经官方Modelscope端点验证TLS续传，最终与固定Hugging Face官方摘要完全相同；Q4由同一量化器产生。1.7B Q4也通过真实文件Store导入、摘要、GGUF、只读FD及删除租约检查；这些检查不代表回顾语义质量。固定官方模型卡：[0.6B](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/23749fefcc72300e3a2ad315e1317431b06b590a)、[1.7B](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/tree/90862c4b9d2787eaed51d12237eafdfe7c5f6077)。

本项目Q4由官方Q8_0再次量化，存在额外精度损失，不能称Qwen官方Q4。实际使用的Windows量化器SHA256为`3fef6bc82c422674c1cd8dd16b21d1bee164b84cb1d9df5aaa7dfe7d60387b10`。

应用只提供上述两个Q4文件的下载/导入清单，Q8仅作为可重现来源记录。默认0.6B约397MB，较大1.7B约1107MB；Q4运行前预留资源分别按至少1.5GiB/2GiB估计并叠加系统阈值检查，不能把估计当作设备总内存最低保证。实际运行仍可能被系统回收。

1.3.0交付范围是用户选定的保守原文摘选：AI只选择编号，原文和心情由程序严格核对。自由生成候选未通过事实/性能门槛，不能称这些模型具备合格的完整中文总结能力。分类、代表性、遗漏和上下文仍有限，详见[质量证据](evidence/offline-ai-grounded-quality.json)。

模型归属Qwen/Alibaba Cloud，许可证Apache-2.0；修改为上述二次量化。APK内的离线AI许可证包含完整Apache及底层MIT文本，Release同步附带模型来源/修改/许可证通知；权重独立于APK与V4备份。

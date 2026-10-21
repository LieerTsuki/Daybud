# Offline inference source pin

- Upstream: https://github.com/ggml-org/llama.cpp
- Release: `b5501`
- Commit: `cdf94a18023c92f41808ec874ba577d914674717`
- Source: https://codeload.github.com/ggml-org/llama.cpp/tar.gz/cdf94a18023c92f41808ec874ba577d914674717
- Archive SHA-256: `142fbf66ca4945bedb74aa0fce555f09f1be73becd064e3b30a53cee92b1b194`
- License: MIT; full upstream notice is retained in `third_party/llama.cpp-LICENSE`.

CMake verifies the archive before extracting it. For a fully offline build, put
that exact archive at `.tools/llama-b5501.tar.gz` in the repository, or set the
`DIARY_LLAMA_ARCHIVE` CMake cache path to an identical archive. An extracted,
unverified source directory is not the normal build input. No mutable branch or
latest-release lookup is used during configuration.

The Android runtime builds CPU inference only, with no network downloader, GPU,
OpenMP, dynamic backend discovery or host-native CPU flags. ARM64 targets the
armv8-a baseline; x86_64 does not require AVX, FMA or SSE4.2. The engine and C++
runtime link into one JNI shared library with 16 KiB ELF segment alignment.
Build dependencies are Android NDK `27.2.12479018` and SDK CMake `3.22.1`.
Debug JNI and upstream kernels explicitly use `-O3` while retaining debug
symbols. AGP's unoptimized Debug defaults are unsuitable for real inference
latency measurements; this setting does not enable additional CPU instructions.
The 32-bit armeabi-v7a and x86 APK slices contain only a small JNI shim that
throws `UnsupportedOperationException`; it cannot produce inference results.

The JNI boundary owns a duplicate of a read-only regular-file model descriptor;
the model lease must remain immutable throughout that session. The engine uses
the model's Jinja chat template with `enable_thinking=false`, then applies the
actual tokenizer for both counting and generation. Qwen special token delimiters
within message contents are separated by U+2060 so diary content cannot create
chat role boundaries. Output is sampled through the supplied GBNF grammar and
is complete only after EOS. Token/byte limits reject input before inference;
they do not silently truncate diary text. No prompt or upstream exception text
is forwarded to native logs or Java exceptions.

`tests/runtime_contract_test.cpp` covers model-independent input limits,
cancellation, irreversible session closure and role-token escaping. It can be
built independently with `-DDIARY_LOCAL_HOST_TESTS=ON`. Passing it does not
establish model loading, real inference quality, cancellation latency or device
compatibility; those require the Android integration and real model tests.

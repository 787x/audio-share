# Android 44.1 kHz PCM24 DIRECT 可行性验证

2026 年 11 月 21 日，在小米 14（HyperOS 3，API 36）和 Bose QC Ultra 2 上复测了[阶段一](android-audio-output-phase-1.md)的 Windows 11 → Wi-Fi → Android → 蓝牙 A2DP 链路。音频策略和公开 API 都报告 DIRECT 能力，但实际播放时，默认路由和明确指定 A2DP 设备的 PCM24 轨道均进入 **48,000 Hz MIXER**。小米自带播放器播放本地 PCM24 WAV 时也进入同一输出。

## 实机对照

网络输入来自 Voicemeeter In 5，为 44,100 Hz、Float32、双声道。两次 PCM24 AudioTrack 对照使用相同的 `USAGE_MEDIA`、`CONTENT_TYPE_MUSIC`、双声道和 44,100 Hz PCM24 packed，差异仅为播放前是否调用 `AudioTrack.setPreferredDevice()`。

| 播放方式 | 活跃 PID／session | 应用轨道 | 实际路由和线程 |
| --- | --- | --- | --- |
| 原始 AudioTrack | 24755／2057 | 44,100 Hz Float32 | A2DP，`AudioOut_25`（`MIXER`） |
| PCM24 默认路由 | 24755／2017 | 44,100 Hz PCM24 packed | A2DP port 427，`AudioOut_25`（`MIXER`） |
| PCM24 首选 A2DP | 24755／2041 | 44,100 Hz PCM24 packed | 请求 port 427，`accepted=true`，实际仍为 port 427，`AudioOut_25`（`MIXER`） |
| 小米媒体查看器播放本地 WAV | 3165／2081 | 44,100 Hz PCM16 | A2DP，`AudioOut_25`（`MIXER`） |
| AAudio 回归检查 | 8903／2097（系统快照） | 44,100 Hz PCM24 packed，实际 `SHARED` | A2DP port 427，`AudioOut_25`（`MIXER`） |

各组均匹配到 I/O handle `37`，线程采样率为 48,000 Hz，HAL 格式为 `AUDIO_FORMAT_PCM_24_BIT_PACKED`。同一 handle 在 AudioPolicy 中对应活跃 `deep_buffer` 和 `AUDIO_DEVICE_OUT_BLUETOOTH_A2DP`。所连接设备的蓝牙配置为 aptX Adaptive、44,100 Hz、24-bit、双声道，A2DP offload 已启用。aptX Lossless 子模式未确认。

本地对照使用 FFmpeg 生成双声道 WAV，并用 ffprobe 核对为 `pcm_s24le`、44,100 Hz、24-bit。媒体查看器 `com.miui.mediaviewer` 的活跃轨道为 PCM16，说明这条播放链未将文件 PCM24 保留到 AudioFlinger 轨道。该对照未观察到 PCM DIRECT 或压缩 offload 输出，无法说明支持原生 PCM24 的其他播放器会如何路由。

## 能力查询与策略

- 两次 PCM24 模式的 `getAudioDevicesForAttributes()` 和实播路由均指向 A2DP port 427。对双声道 PCM24 packed 44.1 kHz、PCM24 packed 48 kHz、Float32 44.1 kHz，`getDirectPlaybackSupport()` 均返回 `0x4`（`DIRECT_PLAYBACK_BITSTREAM_SUPPORTED`），未返回压缩 offload 标志。此查询没有接收 AudioTrack 或首选设备参数。[Android AudioManager](https://developer.android.com/reference/android/media/AudioManager#getDirectPlaybackSupport(android.media.AudioFormat,%20android.media.AudioAttributes))
- `getDirectProfilesForAttributes()` 返回 14 项，包括同时列出 44.1 和 48 kHz 及立体声的 PCM24 packed、Float32 profile。实际 A2DP 设备的 `getAudioProfiles()` 返回两项，均登记为 PCM16、48 kHz、立体声。两组 API 查询的对象不同。设备 profile 的值也无法推断蓝牙编解码器参数或 HAL 最终格式。[Android AudioManager](https://developer.android.com/reference/android/media/AudioManager#getDirectProfilesForAttributes(android.media.AudioAttributes)) · [Android AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo#getAudioProfiles())
- 系统策略把 `direct_pcm` 列为 `primary` 模块（模块 handle `10`）的输出 mix port，flags 为 `0x0001 AUDIO_OUTPUT_FLAG_DIRECT`。它声明 PCM16、PCM 8.24、PCM24 packed、PCM32，PCM24 packed 包含 44.1 和 48 kHz 及 HAL 立体声掩码 `0x0003`。`BT A2DP Out` 和 `BT A2DP Headphones` 的路由均列出 `direct_pcm`。另一模块 `bluetooth_qti` 有独立的动态 `a2dp output`，flags 为 `NONE`。模块 handle、mix port 与已打开的输出 I/O handle 分属不同层级。
- AOSP 的能力查询会在匹配 profile 时加入 DIRECT flag，匹配项可以让查询返回 BITSTREAM。实际开流是另一条路径。AOSP 的 `openDirectOutput()` 会对缺少 DIRECT flag 且可由 Mixer 容纳的常规双声道线性 PCM 跳过 DIRECT 选择。进入直接输出选择后仍需匹配设备、格式、采样率和声道，HAL 打开也须成功。这可解释为何能力查询与实播结果不同。HyperOS 厂商分支实际执行的判断条件无法从本次转储确定。[AOSP AudioPolicyManager](https://android.googlesource.com/platform/frameworks/av/+/master/services/audiopolicy/managerdefault/AudioPolicyManager.cpp)

**阶段二判断为公开能力存在但实际未选中。** `setPreferredDevice()` 能请求 A2DP 设备，当前设备也接受了请求，实播仍映射到 `deep_buffer`。既有 AAudio 请求在阶段一得到 SHARED。现有证据支持停止重复尝试普通 AudioTrack 参数、首选设备和 AAudio sharing mode。若今后发现某个播放器或厂商公开功能，在同一耳机和编码配置下实际得到 44.1 kHz PCM DIRECT，再以活跃轨道及 I/O handle 为证据调查该差异。当前尚无法确认厂商可用的触发条件或其他播放器的结果。

## 构建与复测

使用阶段一提交 `4c43b40` 为基线，保留三种原有模式、独立 `.audiolab` 安装、自动重连及回退逻辑。实验 APK 已并存安装。执行 `:app:assembleDebug :app:testDebugUnitTest --offline` 成功，13 项测试通过。实机确认原始模式仍能写入 44.1 kHz Float32。部分连接收到 TCP reset，自动重连后恢复写入。

1. 打开 **Audio Share Lab → 音频 → 实验音频输出**。先选 **AudioTrack PCM24**，再选 **AudioTrack PCM24（首选 A2DP）**，每轮停止后重新播放。没有唯一 A2DP 候选时，日志记录跳过原因并使用默认路由。
2. 等 `first_write` 后在播放中采集。用 `preferred_device`、`api_route`、`direct_capability`、`direct_profile`、`route_device_profile` 检查请求、实际设备和能力。能力查询依赖当前媒体属性与策略，应用实播以各自 session 对应的 AudioFlinger 线程为准。
3. 在 AudioFlinger 中用活跃的 PID／session 行查找线程类型、输出采样率和 HAL 格式，再用 I/O handle 到 AudioPolicy 的活跃输出核对 profile、设备和 activity count。判断本地播放器时同时查看轨道格式和输出 flags，避免把压缩 offload 归为 PCM DIRECT。

```sh
adb logcat -d -s AudioOutputLab NetClient AAudio
adb shell dumpsys media.audio_flinger
adb shell dumpsys media.audio_policy
adb shell dumpsys bluetooth_manager
```

本地证据目录为 `android-app/app/build/outputs/audio-lab-phase-2/`。快照前缀为 `default`、`preferred`、`original`、`local` 和 `aaudio-smoke`。蓝牙地址已在快照中脱敏，转储文件保留在构建目录。

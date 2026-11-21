# Android 44.1 kHz PCM24 音频输出验证

2026 年 11 月 21 日，小米 14（HyperOS 3，Android API 36）通过 Wi-Fi 接收 Windows 11 上 Audio Share Server 从 Voicemeeter In 5 捕获的 44,100 Hz、Float32、双声道 PCM，并输出到 Bose QC Ultra 2 蓝牙耳机。三个模式均实际写入音频，活跃的 AudioFlinger 输出均为 **48,000 Hz MIXER**。请求 AAudio `EXCLUSIVE` 时，系统返回 `SHARED`。

## 实机记录

| 模式 | 应用实际写入 | 活跃轨道 session | 活跃输出 |
| --- | --- | --- | --- |
| 原始 AudioTrack | 44,100 Hz、Float32、双声道 | 1393 | `AudioOut_25`，`MIXER` |
| AudioTrack PCM24 | 44,100 Hz、PCM24 packed、双声道 | 1345 | `AudioOut_25`，`MIXER` |
| AAudio EXCLUSIVE 实验 | 44,100 Hz、PCM24 packed、双声道，实际 `SHARED` | 1377（系统快照） | `AudioOut_25`，`MIXER` |

三组活跃轨道属于实验版进程 PID 22736，输出线程均为 I/O handle `37`，线程采样率为 48,000 Hz，HAL 格式为 `AUDIO_FORMAT_PCM_24_BIT_PACKED`。同一 handle 在音频策略中对应活跃的 `deep_buffer`，设备为 `AUDIO_DEVICE_OUT_BLUETOOTH_A2DP`。策略中的 `direct_pcm` 是声明的 profile，本次未承载这些轨道。

蓝牙的活跃 `mCodecConfig` 均为 aptX Adaptive、44,100 Hz、24-bit、双声道，`A2dpOffloadEnabled` 为 `true`。这些字段未确认 aptX Lossless 子模式。AAudio 创建失败分支在本次实机测试中未触发。

## 实验版与测试

在实验版 **Audio Share Lab → 音频 → 实验音频输出** 选择模式，然后停止并重新播放。Debug APK 使用独立应用 ID `io.github.mkckr0.audio_share_app.audiolab`，可与原版同时安装。PCM24 实验模式需要 API 31 或以上，以及 44,100 Hz、Float32、双声道的网络输入。[Android NDK AAudio 参考](https://developer.android.com/ndk/reference/group/audio)

PCM24 转换保持左右声道交错顺序，以小端写入 3 字节有符号样本，并裁剪超出范围的幅度。播放器按顺序处理数据和部分写入，记录路由、错误和回退，在停止或重连时释放旧输出。Windows 服务端和传输协议沿用原实现。

在 `android-app` 目录运行以下命令。构建使用 JDK 17、Android SDK 35 和 NDK `27.0.12077973`。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

本次构建和 13 项单元测试均通过，其中 4 项覆盖 PCM24 裁剪、数值边界、端序、声道顺序、帧对齐和缓冲区容量。APK 位于 `android-app/app/build/outputs/apk/debug/audio-share-app-0.3.4-debug.apk`。

实机验证了三种模式的切换、旧流关闭和重连后写入。连接重置后，客户端自动重连并恢复写入。另用 48 kHz 测试输入触发 PCM24 的格式拒绝，确认日志记录 `fallback`，并建立了 48 kHz Float32 的原始 AudioTrack。

## 复测步骤

连接蓝牙耳机，在 Windows 上持续播放音频。每次只运行一个输出模式，等日志出现 `first_write` 后，在播放期间采集以下信息。

```sh
adb logcat -d -s AudioOutputLab NetClient AAudio
adb shell dumpsys media.audio_flinger
adb shell dumpsys media.audio_policy
adb shell dumpsys bluetooth_manager
```

1. 用应用日志中的 `selected`、`api_request`、`api_actual` 和 `api_route` 核对请求、实际流参数及路由。AAudio 是否取得独占以 `actualSharing` 为准。若有 `fallback`，以记录的 `active` 为实际播放模式。
2. 在 `media.audio_flinger` 中用应用 PID 或 AudioTrack session 找到活跃轨道，读取所属输出线程的类型、`Sample rate` 和 `HAL format`。轨道自身的 `SRate` 反映应用流的采样率。
3. 用同一 `I/O handle` 在 `media.audio_policy` 的活跃输出中核对 profile、设备和 `Global active count`，并在 `bluetooth_manager` 中读取当前连接的 `mCodecConfig`。

本次系统快照和应用日志保存在本地构建目录 `android-app/app/build/outputs/audio-lab-evidence/`。三组快照分别以 `original-`、`pcm24-` 和 `aaudio-` 开头，格式回退见 `fallback-logcat.txt`。

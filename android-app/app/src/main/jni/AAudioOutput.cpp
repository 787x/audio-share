#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>

namespace {
constexpr char kTag[] = "AudioOutputLab";
constexpr int kFrameBytes = 6;

AAudioStream* streamFrom(jlong handle) {
    return reinterpret_cast<AAudioStream*>(handle);
}

void logError(const char* operation, aaudio_result_t result) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "mode=AAUDIO_EXCLUSIVE event=%s error=%s (%d)",
                        operation, AAudio_convertResultToText(result), result);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mkckr0_audio_1share_1app_service_AAudioNative_open(
        JNIEnv*, jobject, jint deviceId) {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        logError("create_builder_failed", result);
        return 0;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSampleRate(builder, 44100);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I24_PACKED);
    AAudioStreamBuilder_setDeviceId(builder, deviceId);

    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        logError("open_failed", result);
        return 0;
    }

    const auto sharing = AAudioStream_getSharingMode(stream);
    const char* sharingName = sharing == AAUDIO_SHARING_MODE_EXCLUSIVE ? "EXCLUSIVE" :
                              sharing == AAUDIO_SHARING_MODE_SHARED ? "SHARED" : "UNKNOWN";
    const auto rate = AAudioStream_getSampleRate(stream);
    const auto channels = AAudioStream_getChannelCount(stream);
    const auto format = AAudioStream_getFormat(stream);
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "mode=AAUDIO_EXCLUSIVE event=api_actual rate=%d format=%s(%d) channels=%d "
                        "requestedSharing=EXCLUSIVE actualSharing=%s downgraded=%s "
                        "performance=%d deviceId=%d",
                        rate, format == AAUDIO_FORMAT_PCM_I24_PACKED ? "PCM24_PACKED" : "other",
                        format, channels, sharingName,
                        sharing == AAUDIO_SHARING_MODE_EXCLUSIVE ? "false" : "true",
                        AAudioStream_getPerformanceMode(stream), AAudioStream_getDeviceId(stream));
    if (rate != 44100 || channels != 2 || format != AAUDIO_FORMAT_PCM_I24_PACKED) {
        logError("stream_format_mismatch", AAUDIO_ERROR_INVALID_FORMAT);
        AAudioStream_close(stream);
        return 0;
    }

    result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
        logError("start_failed", result);
        AAudioStream_close(stream);
        return 0;
    }
    return reinterpret_cast<jlong>(stream);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_mkckr0_audio_1share_1app_service_AAudioNative_write(
        JNIEnv* env, jobject, jlong handle, jobject buffer, jint offset, jint frames) {
    auto* bytes = static_cast<const char*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!bytes || offset < 0 || frames < 0 ||
        static_cast<jlong>(offset) + static_cast<jlong>(frames) * kFrameBytes > capacity) {
        return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    }
    const auto result = AAudioStream_write(streamFrom(handle), bytes + offset, frames, 10000000);
    if (result < 0) logError("write_failed", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_mkckr0_audio_1share_1app_service_AAudioNative_deviceId(
        JNIEnv*, jobject, jlong handle) {
    return AAudioStream_getDeviceId(streamFrom(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_mkckr0_audio_1share_1app_service_AAudioNative_xRuns(
        JNIEnv*, jobject, jlong handle) {
    return AAudioStream_getXRunCount(streamFrom(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_mkckr0_audio_1share_1app_service_AAudioNative_close(
        JNIEnv*, jobject, jlong handle) {
    const auto result = AAudioStream_close(streamFrom(handle));
    if (result != AAUDIO_OK) logError("close_failed", result);
}

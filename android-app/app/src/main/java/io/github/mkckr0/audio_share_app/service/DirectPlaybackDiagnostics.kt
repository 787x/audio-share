package io.github.mkckr0.audio_share_app.service

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioProfile
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(31)
internal fun logDirectPlaybackCapabilities(
    manager: AudioManager,
    attributes: AudioAttributes,
    routedDevice: AudioDeviceInfo?,
    mode: String,
    session: Int,
) {
    val context = "mode=$mode session=$session routedDeviceId=${routedDevice?.id} " +
        "usage=${attributes.usage} contentType=${attributes.contentType} attributesFlags=${attributes.flags}"
    fun log(event: String, fields: String) = Log.i(OUTPUT_LOG, "event=$event $context $fields")
    fun probe(event: String, query: () -> Unit) {
        try {
            query()
        } catch (error: RuntimeException) {
            log(event, "status=error exception=${error.javaClass.simpleName}")
        }
    }

    probe("route_device_profiles") {
        if (routedDevice == null) {
            log("route_device_profiles", "status=route_unknown")
        } else {
            val profiles = routedDevice.audioProfiles
            log("route_device_profiles", "count=${profiles.size}")
            profiles.forEach { log("route_device_profile", it.describe()) }
        }
    }

    if (Build.VERSION.SDK_INT < 33) {
        log("direct_capability", "status=api_unavailable sdk=${Build.VERSION.SDK_INT} requires=33")
        return
    }

    probe("attributes_route") {
        val devices = manager.getAudioDevicesForAttributes(attributes)
        log("attributes_route", "devices=${devices.map { "${it.id}:${it.type}" }}")
    }
    val queries = listOf(
        44100 to AudioFormat.ENCODING_PCM_24BIT_PACKED,
        48000 to AudioFormat.ENCODING_PCM_24BIT_PACKED,
        44100 to AudioFormat.ENCODING_PCM_FLOAT,
    )
    for ((rate, encoding) in queries) {
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(encoding)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val requested = "rate=$rate format=${encodingName(encoding)} channels=2"
        probe("direct_capability") {
            val flags = AudioManager.getDirectPlaybackSupport(format, attributes)
            log("direct_capability", "$requested flags=$flags meanings=${directFlags(flags)}")
        }
    }
    probe("direct_profiles") {
        val profiles = manager.getDirectProfilesForAttributes(attributes)
        log("direct_profiles", "count=${profiles.size}")
        profiles.forEach { log("direct_profile", it.describe()) }
    }
}

@RequiresApi(33)
private fun directFlags(flags: Int): String {
    if (flags == AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED) return "NOT_SUPPORTED"
    val names = mapOf(
        AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED to "OFFLOAD",
        AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED to "OFFLOAD_GAPLESS",
        AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED to "BITSTREAM",
    )
    val known = names.keys.fold(0) { combined, flag -> combined or flag }
    val unknown = flags and known.inv()
    return (names.filterKeys { flags and it != 0 }.values +
        listOfNotNull("UNKNOWN(0x${unknown.toUInt().toString(16)})".takeIf { unknown != 0 })).joinToString("|")
}

@RequiresApi(31)
private fun AudioProfile.describe() =
    "format=${encodingName(format)} encoding=$format rates=${sampleRates.contentToString()} " +
        "channelMasks=${channelMasks.map { "0x${it.toString(16)}" }} " +
        "channelIndexMasks=${channelIndexMasks.map { "0x${it.toString(16)}" }} encapsulation=$encapsulationType"

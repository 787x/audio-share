package io.github.mkckr0.audio_share_app.model

import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mkckr0.audio_share_app.R

internal enum class OutputMode(val label: Int) {
    ORIGINAL(R.string.output_original),
    TRACK_PCM24(R.string.output_track_pcm24),
    TRACK_PCM24_PREFER_A2DP(R.string.output_track_pcm24_a2dp),
    AAUDIO_EXCLUSIVE(R.string.output_aaudio);

    companion object {
        val key = stringPreferencesKey("output_mode")
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

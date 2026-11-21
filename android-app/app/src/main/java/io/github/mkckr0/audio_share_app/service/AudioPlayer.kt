/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.mkckr0.audio_share_app.service

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.Commands
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures.immediateVoidFuture
import com.google.common.util.concurrent.ListenableFuture
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.model.NetworkConfigKeys
import io.github.mkckr0.audio_share_app.model.getInteger
import io.github.mkckr0.audio_share_app.model.getResourceUri
import io.github.mkckr0.audio_share_app.model.networkConfigDataStore
import io.github.mkckr0.audio_share_app.pb.Client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class AudioPlayer(val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val tag = AudioPlayer::class.simpleName

    private var _initState: State = State.Builder()
        .setAvailableCommands(
            Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_STOP,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_METADATA,
                    COMMAND_RELEASE,
                )
                .build()
        )
        .build()
    private var _state: State = _initState
    override fun getState(): State = _state

    private val scope: CoroutineScope = MainScope()
    private val retryScope: CoroutineScope = MainScope()
    private val netClient = NetClient(context.applicationContext, scope)

    companion object {
        var message by mutableStateOf("")
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        return future {
            Log.d(tag, "handleSetPlayWhenReady playWhenReady=$playWhenReady")
            _state = state.buildUpon().setPlayerError(null).build()
            if (playWhenReady) {
                val networkConfig = context.networkConfigDataStore.data.first()
                val host = networkConfig[stringPreferencesKey(NetworkConfigKeys.HOST)]
                    ?: context.getString(R.string.default_host)
                val port = networkConfig[intPreferencesKey(NetworkConfigKeys.PORT)]
                    ?: context.getInteger(R.integer.default_port)

                val mediaItem = MediaItem.fromUri("tcp://$host:$port").buildUpon()
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Audio Share")
                            .setArtist("$host:$port")
                            .setArtworkUri(context.getResourceUri(R.drawable.artwork))
                            .build()
                    )
                    .build()

                _state = state.buildUpon()
                    .setPlaylist(
                        listOf(
                            MediaItemData.Builder("media-1")
                                .setMediaItem(mediaItem)
                                .build()
                        )
                    )
                    .setCurrentMediaItemIndex(0)
                    .setPlaybackState(Player.STATE_BUFFERING)
                    .setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .build()

                netClient.start(
                    host = host,
                    port = port,
                    callback = NetClientCallBack()
                )
            } else {
                _state = state.buildUpon()
                    .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .build()
                netClient.stop()
                retryScope.coroutineContext.cancelChildren()
                message = context.getString(R.string.label_paused)
            }
        }
    }

    override fun handleStop(): ListenableFuture<*> {
        Log.d(tag, "handleStop")
        _state = _initState.buildUpon()
            .setPlaybackState(STATE_IDLE)
            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        netClient.stop()
        retryScope.coroutineContext.cancelChildren()
        message = context.getString(R.string.label_stopped)
        return immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        Log.d(tag, "handleRelease")
        scope.cancel()
        netClient.stop()
        retryScope.cancel()
        _state = State.Builder().build()
        return immediateVoidFuture()
    }

    inner class NetClientCallBack : NetClient.Callback {
        private val tag = NetClientCallBack::class.simpleName

        private val output = PlaybackOutput(context.applicationContext)

        override fun log(message: String) {
            AudioPlayer.message = message
        }

        override suspend fun onReceiveAudioFormat(format: Client.AudioFormat) {
            output.start(format)
        }

        override fun onPlaybackClosed() {
            output.close()
        }

        override fun onPlaybackStarted() {
            _state = state.buildUpon()
                .setPlaybackState(STATE_READY)
                .build()
            invalidateState()
            Log.d(tag, "onPlaybackStarted")
            message = context.getString(R.string.label_started)
        }

        override suspend fun onReceiveAudioData(audioData: ByteBuffer) {
            output.write(audioData)
        }

        override fun onError(message: String?, cause: Throwable?) {
            // The disconnected session has already released its output.
            retryScope.launch {

                netClient.stop()

                val reason = message ?: cause?.stackTraceToString()
                var wait = 3
                while (wait > 0) {
                    log("$reason, ${context.getString(R.string.label_retry).format(wait)}")
                    delay(1.seconds)
                    --wait
                }

                _state = state.buildUpon()
                    .setPlayerError(null)
                    .setPlaybackState(Player.STATE_BUFFERING)
                    .build()
                invalidateState()

                val networkConfig = context.networkConfigDataStore.data.first()
                val host = networkConfig[stringPreferencesKey(NetworkConfigKeys.HOST)]
                    ?: context.getString(R.string.default_host)
                val port = networkConfig[intPreferencesKey(NetworkConfigKeys.PORT)]
                    ?: context.getInteger(R.integer.default_port)
                netClient.start(
                    host = host,
                    port = port,
                    callback = NetClientCallBack()
                )
            }
        }
    }

    /**
     * All exceptions in ListenableFuture will be suppressed, need log it
     */
    private fun future(block: suspend CoroutineScope.() -> Unit): ListenableFuture<*> {
        return scope.future {
            try {
                block()
            } catch (e: Exception) {
                Log.e(tag, e.stackTraceToString())
            }
        }
    }
}
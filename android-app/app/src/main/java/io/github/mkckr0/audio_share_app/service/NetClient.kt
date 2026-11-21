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
import android.util.Log
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.pb.Client.AudioFormat
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.toJavaAddress
import io.ktor.util.network.address
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class NetClient(private val context: Context, private val scope: CoroutineScope) {
    private val connectionLock = Mutex()
    private var connection: Job? = null

    enum class CMD {
        CMD_NONE,
        CMD_GET_FORMAT,
        CMD_START_PLAY,
        CMD_HEARTBEAT,
    }

    interface Callback {
        fun log(message: String)
        suspend fun onReceiveAudioFormat(format: AudioFormat)
        fun onPlaybackStarted()
        suspend fun onReceiveAudioData(audioData: ByteBuffer)
        fun onPlaybackClosed()
        fun onError(message: String?, cause: Throwable?)
    }

    fun start(host: String, port: Int, callback: Callback) {
        stop()
        connection = scope.launch(Dispatchers.IO) {
            try {
                connectionLock.withLock {
                    try {
                        runConnection(host, port, callback)
                    } finally {
                        callback.onPlaybackClosed()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("NetClient", "Connection failed", error)
                withContext(Dispatchers.Main) { callback.onError(error.message, error) }
            }
        }
    }

    private suspend fun runConnection(host: String, port: Int, callback: Callback) {
        withContext(Dispatchers.Main) {
            callback.log("${context.getString(R.string.label_connecting)} $host:$port")
        }
        SelectorManager(Dispatchers.IO).use { selector ->
            val socket = try {
                withTimeout(3.seconds) { aSocket(selector).tcp().connect(host, port) }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException(context.getString(R.string.label_timeout), e)
            } catch (e: UnresolvedAddressException) {
                throw IllegalStateException(context.getString(R.string.label_unresolved_address), e)
            }
            socket.use { tcp ->
                val read = tcp.openReadChannel()
                val write = tcp.openWriteChannel()
                write.writeCMD(CMD.CMD_GET_FORMAT)
                check(read.readCMD() == CMD.CMD_GET_FORMAT) { "Unexpected format response" }
                callback.onReceiveAudioFormat(checkNotNull(read.readAudioFormat()))

                val id = try {
                    withTimeout(5.seconds) {
                        Log.d("NetClient", "Sending playback request")
                        write.writeCMD(CMD.CMD_START_PLAY)
                        check(read.readCMD() == CMD.CMD_START_PLAY) { "Unexpected start response" }
                        read.readIntLE()
                    }
                } catch (error: TimeoutCancellationException) {
                    throw IllegalStateException("Playback request timed out", error)
                }
                check(id > 0) { "Invalid playback client ID: $id" }

                Log.d("NetClient", "Playback request accepted")
                aSocket(selector).udp().bind(InetSocketAddress(tcp.localAddress.toJavaAddress().address, 0)).use { udp ->
                    udp.writeIntLE(id, InetSocketAddress(host, port))
                    withContext(Dispatchers.Main) { callback.onPlaybackStarted() }
                    coroutineScope {
                        // Check and acknowledge heartbeats on the same coroutine.
                        launch {
                            var lastHeartbeat = TimeSource.Monotonic.markNow()
                            while (true) {
                                val cmd = try {
                                    withTimeout(5.seconds - lastHeartbeat.elapsedNow()) { read.readCMD() }
                                } catch (e: TimeoutCancellationException) {
                                    throw IllegalStateException("heartbeat timeout", e)
                                }
                                if (cmd == CMD.CMD_HEARTBEAT) {
                                    lastHeartbeat = TimeSource.Monotonic.markNow()
                                    write.writeCMD(CMD.CMD_HEARTBEAT)
                                }
                            }
                        }
                        // Consume packets in order and bound buffering to the socket's receive queue.
                        while (true) {
                            callback.onReceiveAudioData(udp.readByteBuffer().order(ByteOrder.LITTLE_ENDIAN))
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        connection?.cancel()
    }
}

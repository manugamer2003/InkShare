package com.manuelperez.inkshare

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper

class WinnerAudio(private val context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var focused = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) stop()
    }
    fun play(position: Int) {
        require(position in 1..4)
        playAsset("sounds/player$position.mp3")
    }
    fun playTimeUp() = playAsset("sounds/GAME.wav")
    @Suppress("DEPRECATION")
    private fun playAsset(path: String) {
        stop()
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper())).build()
            focus = request
            manager.requestAudioFocus(request)
        } else manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        focused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focused) { focus = null; return }
        try {
            val sound = MediaPlayer()
            player = sound
            sound.setAudioAttributes(attributes)
            context.assets.openFd(path).use { file ->
                sound.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            sound.setOnPreparedListener { if (player === it) it.start() }
            sound.setOnCompletionListener { if (player === it) stop() }
            sound.setOnErrorListener { media, _, _ -> if (player === media) stop(); true }
            sound.prepareAsync()
        } catch (e: Exception) {
            android.util.Log.w("InkShareCounter", "No se pudo reproducir el audio $path", e)
            stop()
        }
    }
    @Suppress("DEPRECATION")
    fun stop() {
        val previous = player
        player = null
        previous?.release()
        if (focused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { manager.abandonAudioFocusRequest(it) }
            else manager.abandonAudioFocus(focusListener)
        }
        focus = null
        focused = false
    }
}

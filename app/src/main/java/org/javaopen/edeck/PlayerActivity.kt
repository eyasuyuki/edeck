package org.javaopen.edeck

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private lateinit var mediaUri: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        mediaUri = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.player_title)
        val playerView = PlayerView(this)
        playerView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(playerView)
        playerView.setControllerShowTimeoutMs(CONTROLLER_SHOW_TIMEOUT_MS)
        val isSmb = Uri.parse(mediaUri).scheme.equals("smb", ignoreCase = true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        val mediaDataSourceFactory = if (isSmb) {
            SmbDataSource.Factory(this)
        } else {
            DefaultDataSource.Factory(this, httpDataSourceFactory)
        }
        val builder = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(SKIP_INTERVAL_MS)
            .setSeekForwardIncrementMs(SKIP_INTERVAL_MS)
            .setMediaSourceFactory(DefaultMediaSourceFactory(mediaDataSourceFactory))
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        player = builder.build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(mediaUri)))
            exo.prepare()
            exo.seekTo(preferences.getLong(positionKey(), 0L))
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) preferences.edit().putBoolean(watchedKey(), true).apply()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(
                        this@PlayerActivity,
                        getString(R.string.player_playback_error, error.localizedMessage ?: getString(R.string.network_error)),
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) player?.seekBack()
                else player?.seekForward()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        savePosition()
        player?.pause()
        super.onPause()
    }

    override fun onStop() {
        savePosition()
        super.onStop()
    }

    override fun onDestroy() {
        savePosition()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun savePosition() {
        player?.let { exo ->
            if (exo.currentPosition > 0L) {
                preferences.edit().putLong(positionKey(), exo.currentPosition).apply()
                preferences.edit().putBoolean(watchedKey(), true).apply()
            }
        }
    }

    private fun positionKey() = "position:$mediaUri"
    private fun watchedKey() = "watched:$mediaUri"

    companion object {
        private const val MIN_BUFFER_MS = 60_000
        private const val MAX_BUFFER_MS = 180_000
        private const val BUFFER_FOR_PLAYBACK_MS = 3_000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 8_000
        private const val TARGET_BUFFER_BYTES = 96 * 1024 * 1024
        private const val CONTROLLER_SHOW_TIMEOUT_MS = 2_000
        private const val HTTP_CONNECT_TIMEOUT_MS = 30_000
        private const val HTTP_READ_TIMEOUT_MS = 60_000
        private const val SKIP_INTERVAL_MS = 10_000L

        const val EXTRA_URI = "video_uri"
        const val EXTRA_TITLE = "video_title"
    }
}

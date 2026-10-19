package org.javaopen.edeck

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
        val hideControllerRunnable = Runnable { playerView.hideController() }
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
            // TV のスクリーンセーバー／スリープを再生中は抑止する。
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playerView.removeCallbacks(hideControllerRunnable)
                    if (isPlaying) {
                        preferences.edit().putBoolean(watchedKey(), true).apply()
                        // 復帰シーク後に準備中表示が残っても、再生開始から2秒で閉じる。
                        playerView.postDelayed(hideControllerRunnable, CONTROLLER_SHOW_TIMEOUT_MS.toLong())
                    }
                }
            })
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(mediaUri)))
            exo.prepare()
            val startPosition = if (preferences.getBoolean(completedKey(), false)) {
                0L
            } else {
                preferences.getLong(positionKey(), 0L)
            }
            exo.seekTo(startPosition)
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        preferences.edit()
                            .putBoolean(completedKey(), true)
                            .putLong(positionKey(), 0L)
                            .apply()
                    }
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
        setKeepScreenOn(false)
        savePosition()
        player?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        setKeepScreenOn(true)
    }

    override fun onStop() {
        savePosition()
        super.onStop()
    }

    override fun onDestroy() {
        setKeepScreenOn(false)
        savePosition()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun savePosition() {
        player?.let { exo ->
            if (exo.playbackState == Player.STATE_ENDED) {
                preferences.edit().putLong(positionKey(), 0L).apply()
            } else if (exo.currentPosition > 0L) {
                preferences.edit().putLong(positionKey(), exo.currentPosition).apply()
                preferences.edit().putBoolean(watchedKey(), true).apply()
            }
        }
    }

    private fun setKeepScreenOn(keepScreenOn: Boolean) {
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun positionKey() = "position:$mediaUri"
    private fun watchedKey() = "watched:$mediaUri"
    private fun completedKey() = "completed:$mediaUri"

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

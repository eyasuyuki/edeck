package org.javaopen.edeck

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private lateinit var mediaUri: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaUri = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.player_title)
        val playerView = PlayerView(this)
        playerView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(playerView)
        val isSmb = Uri.parse(mediaUri).scheme.equals("smb", ignoreCase = true)
        val builder = ExoPlayer.Builder(this)
        if (isSmb) {
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory()))
        }
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
            })
        }
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
        const val EXTRA_URI = "video_uri"
        const val EXTRA_TITLE = "video_title"
    }
}

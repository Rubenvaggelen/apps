package com.gmailorg.hub

import android.os.Bundle
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout

class MediaCatalogPlaybackActivity: AppCompatActivity() {
    private var player: ExoPlayer?=null
    private var view: PlayerView?=null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        supportActionBar?.hide()
        enterFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val entry=MediaPlayerCatalog.Entry(intent.getIntExtra("id",0),intent.getStringExtra("title").orEmpty(),
            intent.getBooleanExtra("episode",false),intent.getStringExtra("extension")?:"mp4")
        title=entry.title
        Thread {
            val url=runCatching { MediaPlayerCatalog.stream(this,entry,entry.series) }.getOrNull()
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                if(url==null){Toast.makeText(this,"Geen Mediaplayer-toestemming of verbinding. Vraag toegang via Main.",Toast.LENGTH_LONG).show();finish();return@runOnUiThread}
                val surface=PlayerView(this).apply {
                    resizeMode=AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                setContentView(surface);view=surface
                enterFullscreen()
                val http=DefaultHttpDataSource.Factory()
                    .setUserAgent("TheOne/1.0")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20000)
                    .setReadTimeoutMs(30000)
                player=ExoPlayer.Builder(this)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                    .build().also { p ->
                    surface.player=p
                    p.addListener(object: Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            // Show useful diagnostics without printing the credential-bearing stream URI.
                            val causes=generateSequence<Throwable>(error) { it.cause }.take(12).toList()
                            val status=causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                            val message=when {
                                status!=null -> "De streamserver weigert deze film of aflevering (HTTP "+status+")."
                                error.errorCode==PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "De streamverbinding wordt geblokkeerd. Werk Main bij."
                                error.errorCode==PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Het videoformaat wordt niet ondersteund op dit apparaat."
                                else -> "Afspelen mislukt (code "+error.errorCode+"). Probeer opnieuw."
                            }
                            Toast.makeText(this@MediaCatalogPlaybackActivity,message,Toast.LENGTH_LONG).show()
                        }
                    })
                    p.setMediaItem(MediaItem.fromUri(url));p.prepare();p.playWhenReady=true
                }
                state?.let {player?.seekTo(it.getLong("position",0))}
            }
        }.start()
    }
    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window,false)
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P) {
            window.attributes=window.attributes.apply {
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window,window.decorView).apply {
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus)enterFullscreen()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putLong("position",player?.currentPosition?:0);super.onSaveInstanceState(state)
    }
    override fun onStop(){player?.pause();super.onStop()}
    override fun onDestroy(){view?.player=null;player?.release();player=null;super.onDestroy()}
}

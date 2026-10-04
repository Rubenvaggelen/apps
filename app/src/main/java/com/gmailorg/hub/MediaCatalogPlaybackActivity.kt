package com.gmailorg.hub

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MediaCatalogPlaybackActivity: AppCompatActivity() {
    private var player: ExoPlayer?=null
    private var view: PlayerView?=null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val entry=MediaPlayerCatalog.Entry(intent.getIntExtra("id",0),intent.getStringExtra("title").orEmpty(),
            intent.getBooleanExtra("episode",false),intent.getStringExtra("extension")?:"mp4")
        title=entry.title
        Thread {
            val url=runCatching { MediaPlayerCatalog.stream(this,entry,entry.series) }.getOrNull()
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                if(url==null){Toast.makeText(this,"Geen Mediaplayer-toestemming of verbinding. Vraag toegang via Main.",Toast.LENGTH_LONG).show();finish();return@runOnUiThread}
                val surface=PlayerView(this)
                setContentView(surface);view=surface
                player=ExoPlayer.Builder(this).build().also { p ->
                    surface.player=p
                    p.addListener(object: Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Toast.makeText(this@MediaCatalogPlaybackActivity,"Deze film of aflevering kan momenteel niet worden afgespeeld.",Toast.LENGTH_LONG).show()
                        }
                    })
                    p.setMediaItem(MediaItem.fromUri(url));p.prepare();p.playWhenReady=true
                }
                state?.let {player?.seekTo(it.getLong("position",0))}
            }
        }.start()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putLong("position",player?.currentPosition?:0);super.onSaveInstanceState(state)
    }
    override fun onStop(){player?.pause();super.onStop()}
    override fun onDestroy(){view?.player=null;player?.release();player=null;super.onDestroy()}
}

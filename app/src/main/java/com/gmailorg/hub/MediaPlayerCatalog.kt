package com.gmailorg.hub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Uses the same configured source as the preserved Media Player release. */
object MediaPlayerCatalog {
    data class Entry(val id: Int, val title: String, val series: Boolean, val extension: String = "mp4")
    class AccessRequired(val pending: Boolean): Exception("Toestemming voor Mediaplayer vereist.")
    private fun authorize(context: Context) {
        val status=MainDeviceRegistry.refreshAccess(context,MainDeviceRegistry.ACCESS_MEDIA_PLAYER)
        if(!status.allowed)throw AccessRequired(status.pending)
    }
    private fun source(context: Context): JSONObject {
        val config = context.assets.open("theone_media_source.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        // New installations must never inherit the owner's private IPTV
        // server, username or password from a previously published APK.
        check(config.optString("server").isNotBlank() &&
              config.optString("username").isNotBlank() &&
              config.optString("password").isNotBlank()) {
            "Mediaplayer-bron is nog niet op dit apparaat ingesteld."
        }
        return config
    }
    private fun enc(value: String)=URLEncoder.encode(value,"UTF-8")
    private fun read(context: Context, action: String, extra: String=""): String {
        authorize(context)
        val source=source(context)
        val server=source.getString("server").trimEnd('/')
        val url=server+"/player_api.php?username="+enc(source.getString("username"))+
            "&password="+enc(source.getString("password"))+"&action="+action+extra
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=15000;connection.readTimeout=30000
        try {
            if(connection.responseCode!=200)throw IllegalStateException("Mediaplayer-bron is momenteel niet bereikbaar.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch(e: Exception) {
            // Do not expose provider URLs or credentials through transport errors.
            throw IllegalStateException("Mediaplayer-catalogus ophalen mislukt. Probeer opnieuw.")
        } finally {connection.disconnect()}
    }
    fun search(context: Context, query: String): List<Entry> {
        val needle=query.trim().lowercase()
        if(needle.isBlank())return emptyList()
        val movies=JSONArray(read(context,"get_vod_streams"))
        val series=JSONArray(read(context,"get_series"))
        val results=mutableListOf<Entry>()
        for((array,isSeries) in listOf(movies to false,series to true)) {
            for(i in 0 until array.length()){
                val item=array.optJSONObject(i)?:continue
                val name=item.optString("name")
                val id=item.optInt(if(isSeries)"series_id" else "stream_id",0)
                if(id>0&&name.lowercase().contains(needle))
                    results.add(Entry(id,name,isSeries,item.optString("container_extension","mp4")))
            }
        }
        return results.sortedWith(compareBy<Entry>{!it.title.equals(query,true)}.thenBy{it.title}).take(60)
    }
    fun episodes(context: Context, series: Entry): List<Entry> {
        val json=JSONObject(read(context,"get_series_info","&series_id="+series.id))
        val seasons=json.optJSONObject("episodes")?:return emptyList()
        val entries=mutableListOf<Entry>()
        val keys=seasons.keys().asSequence().toList().sortedBy { it.toIntOrNull()?:0 }
        for(season in keys){
            val list=seasons.optJSONArray(season)?:continue
            for(i in 0 until list.length()){
                val row=list.optJSONObject(i)?:continue
                val id=row.optString("id").toIntOrNull()?:continue
                val episode=row.optInt("episode_num",i+1)
                entries.add(Entry(id,"S"+season+" E"+episode+" • "+row.optString("title",series.title),true,row.optString("container_extension","mp4")))
            }
        }
        return entries
    }
    fun stream(context: Context, entry: Entry, episode: Boolean=false): String {
        authorize(context)
        val config=source(context)
        val extension=entry.extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }?:"mp4"
        require(entry.id>0)
        return config.getString("server").trimEnd('/')+"/"+(if(episode)"series" else "movie")+
            "/"+enc(config.getString("username"))+"/"+enc(config.getString("password"))+"/"+entry.id+"."+extension
    }
}

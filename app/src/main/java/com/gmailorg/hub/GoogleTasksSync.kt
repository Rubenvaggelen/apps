package com.gmailorg.hub

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Haalt open taken op uit Google Tasks (alle lijsten — zowel je gewone
 * "Mijn taken" als de aparte "Reminders"-lijst waar gemigreerde
 * Assistant-herinneringen in staan) en zet nieuwe, nog niet eerder geziene
 * items automatisch op de boodschappenlijst. Zet de taak daarna op
 * "voltooid" in Google Tasks, zodat 'ie niet opnieuw wordt geïmporteerd en
 * ook niet blijft rondslingeren in je Tasks-app.
 */
object GoogleTasksSync {

    private const val TAG = "GoogleTasksSync"
    private const val BASE_URL = "https://tasks.googleapis.com/tasks/v1"
    private const val PREFS = "google_tasks_sync"
    private const val KEY_IMPORTED_IDS = "imported_task_ids"

    /** @return aantal nieuw geïmporteerde items, of -1 bij een fout (bv. geen netwerk of niet gekoppeld). */
    fun syncNow(context: Context): Int {
        val token = GoogleTasksAuth.getAccessTokenBlocking(context) ?: return -1
        ShoppingListStore.init(context.applicationContext)

        return try {
            val listIds = fetchTaskListIds(token)
            val importedIds = getImportedIds(context)
            var newCount = 0

            for (listId in listIds) {
                val tasks = fetchOpenTasks(token, listId)
                for (task in tasks) {
                    if (task.id in importedIds) continue
                    ShoppingListStore.add(task.title)
                    markTaskCompleted(token, listId, task.id)
                    importedIds.add(task.id)
                    newCount++
                }
            }
            saveImportedIds(context, importedIds)
            newCount
        } catch (e: Exception) {
            Log.e(TAG, "Synchroniseren met Google Tasks mislukt", e)
            -1
        }
    }

    private data class RemoteTask(val id: String, val title: String)

    private fun fetchTaskListIds(token: String): List<String> {
        val body = get("$BASE_URL/users/@me/lists", token)
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val ids = mutableListOf<String>()
        for (i in 0 until items.length()) {
            ids.add(items.getJSONObject(i).getString("id"))
        }
        return ids
    }

    private fun fetchOpenTasks(token: String, listId: String): List<RemoteTask> {
        val body = get("$BASE_URL/lists/$listId/tasks?showCompleted=false&showHidden=false", token)
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val tasks = mutableListOf<RemoteTask>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val title = item.optString("title", "").trim()
            if (title.isEmpty()) continue
            tasks.add(RemoteTask(id = item.getString("id"), title = title))
        }
        return tasks
    }

    private fun markTaskCompleted(token: String, listId: String, taskId: String) {
        val url = URL("$BASE_URL/lists/$listId/tasks/$taskId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "PATCH"
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.outputStream.use { it.write("""{"status":"completed"}""".toByteArray()) }
        try {
            connection.inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Taak als voltooid markeren mislukt", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(urlString: String, token: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getImportedIds(context: Context): MutableSet<String> =
        prefs(context).getStringSet(KEY_IMPORTED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun saveImportedIds(context: Context, ids: Set<String>) {
        // Voorkomt dat deze set eindeloos blijft groeien: bewaar alleen de laatste 500.
        val trimmed = if (ids.size > 500) ids.toList().takeLast(500).toSet() else ids
        prefs(context).edit().putStringSet(KEY_IMPORTED_IDS, trimmed).apply()
    }
}

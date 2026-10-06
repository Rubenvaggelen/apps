package com.gmailorg.hub

import android.content.Context
import android.util.Log

/** Nationwide OSM snapshot shipped with Main, independent of runtime map availability. */
object SupermarketOfflineCatalog {
    @Volatile private var loaded: List<Pair<Double, Double>>? = null

    @Synchronized
    fun points(context: Context): List<Pair<Double, Double>> {
        loaded?.let { return it }
        val result = runCatching {
            context.assets.open("supermarkets-nl.json").bufferedReader().use {
                SupermarketLookupParser.parse(it.readText())
            }
        }.onFailure { Log.w("SupermarketCatalog", "Bundled supermarket data unavailable", it) }
            .getOrDefault(emptyList())
        loaded = result
        return result
    }
}

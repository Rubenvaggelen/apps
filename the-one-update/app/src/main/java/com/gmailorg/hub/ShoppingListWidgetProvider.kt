package com.gmailorg.hub

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews

/**
 * Boodschappenlijst-widget voor het startscherm. Toont je lijst alleen
 * zolang je binnen het bereik van een (gevonden) supermarkt-geofence bent —
 * daarbuiten toont hij enkel een "niet in de buurt"-berichtje. De
 * aan/uit-status wordt bijgewerkt door GeofenceBroadcastReceiver zodra je
 * een supermarkt-geofence binnenkomt of verlaat.
 */
class ShoppingListWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val PREFS = "shopping_widget_prefs"
        private const val KEY_NEAR_SUPERMARKET = "near_supermarket"

        fun setNearSupermarket(context: Context, near: Boolean) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NEAR_SUPERMARKET, near).apply()
            updateAllWidgets(context)
        }

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ShoppingListWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                ShoppingListWidgetProvider().onUpdate(context, manager, ids)
            }
        }

        private fun isNear(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NEAR_SUPERMARKET, false)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ShoppingListStore.init(context.applicationContext)
        val near = isNear(context)
        val pendingItems = ShoppingListStore.getAll().filter { !it.done }

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_shopping_list)

            if (!near) {
                views.setViewVisibility(R.id.widgetFarMessage, View.VISIBLE)
                views.setViewVisibility(R.id.widgetItemsContainer, View.GONE)
            } else {
                views.setViewVisibility(R.id.widgetFarMessage, View.GONE)
                views.setViewVisibility(R.id.widgetItemsContainer, View.VISIBLE)
                views.removeAllViews(R.id.widgetItemsContainer)

                if (pendingItems.isEmpty()) {
                    val emptyRow = RemoteViews(context.packageName, R.layout.widget_shopping_item)
                    emptyRow.setTextViewText(R.id.widgetItemText, "Niets meer op je lijst 🎉")
                    views.addView(R.id.widgetItemsContainer, emptyRow)
                }

                pendingItems.take(8).forEach { item ->
                    val row = RemoteViews(context.packageName, R.layout.widget_shopping_item)
                    row.setTextViewText(R.id.widgetItemText, "• ${item.text}")
                    views.addView(R.id.widgetItemsContainer, row)
                }
            }

            val openAppIntent = android.content.Intent(context, HouseholdActivity::class.java)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, openAppIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetItemsContainer, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetFarMessage, pendingIntent)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}

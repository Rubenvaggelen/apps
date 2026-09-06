package com.gmailorg.hub

data class HomeTile(
    val id: String,               // "notifications" | "mail" | package name van een app
    val type: TileType,
    val label: String,
    val packageName: String? = null // alleen voor type APP
)

enum class TileType { NOTIFICATIONS, MAIL, HOUSEHOLD, MOVIES, PARKING, SETTINGS, ASK, APP, ADD_BUTTON }

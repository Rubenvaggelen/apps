package com.gmailorg.hub

data class NotifItem(
    val key: String,           // originele sbn.key, nodig om te wissen/beantwoorden
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val hasReplyAction: Boolean
)

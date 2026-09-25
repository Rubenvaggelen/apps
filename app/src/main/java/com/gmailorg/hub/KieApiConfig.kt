package com.gmailorg.hub

/**
 * KIE API configuration for The One ChatGPT integration.
 *
 * NOTE: The API key is intentionally embedded at the user's request. Any key
 * shipped inside an Android APK can ultimately be extracted by someone who has
 * the APK, so rotate the key if the app is ever shared publicly.
 */
object KieApiConfig {
    const val API_KEY = "a41b286b531a75205bf7611de35742d8"
    const val CHAT_URL = "https://api.kie.ai/gpt-5-2/v1/chat/completions"
}

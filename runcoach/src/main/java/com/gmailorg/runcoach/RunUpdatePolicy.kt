package com.gmailorg.runcoach

object RunUpdatePolicy {
    fun version(tag: String): Int? = Regex("^run-v([0-9]+)$").matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
    fun validUrl(version: Int, url: String): Boolean =
        url == "https://github.com/Rubenvaggelen/apps/releases/download/run-v$version/runcoach-debug.apk"
}


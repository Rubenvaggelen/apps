package com.gmailorg.carradio

import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

/** Best-effort K2401 launcher control. */
object LauncherControl {
    data class Result(val rootAvailable: Boolean, val homeSet: Boolean, val detail: String)

    /**
     * Op standaard Android mag een gewone app de default HOME niet stil wijzigen.
     * Veel K2401-units zijn echter geroot. In dat geval kunnen we via `su` het
     * package-manager commando uitvoeren en The One echt als HOME vastleggen.
     */
    fun tryForceHomeWithRoot(context: Context): Result {
        val component = ComponentName(context, MainActivity::class.java).flattenToShortString()
        val commands = listOf(
            "cmd package set-home-activity --user 0 $component",
            "pm set-home-activity --user 0 $component"
        )
        var rootSeen = false
        var last = ""
        for (cmd in commands) {
            try {
                val p = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                rootSeen = true
                if (!p.waitFor(4, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    last = "root-commando timeout"
                    continue
                }
                val output = p.inputStream.bufferedReader().readText().trim()
                val ok = p.exitValue() == 0
                last = if (output.isBlank()) "exit=${p.exitValue()}" else output.take(180)
                if (ok) return Result(true, true, last)
            } catch (e: Exception) {
                last = e.javaClass.simpleName + ": " + (e.message ?: "")
            }
        }
        return Result(rootSeen, false, last)
    }
}

package com.livingai.app.context

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Real UsageStatsManager integration. Deliberately *polled* (not observed) because Android
 * exposes no push API for foreground-app changes without usage access. The poll is cheap
 * (a short event-log scan) and is only invoked periodically by [ContextEngine], never on a
 * tight loop — see the interval there.
 */
class UsageStatsCollector(context: Context) {

    private val usageStatsManager = context.applicationContext
        .getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /** Returns the package name last brought to the foreground in the given lookback window. */
    fun currentForegroundApp(lookbackMs: Long = 10_000L): String? {
        val end = System.currentTimeMillis()
        val start = end - lookbackMs
        val events = usageStatsManager.queryEvents(start, end)

        var lastForegroundApp: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForegroundApp = event.packageName
            }
        }
        return lastForegroundApp
    }
}

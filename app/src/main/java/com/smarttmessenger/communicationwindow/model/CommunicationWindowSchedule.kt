package com.smarttmessenger.communicationwindow.model

import java.time.ZonedDateTime

/**
 * A single time-based schedule within a window.
 * [start] and [end] are minutes since midnight (0–1439).
 * [daysEnabled] uses ISO day values: 1=Monday … 7=Sunday.
 */
data class CommunicationWindowSchedule(
  val enabled: Boolean = true,
  val start: Int = 9 * 60, // 9:00
  val end: Int = 17 * 60, // 17:00
  val daysEnabled: Set<Int> = setOf(1, 2, 3, 4, 5) // Mon–Fri
) {
  fun isCurrentlyActive(now: ZonedDateTime): Boolean {
    if (!enabled) return false
    if (now.dayOfWeek.value !in daysEnabled) return false

    val currentMinutes = now.hour * 60 + now.minute
    return if (end <= start) {
      // Wraparound (e.g. 22:00–06:00)
      currentMinutes >= start || currentMinutes < end
    } else {
      currentMinutes >= start && currentMinutes < end
    }
  }
}

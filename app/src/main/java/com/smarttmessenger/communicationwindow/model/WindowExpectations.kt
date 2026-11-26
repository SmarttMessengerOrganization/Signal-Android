package com.smarttmessenger.communicationwindow.model

import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R

data class WindowExpectations(
  val checkFrequency: CheckFrequency? = null,
  val usualReplyTime: UsualReplyTime? = null,
  val personalNote: String? = null
) {
  enum class CheckFrequency(@StringRes val label: Int) {
    EVERY_FEW_MINUTES(R.string.CommunicationWindow__every_few_minutes),
    ONCE_AN_HOUR(R.string.CommunicationWindow__once_an_hour),
    EVERY_FEW_HOURS(R.string.CommunicationWindow__every_few_hours),
    ONCE_A_DAY(R.string.CommunicationWindow__once_a_day);

    companion object {
      /** The value the server sends (the enum's name), or null if this build doesn't know it. */
      fun fromServerValue(value: String): CheckFrequency? = entries.firstOrNull { it.name == value }
    }
  }

  enum class UsualReplyTime(@StringRes val label: Int) {
    WITHIN_MINUTES(R.string.CommunicationWindow__within_minutes),
    WITHIN_AN_HOUR(R.string.CommunicationWindow__within_an_hour),
    IN_THE_AFTERNOON(R.string.CommunicationWindow__in_the_afternoon),
    IN_THE_EVENING(R.string.CommunicationWindow__in_the_evening),
    NEXT_DAY(R.string.CommunicationWindow__next_day),
    NEXT_BUSINESS_DAY(R.string.CommunicationWindow__next_business_day);

    companion object {
      /** The value the server sends (the enum's name), or null if this build doesn't know it. */
      fun fromServerValue(value: String): UsualReplyTime? = entries.firstOrNull { it.name == value }
    }
  }
}

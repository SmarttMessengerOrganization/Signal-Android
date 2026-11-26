package com.smarttmessenger.core

/**
 * Signal features that Smartt hides. Signal's code for each one stays in place behind one of these
 * conditions instead of being deleted, so an upstream merge only meets the condition.
 *
 * Stories are hidden by `Stories.isFeatureEnabled()`, which Signal's own code already checks.
 */
object SmarttHiddenFeatures {

  /**
   * The "Donate to Signal" row in Settings. Donations go through Signal's payment processor, which
   * Smartt's server doesn't run. Parental Control takes its place.
   */
  @JvmStatic
  val showsDonateRow: Boolean = false

  /** The camera button on the chat list. The communication window shortcut takes its place. */
  @JvmStatic
  val showsChatListCamera: Boolean = false
}

package com.smarttmessenger.communicationwindow.repository

import com.smarttmessenger.communicationwindow.model.CommunicationWindow

/**
 * The rules that keep the device's windows and the server's in step. Kept apart from
 * [CommunicationWindowsRepository], behind [Store] and [Remote], so they can be unit tested without a
 * device, a database or a network.
 *
 * - A delete is remembered on the device ([Store.addPendingDelete]) until the server confirms it, or
 *   answers that it has no such window. Until then every sync retries it.
 * - A sync pushes pending deletes, then pending changes, then pulls.
 * - A pull mirrors the server, with two exceptions: a window with changes not pushed yet stays as it
 *   is on the device (a new window has to survive a pull that runs before its first push), and a
 *   window whose delete is still pending is not brought back.
 *
 * Every method blocks: call it off the main thread.
 */
class CommunicationWindowSync(
  private val store: Store,
  private val remote: Remote,
  private val log: (String, Throwable?) -> Unit = { _, _ -> }
) {

  /** The device's side. */
  interface Store {
    fun getAll(): List<CommunicationWindow>

    /** Windows changed on the device and not pushed yet. */
    fun getPendingSync(): List<CommunicationWindow>

    fun markSynced(windowId: String)

    /** Writes [window] as it is on the server, unless it has changes not pushed yet. */
    fun upsertFromServerIfNotPending(window: CommunicationWindow)

    /** Removes the window, unless it has changes not pushed yet. */
    fun deleteIfNotPending(windowId: String)

    /** Removes the window and remembers its delete, in one step. */
    fun deleteAndRememberDelete(windowId: String)

    fun getPendingDeletes(): Set<String>

    fun clearPendingDelete(windowId: String)
  }

  /** The server's side. Every method throws when the server can't be reached or refuses. */
  interface Remote {
    fun getWindows(): List<CommunicationWindow>

    fun updateWindow(window: CommunicationWindow)

    /** @return false when the server has no such window, which counts as deleted. */
    fun deleteWindow(windowId: String): Boolean
  }

  /** Removes the window on the device and remembers the delete. Then call [pushDelete]. */
  fun deleteLocally(windowId: String) {
    store.deleteAndRememberDelete(windowId)
  }

  /** @return true when the server no longer has the window, and the pending delete is cleared. */
  fun pushDelete(windowId: String): Boolean {
    return try {
      if (!remote.deleteWindow(windowId)) {
        log("Server had no window $windowId; treating the delete as done", null)
      }
      store.clearPendingDelete(windowId)
      true
    } catch (e: Exception) {
      log("Delete failed for $windowId; will retry on the next sync", e)
      false
    }
  }

  /** @return true when the server accepted the change. */
  fun pushChange(window: CommunicationWindow): Boolean {
    return try {
      remote.updateWindow(window)
      store.markSynced(window.windowId)
      true
    } catch (e: Exception) {
      log("Push failed for ${window.windowId}; will retry on the next sync", e)
      false
    }
  }

  /** Pending deletes, then pending changes. */
  fun pushPending() {
    store.getPendingDeletes().forEach { pushDelete(it) }
    store.getPendingSync().forEach { pushChange(it) }
  }

  /**
   * Makes the device mirror the server, except for windows with changes not pushed yet and windows
   * whose delete is pending. Throws when the server can't be reached, leaving the device as it was.
   */
  fun pull() {
    val serverWindows = remote.getWindows()
    val pendingDeletes = store.getPendingDeletes()
    val serverIds = serverWindows.map { it.windowId }.toSet()

    serverWindows
      .filterNot { pendingDeletes.contains(it.windowId) }
      .forEach { store.upsertFromServerIfNotPending(it) }

    store.getAll()
      .filterNot { serverIds.contains(it.windowId) }
      .forEach { store.deleteIfNotPending(it.windowId) }
  }

  /** Push everything pending, then pull. Throws only if the pull fails. */
  fun syncAll() {
    pushPending()
    pull()
  }
}

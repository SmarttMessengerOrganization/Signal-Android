package com.smarttmessenger.communicationwindow.repository

import android.content.Context
import com.smarttmessenger.communicationwindow.database.SmarttCommunicationWindowsTable
import com.smarttmessenger.communicationwindow.database.SmarttPendingWindowDeletesTable
import com.smarttmessenger.communicationwindow.database.SmarttWindowsObserver
import com.smarttmessenger.communicationwindow.model.CommunicationWindow
import com.smarttmessenger.communicationwindow.network.CommunicationWindowRequest
import com.smarttmessenger.communicationwindow.network.SmarttCommunicationWindowApi
import com.smarttmessenger.communicationwindow.network.WindowMetadataResponse
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * Single source of truth for communication-window data, modeled on NotificationProfilesRepository:
 * **local-first**. Reads come reactively from the local table (via [SmarttWindowsObserver]); writes
 * hit the local table immediately and are pushed to our server in the background (the equivalent of
 * NP's storage-service sync, but against our own Smartt REST API since the server enforces windows).
 *
 * This is why save+navigate is instant and never hangs when the server is unreachable — the UI never
 * blocks on the network. See [[project-communication-window-architecture]].
 */
class CommunicationWindowsRepository(private val context: Context) {

  private val localTable = SmarttCommunicationWindowsTable(context)
  private val pendingDeletesTable = SmarttPendingWindowDeletesTable(context)
  private val api: SmarttCommunicationWindowApi by lazy {
    SmarttCommunicationWindowApi(AppDependencies.signalOkHttpClient)
  }

  private val sync = CommunicationWindowSync(
    store = object : CommunicationWindowSync.Store {
      override fun getAll() = localTable.getAll()
      override fun getPendingSync() = localTable.getPendingSync()
      override fun markSynced(windowId: String) = localTable.markSynced(windowId)
      override fun upsertFromServerIfNotPending(window: CommunicationWindow) = localTable.upsertFromServerIfNotPending(window)
      override fun deleteIfNotPending(windowId: String) = localTable.deleteIfNotPending(windowId)
      override fun deleteAndRememberDelete(windowId: String) = localTable.deleteAndRememberDelete(windowId, pendingDeletesTable)
      override fun getPendingDeletes() = pendingDeletesTable.getAll()
      override fun clearPendingDelete(windowId: String) = pendingDeletesTable.remove(windowId)
    },
    remote = object : CommunicationWindowSync.Remote {
      override fun getWindows() = api.getWindows().map { it.toModel() }
      override fun updateWindow(window: CommunicationWindow) {
        api.updateWindow(window.windowId, CommunicationWindowRequest.from(window))
      }
      override fun deleteWindow(windowId: String) = api.deleteWindow(windowId)
    },
    log = { message, error -> Log.w(TAG, message, error) }
  )

  // --- Reactive reads (mirror NotificationProfilesRepository.getProfiles/getProfile) ---

  fun getWindows(): Flowable<List<CommunicationWindow>> {
    return Observable.create { emitter: ObservableEmitter<List<CommunicationWindow>> ->
      val listener = { emitter.onNext(localTable.getAll()) }
      SmarttWindowsObserver.register(listener)
      emitter.setCancellable { SmarttWindowsObserver.unregister(listener) }
      listener()
    }.subscribeOn(Schedulers.io()).toFlowable(BackpressureStrategy.LATEST)
  }

  fun getWindow(windowId: String): Observable<CommunicationWindow> {
    return Observable.create { emitter: ObservableEmitter<CommunicationWindow> ->
      val listener = {
        val window = localTable.get(windowId)
        if (window != null) emitter.onNext(window) else emitter.onError(NoSuchWindowException(windowId))
      }
      SmarttWindowsObserver.register(listener)
      emitter.setCancellable { SmarttWindowsObserver.unregister(listener) }
      listener()
    }.subscribeOn(Schedulers.io())
  }

  /** Synchronous local read. Caller must be off the main thread. */
  fun getLocalWindowsSync(): List<CommunicationWindow> = localTable.getAll()

  // --- Local-first writes ---

  /** Create or update (upsert). Writes locally and returns immediately; pushes to the server in the
   *  background. The client owns the windowId, so this works for both create and edit. */
  fun save(window: CommunicationWindow): Single<CommunicationWindow> {
    return Single.fromCallable {
      localTable.upsert(window, needsSync = true)
      pushToServer(window)
      window
    }.subscribeOn(Schedulers.io())
  }

  /**
   * Deletes on the device, remembers the delete, and deletes on the server in the background. If the
   * server delete fails, every later sync retries it until the server confirms it (see
   * [CommunicationWindowSync]), so the server stops holding messages for the window.
   */
  fun delete(windowId: String): Completable {
    return Completable.fromAction {
      sync.deleteLocally(windowId)
      SignalExecutors.BOUNDED_IO.execute { sync.pushDelete(windowId) }
    }.subscribeOn(Schedulers.io())
  }

  /** Fire-and-forget push of a single window; clears the needs-sync flag on success. */
  private fun pushToServer(window: CommunicationWindow) {
    SignalExecutors.BOUNDED_IO.execute { sync.pushChange(window) }
  }

  /** Retry every pending delete, then every locally-changed-but-unpushed window. */
  fun syncPending() {
    if (notRegisteredYet()) return
    SignalExecutors.BOUNDED_IO.execute { sync.pushPending() }
  }

  /**
   * Push pending deletes, then local changes, then pull the server's state down. The order matters:
   * pulling first would race the pending pushes. On a fresh install there is nothing to push, so
   * this is also what restores the user's windows after a reinstall or on a new device.
   *
   * Safe to call whenever the window UI is opened; it is cheap and self-limiting.
   */
  fun syncAll() {
    if (notRegisteredYet()) return
    SignalExecutors.BOUNDED_IO.execute {
      runCatching { sync.syncAll() }
        .onFailure { Log.w(TAG, "syncFromServer failed; local windows may be stale", it) }
    }
  }

  /**
   * Every sync route needs credentials. `ApplicationContext` kicks one off at process start, which
   * on a fresh install happens *before* the user registers — those requests would all 401 and the
   * failure would be swallowed, which is exactly why a reinstall used to come back with no windows.
   */
  private fun notRegisteredYet(): Boolean {
    if (SignalStore.account.isRegistered) {
      return false
    }
    Log.d(TAG, "Not registered yet; skipping window sync")
    return true
  }

  /**
   * Make the local table mirror the server (e.g. fresh install / multi-device): windows deleted on
   * another device or on the server are removed here too.
   *
   * Two exceptions (see [CommunicationWindowSync.pull]): windows with unpushed local edits are left
   * alone, because the server copy is older than what [syncPending] is still trying to send (and a
   * new window must survive a pull that runs before its first push); and a window whose delete is
   * still pending isn't brought back.
   */
  fun syncFromServer(): Completable {
    return Completable.fromAction { sync.pull() }.subscribeOn(Schedulers.io())
  }

  /** [recipientServiceId] is the wire form of an ACI (a bare UUID) or a PNI (`PNI:` + UUID). */
  fun getWindowMetadata(recipientServiceId: String): Single<WindowMetadataResponse> {
    return Single.fromCallable { api.getWindowMetadata(recipientServiceId) }.subscribeOn(Schedulers.io())
  }

  class NoSuchWindowException(id: String) : Exception("No window with id $id")

  companion object {
    private val TAG = Log.tag(CommunicationWindowsRepository::class.java)
  }
}

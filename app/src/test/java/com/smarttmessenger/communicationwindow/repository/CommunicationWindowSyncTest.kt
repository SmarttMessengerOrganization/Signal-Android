package com.smarttmessenger.communicationwindow.repository

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.smarttmessenger.communicationwindow.model.CommunicationWindow
import org.junit.Assert.assertThrows
import org.junit.Test

class CommunicationWindowSyncTest {

  private val store = FakeStore()
  private val remote = FakeRemote()
  private val sync = CommunicationWindowSync(store, remote)

  @Test
  fun `a failed delete is remembered and retried by the next sync`() {
    store.put(window("a"), needsSync = false)
    remote.windows["a"] = window("a")
    remote.reachable = false

    sync.deleteLocally("a")
    assertThat(sync.pushDelete("a")).isFalse()

    assertThat(store.windows.keys).isEmpty()
    assertThat(store.pendingDeletes).containsOnly("a")
    assertThat(remote.windows.keys).containsOnly("a")

    remote.reachable = true
    sync.syncAll()

    assertThat(remote.windows.keys).isEmpty()
    assertThat(store.pendingDeletes).isEmpty()
    assertThat(store.windows.keys).isEmpty()
  }

  @Test
  fun `a delete the server answers 404 to is cleared`() {
    store.pendingDeletes.add("gone")

    sync.pushPending()

    assertThat(remote.deleteCalls).containsExactly("gone")
    assertThat(store.pendingDeletes).isEmpty()
  }

  @Test
  fun `syncPending retries pending deletes`() {
    remote.windows["a"] = window("a")
    store.pendingDeletes.add("a")

    sync.pushPending()

    assertThat(remote.windows.keys).isEmpty()
    assertThat(store.pendingDeletes).isEmpty()
  }

  @Test
  fun `a pending delete is not brought back by a pull`() {
    remote.windows["a"] = window("a")
    store.pendingDeletes.add("a")

    sync.pull()

    assertThat(store.windows.keys).isEmpty()
    assertThat(store.pendingDeletes).containsOnly("a")
  }

  @Test
  fun `a pending delete that still fails is not brought back by a full sync`() {
    remote.windows["a"] = window("a")
    remote.failDeletes = true
    store.pendingDeletes.add("a")

    sync.syncAll()

    assertThat(store.windows.keys).isEmpty()
    assertThat(store.pendingDeletes).containsOnly("a")
  }

  @Test
  fun `a window deleted elsewhere is removed by a pull`() {
    store.put(window("a"), needsSync = false)
    store.put(window("b"), needsSync = false)
    remote.windows["b"] = window("b")

    sync.pull()

    assertThat(store.windows.keys).containsOnly("b")
  }

  @Test
  fun `an unpushed window survives a pull`() {
    store.put(window("new"), needsSync = true)
    remote.reachable = true
    remote.failUpdates = true

    sync.syncAll()

    assertThat(store.windows.keys).containsOnly("new")
    assertThat(store.windows["new"]!!.needsSync).isTrue()
  }

  @Test
  fun `an unpushed edit is not overwritten by a pull`() {
    store.put(window("a", name = "mine"), needsSync = true)
    remote.windows["a"] = window("a", name = "server")
    remote.failUpdates = true

    sync.pull()

    assertThat(store.windows["a"]!!.name).isEqualTo("mine")
  }

  @Test
  fun `a pull brings windows created elsewhere and updates synced ones`() {
    store.put(window("a", name = "old"), needsSync = false)
    remote.windows["a"] = window("a", name = "new")
    remote.windows["b"] = window("b")

    sync.pull()

    assertThat(store.windows.keys).containsOnly("a", "b")
    assertThat(store.windows["a"]!!.name).isEqualTo("new")
  }

  @Test
  fun `a full sync pushes deletes, then changes, then pulls`() {
    store.pendingDeletes.add("d")
    store.put(window("c"), needsSync = true)
    remote.windows["d"] = window("d")

    sync.syncAll()

    assertThat(remote.calls).containsExactly("delete d", "update c", "get")
    assertThat(store.windows.keys).containsOnly("c")
    assertThat(store.windows["c"]!!.needsSync).isFalse()
  }

  @Test
  fun `a pull that can't reach the server leaves the device as it was`() {
    store.put(window("a"), needsSync = false)
    remote.reachable = false

    assertThrows(Exception::class.java) { sync.pull() }

    assertThat(store.windows.keys).containsOnly("a")
  }

  private fun window(id: String, name: String = id) = CommunicationWindow(windowId = id, name = name)

  private class FakeStore : CommunicationWindowSync.Store {
    val windows = linkedMapOf<String, CommunicationWindow>()
    val pendingDeletes = linkedSetOf<String>()

    fun put(window: CommunicationWindow, needsSync: Boolean) {
      windows[window.windowId] = window.copy(needsSync = needsSync)
    }

    override fun getAll() = windows.values.toList()
    override fun getPendingSync() = windows.values.filter { it.needsSync }
    override fun markSynced(windowId: String) {
      windows[windowId]?.let { windows[windowId] = it.copy(needsSync = false) }
    }
    override fun upsertFromServerIfNotPending(window: CommunicationWindow) {
      if (windows[window.windowId]?.needsSync != true) put(window, needsSync = false)
    }
    override fun deleteIfNotPending(windowId: String) {
      if (windows[windowId]?.needsSync == false) windows.remove(windowId)
    }
    override fun deleteAndRememberDelete(windowId: String) {
      windows.remove(windowId)
      pendingDeletes.add(windowId)
    }
    override fun getPendingDeletes() = pendingDeletes.toSet()
    override fun clearPendingDelete(windowId: String) {
      pendingDeletes.remove(windowId)
    }
  }

  private class FakeRemote : CommunicationWindowSync.Remote {
    val windows = linkedMapOf<String, CommunicationWindow>()
    val calls = mutableListOf<String>()
    val deleteCalls = mutableListOf<String>()
    var reachable = true
    var failUpdates = false
    var failDeletes = false

    override fun getWindows(): List<CommunicationWindow> {
      calls += "get"
      if (!reachable) throw Exception("offline")
      return windows.values.toList()
    }

    override fun updateWindow(window: CommunicationWindow) {
      calls += "update ${window.windowId}"
      if (!reachable || failUpdates) throw Exception("offline")
      windows[window.windowId] = window.copy(needsSync = false)
    }

    override fun deleteWindow(windowId: String): Boolean {
      calls += "delete $windowId"
      deleteCalls += windowId
      if (!reachable || failDeletes) throw Exception("offline")
      return windows.remove(windowId) != null
    }
  }
}

package com.smarttmessenger.communicationwindow.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * IDs of windows deleted on the device whose delete the server hasn't confirmed yet. Every sync
 * retries them, and a pull doesn't bring them back.
 */
class SmarttPendingWindowDeletesTable(private val context: Context) {

  private val db get() = SmarttDatabase.getInstance(context).writableDatabase

  fun getAll(): Set<String> {
    val result = mutableSetOf<String>()
    db.query(TABLE_NAME, arrayOf(COL_WINDOW_ID), null, null, null, null, null).use { cursor ->
      while (cursor.moveToNext()) result.add(cursor.getString(0))
    }
    return result
  }

  /** Inside a transaction when it goes with removing the window itself. */
  fun add(db: SQLiteDatabase, windowId: String) {
    val values = ContentValues().apply { put(COL_WINDOW_ID, windowId) }
    db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE)
  }

  fun remove(windowId: String) {
    db.delete(TABLE_NAME, "$COL_WINDOW_ID = ?", arrayOf(windowId))
  }

  companion object {
    const val TABLE_NAME = "smartt_pending_window_deletes"
    const val COL_WINDOW_ID = "window_id"

    const val CREATE_TABLE = """
      CREATE TABLE IF NOT EXISTS $TABLE_NAME (
        $COL_WINDOW_ID TEXT PRIMARY KEY NOT NULL
      )
    """
  }
}

package com.zetta.expensetracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE transactions (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              date TEXT NOT NULL,
              value_date TEXT,
              amount_cents INTEGER NOT NULL,
              type INTEGER NOT NULL,
              category TEXT NOT NULL DEFAULT 'Altro',
              merchant TEXT NOT NULL DEFAULT '',
              note TEXT NOT NULL DEFAULT '',
              source TEXT NOT NULL DEFAULT '',
              raw_desc TEXT NOT NULL DEFAULT '',
              category_manual INTEGER NOT NULL DEFAULT 0,
              fingerprint TEXT NOT NULL,
              possible_dup INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX idx_tx_fingerprint ON transactions(fingerprint)")
        db.execSQL("CREATE INDEX idx_tx_date ON transactions(date)")
        db.execSQL("CREATE INDEX idx_tx_lookup ON transactions(date, amount_cents, type, merchant)")

        db.execSQL(
            """
            CREATE TABLE imports (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              file_name TEXT NOT NULL,
              imported_at INTEGER NOT NULL,
              total_rows INTEGER NOT NULL,
              added INTEGER NOT NULL,
              skipped INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    // Migrations keep the rows: the imported statements are the whole point of the app.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addColumnIfMissing(db, "transactions", "raw_desc", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "transactions", "category_manual", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, decl: String) {
        var found = false
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val i = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (i >= 0 && c.getString(i).equals(column, ignoreCase = true)) {
                    found = true
                    break
                }
            }
        }
        if (!found) db.execSQL("ALTER TABLE $table ADD COLUMN $column $decl")
    }

    companion object {
        const val NAME = "expenses.db"
        const val VERSION = 2
    }
}

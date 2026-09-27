package com.zetta.expensetracker.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class TxRepository private constructor(context: Context) {

    private val helper = Db(context.applicationContext)

    private fun db(): SQLiteDatabase = helper.writableDatabase

    fun openWritable(): SQLiteDatabase = helper.writableDatabase

    // ---------------------------------------------------------------- reads

    fun countTx(): Int {
        var n = 0
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM transactions", null).use {
            if (it.moveToFirst()) n = it.getInt(0)
        }
        return n
    }

    fun list(month: String?, type: Int?, search: String?): List<Tx> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (!month.isNullOrEmpty() && month != ALL_MONTHS) {
            where.append(" AND substr(date,1,7)=?")
            args.add(month)
        }
        if (type != null) {
            where.append(" AND type=?")
            args.add(type.toString())
        }
        if (!search.isNullOrBlank()) {
            where.append(" AND (merchant LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\' OR note LIKE ? ESCAPE '\\')")
            val p = "%" + escapeLike(search.trim()) + "%"
            args.add(p)
            args.add(p)
            args.add(p)
        }
        val sql = "SELECT * FROM transactions WHERE $where ORDER BY date DESC, id DESC"
        val out = ArrayList<Tx>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(c.toTx())
        }
        return out
    }

    fun exportAll(): List<Tx> {
        val out = ArrayList<Tx>()
        helper.readableDatabase.rawQuery("SELECT * FROM transactions ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.toTx())
        }
        return out
    }

    fun months(): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT substr(date,1,7) AS m FROM transactions ORDER BY m DESC", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) ?: "") }
        return out
    }

    fun totals(month: String?, type: Int?, search: String?): Totals {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (!month.isNullOrEmpty() && month != ALL_MONTHS) {
            where.append(" AND substr(date,1,7)=?")
            args.add(month)
        }
        if (type != null) {
            where.append(" AND type=?")
            args.add(type.toString())
        }
        if (!search.isNullOrBlank()) {
            where.append(" AND (merchant LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\' OR note LIKE ? ESCAPE '\\')")
            val p = "%" + escapeLike(search.trim()) + "%"
            args.add(p)
            args.add(p)
            args.add(p)
        }
        val sql = "SELECT type, SUM(amount_cents) FROM transactions WHERE $where GROUP BY type"
        var e = 0L
        var i = 0L
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(0) == TYPE_EXPENSE) e = c.getLong(1) else i = c.getLong(1)
            }
        }
        return Totals(e, i)
    }

    fun monthStats(limit: Int): List<MonthStat> {
        val out = ArrayList<MonthStat>()
        val sql = "SELECT substr(date,1,7) AS m, type, SUM(amount_cents) FROM transactions " +
            "GROUP BY m, type ORDER BY m DESC LIMIT ?"
        val acc = LinkedHashMap<String, LongArray>()
        helper.readableDatabase.rawQuery(sql, arrayOf((limit * 2).toString())).use { c ->
            while (c.moveToNext()) {
                val m = c.getString(0) ?: continue
                val arr = acc.getOrPut(m) { LongArray(2) }
                arr[c.getInt(1)] = c.getLong(2)
            }
        }
        acc.keys.sortedDescending().take(limit).forEach { m ->
            val a = acc[m] ?: LongArray(2)
            out.add(MonthStat(m, a[TYPE_EXPENSE], a[TYPE_INCOME]))
        }
        return out.reversed()
    }

    fun byCategory(fromMonth: String?, toMonth: String?): List<CatStat> {
        val out = ArrayList<CatStat>()
        val where = StringBuilder("type=?")
        val args = ArrayList<String>()
        args.add(TYPE_EXPENSE.toString())
        if (fromMonth != null) { where.append(" AND substr(date,1,7)>=?"); args.add(fromMonth) }
        if (toMonth != null) { where.append(" AND substr(date,1,7)<=?"); args.add(toMonth) }
        val sql = "SELECT category, SUM(amount_cents) s, COUNT(*) c FROM transactions WHERE $where " +
            "GROUP BY category ORDER BY s DESC"
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(CatStat(c.getString(0) ?: "Altro", c.getLong(1), c.getInt(2)))
            }
        }
        return out
    }

    fun topMerchants(fromMonth: String?, toMonth: String?, limit: Int): List<MerchantStat> {
        val out = ArrayList<MerchantStat>()
        val where = StringBuilder("type=? AND merchant<>''")
        val args = ArrayList<String>()
        args.add(TYPE_EXPENSE.toString())
        if (fromMonth != null) { where.append(" AND substr(date,1,7)>=?"); args.add(fromMonth) }
        if (toMonth != null) { where.append(" AND substr(date,1,7)<=?" ); args.add(toMonth) }
        val sql = "SELECT merchant, SUM(amount_cents) s, COUNT(*) c FROM transactions WHERE $where " +
            "GROUP BY merchant ORDER BY s DESC LIMIT $limit"
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(MerchantStat(c.getString(0) ?: "?", c.getLong(1), c.getInt(2)))
            }
        }
        return out
    }

    fun totalsRange(fromMonth: String?, toMonth: String?): Totals {
        var e = 0L
        var i = 0L
        helper.readableDatabase.rawQuery(
            "SELECT type, SUM(amount_cents) FROM transactions " +
                "WHERE substr(date,1,7)>=? AND substr(date,1,7)<=? GROUP BY type",
            arrayOf(fromMonth ?: "0000-00", toMonth ?: "9999-99")
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(0) == TYPE_EXPENSE) e = c.getLong(1) else i = c.getLong(1)
            }
        }
        return Totals(e, i)
    }

    fun countRange(fromMonth: String?, toMonth: String?): Int {
        var n = 0
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM transactions WHERE substr(date,1,7)>=? AND substr(date,1,7)<=?",
            arrayOf(fromMonth ?: "0000-00", toMonth ?: "9999-99")
        ).use { if (it.moveToFirst()) n = it.getInt(0) }
        return n
    }

    fun dayCount(month: String?): Int {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (!month.isNullOrEmpty() && month != ALL_MONTHS) {
            where.append(" AND substr(date,1,7)=?")
            args.add(month)
        }
        var n = 0
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM transactions WHERE $where", args.toTypedArray()
        ).use { if (it.moveToFirst()) n = it.getInt(0) }
        return n
    }

    // --------------------------------------------------------------- writes

    /** @return the new row id, or -1 when a row with the same fingerprint already exists. */
    fun insert(t: Tx): Long = db().insertWithOnConflict(
        "transactions", null, t.toValues(), SQLiteDatabase.CONFLICT_IGNORE
    )

    fun update(t: Tx): Int {
        val d = db()
        val fp = uniqueFingerprint(d, t)
        return d.update(
            "transactions", t.copy(fingerprint = fp).toValues(),
            "id=?", arrayOf(t.id.toString())
        )
    }

    fun delete(id: Long): Int =
        db().delete("transactions", "id=?", arrayOf(id.toString()))

    fun clearAll() {
        db().delete("transactions", null, null)
        db().delete("imports", null, null)
    }

    fun setPossibleDup(id: Long, value: Boolean) {
        val cv = ContentValues()
        cv.put("possible_dup", if (value) 1 else 0)
        db().update("transactions", cv, "id=?", arrayOf(id.toString()))
    }

    /** Re-attribute one operation to another category. */
    /** Expense series for a single category over [fromMonth]..[toMonth], zero-filled. */
    fun monthStatsForCategory(category: String, fromMonth: String, toMonth: String): List<MonthStat> {
        val acc = HashMap<String, Long>()
        helper.readableDatabase.rawQuery(
            "SELECT substr(date,1,7), SUM(amount_cents) FROM transactions " +
                "WHERE type=? AND category=? AND substr(date,1,7)>=? AND substr(date,1,7)<=? GROUP BY 1",
            arrayOf(TYPE_EXPENSE.toString(), category, fromMonth, toMonth)
        ).use { c ->
            while (c.moveToNext()) {
                acc[c.getString(0) ?: continue] = c.getLong(1)
            }
        }
        return Months.range(fromMonth, toMonth).map { key -> MonthStat(key, acc[key] ?: 0L, 0L) }
    }

    /** Every expense category ever seen, alphabetical, so a picker can offer all of them. */
    fun allCategories(): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT category FROM transactions WHERE type=? ORDER BY category",
            arrayOf(TYPE_EXPENSE.toString())
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }

    /** A category chosen by the user is final: it is flagged so a later re-run leaves it alone. */
    fun setCategory(id: Long, category: String): Int {
        val cv = ContentValues()
        cv.put("category", category)
        cv.put("category_manual", 1)
        cv.put("source", "manual")
        return db().update("transactions", cv, "id=?", arrayOf(id.toString()))
    }

    /**
     * Re-runs the automatic attribution on every row. Rows whose category the user changed
     * by hand (category_manual = 1) keep that value.
     */
    fun recategorizeAll(): RecatResult {
        val all = exportAll()
        var changed = 0
        var kept = 0
        val d = db()
        d.beginTransaction()
        try {
            for (t in all) {
                if (t.categoryManual) {
                    kept++
                    continue
                }
                val guess = Categories.guess("", guessText(t), t.type)
                if (guess != t.category) {
                    val cv = ContentValues()
                    cv.put("category", guess)
                    d.update("transactions", cv, "id=?", arrayOf(t.id.toString()))
                    changed++
                }
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return RecatResult(changed, kept, all.size)
    }

    private fun guessText(t: Tx): String =
        t.rawDesc.ifBlank { t.merchant + " " + t.note + " " + t.source }

    /**
     * Month totals for [fromMonth]..[toMonth] (inclusive, "yyyy-MM") as a *complete* series:
     * months without transactions are present with zero, so a whole year always plots 12 bars.
     */
    fun monthStatsInRange(fromMonth: String, toMonth: String): List<MonthStat> {
        val acc = LinkedHashMap<String, LongArray>()
        helper.readableDatabase.rawQuery(
            "SELECT substr(date,1,7), type, SUM(amount_cents) FROM transactions " +
                "WHERE substr(date,1,7)>=? AND substr(date,1,7)<=? GROUP BY 1, 2",
            arrayOf(fromMonth, toMonth)
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val t = c.getInt(1)
                if (t == TYPE_EXPENSE || t == TYPE_INCOME) {
                    acc.getOrPut(key) { LongArray(2) }[t] = c.getLong(2)
                }
            }
        }
        return Months.range(fromMonth, toMonth).map { key ->
            val a = acc[key] ?: LongArray(2)
            MonthStat(key, a[TYPE_EXPENSE], a[TYPE_INCOME])
        }
    }

    /** Categories actually present in the data, so the picker can offer them too. */
    fun usedCategories(): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT category FROM transactions WHERE category<>'' ORDER BY category", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) ?: "Altro") }
        return out
    }

    fun hasSimilar(id: Long, date: String, cents: Int, type: Int, merchant: String): Boolean {
        val sql = "SELECT 1 FROM transactions WHERE date=? AND amount_cents=? AND type=? AND merchant=? AND id<>? LIMIT 1"
        var found = false
        helper.readableDatabase.rawQuery(
            sql, arrayOf(date, cents.toString(), type.toString(), merchant, id.toString())
        ).use { found = it.moveToFirst() }
        return found
    }

    fun logImport(r: ImportReport) {
        val cv = ContentValues()
        cv.put("file_name", r.fileName)
        cv.put("imported_at", System.currentTimeMillis())
        cv.put("total_rows", r.totalRows)
        cv.put("added", r.added)
        cv.put("skipped", r.skippedDup + r.skippedBad)
        db().insert("imports", null, cv)
    }

    fun importLogs(): List<ImportLog> {
        val out = ArrayList<ImportLog>()
        helper.readableDatabase.rawQuery("SELECT * FROM imports ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ImportLog(
                        c.getLong(0), c.getString(1) ?: "?", c.getLong(2),
                        c.getInt(3), c.getInt(4), c.getInt(5)
                    )
                )
            }
        }
        return out
    }

    fun replaceAll(list: List<Tx>) {
        val d = db()
        d.beginTransaction()
        try {
            d.delete("transactions", null, null)
            d.delete("imports", null, null)
            for (t in list) d.insertWithOnConflict("transactions", null, t.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    // --------------------------------------------------------------- helpers

    private fun uniqueFingerprint(d: SQLiteDatabase, t: Tx): String {
        var fp = t.fingerprint
        var n = 1
        while (true) {
            var clash = false
            d.rawQuery(
                "SELECT 1 FROM transactions WHERE fingerprint=? AND id<>? LIMIT 1",
                arrayOf(fp, t.id.toString())
            ).use { clash = it.moveToFirst() }
            if (!clash) return fp
            fp = t.fingerprint + "#" + n
            n++
        }
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun Tx.toValues(): ContentValues = ContentValues().apply {
        put("date", date)
        put("value_date", valueDate)
        put("amount_cents", amountCents)
        put("type", type)
        put("category", category)
        put("merchant", merchant)
        put("note", note)
        put("source", source)
        put("raw_desc", rawDesc)
        put("category_manual", if (categoryManual) 1 else 0)
        put("fingerprint", fingerprint)
        put("possible_dup", if (possibleDup) 1 else 0)
        put("created_at", createdAt)
    }

    private fun Cursor.toTx(): Tx = Tx(
        id = getLong(getColumnIndexOrThrow("id")),
        date = getString(getColumnIndexOrThrow("date")) ?: "",
        valueDate = getString(getColumnIndexOrThrow("value_date")),
        amountCents = getInt(getColumnIndexOrThrow("amount_cents")),
        type = getInt(getColumnIndexOrThrow("type")),
        category = getString(getColumnIndexOrThrow("category")) ?: "Altro",
        merchant = getString(getColumnIndexOrThrow("merchant")) ?: "",
        note = getString(getColumnIndexOrThrow("note")) ?: "",
        source = getString(getColumnIndexOrThrow("source")) ?: "",
        rawDesc = getString(getColumnIndexOrThrow("raw_desc")) ?: "",
        fingerprint = getString(getColumnIndexOrThrow("fingerprint")) ?: "",
        categoryManual = getInt(getColumnIndexOrThrow("category_manual")) == 1,
        possibleDup = getInt(getColumnIndexOrThrow("possible_dup")) == 1,
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )

    companion object {
        const val ALL_MONTHS = "ALL"

        @Volatile
        private var instance: TxRepository? = null

        fun get(context: Context): TxRepository =
            instance ?: synchronized(this) {
                instance ?: TxRepository(context).also { instance = it }
            }
    }
}

package com.zetta.expensetracker.data

import java.util.Calendar
import java.util.Locale

const val TYPE_EXPENSE = 0
const val TYPE_INCOME = 1

data class Tx(
    val id: Long = 0L,
    val date: String,
    val valueDate: String? = null,
    val amountCents: Int,
    val type: Int,
    val category: String,
    val merchant: String,
    val note: String = "",
    val source: String = "",
    val rawDesc: String = "",
    val fingerprint: String,
    val categoryManual: Boolean = false,
    val possibleDup: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isExpense: Boolean get() = type == TYPE_EXPENSE
}

data class RecatResult(val changed: Int, val kept: Int, val total: Int)

data class ImportReport(
    val fileName: String,
    val totalRows: Int = 0,
    val added: Int = 0,
    val skippedDup: Int = 0,
    val skippedBad: Int = 0,
    val expenseCents: Long = 0L,
    val incomeCents: Long = 0L,
    val error: String? = null
)

data class ImportLog(
    val id: Long = 0L,
    val fileName: String,
    val importedAt: Long,
    val totalRows: Int,
    val added: Int,
    val skipped: Int
)

data class MonthStat(val month: String, val expenseCents: Long, val incomeCents: Long)

data class CatStat(val category: String, val cents: Long, val count: Int)

data class MerchantStat(val merchant: String, val cents: Long, val count: Int)

data class Totals(val expenseCents: Long, val incomeCents: Long) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

object Money {
    fun fmt(cents: Long, withSign: Boolean = false): String {
        val neg = cents < 0
        val a = kotlin.math.abs(cents)
        val s = String.format(java.util.Locale.US, "%,d.%02d", a / 100, a % 100)
        val core = "\u20AC$s"
        return when {
            neg && withSign -> "-" + core
            neg -> "-" + core
            withSign -> "+" + core
            else -> core
        }
    }

    /** Short form for chart axes: €1.234 -> "€1,2k", €950 -> "€950". */
    fun fmtCompact(cents: Long): String {
        val neg = cents < 0
        val a = kotlin.math.abs(cents)
        val euros = a / 100
        val core = if (a >= 100000) {
            String.format(java.util.Locale.US, "€%.1fk", euros / 1000.0)
        } else {
            String.format(java.util.Locale.US, "€%d", euros)
        }
        return if (neg) "-" + core else core
    }

    fun parseItalian(raw: String): Long? {
        var s = raw.trim().replace("\u00A0", "").replace(" ", "")
        if (s.isEmpty()) return null
        var sign = 1L
        if (s.startsWith("-")) {
            sign = -1
            s = s.substring(1)
        } else if (s.startsWith("+")) {
            s = s.substring(1)
        }
        s = s.replace(".", "").replace(",", ".")
        if (s.isEmpty()) return null
        val d = s.toDoubleOrNull() ?: return null
        return Math.round(d * 100.0) * sign
    }

    /** Parse an amount typed by a user: accepts 12,34 / 12.34 / 1.234,56 / 1234 */
    fun parseUser(raw: String): Long? {
        val s = raw.trim().replace("\u00A0", "").replace(" ", "")
        if (s.isEmpty()) return null
        if (!s.contains(',')) {
            val m = Regex("^[+-]?\\d+\\.\\d{1,2}$").matches(s)
            if (m) {
                val d = s.toDoubleOrNull() ?: return null
                return Math.round(d * 100.0)
            }
        }
        return parseItalian(s)
    }

    /** Convert an ISO yyyy-MM-dd (or yyyy/MM/dd) to a display date dd/MM/yyyy. */
    fun prettyDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "-"
        val p = iso.replace("/", "-").split("-")
        if (p.size != 3) return iso
        return "${p[2]}/${p[1]}/${p[0]}"
    }

    fun monthLabel(isoMonth: String): String {
        val p = isoMonth.split("-")
        if (p.size != 2) return isoMonth
        val names = arrayOf(
            "gen", "feb", "mar", "apr", "mag", "giu",
            "lug", "ago", "set", "ott", "nov", "dic"
        )
        val m = p[1].toIntOrNull() ?: return isoMonth
        val name = if (m in 1..12) names[m - 1] else "?"
        return "$name ${p[0]}"
    }
}

/** Helpers for the "yyyy-MM" month keys used by every date query. */
object Months {

    /** Every month from [fromIso] to [toIso] inclusive, ascending. Used to build complete series. */
    fun range(fromIso: String, toIso: String): List<String> {
        val f = parts(fromIso) ?: return emptyList()
        val t = parts(toIso) ?: return emptyList()
        if (f[0] > t[0] || (f[0] == t[0] && f[1] > t[1])) return emptyList()
        val out = ArrayList<String>()
        var y = f[0]
        var m = f[1]
        while (y < t[0] || (y == t[0] && m <= t[1])) {
            out.add(String.format(Locale.US, "%04d-%02d", y, m))
            m++
            if (m > 12) {
                m = 1
                y++
            }
            if (out.size > 600) break
        }
        return out
    }

    fun nowIso(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    fun yearOf(iso: String): Int =
        parts(iso)?.get(0) ?: Calendar.getInstance().get(Calendar.YEAR)

    fun yearStart(year: Int): String = String.format(Locale.US, "%04d-01", year)

    fun yearEnd(year: Int): String = String.format(Locale.US, "%04d-12", year)

    /** [iso] shifted by [delta] months, so shift("2026-01", -1) == "2025-12". */
    fun shift(iso: String, delta: Int): String {
        val p = parts(iso) ?: return iso
        val total = p[0] * 12 + (p[1] - 1) + delta
        return String.format(Locale.US, "%04d-%02d", total / 12, total % 12 + 1)
    }

    private fun parts(iso: String): IntArray? {
        val p = iso.trim().split("-")
        if (p.size != 2) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (m !in 1..12) return null
        return intArrayOf(y, m)
    }
}

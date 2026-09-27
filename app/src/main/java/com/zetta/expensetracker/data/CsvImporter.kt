package com.zetta.expensetracker.data

import android.content.ContentResolver
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Imports Italian bank statement CSV exports.
 *
 * Expected header (delimiter auto-detected, quotes supported):
 *   DATA CONTABILE;DATA VALUTA;USCITE;ENTRATE;CAUSALE;DESCRIZIONE OPERAZIONE
 *
 * Duplicate avoidance: every row is reduced to a stable SHA-256 fingerprint built from
 * the booking date, value date, signed amount, causale and description. The fingerprint
 * is a UNIQUE column, so re-importing the same statement (or an export that overlaps an
 * earlier one) silently skips operations already stored instead of creating copies.
 */
class CsvImporter(private val repo: TxRepository) {

    fun import(uri: Uri, resolver: ContentResolver, displayName: String): ImportReport {
        return try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return ImportReport(displayName, error = "Impossibile leggere il file")
            importText(decode(bytes), displayName)
        } catch (e: Exception) {
            ImportReport(displayName, error = e.message ?: "Errore sconosciuto")
        }
    }

    fun importText(text: String, fileName: String): ImportReport {
        val lines = text.split("\n")
        if (lines.isEmpty()) return ImportReport(fileName, error = "File vuoto")

        val delim = detectDelimiter(lines)
        var idxDate = 0
        var idxValue = 1
        var idxOut = 2
        var idxIn = 3
        var idxCausale = 4
        var idxDesc = 5
        var singleAmount = -1
        var headerFound = false

        for (i in lines.indices) {
            val raw = lines[i].trim()
            if (raw.isEmpty()) continue
            val fields = splitLine(raw, delim)
            if (looksLikeHeader(fields)) {
                val map = HashMap<String, Int>()
                fields.forEachIndexed { n, f -> map[normalizeHeader(f)] = n }
                idxDate = map["data contabile"] ?: map["data"] ?: 0
                idxValue = map["data valuta"] ?: map["valuta"] ?: -1
                idxOut = map["uscite"] ?: map["uscita"] ?: -1
                idxIn = map["entrate"] ?: map["entrata"] ?: -1
                idxCausale = map["causale"] ?: 4
                idxDesc = map["descrizione operazione"] ?: map["descrizione"] ?: 5
                singleAmount = map["importo"] ?: map["ammontare"] ?: map["valore"] ?: -1
                headerFound = true
                break
            }
            if (headerFound) break
        }

        var total = 0
        var added = 0
        var dup = 0
        var bad = 0
        var expCents = 0L
        var incCents = 0L
        val start = if (headerFound) 1 else 0
        val db = repo.openWritable()
        db.beginTransaction()
        try {
            for (i in start until lines.size) {
                val raw = lines[i].trim()
                if (raw.isEmpty()) continue
                val f = splitLine(raw, delim)
                val causale = f.getOrEmpty(idxCausale).trim()
                val desc = f.getOrEmpty(idxDesc).trim()
                if (isNonTransaction(causale, desc)) {
                    bad++
                    continue
                }
                val date = parseDate(f.getOrEmpty(idxDate)) ?: parseDate(f.getOrEmpty(idxValue))
                if (date == null) {
                    bad++
                    continue
                }
                val valueDate = parseDate(f.getOrEmpty(idxValue))

                var type = -1
                var cents = 0L
                val outRaw = if (idxOut >= 0) f.getOrEmpty(idxOut) else ""
                val inRaw = if (idxIn >= 0) f.getOrEmpty(idxIn) else ""
                val signed = if (singleAmount >= 0) Money.parseItalian(f.getOrEmpty(singleAmount)) else null
                if (outRaw.isNotBlank()) {
                    val v = Money.parseItalian(outRaw)
                    if (v != null && v != 0L) {
                        type = TYPE_EXPENSE
                        cents = abs(v)
                    }
                }
                if (type == -1 && inRaw.isNotBlank()) {
                    val v = Money.parseItalian(inRaw)
                    if (v != null && v != 0L) {
                        type = TYPE_INCOME
                        cents = abs(v)
                    }
                }
                if (type == -1 && signed != null && signed != 0L) {
                    type = if (signed < 0) TYPE_EXPENSE else TYPE_INCOME
                    cents = abs(signed)
                }
                if (type == -1) {
                    bad++
                    continue
                }

                total++
                if (type == TYPE_EXPENSE) expCents += cents else incCents += cents

                val merchant = extractMerchant(desc, causale)
                val note = extractNote(desc)
                val tx = Tx(
                    date = date,
                    valueDate = valueDate,
                    amountCents = cents.toInt(),
                    type = type,
                    category = Categories.guess("$causale $desc", merchant, type),
                    merchant = merchant,
                    note = note,
                    source = "csv:$fileName",
                    rawDesc = desc,
                    fingerprint = fingerprint(date, valueDate, cents, type, causale, desc)
                )
                val id = repo.insert(tx)
                if (id == -1L) {
                    dup++
                } else {
                    added++
                    if (repo.hasSimilar(id, date, cents.toInt(), type, merchant)) {
                        repo.setPossibleDup(id, true)
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val report = ImportReport(fileName, total, added, dup, bad, expCents, incCents)
        if (added > 0) repo.logImport(report)
        return report
    }

    // ------------------------------------------------------------- parsing

    private fun detectDelimiter(lines: List<String>): Char {
        for (l in lines) {
            if (l.isBlank()) continue
            val semi = l.count { it == ';' }
            val comma = l.count { it == ',' }
            if (semi > 0 && semi >= comma) return ';'
            if (comma > 0) return ','
        }
        return ';'
    }

    private fun looksLikeHeader(fields: List<String>): Boolean {
        if (fields.isEmpty()) return false
        val h = normalizeHeader(fields[0])
        return h == "data contabile" || h == "data" || h == "data operazione" ||
            h.contains("data contabile") || (h.startsWith("data") && fields.size >= 4)
    }

    private fun normalizeHeader(s: String): String =
        s.trim().trim('"').lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun splitLine(line: String, delim: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else inQuotes = false
                } else sb.append(c)
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    delim -> {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun isNonTransaction(causale: String, desc: String): Boolean {
        val h = "$causale $desc".uppercase(Locale.ROOT)
        return h.contains("SALDO INIZIALE") || h.contains("SALDO PRECEDENTE") ||
            h.contains("SALDO FINALE") || h.contains("TOTALIZZAZIONE") ||
            h.contains("LIQUIDAZIONE") || h.contains("PROVVISTA")
    }

    private fun parseDate(raw: String): String? {
        val s = raw.trim().replace('-', '/')
        if (s.length < 8) return null
        val patterns = arrayOf("dd/MM/yyyy", "dd/MM/yy", "dd/MM/yyyyy")
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.ITALY)
                f.isLenient = false
                val d = f.parse(s) ?: continue
                val c = java.util.Calendar.getInstance()
                c.time = d
                return String.format(
                    Locale.US, "%04d-%02d-%02d",
                    c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH)
                )
            } catch (e: Exception) {
                // try next pattern
            }
        }
        return null
    }

    private fun extractNote(desc: String): String {
        val i = desc.uppercase(Locale.ROOT).lastIndexOf("NOTE:")
        if (i < 0) return ""
        var n = desc.substring(i + 5).trim().trim('"').trim()
        if (n.length > 120) n = n.substring(0, 120)
        return n
    }

    private fun extractMerchant(desc: String, causale: String): String {
        val upper = desc.uppercase(Locale.ROOT)

        if (upper.contains("STIPENDIO") || upper.contains("PENSIONE")) {
            return "Stipendio"
        }
        if (upper.contains("RUBINCA RICARICA") || (upper.contains("RICARICA") && upper.contains("CARTA"))) {
            return "Ricarica carta prepagata"
        }
        if (upper.contains("IMPOSTA DI BOLLO") || upper.contains("BOLLI GOVERNATIVI")) {
            return "Imposta di bollo"
        }

        val presso = Regex("presso\\s+(.+?)(?:\\s+-\\s+-\\s+.*|\\s+-\\s+Transazione.*|$)", RegexOption.IGNORE_CASE)
        val m1 = presso.find(desc)
        if (m1 != null) {
            val v = m1.groupValues[1].trim()
            if (v.isNotEmpty()) return tidy(v)
        }

        val favore = Regex("A favore di\\s+(.+?)(?:\\s+IBAN|\\s+Codice|\\s+Note|$)", RegexOption.IGNORE_CASE)
        val m2 = favore.find(desc)
        if (m2 != null) {
            val v = m2.groupValues[1].trim()
            if (v.isNotEmpty()) return tidy(v)
        }

        if (upper.contains("ACCREDITO") || upper.contains("BONIFICO") || upper.contains("PRELIEVO")) {
            val note = extractNote(desc)
            if (note.isNotEmpty()) return tidy(note)
            return tidy(causale.ifBlank { desc })
        }

        var s = desc
        s = Regex("Operazione\\s+Mastercard\\s+del\\s+\\d{2}/\\d{2}/\\d{4}\\s+alle\\s+ore\\s+\\d{1,2}:\\d{2}", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("con Carta\\s+x*[0-9]+", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("Carta\\s+x*[0-9]{4,}", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("Importo in divisa=.*$", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("Div=[A-Z]{3}", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("alle ore\\s+\\d{1,2}:\\d{2}", RegexOption.IGNORE_CASE).replace(s, "")
        s = s.replace(Regex("\\s+"), " ").trim().trim('"').trim()
        if (s.isEmpty()) return tidy(causale.ifBlank { "Operazione" })
        return tidy(s)
    }

    private fun tidy(s: String): String {
        var v = s.replace(Regex("\\s+"), " ").trim()
        if (v.length > 60) v = v.substring(0, 60).trim()
        return v
    }

    // --------------------------------------------------------- fingerprint

    fun fingerprint(
        date: String,
        valueDate: String?,
        cents: Long,
        type: Int,
        causale: String,
        desc: String
    ): String {
        val raw = listOf(
            date,
            valueDate ?: "",
            cents.toString(),
            type.toString(),
            norm(causale),
            norm(desc)
        ).joinToString("|")
        return sha256(raw)
    }

    private fun norm(s: String): String =
        s.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun sha256(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }

    private fun decode(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()
        } catch (e: CharacterCodingException) {
            String(b, Charsets.ISO_8859_1)
        }
    }

    private fun List<String>.getOrEmpty(i: Int): String = if (i in indices) this[i] else ""
}

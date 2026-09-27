package com.zetta.expensetracker.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps a copy of the database in shared storage so the data survives both app
 * updates and a full uninstall/reinstall cycle (plain app-private storage is wiped
 * on uninstall, MediaStore files in Documents are not).
 *
 * On Android 10+ the mirror is written through MediaStore to
 * Documents/ExpenseTracker/expenses-backup.json. On older versions it is written to
 * the app-specific external documents dir, which still survives app updates.
 */
class BackupManager(private val context: Context) {

    private val repo = TxRepository.get(context)
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false

    // ------------------------------------------------------------ mirroring

    fun scheduleMirror() {
        if (pending) return
        pending = true
        handler.postDelayed({
            pending = false
            try {
                mirrorNow()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "mirror failed: ${e.message}")
            }
        }, 1200L)
    }

    fun mirrorNow(): Boolean {
        val json = exportJson()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(json)
        } else {
            writeLegacyFile(json)
        }
    }

    fun autoRestore(): Int {
        if (repo.countTx() > 0) return 0
        val text = readMirror() ?: return 0
        val txs = parseJson(text)
        if (txs.isEmpty()) return 0
        repo.replaceAll(txs)
        return txs.size
    }

    // -------------------------------------------------------------- export

    fun exportJson(): String {
        val arr = JSONArray()
        repo.exportAll().forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("date", t.date)
                    put("valueDate", t.valueDate ?: JSONObject.NULL)
                    put("cents", t.amountCents)
                    put("type", t.type)
                    put("category", t.category)
                    put("merchant", t.merchant)
                    put("note", t.note)
                    put("source", t.source)
                    put("rawDesc", t.rawDesc)
                    put("catManual", if (t.categoryManual) 1 else 0)
                    put("fp", t.fingerprint)
                    put("dup", if (t.possibleDup) 1 else 0)
                    put("createdAt", t.createdAt)
                }
            )
        }
        return JSONObject().apply {
            put("app", "ExpenseTracker")
            put("schema", 1)
            put("exportedAt", System.currentTimeMillis())
            put("count", arr.length())
            put("transactions", arr)
        }.toString(1)
    }

    fun exportTo(uri: Uri): Boolean {
        return try {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use {
                it.write(exportJson().toByteArray(Charsets.UTF_8))
                it.flush()
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "export failed: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------- import

    fun importFrom(uri: Uri): Int {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use {
                String(it.readBytes(), Charsets.UTF_8)
            } ?: return 0
        } catch (e: Exception) {
            return 0
        }
        val txs = parseJson(text)
        repo.replaceAll(txs)
        mirrorNow()
        return txs.size
    }

    private fun parseJson(text: String): List<Tx> {
        val out = ArrayList<Tx>()
        try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("transactions") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val date = o.optString("date")
                if (date.isEmpty()) continue
                val vd = if (o.isNull("valueDate")) null else o.optString("valueDate")
                val cents = o.optLong("cents", 0L)
                if (cents == 0L) continue
                val type = o.optInt("type", TYPE_EXPENSE)
                val merchant = o.optString("merchant")
                val cat = o.optString("category")
                out.add(
                    Tx(
                        date = date,
                        valueDate = vd,
                        amountCents = cents.toInt(),
                        type = type,
                        category = cat.ifBlank { Categories.guess("", merchant, type) },
                        merchant = merchant,
                        note = o.optString("note"),
                        source = o.optString("source"),
                        rawDesc = o.optString("rawDesc"),
                        fingerprint = o.optString("fp").ifBlank {
                            sha256("$date|${vd ?: ""}|$cents|$type")
                        },
                        categoryManual = o.optInt("catManual", 0) == 1,
                        possibleDup = o.optInt("dup", 0) == 1,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "parse failed: ${e.message}")
        }
        return out
    }

    private fun sha256(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(java.util.Locale.US, "%02x", b))
        return sb.toString()
    }

    // --------------------------------------------------------------- sinks

    private fun writeMediaStore(json: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + "/" + DIR_NAME
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val existing = queryExisting(resolver, collection)
        val uri = if (existing != null) {
            resolver.update(existing, values, null, null)
            existing
        } else {
            resolver.insert(collection, values)
        }
        if (uri == null) return false
        resolver.openOutputStream(uri, "wt")?.use {
            it.write(json.toByteArray(Charsets.UTF_8))
            it.flush()
        } ?: return false
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return true
    }

    private fun queryExisting(
        resolver: android.content.ContentResolver,
        collection: Uri
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val args = arrayOf("${Environment.DIRECTORY_DOCUMENTS}/$DIR_NAME", FILE_NAME)
        return try {
            resolver.query(collection, projection, sel, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    android.content.ContentUris.withAppendedId(collection, id)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeLegacyFile(json: String): Boolean {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return false
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, FILE_NAME)
        f.writeText(json, Charsets.UTF_8)
        return true
    }

    private fun readMirror(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = queryExisting(resolver, collection) ?: return null
                resolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null
                val f = File(dir, FILE_NAME)
                if (f.exists()) f.readText(Charsets.UTF_8) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ExpenseBackup"
        const val DIR_NAME = "ExpenseTracker"
        const val FILE_NAME = "expenses-backup.json"
    }
}

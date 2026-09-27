package com.zetta.expensetracker.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zetta.expensetracker.R
import com.zetta.expensetracker.data.Categories
import com.zetta.expensetracker.data.Money
import com.zetta.expensetracker.data.TYPE_EXPENSE
import com.zetta.expensetracker.data.TYPE_INCOME
import com.zetta.expensetracker.data.Tx
import com.zetta.expensetracker.data.TxRepository
import com.zetta.expensetracker.databinding.ActivityAddEditBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddEditActivity : AppCompatActivity() {

    private lateinit var b: ActivityAddEditBinding
    private lateinit var repo: TxRepository
    private var editingId: Long = 0L
    private var original: Tx? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAddEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = TxRepository.get(this)

        b.edtCategory.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, Categories.ALL)
        )

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        if (editingId > 0) {
            b.toolbar.title = getString(R.string.edit_tx)
            loadExisting(editingId)
        } else {
            b.toolbar.title = getString(R.string.add_tx)
            b.edtDate.setText(Money.prettyDate(today()))
            b.edtValueDate.setText(Money.prettyDate(today()))
        }

        b.edtDate.setOnClickListener { pickDate(b.edtDate) }
        b.edtValueDate.setOnClickListener { pickDate(b.edtValueDate) }
        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { delete() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadExisting(id: Long) {
        lifecycleScope.launch {
            val tx = withContext(Dispatchers.IO) {
                repo.list(null, null, null).firstOrNull { it.id == id }
            }
            if (tx == null) {
                finish()
                return@launch
            }
            original = tx
            b.edtAmount.setText(String.format(Locale.US, "%.2f", tx.amountCents / 100.0))
            b.edtDate.setText(Money.prettyDate(tx.date))
            b.edtValueDate.setText(Money.prettyDate(tx.valueDate))
            b.edtMerchant.setText(tx.merchant)
            b.edtNote.setText(tx.note)
            b.edtCategory.setText(tx.category, false)
            if (tx.type == TYPE_INCOME) b.radioIncome.isChecked = true else b.radioExpense.isChecked = true
            b.btnDelete.visibility = android.view.View.VISIBLE
        }
    }

    private fun pickDate(target: android.widget.EditText) {
        val iso = parseIso(target.text.toString()) ?: today()
        val parts = iso.split("-")
        val c = Calendar.getInstance()
        c.set(
            parts[0].toIntOrNull() ?: c.get(Calendar.YEAR),
            (parts.getOrNull(1)?.toIntOrNull() ?: (c.get(Calendar.MONTH) + 1)) - 1,
            parts.getOrNull(2)?.toIntOrNull() ?: c.get(Calendar.DAY_OF_MONTH)
        )
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val s = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                target.setText(Money.prettyDate(s))
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun save() {
        val cents = Money.parseUser(b.edtAmount.text.toString())
        if (cents == null || cents == 0L) {
            b.layoutAmount.error = getString(R.string.err_amount)
            return
        }
        b.layoutAmount.error = null
        val type = if (b.radioIncome.isChecked) TYPE_INCOME else TYPE_EXPENSE
        val date = parseIso(b.edtDate.text.toString()) ?: today()
        val valueDate = parseIso(b.edtValueDate.text.toString())
        val merchant = b.edtMerchant.text.toString().trim()
        val note = b.edtNote.text.toString().trim()
        var category = b.edtCategory.text.toString().trim()
        if (category.isEmpty() || !Categories.ALL.contains(category)) {
            category = Categories.guess("", merchant, type)
        }
        val fingerprint = CsvImporterFingerprint.of(date, valueDate, cents, type, merchant, note)
        // a category the user actually chose wins over any automatic guess, now and in future
        val auto = Categories.guess("", merchant, type)
        val categoryManual = if (editingId > 0) {
            (original?.categoryManual ?: false) || category != (original?.category ?: auto)
        } else {
            category != auto
        }
        val tx = Tx(
            id = editingId,
            date = date,
            valueDate = valueDate,
            amountCents = Math.abs(cents).toInt(),
            type = type,
            category = category,
            merchant = merchant,
            note = note,
            source = if (editingId > 0) (original?.source ?: "manual") else "manual",
            rawDesc = original?.rawDesc ?: "",
            fingerprint = if (editingId > 0 && original != null && original!!.fingerprint.isNotEmpty() &&
                original!!.date == date && original!!.amountCents == Math.abs(cents).toInt() &&
                original!!.type == type
            ) original!!.fingerprint else fingerprint,
            categoryManual = categoryManual,
            possibleDup = original?.possibleDup ?: false,
            createdAt = original?.createdAt ?: System.currentTimeMillis()
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (editingId > 0) repo.update(tx) else repo.insert(tx)
            }
            finish()
        }
    }

    private fun delete() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.delete(editingId) }
            finish()
        }
    }

    private fun today(): String {
        val c = Calendar.getInstance()
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun parseIso(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val p = t.split("/", "-")
        if (p.size != 3) return null
        return try {
            val f = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY)
            f.isLenient = false
            val d = f.parse(t.replace('-', '/')) ?: return null
            val c = Calendar.getInstance()
            c.time = d
            String.format(
                Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val EXTRA_ID = "tx_id"
    }
}

private object CsvImporterFingerprint {
    fun of(date: String, valueDate: String?, cents: Long, type: Int, a: String, b: String): String {
        val raw = listOf(
            date, valueDate ?: "", Math.abs(cents).toString(), type.toString(),
            a.trim().uppercase(Locale.ROOT), b.trim().uppercase(Locale.ROOT)
        ).joinToString("|")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (x in bytes) sb.append(String.format(Locale.US, "%02x", x))
        return sb.toString()
    }
}

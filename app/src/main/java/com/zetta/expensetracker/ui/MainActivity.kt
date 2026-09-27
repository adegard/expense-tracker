package com.zetta.expensetracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.zetta.expensetracker.R
import com.zetta.expensetracker.data.BackupManager
import com.zetta.expensetracker.data.Categories
import com.zetta.expensetracker.data.CsvImporter
import com.zetta.expensetracker.data.ImportReport
import com.zetta.expensetracker.data.Money
import com.zetta.expensetracker.data.Tx
import com.zetta.expensetracker.data.TxRepository
import com.zetta.expensetracker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var repo: TxRepository
    private lateinit var backup: BackupManager
    private lateinit var adapter: TxAdapter

    private var month: String = TxRepository.ALL_MONTHS
    private var type: Int? = null
    private var search: String? = null
    private var spinnerTouched = false
    private var refreshJob: Job? = null
    private val monthIsos = ArrayList<String>()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) doImport(uri)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { backup.exportTo(uri) }
                toast(if (ok) getString(R.string.backup_saved) else getString(R.string.backup_failed))
            }
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        repo = TxRepository.get(this)
        backup = BackupManager(this)

        adapter = TxAdapter(
            onClick = { tx ->
                startActivity(
                    Intent(this, AddEditActivity::class.java)
                        .putExtra(AddEditActivity.EXTRA_ID, tx.id)
                )
            },
            onLongClick = { tx -> pickCategory(tx) }
        )
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.fabAdd.setOnClickListener {
            startActivity(Intent(this, AddEditActivity::class.java))
        }
        b.btnImport.setOnClickListener { startImport() }
        b.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_MONTH, month))
        }

        val typeAdapter = ArrayAdapter.createFromResource(
            this, R.array.filter_types, android.R.layout.simple_spinner_item
        )
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.spinnerType.adapter = typeAdapter
        b.spinnerType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!spinnerTouched) return
                type = when (pos) {
                    1 -> 0
                    2 -> 1
                    else -> null
                }
                refresh()
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        b.spinnerMonth.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        b.spinnerMonth.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!spinnerTouched) return
                // the spinner shows labels ("ago 2026") but the queries need the ISO
                // month ("2026-08") -> map the position back to the ISO value
                month = if (pos <= 0) {
                    TxRepository.ALL_MONTHS
                } else {
                    monthIsos.getOrNull(pos - 1) ?: TxRepository.ALL_MONTHS
                }
                refresh()
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                search = s?.toString()
                refresh()
            }
        })

        b.btnPrev.setOnClickListener { stepMonth(-1) }
        b.btnNext.setOnClickListener { stepMonth(1) }

        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) { backup.autoRestore() }
            if (restored > 0) {
                toast(getString(R.string.restored_n, restored))
            }
            reloadMonths()
            refresh()
        }

        handleIncoming(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        intent = i
        handleIncoming(i)
    }

    /** Accepts "share CSV" / "open with" from a file manager or browser. */
    @Suppress("DEPRECATION")
    private fun handleIncoming(i: Intent?) {
        if (i == null) return
        val uri = when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri != null) {
            intent = Intent()
            doImport(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        reloadMonths()
        refresh()
        backup.scheduleMirror()
    }

    // ------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import -> {
                startImport()
                return true
            }
            R.id.action_stats -> {
                startActivity(Intent(this, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_MONTH, month))
                return true
            }
            R.id.action_export -> {
                exportLauncher.launch(BackupManager.FILE_NAME)
                return true
            }
            R.id.action_restore -> {
                restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                return true
            }
            R.id.action_history -> {
                showHistory()
                return true
            }
            R.id.action_recat -> {
                confirmRecategorizeAll()
                return true
            }
            R.id.action_delete_all -> {
                confirmDeleteAll()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------- data

    private fun reloadMonths() {
        lifecycleScope.launch {
            val months = withContext(Dispatchers.IO) { repo.months() }
            val labels = ArrayList<String>()
            labels.add(getString(R.string.all_months))
            months.forEach { labels.add(Money.monthLabel(it)) }
            monthIsos.clear()
            monthIsos.addAll(months)
            spinnerTouched = false
            b.spinnerMonth.adapter =
                ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
            val idx = if (month == TxRepository.ALL_MONTHS) 0 else months.indexOf(month) + 1
            if (idx in labels.indices) b.spinnerMonth.setSelection(idx, false)
            spinnerTouched = true
        }
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { repo.list(month, type, search) }
            val totals = withContext(Dispatchers.IO) { repo.totals(month, type, search) }
            val days = withContext(Dispatchers.IO) { repo.dayCount(month) }
            adapter.submit(data)
            b.txtExpense.text = Money.fmt(totals.expenseCents)
            b.txtIncome.text = Money.fmt(totals.incomeCents)
            b.txtBalance.text = Money.fmt(totals.balanceCents, withSign = true)
            b.txtCount.text = resources.getQuantityString(R.plurals.tx_count, data.size, data.size)
            val avg = if (days > 0) totals.expenseCents / days else 0L
            b.txtAvg.text = getString(R.string.avg_per_day, Money.fmt(avg))
            b.empty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
            b.recycler.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun stepMonth(dir: Int) {
        val list = b.spinnerMonth.adapter
        if (list == null || list.count <= 1) return
        val cur = b.spinnerMonth.selectedItemPosition
        val next = (cur + dir).coerceIn(0, list.count - 1)
        if (next == cur) return
        b.spinnerMonth.setSelection(next)
    }

    // ----------------------------------------------------------- actions

    private fun startImport() {
        importLauncher.launch(
            arrayOf(
                "text/csv", "text/comma-separated-values", "text/plain",
                "application/csv", "application/vnd.ms-excel", "*/*"
            )
        )
    }

    private fun doImport(uri: android.net.Uri) {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val name = displayName(uri)
            val report = withContext(Dispatchers.IO) {
                CsvImporter(repo).import(uri, contentResolver, name)
            }
            b.progress.visibility = View.GONE
            showReport(report)
            reloadMonths()
            refresh()
            backup.scheduleMirror()
        }
    }

    private fun displayName(uri: android.net.Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment ?: "file.csv"
            } ?: (uri.lastPathSegment ?: "file.csv")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "file.csv"
        }
    }

    private fun showReport(r: ImportReport) {
        if (r.error != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.import_failed))
                .setMessage(r.error)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val msg = StringBuilder()
        msg.append(getString(R.string.import_file, r.fileName)).append("\n\n")
        msg.append("• " + getString(R.string.import_total, r.totalRows) + "\n")
        msg.append("• " + getString(R.string.import_added, r.added) + "\n")
        msg.append("• " + getString(R.string.import_dups, r.skippedDup) + "\n")
        if (r.skippedBad > 0) {
            msg.append("• " + getString(R.string.import_skipped, r.skippedBad) + "\n")
        }
        if (r.added == 0 && r.skippedDup > 0) {
            msg.append("\n").append(getString(R.string.import_already_done))
        }
        if (r.totalRows == 0 && r.skippedBad > 0) {
            msg.append("\n").append(getString(R.string.import_no_rows))
        }
        if (r.totalRows > 0) {
            msg.append("\n")
            msg.append(getString(R.string.import_exp, Money.fmt(r.expenseCents)) + "\n")
            msg.append(getString(R.string.import_inc, Money.fmt(r.incomeCents)))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (r.added > 0) R.string.import_done else R.string.import_nothing_new
            )
            .setMessage(msg.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showHistory() {
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) { repo.importLogs() }
            val txt = if (logs.isEmpty()) getString(R.string.no_imports) else logs.joinToString("\n\n") {
                val d = android.text.format.DateFormat.getDateFormat(this@MainActivity).format(it.importedAt)
                getString(R.string.history_row, it.fileName, d, it.added, it.skipped)
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.action_history)
                .setMessage(txt)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun confirmRestore(uri: android.net.Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_title)
            .setMessage(R.string.restore_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    b.progress.visibility = View.VISIBLE
                    val n = withContext(Dispatchers.IO) { backup.importFrom(uri) }
                    b.progress.visibility = View.GONE
                    month = TxRepository.ALL_MONTHS
                    reloadMonths()
                    refresh()
                    toast(getString(R.string.restored_n, n))
                }
            }
            .show()
    }

    /** Long-press a row: re-attribute it to another category without opening the editor. */
    private fun pickCategory(tx: Tx) {
        lifecycleScope.launch {
            val used = withContext(Dispatchers.IO) { repo.usedCategories() }
            val cats = (Categories.ALL + used).distinct()
            val options = cats + getString(R.string.delete_op)
            val current = cats.indexOf(tx.category)
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.category_pick_title))
                .setSingleChoiceItems(
                    options.toTypedArray(),
                    current,
                    android.content.DialogInterface.OnClickListener { d, which ->
                        d.dismiss()
                        if (which >= cats.size) {
                            confirmDelete(tx.id, tx.merchant)
                        } else {
                            val picked = cats.getOrNull(which)
                            if (picked != null && picked != tx.category) {
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { repo.setCategory(tx.id, picked) }
                                    toast(getString(R.string.category_set, picked))
                                    refresh()
                                    backup.scheduleMirror()
                                }
                            }
                        }
                    }
                )
                .setNeutralButton(R.string.edit_tx) { _, _ ->
                    startActivity(
                        Intent(this@MainActivity, AddEditActivity::class.java)
                            .putExtra(AddEditActivity.EXTRA_ID, tx.id)
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmDelete(id: Long, label: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_msg, label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.delete(id) }
                    refresh()
                    backup.scheduleMirror()
                }
            }
            .show()
    }

    private fun confirmDeleteAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_all_title)
            .setMessage(R.string.delete_all_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.clearAll() }
                    month = TxRepository.ALL_MONTHS
                    reloadMonths()
                    refresh()
                    backup.scheduleMirror()
                }
            }
            .show()
    }

    private fun confirmRecategorizeAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recat_title)
            .setMessage(R.string.recat_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recat_run) { _, _ ->
                b.progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) { repo.recategorizeAll() }
                    b.progress.visibility = View.GONE
                    toast(getString(R.string.recat_done, res.changed, res.kept))
                    refresh()
                    backup.scheduleMirror()
                }
            }
            .show()
    }

    fun notifyChanged() {
        reloadMonths()
        refresh()
        backup.scheduleMirror()
    }

    private fun toast(msg: String) {
        Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
    }
}

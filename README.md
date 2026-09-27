# Expense Tracker

Kotlin Android app that tracks expenses and imports Italian bank statement CSV exports
(e.g. the estratto conto in `Download/1790426789653.csv`).

## Features

- **Expense tracking** — add / edit / delete operations manually, with date, value date,
  merchant, category and note. Long-press a row to delete it.
- **CSV import** — menu → *Importa CSV* (Storage Access Framework, no permissions needed).
  Works with the semicolon format used by the bank:
  `DATA CONTABILE;DATA VALUTA;USCITE;ENTRATE;CAUSALE;DESCRIZIONE OPERAZIONE`
  * delimiter, quote handling, UTF-8/Latin-1 encoding, `+79.566,55` / `-113,90` amounts
    and `dd/MM/yyyy` dates are all parsed automatically;
  * the merchant is extracted from the description (`presso …`, `A favore di …`, `Note: …`,
    bonifico / stipendio / ricarica carte patterns);
  * the category is guessed from the description keyword (Amazon → Online, ENI → Carburante,
    ENEL → Bollette, …);
  * opening-balance / summary rows (`Saldo iniziale`, `Liquidazione`, …) are skipped.
- **No duplicates** — every row is reduced to a SHA-256 fingerprint of
  *(booking date, value date, signed amount, causale, description)* stored in a `UNIQUE`
  column. Re-importing the same statement (or a second export that overlaps the first)
  reports *0 added / N duplicates ignored* instead of creating copies. Two genuinely
  different operations with the same amount and merchant stay separate, and when such a
  pair exists the newer row gets an orange warning icon in the list.
- **Statistics** — per-month income/expense/balance, average per operation, and a range
  switch on the stats screen: the tapped month, *Ultimi 6 mesi*, *Ultimi 12 mesi*,
  *Anno corrente* and *Anno precedente*. A full year always plots 12 bars (months without
  movements are shown as zero), so the current and previous year can be compared at a
  glance. Also expenses by category with amount, share and number of operations, and the
  top 10 merchants.
- **Per-category trend** — a second chart plots the monthly expense of one category, picked from
  a dropdown of all the categories found in the data. It follows the same range switch, and
  shows the total of the period, the average per active month and the peak month.
- **Automatic categories, manual ones respected** — the category is guessed from the
  description at import time; menu → *Ricategorizza tutto* re-runs the attribution on
  every operation, including the ones imported earlier. Any category you changed yourself
  (menu or long-press) is flagged as manual and is never overwritten.
- **Data survives updates and reinstalls** — the SQLite database lives in app storage
  (survives every app update) and is mirrored automatically to
  `Documents/ExpenseTracker/expenses-backup.json` through MediaStore, which is *not*
  deleted on uninstall. On next launch the mirror is restored automatically if the
  database is empty. Manual *Esporta backup* / *Ripristina backup* (SAF) are also available.

## Project layout

```
app/src/main/java/com/zetta/expensetracker/
  data/Models.kt        data classes + Money (parse/format)
  data/Db.kt            SQLiteOpenHelper schema
  data/TxRepository.kt  queries: list, totals, months, categories, top merchants
  data/CsvImporter.kt   CSV parsing, merchant/category extraction, fingerprints
  data/Categories.kt    category list + keyword rules
  data/BackupManager.kt JSON export/import + MediaStore auto-mirror
  ui/MainActivity.kt    list, filters, import, backup menu
  ui/AddEditActivity.kt manual entry / edit
  ui/StatsActivity.kt   statistics screen
  ui/BarChartView.kt    dependency-free bar chart
  ui/TxAdapter.kt       RecyclerView adapter
```

## Build (in Termux, on this device)

The build needs a JDK, which only exists inside Termux — the assistant shell cannot
execute `java`/`aapt2` (SELinux), so the APK must be produced from a Termux session:

```sh
cd /storage/emulated/0/apps/ExpenseTracker
sh build.sh                # = sh ./gradlew assembleDebug
```

`build.sh` discovers the toolchain the same way the working `GentleDawn/build.sh` does on
this device:

| what | where it looks |
|---|---|
| Gradle | `./gradlew` (8.9, required by AGP 8.7.0) |
| JDK | `$JAVA_HOME`, `java` on PATH, `/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk`, `…/java-17-openjdk` |
| SDK | `$ANDROID_HOME`, `/storage/emulated/0/apps/android-sdk`, `/data/data/com.termux/files/home/android-sdk` |

If it stops with `No JDK found`, install one: `pkg install openjdk-17`.

Output: `app/build/outputs/apk/debug/app-debug.apk` (the script prints the path and size).

Install:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to `Download/` and open it on the phone.

## Publish (GitHub + APK release)

`release.sh` creates a new GitHub repository from this folder and publishes the APK as a
release asset. Run it from Termux, where `git` and the network are available:

```sh
pkg install git gh
gh auth login
cd /storage/emulated/0/apps/ExpenseTracker
REPO=expense-tracker VISIBILITY=public sh release.sh
```

It builds, commits, creates `github.com/<user>/$REPO`, pushes `main` and creates the release
`v<versionName>` with `expense-tracker-v<versionName>.apk` attached. Without `gh` it prints
the four manual steps instead. Set `REPO` / `VISIBILITY` to change the name or make it
private.

The attached APK is the **debug** build, which is the one that installs on the phone; a
signed release build would need a keystore (`app/build.gradle.kts`, `signingConfigs`), which
is deliberately not committed.

If the build fails inside aapt2, add this to `~/.gradle/gradle.properties` (as the other
apps in `/apps` do, because the aapt2 binary shipped in the SDK is not runnable in
Termux):

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

Everything else is already on the device: Gradle 8.9, AGP 8.7.0, Kotlin 1.9.24 and all
dependency versions used here are in the on-device caches.

Toolchain: AGP 8.7.0, Kotlin 1.9.24, Gradle 8.9 (wrapper), compileSdk 35, minSdk 24,
viewBinding, no annotation processors, debug-signed release build.

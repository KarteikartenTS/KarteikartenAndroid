// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.tuev

import android.content.Context
import androidx.core.content.edit
import anki.import_export.ImportAnkiPackageOptions
import anki.import_export.ImportAnkiPackageUpdateCondition
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.preferences.sharedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TÜV SÜD Akademie: hält die Karteikarten-Module aktuell.
 *
 * Ablauf je Modul: HEAD-Request auf die Datei, Vergleich mit dem zuletzt importierten Stand
 * (Größe plus ETag, ersatzweise Last-Modified). Bei Abweichung Download in den Cache, Prüfung,
 * Import. Der Import ordnet Notizen über ihre GUID zu, der Lernstand bestehender Karten bleibt.
 *
 * Die Quelle steht in `assets/tuev/content-source.json`. Für den TÜV-SÜD-Server ändert sich nur
 * `contentBaseUrl`.
 */
object ContentUpdater {
    private const val CONFIG_ASSET = "tuev/content-source.json"
    private const val PREF_PREFIX = "tuev_content_signature_"

    private val mutex = Mutex()
    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class Module(
        val id: String,
        val title: String,
        val file: String,
    )

    data class Result(
        /** Titel der Module, die neu importiert oder aktualisiert wurden */
        val updated: List<String>,
        /** Titel der Module, die nicht geprüft oder geladen werden konnten (z. B. offline) */
        val failed: List<String>,
    )

    /** Ob noch nie ein Modul importiert wurde (erster Start). */
    fun isFirstImport(context: Context): Boolean = loadModules(context).second.none { context.sharedPrefs().contains(PREF_PREFIX + it.id) }

    /** Prüft alle Module und importiert geänderte. Läuft nie parallel zu sich selbst. */
    suspend fun updateAll(context: Context): Result =
        mutex.withLock {
            val (baseUrl, modules) = loadModules(context)
            val updated = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (module in modules) {
                try {
                    if (updateModule(context, baseUrl, module)) updated += module.title
                } catch (e: Exception) {
                    Timber.w(e, "Inhalte: Modul %s nicht aktualisiert", module.id)
                    failed += module.title
                }
            }
            Result(updated, failed)
        }

    /** @return true, wenn das Modul importiert wurde */
    private suspend fun updateModule(
        context: Context,
        baseUrl: String,
        module: Module,
    ): Boolean {
        val url = baseUrl + module.file
        val remote = withContext(Dispatchers.IO) { head(url) }
        val prefKey = PREF_PREFIX + module.id
        val known = context.sharedPrefs().getString(prefKey, null)
        if (remote.signature == known) {
            Timber.d("Inhalte: %s unverändert", module.id)
            return false
        }
        Timber.i("Inhalte: %s geändert (%s -> %s), lade", module.id, known, remote.signature)

        val file = withContext(Dispatchers.IO) { download(context, url, module.file, remote.size) }
        try {
            withCol { importAnkiPackage(file.absolutePath, importOptions) }
        } finally {
            file.delete()
        }
        // Erst nach erfolgreichem Import merken, sonst wird beim nächsten Start erneut versucht
        context.sharedPrefs().edit { putString(prefKey, remote.signature) }
        Timber.i("Inhalte: %s importiert", module.id)
        return true
    }

    /**
     * Inhalte der Akademie gelten als maßgeblich: geänderte Notizen werden immer übernommen.
     * Ohne Lernstand aus dem Paket, damit der Lernstand auf dem Gerät erhalten bleibt.
     */
    private val importOptions: ImportAnkiPackageOptions =
        ImportAnkiPackageOptions
            .newBuilder()
            .setMergeNotetypes(true)
            .setUpdateNotes(ImportAnkiPackageUpdateCondition.IMPORT_ANKI_PACKAGE_UPDATE_CONDITION_ALWAYS)
            .setUpdateNotetypes(ImportAnkiPackageUpdateCondition.IMPORT_ANKI_PACKAGE_UPDATE_CONDITION_ALWAYS)
            .setWithScheduling(false)
            .setWithDeckConfigs(false)
            .build()

    private data class RemoteState(
        val size: Long,
        val etag: String?,
        val lastModified: String?,
    ) {
        /** Größe und ETag; nur wenn der Server keinen ETag liefert, Last-Modified. */
        val signature: String get() = "$size|${etag ?: lastModified.orEmpty()}"
    }

    private fun head(url: String): RemoteState {
        val request =
            Request
                .Builder()
                .url(url)
                .head()
                .build()
        client.newCall(request).execute().use { response ->
            // Nach Weiterleitungen zählt die letzte Antwort (die 302 hat Content-Length 0)
            check(response.isSuccessful) { "HEAD $url: HTTP ${response.code}" }
            val size = response.header("Content-Length")?.toLongOrNull() ?: -1L
            check(size > 0) { "HEAD $url: keine Dateigröße" }
            return RemoteState(size, response.header("ETag"), response.header("Last-Modified"))
        }
    }

    private fun download(
        context: Context,
        url: String,
        fileName: String,
        expectedSize: Long,
    ): File {
        val dir = File(context.cacheDir, "tuev-content").apply { mkdirs() }
        val part = File(dir, "$fileName.part")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GET $url: HTTP ${response.code}" }
            response.body.byteStream().use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // Abgebrochene oder kaputte Downloads nie importieren
        check(part.length() == expectedSize) { "Download $fileName unvollständig: ${part.length()} von $expectedSize" }
        val magic = part.inputStream().use { ByteArray(2).also { b -> it.read(b) } }
        check(magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) { "Download $fileName ist kein .apkg" }
        val target = File(dir, fileName)
        target.delete()
        check(part.renameTo(target)) { "Download $fileName konnte nicht abgelegt werden" }
        return target
    }

    private fun loadModules(context: Context): Pair<String, List<Module>> {
        val json =
            JSONObject(
                context.assets
                    .open(CONFIG_ASSET)
                    .bufferedReader()
                    .use { it.readText() },
            )
        val baseUrl = json.getString("contentBaseUrl").let { if (it.endsWith("/")) it else "$it/" }
        val array = json.getJSONArray("modules")
        val modules =
            (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                Module(m.getString("id"), m.getString("title"), m.getString("file"))
            }
        return baseUrl to modules
    }
}

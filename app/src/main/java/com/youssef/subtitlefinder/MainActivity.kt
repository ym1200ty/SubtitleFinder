package com.youssef.subtitlefinder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var apiKeyInput: EditText
    private lateinit var folderLabel: TextView
    private lateinit var logContainer: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private var selectedTree: Uri? = null
    private var selectedLanguage = "BOTH"
    private var searchMode = "AUTO"
    private var currentPage = "HOME"
    private val logEntries = mutableListOf<Pair<String, String>>()
    private val prefs by lazy { getSharedPreferences("subtitle_finder", MODE_PRIVATE) }

    companion object {
        private const val PICK_FOLDER = 101
        private const val BG = 0xFF0A0A0F.toInt()
        private const val CARD = 0xFF121222.toInt()
        private const val PURPLE = 0xFF9B36FF.toInt()
        private const val BLUE = 0xFF00BFFF.toInt()
        private const val WHITE = 0xFFF5F3FF.toInt()
        private const val MUTED = 0xFFAAA6C8.toInt()
        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v", "webm", "mpg", "mpeg", "ts")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTree = savedInstanceState?.getString("tree")?.let(Uri::parse)
        selectedLanguage = prefs.getString("language", "BOTH") ?: "BOTH"
        searchMode = prefs.getString("mode", "AUTO") ?: "AUTO"
        buildShell()
        showPage("HOME")
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(12), dp(18), dp(8))
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(CARD, 22, 0xFF30204D.toInt(), 1)
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }
        root.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(root)
    }

    private fun showPage(page: String) {
        currentPage = page
        content.removeAllViews()
        when (page) {
            "HOME" -> buildHome()
            "SEARCH" -> buildSearchPage()
            else -> buildSettingsPage()
        }
        buildBottomNav()
    }

    private fun buildHome() {
        content.addView(label("Subtitle Finder", 29f, WHITE, true).apply {
            gravity = Gravity.CENTER
        }, match())
        content.addView(label("Find • Download • Enjoy", 13f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(14))
        }, match())

        val apiCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20, PURPLE, 2)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        apiCard.addView(label("🔑  SubDL API Key", 15f, WHITE, true), match())
        apiKeyInput = EditText(this).apply {
            hint = "Paste your free API key here..."
            setSingleLine(true)
            setTextColor(WHITE)
            setHintTextColor(MUTED)
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setText(prefs.getString("api_key", ""))
        }
        apiCard.addView(apiKeyInput, match())
        content.addView(apiCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })

        content.addView(label("Subtitle Language", 14f, WHITE, true), match())
        val langRow = chipRow(
            listOf("AR" to "العربية", "EN" to "English", "BOTH" to "Both"),
            selectedLanguage
        ) { selectedLanguage = it; saveOptions(); showPage("HOME") }
        content.addView(langRow, LinearLayout.LayoutParams(-1, dp(46)).apply {
            bottomMargin = dp(8)
        })

        content.addView(label("Search Mode", 14f, WHITE, true), match())
        content.addView(chipRow(
            listOf("AUTO" to "Automatic", "MANUAL" to "Manual"),
            searchMode
        ) { searchMode = it; saveOptions(); showPage("HOME") },
            LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(12) })

        folderLabel = label(
            selectedTree?.let { "Folder selected" } ?: "No folder selected",
            12f, MUTED
        )
        content.addView(folderLabel, match())

        val scanButton = Button(this).apply {
            text = "📁  Choose Folder and Scan and Download  ➜"
            textSize = 15f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(PURPLE, 0xFF5B38FF.toInt(), BLUE)
            ).apply { cornerRadius = dp(22).toFloat() }
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                if (key.isBlank()) {
                    addLog("WARN", "Please enter your SubDL API key.")
                } else {
                    prefs.edit().putString("api_key", key).apply()
                    chooseFolder()
                }
            }
        }
        content.addView(scanButton, LinearLayout.LayoutParams(-1, dp(62)).apply {
            topMargin = dp(8); bottomMargin = dp(12)
        })

        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(label("〽  Activity Log", 16f, WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val clear = smallButton("Clear") {
            logEntries.clear()
            renderLogs()
        }
        logHeader.addView(clear)
        content.addView(logHeader, match())

        val logScroll = ScrollView(this)
        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        logScroll.addView(logContainer)
        content.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderLogs()
    }

    private fun buildSearchPage() {
        content.addView(label("Search", 27f, WHITE, true), match())
        content.addView(label("Choose the search behavior used for subtitle matching.", 14f, MUTED), match())
        content.addView(chipRow(
            listOf("AUTO" to "Automatic", "MANUAL" to "Manual"),
            searchMode
        ) { searchMode = it; saveOptions(); showPage("SEARCH") },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
        content.addView(label(
            if (searchMode == "AUTO") "Automatic mode downloads the first matching result." else "Manual mode asks you to choose from available matching subtitles.",
            14f, MUTED
        ), match())
        content.addView(label("Language preference", 16f, WHITE, true).apply {
            setPadding(0, dp(22), 0, dp(8))
        }, match())
        content.addView(chipRow(
            listOf("AR" to "العربية", "EN" to "English", "BOTH" to "Both"),
            selectedLanguage
        ) { selectedLanguage = it; saveOptions(); showPage("SEARCH") },
            LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun buildSettingsPage() {
        content.addView(label("Settings", 27f, WHITE, true), match())
        content.addView(label("API Configuration", 16f, WHITE, true).apply {
            setPadding(0, dp(18), 0, dp(8))
        }, match())
        val key = EditText(this).apply {
            hint = "SubDL API key"
            setSingleLine(true)
            setTextColor(WHITE); setHintTextColor(MUTED)
            setText(prefs.getString("api_key", ""))
            background = rounded(CARD, 14, PURPLE, 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        content.addView(key, match())
        content.addView(smallButton("Save API Key") {
            prefs.edit().putString("api_key", key.text.toString().trim()).apply()
            addLog("OK", "API key saved.")
            Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
        }.apply { setPadding(0, dp(10), 0, dp(10)) }, match())
        content.addView(label("Subtitle Finder • v2.0", 14f, MUTED).apply {
            setPadding(0, dp(24), 0, dp(8))
        }, match())
        content.addView(label("Made with ❤️ for movie & series lovers", 12f, MUTED), match())
        content.addView(smallButton("Open GitHub repository") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ym1200ty/SubtitleFinder")))
        }, match())
    }

    private fun buildBottomNav() {
        bottomNav.removeAllViews()
        listOf("HOME" to "⌂\nHome", "SEARCH" to "⌕\nSearch", "SETTINGS" to "⚙\nSettings").forEach { item ->
            val active = currentPage == item.first
            val button = TextView(this).apply {
                text = item.second
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(if (active) 0xFFDDAAFF.toInt() else MUTED)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (active) rounded(0xFF24113D.toInt(), 16, PURPLE, 1) else null
                setOnClickListener { showPage(item.first) }
            }
            bottomNav.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun chipRow(
        items: List<Pair<String, String>>,
        selected: String,
        changed: (String) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        items.forEach { item ->
            val active = item.first == selected
            val chip = TextView(this).apply {
                text = item.second
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(if (active) Color.WHITE else MUTED)
                background = rounded(
                    if (active) 0xFF35135C.toInt() else CARD,
                    18,
                    if (active) BLUE else 0xFF39334D.toInt(),
                    if (active) 2 else 1
                )
                setOnClickListener { changed(item.first) }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        return row
    }

    private fun addLog(type: String, message: String) {
        val time = System.currentTimeMillis()
        mainHandler.post {
            logEntries.add(0, Pair("$time|$type", message))
            if (logEntries.size > 100) logEntries.removeAt(logEntries.lastIndex)
            if (currentPage == "HOME" && ::logContainer.isInitialized) renderLogs()
        }
    }

    private fun renderLogs() {
        if (!::logContainer.isInitialized) return
        logContainer.removeAllViews()
        if (logEntries.isEmpty()) {
            logContainer.addView(label("Activity log will appear here.", 13f, MUTED), match())
            return
        }
        logEntries.forEach { entry ->
            val parts = entry.first.split("|")
            val time = parts[0].toLongOrNull() ?: System.currentTimeMillis()
            val type = parts.getOrElse(1) { "INFO" }
            val age = (System.currentTimeMillis() - time) / 60000
            val ago = if (age < 1) "Just now" else "${age}m ago"
            val color = when (type) {
                "OK" -> 0xFF21E66B.toInt()
                "WARN" -> 0xFFFFB547.toInt()
                "ERR" -> 0xFFFF426D.toInt()
                "SEARCH" -> BLUE
                else -> PURPLE
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val dot = TextView(this).apply {
                text = "●"; textSize = 13f; setTextColor(color); gravity = Gravity.CENTER
            }
            line.addView(dot, LinearLayout.LayoutParams(dp(24), -2))
            line.addView(label(ago, 11f, MUTED), LinearLayout.LayoutParams(dp(62), -2))
            line.addView(label(entry.second, 12f, WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            logContainer.addView(line, match())
        }
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_FOLDER)
    }

    @Deprecated("Using Activity Result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            selectedTree = uri
            folderLabel.text = "Folder selected: ${DocumentFile.fromTreeUri(this, uri)?.name ?: "Selected folder"}"
            addLog("OK", "Folder access granted.")
            startScan()
        }
    }

    private fun startScan() {
        val key = prefs.getString("api_key", apiKeyInput.text.toString().trim()).orEmpty()
        val tree = selectedTree ?: return
        addLog("INFO", "Starting scan • Language: $selectedLanguage • Mode: $searchMode")
        executor.execute {
            try {
                val directory = DocumentFile.fromTreeUri(this, tree)
                    ?: throw IllegalStateException("Cannot open selected folder.")
                val videos = mutableListOf<DocumentFile>()
                collectVideos(directory, videos)
                addLog("SEARCH", "Found ${videos.size} video file(s).")
                if (videos.isEmpty()) {
                    addLog("WARN", "No supported video files were found.")
                    return@execute
                }
                videos.forEachIndexed { index, video ->
                    addLog("SEARCH", "[${index + 1}/${videos.size}] ${video.name}")
                    try { processVideo(video, key) }
                    catch (e: Exception) { addLog("ERR", e.message ?: "Unknown error") }
                    Thread.sleep(350)
                }
                addLog("OK", "Finished scanning.")
            } catch (e: Exception) {
                addLog("ERR", "Scan failed: ${e.message}")
            }
        }
    }

    private fun collectVideos(folder: DocumentFile, output: MutableList<DocumentFile>) {
        folder.listFiles().forEach { item ->
            if (item.isDirectory) collectVideos(item, output)
            else if (item.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in VIDEO_EXTENSIONS) output.add(item)
        }
    }

    private data class VideoInfo(val title: String, val year: String?, val season: Int?, val episode: Int?, val isTv: Boolean)

    private fun identify(name: String): VideoInfo {
        val base = name.substringBeforeLast('.', name)
        val se = Regex("""(?i)\bS(\d{1,2})[ ._-]*E(\d{1,2})\b""").find(base)
        val x = Regex("""(?i)\b(\d{1,2})x(\d{1,2})\b""").find(base)
        val season = se?.groupValues?.get(1)?.toIntOrNull() ?: x?.groupValues?.get(1)?.toIntOrNull()
        val episode = se?.groupValues?.get(2)?.toIntOrNull() ?: x?.groupValues?.get(2)?.toIntOrNull()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(base)?.value
        var title = base
        if (se != null) title = title.substring(0, se.range.first)
        else if (x != null) title = title.substring(0, x.range.first)
        else if (year != null) title = title.substring(0, title.indexOf(year))
        title = title.replace('.', ' ').replace('_', ' ').replace('-', ' ')
            .replace(Regex("""(?i)\b(2160p|1080p|720p|480p|WEB[- ]?DL|WEBRip|BluRay|BRRip|HDRip|x264|x265|HEVC|AV1|AAC|DDP\d?\.?\d?)\b.*$"""), "")
            .replace(Regex("""\[[^\]]*]|\([^)]*(?:1080p|720p|BluRay|WEB)[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ").trim()
        return VideoInfo(title.ifBlank { base }, year, season, episode, season != null && episode != null)
    }

    private fun processVideo(video: DocumentFile, apiKey: String) {
        val videoName = video.name ?: return
        val info = identify(videoName)
        addLog("SEARCH", "Searching: ${info.title}${if (info.isTv) " S${info.season}E${info.episode}" else ""}")
        val params = linkedMapOf(
            "api_key" to apiKey, "file_name" to videoName, "film_name" to info.title,
            "languages" to when (selectedLanguage) { "AR" -> "AR"; "EN" -> "EN"; else -> "AR,EN" },
            "subs_per_page" to "30", "unpack" to "1", "client" to "other"
        )
        params["type"] = if (info.isTv) "tv" else "movie"
        info.year?.let { params["year"] = it }
        if (info.isTv) {
            params["season_number"] = info.season.toString()
            params["episode_number"] = info.episode.toString()
        }
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val json = JSONObject(httpGet("https://api.subdl.com/api/v1/subtitles?$query"))
        if (!json.optBoolean("status", false)) {
            addLog("WARN", "SubDL: ${json.optString("error", "No result")}")
            return
        }
        val subs = json.optJSONArray("subtitles")
        if (subs == null || subs.length() == 0) {
            addLog("WARN", "No subtitles found.")
            return
        }
        val candidates = mutableListOf<JSONObject>()
        for (i in 0 until subs.length()) {
            val unpacked = subs.optJSONObject(i)?.optJSONArray("unpack_files") ?: continue
            for (j in 0 until unpacked.length()) {
                val item = unpacked.optJSONObject(j) ?: continue
                val lang = item.optString("language", item.optString("lang", "")).uppercase(Locale.ROOT)
                if ((lang == "AR" || lang == "EN") && (!info.isTv || episodeMatches(item, info))) candidates.add(item)
            }
        }
        if (candidates.isEmpty()) {
            addLog("WARN", "No direct Arabic/English subtitle files found.")
            return
        }

        val languages = when (selectedLanguage) { "AR" -> listOf("AR"); "EN" -> listOf("EN"); else -> listOf("AR", "EN") }
        languages.forEach { lang ->
            val matches = candidates.filter { it.optString("language", it.optString("lang", "")).equals(lang, true) }
            if (matches.isEmpty()) {
                addLog("WARN", "$lang: no match.")
                return@forEach
            }
            val best = if (searchMode == "MANUAL") chooseManual(matches, lang, videoName) else matches.first()
            if (best == null) {
                addLog("INFO", "$lang: skipped by user.")
                return@forEach
            }
            downloadSubtitle(best, lang, video)
        }
    }

    private fun chooseManual(matches: List<JSONObject>, language: String, videoName: String): JSONObject? {
        val latch = CountDownLatch(1)
        var chosen: JSONObject? = null
        mainHandler.post {
            val labels = matches.take(20).mapIndexed { index, item ->
                val release = item.optString("release_name", item.optString("name", "Subtitle ${index + 1}"))
                val rating = item.optString("rating", "")
                "$release${if (rating.isNotBlank()) " • rating $rating" else ""}"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("$language subtitles for $videoName")
                .setItems(labels) { _, which -> chosen = matches.getOrNull(which); latch.countDown() }
                .setNegativeButton("Skip") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(5, TimeUnit.MINUTES)
        return chosen
    }

    private fun downloadSubtitle(best: JSONObject, lang: String, video: DocumentFile) {
        val urlPath = best.optString("url")
        val format = best.optString("format", "srt").lowercase(Locale.ROOT)
        if (urlPath.isBlank()) {
            addLog("WARN", "$lang subtitle has no download URL.")
            return
        }
        val safeExt = if (format in setOf("srt", "ass", "ssa", "sub", "vtt")) format else "srt"
        val base = (video.name ?: return).substringBeforeLast('.', video.name!!)
        val targetName = base + if (lang == "AR") ".ar.$safeExt" else ".en.$safeExt"
        val parent = video.parentFile
        if (parent == null) {
            addLog("ERR", "Cannot access video's parent folder.")
            return
        }
        if (parent.findFile(targetName) != null) {
            addLog("INFO", "$lang already exists; not overwritten.")
            return
        }
        val fullUrl = if (urlPath.startsWith("http")) urlPath else "https://dl.subdl.com" + if (urlPath.startsWith("/")) urlPath else "/$urlPath"
        val bytes = httpBytes(fullUrl)
        val created = parent.createFile("application/octet-stream", targetName)
            ?: throw IllegalStateException("Cannot create $targetName")
        contentResolver.openOutputStream(created.uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Cannot write $targetName")
        addLog("OK", "Downloaded: $targetName")
    }

    private fun episodeMatches(item: JSONObject, info: VideoInfo): Boolean {
        val season = item.optInt("season", -1)
        val episode = item.optInt("episode", -1)
        return (season == -1 || season == info.season) && (episode == -1 || episode == info.episode)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun httpGet(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20000; readTimeout = 30000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $body")
            return body
        } finally { connection.disconnect() }
    }

    private fun httpBytes(address: String): ByteArray {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("User-Agent", "SubtitleFinderAndroid/2.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Download failed (HTTP $code)")
            return BufferedInputStream(connection.inputStream).use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private fun saveOptions() {
        prefs.edit().putString("language", selectedLanguage).putString("mode", searchMode).apply()
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun smallButton(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(WHITE); background = rounded(0xFF25143B.toInt(), 16, PURPLE, 1)
        setPadding(dp(12), dp(7), dp(12), dp(7)); setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int, strokeWidth: Int) =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(radius).toFloat()
            setStroke(dp(strokeWidth), stroke)
        }

    private fun match() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        selectedTree?.let { outState.putString("tree", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

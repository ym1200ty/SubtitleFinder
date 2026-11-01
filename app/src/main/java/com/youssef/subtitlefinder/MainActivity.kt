
package com.youssef.subtitlefinder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var apiKeyInput: EditText
    private lateinit var folderLabel: TextView
    private lateinit var logView: TextView

    private var selectedTree: Uri? = null
    private var selectedLanguage = "BOTH"
    private var searchMode = "AUTO"

    private val prefs by lazy {
        getSharedPreferences("subtitle_finder", MODE_PRIVATE)
    }

    companion object {
        private const val PICK_FOLDER = 101

        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "mov", "wmv",
            "m4v", "webm", "mpg", "mpeg", "ts"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 18)
        }

        val title = TextView(this).apply {
            text = "Subtitle Finder"
            textSize = 25f
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWidth())

        val description = TextView(this).apply {
            text = "Find and download subtitles beside your video files."
            textSize = 14f
            setPadding(0, 12, 0, 18)
        }
        root.addView(description, matchWidth())

        apiKeyInput = EditText(this).apply {
            hint = "Paste your free SubDL API key"
            setSingleLine(true)
            setText(prefs.getString("api_key", ""))
        }
        root.addView(apiKeyInput, matchWidth())

        addChoiceGroup(
            root,
            "Subtitle language",
            listOf(
                "English" to "EN",
                "العربية" to "AR",
                "Both" to "BOTH"
            ),
            selectedLanguage
        ) {
            selectedLanguage = it
        }

        addChoiceGroup(
            root,
            "Search mode",
            listOf(
                "Automatic" to "AUTO",
                "Manual" to "MANUAL"
            ),
            searchMode
        ) {
            searchMode = it
        }

        val chooseButton = Button(this).apply {
            text = "1. Choose movies / series folder"
            setOnClickListener { chooseFolder() }
        }
        root.addView(chooseButton, matchWidth())

        folderLabel = TextView(this).apply {
            text = "No folder selected"
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        root.addView(folderLabel, matchWidth())

        val scanButton = Button(this).apply {
            text = "2. Scan folder and download subtitles"
            setOnClickListener { startScan() }
        }
        root.addView(scanButton, matchWidth())

        val note = TextView(this).apply {
            text = "Video files are not uploaded. Existing subtitles are not overwritten."
            textSize = 12f
            setPadding(0, 10, 0, 10)
        }
        root.addView(note, matchWidth())

        logView = TextView(this).apply {
            textSize = 13f
            text = "Activity log will appear here."
            setPadding(10, 10, 10, 10)
        }

        val scroll = ScrollView(this)
        scroll.addView(logView)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        selectedTree = savedInstanceState
            ?.getString("tree")
            ?.let(Uri::parse)

        if (selectedTree != null) {
            folderLabel.text = "Folder selected"
        }
    }

    private fun addChoiceGroup(
        root: LinearLayout,
        heading: String,
        options: List<Pair<String, String>>,
        defaultValue: String,
        onChanged: (String) -> Unit
    ) {
        root.addView(TextView(this).apply {
            text = heading
            textSize = 15f
            setPadding(0, 12, 0, 4)
        }, matchWidth())

        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }

        var defaultId = View.NO_ID

        options.forEach { option ->
            val radio = RadioButton(this).apply {
                text = option.first
                id = View.generateViewId()
                tag = option.second
            }

            group.addView(radio)

            if (option.second == defaultValue) {
                defaultId = radio.id
            }
        }

        group.setOnCheckedChangeListener { g, id ->
            g.findViewById<RadioButton>(id)
                ?.tag?.toString()
                ?.let(onChanged)
        }

        group.check(defaultId)

        root.addView(group, matchWidth())
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )

        startActivityForResult(intent, PICK_FOLDER)
    }

    @Deprecated("Using Activity Result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK) {

            val uri = data?.data ?: return

            val flags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            contentResolver.takePersistableUriPermission(uri, flags)

            selectedTree = uri

            folderLabel.text =
                "Folder selected: ${DocumentFile.fromTreeUri(this, uri)?.name ?: "Selected folder"}"

            appendLog("Folder access granted.")
        }
    }

    private fun startScan() {

        val key = apiKeyInput.text.toString().trim()
        val tree = selectedTree

        if (key.isBlank()) {
            appendLog("Please enter your SubDL API key first.")
            return
        }

        if (tree == null) {
            appendLog("Please choose the folder containing your videos.")
            return
        }

        prefs.edit().putString("api_key", key).apply()

        appendLog("Starting scan...")
        appendLog("Language: $selectedLanguage")
        appendLog("Mode: $searchMode")

        executor.execute {
            try {

                val directory = DocumentFile.fromTreeUri(this, tree)
                    ?: throw IllegalStateException("Cannot open selected folder.")

                val videos = mutableListOf<DocumentFile>()

                collectVideos(directory, videos)

                postLog("Found ${videos.size} video file(s).")

                if (videos.isEmpty()) {
                    postLog("No supported video files were found.")
                    return@execute
                }

                videos.forEachIndexed { index, video ->

                    postLog("\n[${index + 1}/${videos.size}] ${video.name}")

                    try {
                        processVideo(video, key)
                    } catch (e: Exception) {
                        postLog("Error: ${e.message ?: "Unknown error"}")
                    }

                    Thread.sleep(350)
                }

                postLog("\nFinished.")

            } catch (e: Exception) {
                postLog("Scan failed: ${e.message}")
            }
        }
    }

    private fun collectVideos(
        folder: DocumentFile,
        output: MutableList<DocumentFile>
    ) {
        folder.listFiles().forEach { item ->

            if (item.isDirectory) {
                collectVideos(item, output)
            } else {

                val ext = item.name
                    ?.substringAfterLast('.', "")
                    ?.lowercase(Locale.ROOT)

                if (ext in VIDEO_EXTENSIONS) {
                    output.add(item)
                }
            }
        }
    }

    private data class VideoInfo(
        val title: String,
        val year: String?,
        val season: Int?,
        val episode: Int?,
        val isTv: Boolean
    )

    private fun identify(name: String): VideoInfo {

        val base = name.substringBeforeLast('.', name)

        val se = Regex(
            """(?i)\bS(\d{1,2})[ ._-]*E(\d{1,2})\b"""
        ).find(base)

        val x = Regex(
            """(?i)\b(\d{1,2})x(\d{1,2})\b"""
        ).find(base)

        val season =
            se?.groupValues?.get(1)?.toIntOrNull()
                ?: x?.groupValues?.get(1)?.toIntOrNull()

        val episode =
            se?.groupValues?.get(2)?.toIntOrNull()
                ?: x?.groupValues?.get(2)?.toIntOrNull()

        val year = Regex(
            """\b(19\d{2}|20\d{2})\b"""
        ).find(base)?.value

        var title = base

        if (se != null) {
            title = title.substring(0, se.range.first)
        } else if (x != null) {
            title = title.substring(0, x.range.first)
        } else if (year != null) {
            title = title.substring(0, title.indexOf(year))
        }

        title = title.replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(
                Regex(
                    """(?i)\b(2160p|1080p|720p|480p|WEB[- ]?DL|WEBRip|BluRay|BRRip|HDRip|x264|x265|HEVC|AV1|AAC|DDP\d?\.?\d?)\b.*$"""
                ),
                ""
            )
            .replace(
                Regex(
                    """\[[^\]]*]|\([^)]*(?:1080p|720p|BluRay|WEB)[^)]*\)""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

        return VideoInfo(
            title.ifBlank { base },
            year,
            season,
            episode,
            season != null && episode != null
        )
    }

    private fun processVideo(
        video: DocumentFile,
        apiKey: String
    ) {

        val videoName = video.name ?: return
        val info = identify(videoName)

        postLog(
            "Searching: ${info.title}" +
            if (info.isTv) " S${info.season}E${info.episode}" else ""
        )

        val requestedLanguages = when (selectedLanguage) {
            "AR" -> "AR"
            "EN" -> "EN"
            else -> "AR,EN"
        }

        val params = linkedMapOf(
            "api_key" to apiKey,
            "file_name" to videoName,
            "film_name" to info.title,
            "languages" to requestedLanguages,
            "subs_per_page" to "30",
            "unpack" to "1",
            "client" to "other"
        )

        params["type"] = if (info.isTv) "tv" else "movie"

        info.year?.let {
            params["year"] = it
        }

        if (info.isTv) {
            params["season_number"] = info.season.toString()
            params["episode_number"] = info.episode.toString()
        }

        val query = params.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }

        val jsonText = httpGet(
            "https://api.subdl.com/api/v1/subtitles?$query"
        )

        val json = JSONObject(jsonText)

        if (!json.optBoolean("status", false)) {
            postLog("SubDL: ${json.optString("error", "No result")}")
            return
        }

        val subs = json.optJSONArray("subtitles")

        if (subs == null || subs.length() == 0) {
            postLog("No subtitles found.")
            return
        }

        val candidates = mutableListOf<JSONObject>()

        for (i in 0 until subs.length()) {

            val sub = subs.optJSONObject(i) ?: continue
            val unpacked = sub.optJSONArray("unpack_files") ?: continue

            for (j in 0 until unpacked.length()) {

                val item = unpacked.optJSONObject(j) ?: continue

                val lang = item.optString(
                    "language",
                    item.optString("lang", "")
                ).uppercase(Locale.ROOT)

                if (lang == "AR" || lang == "EN") {

                    if (!info.isTv || episodeMatches(item, info)) {
                        candidates.add(item)
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            postLog("No direct Arabic/English subtitle files found.")
            return
        }

        val base = videoName.substringBeforeLast('.', videoName)

        val languagesToDownload = when (selectedLanguage) {
            "AR" -> listOf("AR")
            "EN" -> listOf("EN")
            else -> listOf("AR", "EN")
        }

        languagesToDownload.forEach { lang ->

            val best = candidates.firstOrNull {
                it.optString(
                    "language",
                    it.optString("lang", "")
                ).equals(lang, true)
            }

            if (best == null) {
                postLog("$lang: no match.")
                return@forEach
            }

            val urlPath = best.optString("url")

            val format = best.optString("format", "srt")
                .lowercase(Locale.ROOT)

            if (urlPath.isBlank()) {
                postLog("$lang subtitle has no download URL.")
                return@forEach
            }

            val safeExt = if (
                format in setOf("srt", "ass", "ssa", "sub", "vtt")
            ) format else "srt"

            val suffix = if (lang == "AR") {
                ".ar.$safeExt"
            } else {
                ".en.$safeExt"
            }

            val targetName = base + suffix

            val parent = video.parentFile

            if (parent == null) {
                postLog("Cannot access the video's parent folder.")
                return@forEach
            }

            if (parent.findFile(targetName) != null) {
                postLog("$lang already exists; not overwritten.")
                return@forEach
            }

            val fullUrl = if (urlPath.startsWith("http")) {
                urlPath
            } else {
                "https://dl.subdl.com" +
                if (urlPath.startsWith("/")) urlPath else "/$urlPath"
            }

            val bytes = httpBytes(fullUrl)

            val created = parent.createFile(
                "application/octet-stream",
                targetName
            ) ?: throw IllegalStateException("Cannot create $targetName")

            contentResolver.openOutputStream(created.uri)?.use {
                it.write(bytes)
            } ?: throw IllegalStateException("Cannot write $targetName")

            postLog("Downloaded: $targetName")
        }
    }

    private fun episodeMatches(
        item: JSONObject,
        info: VideoInfo
    ): Boolean {

        val season = item.optInt("season", -1)
        val episode = item.optInt("episode", -1)

        return (season == -1 || season == info.season) &&
                (episode == -1 || episode == info.episode)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun httpGet(address: String): String {

        val connection =
            (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("Accept", "application/json")
            }

        try {

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = BufferedReader(
                InputStreamReader(stream)
            ).use { it.readText() }

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $body")
            }

            return body

        } finally {
            connection.disconnect()
        }
    }

    private fun httpBytes(address: String): ByteArray {

        val connection =
            (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty(
                    "User-Agent",
                    "SubtitleFinderAndroid/1.0"
                )
            }

        try {

            val code = connection.responseCode

            if (code !in 200..299) {
                throw IllegalStateException(
                    "Download failed (HTTP $code)"
                )
            }

            return BufferedInputStream(
                connection.inputStream
            ).use { it.readBytes() }

        } finally {
            connection.disconnect()
        }
    }

    private fun postLog(message: String) {
        mainHandler.post {
            appendLog(message)
        }
    }

    private fun appendLog(message: String) {
        logView.append(message + "\n")
    }

    override fun onSaveInstanceState(outState: Bundle) {

        selectedTree?.let {
            outState.putString("tree", it.toString())
        }

        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

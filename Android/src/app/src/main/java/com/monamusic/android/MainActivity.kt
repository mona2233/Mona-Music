package com.monamusic.android

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.monamusic.android.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer
    private lateinit var webdavGateway: WebdavGateway
    private lateinit var playbackModeController: PlaybackModeController
    @Volatile
    private var isMainSheetOpen: Boolean = false

    private val prefName = "mona_music_android_prefs"
    private val keyPlayMode = "play_mode"
    private val keyWebdavSettings = "webdav_settings_json"
    private val keyWebdavLibraries = "webdav_libraries_json"
    private val keyLocalMusicDirs = "local_music_dirs_json"
    private val keyPendingLocalPick = "pending_local_pick"

    private val songsLock = Any()
    private val songsCache = mutableListOf<Song>()
    private val isLibraryRefreshing = AtomicBoolean(false)
    private val richMetaCache = mutableMapOf<String, String>()
    private val richMetaLoading = mutableSetOf<String>()
    private val libraryScanExecutor = Executors.newSingleThreadExecutor()
    private val metaExecutor = Executors.newSingleThreadExecutor()
    private val insecureTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
    private val insecureSslSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(insecureTrustManager), java.security.SecureRandom())
        }.socketFactory
    }
    private val insecureHostnameVerifier = HostnameVerifier { _, _ -> true }
    private val plainHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val insecureHttpsClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(insecureSslSocketFactory, insecureTrustManager)
            .hostnameVerifier(insecureHostnameVerifier)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var playbackStateCache: String = JSONObject()
        .put("isPlaying", false)
        .put("playbackState", Player.STATE_IDLE)
        .put("index", -1)
        .put("positionMs", 0)
        .put("durationMs", 0)
        .toString()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadMainPage()
        }
    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                getSharedPreferences(prefName, MODE_PRIVATE).edit().putBoolean(keyPendingLocalPick, false).apply()
                return@registerForActivityResult
            }
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val value = uri.toString()
            val next = localMusicDirsFromPrefs().toMutableSet()
            next.add(value)
            saveLocalMusicDirsToPrefs(next.toList())
            getSharedPreferences(prefName, MODE_PRIVATE).edit().putBoolean(keyPendingLocalPick, false).apply()
            runOnUiThread {
                val listArr = JSONArray()
                localMusicDirsFromPrefs().forEach(listArr::put)
                val payload = JSONObject().put("list", listArr).toString()
                binding.webView.evaluateJavascript(
                    "window.onNativeLocalDirsChanged && window.onNativeLocalDirsChanged($payload);",
                    null
                )
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialMode = getSharedPreferences(prefName, MODE_PRIVATE).getInt(keyPlayMode, 2).coerceIn(0, 2)
        playbackModeController = PlaybackModeController(initialMode) { nextMode ->
            getSharedPreferences(prefName, MODE_PRIVATE).edit().putInt(keyPlayMode, nextMode).apply()
        }
        webdavGateway = WebdavGateway(
            plainHttpClient = plainHttpClient,
            insecureHttpsClient = insecureHttpsClient,
            buildAuthHeader = ::authHeader
        )
        val httpDataSourceFactory = OkHttpDataSource.Factory(insecureHttpsClient)
        val baseDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val resolvingDataSourceFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
            val targetUri = dataSpec.uri.toString()
            val song = findSongByUri(targetUri)
            if (song != null && song.authHeader.isNotBlank()) {
                dataSpec.withRequestHeaders(mapOf("Authorization" to song.authHeader))
            } else {
                dataSpec
            }
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingDataSourceFactory))
            .build()
            .apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) handleTrackEndedNative()
                    updateKeepScreenOnState()
                    emitPlaybackStateToWeb()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateKeepScreenOnState()
                    emitPlaybackStateToWeb()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = emitPlaybackStateToWeb()
            })
        }
        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }

        ensurePermissionThenLoad()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val url = webView.url.orEmpty()
                if (url.contains("settings.html")) {
                    webView.loadUrl("file:///android_asset/www/main.html")
                    return
                }
                if (isMainSheetOpen) {
                    isMainSheetOpen = false
                    webView.evaluateJavascript("window.closeSheet && window.closeSheet();", null)
                    return
                }
                showExitConfirmDialog()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { libraryScanExecutor.shutdownNow() }
        runCatching { metaExecutor.shutdownNow() }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::player.isInitialized) {
            player.release()
        }
    }

    override fun onStop() {
        super.onStop()
        // 仅前台播放常亮，离开前台后恢复系统默认息屏策略
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) {
            updateKeepScreenOnState()
        }
    }

    private fun ensurePermissionThenLoad() {
        val perms = requiredPermissions()
        val granted = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) loadMainPage() else permissionLauncher.launch(perms.toTypedArray())
    }

    private fun loadMainPage() {
        binding.webView.loadUrl("file:///android_asset/www/main.html")
        refreshSongsInBackground(emitToWeb = false)
    }

    private fun currentSongsSnapshot(): List<Song> = synchronized(songsLock) { songsCache.toList() }

    private fun replaceSongsCache(newSongs: List<Song>) {
        synchronized(songsLock) {
            songsCache.clear()
            songsCache.addAll(newSongs)
        }
        synchronized(richMetaCache) { richMetaCache.clear() }
        synchronized(richMetaLoading) { richMetaLoading.clear() }
        if (::playbackModeController.isInitialized) {
            // 后台线程刷新曲库时不能访问 ExoPlayer，避免线程模型崩溃
            playbackModeController.onQueueChanged(newSongs.size, -1)
        }
    }

    private fun findSongByUri(uri: String): Song? = synchronized(songsLock) {
        songsCache.firstOrNull { it.uri == uri }
    }

    private fun showExitConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("退出应用")
            .setMessage("确定要退出 Mona Music 吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ -> finish() }
            .show()
    }

    private fun updateKeepScreenOnState() {
        val keepOn = player.isPlaying
        runOnUiThread {
            if (keepOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(android.Manifest.permission.READ_MEDIA_AUDIO)
        else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasMediaPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun authHeader(user: String, pass: String): String {
        val token = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        return "Basic $token"
    }

    private fun parseWebdavConfig(json: String): WebdavLibrary? = runCatching {
        val o = JSONObject(json)
        val protocol = if (o.optString("protocol", "https").lowercase(Locale.ROOT) == "http") "http" else "https"
        val host = o.optString("host", "").trim()
        if (host.isBlank()) return null
        val port = o.optString("port", if (protocol == "https") "443" else "80").toIntOrNull()
            ?: if (protocol == "https") 443 else 80
        val username = o.optString("username", "").trim()
        val password = o.optString("password", "")
        val folder = o.optString("folderPath", "/").trim().ifBlank { "/" }
        val normalized = if (folder.startsWith("/")) folder else "/$folder"
        val id = "$protocol://$host:$port$normalized".lowercase(Locale.ROOT)
        WebdavLibrary(id, protocol, host, port, username, password, normalized)
    }.getOrNull()

    private fun webdavLibrariesFromPrefs(): MutableList<WebdavLibrary> {
        val raw = getSharedPreferences(prefName, MODE_PRIVATE).getString(keyWebdavLibraries, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<WebdavLibrary>()
            for (i in 0 until arr.length()) {
                parseWebdavConfig(arr.optJSONObject(i)?.toString() ?: continue)?.let(out::add)
            }
            out
        }.getOrDefault(mutableListOf())
    }

    private fun webdavLibrariesToJson(list: List<WebdavLibrary>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("protocol", it.protocol)
                    .put("host", it.host)
                    .put("port", it.port)
                    .put("username", it.username)
                    .put("password", it.password)
                    .put("folderPath", it.folderPath)
            )
        }
        return arr.toString()
    }

    private fun saveWebdavLibrariesToPrefs(list: List<WebdavLibrary>) {
        getSharedPreferences(prefName, MODE_PRIVATE).edit().putString(keyWebdavLibraries, webdavLibrariesToJson(list)).apply()
    }

    private fun localMusicDirsFromPrefs(): MutableList<String> {
        val raw = getSharedPreferences(prefName, MODE_PRIVATE).getString(keyLocalMusicDirs, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotBlank()) out.add(v)
            }
            out.distinct().toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun saveLocalMusicDirsToPrefs(list: List<String>) {
        val arr = JSONArray()
        list.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach(arr::put)
        getSharedPreferences(prefName, MODE_PRIVATE).edit().putString(keyLocalMusicDirs, arr.toString()).apply()
    }

    private fun normalizeLocalPath(path: String): String {
        if (path.startsWith("content://")) return path.trim()
        val cleaned = path.replace('\\', '/').trim()
        if (cleaned.isBlank() || cleaned == "/") return "/"
        return "/${cleaned.trim('/')}/"
    }

    private fun isAudioName(name: String): Boolean {
        val low = name.lowercase(Locale.ROOT)
        return low.endsWith(".mp3") || low.endsWith(".flac") || low.endsWith(".wav") || low.endsWith(".m4a") || low.endsWith(".ogg") || low.endsWith(".aac")
    }

    private fun songFromTreeFile(file: DocumentFile): Song {
        val filename = file.name ?: "未知歌曲"
        val title = filename.substringBeforeLast('.').ifBlank { filename }
        return Song(
            id = "tree:${file.uri}",
            title = title,
            artist = "本地目录",
            durationMs = 0,
            uri = file.uri.toString(),
            source = "local"
        )
    }

    private fun scanTreeSongs(uriText: String, maxSongs: Int = 5000): List<Song> {
        val root = DocumentFile.fromTreeUri(this, Uri.parse(uriText)) ?: return emptyList()
        val queue = ArrayDeque<DocumentFile>()
        val songs = mutableListOf<Song>()
        queue.add(root)
        var visitedDirs = 0
        while (queue.isNotEmpty() && songs.size < maxSongs && visitedDirs < 20000) {
            val dir = queue.removeFirst()
            if (!dir.isDirectory) continue
            visitedDirs += 1
            val list = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
            list.forEach { f ->
                when {
                    f.isDirectory -> queue.add(f)
                    f.isFile && isAudioName(f.name ?: "") -> songs.add(songFromTreeFile(f))
                }
            }
        }
        return songs
    }

    private fun listLocalDirectories(path: String): List<Pair<String, String>> {
        if (!hasMediaPermission()) return emptyList()
        val current = normalizeLocalPath(path)
        val currentLower = current.lowercase(Locale.ROOT)
        val children = linkedMapOf<String, String>()
        val projection = arrayOf(MediaStore.Audio.Media.RELATIVE_PATH)
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )?.use { c ->
            val relIdx = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                if (relIdx < 0) continue
                val relRaw = c.getString(relIdx) ?: continue
                val full = normalizeLocalPath("/$relRaw")
                val fullLower = full.lowercase(Locale.ROOT)
                if (!fullLower.startsWith(currentLower)) continue
                val remaining = full.substring(current.length.coerceAtMost(full.length)).trim('/')
                if (remaining.isBlank()) continue
                val child = remaining.substringBefore('/').trim()
                if (child.isBlank()) continue
                val childPath = normalizeLocalPath(if (current == "/") "/$child" else "$current$child")
                children[childPath] = child
            }
        }
        return children.entries
            .map { it.key to it.value }
            .sortedBy { it.second.lowercase(Locale.ROOT) }
    }

    private fun openWebdavConnection(url: String, cfg: WebdavLibrary): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && cfg.protocol == "https") {
            conn.sslSocketFactory = insecureSslSocketFactory
            conn.hostnameVerifier = insecureHostnameVerifier
        }
        return conn
    }

    private fun createWebdavSong(lib: WebdavLibrary, filePath: String): Song {
        val p = if (filePath.startsWith("/")) filePath else "/$filePath"
        val title = p.substringAfterLast('/').substringBeforeLast('.').ifBlank { "未知歌曲" }
        val userInfo = if (lib.username.isNotBlank()) "${Uri.encode(lib.username)}:${Uri.encode(lib.password)}@" else ""
        return Song(
            id = "webdav:${lib.id}:$p",
            title = title,
            artist = "WebDAV",
            durationMs = 0,
            uri = "${lib.protocol}://$userInfo${lib.host}:${lib.port}${p.split("/").joinToString("/") { if (it.isEmpty()) "" else Uri.encode(it) }}",
            source = "webdav",
            authHeader = authHeader(lib.username, lib.password)
        )
    }

    private fun scanWebdavLibrary(lib: WebdavLibrary): List<Song> {
        // 大曲库优先一次性拉取，减少逐目录请求导致的卡顿
        val fast = runCatching {
            webdavGateway.propfind(lib, lib.folderPath, "infinity")
                .asSequence()
                .filter { !it.isCollection }
                .map { it.path }
                .distinct()
                .filter {
                    val low = it.lowercase(Locale.ROOT)
                    low.endsWith(".mp3") || low.endsWith(".flac") || low.endsWith(".wav") ||
                        low.endsWith(".m4a") || low.endsWith(".ogg") || low.endsWith(".aac")
                }
                .map { createWebdavSong(lib, it) }
                .take(5000)
                .toList()
        }.getOrDefault(emptyList())
        if (fast.isNotEmpty()) return fast

        val q = ArrayDeque<String>()
        q.add(lib.folderPath)
        val visited = mutableSetOf<String>()
        val out = mutableListOf<Song>()
        while (q.isNotEmpty() && out.size < 5000) {
            val cur = q.removeFirst()
            val curKey = cur.trimEnd('/').lowercase(Locale.ROOT)
            if (!visited.add(curKey)) continue
            val items = runCatching { webdavGateway.propfind(lib, cur, "1") }.getOrDefault(emptyList())
            for (it in items) {
                if (it.isCollection) q.add(it.path)
                else {
                    val low = it.path.lowercase(Locale.ROOT)
                    if (low.endsWith(".mp3") || low.endsWith(".flac") || low.endsWith(".wav") || low.endsWith(".m4a") || low.endsWith(".ogg") || low.endsWith(".aac")) {
                        out.add(createWebdavSong(lib, it.path))
                    }
                }
                if (out.size >= 5000) break
            }
        }
        return out
    }

    private fun queryLocalSongs(): List<Song> {
        val localDirFilters = localMusicDirsFromPrefs().map(::normalizeLocalPath).distinct()
        if (localDirFilters.isEmpty()) return emptyList()
        val treeUris = localDirFilters.filter { it.startsWith("content://") }
        val result = mutableListOf<Song>()
        if (treeUris.isNotEmpty()) {
            treeUris.forEach { result.addAll(scanTreeSongs(it)) }
            return result
        }
        if (!hasMediaPermission()) return emptyList()
        val localDirFiltersLower = localDirFilters.map { it.lowercase(Locale.ROOT) }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val tIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val aIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val relIdx = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val title = c.getString(tIdx) ?: "未知歌曲"
                val artist = c.getString(aIdx) ?: "未知歌手"
                val rel = if (relIdx >= 0) normalizeLocalPath(c.getString(relIdx) ?: "") else "/"
                val relLower = rel.lowercase(Locale.ROOT)
                if (localDirFiltersLower.isNotEmpty() && localDirFiltersLower.none { relLower.startsWith(it) }) continue
                result.add(
                    Song(
                        id = id.toString(),
                        title = title,
                        artist = artist,
                        durationMs = c.getLong(dIdx),
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        source = "local"
                    )
                )
            }
        }
        return result
    }

    private fun loadSongsIntoCache() {
        val next = mutableListOf<Song>()
        next.addAll(queryLocalSongs())
        webdavLibrariesFromPrefs().forEach { lib ->
            next.addAll(runCatching { scanWebdavLibrary(lib) }.getOrDefault(emptyList()))
        }
        replaceSongsCache(next)
    }

    private fun ensureSongsAvailable(blocking: Boolean): Boolean {
        if (currentSongsSnapshot().isNotEmpty()) return true
        if (blocking) loadSongsIntoCache() else refreshSongsInBackground(emitToWeb = false)
        return currentSongsSnapshot().isNotEmpty()
    }

    private fun refreshSongsInBackground(emitToWeb: Boolean = false) {
        if (!isLibraryRefreshing.compareAndSet(false, true)) return
        libraryScanExecutor.execute {
            try {
                loadSongsIntoCache()
                runOnUiThread {
                    syncPlayerQueue()
                    if (emitToWeb) emitPlaybackStateToWeb()
                }
            } finally {
                isLibraryRefreshing.set(false)
            }
        }
    }

    private fun syncPlayerQueue() {
        val songs = currentSongsSnapshot()
        if (songs.isEmpty()) {
            player.clearMediaItems()
            return
        }
        val same = runCatching {
            if (player.mediaItemCount != songs.size) return@runCatching false
            songs.indices.all { i ->
                if (i >= player.mediaItemCount) return@all false
                player.getMediaItemAt(i).mediaId == songs[i].id
            }
        }.getOrDefault(false)
        if (same) return
        val oldIndex = player.currentMediaItemIndex
        val mediaItems = songs.map {
            MediaItem.Builder()
                .setMediaId(it.id)
                .setUri(it.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(it.title)
                        .setArtist(it.artist)
                        .build()
                )
                .build()
        }
        player.setMediaItems(mediaItems, false)
        if (oldIndex in mediaItems.indices) player.seekToDefaultPosition(oldIndex)
        player.prepare()
        playbackModeController.onQueueChanged(mediaItems.size, player.currentMediaItemIndex)
    }

    private fun applyAuthForIndex(index: Int) {
        val song = currentSongsSnapshot().getOrNull(index) ?: return
        if (song.authHeader.isBlank()) System.setProperty("http.auth.preference", "")
        else System.setProperty("http.auth.preference", "Basic")
    }

    private fun handleTrackEndedNative() {
        val songs = currentSongsSnapshot()
        if (songs.isEmpty()) return
        val i = playbackModeController.resolveEndedIndex(player.currentMediaItemIndex, songs.size)
        applyAuthForIndex(i)
        player.seekToDefaultPosition(i)
        player.playWhenReady = true
    }

    private fun emitPlaybackStateToWeb() {
        runOnUiThread {
            val s = buildPlaybackStateJson()
            playbackStateCache = s.toString()
            warmupTrackMeta(player.currentMediaItemIndex)
            if (binding.webView.url?.contains("main.html") == true) {
                binding.webView.evaluateJavascript("window.onNativePlaybackState && window.onNativePlaybackState($s);", null)
            }
        }
    }

    private fun warmupTrackMeta(index: Int) {
        val song = currentSongsSnapshot().getOrNull(index) ?: return
        if (synchronized(richMetaCache) { richMetaCache.containsKey(song.id) }) return
        synchronized(richMetaLoading) {
            if (!richMetaLoading.add(song.id)) return
        }
        metaExecutor.execute {
            try {
                val meta = readTrackRichMeta(song)
                synchronized(richMetaCache) { richMetaCache[song.id] = meta }
                runOnUiThread {
                    if (binding.webView.url?.contains("main.html") == true) {
                        val escaped = JSONObject.quote(meta)
                        val js = "window.onNativeTrackMetaReady && window.onNativeTrackMetaReady($index, JSON.parse($escaped));"
                        binding.webView.evaluateJavascript(js, null)
                    }
                }
            } finally {
                synchronized(richMetaLoading) { richMetaLoading.remove(song.id) }
            }
        }
    }

    private fun buildPlaybackStateJson(): JSONObject =
        if (!::player.isInitialized) {
            JSONObject()
                .put("isPlaying", false)
                .put("playbackState", Player.STATE_IDLE)
                .put("index", -1)
                .put("positionMs", 0)
                .put("durationMs", 0)
        } else
        JSONObject()
            .put("isPlaying", player.isPlaying)
            .put("playbackState", player.playbackState)
            .put("index", player.currentMediaItemIndex)
            .put("positionMs", player.currentPosition)
            .put("durationMs", player.duration)

    private fun readLimited(input: BufferedInputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var off = 0
        while (off < maxBytes) {
            val read = input.read(buf, off, maxBytes - off)
            if (read <= 0) break
            off += read
        }
        return buf.copyOf(off)
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun guessExtFromUri(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val name = c.getString(idx).orEmpty()
                    val ext = name.substringAfterLast('.', "")
                    if (ext.isNotBlank()) return ext.lowercase(Locale.ROOT)
                }
            }
            "mp3"
        }.getOrDefault("mp3")
    }

    private fun ensureLocalCopy(song: Song): File? = runCatching {
        val dir = File(cacheDir, "track_meta")
        if (!dir.exists()) dir.mkdirs()
        val parsedUri = Uri.parse(song.uri)
        val ext = if (song.uri.startsWith("content://")) guessExtFromUri(parsedUri) else song.uri.substringAfterLast('.', "mp3")
        val file = File(dir, "${song.id.hashCode()}.$ext")
        if (file.exists() && file.length() > 0L) return file

        if (song.source == "webdav") {
            val parsed = parsedUri
            val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            val host = parsed.host.orEmpty()
            val isHttps = scheme == "https"
            val fallbackPort = if (isHttps) 443 else 80
            val port = if (parsed.port > 0) parsed.port else fallbackPort
            val cfg = parseWebdavConfig(
                JSONObject()
                    .put("protocol", if (isHttps) "https" else "http")
                    .put("host", host)
                    .put("port", port)
                    .put("username", "")
                    .put("password", "")
                    .put("folderPath", "/")
                    .toString()
            )
            val c = if (cfg != null) openWebdavConnection(song.uri, cfg) else (URL(song.uri).openConnection() as HttpURLConnection)
            c.apply {
                connectTimeout = 8000
                readTimeout = 12000
                requestMethod = "GET"
                if (song.authHeader.isNotBlank()) setRequestProperty("Authorization", song.authHeader)
                setRequestProperty("Range", "bytes=0-12582911")
            }
            val code = c.responseCode
            if (code !in 200..299 && code != 206) {
                c.disconnect()
                return null
            }
            val bytes = BufferedInputStream(c.inputStream).use { readLimited(it, 12 * 1024 * 1024) }
            c.disconnect()
            FileOutputStream(file).use { it.write(bytes) }
        } else {
            var copied = false
            contentResolver.openInputStream(parsedUri)?.use { ins ->
                val bytes = BufferedInputStream(ins).use { bis -> readLimited(bis, 16 * 1024 * 1024) }
                FileOutputStream(file).use { it.write(bytes) }
                copied = true
            }
            if (!copied) {
                contentResolver.openAssetFileDescriptor(parsedUri, "r")?.use { afd ->
                    FileInputStream(afd.fileDescriptor).use { fis ->
                        if (afd.startOffset > 0) fis.skip(afd.startOffset)
                        val bytes = BufferedInputStream(fis).use { bis -> readLimited(bis, 16 * 1024 * 1024) }
                        FileOutputStream(file).use { it.write(bytes) }
                        copied = true
                    }
                }
            }
        }
        if (file.exists() && file.length() > 0L) file else null
    }.getOrNull()

    private fun extractCover(song: Song): String = runCatching {
        val r = MediaMetadataRetriever()
        if (song.source == "webdav") {
            val headers = if (song.authHeader.isNotBlank()) mapOf("Authorization" to song.authHeader) else emptyMap()
            r.setDataSource(song.uri, headers)
        } else {
            if (song.uri.startsWith("content://")) {
                val uri = Uri.parse(song.uri)
                val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                if (afd != null) {
                    afd.use {
                        r.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                } else {
                    val local = ensureLocalCopy(song)
                    if (local != null && local.exists()) r.setDataSource(local.absolutePath)
                    else r.setDataSource(this, uri)
                }
            } else {
                r.setDataSource(this, Uri.parse(song.uri))
            }
        }
        val data = r.embeddedPicture
        r.release()
        if (data != null && data.isNotEmpty()) "data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP) else ""
    }.getOrDefault("")

    private fun synchsafeToInt(b1: Int, b2: Int, b3: Int, b4: Int): Int =
        ((b1 and 0x7F) shl 21) or ((b2 and 0x7F) shl 14) or ((b3 and 0x7F) shl 7) or (b4 and 0x7F)

    private fun readIntBE(bytes: ByteArray, off: Int): Int {
        if (off + 4 > bytes.size) return 0
        return ((bytes[off].toInt() and 0xFF) shl 24) or
            ((bytes[off + 1].toInt() and 0xFF) shl 16) or
            ((bytes[off + 2].toInt() and 0xFF) shl 8) or
            (bytes[off + 3].toInt() and 0xFF)
    }

    private fun readIntLE(bytes: ByteArray, off: Int): Int {
        if (off + 4 > bytes.size) return 0
        return (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun encodingTerminatorLength(enc: Int): Int = if (enc == 1 || enc == 2) 2 else 1

    private fun findTextTerminator(bytes: ByteArray, start: Int, enc: Int): Int {
        val term = encodingTerminatorLength(enc)
        var i = start.coerceAtLeast(0)
        while (i + term <= bytes.size) {
            if (term == 1) {
                if (bytes[i].toInt() == 0) return i
            } else {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
            }
            i += 1
        }
        return bytes.size
    }

    private fun decodeId3Text(bytes: ByteArray, enc: Int): String = runCatching {
        when (enc) {
            0 -> String(bytes, Charset.forName("ISO-8859-1"))
            1 -> String(bytes, Charset.forName("UTF-16"))
            2 -> String(bytes, Charset.forName("UTF-16BE"))
            else -> String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault(String(bytes, Charsets.UTF_8))

    private fun extractMp3Apic(bytes: ByteArray): Pair<String, ByteArray>? {
        if (bytes.size < 10) return null
        if (!(bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte())) return null
        val ver = bytes[3].toInt() and 0xFF
        val tagSize = synchsafeToInt(bytes[6].toInt() and 0xFF, bytes[7].toInt() and 0xFF, bytes[8].toInt() and 0xFF, bytes[9].toInt() and 0xFF)
        val end = (10 + tagSize).coerceAtMost(bytes.size)
        var pos = 10
        while (pos + 10 <= end) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val size = if (ver >= 4) synchsafeToInt(
                bytes[pos + 4].toInt() and 0xFF,
                bytes[pos + 5].toInt() and 0xFF,
                bytes[pos + 6].toInt() and 0xFF,
                bytes[pos + 7].toInt() and 0xFF
            ) else readIntBE(bytes, pos + 4)
            if (size <= 0 || pos + 10 + size > end) break
            if (id == "APIC") {
                val frame = bytes.copyOfRange(pos + 10, pos + 10 + size)
                if (frame.size < 5) return null
                val enc = frame[0].toInt() and 0xFF
                var p = 1
                val mimeEnd = findTextTerminator(frame, p, 0)
                val mime = String(frame, p, (mimeEnd - p).coerceAtLeast(0), Charsets.ISO_8859_1).ifBlank { "image/jpeg" }
                p = (mimeEnd + 1).coerceAtMost(frame.size)
                p = (p + 1).coerceAtMost(frame.size) // picture type
                val descEnd = findTextTerminator(frame, p, enc)
                p = (descEnd + encodingTerminatorLength(enc)).coerceAtMost(frame.size)
                if (p >= frame.size) return null
                val img = frame.copyOfRange(p, frame.size)
                if (img.isNotEmpty()) return mime to img
            }
            pos += 10 + size
        }
        return null
    }

    private fun extractMp3Lyrics(bytes: ByteArray): String {
        if (bytes.size < 10) return ""
        if (!(bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte())) return ""
        val ver = bytes[3].toInt() and 0xFF
        val tagSize = synchsafeToInt(bytes[6].toInt() and 0xFF, bytes[7].toInt() and 0xFF, bytes[8].toInt() and 0xFF, bytes[9].toInt() and 0xFF)
        val end = (10 + tagSize).coerceAtMost(bytes.size)
        var pos = 10
        val parts = mutableListOf<String>()
        while (pos + 10 <= end) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val size = if (ver >= 4) synchsafeToInt(
                bytes[pos + 4].toInt() and 0xFF,
                bytes[pos + 5].toInt() and 0xFF,
                bytes[pos + 6].toInt() and 0xFF,
                bytes[pos + 7].toInt() and 0xFF
            ) else readIntBE(bytes, pos + 4)
            if (size <= 0 || pos + 10 + size > end) break
            if (id == "USLT" || id == "SYLT") {
                val frame = bytes.copyOfRange(pos + 10, pos + 10 + size)
                if (frame.size > 5) {
                    val enc = frame[0].toInt() and 0xFF
                    var p = 4 // [enc][lang(3)]
                    val descEnd = findTextTerminator(frame, p, enc)
                    p = (descEnd + encodingTerminatorLength(enc)).coerceAtMost(frame.size)
                    if (p < frame.size) {
                        val text = decodeId3Text(frame.copyOfRange(p, frame.size), enc).replace("\u0000", "\n").trim()
                        if (text.isNotBlank()) parts.add(text)
                    }
                }
            } else if (id == "TXXX") {
                val frame = bytes.copyOfRange(pos + 10, pos + 10 + size)
                if (frame.size > 2) {
                    val enc = frame[0].toInt() and 0xFF
                    var p = 1
                    val descEnd = findTextTerminator(frame, p, enc)
                    val desc = decodeId3Text(frame.copyOfRange(p, descEnd.coerceAtMost(frame.size)), enc).lowercase(Locale.ROOT)
                    p = (descEnd + encodingTerminatorLength(enc)).coerceAtMost(frame.size)
                    if (p < frame.size && (desc.contains("lyric") || desc.contains("歌词") || desc.contains("lrc"))) {
                        val text = decodeId3Text(frame.copyOfRange(p, frame.size), enc).replace("\u0000", "\n").trim()
                        if (text.isNotBlank()) parts.add(text)
                    }
                }
            } else if (id == "COMM") {
                val frame = bytes.copyOfRange(pos + 10, pos + 10 + size)
                if (frame.size > 5) {
                    val enc = frame[0].toInt() and 0xFF
                    var p = 4
                    val descEnd = findTextTerminator(frame, p, enc)
                    val desc = decodeId3Text(frame.copyOfRange(p, descEnd.coerceAtMost(frame.size)), enc).lowercase(Locale.ROOT)
                    p = (descEnd + encodingTerminatorLength(enc)).coerceAtMost(frame.size)
                    if (p < frame.size && (desc.isBlank() || desc.contains("lyric") || desc.contains("歌词"))) {
                        val text = decodeId3Text(frame.copyOfRange(p, frame.size), enc).replace("\u0000", "\n").trim()
                        if (text.isNotBlank()) parts.add(text)
                    }
                }
            }
            pos += 10 + size
        }
        return parts.joinToString("\n").trim()
    }

    private fun extractFlacPicture(bytes: ByteArray): Pair<String, ByteArray>? {
        if (bytes.size < 8) return null
        if (!(bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() && bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte())) return null
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val last = (header and 0x80) != 0
            val type = header and 0x7F
            val len = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + len > bytes.size) break
            if (type == 6 && len >= 32) {
                var p = pos
                p += 4 // picture type
                val mimeLen = readIntBE(bytes, p); p += 4
                if (p + mimeLen > pos + len) return null
                val mime = String(bytes, p, mimeLen, Charsets.UTF_8).ifBlank { "image/jpeg" }; p += mimeLen
                val descLen = readIntBE(bytes, p); p += 4
                p += descLen.coerceAtLeast(0)
                p += 4 * 4 // width height depth colors
                if (p + 4 > pos + len) return null
                val dataLen = readIntBE(bytes, p); p += 4
                if (dataLen <= 0 || p + dataLen > pos + len) return null
                val img = bytes.copyOfRange(p, p + dataLen)
                if (img.isNotEmpty()) return mime to img
            }
            pos += len
            if (last) break
        }
        return null
    }

    private fun extractFlacLyrics(bytes: ByteArray): String {
        if (bytes.size < 8) return ""
        if (!(bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() && bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte())) return ""
        var pos = 4
        val out = mutableListOf<String>()
        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val last = (header and 0x80) != 0
            val type = header and 0x7F
            val len = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + len > bytes.size) break
            if (type == 4 && len >= 8) {
                val end = pos + len
                var p = pos
                val vendorLen = readIntLE(bytes, p); p += 4
                p += vendorLen.coerceAtLeast(0)
                if (p + 4 <= end) {
                    val count = readIntLE(bytes, p); p += 4
                    for (i in 0 until count) {
                        if (p + 4 > end) break
                        val cLen = readIntLE(bytes, p); p += 4
                        if (cLen <= 0 || p + cLen > end) break
                        val c = String(bytes, p, cLen, Charsets.UTF_8)
                        p += cLen
                        val eq = c.indexOf('=')
                        if (eq > 0) {
                            val key = c.substring(0, eq).trim().uppercase(Locale.ROOT)
                            val value = c.substring(eq + 1).trim()
                            if (value.isNotBlank() && (key.contains("LYRICS") || key == "UNSYNCEDLYRICS" || key == "USLT")) {
                                out.add(value)
                            }
                        }
                    }
                }
            }
            pos += len
            if (last) break
        }
        return out.joinToString("\n").trim()
    }

    private fun extractCoverFromBytes(bytes: ByteArray): String {
        val mp3 = extractMp3Apic(bytes)
        if (mp3 != null) {
            return "data:${mp3.first};base64," + Base64.encodeToString(mp3.second, Base64.NO_WRAP)
        }
        val flac = extractFlacPicture(bytes)
        if (flac != null) {
            return "data:${flac.first};base64," + Base64.encodeToString(flac.second, Base64.NO_WRAP)
        }
        return ""
    }

    private fun extractLyricsFromBytes(bytes: ByteArray): String {
        val fromMp3 = extractMp3Lyrics(bytes)
        if (fromMp3.isNotBlank()) return fromMp3
        val fromFlac = extractFlacLyrics(bytes)
        if (fromFlac.isNotBlank()) return fromFlac
        if (bytes.size < 10) return ""
        val parts = mutableListOf<String>()

        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val ver = bytes[3].toInt() and 0xFF
            val tagSize = synchsafeToInt(bytes[6].toInt() and 0xFF, bytes[7].toInt() and 0xFF, bytes[8].toInt() and 0xFF, bytes[9].toInt() and 0xFF)
            val end = (10 + tagSize).coerceAtMost(bytes.size)
            var pos = 10
            while (pos + 10 <= end) {
                val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
                val size = if (ver >= 4) synchsafeToInt(
                    bytes[pos + 4].toInt() and 0xFF,
                    bytes[pos + 5].toInt() and 0xFF,
                    bytes[pos + 6].toInt() and 0xFF,
                    bytes[pos + 7].toInt() and 0xFF
                ) else
                    ((bytes[pos + 4].toInt() and 0xFF) shl 24) or
                        ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 6].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 7].toInt() and 0xFF)
                if (size <= 0 || pos + 10 + size > end) break
                if (id == "USLT" || id == "SYLT") {
                    val frame = bytes.copyOfRange(pos + 10, pos + 10 + size)
                    if (frame.size > 5) {
                        val text = decodeId3Text(frame.copyOfRange(4, frame.size), frame[0].toInt() and 0xFF).replace("\u0000", "\n").trim()
                        if (text.isNotBlank()) parts.add(text)
                    }
                }
                pos += 10 + size
            }
        }

        if (parts.isEmpty()) {
            val text = String(bytes, Charsets.UTF_8)
            listOf("LYRICS=", "UNSYNCEDLYRICS=", "USLT=").forEach { key ->
                var from = 0
                while (true) {
                    val idx = text.indexOf(key, from, true)
                    if (idx < 0) break
                    val start = idx + key.length
                    val end = text.indexOf('\u0000', start).let { if (it < 0) text.length else it }
                    val value = text.substring(start, end).trim()
                    if (value.isNotBlank()) parts.add(value)
                    from = end + 1
                }
            }
        }
        return parts.joinToString("\n").trim()
    }

    private fun readTrackRichMeta(song: Song): String {
        val o = JSONObject().put("coverDataUrl", "").put("lyrics", "").put("artist", "")
        ensureLocalCopy(song)?.let { f ->
            val bytes = runCatching {
                BufferedInputStream(f.inputStream()).use { bis ->
                    readLimited(bis, 4 * 1024 * 1024)
                }
            }.getOrDefault(ByteArray(0))
            val lyrics = runCatching { extractLyricsFromBytes(bytes) }.getOrDefault("")
            if (lyrics.isNotBlank()) o.put("lyrics", lyrics)
            val cover = runCatching { extractCoverFromBytes(bytes) }.getOrDefault("")
            if (cover.isNotBlank() && cover.length <= 1_200_000) o.put("coverDataUrl", cover)
            val tagArtist = runCatching { AudioFileIO.read(f).tag?.getFirst(FieldKey.ARTIST).orEmpty().trim() }.getOrDefault("")
            if (tagArtist.isNotBlank()) o.put("artist", tagArtist)
            if (song.source != "webdav" && (o.optString("lyrics", "").isBlank() || o.optString("coverDataUrl", "").isBlank())) {
                val fallback = runCatching { readRichMetaByJaudiotagger(f) }.getOrDefault("" to "")
                if (o.optString("lyrics", "").isBlank() && fallback.second.isNotBlank()) {
                    o.put("lyrics", fallback.second)
                }
                if (o.optString("coverDataUrl", "").isBlank() && fallback.first.isNotBlank()) {
                    o.put("coverDataUrl", fallback.first)
                }
            }
        }
        if (o.optString("coverDataUrl", "").isBlank()) {
            o.put("coverDataUrl", extractCover(song))
        }
        if (o.optString("artist", "").isBlank() && song.artist.isNotBlank() && song.artist != "本地目录" && song.artist != "WebDAV") {
            o.put("artist", song.artist)
        }
        return o.toString()
    }

    private fun readRichMetaByJaudiotagger(file: File): Pair<String, String> = runCatching {
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tag
        if (tag == null) return "" to ""

        val lyrics = runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrDefault("").trim()

        val coverDataUrl = runCatching {
            val artwork = tag.firstArtwork ?: return@runCatching ""
            val data = artwork.binaryData ?: return@runCatching ""
            if (data.isEmpty()) return@runCatching ""
            val mime = artwork.mimeType?.trim().orEmpty().ifBlank { "image/jpeg" }
            "data:$mime;base64," + Base64.encodeToString(data, Base64.NO_WRAP)
        }.getOrDefault("")

        coverDataUrl to lyrics
    }.getOrDefault("" to "")

    private fun testWebdavConnection(configJson: String): String = runCatching {
        val cfg = parseWebdavConfig(configJson)
            ?: return JSONObject().put("ok", false).put("message", "配置不完整").toString()
        val conn = openWebdavConnection("${cfg.protocol}://${cfg.host}:${cfg.port}/", cfg).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
            if (cfg.username.isNotBlank()) setRequestProperty("Authorization", authHeader(cfg.username, cfg.password))
        }
        val code = conn.responseCode
        conn.disconnect()
        val ok = code in 200..399 || code == 405
        val msg = when {
            ok -> "连接成功 (HTTP $code)"
            code == 401 || code == 403 -> "鉴权失败 (HTTP $code)"
            else -> "连接失败 (HTTP $code)"
        }
        JSONObject().put("ok", ok).put("message", msg).toString()
    }.getOrElse {
        JSONObject().put("ok", false).put("message", "连接失败: ${it.message ?: "未知错误"}").toString()
    }

    private fun scrubLegacyWebdavSettings(raw: String): String {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        val host = obj.optString("host", "").trim()
        if (host == "192.168.31.11") {
            obj.put("host", "127.0.0.1")
        }
        return obj.toString()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun getSongsJson(): String {
            ensureSongsAvailable(blocking = false)
            runOnUiThread { syncPlayerQueue() }
            val songs = currentSongsSnapshot()
            val arr = JSONArray()
            songs.forEach {
                arr.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("durationMs", it.durationMs).put("uri", it.uri).put("source", it.source))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun getTrackMetaJson(index: Int): String {
            if (!ensureSongsAvailable(blocking = false)) return JSONObject().put("coverDataUrl", "").put("lyrics", "").toString()
            val songs = currentSongsSnapshot()
            val i = index.coerceIn(0, songs.lastIndex)
            val s = songs[i]
            synchronized(richMetaCache) { richMetaCache[s.id] }?.let { return it }
            warmupTrackMeta(i)
            return JSONObject()
                .put("coverDataUrl", "")
                .put("lyrics", "")
                .put("artist", if (s.artist == "本地目录" || s.artist == "WebDAV") "" else s.artist)
                .put("title", s.title)
                .toString()
        }

        @JavascriptInterface
        fun playAtIndex(index: Int) {
            if (!ensureSongsAvailable(blocking = true)) return
            runOnUiThread {
                val songs = currentSongsSnapshot()
                val i = index.coerceIn(0, songs.lastIndex)
                syncPlayerQueue()
                applyAuthForIndex(i)
                player.seekToDefaultPosition(i)
                player.playWhenReady = true
                if (playbackModeController.mode() == 2) {
                    playbackModeController.resetShuffleRound(songs.size, i, markCurrent = true)
                }
                emitPlaybackStateToWeb()
            }
        }

        @JavascriptInterface
        fun nextTrack() {
            if (!ensureSongsAvailable(blocking = true)) return
            runOnUiThread {
                val songs = currentSongsSnapshot()
                syncPlayerQueue()
                val current = player.currentMediaItemIndex.coerceAtLeast(0)
                val i = playbackModeController.resolveNextIndex(current, songs.size)
                applyAuthForIndex(i)
                player.seekToDefaultPosition(i)
                player.playWhenReady = true
                emitPlaybackStateToWeb()
            }
        }

        @JavascriptInterface
        fun prevTrack() {
            if (!ensureSongsAvailable(blocking = true)) return
            runOnUiThread {
                val songs = currentSongsSnapshot()
                syncPlayerQueue()
                val current = player.currentMediaItemIndex.coerceAtLeast(0)
                val i = playbackModeController.resolvePrevIndex(current, songs.size)
                applyAuthForIndex(i)
                player.seekToDefaultPosition(i)
                player.playWhenReady = true
                emitPlaybackStateToWeb()
            }
        }

        @JavascriptInterface
        fun togglePlayPause() {
            if (!ensureSongsAvailable(blocking = true)) return
            runOnUiThread {
                syncPlayerQueue()
                if (player.currentMediaItemIndex < 0) {
                    applyAuthForIndex(0)
                    player.seekToDefaultPosition(0)
                    player.playWhenReady = true
                } else if (player.isPlaying) player.pause() else player.play()
                emitPlaybackStateToWeb()
            }
        }

        @JavascriptInterface
        fun seekTo(positionMs: Long) = runOnUiThread {
            if (player.currentMediaItemIndex < 0) return@runOnUiThread
            player.seekTo(positionMs.coerceAtLeast(0))
            emitPlaybackStateToWeb()
        }

        @JavascriptInterface
        fun getPlaybackStateJson(): String {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val now = buildPlaybackStateJson().toString()
                playbackStateCache = now
                return now
            }
            var out = playbackStateCache
            val latch = CountDownLatch(1)
            runOnUiThread {
                out = buildPlaybackStateJson().toString()
                playbackStateCache = out
                latch.countDown()
            }
            latch.await(120, TimeUnit.MILLISECONDS)
            return out
        }

        @JavascriptInterface
        fun getPlayMode(): Int =
            if (::playbackModeController.isInitialized) playbackModeController.mode()
            else getSharedPreferences(prefName, MODE_PRIVATE).getInt(keyPlayMode, 2).coerceIn(0, 2)

        @JavascriptInterface
        fun setPlayMode(mode: Int) {
            if (!::playbackModeController.isInitialized) return
            val songs = currentSongsSnapshot()
            val currentIndex = if (::player.isInitialized) player.currentMediaItemIndex else -1
            playbackModeController.setMode(mode, songs.size, currentIndex)
        }

        @JavascriptInterface
        fun getLocalMusicDirsJson(): String {
            val arr = JSONArray()
            localMusicDirsFromPrefs().forEach(arr::put)
            return arr.toString()
        }

        @JavascriptInterface
        fun requestAddLocalMusicDir() = runOnUiThread {
            getSharedPreferences(prefName, MODE_PRIVATE).edit().putBoolean(keyPendingLocalPick, true).apply()
            openTreeLauncher.launch(null)
        }

        @JavascriptInterface
        fun addLocalMusicDir(path: String): String {
            val value = normalizeLocalPath(path)
            if (value.isBlank()) return getLocalMusicDirsJson()
            val next = localMusicDirsFromPrefs().toMutableSet()
            next.add(value)
            saveLocalMusicDirsToPrefs(next.toList())
            return getLocalMusicDirsJson()
        }

        @JavascriptInterface
        fun removeLocalMusicDir(path: String): String {
            val value = normalizeLocalPath(path)
            val next = localMusicDirsFromPrefs().filter { !normalizeLocalPath(it).equals(value, true) }
            saveLocalMusicDirsToPrefs(next)
            refreshSongsInBackground(emitToWeb = true)
            return getLocalMusicDirsJson()
        }

        @JavascriptInterface
        fun listLocalDirectoryJson(path: String): String {
            val arr = JSONArray()
            listLocalDirectories(path).forEach { (dirPath, dirName) ->
                arr.put(JSONObject().put("path", dirPath).put("name", dirName))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun scanLocalMusicNow(): String {
            return runCatching {
                loadSongsIntoCache()
                runOnUiThread {
                    syncPlayerQueue()
                    emitPlaybackStateToWeb()
                }
                val songs = currentSongsSnapshot()
                val localCount = songs.count { it.source == "local" }
                val webdavCount = songs.count { it.source == "webdav" }
                JSONObject()
                    .put("ok", true)
                    .put("localCount", localCount)
                    .put("webdavCount", webdavCount)
                    .put("totalCount", songs.size)
                    .toString()
            }.getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("message", "扫描失败: ${it.message ?: "未知错误"}")
                    .toString()
            }
        }

        @JavascriptInterface
        fun refreshLibraryNow(): String {
            return scanLocalMusicNow()
        }

        @JavascriptInterface
        fun getWebdavSettingsJson(): String {
            val raw = getSharedPreferences(prefName, MODE_PRIVATE).getString(keyWebdavSettings, null)
            if (raw.isNullOrBlank()) {
                return JSONObject().put("protocol", "https").put("host", "").put("port", "5006").put("username", "").put("password", "").toString()
            }
            val scrubbed = scrubLegacyWebdavSettings(raw)
            if (scrubbed != raw) {
                getSharedPreferences(prefName, MODE_PRIVATE).edit().putString(keyWebdavSettings, scrubbed).apply()
            }
            return scrubbed
        }

        @JavascriptInterface
        fun setWebdavSettingsJson(json: String) {
            getSharedPreferences(prefName, MODE_PRIVATE).edit().putString(keyWebdavSettings, json).apply()
        }

        @JavascriptInterface
        fun testWebdavConnectionJson(configJson: String): String = testWebdavConnection(configJson)

        @JavascriptInterface
        fun listWebdavDirectoryJson(configJson: String, path: String): String {
            val cfg = parseWebdavConfig(configJson) ?: return JSONArray().toString()
            val p = path.ifBlank { cfg.folderPath }
            val arr = JSONArray()
            val dirs = runCatching {
                var items = webdavGateway.propfind(cfg, p, "1").filter { it.isCollection }
                if (items.isEmpty() && p.isNotBlank()) {
                    val p1 = if (p.endsWith("/")) p else "$p/"
                    if (p1 != p) items = webdavGateway.propfind(cfg, p1, "1").filter { it.isCollection }
                }
                if (items.isEmpty() && p.length > 1 && p.endsWith("/")) {
                    val p2 = p.trimEnd('/')
                    if (p2.isNotBlank()) items = webdavGateway.propfind(cfg, p2, "1").filter { it.isCollection }
                }
                if (items.isEmpty()) {
                    items = webdavGateway.propfind(cfg, p, "infinity").filter { it.isCollection }
                }
                items
            }.getOrDefault(emptyList())
            dirs.forEach {
                arr.put(JSONObject().put("path", it.path).put("name", it.name))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun getWebdavLibrariesJson(): String = webdavLibrariesToJson(webdavLibrariesFromPrefs())

        @JavascriptInterface
        fun addWebdavLibraryJson(configJson: String): String {
            val cfg = parseWebdavConfig(configJson) ?: return webdavLibrariesToJson(webdavLibrariesFromPrefs())
            val list = webdavLibrariesFromPrefs().filter { it.id != cfg.id }.toMutableList()
            list.add(cfg)
            saveWebdavLibrariesToPrefs(list)
            return webdavLibrariesToJson(list)
        }

        @JavascriptInterface
        fun removeWebdavLibrary(id: String): String {
            val list = webdavLibrariesFromPrefs().filter { it.id != id }
            saveWebdavLibrariesToPrefs(list)
            refreshSongsInBackground(emitToWeb = true)
            return webdavLibrariesToJson(list)
        }

        @JavascriptInterface
        fun showToast(message: String) = runOnUiThread {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun requestExitConfirm() = runOnUiThread {
            showExitConfirmDialog()
        }

        @JavascriptInterface
        fun setMainSheetOpen(open: Boolean) {
            isMainSheetOpen = open
        }
    }
}




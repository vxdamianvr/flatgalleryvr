package com.yourname.flatgallery

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class ScanEntry(
    val path: String,
    val parentPath: String,
    val uri: Uri,
    val width: Int,
    val height: Int,
    val exifOrientation: Int,
    val sizeBytes: Long,
    val lastModified: Long,
    val shapeBucket: String
)

interface ScanListener {
    fun onScanBatchStreamed(newEntries: List<ScanEntry>)
    fun onScanCompleted(portrait: List<Uri>, landscape: List<Uri>, square: List<Uri>)
}

object GlobalCache {
    private const val CACHE_FILE_NAME = "scan_cache.bin"
    private const val CACHE_TMP_FILE_NAME = "scan_cache.tmp"
    private const val CACHE_MAGIC = 0x46475343 // "FGSC"
    private const val CACHE_VERSION = 1
    private const val INCOMPLETE_FLAG_BIT = 0x40000000

    var isScanned = false
    @Volatile var isScanning = false
    @Volatile var pendingFolderRescan = false
    @Volatile var isCacheLoadedFromDisk = false
    @Volatile var isCacheComplete = false
    val scanLock = Any()
    val cacheDiskLock = Any()

    val portraitUris = mutableListOf<Uri>()
    val landscapeUris = mutableListOf<Uri>()
    val squareUris = mutableListOf<Uri>()

    val entriesByPath = ConcurrentHashMap<String, ScanEntry>()
    val parentPathByPath = ConcurrentHashMap<String, String>()
    val folderFirstImageCache = ConcurrentHashMap<String, String>()

    // Explorer & recycling verification counters
    val explorerStaleThumbSkips = AtomicInteger(0)
    val explorerThumbDecodes = AtomicInteger(0)
    val explorerRowRecycles = AtomicInteger(0)
    val explorerHeaderRecycles = AtomicInteger(0)
    val explorerTypeMismatchCount = AtomicInteger(0)

    var transitionIndex = 0 
    var favoritePaths = mutableSetOf<String>()
    var nightModeLevel = 0
    val nightModeListeners = mutableListOf<() -> Unit>()
    val scanListeners = LinkedHashSet<ScanListener>()

    val memoryCache: LruCache<String, Bitmap> by lazy {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 4
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    val explorerExecutor: ExecutorService = Executors.newFixedThreadPool(4)
    val decodeExecutor: ExecutorService = Executors.newFixedThreadPool(4)

    fun computeShapeBucket(w: Int, h: Int): String {
        val ratio = w.toFloat() / h.toFloat()
        return when {
            ratio > 1.05f -> "LANDSCAPE"
            ratio < 0.95f -> "PORTRAIT"
            else -> "SQUARE"
        }
    }

    fun isPathIncluded(path: String, selectedPaths: Set<String>, excludedPaths: Set<String>): Boolean {
        for (ex in excludedPaths) {
            if (path == ex || path.startsWith(ex + File.separatorChar)) return false
        }
        for (sel in selectedPaths) {
            if (path == sel || path.startsWith(sel + File.separatorChar)) return true
        }
        return false
    }

    fun loadDiskCacheIfNeeded(context: Context): Boolean {
        if (isCacheLoadedFromDisk) return true
        synchronized(cacheDiskLock) {
            if (isCacheLoadedFromDisk) return true
            val startMs = SystemClock.elapsedRealtime()
            val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
            if (!cacheFile.exists()) {
                isCacheComplete = false
                isCacheLoadedFromDisk = true
                Log.i("FlatGalleryPerf", "Disk cache not found (${SystemClock.elapsedRealtime() - startMs}ms)")
                return false
            }
            return try {
                val bytes = cacheFile.readBytes()
                val buf = ByteBuffer.wrap(bytes)
                if (buf.remaining() < 12) {
                    throw IllegalStateException("Cache file too short (${bytes.size} bytes)")
                }
                val magic = buf.int
                val version = buf.int
                if (magic != CACHE_MAGIC || version != CACHE_VERSION) {
                    throw IllegalStateException("Invalid cache header: magic=$magic version=$version")
                }
                val rawCount = buf.int
                val complete = (rawCount and INCOMPLETE_FLAG_BIT) == 0
                val count = rawCount and INCOMPLETE_FLAG_BIT.inv()
                if (count < 0 || count > 1_000_000) {
                    throw IllegalStateException("Corrupt entry count: $count")
                }
                val mapCap = (count * 4 / 3) + 16
                val loaded = HashMap<String, ScanEntry>(mapCap)
                val parents = HashMap<String, String>(mapCap)
                val uriBuilder = Uri.Builder().scheme("file").authority("")
                var lastParent = ""
                for (i in 0 until count) {
                    if (buf.remaining() < 2) {
                        throw IllegalStateException("Truncated path length at $i")
                    }
                    val pathLen = buf.short.toInt() and 0xFFFF
                    if (pathLen <= 0 || buf.remaining() < pathLen + 28) {
                        throw IllegalStateException("Truncated entry body at $i")
                    }
                    val pos = buf.position()
                    val path = String(bytes, pos, pathLen, Charsets.UTF_8)
                    buf.position(pos + pathLen)
                    val w = buf.int
                    val h = buf.int
                    val exif = buf.int
                    val size = buf.long
                    val lastMod = buf.long
                    if (path.isEmpty() || w <= 0 || h <= 0) {
                        throw IllegalStateException("Corrupt entry at $i")
                    }
                    val sepIdx = path.lastIndexOf(File.separatorChar)
                    val parent = if (sepIdx > 0) {
                        if (sepIdx == lastParent.length && path.regionMatches(0, lastParent, 0, sepIdx)) {
                            lastParent
                        } else {
                            path.substring(0, sepIdx).also { lastParent = it }
                        }
                    } else "unknown"
                    val bucket = computeShapeBucket(w, h)
                    val entry = ScanEntry(
                        path = path,
                        parentPath = parent,
                        uri = uriBuilder.path(path).build(),
                        width = w,
                        height = h,
                        exifOrientation = exif,
                        sizeBytes = size,
                        lastModified = lastMod,
                        shapeBucket = bucket
                    )
                    loaded[path] = entry
                    parents[path] = parent
                }
                entriesByPath.clear()
                entriesByPath.putAll(loaded)
                parentPathByPath.putAll(parents)
                isCacheComplete = complete && loaded.isNotEmpty()
                isCacheLoadedFromDisk = true
                Log.i(
                    "FlatGalleryPerf",
                    "Disk cache loaded (complete=$isCacheComplete): ${loaded.size} entries in ${SystemClock.elapsedRealtime() - startMs}ms"
                )
                loaded.isNotEmpty()
            } catch (t: Throwable) {
                Log.w("FlatGalleryPerf", "Corrupt or unreadable scan cache, falling back to full scan: ${t.message}")
                entriesByPath.clear()
                isCacheComplete = false
                try { cacheFile.delete() } catch (_: Exception) {}
                isCacheLoadedFromDisk = true
                false
            }
        }
    }

    fun writeDiskCacheAtomically(context: Context, entries: Collection<ScanEntry>, isComplete: Boolean = true) {
        synchronized(cacheDiskLock) {
            val startMs = SystemClock.elapsedRealtime()
            val tmpFile = File(context.filesDir, CACHE_TMP_FILE_NAME)
            val destFile = File(context.filesDir, CACHE_FILE_NAME)
            try {
                FileOutputStream(tmpFile).use { fos ->
                    DataOutputStream(BufferedOutputStream(fos, 65536)).use { dos ->
                        dos.writeInt(CACHE_MAGIC)
                        dos.writeInt(CACHE_VERSION)
                        val rawCount = if (isComplete) entries.size else (entries.size or INCOMPLETE_FLAG_BIT)
                        dos.writeInt(rawCount)
                        for (e in entries) {
                            dos.writeUTF(e.path)
                            dos.writeInt(e.width)
                            dos.writeInt(e.height)
                            dos.writeInt(e.exifOrientation)
                            dos.writeLong(e.sizeBytes)
                            dos.writeLong(e.lastModified)
                        }
                        dos.flush()
                        fos.fd.sync()
                    }
                }
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                isCacheComplete = isComplete
                Log.i(
                    "FlatGalleryPerf",
                    "Atomically wrote ${entries.size} cache entries (complete=$isComplete) in ${SystemClock.elapsedRealtime() - startMs}ms"
                )
            } catch (t: Throwable) {
                Log.e("FlatGalleryPerf", "Failed to write scan cache: ${t.message}")
                try { tmpFile.delete() } catch (_: Exception) {}
            }
        }
    }

    fun rebuildBucketsFromCachedEntries(selectedPaths: Set<String>, excludedPaths: Set<String>) {
        val pList = ArrayList<Uri>()
        val lList = ArrayList<Uri>()
        val sList = ArrayList<Uri>()
        val sortedValues = entriesByPath.values.sortedBy { it.path }
        for (e in sortedValues) {
            if (!isPathIncluded(e.path, selectedPaths, excludedPaths)) continue
            when (e.shapeBucket) {
                "PORTRAIT" -> pList.add(e.uri)
                "LANDSCAPE" -> lList.add(e.uri)
                else -> sList.add(e.uri)
            }
        }
        portraitUris.clear()
        portraitUris.addAll(pList)
        landscapeUris.clear()
        landscapeUris.addAll(lList)
        squareUris.clear()
        squareUris.addAll(sList)
        isScanned = isCacheComplete && (pList.isNotEmpty() || lList.isNotEmpty() || sList.isNotEmpty())
    }
}

class MainActivity : Activity() {

    companion object {
        private const val PERMISSION_REQ_CODE = 1001
    }

    // --- Core Layouts ---
    private lateinit var rootLayout: FrameLayout
    private lateinit var wizardLayout: LinearLayout
    private lateinit var slideshowLayout: FrameLayout
    private lateinit var settingsOverlay: LinearLayout
    private lateinit var favoritePromptOverlay: LinearLayout
    private lateinit var favoritePromptTitle: TextView
    private lateinit var favoritePromptSubtitle: TextView
    private var pendingFavoritePath: String? = null
    private var isPendingUnfavorite = false
    
    // --- Dynamic Settings UI Tracking ---
    private val settingsWideViews = mutableListOf<View>()
    private val settingsNarrowViews = mutableListOf<View>()

    // --- Wizard UI ---
    private lateinit var wizardTitle: TextView
    private lateinit var wizardSubtitle: TextView
    private lateinit var wizardActionContainer: LinearLayout

    // --- Slideshow UI ---
    private lateinit var imageView: ImageView
    private lateinit var infoTextView: TextView
    private lateinit var overlayInfoText: TextView
    private lateinit var controlsLayout: LinearLayout
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var playPauseButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var timerButton: Button
    private lateinit var topControlsLayout: LinearLayout
    private lateinit var leftArrow: TextView
    private lateinit var rightArrow: TextView
    private lateinit var starButton: TextView
    
    // --- Custom Explorer ---
    private lateinit var explorerOverlay: FrameLayout
    private lateinit var leftScroll: ScrollView
    private lateinit var folderTreeLayout: LinearLayout
    private lateinit var contentListView: ListView
    private lateinit var currentPathTextView: TextView
    private lateinit var selectedFoldersTextView: TextView
    private lateinit var explorerTitle: TextView
    private lateinit var explorerHeader: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var closeExplorerBtn: Button
    private lateinit var clearFoldersBtn: Button
    private lateinit var startBtn: Button

    private var selectedDirectory: File? = null
    private val selectedFolders = mutableListOf<File>()
    private val excludedFolders = mutableListOf<File>() 
    private var preExplorerSelectedPaths = emptySet<String>()
    private var preExplorerExcludedPaths = emptySet<String>()
    private var explorerLoadGeneration = 0

    // --- Playback Data ---
    private var activeUris = mutableListOf<Uri>()
    private val activeUriSet = HashSet<String>()
    private val playbackOrder = mutableListOf<Int>()
    private var playheadIndex = 0
    private var isPlaying = true
    private var isShuffle = true
    private var isFavoritesMode = false
    private var isKenBurns = false
    private var chosenShape = "NONE"
    private var hasPendingBoundaryRebuild = false
    private var pendingCompletedUris: List<Uri>? = null
    private var isShowingPermissionDeniedScreen = false

    // --- Window Visibility, Resize & Perf Tracking ---
    private var isWindowVisible = false
    private var lastDecodedTargetSize = 0
    private var pendingLayoutChangeFrames = 0
    private val resizeHandler = Handler(Looper.getMainLooper())
    private val resizeRunnable = Runnable { checkAndRedecodeOnResize() }
    private var activityStartElapsedMs = 0L
    private var shapePickElapsedMs = 0L
    private var hasLoggedFirstImage = false

    // --- Live Shape Picker Buttons ---
    private var shapeBtnPortrait: Button? = null
    private var shapeBtnSquare: Button? = null
    private var shapeBtnLandscape: Button? = null

    // --- Timers & Config ---
    private val timerOptions = listOf(1_000L, 3_000L, 15_000L, 30_000L, 60_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L)
    private val timerLabels = listOf("1s", "3s", "15s", "30s", "1m", "5m", "15m", "30m", "1h")
    private var currentTimerIndex = 3
    private lateinit var windowId: String

    private val slideHandler = Handler(Looper.getMainLooper())
    private val slideRunnable = Runnable { nextImage(auto = true) }

    private val uiFadeHandler = Handler(Looper.getMainLooper())
    private val uiFadeRunnable = Runnable { fadeOutUi() }
    private val fadeableViews = mutableListOf<View>()

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    // --- Professional Glyph Constants ---
    private val ICON_PREV = "\u23EE\uFE0E"
    private val ICON_NEXT = "\u23ED\uFE0E"
    private val ICON_PLAY = "\u25B6\uFE0E"
    private val ICON_PAUSE = "\u2016\uFE0E"
    private val ICON_SHUFFLE = "\u21C4" 
    private val ICON_LOOP = "\u21BB" 
    private val ICON_TIMER = "\u23F2\uFE0E"
    private val ICON_NIGHT = "\u263D\uFE0E"
    
    private lateinit var nightModeOverlay: View
    private lateinit var nightModeButton: TextView
    private val nightModeListener: () -> Unit = { updateNightModeUi() }

    private val scanListener = object : ScanListener {
        override fun onScanBatchStreamed(newEntries: List<ScanEntry>) {
            handleStreamedScanBatch(newEntries)
        }

        override fun onScanCompleted(portrait: List<Uri>, landscape: List<Uri>, square: List<Uri>) {
            handleScanCompletedSnapshot(portrait, landscape, square)
        }
    }

    private fun View.addHoverEffect() {
        setOnHoverListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    v.animate().alpha(0.6f).setDuration(150).start()
                    true
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    v.animate().alpha(1.0f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityStartElapsedMs = SystemClock.elapsedRealtime()
        hasLoggedFirstImage = false
        windowId = savedInstanceState?.getString("WINDOW_ID") ?: UUID.randomUUID().toString()
        Log.i("FlatGalleryPerf", "Activity onCreate start win=${windowId.take(8)}")

        // Initialize shared process-wide LruCache in GlobalCache
        GlobalCache.memoryCache
        val addedListener = GlobalCache.scanListeners.add(scanListener)
        Log.i(
            "FlatGalleryPerf",
            "Window ${windowId.take(8)} onCreate scanListener added=$addedListener activeListeners=${GlobalCache.scanListeners.size}"
        )

        rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        wizardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(64, 64, 64, 64)
        }
        
        wizardTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        wizardSubtitle = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        
        wizardActionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        wizardLayout.addView(wizardTitle)
        wizardLayout.addView(wizardSubtitle)
        wizardLayout.addView(wizardActionContainer)

        slideshowLayout = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { resetUiFadeTimer() }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val newW = right - left
                val newH = bottom - top
                val oldW = oldRight - oldLeft
                val oldH = oldBottom - oldTop
                if (newW > 0 && newH > 0 && (newW != oldW || newH != oldH)) {
                    pendingLayoutChangeFrames++
                    resizeHandler.removeCallbacks(resizeRunnable)
                    resizeHandler.postDelayed(resizeRunnable, 250L)
                }
            }
        }
        
        nightModeOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        infoTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val settingsButton = TextView(this).apply {
            text = "≡" 
            setTextColor(Color.WHITE)
            textSize = 32f
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#33000000")) 
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(24, 24, 0, 0)
            }
            addHoverEffect()
            setOnClickListener { 
                resetUiFadeTimer()
                showSettingsMenu() 
            }
        }

        topControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, 24, 24, 0)
            }
        }

        starButton = TextView(this).apply {
            text = "☆"
            setTextColor(Color.WHITE)
            textSize = 32f
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#33000000"))
            addHoverEffect()
            setOnClickListener { 
                resetUiFadeTimer()
                toggleFavoriteCurrent()
            }
        }
        
        nightModeButton = TextView(this).apply {
            text = ICON_NIGHT
            setTextColor(Color.WHITE)
            textSize = 32f
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#33000000"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 4 }
            addHoverEffect()
            setOnClickListener { 
                resetUiFadeTimer()
                toggleNightMode()
            }
        }

        val newWindowButton = TextView(this).apply {
            text = "❐"
            setTextColor(Color.WHITE)
            textSize = 28f
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#33000000"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 4 }
            addHoverEffect()
            setOnClickListener { 
                resetUiFadeTimer()
                spawnNewWindow()
            }
        }

        topControlsLayout.addView(starButton)
        topControlsLayout.addView(nightModeButton)
        topControlsLayout.addView(newWindowButton)

        leftArrow = TextView(this).apply {
            text = "◀"
            setTextColor(Color.parseColor("#80FFFFFF")) 
            textSize = 36f
            setPadding(48, 48, 48, 48)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }
            addHoverEffect()
            setOnClickListener { 
                resetUiFadeTimer()
                prevImage() 
            }
        }

        rightArrow = TextView(this).apply {
            text = "▶"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 36f
            setPadding(48, 48, 48, 48)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            addHoverEffect()
            setOnClickListener { 
                resetUiFadeTimer()
                nextImage(auto = false) 
            }
        }

        controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            weightSum = 5f 
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        timerButton = createMinimalButton("") { toggleTimer() }
        prevBtn = createMinimalButton("") { prevImage() }
        playPauseButton = createMinimalButton("") { togglePlayPause() }
        nextBtn = createMinimalButton("") { nextImage(auto = false) }
        shuffleButton = createMinimalButton("") { toggleShuffle() }

        controlsLayout.addView(timerButton)
        controlsLayout.addView(prevBtn)
        controlsLayout.addView(playPauseButton)
        controlsLayout.addView(nextBtn)
        controlsLayout.addView(shuffleButton)

        overlayInfoText = TextView(this).apply {
            setTextColor(Color.parseColor("#B3FFFFFF")) 
            textSize = 14f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#33000000"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
                bottomMargin = 16 
                marginStart = 24
            }
        }

        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        }
        bottomContainer.addView(overlayInfoText)
        bottomContainer.addView(controlsLayout)

        slideshowLayout.addView(imageView)
        slideshowLayout.addView(nightModeOverlay)
        slideshowLayout.addView(infoTextView)
        slideshowLayout.addView(leftArrow)
        slideshowLayout.addView(rightArrow)
        slideshowLayout.addView(settingsButton)
        slideshowLayout.addView(topControlsLayout)
        slideshowLayout.addView(bottomContainer)

        fadeableViews.addAll(listOf(settingsButton, topControlsLayout, leftArrow, rightArrow, bottomContainer))

        rootLayout.addView(wizardLayout)
        rootLayout.addView(slideshowLayout)

        buildFileExplorerOverlay(rootLayout)
        buildSettingsOverlay(rootLayout)
        buildFavoritePromptOverlay(rootLayout)
        updateResponsiveUi()
        
        setContentView(rootLayout)
        if (BuildConfig.DEBUG) {
            registerTestReceiver()
        }
        restoreWindowState()
    }

    private fun getRequiredStoragePermission(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasStoragePermission(): Boolean {
        val perm = getRequiredStoragePermission()
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionRequestPendingScreen() {
        wizardLayout.visibility = View.VISIBLE
        slideshowLayout.visibility = View.GONE
        wizardTitle.text = "Storage Permission"
        wizardSubtitle.text = "Please allow Flat Gallery to access photos on your device when prompted."
        wizardActionContainer.removeAllViews()
        shapeBtnPortrait = null
        shapeBtnSquare = null
        shapeBtnLandscape = null
    }

    private fun showPermissionDeniedScreen() {
        isShowingPermissionDeniedScreen = true
        wizardLayout.visibility = View.VISIBLE
        slideshowLayout.visibility = View.GONE
        wizardTitle.text = "Storage Permission Required"
        wizardSubtitle.text = "Flat Gallery needs permission to read images on your headset so it can display your folders and photo frames.\n\nPlease grant Photos & Videos access in App Settings, or tap Try Again."
        wizardActionContainer.removeAllViews()
        shapeBtnPortrait = null
        shapeBtnSquare = null
        shapeBtnLandscape = null

        wizardActionContainer.addView(createSolidThemeButton("Open App Settings") {
            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            } catch (_: Exception) {}
        })

        wizardActionContainer.addView(createSolidThemeButton("Try Again") {
            requestPermissions(arrayOf(getRequiredStoragePermission()), PERMISSION_REQ_CODE)
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            if (hasStoragePermission()) {
                Log.i("FlatGalleryPerf", "Storage permission GRANTED via runtime dialog")
                isShowingPermissionDeniedScreen = false
                restoreWindowState()
            } else {
                Log.w("FlatGalleryPerf", "Storage permission DENIED via runtime dialog; showing explanation & Settings UI")
                showPermissionDeniedScreen()
            }
        }
    }

    private fun createMinimalButton(btnText: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = btnText
            isAllCaps = false 
            setTextColor(Color.WHITE)
            textSize = 20f
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16, 24, 16, 24)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addHoverEffect()
            setOnClickListener { 
                resetUiFadeTimer()
                onClick() 
            }
        }
    }

    private fun createSolidThemeButton(btnText: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = btnText
            isAllCaps = false 
            setTextColor(Color.WHITE)
            textSize = 18f
            
            val btnShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#2C2C2E"))
            }
            background = btnShape
            
            setPadding(48, 32, 48, 32)
            layoutParams = LinearLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 24
            }
            addHoverEffect()
            setOnClickListener { onClick() }
        }
    }

    // --- State Machine ---
    private fun showWelcomeScreen() {
        wizardLayout.visibility = View.VISIBLE
        slideshowLayout.visibility = View.GONE
        wizardTitle.text = "Welcome to Flat Gallery"
        wizardSubtitle.text = "Set up your custom portrait, landscape, or square frame."
        wizardActionContainer.removeAllViews()
        shapeBtnPortrait = null
        shapeBtnSquare = null
        shapeBtnLandscape = null
        wizardActionContainer.addView(createSolidThemeButton("Next ➔") { showFolderPickScreen() })
    }

    private fun showFolderPickScreen() {
        wizardLayout.visibility = View.VISIBLE
        slideshowLayout.visibility = View.GONE
        wizardTitle.text = "Select Media Folders"
        wizardSubtitle.text = "Pick the folders you want this frame to cycle through.\nParent folders include subfolders, but you can manually uncheck children to exclude them."
        wizardActionContainer.removeAllViews()
        shapeBtnPortrait = null
        shapeBtnSquare = null
        shapeBtnLandscape = null
        wizardActionContainer.addView(createSolidThemeButton("Choose Folders") { showFolderExplorer() })
    }

    private fun showShapePickScreen() {
        wizardLayout.visibility = View.VISIBLE
        slideshowLayout.visibility = View.GONE
        wizardTitle.text = "Choose Frame Shape"
        wizardSubtitle.text = "Manually resize your system window to match your choice."
        wizardActionContainer.removeAllViews()

        val lastUsedShape = getSharedPreferences("FlatGalleryPrefs", Context.MODE_PRIVATE)
            .getString("last_used_shape", "NONE") ?: "NONE"

        fun stylePickerButton(btn: Button, shapeName: String) {
            if (shapeName == lastUsedShape) {
                (btn.background as? GradientDrawable)?.setStroke(4, Color.parseColor("#34C759"))
            }
        }

        shapeBtnPortrait = createSolidThemeButton("Portrait (${GlobalCache.portraitUris.size})") {
            onShapeSelectedFromPicker("PORTRAIT")
        }.also {
            stylePickerButton(it, "PORTRAIT")
            wizardActionContainer.addView(it)
        }

        shapeBtnSquare = createSolidThemeButton("Square (${GlobalCache.squareUris.size})") {
            onShapeSelectedFromPicker("SQUARE")
        }.also {
            stylePickerButton(it, "SQUARE")
            wizardActionContainer.addView(it)
        }

        shapeBtnLandscape = createSolidThemeButton("Landscape (${GlobalCache.landscapeUris.size})") {
            onShapeSelectedFromPicker("LANDSCAPE")
        }.also {
            stylePickerButton(it, "LANDSCAPE")
            wizardActionContainer.addView(it)
        }

        Log.i(
            "FlatGalleryPerf",
            "ShapePicker shown (+${SystemClock.elapsedRealtime() - activityStartElapsedMs}ms from onCreate) " +
                "counts P=${GlobalCache.portraitUris.size} S=${GlobalCache.squareUris.size} L=${GlobalCache.landscapeUris.size}"
        )
    }

    private fun onShapeSelectedFromPicker(shape: String) {
        shapePickElapsedMs = SystemClock.elapsedRealtime()
        getSharedPreferences("FlatGalleryPrefs", Context.MODE_PRIVATE).edit()
            .putString("last_used_shape", shape).apply()
        Log.i("FlatGalleryPerf", "Shape picked: $shape (+${shapePickElapsedMs - activityStartElapsedMs}ms from onCreate)")
        startSlideshowMode(shape)
    }

    private fun updateShapePickerButtonCounts() {
        shapeBtnPortrait?.text = "Portrait (${GlobalCache.portraitUris.size})"
        shapeBtnSquare?.text = "Square (${GlobalCache.squareUris.size})"
        shapeBtnLandscape?.text = "Landscape (${GlobalCache.landscapeUris.size})"
    }

    private fun getSourceUrisForShape(shape: String): List<Uri> {
        return when (shape) {
            "PORTRAIT" -> GlobalCache.portraitUris
            "LANDSCAPE" -> GlobalCache.landscapeUris
            "SQUARE" -> GlobalCache.squareUris
            else -> emptyList()
        }
    }

    private fun startSlideshowMode(shape: String) {
        chosenShape = shape
        hasPendingBoundaryRebuild = false
        pendingCompletedUris = null
        activeUris.clear()
        activeUriSet.clear()
        
        val sourceUris = getSourceUrisForShape(shape)

        if (isFavoritesMode) {
            for (u in sourceUris) {
                val p = u.path ?: continue
                if (GlobalCache.favoritePaths.contains(p) && activeUriSet.add(p)) {
                    activeUris.add(u)
                }
            }
        } else {
            for (u in sourceUris) {
                val p = u.path ?: continue
                if (activeUriSet.add(p)) {
                    activeUris.add(u)
                }
            }
        }

        Log.i(
            "FlatGalleryPerf",
            "Window ${windowId.take(8)} startSlideshowMode($shape) midScan=${GlobalCache.isScanning} " +
                "initialSnapshot=${activeUris.size} activeListeners=${GlobalCache.scanListeners.size}"
        )

        saveWindowState()
        syncShapeSettingSelection()
        wizardLayout.visibility = View.GONE
        slideshowLayout.visibility = View.VISIBLE
        shapeBtnPortrait = null
        shapeBtnSquare = null
        shapeBtnLandscape = null
        resetUiFadeTimer()
        updateResponsiveUi()

        if (activeUris.isEmpty()) {
            playbackOrder.clear()
            slideHandler.removeCallbacks(slideRunnable)
            imageView.setImageDrawable(null)
            overlayInfoText.text = ""
            starButton.text = "☆"
            starButton.setTextColor(Color.WHITE)
            
            if (GlobalCache.isScanning || !GlobalCache.isCacheLoadedFromDisk) {
                // Stream or disk cache load is in progress; keep slideshow screen clean until first matching image arrives
                infoTextView.text = ""
            } else {
                val modeStr = if (isFavoritesMode) "Favorites" else "Selected Folders"
                infoTextView.text = "No images found in $modeStr for $shape shape.\n\nOpen Settings to change your Frame Shape or Source."
            }
        } else {
            infoTextView.text = ""
            initPlaybackQueue()
        }
    }

    // --- Dynamic Settings Rows Builder ---
    private fun buildSettingRow(
        parent: LinearLayout, 
        label: String, 
        options: List<String>, 
        selectedIndex: Int, 
        onSelect: (Int) -> Unit
    ): Pair<LinearLayout, Spinner> {
        
        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#2C2C2E"))
            }
            background = shape
            setPadding(48, 32, 48, 48)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { 
                bottomMargin = 32 
                leftMargin = 32
                rightMargin = 32
            }
        }

        val rowTitle = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#AEAEB2"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = true
            setPadding(0, 0, 0, 24)
        }
        cardContainer.addView(rowTitle)

        // Wide Layout (Horizontal Segmented Buttons)
        val wideLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        
        val buttons = mutableListOf<Button>()
        for ((index, option) in options.withIndex()) {
            val btn = Button(this).apply {
                text = option
                isAllCaps = false
                textSize = 16f
                setPadding(48, 24, 48, 24)
                
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { 
                    if (index < options.size - 1) marginEnd = 16
                }
                
                val btnShape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f
                    setColor(if (index == selectedIndex) Color.parseColor("#34C759") else Color.parseColor("#3A3A3C"))
                }
                background = btnShape
                
                setTextColor(if (index == selectedIndex) Color.BLACK else Color.WHITE)
                
                addHoverEffect()
                setOnClickListener {
                    onSelect(index)
                    buttons.forEachIndexed { i, b -> 
                        (b.background as GradientDrawable).setColor(if (i == index) Color.parseColor("#34C759") else Color.parseColor("#3A3A3C"))
                        b.setTextColor(if (i == index) Color.BLACK else Color.WHITE)
                    }
                }
            }
            buttons.add(btn)
            wideLayout.addView(btn)
        }

        // Narrow Layout (Spinner Dropdown)
        val customAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.textSize = 16f
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.setBackgroundColor(Color.parseColor("#3A3A3C"))
                view.setPadding(32, 32, 32, 32)
                return view
            }
        }
        
        val narrowSpinner = Spinner(this).apply {
            adapter = customAdapter
            setSelection(selectedIndex)
            
            val spinnerShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(Color.parseColor("#3A3A3C"))
            }
            background = spinnerShape
            setPadding(24, 16, 24, 16)
            
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            
            var isUserInit = false
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, id: Long) {
                    if (isUserInit) {
                        onSelect(pos)
                        buttons.forEachIndexed { i, b -> 
                            (b.background as GradientDrawable).setColor(if (i == pos) Color.parseColor("#34C759") else Color.parseColor("#3A3A3C"))
                            b.setTextColor(if (i == pos) Color.BLACK else Color.WHITE)
                        }
                    }
                    isUserInit = true
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }

        cardContainer.addView(wideLayout)
        cardContainer.addView(narrowSpinner)

        parent.addView(cardContainer)

        return Pair(wideLayout, narrowSpinner)
    }

    // --- Settings Overlay ---
    private fun buildSettingsOverlay(root: FrameLayout) {
        settingsOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E61C1C1E")) 
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
        }

        val scrollContainer = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        
        val settingsStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(32, 64, 32, 64)
        }

        val title = TextView(this).apply {
            text = "Frame Settings"
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        settingsStack.addView(title)

        val shapes = listOf("PORTRAIT", "SQUARE", "LANDSCAPE")
        val shapeIdx = shapes.indexOf(chosenShape).takeIf { it >= 0 } ?: 0
        val (shapeW, shapeN) = buildSettingRow(settingsStack, "Frame Shape", shapes, shapeIdx) { idx ->
            chosenShape = shapes[idx]
            getSharedPreferences("FlatGalleryPrefs", Context.MODE_PRIVATE).edit()
                .putString("last_used_shape", chosenShape).apply()
            saveWindowState()
            if (slideshowLayout.visibility == View.VISIBLE) startSlideshowMode(chosenShape)
        }
        settingsWideViews.add(shapeW)
        settingsNarrowViews.add(shapeN)

        val sources = listOf("All Folders", "Favorites Only")
        val sourceIdx = if (isFavoritesMode) 1 else 0
        val (sourceW, sourceN) = buildSettingRow(settingsStack, "Playback Source", sources, sourceIdx) { idx ->
            isFavoritesMode = (idx == 1)
            saveWindowState()
            if (chosenShape != "NONE") startSlideshowMode(chosenShape)
        }
        settingsWideViews.add(sourceW)
        settingsNarrowViews.add(sourceN)

        val transitions = listOf("Instant", "Smooth Fade")
        val (transW, transN) = buildSettingRow(settingsStack, "Global Transition", transitions, GlobalCache.transitionIndex) { idx ->
            GlobalCache.transitionIndex = idx
            getSharedPreferences("FlatGalleryPrefs", Context.MODE_PRIVATE).edit()
                .putInt("global_transition", GlobalCache.transitionIndex).apply()
        }
        settingsWideViews.add(transW)
        settingsNarrowViews.add(transN)

        val kenBurnsLabels = listOf("Off", "On")
        val kbIdx = if (isKenBurns) 1 else 0
        val (kbW, kbN) = buildSettingRow(settingsStack, "Ken Burns Effect (This Window)", kenBurnsLabels, kbIdx) { idx ->
            isKenBurns = (idx == 1)
            saveWindowState()
            displayCurrentImage() 
        }
        settingsWideViews.add(kbW)
        settingsNarrowViews.add(kbN)

        settingsStack.addView(createSolidThemeButton("Adjust Source Folders") {
            hideSettingsMenu()
            showFolderExplorer()
        }.apply { layoutParams = LinearLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 32; bottomMargin = 32 } })

        scrollContainer.addView(settingsStack)
        settingsOverlay.addView(scrollContainer)

        val closeBtn = Button(this).apply {
            text = "Close Settings"
            isAllCaps = false
            setTextColor(Color.BLACK)
            textSize = 18f
            
            val btnShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#34C759"))
            }
            background = btnShape
            
            setPadding(64, 32, 64, 32)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 48) }
            addHoverEffect()
            setOnClickListener {
                hideSettingsMenu()
                resetUiFadeTimer()
                if (isPlaying && isWindowVisible && activeUris.isNotEmpty()) {
                    slideHandler.postDelayed(slideRunnable, timerOptions[currentTimerIndex])
                }
            }
        }
        settingsOverlay.addView(closeBtn)

        root.addView(settingsOverlay)
    }

    private fun syncShapeSettingSelection() {
        val shapes = listOf("PORTRAIT", "SQUARE", "LANDSCAPE")
        val shapeIdx = shapes.indexOf(chosenShape).takeIf { it >= 0 } ?: return
        val shapeWide = settingsWideViews.getOrNull(0) as? LinearLayout
        if (shapeWide != null) {
            for (i in 0 until shapeWide.childCount) {
                val btn = shapeWide.getChildAt(i) as? Button ?: continue
                (btn.background as? GradientDrawable)?.setColor(
                    if (i == shapeIdx) Color.parseColor("#34C759") else Color.parseColor("#3A3A3C")
                )
                btn.setTextColor(if (i == shapeIdx) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun showSettingsMenu() {
        syncShapeSettingSelection()
        slideHandler.removeCallbacks(slideRunnable) 
        uiFadeHandler.removeCallbacks(uiFadeRunnable)
        settingsOverlay.visibility = View.VISIBLE
        settingsOverlay.animate().alpha(1f).setDuration(250).start()
    }

    private fun hideSettingsMenu() {
        settingsOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            settingsOverlay.visibility = View.GONE
        }.start()
    }

    private fun buildFavoritePromptOverlay(root: FrameLayout) {
        favoritePromptOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E61C1C1E"))
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
            setPadding(64, 64, 64, 64)
        }

        favoritePromptTitle = TextView(this).apply {
            text = "Add to Favorites"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        favoritePromptSubtitle = TextView(this).apply {
            text = "Do you want to favorite this image only, or the entire containing folder?"
            setTextColor(Color.parseColor("#AEAEB2"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        
        val actionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        
        val btnImageOnly = createSolidThemeButton("This Image Only") {
            performFavoriteAction(false)
        }.apply { layoutParams = LinearLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 24 } }
        
        val btnEntireFolder = createSolidThemeButton("Entire Folder") {
            performFavoriteAction(true)
        }.apply { layoutParams = LinearLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 48 } }
        
        val btnCancel = Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(32, 16, 32, 16)
            addHoverEffect()
            setOnClickListener {
                hideFavoritePrompt()
            }
        }
        
        actionContainer.addView(btnImageOnly)
        actionContainer.addView(btnEntireFolder)
        actionContainer.addView(btnCancel)

        favoritePromptOverlay.addView(favoritePromptTitle)
        favoritePromptOverlay.addView(favoritePromptSubtitle)
        favoritePromptOverlay.addView(actionContainer)

        root.addView(favoritePromptOverlay)
    }

    // --- Persistence ---
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("WINDOW_ID", windowId)
    }

    private fun saveWindowState() {
        val prefs = getSharedPreferences("FlatGalleryPrefs", Context.MODE_PRIVATE).edit()
        prefs.putStringSet("global_folders", selectedFolders.map { it.absolutePath }.toSet())
        prefs.putStringSet("global_excluded", excludedFolders.map { it.absolutePath }.toSet())
        prefs.putStringSet("global_favorites", GlobalCache.favoritePaths.toSet())
        prefs.putInt("global_night_mode", GlobalCache.nightModeLevel)
        
        prefs.putBoolean("shuffle_$windowId", isShuffle)
        prefs.putInt("timer_$windowId", currentTimerIndex)
        prefs.putString("shape_$windowId", chosenShape)
        prefs.putBoolean("favmode_$windowId", isFavoritesMode)
        prefs.putBoolean("kenburns_$windowId", isKenBurns)
        prefs.apply()
    }

    private fun restoreWindowState() {
        val prefs = getSharedPreferences("FlatGalleryPrefs", Context.MODE_PRIVATE)
        isShuffle = prefs.getBoolean("shuffle_$windowId", true)
        currentTimerIndex = prefs.getInt("timer_$windowId", 3) 
        chosenShape = prefs.getString("shape_$windowId", "NONE") ?: "NONE"
        isFavoritesMode = prefs.getBoolean("favmode_$windowId", false)
        isKenBurns = prefs.getBoolean("kenburns_$windowId", false)
        
        GlobalCache.transitionIndex = prefs.getInt("global_transition", 0)
        GlobalCache.favoritePaths = prefs.getStringSet("global_favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        GlobalCache.nightModeLevel = prefs.getInt("global_night_mode", 0)

        if (!hasStoragePermission()) {
            Log.i("FlatGalleryPerf", "Storage permission not granted on launch; requesting ${getRequiredStoragePermission()}")
            showPermissionRequestPendingScreen()
            requestPermissions(arrayOf(getRequiredStoragePermission()), PERMISSION_REQ_CODE)
            return
        }
        isShowingPermissionDeniedScreen = false
        
        val savedPaths = prefs.getStringSet("global_folders", null)
        val savedExclusions = prefs.getStringSet("global_excluded", null)

        if (!savedPaths.isNullOrEmpty()) {
            selectedFolders.clear()
            savedPaths.forEach { path ->
                val f = File(path)
                if (f.exists() && f.isDirectory) selectedFolders.add(f)
            }
            excludedFolders.clear()
            savedExclusions?.forEach { path ->
                val f = File(path)
                if (f.exists() && f.isDirectory) excludedFolders.add(f)
            }
            
            if (selectedFolders.isNotEmpty()) {
                val selectedSet = selectedFolders.map { it.absolutePath }.toSet()
                val excludedSet = excludedFolders.map { it.absolutePath }.toSet()

                if (!GlobalCache.isCacheLoadedFromDisk) {
                    // Show Shape Picker or Slideshow immediately on UI thread without waiting for disk cache read
                    if (chosenShape != "NONE") {
                        startSlideshowMode(chosenShape)
                    } else {
                        showShapePickScreen()
                    }
                    val appCtx = applicationContext
                    GlobalCache.decodeExecutor.execute {
                        GlobalCache.loadDiskCacheIfNeeded(appCtx)
                        runOnUiThread {
                            if (!GlobalCache.isScanning) {
                                GlobalCache.rebuildBucketsFromCachedEntries(selectedSet, excludedSet)
                            }
                            updateShapePickerButtonCounts()
                            Log.i(
                                "FlatGalleryPerf",
                                "ShapePicker counts updated from disk cache (+${SystemClock.elapsedRealtime() - activityStartElapsedMs}ms from onCreate) " +
                                    "counts P=${GlobalCache.portraitUris.size} S=${GlobalCache.squareUris.size} L=${GlobalCache.landscapeUris.size} complete=${GlobalCache.isCacheComplete}"
                            )
                            if (chosenShape != "NONE" && activeUris.isEmpty()) {
                                val src = getSourceUrisForShape(chosenShape)
                                if (src.isNotEmpty()) {
                                    startSlideshowMode(chosenShape)
                                }
                            }
                            if (GlobalCache.entriesByPath.isEmpty() || !GlobalCache.isCacheComplete) {
                                triggerUnifiedScan()
                            } else {
                                slideHandler.postDelayed({ triggerUnifiedScan() }, 1500L)
                            }
                        }
                    }
                } else {
                    if (!GlobalCache.isScanning && !GlobalCache.isScanned && GlobalCache.entriesByPath.isNotEmpty()) {
                        GlobalCache.rebuildBucketsFromCachedEntries(selectedSet, excludedSet)
                    }
                    if (GlobalCache.entriesByPath.isEmpty() || !GlobalCache.isCacheComplete) {
                        triggerUnifiedScan()
                    } else if (!GlobalCache.isScanning) {
                        slideHandler.postDelayed({ triggerUnifiedScan() }, 1500L)
                    }
                    if (chosenShape != "NONE") {
                        startSlideshowMode(chosenShape)
                    } else {
                        showShapePickScreen()
                    }
                }
            } else {
                showWelcomeScreen()
            }
        } else {
            showWelcomeScreen()
        }
    }

    private fun spawnNewWindow() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(intent)
    }

    // --- Responsiveness ---
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResponsiveUi()
    }

    private fun updateResponsiveUi() {
        val config = resources.configuration
        val isCompact = config.screenWidthDp < 600 || config.screenHeightDp < 500

        leftScroll.visibility = if (isCompact) View.GONE else View.VISIBLE

        val isSettingsCompact = config.screenWidthDp < 600
        settingsWideViews.forEach { it.visibility = if (isSettingsCompact) View.GONE else View.VISIBLE }
        settingsNarrowViews.forEach { it.visibility = if (isSettingsCompact) View.VISIBLE else View.GONE }

        // Icon-only buttons for perfect symmetry and uniformity across compact/wide states.
        timerButton.text = "$ICON_TIMER  ${timerLabels[currentTimerIndex]}"
        prevBtn.text = if (isCompact) ICON_PREV else "$ICON_PREV  Prev"
        playPauseButton.text = if (isPlaying) (if (isCompact) ICON_PAUSE else "$ICON_PAUSE  Pause") else (if (isCompact) ICON_PLAY else "$ICON_PLAY  Play")
        nextBtn.text = if (isCompact) ICON_NEXT else "Next  $ICON_NEXT"
        shuffleButton.text = if (isShuffle) (if (isCompact) ICON_SHUFFLE else "$ICON_SHUFFLE  Shuf") else (if (isCompact) ICON_LOOP else "$ICON_LOOP  Foldr")

        explorerTitle.textSize = if (isCompact) 16f else 20f
        closeExplorerBtn.text = if (isCompact) "✕" else "Cancel"
        clearFoldersBtn.text = if (isCompact) "🗑" else "Clear"
        startBtn.text = if (isCompact) "▶" else "Done"

        val headerPad = if (isCompact) 16 else 32
        explorerHeader.setPadding(headerPad, headerPad / 2, headerPad, headerPad / 2)
        selectBar.setPadding(headerPad, headerPad / 2, headerPad, headerPad / 2)
        controlsLayout.setPadding(headerPad, headerPad / 2, headerPad, headerPad / 2)
        
        val btnPad = if (isCompact) 16 else 32
        clearFoldersBtn.setPadding(btnPad, btnPad/2, btnPad, btnPad/2)
        startBtn.setPadding(btnPad, btnPad/2, btnPad, btnPad/2)
    }

    private fun resetUiFadeTimer() {
        if (slideshowLayout.visibility != View.VISIBLE || settingsOverlay.visibility == View.VISIBLE || favoritePromptOverlay.visibility == View.VISIBLE) return
        fadeableViews.forEach { 
            it.animate().cancel()
            it.alpha = 1f
            it.visibility = View.VISIBLE
        }
        uiFadeHandler.removeCallbacks(uiFadeRunnable)
        uiFadeHandler.postDelayed(uiFadeRunnable, 3000)
    }

    private fun fadeOutUi() {
        fadeableViews.forEach { view ->
            view.animate().alpha(0f).setDuration(400)
                .withEndAction { view.visibility = View.GONE }.start()
        }
    }

    // --- Playback Logic ---
    private fun toggleNightMode() {
        GlobalCache.nightModeLevel = (GlobalCache.nightModeLevel + 1) % 4
        saveWindowState()
        GlobalCache.nightModeListeners.forEach { it.invoke() }
    }

    private fun updateNightModeUi() {
        val alphaStr = when (GlobalCache.nightModeLevel) {
            1 -> "#4D000000" // Light
            2 -> "#99000000" // Medium
            3 -> "#D9000000" // Dark
            else -> "#00000000" // Off
        }
        nightModeOverlay.setBackgroundColor(Color.parseColor(alphaStr))
        
        if (GlobalCache.nightModeLevel > 0) {
            nightModeButton.setTextColor(Color.parseColor("#80D8FF")) // Light blue to indicate active
        } else {
            nightModeButton.setTextColor(Color.WHITE)
        }
    }

    private fun togglePlayPause() {
        isPlaying = !isPlaying
        updateResponsiveUi()
        slideHandler.removeCallbacks(slideRunnable)
        if (isPlaying && isWindowVisible && activeUris.isNotEmpty()) {
            val remainingDelay = timerOptions[currentTimerIndex]
            slideHandler.postDelayed(slideRunnable, remainingDelay)
            if (isKenBurns) applyKenBurns(remainingDelay)
        } else {
            imageView.animate().cancel()
        }
    }

    private fun toggleShuffle() {
        if (playbackOrder.isEmpty() || activeUris.isEmpty() || playheadIndex >= playbackOrder.size) {
            isShuffle = !isShuffle
            saveWindowState()
            updateResponsiveUi()
            return
        }
        
        isShuffle = !isShuffle
        saveWindowState()
        updateResponsiveUi()
        
        val uri = activeUris.getOrNull(playbackOrder.getOrNull(playheadIndex) ?: 0)
        val parentPath = uri?.path?.let { resolveParentPath(it) }
        val folderName = parentPath?.substringAfterLast(File.separatorChar) ?: "Unknown"
        
        if (!isShuffle) {
            Toast.makeText(this, "Looping Folder: $folderName", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Shuffling All Images", Toast.LENGTH_SHORT).show()
        }
        
        initPlaybackQueue(keepCurrentUri = uri)
    }

    private fun toggleTimer() {
        currentTimerIndex = (currentTimerIndex + 1) % timerOptions.size
        saveWindowState()
        updateResponsiveUi()
        resetTimerIfPlaying()
    }

    private fun toggleFavoriteCurrent() {
        if (playbackOrder.isEmpty() || activeUris.isEmpty() || playheadIndex >= playbackOrder.size) return
        val actualImageIndex = playbackOrder[playheadIndex]
        if (actualImageIndex >= activeUris.size) return 

        val currentUri = activeUris[actualImageIndex]
        val path = currentUri.path ?: return

        pendingFavoritePath = path
        isPendingUnfavorite = GlobalCache.favoritePaths.contains(path)
        showFavoritePrompt()
    }
    
    private fun showFavoritePrompt() {
        if (isPlaying) togglePlayPause()
        slideHandler.removeCallbacks(slideRunnable)
        uiFadeHandler.removeCallbacks(uiFadeRunnable)
        
        if (isPendingUnfavorite) {
            favoritePromptTitle.text = "Remove from Favorites"
            favoritePromptSubtitle.text = "Do you want to remove this image only, or the entire containing folder?"
        } else {
            favoritePromptTitle.text = "Add to Favorites"
            favoritePromptSubtitle.text = "Do you want to favorite this image only, or the entire containing folder?"
        }
        
        favoritePromptOverlay.visibility = View.VISIBLE
        favoritePromptOverlay.animate().alpha(1f).setDuration(250).start()
    }

    private fun hideFavoritePrompt() {
        favoritePromptOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            favoritePromptOverlay.visibility = View.GONE
            pendingFavoritePath = null
            resetUiFadeTimer()
        }.start()
    }

    private fun performFavoriteAction(entireFolder: Boolean) {
        val path = pendingFavoritePath ?: return
        
        if (isPendingUnfavorite) {
            if (entireFolder) {
                val parentFile = File(path).parentFile
                if (parentFile != null && parentFile.exists()) {
                    val imagesInFolder = parentFile.listFiles()?.filter { it.isFile && isImageFile(it) } ?: emptyList()
                    for (img in imagesInFolder) {
                        GlobalCache.favoritePaths.remove(img.absolutePath)
                    }
                    Toast.makeText(this, "Removed ${imagesInFolder.size} images", Toast.LENGTH_SHORT).show()
                } else {
                    GlobalCache.favoritePaths.remove(path)
                }
            } else {
                GlobalCache.favoritePaths.remove(path)
                Toast.makeText(this, "Removed image", Toast.LENGTH_SHORT).show()
            }
            
            starButton.text = "☆"
            starButton.setTextColor(Color.WHITE)
            
            if (isFavoritesMode) {
                val currentUri = activeUris.find { it.path == path }
                if (currentUri != null) {
                    activeUris.remove(currentUri)
                    activeUriSet.remove(path)
                }
                
                if (entireFolder) {
                    val parentPath = resolveParentPath(path)
                    val toRemove = activeUris.filter { resolveParentPath(it.path ?: "") == parentPath }
                    activeUris.removeAll(toRemove)
                    for (u in toRemove) u.path?.let { activeUriSet.remove(it) }
                }
                
                if (activeUris.isEmpty()) {
                    startSlideshowMode(chosenShape) 
                } else {
                    initPlaybackQueue()
                }
            }
        } else {
            if (entireFolder) {
                val parentFile = File(path).parentFile
                if (parentFile != null && parentFile.exists()) {
                    val imagesInFolder = parentFile.listFiles()?.filter { it.isFile && isImageFile(it) } ?: emptyList()
                    for (img in imagesInFolder) {
                        GlobalCache.favoritePaths.add(img.absolutePath)
                    }
                    Toast.makeText(this, "Favorited ${imagesInFolder.size} images", Toast.LENGTH_SHORT).show()
                } else {
                    GlobalCache.favoritePaths.add(path)
                }
            } else {
                GlobalCache.favoritePaths.add(path)
                Toast.makeText(this, "Favorited image", Toast.LENGTH_SHORT).show()
            }
            
            starButton.text = "★"
            starButton.setTextColor(Color.parseColor("#FFD700"))
        }
        
        saveWindowState()
        hideFavoritePrompt()
    }

    private fun resolveParentPath(path: String): String {
        if (path.isEmpty()) return "unknown"
        GlobalCache.parentPathByPath[path]?.let { return it }
        val sepIdx = path.lastIndexOf(File.separatorChar)
        val parent = if (sepIdx > 0) path.substring(0, sepIdx) else "unknown"
        GlobalCache.parentPathByPath[path] = parent
        return parent
    }

    private fun getCurrentDisplayedUri(): Uri? {
        if (playbackOrder.isEmpty() || activeUris.isEmpty() || playheadIndex !in playbackOrder.indices) return null
        val idx = playbackOrder[playheadIndex]
        return activeUris.getOrNull(idx)
    }

    private fun rebuildQueueListsOnly(keepCurrentUri: Uri? = null): Boolean {
        val currentlyDisplayedUri = getCurrentDisplayedUri()
        val targetUri = keepCurrentUri ?: currentlyDisplayedUri

        playbackOrder.clear()

        if (isShuffle) {
            val folderGroups = activeUris.indices.groupBy {
                resolveParentPath(activeUris[it].path ?: "")
            }
            
            val scoredIndices = mutableListOf<Pair<Int, Double>>()
            val random = java.util.Random()
            
            for ((_, indices) in folderGroups) {
                val shuffledIndices = indices.shuffled(random)
                val folderOffset = random.nextDouble()
                val n = shuffledIndices.size.toDouble()
                
                for ((i, index) in shuffledIndices.withIndex()) {
                    val score = (i + folderOffset) / n
                    scoredIndices.add(Pair(index, score))
                }
            }
            
            scoredIndices.sortBy { it.second }
            playbackOrder.addAll(scoredIndices.map { it.first })
        } else {
            if (targetUri != null) {
                val targetParent = resolveParentPath(targetUri.path ?: "")
                val folderIndices = activeUris.indices.filter {
                    resolveParentPath(activeUris[it].path ?: "") == targetParent
                }
                if (folderIndices.isNotEmpty()) {
                    playbackOrder.addAll(folderIndices)
                } else {
                    playbackOrder.addAll(activeUris.indices)
                }
            } else {
                playbackOrder.addAll(activeUris.indices)
            }
        }

        var foundTarget = false
        if (targetUri != null) {
            val targetPath = targetUri.path
            val newActualIndex = activeUris.indexOfFirst { it.path == targetPath }
            if (newActualIndex != -1) {
                playheadIndex = playbackOrder.indexOf(newActualIndex).takeIf { it >= 0 } ?: 0
                foundTarget = true
            }
        }
        
        if (!foundTarget) {
            playheadIndex = 0
        }
        return foundTarget && targetUri?.path == currentlyDisplayedUri?.path
    }

    private fun initPlaybackQueue(keepCurrentUri: Uri? = null, skipDisplay: Boolean = false) {
        val preservedCurrentOnScreen = rebuildQueueListsOnly(keepCurrentUri)
        val shouldSkipDisplay = skipDisplay || preservedCurrentOnScreen
        
        if (!shouldSkipDisplay) {
            displayCurrentImage()
            resetTimerIfPlaying()
        } else if (isWindowVisible && activeUris.size > 1) {
            // Anchor stayed on screen; do not reset the slide timer mid-slide, only ensure next image is prefetched
            prefetchNextImage()
        }
    }

    private fun handleMissingFile(missingUri: Uri) {
        val missingPath = missingUri.path ?: ""
        if (missingPath.isNotEmpty()) {
            GlobalCache.entriesByPath.remove(missingPath)
        }
        GlobalCache.portraitUris.remove(missingUri)
        GlobalCache.landscapeUris.remove(missingUri)
        GlobalCache.squareUris.remove(missingUri)

        triggerUnifiedScan()
        
        val missingParent = File(missingPath).parentFile
        val actualIdx = activeUris.indexOfFirst { it.path == missingPath }
        
        if (actualIdx != -1) {
            val currentlyDisplayedUri = getCurrentDisplayedUri()

            activeUris.removeAt(actualIdx)
            activeUriSet.remove(missingPath)
            
            if (activeUris.isEmpty()) {
                startSlideshowMode(chosenShape)
                return
            }
            
            if (!isShuffle && missingParent != null && !missingParent.exists()) {
                isShuffle = true
                saveWindowState()
                runOnUiThread { updateResponsiveUi() }
                runOnUiThread { Toast.makeText(this@MainActivity, "Folder moved. Shuffling all.", Toast.LENGTH_SHORT).show() }
                initPlaybackQueue(keepCurrentUri = null)
                return
            }

            initPlaybackQueue(keepCurrentUri = currentlyDisplayedUri)
        }
    }

    private fun handleStreamedScanBatch(newEntries: List<ScanEntry>) {
        if (wizardLayout.visibility == View.VISIBLE) {
            updateShapePickerButtonCounts()
        }
        if (chosenShape == "NONE") return

        val wasEmpty = activeUris.isEmpty()
        val prevSize = activeUris.size
        var appendedCount = 0

        for (entry in newEntries) {
            if (entry.shapeBucket != chosenShape) continue
            if (isFavoritesMode && !GlobalCache.favoritePaths.contains(entry.path)) continue
            if (activeUriSet.add(entry.path)) {
                val newActualIdx = activeUris.size
                activeUris.add(entry.uri)
                playbackOrder.add(newActualIdx)
                appendedCount++
            }
        }

        if (appendedCount > 0) {
            if (wasEmpty) {
                infoTextView.text = ""
                playheadIndex = 0
                displayCurrentImage()
                resetTimerIfPlaying()
            } else if (prevSize == 1 && activeUris.size > 1 && isWindowVisible) {
                prefetchNextImage()
            }
        }
    }

    private fun handleScanCompletedSnapshot(portrait: List<Uri>, landscape: List<Uri>, square: List<Uri>) {
        if (wizardLayout.visibility == View.VISIBLE) {
            updateShapePickerButtonCounts()
        }
        if (chosenShape == "NONE") return

        val sourceUris = when (chosenShape) {
            "PORTRAIT" -> portrait
            "LANDSCAPE" -> landscape
            "SQUARE" -> square
            else -> emptyList()
        }

        val filteredUris = if (isFavoritesMode) {
            sourceUris.filter { GlobalCache.favoritePaths.contains(it.path) }
        } else {
            sourceUris
        }

        if (activeUris.isEmpty()) {
            if (filteredUris.isEmpty()) {
                playbackOrder.clear()
                slideHandler.removeCallbacks(slideRunnable)
                imageView.setImageDrawable(null)
                overlayInfoText.text = ""
                starButton.text = "☆"
                starButton.setTextColor(Color.WHITE)
                val modeStr = if (isFavoritesMode) "Favorites" else "Selected Folders"
                infoTextView.text = "No images found in $modeStr for $chosenShape shape.\n\nOpen Settings to change your Frame Shape or Source."
            } else {
                activeUris.clear()
                activeUriSet.clear()
                for (u in filteredUris) {
                    val p = u.path ?: continue
                    if (activeUriSet.add(p)) activeUris.add(u)
                }
                infoTextView.text = ""
                initPlaybackQueue()
            }
            return
        }

        if (filteredUris.isEmpty()) {
            // All matching images were removed or excluded
            startSlideshowMode(chosenShape)
            return
        }

        // Queue is already active and showing an image: schedule full stratified shuffle rebuild strictly at the next slide boundary
        pendingCompletedUris = filteredUris
        hasPendingBoundaryRebuild = true
    }

    private fun applyPendingBoundaryRebuildIfNeeded() {
        if (!hasPendingBoundaryRebuild) return
        hasPendingBoundaryRebuild = false
        val updatedUris = pendingCompletedUris ?: return
        pendingCompletedUris = null

        val anchorUri = getCurrentDisplayedUri()
        activeUris.clear()
        activeUriSet.clear()
        for (u in updatedUris) {
            val p = u.path ?: continue
            if (activeUriSet.add(p)) {
                activeUris.add(u)
            }
        }
        if (activeUris.isNotEmpty()) {
            rebuildQueueListsOnly(keepCurrentUri = anchorUri)
        }
    }

    private fun readExifOrientation(path: String): Int {
        if (path.endsWith(".png", ignoreCase = true)) {
            return ExifInterface.ORIENTATION_NORMAL
        }
        return try {
            val exif = ExifInterface(path)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun isSwappedExifOrientation(orientation: Int): Boolean {
        return orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
    }

    private fun triggerUnifiedScan(
        specificDirsToWalk: List<File>? = null,
        isIncrementalAdd: Boolean = false,
        isFolderChange: Boolean = false
    ) {
        if (!hasStoragePermission()) return
        synchronized(GlobalCache.scanLock) {
            if (GlobalCache.isScanning) {
                if (isFolderChange) {
                    GlobalCache.pendingFolderRescan = true
                }
                return
            }
            GlobalCache.isScanning = true
            GlobalCache.pendingFolderRescan = false
        }

        val appCtx = applicationContext
        val safeSelected = selectedFolders.toList()
        val safeExcluded = excludedFolders.toList()
        val dirsToWalk = specificDirsToWalk?.toList() ?: safeSelected
        val selectedPathSet = HashSet<String>(safeSelected.map { it.absolutePath })
        val excludedPathSet = HashSet<String>(safeExcluded.map { it.absolutePath })
        val favSnapshot = HashSet<String>(GlobalCache.favoritePaths)

        Thread {
            val scanStartMs = SystemClock.elapsedRealtime()
            var headerReadCount = 0
            var cacheHitCount = 0
            try {
                GlobalCache.loadDiskCacheIfNeeded(appCtx)

                val existingEntriesSnapshot = HashMap<String, ScanEntry>(GlobalCache.entriesByPath)
                // Track which paths were already live in GlobalCache buckets before this scan started
                val alreadyLivePaths = HashSet<String>(
                    GlobalCache.portraitUris.size + GlobalCache.landscapeUris.size + GlobalCache.squareUris.size
                )
                for (u in GlobalCache.portraitUris) u.path?.let { alreadyLivePaths.add(it) }
                for (u in GlobalCache.landscapeUris) u.path?.let { alreadyLivePaths.add(it) }
                for (u in GlobalCache.squareUris) u.path?.let { alreadyLivePaths.add(it) }

                val updatedEntries = if (isIncrementalAdd) {
                    HashMap<String, ScanEntry>(existingEntriesSnapshot)
                } else {
                    HashMap<String, ScanEntry>(existingEntriesSnapshot.size.coerceAtLeast(256))
                }

                val seenFirstInCategory = BooleanArray(6) // 0..2: P/L/S all, 3..5: P/L/S fav
                val pendingStreamBatch = ArrayList<ScanEntry>(32)
                var lastBatchFlushMs = SystemClock.elapsedRealtime()
                val mainHandler = Handler(Looper.getMainLooper())
                val uriBuilder = Uri.Builder().scheme("file").authority("")

                fun flushBatchToUi() {
                    if (pendingStreamBatch.isEmpty()) return
                    val batchSnapshot = ArrayList<ScanEntry>(pendingStreamBatch)
                    pendingStreamBatch.clear()
                    lastBatchFlushMs = SystemClock.elapsedRealtime()
                    mainHandler.post {
                        for (e in batchSnapshot) {
                            GlobalCache.entriesByPath[e.path] = e
                            GlobalCache.parentPathByPath[e.path] = e.parentPath
                            when (e.shapeBucket) {
                                "PORTRAIT" -> GlobalCache.portraitUris.add(e.uri)
                                "LANDSCAPE" -> GlobalCache.landscapeUris.add(e.uri)
                                else -> GlobalCache.squareUris.add(e.uri)
                            }
                        }
                        val listeners = GlobalCache.scanListeners.toList()
                        for (listener in listeners) {
                            listener.onScanBatchStreamed(batchSnapshot)
                        }
                    }
                }

                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }

                for (dir in dirsToWalk) {
                    if (!dir.exists()) continue
                    try {
                        dir.walkTopDown()
                            .onEnter { folder -> !excludedPathSet.contains(folder.absolutePath) }
                            .onFail { _, _ -> }
                            .forEach { file ->
                                if (file.isFile && isImageFile(file)) {
                                    val path = file.absolutePath
                                    if (!GlobalCache.isPathIncluded(path, selectedPathSet, excludedPathSet)) {
                                        return@forEach
                                    }
                                    val size = file.length()
                                    val lastMod = file.lastModified()
                                    val cached = existingEntriesSnapshot[path]

                                    val entry: ScanEntry? = if (
                                        cached != null &&
                                        cached.sizeBytes == size &&
                                        cached.lastModified == lastMod &&
                                        cached.width > 0 &&
                                        cached.height > 0
                                    ) {
                                        cacheHitCount++
                                        cached
                                    } else {
                                        try {
                                            boundsOptions.outWidth = 0
                                            boundsOptions.outHeight = 0
                                            BitmapFactory.decodeFile(path, boundsOptions)
                                            var w = boundsOptions.outWidth
                                            var h = boundsOptions.outHeight
                                            if (w > 0 && h > 0) {
                                                headerReadCount++
                                                val exif = readExifOrientation(path)
                                                if (isSwappedExifOrientation(exif)) {
                                                    val tmp = w
                                                    w = h
                                                    h = tmp
                                                }
                                                val sepIdx = path.lastIndexOf(File.separatorChar)
                                                val parent = if (sepIdx > 0) path.substring(0, sepIdx) else "unknown"
                                                val bucket = GlobalCache.computeShapeBucket(w, h)
                                                ScanEntry(
                                                    path = path,
                                                    parentPath = parent,
                                                    uri = uriBuilder.path(path).build(),
                                                    width = w,
                                                    height = h,
                                                    exifOrientation = exif,
                                                    sizeBytes = size,
                                                    lastModified = lastMod,
                                                    shapeBucket = bucket
                                                )
                                            } else null
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }

                                    if (entry != null) {
                                        updatedEntries[path] = entry
                                        if (alreadyLivePaths.add(path)) {
                                            pendingStreamBatch.add(entry)
                                            val catIdx = when (entry.shapeBucket) {
                                                "PORTRAIT" -> 0
                                                "LANDSCAPE" -> 1
                                                else -> 2
                                            }
                                            var isFirstForAny = false
                                            if (!seenFirstInCategory[catIdx]) {
                                                seenFirstInCategory[catIdx] = true
                                                isFirstForAny = true
                                                Log.i(
                                                    "FlatGalleryPerf",
                                                    "First ${entry.shapeBucket} streamed in ${SystemClock.elapsedRealtime() - scanStartMs}ms"
                                                )
                                            }
                                            if (favSnapshot.contains(path) && !seenFirstInCategory[catIdx + 3]) {
                                                seenFirstInCategory[catIdx + 3] = true
                                                isFirstForAny = true
                                            }
                                            val now = SystemClock.elapsedRealtime()
                                            if (isFirstForAny || pendingStreamBatch.size >= 25 || (now - lastBatchFlushMs) >= 150L) {
                                                flushBatchToUi()
                                            }
                                            if (headerReadCount > 0 && headerReadCount % 500 == 0) {
                                                GlobalCache.writeDiskCacheAtomically(
                                                    appCtx,
                                                    ArrayList(updatedEntries.values),
                                                    isComplete = false
                                                )
                                                Log.i(
                                                    "FlatGalleryPerf",
                                                    "Checkpoint saved: headerReads=$headerReadCount cacheHits=$cacheHitCount totalCached=${updatedEntries.size}"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                    } catch (_: Exception) {}
                }

                flushBatchToUi()

                val cacheChanged = headerReadCount > 0 ||
                    updatedEntries.size != existingEntriesSnapshot.size ||
                    !GlobalCache.isCacheComplete
                if (cacheChanged) {
                    GlobalCache.writeDiskCacheAtomically(appCtx, updatedEntries.values, isComplete = true)
                }

                val finalPortrait = ArrayList<Uri>()
                val finalLandscape = ArrayList<Uri>()
                val finalSquare = ArrayList<Uri>()
                val sortedFinal = updatedEntries.values
                    .filter { GlobalCache.isPathIncluded(it.path, selectedPathSet, excludedPathSet) }
                    .sortedBy { it.path }

                for (e in sortedFinal) {
                    GlobalCache.parentPathByPath[e.path] = e.parentPath
                    when (e.shapeBucket) {
                        "PORTRAIT" -> finalPortrait.add(e.uri)
                        "LANDSCAPE" -> finalLandscape.add(e.uri)
                        else -> finalSquare.add(e.uri)
                    }
                }

                GlobalCache.entriesByPath.clear()
                GlobalCache.entriesByPath.putAll(updatedEntries)

                val elapsed = SystemClock.elapsedRealtime() - scanStartMs
                Log.i(
                    "FlatGalleryPerf",
                    "Unified scan complete in ${elapsed}ms: total=${sortedFinal.size} " +
                        "(P=${finalPortrait.size}, L=${finalLandscape.size}, S=${finalSquare.size}) " +
                        "cacheHits=$cacheHitCount headerReads=$headerReadCount activeListeners=${GlobalCache.scanListeners.size}"
                )

                mainHandler.post {
                    GlobalCache.portraitUris.clear()
                    GlobalCache.portraitUris.addAll(finalPortrait)
                    GlobalCache.landscapeUris.clear()
                    GlobalCache.landscapeUris.addAll(finalLandscape)
                    GlobalCache.squareUris.clear()
                    GlobalCache.squareUris.addAll(finalSquare)
                    GlobalCache.isScanned = true
                    val needsFollowUp: Boolean
                    synchronized(GlobalCache.scanLock) {
                        GlobalCache.isScanning = false
                        needsFollowUp = GlobalCache.pendingFolderRescan
                        GlobalCache.pendingFolderRescan = false
                    }
                    val listeners = GlobalCache.scanListeners.toList()
                    for (listener in listeners) {
                        listener.onScanCompleted(finalPortrait, finalLandscape, finalSquare)
                    }
                    if (needsFollowUp) {
                        triggerUnifiedScan()
                    }
                }
            } catch (t: Throwable) {
                Log.e("FlatGalleryPerf", "Scan error: ${t.message}")
                synchronized(GlobalCache.scanLock) {
                    GlobalCache.isScanning = false
                }
            }
        }.start()
    }

    private fun prevImage() {
        applyPendingBoundaryRebuildIfNeeded()
        if (playbackOrder.isEmpty()) return
        playheadIndex = (playheadIndex - 1 + playbackOrder.size) % playbackOrder.size
        displayCurrentImage()
        resetTimerIfPlaying()
    }

    private fun nextImage(auto: Boolean) {
        applyPendingBoundaryRebuildIfNeeded()
        if (playbackOrder.isEmpty()) return
        playheadIndex = (playheadIndex + 1) % playbackOrder.size
        displayCurrentImage()
        if (!auto) resetTimerIfPlaying()
        else if (isPlaying && isWindowVisible) slideHandler.postDelayed(slideRunnable, timerOptions[currentTimerIndex])
    }

    private fun resetTimerIfPlaying() {
        slideHandler.removeCallbacks(slideRunnable)
        if (isPlaying && isWindowVisible && activeUris.isNotEmpty()) {
            slideHandler.postDelayed(slideRunnable, timerOptions[currentTimerIndex])
        }
    }

    private fun getTargetDecodeSize(): Int {
        var w = if (::imageView.isInitialized) imageView.width else 0
        var h = if (::imageView.isInitialized) imageView.height else 0
        if (w <= 0 || h <= 0) {
            w = if (::rootLayout.isInitialized) rootLayout.width else 0
            h = if (::rootLayout.isInitialized) rootLayout.height else 0
        }
        val longestSide = maxOf(w, h)
        if (longestSide <= 0) return 2048
        val rawTarget = (longestSide * 1.5f).toInt()
        val rounded = ((rawTarget + 64) / 128) * 128
        return rounded.coerceIn(256, 2048)
    }

    private fun checkAndRedecodeOnResize() {
        val settledFrames = pendingLayoutChangeFrames
        pendingLayoutChangeFrames = 0
        if (!isWindowVisible || slideshowLayout.visibility != View.VISIBLE || activeUris.isEmpty()) return
        val newTarget = getTargetDecodeSize()
        if (lastDecodedTargetSize > 0) {
            val oldTarget = lastDecodedTargetSize
            val diffRatio = kotlin.math.abs(newTarget - oldTarget).toFloat() / oldTarget.toFloat()
            Log.i(
                "FlatGalleryPerf",
                "Resize settled after $settledFrames layout frames: view=${imageView.width}x${imageView.height} " +
                    "oldTarget=$oldTarget newTarget=$newTarget diffRatio=${String.format(Locale.US, "%.2f", diffRatio)}"
            )
            if (diffRatio > 0.25f) {
                val currentUri = getCurrentDisplayedUri() ?: return
                lastDecodedTargetSize = newTarget
                val cacheKey = "${currentUri}@$newTarget"
                val cached = GlobalCache.memoryCache.get(cacheKey)
                if (cached != null) {
                    imageView.setImageBitmap(cached)
                    Log.i(
                        "FlatGalleryPerf",
                        "Resize re-decoded (memory_cache): view=${imageView.width}x${imageView.height} " +
                            "oldTarget=$oldTarget newTarget=$newTarget cacheKey=$cacheKey " +
                            "decoded=${cached.width}x${cached.height} kenBurns=$isKenBurns scale=${String.format(Locale.US, "%.3f", imageView.scaleX)}"
                    )
                    prefetchNextImage()
                } else {
                    GlobalCache.decodeExecutor.execute {
                        if (getCurrentDisplayedUri() != currentUri) return@execute
                        val bmp = decodeSampledBitmap(currentUri, newTarget, newTarget)
                        if (bmp != null) {
                            GlobalCache.memoryCache.put(cacheKey, bmp)
                            runOnUiThread {
                                if (getCurrentDisplayedUri() == currentUri) {
                                    imageView.setImageBitmap(bmp)
                                    Log.i(
                                        "FlatGalleryPerf",
                                        "Resize re-decoded (disk_decode): view=${imageView.width}x${imageView.height} " +
                                            "oldTarget=$oldTarget newTarget=$newTarget cacheKey=$cacheKey " +
                                            "decoded=${bmp.width}x${bmp.height} kenBurns=$isKenBurns scale=${String.format(Locale.US, "%.3f", imageView.scaleX)}"
                                    )
                                    prefetchNextImage()
                                }
                            }
                        }
                    }
                }
            }
        } else {
            lastDecodedTargetSize = newTarget
        }
    }

    private fun logFirstImageIfNeeded(uri: Uri, source: String) {
        if (hasLoggedFirstImage) return
        hasLoggedFirstImage = true
        val now = SystemClock.elapsedRealtime()
        val fromCreate = now - activityStartElapsedMs
        val fromPick = if (shapePickElapsedMs > 0L) (now - shapePickElapsedMs) else fromCreate
        Log.i(
            "FlatGalleryPerf",
            "First image displayed ($source): fromOnCreate=${fromCreate}ms fromShapePick=${fromPick}ms uri=${uri.lastPathSegment}"
        )
    }

    private fun displayCurrentImage() {
        if (playbackOrder.isEmpty() || activeUris.isEmpty()) {
            imageView.animate().cancel()
            imageView.setImageDrawable(null)
            overlayInfoText.text = ""
            starButton.text = "☆"
            starButton.setTextColor(Color.WHITE)
            return
        }
        
        if (playheadIndex >= playbackOrder.size) return
        val actualImageIndex = playbackOrder[playheadIndex]
        if (actualImageIndex >= activeUris.size) return 
        
        val requestedUri = activeUris[actualImageIndex]
        
        val file = File(requestedUri.path ?: "")
        if (!file.exists()) {
            handleMissingFile(requestedUri)
            return
        }
        
        val timeStr = timeFormat.format(Date())
        val fileName = file.name
        overlayInfoText.text = "$timeStr • $fileName"

        if (GlobalCache.favoritePaths.contains(requestedUri.path)) {
            starButton.text = "★"
            starButton.setTextColor(Color.parseColor("#FFD700"))
        } else {
            starButton.text = "☆"
            starButton.setTextColor(Color.WHITE)
        }

        val targetSize = getTargetDecodeSize()
        if (imageView.width > 0 && imageView.height > 0) {
            lastDecodedTargetSize = targetSize
        }
        val cacheKey = "${requestedUri}@$targetSize"
        
        val cachedBitmap = GlobalCache.memoryCache.get(cacheKey)
        if (cachedBitmap != null) {
            applyTransition(cachedBitmap)
            logFirstImageIfNeeded(requestedUri, "memory_cache")
            if (isWindowVisible) prefetchNextImage()
        } else {
            GlobalCache.decodeExecutor.execute {
                // Skip decode if user already navigated away before worker started
                if (getCurrentDisplayedUri() != requestedUri) return@execute
                val bitmap = decodeSampledBitmap(requestedUri, targetSize, targetSize)
                if (bitmap != null) {
                    GlobalCache.memoryCache.put(cacheKey, bitmap)
                    runOnUiThread {
                        // Out-of-order guard: verify loaded URI still equals current URI before applying transition
                        if (getCurrentDisplayedUri() == requestedUri) {
                            applyTransition(bitmap)
                            logFirstImageIfNeeded(requestedUri, "disk_decode")
                            if (isWindowVisible) prefetchNextImage()
                        }
                    }
                } else {
                    runOnUiThread {
                        if (getCurrentDisplayedUri() == requestedUri) {
                            imageView.setImageDrawable(null)
                        }
                    }
                }
            }
        }
    }

    private fun applyTransition(bitmap: Bitmap) {
        imageView.animate().cancel()
        imageView.scaleX = 1.0f
        imageView.scaleY = 1.0f

        val kenBurnsTrigger = Runnable {
            if (isKenBurns && isPlaying && isWindowVisible) {
                applyKenBurns(timerOptions[currentTimerIndex])
            }
        }

        if (GlobalCache.transitionIndex == 1) { 
            imageView.animate().alpha(0f).setDuration(300).withEndAction {
                imageView.setImageBitmap(bitmap)
                imageView.animate().alpha(1f).setDuration(400).withEndAction(kenBurnsTrigger).start()
            }.start()
        } else { 
            imageView.setImageBitmap(bitmap)
            kenBurnsTrigger.run()
        }
    }

    private fun applyKenBurns(duration: Long) {
        imageView.animate()
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(duration)
            .start()
    }

    private fun prefetchNextImage() {
        if (!isWindowVisible || playbackOrder.isEmpty() || activeUris.isEmpty()) return
        val nextPlayheadIndex = (playheadIndex + 1) % playbackOrder.size
        val nextActualImageIndex = playbackOrder[nextPlayheadIndex]
        if (nextActualImageIndex >= activeUris.size) return 
        
        val nextUri = activeUris[nextActualImageIndex]
        
        val file = File(nextUri.path ?: "")
        if (!file.exists()) {
            handleMissingFile(nextUri)
            return
        }

        val targetSize = getTargetDecodeSize()
        val cacheKey = "${nextUri}@$targetSize"

        if (GlobalCache.memoryCache.get(cacheKey) == null) {
            GlobalCache.decodeExecutor.execute {
                if (!isWindowVisible) return@execute
                val nextBitmap = decodeSampledBitmap(nextUri, targetSize, targetSize)
                if (nextBitmap != null) {
                    GlobalCache.memoryCache.put(cacheKey, nextBitmap)
                }
            }
        }
    }

    private fun applyExifRotationIfNeeded(bitmap: Bitmap, exifOrientation: Int): Bitmap {
        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun decodeSampledBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val path = uri.path ?: return null
            val cachedEntry = GlobalCache.entriesByPath[path]
            val options = BitmapFactory.Options()
            val exifOrientation: Int

            if (cachedEntry != null && cachedEntry.width > 0 && cachedEntry.height > 0) {
                options.outWidth = cachedEntry.width
                options.outHeight = cachedEntry.height
                exifOrientation = cachedEntry.exifOrientation
            } else {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, options)
                exifOrientation = readExifOrientation(path)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            val decoded = BitmapFactory.decodeFile(path, options) ?: return null
            applyExifRotationIfNeeded(decoded, exifOrientation)
        } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        val reqLongest = maxOf(reqWidth, reqHeight)
        val imgLongest = maxOf(height, width)
        var inSampleSize = 1
        while ((imgLongest / inSampleSize) > reqLongest) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    // ==========================================
    // CUSTOM DOUBLE-PANE FILE EXPLORER OVERLAY
    // ==========================================
    private fun buildFileExplorerOverlay(root: FrameLayout) {
        explorerOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E6121212")) 
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { setMargins(48, 48, 48, 48) }
            isClickable = true 
            isFocusable = true
        }

        val contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        explorerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2C2C2E"))
        }

        explorerTitle = TextView(this).apply {
            text = "📁 Multi-Folder Explorer"
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        closeExplorerBtn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            addHoverEffect()
            setOnClickListener { hideFolderExplorer() }
        }

        explorerHeader.addView(explorerTitle)
        explorerHeader.addView(closeExplorerBtn)
        contentBox.addView(explorerHeader)

        currentPathTextView = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 14f
            setPadding(32, 8, 32, 8)
            text = "Shared Storage:"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        contentBox.addView(currentPathTextView)

        val explorerBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        leftScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.4f).apply { rightMargin = 2 }
            setBackgroundColor(Color.parseColor("#2C2C2E"))
        }
        folderTreeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        leftScroll.addView(folderTreeLayout)

        contentListView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            divider = null
            setPadding(24, 24, 24, 24)
            clipToPadding = false
        }

        explorerBody.addView(leftScroll)
        explorerBody.addView(contentListView)
        contentBox.addView(explorerBody)

        selectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2C2C2E"))
        }

        selectedFoldersTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "0 Included | 0 Excluded"
        }

        val actionButtonsContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        clearFoldersBtn = createMinimalButton("Clear") {
            selectedFolders.clear()
            excludedFolders.clear()
            GlobalCache.portraitUris.clear()
            GlobalCache.landscapeUris.clear()
            GlobalCache.squareUris.clear()
            GlobalCache.isScanned = false 
            updateSelectedFoldersText()
            saveWindowState()
            refreshExplorerViews()
        }.apply { 
            setTextColor(Color.parseColor("#FF9500"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        startBtn = createMinimalButton("Done") {
            hideFolderExplorer()
            applyFolderChangesAfterExplorer()
        }.apply { 
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#34C759"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 16 }
        }

        actionButtonsContainer.addView(clearFoldersBtn)
        actionButtonsContainer.addView(startBtn)

        val actionScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionButtonsContainer)
        }

        selectBar.addView(selectedFoldersTextView)
        selectBar.addView(actionScroller)
        contentBox.addView(selectBar)

        explorerOverlay.addView(contentBox)
        root.addView(explorerOverlay)
    }

    private fun applyFolderChangesAfterExplorer() {
        if (selectedFolders.isEmpty()) {
            showFolderPickScreen()
            return
        }

        val newSelectedPaths = selectedFolders.map { it.absolutePath }.toSet()
        val newExcludedPaths = excludedFolders.map { it.absolutePath }.toSet()

        // Immediately filter existing cache by updated folders & exclusions (no full rescan for removals/exclusions)
        GlobalCache.loadDiskCacheIfNeeded(applicationContext)
        GlobalCache.rebuildBucketsFromCachedEntries(newSelectedPaths, newExcludedPaths)

        // Determine if any folders were added or un-excluded
        val dirsToScan = ArrayList<File>()
        for (dir in selectedFolders) {
            if (!GlobalCache.isPathIncluded(dir.absolutePath, preExplorerSelectedPaths, preExplorerExcludedPaths)) {
                dirsToScan.add(dir)
            }
        }
        for (oldExPath in preExplorerExcludedPaths) {
            if (!newExcludedPaths.contains(oldExPath) && GlobalCache.isPathIncluded(oldExPath, newSelectedPaths, newExcludedPaths)) {
                val f = File(oldExPath)
                if (f.exists() && f.isDirectory) dirsToScan.add(f)
            }
        }

        if (dirsToScan.isNotEmpty()) {
            triggerUnifiedScan(specificDirsToWalk = dirsToScan, isIncrementalAdd = true, isFolderChange = true)
        } else if (GlobalCache.entriesByPath.isEmpty()) {
            triggerUnifiedScan(isFolderChange = true)
        } else {
            // Only removals or exclusions occurred: persist updated cache and notify open windows with filtered snapshot
            val remainingEntries = GlobalCache.entriesByPath.values.filter {
                GlobalCache.isPathIncluded(it.path, newSelectedPaths, newExcludedPaths)
            }
            GlobalCache.entriesByPath.clear()
            for (e in remainingEntries) {
                GlobalCache.entriesByPath[e.path] = e
            }
            Thread {
                GlobalCache.writeDiskCacheAtomically(applicationContext, remainingEntries, isComplete = GlobalCache.isCacheComplete)
            }.start()

            val pSnap = GlobalCache.portraitUris.toList()
            val lSnap = GlobalCache.landscapeUris.toList()
            val sSnap = GlobalCache.squareUris.toList()
            val listeners = GlobalCache.scanListeners.toList()
            for (listener in listeners) {
                if (listener !== scanListener) {
                    listener.onScanCompleted(pSnap, lSnap, sSnap)
                }
            }
        }

        if (chosenShape != "NONE") {
            startSlideshowMode(chosenShape)
        } else {
            showShapePickScreen()
        }
    }

    private fun showFolderExplorer() {
        if (!hasStoragePermission()) {
            requestPermissions(arrayOf(getRequiredStoragePermission()), PERMISSION_REQ_CODE)
            return
        }
        slideHandler.removeCallbacks(slideRunnable)
        uiFadeHandler.removeCallbacks(uiFadeRunnable) 
        preExplorerSelectedPaths = selectedFolders.map { it.absolutePath }.toSet()
        preExplorerExcludedPaths = excludedFolders.map { it.absolutePath }.toSet()
        explorerOverlay.visibility = View.VISIBLE
        selectedDirectory = Environment.getExternalStorageDirectory()
        updateSelectedFoldersText()
        refreshExplorerViews()
    }

    private fun hideFolderExplorer() {
        explorerOverlay.visibility = View.GONE
        if (slideshowLayout.visibility == View.VISIBLE) resetUiFadeTimer()
    }

    private fun updateSelectedFoldersText() {
        selectedFoldersTextView.text = "${selectedFolders.size} Included | ${excludedFolders.size} Excluded"
    }

    data class SidebarNode(val directory: File, val depth: Int, val isCurrent: Boolean)

    private fun collectSidebarNodesOffThread(
        directory: File,
        depth: Int,
        storageRootPath: String,
        selectedDirPath: String?,
        outNodes: MutableList<SidebarNode>
    ) {
        val dirPath = directory.absolutePath
        val isCurrent = (dirPath == selectedDirPath)
        outNodes.add(SidebarNode(directory, depth, isCurrent))

        val checkNodes = setOf("Pictures", "DCIM", "Download", "Downloads", "Android", "Movies", "Documents")
        val isRoot = (dirPath == storageRootPath)

        val subdirectories = directory.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        for (sub in subdirectories) {
            val shouldDisplay = !isRoot || checkNodes.contains(sub.name)
            if (shouldDisplay && (selectedDirPath?.startsWith(sub.absolutePath) == true || depth < 1)) {
                collectSidebarNodesOffThread(sub, depth + 1, storageRootPath, selectedDirPath, outNodes)
            }
        }
    }

    private fun refreshExplorerViews() {
        val rootStorage = Environment.getExternalStorageDirectory()
        val currDir = selectedDirectory ?: rootStorage
        val selectedDirPath = currDir.absolutePath
        currentPathTextView.text = selectedDirPath

        val myGen = ++explorerLoadGeneration

        GlobalCache.explorerExecutor.execute {
            val sidebarNodes = ArrayList<SidebarNode>()
            collectSidebarNodesOffThread(rootStorage, 0, rootStorage.absolutePath, selectedDirPath, sidebarNodes)

            val allFiles = currDir.listFiles() ?: emptyArray()
            val children = allFiles.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            val imageCount = allFiles.count { it.isFile && isImageFile(it) }

            val items = mutableListOf<ExplorerItem>()
            if (currDir.absolutePath != rootStorage.absolutePath) {
                items.add(ExplorerItem(File(".. (Go Up)"), true, false, 0))
            }
            if (imageCount > 0) {
                items.add(ExplorerItem(currDir, false, true, imageCount))
            }
            if (children.isEmpty() && imageCount == 0) {
                items.add(ExplorerItem(currDir, false, false, -1)) // Empty marker
            } else {
                items.addAll(children.map { ExplorerItem(it, false, false, 0) })
            }

            runOnUiThread {
                if (myGen != explorerLoadGeneration) return@runOnUiThread
                folderTreeLayout.removeAllViews()
                for (node in sidebarNodes) {
                    val treeItem = TextView(this@MainActivity).apply {
                        val indent = "    ".repeat(node.depth)
                        text = "$indent${if (node.depth > 0) "├─ " else "📱 "}${node.directory.name.ifEmpty { "Shared Storage" }}"
                        setTextColor(if (node.isCurrent) Color.parseColor("#34C759") else Color.WHITE)
                        textSize = 15f
                        setPadding(12, 16, 12, 16)
                        setBackgroundColor(if (node.isCurrent) Color.parseColor("#33FFFFFF") else Color.TRANSPARENT)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        addHoverEffect()

                        setOnClickListener {
                            selectedDirectory = node.directory
                            refreshExplorerViews()
                        }
                    }
                    folderTreeLayout.addView(treeItem)
                }
                contentListView.adapter = ExplorerAdapter(items)
            }
        }
    }

    data class ExplorerItem(val file: File, val isUpButton: Boolean, val isHeader: Boolean, val imageCount: Int)

    inner class ExplorerAdapter(private val items: List<ExplorerItem>) : BaseAdapter() {
        private val VIEW_TYPE_ROW = 0
        private val VIEW_TYPE_HEADER = 1
        private val VIEW_TYPE_EMPTY = 2

        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(pos: Int): Int {
            val item = getItem(pos)
            return when {
                item.isHeader -> VIEW_TYPE_HEADER
                item.imageCount == -1 -> VIEW_TYPE_EMPTY
                else -> VIEW_TYPE_ROW
            }
        }

        override fun getView(pos: Int, recycledView: View?, parent: ViewGroup): View {
            val item = getItem(pos)
            when (getItemViewType(pos)) {
                VIEW_TYPE_HEADER -> {
                    if (recycledView != null) {
                        if (recycledView is ExplorerItemView) {
                            GlobalCache.explorerTypeMismatchCount.incrementAndGet()
                        } else {
                            GlobalCache.explorerHeaderRecycles.incrementAndGet()
                        }
                    }
                    val tv = (recycledView as? TextView) ?: TextView(this@MainActivity).apply {
                        setTextColor(Color.parseColor("#34C759"))
                        textSize = 15f
                        setPadding(16, 16, 16, 32)
                    }
                    tv.text = "📁 Contains ${item.imageCount} images"
                    return tv
                }
                VIEW_TYPE_EMPTY -> {
                    if (recycledView is ExplorerItemView) {
                        GlobalCache.explorerTypeMismatchCount.incrementAndGet()
                    }
                    val tv = (recycledView as? TextView) ?: TextView(this@MainActivity).apply {
                        setTextColor(Color.GRAY)
                        textSize = 16f
                        setPadding(16, 16, 16, 16)
                    }
                    tv.text = "(Directory is Empty)"
                    return tv
                }
                else -> {
                    if (recycledView != null) {
                        if (recycledView !is ExplorerItemView) {
                            GlobalCache.explorerTypeMismatchCount.incrementAndGet()
                        } else {
                            GlobalCache.explorerRowRecycles.incrementAndGet()
                        }
                    }
                    val view = (recycledView as? ExplorerItemView) ?: ExplorerItemView(this@MainActivity)
                    view.bind(item.file, item.isUpButton) {
                        if (item.isUpButton) {
                            selectedDirectory = (selectedDirectory ?: Environment.getExternalStorageDirectory()).parentFile
                        } else if (item.file.isDirectory) {
                            selectedDirectory = item.file
                        }
                        refreshExplorerViews()
                    }
                    return view
                }
            }
        }
    }

    inner class ExplorerItemView(context: Context) : LinearLayout(context) {
        private val iconView = ImageView(context)
        private val label = TextView(context)
        private val checkBox = CheckBox(context)
        @Volatile private var currentFile: File? = null

        init {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 24, 16, 24)
            setBackgroundColor(Color.parseColor("#2C2C2E"))
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            
            iconView.layoutParams = LinearLayout.LayoutParams(80, 80).apply { rightMargin = 24 }
            iconView.scaleType = ImageView.ScaleType.CENTER_CROP
            iconView.setBackgroundColor(Color.parseColor("#3C3C3E"))
            addView(iconView)

            label.setTextColor(Color.WHITE)
            label.textSize = 16f
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(label)

            checkBox.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 16 }
            addView(checkBox)
            
            setOnHoverListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_HOVER_ENTER -> v.setBackgroundColor(Color.parseColor("#3A3A3C"))
                    android.view.MotionEvent.ACTION_HOVER_EXIT -> v.setBackgroundColor(Color.parseColor("#2C2C2E"))
                }
                false
            }
        }

        fun bind(file: File, isUpButton: Boolean, onClick: () -> Unit) {
            currentFile = file
            val isDirectory = file.isDirectory || isUpButton
            label.text = if (isUpButton) "↩ .. (Go Up)" else file.name
            label.setTextColor(if (isDirectory) Color.WHITE else Color.parseColor("#AEAEB2"))
            
            setOnClickListener { onClick() }

            if (!isUpButton) {
                val isExplicitlySelected = selectedFolders.any { it.absolutePath == file.absolutePath }
                val isExplicitlyExcluded = excludedFolders.any { it.absolutePath == file.absolutePath }
                val isIncluded = isFolderIncluded(file)

                checkBox.visibility = View.VISIBLE
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = isIncluded
                
                checkBox.setOnClickListener { 
                    if (isIncluded) {
                        if (isExplicitlySelected) {
                            selectedFolders.removeAll { it.absolutePath == file.absolutePath }
                            excludedFolders.removeAll { it.absolutePath.startsWith(file.absolutePath + File.separator) }
                        } else {
                            excludedFolders.add(file)
                        }
                    } else {
                        if (isExplicitlyExcluded) {
                            excludedFolders.removeAll { it.absolutePath == file.absolutePath }
                        } else {
                            selectedFolders.add(file)
                        }
                    }
                    updateSelectedFoldersText()
                    saveWindowState()
                    (parent as? ListView)?.invalidateViews()
                }
            } else {
                checkBox.visibility = View.GONE
            }

            if (isUpButton) {
                iconView.setImageDrawable(null)
                return
            }

            val filePath = file.absolutePath
            val thumbKey = "thumb:${filePath}@100"
            val cachedThumb = GlobalCache.memoryCache.get(thumbKey)
            if (cachedThumb != null) {
                iconView.setImageBitmap(cachedThumb)
                return
            }

            iconView.setImageDrawable(null)
            GlobalCache.explorerExecutor.execute {
                if (currentFile != file) {
                    GlobalCache.explorerStaleThumbSkips.incrementAndGet()
                    return@execute
                }
                try {
                    val targetImgPath: String = if (isDirectory) {
                        val cachedFirst = GlobalCache.folderFirstImageCache[filePath]
                        if (cachedFirst != null) {
                            cachedFirst
                        } else {
                            val prefix = filePath + File.separatorChar
                            val fromScanCache = GlobalCache.entriesByPath.keys.firstOrNull { it.startsWith(prefix) }
                            val resolved = fromScanCache ?: file.walkTopDown()
                                .onEnter { !it.name.startsWith(".") && it.name != "Android" }
                                .onFail { _, _ -> }
                                .firstOrNull { it.isFile && isImageFile(it) }?.absolutePath ?: ""
                            GlobalCache.folderFirstImageCache[filePath] = resolved
                            resolved
                        }
                    } else {
                        filePath
                    }

                    if (currentFile != file) {
                        GlobalCache.explorerStaleThumbSkips.incrementAndGet()
                        return@execute
                    }

                    if (targetImgPath.isNotEmpty()) {
                        val targetFile = File(targetImgPath)
                        if (isImageFile(targetFile)) {
                            val bmp = decodeSampledBitmap(Uri.fromFile(targetFile), 100, 100)
                            if (bmp != null) {
                                GlobalCache.explorerThumbDecodes.incrementAndGet()
                                GlobalCache.memoryCache.put(thumbKey, bmp)
                                (context as Activity).runOnUiThread {
                                    if (currentFile == file) {
                                        iconView.setImageBitmap(bmp)
                                    } else {
                                        GlobalCache.explorerStaleThumbSkips.incrementAndGet()
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun isFolderIncluded(target: File): Boolean {
        if (excludedFolders.any { target.absolutePath == it.absolutePath || target.absolutePath.startsWith(it.absolutePath + File.separator) }) {
            return false
        }
        return selectedFolders.any { target.absolutePath == it.absolutePath || target.absolutePath.startsWith(it.absolutePath + File.separator) }
    }

    private fun isImageFile(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!BuildConfig.DEBUG) return
            val cmd = intent?.getStringExtra("cmd") ?: return
            val targetWin = intent.getStringExtra("windowId")
            if (targetWin != null && targetWin != windowId && !windowId.startsWith(targetWin)) return

            when (cmd) {
                "PICK_PORTRAIT" -> shapeBtnPortrait?.performClick()
                "PICK_LANDSCAPE" -> shapeBtnLandscape?.performClick()
                "PICK_SQUARE" -> shapeBtnSquare?.performClick()
                "NEXT" -> nextBtn.performClick()
                "PREV" -> prevBtn.performClick()
                "SPAWN" -> spawnNewWindow()
                "CLOSE_WINDOW" -> finish()
                "SET_KEN_BURNS" -> {
                    isKenBurns = intent.getBooleanExtra("enabled", false)
                    saveWindowState()
                    displayCurrentImage()
                }
                "SIMULATE_RESIZE_DRAG" -> {
                    val targetW = intent.getIntExtra("targetW", 900)
                    val targetH = intent.getIntExtra("targetH", 1350)
                    val frames = intent.getIntExtra("frames", 10).coerceAtLeast(2)
                    val stepMs = intent.getLongExtra("stepMs", 25L)
                    val startW = if (imageView.width > 0) imageView.width else 400
                    val startH = if (imageView.height > 0) imageView.height else 600
                    val h = Handler(Looper.getMainLooper())
                    for (i in 1..frames) {
                        h.postDelayed({
                            val frac = i.toFloat() / frames.toFloat()
                            val curW = (startW + ((targetW - startW) * frac)).toInt()
                            val curH = (startH + ((targetH - startH) * frac)).toInt()
                            imageView.layoutParams = FrameLayout.LayoutParams(curW, curH, Gravity.CENTER)
                            imageView.requestLayout()
                        }, i * stepMs)
                    }
                }
                "TEST_EXPLORER_SCROLL" -> {
                    GlobalCache.explorerStaleThumbSkips.set(0)
                    GlobalCache.explorerThumbDecodes.set(0)
                    GlobalCache.explorerRowRecycles.set(0)
                    GlobalCache.explorerHeaderRecycles.set(0)
                    GlobalCache.explorerTypeMismatchCount.set(0)
                    GlobalCache.folderFirstImageCache.clear()
                    val targetDir = intent.getStringExtra("path") ?: "/storage/emulated/0/Pictures/Waifeet"
                    showFolderExplorer()
                    selectedDirectory = File(targetDir)
                    refreshExplorerViews()
                    val h = Handler(Looper.getMainLooper())
                    val positions = listOf(15, 45, 90, 140, 175, 120, 60, 0, 80, 160, 30, 0, 100, 175, 0)
                    for ((idx, pos) in positions.withIndex()) {
                        h.postDelayed({
                            val count = contentListView.adapter?.count ?: 0
                            if (count > 0) {
                                contentListView.setSelection(pos.coerceIn(0, count - 1))
                            }
                        }, 400L + idx * 45L)
                    }
                    h.postDelayed({
                        Log.i(
                            "FlatGalleryPerf",
                            "ExplorerScrollTest complete: items=${contentListView.adapter?.count ?: 0} " +
                                "headerRecycles=${GlobalCache.explorerHeaderRecycles.get()} " +
                                "rowRecycles=${GlobalCache.explorerRowRecycles.get()} " +
                                "typeMismatches=${GlobalCache.explorerTypeMismatchCount.get()} " +
                                "thumbDecodes=${GlobalCache.explorerThumbDecodes.get()} " +
                                "staleSkips=${GlobalCache.explorerStaleThumbSkips.get()} " +
                                "activeThreads=${Thread.activeCount()}"
                        )
                        hideFolderExplorer()
                    }, 400L + positions.size * 45L + 400L)
                }
                "RAPID_NAV" -> {
                    val steps = intent.getIntExtra("steps", 16)
                    val h = Handler(Looper.getMainLooper())
                    for (i in 0 until steps) {
                        h.postDelayed({
                            if (i % 3 == 2) prevBtn.performClick() else nextBtn.performClick()
                            Log.i(
                                "FlatGalleryPerf",
                                "RapidNav step=$i playhead=$playheadIndex/${playbackOrder.size} " +
                                    "active=${activeUris.size} uri=${getCurrentDisplayedUri()?.lastPathSegment} " +
                                    "hasDrawable=${imageView.drawable != null}"
                            )
                        }, i * 40L)
                    }
                }
                "FAV_IMAGE" -> {
                    toggleFavoriteCurrent()
                    performFavoriteAction(false)
                }
                "FAV_FOLDER" -> {
                    toggleFavoriteCurrent()
                    performFavoriteAction(true)
                }
                "SET_FAV_MODE" -> {
                    isFavoritesMode = intent.getBooleanExtra("enabled", false)
                    saveWindowState()
                    if (chosenShape != "NONE") startSlideshowMode(chosenShape)
                }
                "ADD_EXCLUSION" -> {
                    val exPath = intent.getStringExtra("path") ?: return
                    preExplorerSelectedPaths = selectedFolders.map { it.absolutePath }.toSet()
                    preExplorerExcludedPaths = excludedFolders.map { it.absolutePath }.toSet()
                    val f = File(exPath)
                    if (excludedFolders.none { it.absolutePath == f.absolutePath }) {
                        excludedFolders.add(f)
                    }
                    saveWindowState()
                    applyFolderChangesAfterExplorer()
                }
                "REMOVE_EXCLUSION" -> {
                    val exPath = intent.getStringExtra("path") ?: return
                    preExplorerSelectedPaths = selectedFolders.map { it.absolutePath }.toSet()
                    preExplorerExcludedPaths = excludedFolders.map { it.absolutePath }.toSet()
                    excludedFolders.removeAll { it.absolutePath == exPath }
                    saveWindowState()
                    applyFolderChangesAfterExplorer()
                }
                "DUMP_STATE" -> {
                    val bmp = (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    Log.i(
                        "FlatGalleryPerf",
                        "STATE win=${windowId.take(8)} shape=$chosenShape vis=$isWindowVisible " +
                            "wizardVis=${wizardLayout.visibility == View.VISIBLE} slideVis=${slideshowLayout.visibility == View.VISIBLE} " +
                            "active=${activeUris.size} order=${playbackOrder.size} playhead=$playheadIndex " +
                            "favMode=$isFavoritesMode favTotal=${GlobalCache.favoritePaths.size} " +
                            "curUri=${getCurrentDisplayedUri()?.lastPathSegment} " +
                            "bmp=${if (bmp != null) "${bmp.width}x${bmp.height}" else "null"} " +
                            "view=${imageView.width}x${imageView.height} target=$lastDecodedTargetSize " +
                            "kb=$isKenBurns scale=${String.format(Locale.US, "%.3f", imageView.scaleX)} " +
                            "cacheKB=${GlobalCache.memoryCache.size()}/${GlobalCache.memoryCache.maxSize()} " +
                            "listeners=${GlobalCache.scanListeners.size} " +
                            "buckets=P:${GlobalCache.portraitUris.size}/L:${GlobalCache.landscapeUris.size}/S:${GlobalCache.squareUris.size}"
                    )
                }
            }
        }
    }

    private fun registerTestReceiver() {
        if (!BuildConfig.DEBUG) return
        try {
            val filter = IntentFilter("com.yourname.flatgallery.TEST_CMD")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(testReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(testReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        isWindowVisible = true
        val startElapsed = SystemClock.elapsedRealtime()
        if (slideshowLayout.visibility == View.VISIBLE &&
            settingsOverlay.visibility != View.VISIBLE &&
            explorerOverlay.visibility != View.VISIBLE &&
            favoritePromptOverlay.visibility != View.VISIBLE &&
            activeUris.isNotEmpty()
        ) {
            if (imageView.drawable == null) {
                displayCurrentImage()
            } else {
                prefetchNextImage()
            }
            if (isPlaying) {
                val delay = timerOptions[currentTimerIndex]
                slideHandler.removeCallbacks(slideRunnable)
                slideHandler.postDelayed(slideRunnable, delay)
                if (isKenBurns) {
                    imageView.scaleX = 1.0f
                    imageView.scaleY = 1.0f
                    applyKenBurns(delay)
                }
            }
        }
        Log.i(
            "FlatGalleryPerf",
            "Window ${windowId.take(8)} onStart uri=${getCurrentDisplayedUri()?.lastPathSegment} " +
                "hasDrawable=${imageView.drawable != null} callbackMs=${SystemClock.elapsedRealtime() - startElapsed}"
        )
    }

    override fun onResume() {
        super.onResume()
        if (!GlobalCache.nightModeListeners.contains(nightModeListener)) {
            GlobalCache.nightModeListeners.add(nightModeListener)
        }
        updateNightModeUi()
        if (isShowingPermissionDeniedScreen && hasStoragePermission()) {
            Log.i("FlatGalleryPerf", "Storage permission GRANTED after returning from Settings")
            isShowingPermissionDeniedScreen = false
            restoreWindowState()
        }
    }

    override fun onStop() {
        super.onStop()
        isWindowVisible = false
        Log.i("FlatGalleryPerf", "Window ${windowId.take(8)} onStop uri=${getCurrentDisplayedUri()?.lastPathSegment}")
        slideHandler.removeCallbacks(slideRunnable)
        uiFadeHandler.removeCallbacks(uiFadeRunnable)
        if (::imageView.isInitialized) {
            imageView.animate().cancel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) {
            try { unregisterReceiver(testReceiver) } catch (_: Exception) {}
        }
        GlobalCache.nightModeListeners.remove(nightModeListener)
        val removedListener = GlobalCache.scanListeners.remove(scanListener)
        Log.i(
            "FlatGalleryPerf",
            "Window ${windowId.take(8)} onDestroy scanListener removed=$removedListener activeListeners=${GlobalCache.scanListeners.size}"
        )
        slideHandler.removeCallbacks(slideRunnable)
        uiFadeHandler.removeCallbacks(uiFadeRunnable)
        resizeHandler.removeCallbacksAndMessages(null)
        
        if (isFinishing) {
            getSharedPreferences("FlatGalleryPrefs", Context.MODE_PRIVATE).edit().apply {
                remove("shuffle_$windowId")
                remove("timer_$windowId")
                remove("shape_$windowId")
                remove("favmode_$windowId")
                remove("kenburns_$windowId")
                apply()
            }
        }
    }
}

package com.yourname.flatgallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object GlobalCache {
    var isScanned = false
    var isScanning = false
    val portraitUris = mutableListOf<Uri>()
    val landscapeUris = mutableListOf<Uri>()
    val squareUris = mutableListOf<Uri>()
    var transitionIndex = 0 
    var favoritePaths = mutableSetOf<String>()
    var nightModeLevel = 0
    val nightModeListeners = mutableListOf<() -> Unit>()
    val scanListeners = mutableListOf<() -> Unit>()
}

class MainActivity : Activity() {

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

    // --- Playback Data ---
    private var activeUris = mutableListOf<Uri>()
    private val playbackOrder = mutableListOf<Int>()
    private var playheadIndex = 0
    private var isPlaying = true
    private var isShuffle = true
    private var isFavoritesMode = false
    private var isKenBurns = false
    private var chosenShape = "NONE"

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

    private lateinit var memoryCache: LruCache<String, Bitmap>
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

        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 4
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        windowId = savedInstanceState?.getString("WINDOW_ID") ?: UUID.randomUUID().toString()

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
        restoreWindowState()
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
        wizardTitle.text = "Welcome to Flat Gallery"
        wizardSubtitle.text = "Set up your custom portrait, landscape, or square frame."
        wizardActionContainer.removeAllViews()
        wizardActionContainer.addView(createSolidThemeButton("Next ➔") { showFolderPickScreen() })
    }

    private fun showFolderPickScreen() {
        wizardTitle.text = "Select Media Folders"
        wizardSubtitle.text = "Pick the folders you want this frame to cycle through.\nParent folders include subfolders, but you can manually uncheck children to exclude them."
        wizardActionContainer.removeAllViews()
        wizardActionContainer.addView(createSolidThemeButton("Choose Folders") { showFolderExplorer() })
    }

    private fun showSortingScreen(autoShape: String = "NONE") {
        if (GlobalCache.isScanned) {
            if (autoShape != "NONE") startSlideshowMode(autoShape)
            else showShapePickScreen()
            return
        }

        wizardLayout.visibility = View.VISIBLE
        slideshowLayout.visibility = View.GONE
        wizardTitle.text = "Analyzing Dimensions..."
        wizardSubtitle.text = "Recursively sorting images by aspect ratio. This may take a moment."
        wizardActionContainer.removeAllViews()
        
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        wizardActionContainer.addView(spinner)

        Thread {
            GlobalCache.portraitUris.clear()
            GlobalCache.landscapeUris.clear()
            GlobalCache.squareUris.clear()

            for (dir in selectedFolders) {
                try {
                    dir.walkTopDown()
                        .onEnter { folder -> !excludedFolders.any { it.absolutePath == folder.absolutePath } }
                        .onFail { _, _ -> }
                        .forEach { file ->
                            if (file.isFile && isImageFile(file)) {
                                try {
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(file.absolutePath, options)
                                    val w = options.outWidth.toFloat()
                                    val h = options.outHeight.toFloat()
                                    if (w > 0 && h > 0) {
                                        val uri = Uri.fromFile(file)
                                        val ratio = w / h
                                        if (ratio > 1.05f) GlobalCache.landscapeUris.add(uri)
                                        else if (ratio < 0.95f) GlobalCache.portraitUris.add(uri)
                                        else GlobalCache.squareUris.add(uri)
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                } catch (e: Exception) { e.printStackTrace() }
            }

            GlobalCache.isScanned = true

            runOnUiThread {
                if (autoShape != "NONE") startSlideshowMode(autoShape)
                else showShapePickScreen()
            }
        }.start()
    }

    private fun showShapePickScreen() {
        wizardLayout.visibility = View.VISIBLE
        slideshowLayout.visibility = View.GONE
        wizardTitle.text = "Choose Frame Shape"
        wizardSubtitle.text = "Manually resize your system window to match your choice."
        wizardActionContainer.removeAllViews()
        
        wizardActionContainer.addView(createSolidThemeButton("Portrait (${GlobalCache.portraitUris.size})") { startSlideshowMode("PORTRAIT") })
        wizardActionContainer.addView(createSolidThemeButton("Square (${GlobalCache.squareUris.size})") { startSlideshowMode("SQUARE") })
        wizardActionContainer.addView(createSolidThemeButton("Landscape (${GlobalCache.landscapeUris.size})") { startSlideshowMode("LANDSCAPE") })
    }

    private fun startSlideshowMode(shape: String) {
        chosenShape = shape
        activeUris.clear()
        
        val sourceUris = when (shape) {
            "PORTRAIT" -> GlobalCache.portraitUris
            "LANDSCAPE" -> GlobalCache.landscapeUris
            "SQUARE" -> GlobalCache.squareUris
            else -> emptyList()
        }

        if (isFavoritesMode) {
            activeUris.addAll(sourceUris.filter { GlobalCache.favoritePaths.contains(it.path) })
        } else {
            activeUris.addAll(sourceUris)
        }

        saveWindowState()
        wizardLayout.visibility = View.GONE
        slideshowLayout.visibility = View.VISIBLE
        resetUiFadeTimer()
        updateResponsiveUi()

        if (activeUris.isEmpty()) {
            playbackOrder.clear()
            slideHandler.removeCallbacks(slideRunnable)
            imageView.setImageDrawable(null)
            overlayInfoText.text = ""
            starButton.text = "☆"
            starButton.setTextColor(Color.WHITE)
            
            val modeStr = if (isFavoritesMode) "Favorites" else "Selected Folders"
            infoTextView.text = "No images found in $modeStr for $shape shape.\n\nOpen Settings to change your Frame Shape or Source."
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
                if (isPlaying && activeUris.isNotEmpty()) {
                    slideHandler.postDelayed(slideRunnable, timerOptions[currentTimerIndex])
                }
            }
        }
        settingsOverlay.addView(closeBtn)

        root.addView(settingsOverlay)
    }

    private fun showSettingsMenu() {
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
                showSortingScreen(if (chosenShape != "NONE") chosenShape else "NONE")
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
        if (isPlaying && activeUris.isNotEmpty()) {
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
        val folderName = uri?.let { File(it.path ?: "").parentFile?.name } ?: "Unknown"
        
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
                if (currentUri != null) activeUris.remove(currentUri)
                
                if (entireFolder) {
                    val parentPath = File(path).parent
                    activeUris.removeAll { File(it.path ?: "").parent == parentPath }
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

    private fun initPlaybackQueue(keepCurrentUri: Uri? = null, skipDisplay: Boolean = false) {
        val currentlyDisplayedUri = if (playbackOrder.isNotEmpty() && playheadIndex < playbackOrder.size) {
            val idx = playbackOrder[playheadIndex]
            if (idx < activeUris.size) activeUris[idx] else null
        } else null

        val targetUri = keepCurrentUri ?: currentlyDisplayedUri

        playbackOrder.clear()

        if (isShuffle) {
            val folderGroups = activeUris.indices.groupBy { 
                File(activeUris[it].path ?: "").parent ?: "unknown"
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
                val targetParent = File(targetUri.path ?: "").parent
                val folderIndices = activeUris.indices.filter {
                    File(activeUris[it].path ?: "").parent == targetParent
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
            val newActualIndex = activeUris.indexOf(targetUri)
            if (newActualIndex != -1) {
                playheadIndex = playbackOrder.indexOf(newActualIndex).takeIf { it >= 0 } ?: 0
                foundTarget = true
            }
        }
        
        if (!foundTarget) {
            playheadIndex = 0
        }
        
        val shouldSkipDisplay = skipDisplay || (foundTarget && targetUri == currentlyDisplayedUri)
        
        if (!shouldSkipDisplay) {
            displayCurrentImage()
        }
        
        resetTimerIfPlaying()
    }

    private fun handleMissingFile(missingUri: Uri) {
        triggerBackgroundRescan()
        
        val missingParent = File(missingUri.path ?: "").parentFile
        val actualIdx = activeUris.indexOf(missingUri)
        
        if (actualIdx != -1) {
            val currentlyDisplayedUri = if (playbackOrder.isNotEmpty() && playheadIndex < playbackOrder.size) {
                val oldIdx = playbackOrder[playheadIndex]
                activeUris.getOrNull(oldIdx)
            } else null

            activeUris.removeAt(actualIdx)
            
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

    private fun onScanComplete() {
        if (chosenShape != "NONE") {
            val currentImageUri = if (playbackOrder.isNotEmpty() && playheadIndex < playbackOrder.size) {
                activeUris.getOrNull(playbackOrder[playheadIndex])
            } else null

            activeUris.clear()
            val sourceUris = when (chosenShape) {
                "PORTRAIT" -> GlobalCache.portraitUris
                "LANDSCAPE" -> GlobalCache.landscapeUris
                "SQUARE" -> GlobalCache.squareUris
                else -> emptyList()
            }

            if (isFavoritesMode) {
                activeUris.addAll(sourceUris.filter { GlobalCache.favoritePaths.contains(it.path) })
            } else {
                activeUris.addAll(sourceUris)
            }

            initPlaybackQueue(keepCurrentUri = currentImageUri, skipDisplay = true)

            if (wizardLayout.visibility == View.VISIBLE) {
                wizardLayout.visibility = View.GONE
                slideshowLayout.visibility = View.VISIBLE
                resetUiFadeTimer()
                displayCurrentImage()
            }
        }
    }

    private fun triggerBackgroundRescan() {
        GlobalCache.scanListeners.add { onScanComplete() }
        
        if (GlobalCache.isScanning) return
        GlobalCache.isScanning = true
        
        Thread {
            val newPortraitUris = mutableListOf<Uri>()
            val newLandscapeUris = mutableListOf<Uri>()
            val newSquareUris = mutableListOf<Uri>()

            val safeFolders = selectedFolders.toList()
            val safeExcluded = excludedFolders.toList()
            
            for (dir in safeFolders) {
                if (!dir.exists()) continue
                try {
                    dir.walkTopDown()
                        .onEnter { folder -> !safeExcluded.any { it.absolutePath == folder.absolutePath } }
                        .onFail { _, _ -> }
                        .forEach { file ->
                            if (file.isFile && isImageFile(file)) {
                                try {
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(file.absolutePath, options)
                                    val w = options.outWidth.toFloat()
                                    val h = options.outHeight.toFloat()
                                    if (w > 0 && h > 0) {
                                        val uri = Uri.fromFile(file)
                                        val ratio = w / h
                                        if (ratio > 1.05f) newLandscapeUris.add(uri)
                                        else if (ratio < 0.95f) newPortraitUris.add(uri)
                                        else newSquareUris.add(uri)
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                } catch (e: Exception) { }
            }

            GlobalCache.portraitUris.clear()
            GlobalCache.portraitUris.addAll(newPortraitUris)
            GlobalCache.landscapeUris.clear()
            GlobalCache.landscapeUris.addAll(newLandscapeUris)
            GlobalCache.squareUris.clear()
            GlobalCache.squareUris.addAll(newSquareUris)
            GlobalCache.isScanned = true
            GlobalCache.isScanning = false

            val listeners = GlobalCache.scanListeners.toList()
            GlobalCache.scanListeners.clear()
            
            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it.invoke() }
            }
        }.start()
    }

    private fun prevImage() {
        if (playbackOrder.isEmpty()) return
        playheadIndex = (playheadIndex - 1 + playbackOrder.size) % playbackOrder.size
        displayCurrentImage()
        resetTimerIfPlaying()
    }

    private fun nextImage(auto: Boolean) {
        if (playbackOrder.isEmpty()) return
        playheadIndex = (playheadIndex + 1) % playbackOrder.size
        displayCurrentImage()
        if (!auto) resetTimerIfPlaying()
        else if (isPlaying) slideHandler.postDelayed(slideRunnable, timerOptions[currentTimerIndex])
    }

    private fun resetTimerIfPlaying() {
        slideHandler.removeCallbacks(slideRunnable)
        if (isPlaying) {
            slideHandler.postDelayed(slideRunnable, timerOptions[currentTimerIndex])
        }
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
        
        val uri = activeUris[actualImageIndex]
        
        val file = File(uri.path ?: "")
        if (!file.exists()) {
            handleMissingFile(uri)
            return
        }
        
        val timeStr = timeFormat.format(Date())
        val fileName = file.name
        overlayInfoText.text = "$timeStr • $fileName"

        if (GlobalCache.favoritePaths.contains(uri.path)) {
            starButton.text = "★"
            starButton.setTextColor(Color.parseColor("#FFD700"))
        } else {
            starButton.text = "☆"
            starButton.setTextColor(Color.WHITE)
        }
        
        val cachedBitmap = memoryCache.get(uri.toString())
        if (cachedBitmap != null) {
            applyTransition(cachedBitmap)
            prefetchNextImage()
        } else {
            Thread {
                val bitmap = decodeSampledBitmap(uri, 2048, 2048)
                if (bitmap != null) {
                    memoryCache.put(uri.toString(), bitmap)
                    runOnUiThread {
                        applyTransition(bitmap)
                        prefetchNextImage()
                    }
                } else {
                    runOnUiThread { imageView.setImageDrawable(null) }
                }
            }.start()
        }
    }



    private fun applyTransition(bitmap: Bitmap) {
        imageView.animate().cancel()
        imageView.scaleX = 1.0f
        imageView.scaleY = 1.0f

        val kenBurnsTrigger = Runnable {
            if (isKenBurns && isPlaying) {
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
        if (playbackOrder.isEmpty() || activeUris.isEmpty()) return
        val nextPlayheadIndex = (playheadIndex + 1) % playbackOrder.size
        val nextActualImageIndex = playbackOrder[nextPlayheadIndex]
        if (nextActualImageIndex >= activeUris.size) return 
        
        val nextUri = activeUris[nextActualImageIndex]
        
        val file = File(nextUri.path ?: "")
        if (!file.exists()) {
            handleMissingFile(nextUri)
            return
        }

        if (memoryCache.get(nextUri.toString()) == null) {
            Thread {
                val nextBitmap = decodeSampledBitmap(nextUri, 2048, 2048)
                if (nextBitmap != null) {
                    memoryCache.put(nextUri.toString(), nextBitmap)
                }
            }.start()
        }
    }

    private fun decodeSampledBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val path = uri.path ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.apply {
                inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
                inJustDecodeBounds = false
            }
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
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
            showSortingScreen("NONE") 
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

    private fun showFolderExplorer() {
        slideHandler.removeCallbacks(slideRunnable)
        uiFadeHandler.removeCallbacks(uiFadeRunnable) 
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

    private fun refreshExplorerViews() {
        folderTreeLayout.removeAllViews()

        val rootStorage = Environment.getExternalStorageDirectory()
        currentPathTextView.text = selectedDirectory?.absolutePath ?: "Internal Shared Storage"

        buildLeftSidebarTree(rootStorage, 0)

        val currDir = selectedDirectory ?: rootStorage
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

        contentListView.adapter = ExplorerAdapter(items)
    }

    data class ExplorerItem(val file: File, val isUpButton: Boolean, val isHeader: Boolean, val imageCount: Int)

    inner class ExplorerAdapter(private val items: List<ExplorerItem>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, recycledView: View?, parent: ViewGroup): View {
            val item = getItem(pos)
            if (item.isHeader) {
                return TextView(this@MainActivity).apply {
                    text = "📁 Contains ${item.imageCount} images"
                    setTextColor(Color.parseColor("#34C759"))
                    textSize = 15f
                    setPadding(16, 16, 16, 32)
                }
            }
            if (item.imageCount == -1) {
                return TextView(this@MainActivity).apply {
                    text = "(Directory is Empty)"
                    setTextColor(Color.GRAY)
                    textSize = 16f
                    setPadding(16, 16, 16, 16)
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

    inner class ExplorerItemView(context: Context) : LinearLayout(context) {
        private val iconView = ImageView(context)
        private val label = TextView(context)
        private val checkBox = CheckBox(context)
        private var currentFile: File? = null
        private var loadThread: Thread? = null

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

            iconView.setImageDrawable(null)
            loadThread?.interrupt()
            
            if (!isUpButton) {
                loadThread = Thread {
                    try {
                        val targetImg = if (isDirectory) {
                            file.walkTopDown()
                                .onEnter { !it.name.startsWith(".") && it.name != "Android" }
                                .onFail { _, _ -> }
                                .firstOrNull { it.isFile && isImageFile(it) } 
                        } else file

                        if (targetImg != null && isImageFile(targetImg)) {
                            val bmp = decodeSampledBitmap(Uri.fromFile(targetImg), 100, 100)
                            if (!Thread.currentThread().isInterrupted) {
                                (context as Activity).runOnUiThread { 
                                    if (currentFile == file) iconView.setImageBitmap(bmp) 
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
                loadThread?.start()
            }
        }
    }

    private fun buildLeftSidebarTree(directory: File, depth: Int) {
        val storageRoot = Environment.getExternalStorageDirectory()
        val isCurrent = (directory.absolutePath == selectedDirectory?.absolutePath)
        
        val treeItem = TextView(this).apply {
            val indent = "    ".repeat(depth)
            text = "$indent${if (depth > 0) "├─ " else "📱 "}${directory.name.ifEmpty { "Shared Storage" }}"
            setTextColor(if (isCurrent) Color.parseColor("#34C759") else Color.WHITE)
            textSize = 15f
            setPadding(12, 16, 12, 16)
            setBackgroundColor(if (isCurrent) Color.parseColor("#33FFFFFF") else Color.TRANSPARENT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            addHoverEffect()
            
            setOnClickListener {
                selectedDirectory = directory
                refreshExplorerViews()
            }
        }
        folderTreeLayout.addView(treeItem)

        val checkNodes = listOf("Pictures", "DCIM", "Download", "Downloads", "Android", "Movies", "Documents")
        val isRoot = directory.absolutePath == storageRoot.absolutePath

        val subdirectories = directory.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
        for (sub in subdirectories) {
            val shouldDisplay = !isRoot || checkNodes.contains(sub.name)
            if (shouldDisplay && (selectedDirectory?.absolutePath?.startsWith(sub.absolutePath) == true || depth < 1)) {
                buildLeftSidebarTree(sub, depth + 1)
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

    override fun onResume() {
        super.onResume()
        if (!GlobalCache.nightModeListeners.contains(nightModeListener)) {
            GlobalCache.nightModeListeners.add(nightModeListener)
        }
        updateNightModeUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        GlobalCache.nightModeListeners.remove(nightModeListener)
        slideHandler.removeCallbacks(slideRunnable)
        uiFadeHandler.removeCallbacks(uiFadeRunnable)
        
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

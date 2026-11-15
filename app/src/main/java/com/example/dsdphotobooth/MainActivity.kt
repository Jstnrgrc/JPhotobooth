package com.example.dsdphotobooth

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object { private val processStarted = java.util.concurrent.atomic.AtomicBoolean(false) }
    private lateinit var store: BoothStore
    private lateinit var thermal: ThermalPrinter
    private var supportSession = 0L
    private val nearby = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && adminActive()) printer()
        else toast("Nearby Devices permission is required to connect over Bluetooth.")
    }
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var admin = false
    private var lastAdminUse = 0L
    private var activeSession = 0L
    private var pendingOrder = 0L
    private var cashCodeStep = false
    private var slipStatus: SlipStatus = SlipStatus.UNKNOWN

    private enum class SlipStatus { PRINTING, SENT, FAILED, UNKNOWN, NO_PRINTER }
    private var retakeSlot = 0
    private var foreground = false
    private var originalBrightness: Float? = null

    private fun setCaptureLight(enabled: Boolean) {
        val attrs = window.attributes
        if (enabled) {
            if (originalBrightness == null) originalBrightness = attrs.screenBrightness
            attrs.screenBrightness = 1f
        } else {
            attrs.screenBrightness = originalBrightness ?: return
            originalBrightness = null
        }
        window.attributes = attrs
    }

    private fun applyEventFullscreen() {
        val bars = androidx.core.view.WindowCompat.getInsetsController(window,window.decorView)
        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (store.sessionMode && store.fullscreenEvent) bars.hide(WindowInsetsCompat.Type.systemBars())
        else bars.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun startEventLock() {
        applyEventFullscreen()
        if (store.fullscreenEvent) {
            val manager = getSystemService(android.app.ActivityManager::class.java)
            if (manager.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE)
                runCatching { startLockTask() }.onFailure { toast("Full screen is on. Android could not pin this app.") }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::store.isInitialized) applyEventFullscreen()
    }
    private var cameraProvider: ProcessCameraProvider? = null
    private var resetCountdown: (() -> Unit)? = null
    private val countdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var exportId = 0L
    private var exportKind = ""
    private var importLayout: BoothLayout? = null
    private var replacingArtwork: ArtworkTemplate? = null
    private val designClock = android.os.Handler(android.os.Looper.getMainLooper())
    private var busy = false
    private var exitPromptOpen = false
    private var eventIdle = false
    private var screenVersion = 0L
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && activeSession != 0L) showSession(activeSession)
        else toast("Camera permission is needed. Your ticket is saved.")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = exportId
        val kind = exportKind
        exportId = 0
        exportKind = ""
        if(uri != null && id != 0L && adminActive()) {
            task({
                val stream = contentResolver.openOutputStream(uri,"wt") ?: error("Could not open the destination.")
                stream.use {
                    if(kind == "batch") ticketsPdf(store.tickets(id),it) else receiptPdf(requireNotNull(store.session(id)),it)
                }
                store.exported(if(kind == "batch") "TICKET_PDF_EXPORTED" else "RECEIPT_PDF_EXPORTED",id)
            }) { toast("PDF saved.") }
        }
    }
    private val pickWelcomeCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && adminActive()) {
            task({
                val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder, info, _ ->
                    val scale = minOf(1f,1600f / maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),
                        (info.size.height*scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val file = welcomeCover()
                try {
                    val out = file.startWrite()
                    try {
                        check(image.compress(Bitmap.CompressFormat.PNG,100,out))
                        file.finishWrite(out)
                    } catch (error: Exception) {
                        file.failWrite(out)
                        throw error
                    }
                } finally { image.recycle() }
            }) {
                store.saveWelcomeFraming(CoverFraming(1f,0f,0f))
                toast("Cover photo saved.")
                adjustWelcomeCover()
            }
        }
    }

    private var guideLayout: BoothLayout? = null
    private val saveLayoutGuide = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val layout = guideLayout
        guideLayout = null
        if (uri != null && layout != null && adminActive()) {
            task({ writeLayoutGuide(layout,uri) }) { toast("Design guide saved.") }
        }
    }

    private val importArtwork = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val layout = importLayout
        importLayout = null
        val existing = replacingArtwork
        replacingArtwork = null
        if (uri != null && layout != null && adminActive()) {
            task({
                runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder, info, _ ->
                    val ratio = info.size.height.toFloat()/info.size.width
                    require(layout.acceptsRatio(ratio)) { "Use the same proportions as ${layout.artworkSize}." }
                    val scale = minOf(1f,2200f / maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),
                        (info.size.height*scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                } }
            }) { result ->
                val image = result.getOrNull()
                if (image == null) toast("Choose a readable PNG or JPG with the proportions of ${layout.artworkSize}.")
                else if (adminActive()) markArtwork(layout,image,existing,false) else image.recycle()
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = BoothStore(this)
        thermal = ThermalPrinter(this)
        if (processStarted.compareAndSet(false,true)) store.recoverPrints()
        activeSession = state?.getLong("activeSession") ?: 0L
        pendingOrder = state?.getLong("pendingOrder") ?: 0L
        cashCodeStep = state?.getBoolean("cashCodeStep") ?: false
        slipStatus = SlipStatus.entries.firstOrNull { it.name == state?.getString("slipStatus") }
            ?.takeUnless { it == SlipStatus.PRINTING } ?: SlipStatus.UNKNOWN
        retakeSlot = state?.getInt("retakeSlot") ?: 0
        // Exports require a fresh authenticated admin session after activity recreation.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (store.sessionMode) {
                    if (eventIdle) requestSessionModeExit()
                } else if (!busy) {
                    if (admin) {
                        if (adminActive()) adminBack?.invoke() else login()
                    } else if (activeSession == 0L) home()
                }
            }
        })
        store.expiredPhotos().forEach { id ->
            (1..4).forEach { photo(id,it).delete() }
            (1..2).forEach { retakePhoto(id,it).delete() }
        }
        if (activeSession != 0L) showSession(activeSession) else home()
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        applyEventFullscreen()
    }
    override fun onPause() {
        foreground = false
        setCaptureLight(false)
        countdownHandler.removeCallbacksAndMessages(null)
        resetCountdown?.invoke()
        super.onPause()
    }
    override fun onDestroy() {
        stopCamera()
        designClock.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("activeSession", activeSession)
        outState.putLong("pendingOrder", pendingOrder)
        outState.putBoolean("cashCodeStep",cashCodeStep)
        outState.putString("slipStatus",slipStatus.name)
        outState.putInt("retakeSlot", retakeSlot)
        super.onSaveInstanceState(outState)
    }
    private fun stopCamera() {
        countdownHandler.removeCallbacksAndMessages(null)
        setCaptureLight(false)
        resetCountdown = null
        cameraProvider?.unbindAll()
        cameraProvider = null
    }
    private fun photo(id: Long, index: Int) = File(File(filesDir,"photos"),"${id}_${index}.jpg")
    private fun retakePhoto(id: Long, ordinal: Int) = File(File(filesDir,"photos"),"${id}_retake_${ordinal}.jpg")
    private fun selectedPhotos(session: Session): List<File> {
        val replacements = store.retakes(session.id).toMap()
        return (1..session.photos).map { slot ->
            replacements[slot]?.let { retakePhoto(session.id,it) } ?: photo(session.id,slot)
        }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(text: String) = Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private var adminBack: (() -> Unit)? = null

    private fun page(title: String, idle: Boolean = false, step: String? = null, cover: Boolean = false) {
        designClock.removeCallbacksAndMessages(null)
        screenVersion++
        eventIdle = idle
        adminBack = null
        supportActionBar?.hide()
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24),dp(if (cover) 16 else if (idle) 34 else 22),dp(24),dp(32))
        }
        val column = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels,dp(580)),-2,(if (cover) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL))
        }
        val scroll = ScrollView(this).apply {
            if (!cover) setBackgroundColor(Mono.paper)
            isFillViewport = true
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            val available = (view.width - bars.left - bars.right).takeIf { it > 0 }
                ?: (resources.displayMetrics.widthPixels - bars.left - bars.right).coerceAtLeast(1)
            content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply { width = minOf(available,dp(580)) }
            insets
        }
        if (cover) {
            val root = FrameLayout(this).apply { setBackgroundColor(Mono.ink) }
            val photo = WelcomeCoverView(this,store.welcomeFraming())
            root.addView(photo,FrameLayout.LayoutParams(-1,-1))
            root.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x18000000,0x55000000,0xEF000000.toInt()))
            },FrameLayout.LayoutParams(-1,-1))
            root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
            setContentView(root)
            task({ readWelcomeCover() }) { photo.image = it }
        } else setContentView(scroll)
        val masthead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        content.addView(masthead)
        masthead.addView(TextView(this).apply {
            text = if (admin) "DSD  /  BOOTH ADMIN" else "DSD  /  PHOTO BOOTH"
            setTextColor(if (cover) Mono.white else Mono.ink)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 12f
            letterSpacing = .15f
            layoutParams = LinearLayout.LayoutParams(0,-2,1f)
        })
        content.addView(View(this).apply {
            setBackgroundColor(if (cover) Mono.white else Mono.ink)
            layoutParams = LinearLayout.LayoutParams(-1,dp(1)).apply { setMargins(0,dp(12),0,dp(22)) }
        })
        if (step != null) label(step, 12).apply {
            letterSpacing = .14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        label(title,if (idle) 46 else 34).apply {
            setTextColor(if (cover) Mono.white else Mono.ink)
            typeface = android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD)
            letterSpacing = -.025f
            ViewCompat.setAccessibilityHeading(this,true)
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1),stroke)
        }

    private fun tile(fill: Int, stroke: Int? = null, inverse: Boolean = false) =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(if (inverse) 0x28FFFFFF else 0x24000000),
            rounded(fill,14,stroke),rounded(Mono.white,14)
        )

    private fun styleButton(view: Button, primary: Boolean) = with(view) {
        isAllCaps = false
        textSize = 16f
        typeface = android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        letterSpacing = .005f
        minimumHeight = dp(54)
        minHeight = dp(54)
        backgroundTintList = null
        background = tile(if (primary) Mono.ink else Mono.white,if (primary) null else Mono.line,primary)
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
            intArrayOf(if (primary) 0xFFB5B5B5.toInt() else Mono.muted,if (primary) Mono.white else Mono.ink)
        ))
        setPadding(dp(16),dp(8),dp(16),dp(8))
        stateListAnimator = null
    }

    private fun adminBack(text: String, parent: () -> Unit) {
        adminBack = parent
        button(text) { guarded(parent) }
    }

    private fun label(text: String, size: Int = 16) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(Mono.muted)
        setLineSpacing(dp(3).toFloat(),1f)
        setPadding(0,dp(6),0,dp(8))
        content.addView(this)
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        styleButton(this,primary)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) }
        setOnClickListener { if(!busy) action() }
        content.addView(this)
    }
    private fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT, limit: Int = 80) = EditText(this).apply {
        this.hint = hint
        inputType = type
        filters = arrayOf(InputFilter.LengthFilter(limit))
        setSingleLine()
        minHeight = dp(56)
        setTextColor(Mono.ink)
        setHintTextColor(Mono.muted)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),rounded(Mono.white,14,Mono.ink))
            addState(intArrayOf(),rounded(Mono.white,14,Mono.line))
        }
        setPadding(dp(16),dp(10),dp(16),dp(10))
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) }
        content.addView(this)
    }
    private fun <T> task(work: () -> T, done: (T) -> Unit) {
        busy = true
        worker.execute {
            val result = runCatching(work)
            runOnUiThread {
                busy = false
                if(!isDestroyed && !isFinishing) result.fold(done) { toast("Could not finish that action. Please try again.") }
            }
        }
    }
    private fun home() {
        activeSession = 0L
        retakeSlot = 0
        admin = false
        if (supportSession != 0L) {
            val session = supportSession
            supportSession = 0L
            showSession(session)
            return
        }
        if (pendingOrder != 0L) {
            val order = store.order(pendingOrder)
            if (order != null) {
                if (cashCodeStep) paymentScreen(order) else cashCheckout(order)
                return
            }
            pendingOrder = 0L
        }
        welcomePage()
    }

    private fun welcomePage() {
        designClock.removeCallbacksAndMessages(null)
        screenVersion++
        eventIdle = true
        adminBack = null
        val hasCover = welcomeCover().baseFile.exists()
        val root = FrameLayout(this).apply { setBackgroundColor(if (hasCover) Mono.ink else Mono.paper) }
        val controls = FrameLayout(this)
        if (hasCover) {
            val photo = WelcomeCoverView(this,store.welcomeFraming())
            root.addView(photo,FrameLayout.LayoutParams(-1,-1))
            root.addView(View(this).apply { setBackgroundColor(0x66000000) },FrameLayout.LayoutParams(-1,-1))
            task({ readWelcomeCover() }) { photo.image = it }
        }
        val heading = TextView(this).apply {
            text = store.welcomeHeadline
            gravity = Gravity.CENTER
            textSize = 34f
            maxLines = 4
            setAutoSizeTextTypeUniformWithConfiguration(18,34,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (hasCover) Mono.white else Mono.ink)
            ViewCompat.setAccessibilityHeading(this,true)
        }
        controls.addView(heading,FrameLayout.LayoutParams(-1,dp(104),Gravity.TOP).apply { setMargins(dp(44),dp(8),dp(44),0) })
        val start = Button(this).apply {
            text = "Start"
            styleButton(this,!hasCover)
            setOnClickListener { if (!busy) chooseLayout() }
        }
        controls.addView(start,FrameLayout.LayoutParams(dp(176),-2,Gravity.CENTER))
        val small = TextView(this).apply {
            text = "⋯"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(if (hasCover) Mono.white else Mono.muted)
            contentDescription = if (store.sessionMode) "End event" else "Booth options"
            isFocusable = true
            setOnClickListener {
                if (!busy) {
                    if (store.sessionMode) requestSessionModeExit() else AlertDialog.Builder(this@MainActivity)
                        .setItems(arrayOf(if(store.hasAdmin) "Admin login" else "Set up admin","Resume with a photo code")) { _, item ->
                            if (item == 0) login() else {
                                page("Enter your photo code")
                                photoCodeEntry()
                                button("Back") { home() }
                            }
                        }.show()
                }
            }
        }
        controls.addView(small,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM or Gravity.END))
        root.addView(controls,FrameLayout.LayoutParams(-1,-1))
        ViewCompat.setOnApplyWindowInsetsListener(controls) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun welcomeCover() = android.util.AtomicFile(File(filesDir,"welcome-cover.png"))

    private fun readWelcomeCover(): Bitmap? = runCatching {
        welcomeCover().openRead().use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun adjustWelcomeCover(): Unit = guarded {
        page("Adjust cover photo")
        label("Drag the photo to move it. Use the slider to zoom.")
        val w = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val h = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
        val scale = minOf(dp(270)/w,dp(390)/h)
        val preview = WelcomeCoverView(this,store.welcomeFraming(),editable = true).apply {
            layoutParams = LinearLayout.LayoutParams((w*scale).toInt(),(h*scale).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL }
            contentDescription = "Welcome photo positioning preview"
            setBackgroundColor(Mono.ink)
        }
        content.addView(preview)
        val zoom = SeekBar(this).apply {
            max = 200
            progress = ((preview.framing.zoom-1f)*100).toInt()
            contentDescription = "Cover zoom"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { preview.framing = preview.framing.copy(zoom = 1f+progress/100f); preview.invalidate() }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        content.addView(zoom)
        button("Reset position") {
            preview.framing = CoverFraming(1f,0f,0f)
            zoom.progress = 0
            preview.invalidate()
        }
        button("Save framing", primary = true) { guarded {
            if (store.saveWelcomeFraming(preview.framing)) { toast("Cover framing saved."); welcomeSettings() }
            else toast("Could not save cover framing.")
        } }
        adminBack("Cancel") { welcomeSettings() }
        task({ readWelcomeCover() }) { preview.image = it }
    }

    private fun photoCodeEntry(order: SaleOrder? = null) {
        val codeInput = input("5-digit photo code",InputType.TYPE_CLASS_NUMBER,5)
        val message = label("")
        button("Start photos", primary = true) {
            if(store.cooldown("ticket") > 0) { message.text = "Too many attempts. Please wait and try again."; return@button }
            val code = codeInput.text.toString()
            if(code.length != 5 || code.any { it !in '0'..'9' }) { message.text = "Enter all five digits."; return@button }
            task({
                store.redeem(code,order?.id).also { store.attempt("ticket",it != null) }
            }) {
                if(it == null) message.text = if (order == null) "That code can’t resume a session." else "That code is invalid or has already been used. Ask the attendant for help."
                else {
                    pendingOrder = 0L
                    androidx.core.view.WindowInsetsControllerCompat(window,codeInput).hide(WindowInsetsCompat.Type.ime())
                    showSession(it.id)
                }
            }
        }
    }

    private fun layoutGrid(price: (BoothLayout) -> String?, select: (BoothLayout) -> Unit): Map<String, View> {
        val cards = mutableMapOf<String,View>()
        (PhotoLayouts.catalog + store.customLayouts()).chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            content.addView(row)
            pair.forEach { layout ->
                val detail = price(layout)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(4),dp(3),dp(4)) }
                    setPadding(dp(4),dp(6),dp(4),dp(8))
                    background = tile(Mono.white,Mono.line)
                    addView(LayoutThumbnail(this@MainActivity,layout).apply {
                        layoutParams = LinearLayout.LayoutParams(-1,dp(104))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(TextView(this@MainActivity).apply {
                        gravity = Gravity.CENTER
                        text = if (layout.key.startsWith("custom-")) "Custom layout" else "Layout ${layout.key}"
                        setTextColor(Mono.ink)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        textSize = 16f
                    })
                    addView(TextView(this@MainActivity).apply {
                        gravity = Gravity.CENTER
                        text = "${layout.label}\n${detail ?: "Unavailable"}"
                        setTextColor(Mono.muted)
                        textSize = 13f
                        setPadding(dp(4),dp(3),dp(4),dp(6))
                    })
                    if (detail != null) {
                        isFocusable = true
                        setOnClickListener { if (!busy) select(layout) }
                    } else alpha = .55f
                }
                cards[layout.key] = card
                row.addView(card)
            }
        }
        return cards
    }

    private fun chooseLayout(initialCopies: Int = 1, initialLayout: BoothLayout? = null) {
        page("Choose your layout", step = "01 / 04   ·   LAYOUT & COPIES")
        val prices = store.prices()
        var layout = initialLayout?.takeIf { it.poses in prices }
        val copyLimit = store.copyLimit
        var copies = initialCopies.coerceIn(1,copyLimit)
        if (prices.isEmpty()) label("Pricing is not set yet. Ask the attendant.")
        else label("Pick a layout, then choose how many copies to print.")
        var update = {}
        val cards = layoutGrid({ option -> prices[option.poses]?.let { "${OrderPricing.peso(it)} first copy" } }) { option ->
            layout = option
            update()
        }

        section("COPIES")
        store.extraCopyCents?.let { label("Additional copies: ${OrderPricing.peso(it)} each.") }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) }
        }
        fun counterButton(symbol: String, description: String, change: Int) = Button(this).apply {
            text = symbol
            contentDescription = description
            styleButton(this,false)
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(dp(60),-2)
            setOnClickListener {
                if (!busy) {
                    copies = (copies + change).coerceIn(1,copyLimit)
                    update()
                }
            }
        }
        val minus = counterButton("−","Fewer copies",-1)
        val count = TextView(this).apply {
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Mono.ink)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = LinearLayout.LayoutParams(0,-2,1f)
        }
        val plus = counterButton("+","More copies",1)
        row.addView(minus)
        row.addView(count)
        row.addView(plus)
        content.addView(row)

        val total = label("",23).apply {
            setTextColor(Mono.ink)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val pay = button("Continue to payment", primary = true) {
            layout?.let { option -> prices[option.poses]?.let { reviewOrder(option,copies,it) } }
        }
        update = {
            cards.forEach { (key,card) ->
                val active = key == layout?.key
                card.isSelected = active
                card.background = tile(if (active) 0xFFE7E7E4.toInt() else Mono.white,
                    if (active) Mono.ink else Mono.line)
            }
            count.text = if (copies == 1) "1 copy" else "$copies copies"
            minus.isEnabled = copies > 1
            plus.isEnabled = copies < copyLimit
            val price = layout?.let { prices[it.poses] }
            total.text = price?.let { "Total: ${OrderPricing.peso(OrderPricing.total(it,copies,store.extraCopyCents))}" } ?: "Select a layout"
            pay.isEnabled = price != null
        }
        update()
        button("Back") { home() }
    }

    private fun reviewOrder(layout: BoothLayout, copies: Int, unit: Long) {
        val extra = store.extraCopyCents
        page("Preparing your payment slip…", step = "02 / 04   ·   PAYMENT")
        task({ runCatching { store.createOrder(layout,copies,unit,extra) } }) { result ->
            val order = result.getOrNull()
            if (order == null) {
                toast(if (result.isFailure) "Could not prepare the order. Try again." else "Prices changed. Check your total and try again.")
                chooseLayout(copies,layout)
            } else {
                pendingOrder = order.id
                cashCodeStep = false
                printCashSlip(order)
            }
        }
    }

    private fun printCashSlip(order: SaleOrder) {
        val profile = thermal.profile()
        if (profile == null) {
            slipStatus = SlipStatus.NO_PRINTER
            cashCheckout(order)
            return
        }
        slipStatus = SlipStatus.PRINTING
        cashCheckout(order)
        task({
            val packets = runCatching { cashSlipRaster(profile,order) }.getOrNull()
            if (packets == null) SlipStatus.FAILED else when (runCatching { thermal.send(profile,packets) }.getOrNull()?.status) {
                "TRANSMITTED" -> SlipStatus.SENT
                "FAILED" -> SlipStatus.FAILED
                else -> SlipStatus.UNKNOWN
            }
        }) { status ->
            slipStatus = status
            cashCheckout(order)
        }
    }

    private fun cashSlipRaster(profile: PrinterProfile, order: SaleOrder): List<ByteArray> =
        raster(profile,140,trim = true) { canvas ->
            val dateTime = android.text.format.DateFormat.getDateFormat(this).format(java.util.Date(order.createdAt))
            val clock = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(order.createdAt))
            fun line(text: String, y: Float, size: Float, bold: Boolean = false) {
                val paint = textPaint(size).apply {
                    textAlign = Paint.Align.CENTER
                    if (bold) typeface = Typeface.DEFAULT_BOLD
                    val width = measureText(text)
                    if (width > 203f) textSize *= 203f / width
                }
                canvas.drawText(text,113.5f,y,paint)
            }
            line("PHOTOBOOTH PAYMENT SLIP",17f,12f,true)
            line("AMOUNT DUE",37f,11f)
            line(OrderPricing.peso(order.totalCents),65f,25f,true)
            line("Order #${order.id}   •   $dateTime   $clock",85f,10f)
            line("${order.photos} photo(s)   •   ${order.copies} ${if (order.copies == 1) "copy" else "copies"}",102f,10f)
            line("Pay at the counter. This slip is not proof of payment.",126f,9f,true)
        }

    private fun cashCheckout(order: SaleOrder) {
        page("Pay ${OrderPricing.peso(order.totalCents)}", step = "02 / 04   ·   PAYMENT")
        val layout = store.layout(order.layoutKey)
        label("${layout?.label ?: "${order.photos} photo(s)"} • ${order.copies} ${if (order.copies == 1) "copy" else "copies"}")
        when (slipStatus) {
            SlipStatus.PRINTING -> label("Sending your payment slip…")
            SlipStatus.NO_PRINTER, SlipStatus.FAILED -> label("No slip printed. You can show this screen instead.")
            SlipStatus.UNKNOWN -> label("Check the printer. If there’s no slip, show this screen.")
            SlipStatus.SENT -> Unit
        }
        label(when (slipStatus) {
            SlipStatus.NO_PRINTER, SlipStatus.FAILED -> "1. Show this total to the attendant."
            SlipStatus.UNKNOWN -> "1. Take the slip or show this total."
            else -> "1. Take your payment slip."
        })
        label("2. Pay the attendant and get your 5-digit code.")
        label("3. Tap I paid and enter your code.")
        button("I paid", primary = true) {
            cashCodeStep = true
            paymentScreen(order)
        }.isEnabled = slipStatus != SlipStatus.PRINTING
        if (slipStatus == SlipStatus.FAILED) button("Retry payment slip") { printCashSlip(order) }
        button("Change layout or copies") {
            pendingOrder = 0L
            chooseLayout(order.copies,layout)
        }.isEnabled = slipStatus != SlipStatus.PRINTING
    }

    private fun paymentScreen(order: SaleOrder) {
        page("Enter your photo code", step = "03 / 04   ·   ACCESS CODE")
        val layout = store.layout(order.layoutKey)
        val description = layout?.label ?: "${order.photos}×1 layout"
        label("$description • ${order.copies} ${if(order.copies == 1) "copy" else "copies"} • ${OrderPricing.peso(order.totalCents)}")
        label("Enter the five-digit code given by the attendant.")
        photoCodeEntry(order)
        button("Back to payment") { cashCodeStep = false; cashCheckout(order) }
        if (!store.sessionMode) button("Back to start") { pendingOrder = 0L; home() }
    }

    private fun showSession(id: Long) {
        stopCamera()
        val session = store.session(id) ?: return home()
        activeSession = id
        when(session.state) {
            "CAPTURE" -> openCamera(session)
            "PREVIEW" -> {
                if (retakeSlot in 1..session.photos && store.retakes(id).size < retakeLimit(session)) openCamera(session, retakeSlot)
                else { retakeSlot = 0; editStrip(session) }
            }
            "QUEUED" -> printStatus(session)
            "COMPLETE" -> printDone()
            else -> home()
        }
    }

    private fun openCamera(session: Session, retake: Int = 0) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            page("Camera access needed")
            label("Allow camera access to take your photos. Your ticket is saved.")
            button("Allow camera access") { cameraPermission.launch(android.Manifest.permission.CAMERA) }
            label("If the permission prompt does not open, ask the attendant.")
            return
        }
        supportActionBar?.hide()
        screenVersion++
        eventIdle = false
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        val start = Button(this).apply {
            text = if (retake > 0) "Start retake" else "Start countdown"
            styleButton(this,false)
            isEnabled = false
        }
        root.addView(start, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val timer = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 76f
            setTextColor(Color.WHITE)
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(timer, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB3000000.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = "Opening camera…"
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val flash = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(flash, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.keepScreenOn = true
        setContentView(root)
        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || activeSession != session.id || !root.isAttachedToWindow) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                val front = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                provider.bindToLifecycle(this, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                val retakeOrdinal = if (retake > 0) store.retakes(session.id).size + 1 else 0
                var index = if (retake > 0) retake else session.captured + 1
                var saving = false
                var flashUntil = 0L
                fun ready() {
                    timer.visibility = View.GONE
                    start.visibility = View.VISIBLE
                    start.isEnabled = true
                    status.text = if (retake > 0) "Retake photo $retake" else "Photo $index of ${session.photos} • Photos are stored on this device"
                }
                fun count(seconds: Int, next: () -> Unit) {
                    val deadline = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
                    val tick = object : Runnable {
                        override fun run() {
                            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                            if (remaining <= 0) next() else {
                                timer.textSize = 76f
                                timer.text = ((remaining + 999) / 1000).toString()
                                countdownHandler.postDelayed(this, ((remaining - 1) % 1000) + 1)
                            }
                        }
                    }
                    tick.run()
                }
                fun photoCountdown() {
                    status.text = if (retake > 0) "Get ready • Retake photo $retake" else "Get ready • Photo $index of ${session.photos}"
                    count(10) {
                        val file = if (retake > 0) retakePhoto(session.id, retakeOrdinal) else photo(session.id, index)
                        if ((!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) ||
                            filesDir.usableSpace < 30_000_000L) {
                            ready()
                            status.text = "Not enough space to save a photo. Ask the attendant."
                            return@count
                        }
                        val shot = index
                        val pending = try { File.createTempFile("shot_", ".jpg", file.parentFile) } catch (_: java.io.IOException) {
                            ready()
                            status.text = "Could not open photo storage. Tap to try again."
                            return@count
                        }
                        timer.text = "Capturing…"
                        timer.textSize = 32f
                        flash.animate().cancel()
                        flash.alpha = 1f
                        setCaptureLight(true)
                        flashUntil = android.os.SystemClock.elapsedRealtime() + 2000L
                        root.postDelayed({ flash.alpha = 0f; if (root.isAttachedToWindow) setCaptureLight(false) },2000L)
                        saving = true
                        capture.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = front }
                        val output = ImageCapture.OutputFileOptions.Builder(pending).setMetadata(metadata).build()
                        root.postDelayed({
                            if (!foreground || isDestroyed || !root.isAttachedToWindow) {
                                pending.delete(); flash.alpha = 0f; setCaptureLight(false); saving = false
                                if (!isDestroyed && root.isAttachedToWindow) ready()
                                return@postDelayed
                            }
                            capture.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                if (isDestroyed) { pending.delete(); return }
                                val moved = pending.length() > 0 && pending.renameTo(file)
                                if (!moved) pending.delete()
                                val saved = moved && if (retake > 0) store.recordRetake(session.id, retake, retakeOrdinal)
                                    else { store.recordPhoto(session.id, shot); true }
                                if (moved && !saved) file.delete()
                                if (isDestroyed || activeSession != session.id || !root.isAttachedToWindow) return
                                root.postDelayed({
                                if (isDestroyed || !root.isAttachedToWindow || activeSession != session.id) return@postDelayed
                                flash.alpha = 0f
                                setCaptureLight(false)
                                saving = false
                                if (!saved) {
                                    ready()
                                    status.text = "Photo could not be saved. Tap to try again."
                                } else if (retake > 0 || shot == session.photos) {
                                    retakeSlot = 0
                                    showSession(session.id)
                                } else {
                                    index++
                                    if (foreground && !exitPromptOpen) photoCountdown() else ready()
                                }
                                }, (flashUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                            }
                            override fun onError(error: ImageCaptureException) {
                                pending.delete()
                                if (isDestroyed || activeSession != session.id || !root.isAttachedToWindow) return
                                flash.alpha = 0f
                                setCaptureLight(false)
                                saving = false
                                ready()
                                status.text = "Camera could not take the photo. Tap to try again."
                            }
                            })
                        },400L)
                    }
                }
                resetCountdown = { flash.alpha = 0f; if (!saving) ready() }
                ready()
                start.setOnClickListener {
                    start.visibility = View.GONE
                    timer.visibility = View.VISIBLE
                    status.text = "Get ready"
                    photoCountdown()
                }
            } catch (_: Exception) {
                start.text = "Retry camera"
                start.isEnabled = true
                start.setOnClickListener { showSession(session.id) }
                status.text = "Camera unavailable. Your ticket is saved."
            }
        }, executor)
    }

    private fun retakeLimit(session: Session) = minOf(2, 5 - session.photos)

    private fun templateChoice(session: Session): TemplateChoice {
        val layout = store.layoutForSession(session.layoutKey,session.photos)
        val custom = store.selectedArtwork(session.id)?.takeIf { it.layoutKey == layout.key && usableArtwork(it) && it.slots.size == session.photos }
        return if (custom != null) TemplateChoice(custom.name,custom.ratio,custom.slots,custom)
        else TemplateChoice(store.border(session.id),layout.ratio,layout.slots)
    }

    private fun stripBitmap(session: Session, choice: TemplateChoice = templateChoice(session)): Bitmap {
        val dots = thermal.profile()?.width ?: 576
        val scale = dots / 227f
        val rows = kotlin.math.ceil(designHeight(choice) * scale).toInt()
        return Bitmap.createBitmap(dots,rows,Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.scale(scale,scale)
            drawDesign(canvas,session,choice,dots)
        }
    }

    private fun editStrip(session: Session) {
        page("Choose your design", step = "04 / 04   ·   FINISH")
        val layout = store.layoutForSession(session.layoutKey,session.photos)
        val limit = retakeLimit(session)
        val remaining = limit - store.retakes(session.id).size
        label(if (remaining > 0) "Tap a photo in the preview to retake it. $remaining of $limit retakes left."
            else "No retakes left. Choose a design and print.")
        val deadline = store.designDeadline(session.id,System.currentTimeMillis())
        val timer = label("Design time: 30 s",16).apply {
            setTextColor(Mono.white)
            background = rounded(Mono.ink,12)
            setPadding(dp(14),dp(10),dp(14),dp(10))
        }
        val selected = label("")
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(227),dp(350)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        content.addView(frame)
        val designControls = mutableListOf<View>()
        var rendered: Bitmap? = null
        fun beforeDeadline() = System.currentTimeMillis() < deadline
        fun refresh() {
            val choice = templateChoice(session)
            selected.text = "Design: ${choice.name}"
            task({ stripBitmap(session,choice) }) { image ->
                rendered = image
                frame.removeAllViews()
                val width = minOf(dp(290),dp(600).div(choice.ratio).toInt(),
                    (resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(100)))
                val height = (width * choice.ratio).toInt()
                frame.layoutParams = LinearLayout.LayoutParams(width,height).apply { gravity = Gravity.CENTER_HORIZONTAL }
                frame.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageBitmap(image)
                    contentDescription = "Designed photo print preview"
                },FrameLayout.LayoutParams(-1,-1))
                if (remaining > 0) choice.slots.forEachIndexed { i,slot ->
                    val box = slot.rect(width.toFloat(),height.toFloat())
                    val hit = View(this).apply {
                        contentDescription = "Photo ${i+1}. Tap to retake."
                        isFocusable = true
                        isEnabled = beforeDeadline()
                        val attr = theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                        background = attr.getDrawable(0)
                        attr.recycle()
                        setOnClickListener {
                            if (!busy && beforeDeadline()) {
                                store.pauseDesignTime(session.id,System.currentTimeMillis())
                                retakeSlot = i+1
                                showSession(session.id)
                            }
                        }
                    }
                    frame.addView(hit,FrameLayout.LayoutParams(box.width().toInt(),box.height().toInt()).apply {
                        leftMargin = box.left.toInt(); topMargin = box.top.toInt()
                        gravity = Gravity.TOP or Gravity.LEFT
                    })
                }
            }
        }
        val options = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(options)
        listOf("Classic","Bold","Dashed").forEach { name ->
            val option = Button(this).apply {
                text = name
                styleButton(this,false)
                layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(2),0,dp(2),0) }
                setOnClickListener { if (!busy && beforeDeadline()) {
                    store.selectArtwork(session.id,null)
                    store.setBorder(session.id,name)
                    refresh()
                } }
            }
            designControls.add(option)
            options.addView(option)
        }
        store.artworkFor(layout.key).filter(::usableArtwork).forEach { design ->
            designControls.add(button(design.name) {
                if (beforeDeadline()) { store.selectArtwork(session.id,design.id); refresh() }
            })
        }
        fun mirror(slot: Int) {
            if (beforeDeadline() && !busy) {
                store.transformPhoto(session.id,slot,0)
                refresh()
            }
        }
        designControls.add(button("⇄  Mirror photo") {
            if (beforeDeadline()) {
                if (session.photos == 1) mirror(1)
                else AlertDialog.Builder(this).setTitle("Which photo?")
                    .setItems((1..session.photos).map { "Photo $it" }.toTypedArray()) { _, index -> mirror(index+1) }.show()
            }
        })
        button(if (session.copies == 1) "Print receipt" else "Print ${session.copies} copies", primary = true) { printReceipt(session.id, previewImage = rendered) }
        if (!store.sessionMode) button("Save to Gallery") { offerGallerySave(session) }
        val version = screenVersion
        refresh()
        designClock.post(object : Runnable {
            override fun run() {
                if (isDestroyed || version != screenVersion) return
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                timer.text = if (left == 0L) "Time’s up · tap Print to continue" else "Design time: ${(left+999L)/1000L} s"
                if (left == 0L) {
                    designControls.forEach { it.isEnabled = false }
                    for (i in 1 until frame.childCount) frame.getChildAt(i).isEnabled = false
                } else designClock.postDelayed(this,minOf(250L,left.coerceAtLeast(100L)))
            }
        })
    }

    private fun offerGallerySave(session: Session, adminOnly: Boolean = false) {
        AlertDialog.Builder(this).setTitle("Save to this device’s Gallery?")
            .setMessage("This saves the selected photos and the finished strip to Pictures / Daily Sweet Doze. People who use this device’s Gallery may see those copies.")
            .setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ ->
                val save = {
                    task({
                        val files = selectedPhotos(session)
                        check(files.all { it.length() > 0L })
                        val strip = stripBitmap(session)
                        try {
                            GalleryExport.save(contentResolver, session.id, files, strip)
                        } finally { strip.recycle() }
                    }) { count -> toast("Saved $count images to Gallery → Daily Sweet Doze.") }
                }
                if (adminOnly) guarded(save) else save()
            }.show()
    }

    private fun bitmap(file: File): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val scale = 2048f / maxOf(info.size.width,info.size.height)
            if(scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),(info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) { null }
    private fun adminActive(): Boolean {
        if(store.sessionMode || !admin || android.os.SystemClock.elapsedRealtime() - lastAdminUse > 300_000) {
            admin = false
            return false
        }
        return true
    }
    private fun guarded(action: () -> Unit) {
        if(!adminActive()) { toast("Please log in again."); login(); return }
        lastAdminUse = android.os.SystemClock.elapsedRealtime()
        action()
    }
    private fun requestSessionModeExit() {
        if (!store.sessionMode || !eventIdle || exitPromptOpen) return
        if (busy) { toast("Please wait for the current action to finish."); return }
        countdownHandler.removeCallbacksAndMessages(null)
        resetCountdown?.invoke()
        exitPromptOpen = true
        val password = EditText(this).apply {
            hint = "Admin password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(128))
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24),dp(8),dp(24),0)
            addView(password)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("End photobooth event")
            .setMessage("Enter the admin password to end the event and open Admin.")
            .setView(container)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Exit",null)
            .create()
        dialog.setOnDismissListener {
            exitPromptOpen = false
            password.text.clear()
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (busy) return@setOnClickListener
            val value = password.text.toString()
            if (value.isBlank()) { password.error = "Enter your password."; return@setOnClickListener }
            if (store.cooldown("admin") > 0) {
                password.error = "Too many attempts. Wait before trying again."
                return@setOnClickListener
            }
            dialog.setCancelable(false)
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val exit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            cancel.isEnabled = false
            exit.isEnabled = false
            task({
                runCatching {
                    val valid = store.authenticate(value)
                    store.attempt("admin",valid)
                    valid && store.setSessionMode(false)
                }
            }) { result ->
                if (result.getOrNull() != true) {
                    password.text.clear()
                    password.error = if (result.isFailure) "Could not check the password." else "Could not exit. Check the admin password and try again."
                    dialog.setCancelable(true)
                    cancel.isEnabled = true
                    exit.isEnabled = true
                    return@task
                }
                if (activeSession != 0L && store.session(activeSession)?.state != "COMPLETE") supportSession = activeSession
                activeSession = 0L
                retakeSlot = 0
                stopCamera()
                dialog.dismiss()
                val manager = getSystemService(android.app.ActivityManager::class.java)
                if (manager.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) runCatching { stopLockTask() }
                applyEventFullscreen()
                admin = true
                lastAdminUse = android.os.SystemClock.elapsedRealtime()
                dashboard()
            }
        }
    }

    private fun login() {
        if (store.sessionMode) { requestSessionModeExit(); return }
        val setup = !store.hasAdmin
        page(if(setup) "First-time admin setup" else "Admin login")
        if(setup) label("The booth owner should set a password before customer use. Use at least 10 characters.")
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val password = input("Password",type,128)
        val confirm = if(setup) input("Confirm password",type,128) else null
        button(if(setup) "Create admin" else "Log in", primary = true) {
            val value = password.text.toString()
            if(setup && (value.length < 10 || value != confirm?.text.toString())) { toast("Use matching passwords with at least 10 characters."); return@button }
            if(store.cooldown("admin") > 0) { toast("Too many attempts. Wait before trying again."); return@button }
            task({
                if(setup) { store.createAdmin(value); true }
                else store.authenticate(value).also { store.attempt("admin",it) }
            }) { ok ->
                password.text.clear()
                if(ok) { admin = true; lastAdminUse = android.os.SystemClock.elapsedRealtime(); dashboard() }
                else toast("Password not accepted.")
            }
        }
        button("Back") { home() }
    }
    private fun adminTile(title: String, icon: Int, primary: Boolean = false, action: () -> Unit) =
        button(title,primary,action).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon,0,0,0)
            compoundDrawablePadding = dp(14)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(if (primary) Mono.white else Mono.ink)
        }

    private fun dashboard(): Unit = guarded {
        page("Admin")
        label("Set up your booth, then start the event to switch to customer mode.")
        adminTile("Start Photobooth Event",R.drawable.admin_event, primary = true) {
            guarded {
                if (store.prices().isEmpty()) { toast("Set layout prices before starting an event."); return@guarded }
                supportSession = 0L
                pendingOrder = 0L
                if (store.setSessionMode(true)) { home(); startEventLock() } else toast("Could not start the event.")
            }
        }
        section("SETUP")
        adminTile("General settings",R.drawable.admin_tune) { generalSettings() }
        adminTile("Welcome screen",R.drawable.admin_cover) { welcomeSettings() }
        adminTile("Layout prices",R.drawable.admin_prices) { layoutPrices() }
        adminTile("Photo templates",R.drawable.admin_layouts) { photoTemplates() }
        adminTile("Printer setup",R.drawable.admin_printer) { printer() }
        section("DAILY OPERATIONS")
        adminTile("Tickets",R.drawable.admin_ticket) { ticketTools() }
        adminTile("Photos & receipts",R.drawable.admin_gallery) { photoLibrary() }
        adminTile("Cash report",R.drawable.admin_report) { cashPage() }
        section("ACCOUNT")
        adminTile("Log out",R.drawable.admin_exit) { home() }
    }

    private fun generalSettings(): Unit = guarded {
        page("General settings")
        label("Maximum copies per order")
        val copies = input("1–${OrderPricing.MAX_COPIES} copies",InputType.TYPE_CLASS_NUMBER,1).apply { setText(store.copyLimit.toString()) }
        label("Price per extra copy (₱). Leave blank to use the layout’s regular price.")
        val extra = input("Extra copy price",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,8).apply { setText(store.extraCopyCents?.let(OrderPricing::amount).orEmpty()) }
        val fullscreen = CheckBox(this).apply {
            text = "Full-screen event and Android screen pinning"
            isChecked = store.fullscreenEvent
        }
        content.addView(fullscreen)
        label("When starting an event, accept Android’s pinning prompt if shown. To exit, tap ⋯ on the Start screen and enter the admin password.")
        button("Save settings", primary = true) { guarded {
            val value = copies.text.toString().toIntOrNull()
            if (value == null || value !in 1..OrderPricing.MAX_COPIES) copies.error = "Enter 1 to ${OrderPricing.MAX_COPIES}."
            else if (extra.text.isNotBlank() && OrderPricing.parse(extra.text.toString()) == null) extra.error = "Enter a positive amount or leave blank."
            else if (store.saveGeneralSettings(value,fullscreen.isChecked,OrderPricing.parse(extra.text.toString()))) { toast("Settings saved."); dashboard() }
            else toast("Could not save settings.")
        } }
        adminBack("Back") { dashboard() }
    }

    private fun welcomeSettings(): Unit = guarded {
        page("Welcome screen")
        label("Change the headline and cover customers see before starting a photo session.")
        val headline = input("Welcome headline",limit = 100).apply {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 4
            setText(store.welcomeHeadline)
        }
        fun saveHeadline(): Boolean {
            val value = headline.text.toString().trim()
            if (value.isEmpty()) { headline.error = "Enter a welcome headline."; return false }
            if (!store.setWelcomeHeadline(value)) { toast("Could not save the headline."); return false }
            return true
        }
        button("Save headline", primary = true) { guarded { if (saveHeadline()) toast("Welcome headline saved.") } }
        if (welcomeCover().baseFile.exists()) button("Move or zoom cover photo") { guarded { if (saveHeadline()) adjustWelcomeCover() } }
        else label("No cover photo selected.")
        button("Choose cover photo") { guarded {
            if (saveHeadline()) pickWelcomeCover.launch(arrayOf("image/jpeg","image/png","image/webp"))
        } }
        if (welcomeCover().baseFile.exists()) button("Remove cover photo") { guarded {
            if (saveHeadline()) {
                welcomeCover().delete()
                welcomeSettings()
            }
        } }
        adminBack("Back to admin") { dashboard() }
    }

    private fun section(title: String) {
        label(title,12).apply {
            setPadding(0,dp(22),0,dp(2))
            letterSpacing = .14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private fun layoutPrices(): Unit = guarded {
        page("Layout prices")
        label("Set a price per printed copy by pose count. Leave the four-pose price blank to keep those layouts unavailable.")
        val current = store.prices()
        val inputs = (1..4).associateWith { photos ->
            input("$photos pose(s) per copy",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,10).apply {
                current[photos]?.let { setText(OrderPricing.amount(it)) }
            }
        }
        button("Save prices") { guarded {
            val included = inputs.filter { (photos,field) -> photos != 4 || field.text.isNotBlank() }
            val parsed = included.mapValues { OrderPricing.parse(it.value.text.toString()) }
            if (parsed.values.any { it == null }) toast("Enter a valid positive price, such as 50 or 50.00.")
            else task({ store.savePrices(parsed.mapValues { requireNotNull(it.value) }) }) {
                toast("Prices saved.")
                dashboard()
            }
        } }
        adminBack("Back to admin") { dashboard() }
    }

    private fun usableArtwork(art: ArtworkTemplate) =
        art.ratio in 1.01f..3.8f && art.slots.all { it.valid() && (store.layout(art.layoutKey)?.centeredColumn == false || it.isCentered()) }

    private fun artworkFile(name: String) = File(File(filesDir,"template-artwork"),name)

    private fun photoTemplates(): Unit = guarded {
        page("Photo templates")
        label("Choose a layout to manage designs, or build your own receipt layout.")
        button("Create custom layout", primary = true) { editCustomLayout() }
        layoutGrid({ "Manage designs" }) { layout -> templateLibrary(layout) }
        adminBack("Back to admin") { dashboard() }
    }

    private fun templateLibrary(layout: BoothLayout): Unit = guarded {
        page("${layout.label} templates")
        label("${layout.poses} photo space(s)")
        button("Download PNG design guide") { guarded {
            guideLayout = layout
            saveLayoutGuide.launch("DSD-${layout.key}-design-guide.png")
        } }
        if (layout.key.startsWith("custom-")) {
            button("Edit receipt layout") { editCustomLayout(layout) }
            button("Remove layout") { guarded {
                AlertDialog.Builder(this).setTitle("Remove ${layout.label}?")
                    .setMessage("It will disappear from new orders. Existing sessions keep their original layout.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Remove") { _,_ -> guarded {
                        task({ store.archiveCustomLayout(layout.key) }) { photoTemplates() }
                    } }.show()
            } }
        }
        label("Artwork: ${layout.artworkSize}, or the same proportions. Use the numbered guide to place the photos; hide the guide layer before exporting your artwork.")
        val designs = store.artworkFor(layout.key)
        if (designs.isEmpty()) label("No custom designs yet.")
        else designs.forEach { design -> button(design.name) { manageArtwork(layout,design) } }
        button("Import portrait PNG or JPG artwork") { guarded {
            importLayout = layout
            replacingArtwork = null
            importArtwork.launch(arrayOf("image/png","image/jpeg"))
        } }
        adminBack("Back to layouts") { photoTemplates() }
    }

    private fun editCustomLayout(existing: BoothLayout? = null): Unit = guarded {
        page(if (existing == null) "Create receipt layout" else "Edit receipt layout")
        label("Add up to four photo tiles. Drag a tile to move it, or drag its bottom-right handle to resize it.")
        val name = input("Layout name",limit = 48).apply { setText(existing?.label.orEmpty()) }
        val editor = ReceiptTileEditor(this,existing).apply {
            layoutParams = LinearLayout.LayoutParams(-1,dp(430))
            setBackgroundColor(0xffe7e7e7.toInt())
            contentDescription = "Receipt preview with movable photo tiles"
        }
        val size = label("")
        fun updateSize() { size.text = "Guide: 720 × ${(720*editor.ratio).toInt()} px · ${editor.slots.size} photo tile(s)" }
        content.addView(SeekBar(this).apply {
            max = 270
            progress = ((editor.ratio-1.1f)*100f).toInt()
            contentDescription = "Portrait receipt length"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        editor.ratio = (1.1f+progress/100f).coerceAtMost(3.8f)
                        editor.invalidate()
                        updateSize()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        content.addView(editor)
        updateSize()
        button("Add photo tile") {
            if (editor.addTile()) updateSize() else toast("Up to four tiles. Resize or move an existing tile to make room.")
        }
        button("Remove selected tile") {
            if (editor.removeTile()) updateSize() else toast("Keep at least one tile.")
        }
        if (existing != null) label("Layout changes apply to new orders. Add artwork for the new tile positions after saving.")
        button("Save layout", primary = true) { guarded {
            val title = name.text.toString().trim()
            if (title.isBlank()) { name.error = "Enter a layout name."; return@guarded }
            val slots = editor.slots.toList()
            val ratio = editor.ratio
            task({ store.saveCustomLayout(title,ratio,slots,existing?.key) }) { templateLibrary(it) }
        } }
        adminBack("Cancel") { if (existing == null) photoTemplates() else templateLibrary(existing) }
    }

    private fun writeLayoutGuide(layout: BoothLayout, uri: android.net.Uri) {
        val width = 720
        val height = kotlin.math.round(720 * layout.ratio).toInt()
        val image = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.TRANSPARENT)
            layout.slots.forEachIndexed { i, slot ->
                val rect = slot.rect(width.toFloat(),height.toFloat())
                canvas.drawRect(rect,Paint().apply { color = 0xffe8e8e8.toInt() })
                canvas.drawRect(rect,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
                })
                canvas.drawText("PHOTO ${i+1}",rect.centerX(),rect.centerY(),textPaint(minOf(26f,rect.width()/6f)).apply {
                    textAlign = Paint.Align.CENTER
                })
            }
            checkNotNull(contentResolver.openOutputStream(uri,"wt")).use {
                check(image.compress(Bitmap.CompressFormat.PNG,100,it))
            }
        } finally { image.recycle() }
    }

    private fun manageArtwork(layout: BoothLayout, design: ArtworkTemplate): Unit = guarded {
        page(design.name)
        label("Layout ${layout.key} • Artwork: ${layout.artworkSize} or the same proportions")
        if (layout.acceptsRatio(design.ratio)) button("Edit name or photo spaces") { guarded {
            task({ checkNotNull(BitmapFactory.decodeFile(artworkFile(design.fileName).absolutePath)) }) { image ->
                markArtwork(layout,image,design,true)
            }
        } } else label("Replace the artwork to match this layout’s size before editing its spaces.")
        button("Replace PNG or JPG") { guarded {
            importLayout = layout
            replacingArtwork = design
            importArtwork.launch(arrayOf("image/png","image/jpeg"))
        } }
        button("Remove design") { guarded {
            AlertDialog.Builder(this).setTitle("Remove ${design.name}?")
                .setMessage("Customers won’t see this design in new sessions. Previous receipts will keep it.")
                .setNegativeButton("Cancel",null).setPositiveButton("Remove") { _,_ -> guarded {
                    task({ store.archiveArtwork(layout,design.id) }) { templateLibrary(layout) }
                } }.show()
        } }
        adminBack("Back to designs") { templateLibrary(layout) }
    }

    private fun markArtwork(layout: BoothLayout,image: Bitmap, existing: ArtworkTemplate?, keepSlots: Boolean): Unit = guarded {
        page("Mark photo spaces")
        label(if (layout.centeredColumn) "Drag over each blank space from top to bottom. The spaces snap to the center of the receipt."
            else "Photo spaces start at the layout’s tile positions. To change them for this design, clear the guides and mark them in capture order.")
        val name = input("Design name",limit = 48).apply { setText(existing?.name.orEmpty()) }
        val status = label("0 of ${layout.poses} spaces marked")
        val ratio = image.height.toFloat()/image.width
        val editor = TemplateSlotEditor(this,image,layout.poses,layout.centeredColumn) { count ->
            status.text = "$count of ${layout.poses} spaces marked"
        }.apply {
            val available = (resources.displayMetrics.widthPixels-dp(48)).coerceAtLeast(dp(100))
            val width = minOf(available,dp(420),dp(640).div(ratio).toInt())
            layoutParams = LinearLayout.LayoutParams(width,(width*ratio).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL }
            contentDescription = "Template artwork. Drag over each photo space in order."
            slots.addAll(if (keepSlots && existing != null) existing.slots else layout.slots)
        }
        content.addView(editor)
        status.text = "${editor.slots.size} of ${layout.poses} spaces marked"
        button("Undo last space") { editor.undo() }
        button("Clear photo spaces") { editor.slots.clear(); editor.invalidate(); status.text = "0 of ${layout.poses} spaces marked" }
        button("Save template") { guarded {
            val title = name.text.toString().trim()
            val slots = editor.slots.toList()
            if (title.isBlank() || slots.size != layout.poses) {
                toast("Enter a name and mark all ${layout.poses} spaces.")
                return@guarded
            }
            task({
                val fileName = java.util.UUID.randomUUID().toString()+".png"
                val file = artworkFile(fileName)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                try {
                    file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    store.addArtwork(layout,title,fileName,ratio,slots,existing?.id)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }) { toast("Template saved."); templateLibrary(layout) }
        } }
        adminBack("Cancel") { templateLibrary(layout) }
    }

    private fun ticketTools(): Unit = guarded {
        page("Tickets")
        button("Generate ticket batch") { generate() }
        button("View or export batches") { batches() }
        adminBack("Back to admin") { dashboard() }
    }

    private fun cashPage(): Unit = guarded {
        page("Cash report")
        label(store.report())
        label("Cash recorded by amount\n" + store.cashReport().ifBlank { "No payments recorded yet." })
        adminBack("Back to admin") { dashboard() }
    }

    private fun photoLibrary(): Unit = guarded {
        page("Photos & receipts")
        button("Print history and recovery") { queue() }
        label("Recent photo sessions")
        val sessions = store.photoSessions()
        if (sessions.isEmpty()) label("No finished photo strips yet.")
        sessions.forEach { session ->
            button("Session #${session.id} · ${session.photos} photos") { storedPhotos(session.id) }
        }
        adminBack("Back to admin") { dashboard() }
    }

    private fun storedPhotos(id: Long): Unit = guarded {
        val session = store.session(id) ?: return@guarded photoLibrary()
        page("Session #$id photos")
        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(420))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Saved photo strip"
        }
        if (selectedPhotos(session).all { it.length() > 0L }) {
            content.addView(preview)
            button("Save to Gallery") { guarded { offerGallerySave(session, adminOnly = true) } }
            task({ stripBitmap(session) }) { preview.setImageBitmap(it) }
        } else label("These photos are no longer in the app’s storage.")
        adminBack("Back to photos") { photoLibrary() }
    }

    private fun generate(): Unit = guarded {
        page("Generate photo codes")
        val count = input("Number of codes (1–100)",InputType.TYPE_CLASS_NUMBER,3).apply { setText("25") }
        label("Each code unlocks one session with any layout and copy count.")
        button("Generate and save") { guarded {
            val quantity = count.text.toString().toIntOrNull()
            if (quantity == null || quantity !in 1..100) toast("Enter a quantity from 1 to 100.")
            else task({ store.generate(quantity) }) { batch(it) }
        } }
        adminBack("Back to tickets") { ticketTools() }
    }

    private fun batches(): Unit = guarded {
        page("Ticket batches")
        val items = store.batches()
        if(items.isEmpty()) label("Generate a batch to see it here.")
        items.forEach { item -> button("Batch ${item.id} · ${item.count} codes") { batch(item.id) } }
        adminBack("Back to tickets") { ticketTools() }
    }
    private fun batch(id: Long): Unit = guarded {
        page("Ticket batch #$id")
        label("Keep unused codes with the attendant. Hand out one code after collecting the amount shown.")
        button("Export A4 ticket PDF") { guarded { createPdf("batch",id,"DSD-tickets-$id.pdf") } }
        store.tickets(id).forEach { ticket ->
            button("${ticket.code} · ${ticket.state.lowercase()}") { ticket(ticket) }
        }
        adminBack("Back") { batches() }
    }
    private fun ticket(ticket: Ticket): Unit = guarded {
        page("Ticket ${ticket.code}")
        label("Batch #${ticket.batch} · ${ticket.state.lowercase()}")
        if(ticket.state == "AVAILABLE") button("Void unused code") { guarded {
            AlertDialog.Builder(this).setMessage("Void this code? It will no longer work.")
                .setNegativeButton("Keep code",null).setPositiveButton("Void") { _,_ -> guarded {
                    store.void(ticket.id); batch(ticket.batch)
                } }.show()
        } }
        adminBack("Back") { batch(ticket.batch) }
    }

    private fun printDone() {
        page("Receipt sent")
        label("Check the printer and take your receipt.")
        if (!store.sessionMode) button("Done", primary = true) { home() }
        else {
            val displayed = screenVersion
            label("Ready for the next customer shortly.")
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (isDestroyed || displayed != screenVersion || !store.sessionMode) return
                    if (exitPromptOpen || busy) handler.postDelayed(this,1000L) else home()
                }
            },6000L)
        }
    }
    private fun printStatus(session: Session, message: String? = null) {
        val status = store.printState(session.id)
        if (status == "TRANSMITTED" || status == "CONFIRMED") { printDone(); return }
        page(if (status == "SENDING") "Sending receipt…" else "Receipt needs attention")
        label(message ?: when(status) {
            "SENDING" -> "Please wait while the receipt is sent."
            "UNKNOWN" -> "Check the printer. The receipt may already have printed; ask the attendant before trying again."
            else -> "Make sure the printer is on and connected, or ask the attendant for help."
        })
        label("Session #${session.id}")
        val canRetry = status in listOf(null,"QUEUED","FAILED")
        if (canRetry) button("Retry printing", primary = true) { printReceipt(session.id) }
        if (status == "SENDING") button("Check print status") { showSession(session.id) }
        else button("End without printing") { askEndWithoutPrint(session, canRetry) }
        if (!store.sessionMode) button("Attendant login") {
            supportSession = session.id
            login()
        }
    }

    private fun askEndWithoutPrint(session: Session, canRetry: Boolean) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("End this photo session?")
            .setMessage(if (canRetry) "Return to Start without printing? Your photos stay on the booth for the attendant."
                else "The printer may already have received the receipt. Return to Start and leave the photos for the attendant?")
            .setNeutralButton("Cancel",null)
            .setPositiveButton("End session") { _,_ ->
                task({ store.endWithoutPrint(session.id) }) { ended ->
                    if (ended) { pendingOrder = 0L; home() }
                    else printStatus(session,"The print status changed. Please check the printer.")
                }
            }
        if (canRetry) dialog.setNegativeButton("Retry printing") { _,_ -> printReceipt(session.id) }
        dialog.show()
    }

    private fun printReceipt(id: Long, reprint: Boolean = false, previewImage: Bitmap? = null) {
        val session = store.session(id) ?: return
        val profile = thermal.profile()
        if (profile == null) {
            if (reprint) toast("Select a printer in Printer setup first.")
            else printStatus(session, "No printer is configured. Ask the attendant to set it up.")
            return
        }
        if (!store.beginPrint(id,reprint)) {
            if (reprint) toast("This receipt cannot be sent again right now.") else showSession(id)
            return
        }
        val copies = if (reprint) 1 else session.copies
        if (!reprint) showPrinting(previewImage, copies)
        val visibleUntil = android.os.SystemClock.elapsedRealtime() + if (reprint) 0L else 1400L
        task({
            val result = try {
                if (selectedPhotos(session).any { it.length() == 0L }) {
                    PrintResult("FAILED","One or more photos are no longer available. Ask the attendant.")
                } else {
                    val design = templateChoice(session)
                    val packets = raster(profile, designHeight(design), trim = true) { canvas -> drawDesign(canvas,session,design,profile.width) }
                    thermal.send(profile, packets, copies)
                }
            } catch (_: Exception) {
                PrintResult("FAILED","Could not prepare the receipt. Please ask the attendant.")
            }
            store.finishPrint(id,result.status)
            val animationTime = visibleUntil - android.os.SystemClock.elapsedRealtime()
            if (animationTime > 0) android.os.SystemClock.sleep(animationTime)
            result
        }) { result ->
            if (reprint) { toast(result.message); queue() }
            else if (result.status == "TRANSMITTED") printDone()
            else printStatus(session,result.message)
        }
    }

    private fun showPrinting(strip: Bitmap?, copies: Int) {
        page("Printing your receipt…")
        label(if (copies == 1) "Sending your photo strip to the printer." else "Sending $copies copies of your photo strip.")
        content.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        })
        if (strip != null) {
            val tray = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(370))
                clipChildren = true
            }
            val paper = ImageView(this).apply {
                setImageBitmap(strip)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Your printed photo strip"
                translationY = -dp(370).toFloat()
            }
            tray.addView(paper, FrameLayout.LayoutParams(-1,-1))
            content.addView(tray)
            paper.animate().translationY(0f).setDuration(1250L).start()
        }
    }

    private fun raster(profile: PrinterProfile, height: Int, trim: Boolean = false, draw: (Canvas) -> Unit): List<ByteArray> {
        val scale = profile.width / 227f
        val rows = kotlin.math.ceil(height * scale).toInt()
        val image = Bitmap.createBitmap(profile.width,rows,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale,scale)
            draw(canvas)
            val pixels = IntArray(profile.width * rows)
            image.getPixels(pixels,0,profile.width,0,0,profile.width,rows)
            return EscPosRaster.encode(pixels,profile.width,rows,trim)
        } finally { image.recycle() }
    }

    private fun queue(): Unit = guarded {
        page("Print history and recovery")
        val jobs = store.jobs()
        if(jobs.isEmpty()) label("No print jobs yet.")
        jobs.forEach { job -> button("Session ${job.session} · ${job.state.lowercase()}") { job(job) } }
        adminBack("Back to photos & receipts") { photoLibrary() }
    }
    private fun job(job: Job): Unit = guarded {
        page("Receipt for session ${job.session}")
        label("Status: ${job.state.lowercase()}. A sent receipt may still be waiting inside the printer.")
        if (job.state != "SENDING") {
            button("Export receipt PDF") { guarded { createPdf("receipt",job.session,"DSD-receipt-${job.session}.pdf") } }
            button("Reprint one copy") { guarded {
                AlertDialog.Builder(this).setMessage("Check the printer first. This can produce an extra copy.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Print") { _,_ -> guarded {
                        printReceipt(job.session,true)
                    } }.show()
            } }
            if (job.state in listOf("TRANSMITTED","UNKNOWN")) button("Confirm physical print") { guarded {
                AlertDialog.Builder(this).setMessage("Only confirm after you see the printed receipt.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Confirm") { _,_ -> guarded {
                        store.confirm(job.session); queue()
                    } }.show()
            } }
        }
        adminBack("Back") { queue() }
    }

    private fun bluetoothAllowed() = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun printer(): Unit = guarded {
        page("Printer setup")
        val profile = thermal.profile()
        label(profile?.let { "Selected: ${it.name} via ${it.connection} • ${it.width} dots" } ?: "No printer selected")
        label("Pair Bluetooth Classic printers in Android settings first. For USB, plug the printer into an Android USB host/OTG port.")
        button("Android Bluetooth settings") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        if (!bluetoothAllowed()) button("Allow Bluetooth access") { nearby.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }
        else button("Select a paired Bluetooth printer") { choosePrinter("Bluetooth") }
        button("Select a USB printer") { choosePrinter("USB") }
        if (profile != null) {
            button("Edit printer settings") { configurePrinter(PrinterChoice(profile.connection,profile.id,profile.name)) }
            if (profile.connection == "USB" && !thermal.usbAccess(profile.id)) button("Allow USB printer access") {
                if (!thermal.requestUsbAccess(profile.id)) toast("Reconnect the selected USB printer first.")
            }
            button("Test print") { testPrinter(profile,1) }
            button("Test two copies") { testPrinter(profile,2) }
            button("Calibrate cut") { cutCalibration(profile) }
        }
        adminBack("Back") { dashboard() }
    }

    private fun cutCalibration(profile: PrinterProfile): Unit = guarded {
        page("Calibrate cut")
        label(if (profile.cut == ReceiptCut.REPOSITION) "Compact margins • feed, cut & reposition" else "Current cut clearance: ${profile.cutClearanceMm} mm")
        label(if (profile.cut == ReceiptCut.REPOSITION) "Check that both samples cut and that the next job starts close to the top. If the printer does not cut, use your previous cutter mode." else "Aim for about 5 mm of white paper between the black line and the cut. Increase Cut clearance if it clips the line; decrease it if the gap is too large.")
        button("Print two calibration samples", primary = true) { guarded {
            task({
                val packets = raster(profile,68) { canvas ->
                    canvas.drawText("CUT CALIBRATION",12f,22f,textPaint(14f))
                    canvas.drawText("Clearance: ${profile.cutClearanceMm} mm",12f,39f,textPaint(10f))
                    canvas.drawText("Bottom of design ↓",12f,56f,textPaint(10f))
                    canvas.drawRect(4f,65f,223f,68f,Paint().apply { color = Color.BLACK })
                }
                thermal.send(profile,packets,2)
            }) { toast(it.message) }
        } }
        button("Edit printer settings") { configurePrinter(PrinterChoice(profile.connection,profile.id,profile.name)) }
        adminBack("Back") { printer() }
    }

    private fun testPrinter(profile: PrinterProfile, copies: Int) = guarded {
        task({
            val packets = raster(profile,94) { canvas ->
                canvas.drawText("Daily Sweet Doze",12f,24f,textPaint(15f))
                canvas.drawText("ESC/POS raster test • ${profile.width} dots",12f,44f,textPaint(10f))
                repeat(8) { i ->
                    val tone = i * 32
                    canvas.drawRect(12f+i*24,56f,36f+i*24,82f,Paint().apply { color = Color.rgb(tone,tone,tone) })
                }
            }
            thermal.send(profile,packets,copies)
        }) { toast(it.message) }
    }

    private fun choosePrinter(connection: String): Unit = guarded {
        page("Select a ${connection} printer")
        val choices = if (connection == "USB") thermal.usbChoices() else thermal.bluetoothChoices()
        if (choices.isEmpty()) label(if(connection == "USB") "No standard USB printer detected. Check the cable and USB host support."
            else "No paired devices. Pair your ESC/POS printer in Android Bluetooth settings, then try again.")
        choices.forEach { choice -> button(choice.name) { configurePrinter(choice) } }
        adminBack("Back") { printer() }
    }

    private fun configurePrinter(choice: PrinterChoice): Unit = guarded {
        page("Configure ${choice.name}")
        val current = thermal.profile()?.takeIf { it.connection == choice.connection && it.id == choice.id }
        label("Enter the printable width from the printer manual. Start with 576 dots for 72 mm at 203 dpi, then verify with a test print.")
        val dots = input("Printable dots",InputType.TYPE_CLASS_NUMBER,3).apply { setText((current?.width ?: 576).toString()) }
        label("Cut clearance after each copy (5–60 mm)")
        val feed = input("Cut clearance in mm",InputType.TYPE_CLASS_NUMBER,2).apply {
            setText((current?.cutClearanceMm ?: EscPosRaster.DEFAULT_CUT_CLEARANCE_MM).toString())
        }
        label("Start at 30 mm, then use Calibrate cut to adjust for your printer.")
        label("Cut after every copy")
        val cut = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ReceiptCut.entries.map { it.label })
            setSelection((current?.cut ?: ReceiptCut.FULL).ordinal)
        }
        content.addView(cut)
        label("Use a cutter mode supported by your printer. Full cut is the default. Test before an event.")
        button("Save printer") { guarded {
            val width = dots.text.toString().toIntOrNull()
            if (width == null || width !in 384..832 || width % 8 != 0) toast("Enter a width between 384 and 832 divisible by 8.")
            else {
                val clearance = feed.text.toString().toIntOrNull()
                if (clearance == null || clearance !in EscPosRaster.cutClearanceRange) {
                    toast("Enter a cut clearance from 5 to 60 mm.")
                    return@guarded
                }
                thermal.save(choice,width,ReceiptCut.entries[cut.selectedItemPosition],clearance)
                if (choice.connection == "USB" && !thermal.usbAccess(choice.id)) thermal.requestUsbAccess(choice.id)
                printer()
            }
        } }
        adminBack("Back") { printer() }
    }

    private fun createPdf(kind: String,id: Long,filename: String) {
        exportKind = kind
        exportId = id
        export.launch(filename)
    }
    private inline fun withPdf(body: (PdfDocument) -> Unit) {
        val doc = PdfDocument()
        try { body(doc) } finally { doc.close() }
    }
    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size }
    private fun ticketsPdf(tickets: List<Ticket>, out: OutputStream) {
        require(tickets.isNotEmpty())
        withPdf { doc ->
            tickets.chunked(15).forEachIndexed { pageNumber, rows ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber+1).create())
                val canvas = page.canvas
                rows.forEachIndexed { i,t ->
                    val x = 27f + (i % 3)*180
                    val y = 25f + (i / 3)*154
                    canvas.drawRect(x,y,x+180,y+154,Paint().apply {
                        style = Paint.Style.STROKE; color = Color.GRAY
                        pathEffect = DashPathEffect(floatArrayOf(4f,3f),0f)
                    })
                    canvas.drawText("DAILY SWEET DOZE",x+12,y+26,textPaint(11f))
                    canvas.drawText(t.code,x+12,y+62,textPaint(27f))
                    canvas.drawText("One photo session",x+12,y+87,textPaint(10f))
                    canvas.drawText("Enter this code at the booth.",x+12,y+106,textPaint(10f))
                    canvas.drawText("Batch ${t.batch} / Ticket ${t.id}",x+12,y+132,textPaint(9f))
                }
                doc.finishPage(page)
            }
            doc.writeTo(out)
        }
    }
    private fun designHeight(choice: TemplateChoice) = (227 * choice.ratio).toInt().coerceAtLeast(1)

    private fun drawDesign(canvas: Canvas, session: Session, choice: TemplateChoice, dotWidth: Int) {
        val w = 227f
        val h = designHeight(choice).toFloat()
        val artwork = choice.artwork
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawColor(Color.WHITE)
        if (artwork != null) {
            val image = BitmapFactory.decodeFile(artworkFile(artwork.fileName).absolutePath) ?: error("Template artwork is unavailable.")
            canvas.drawBitmap(image,null,RectF(0f,0f,w,h),Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            if (choice.name == "Bold") canvas.drawRect(0f,0f,w,h,framePaint)
            val free = listOf(.94f,.11f,.50f).firstOrNull { y ->
                choice.slots.none { y+.018f > it.top && y-.045f < it.bottom && it.right > .15f && it.left < .85f }
            }
            free?.let {
                val title = textPaint(if (h < 180f) 9f else 11f).apply {
                    textAlign = Paint.Align.CENTER
                    color = if (choice.name == "Bold") Color.WHITE else Color.BLACK
                }
                canvas.drawText("Daily Sweet Doze",w/2,h*it,title)
            }
        }
        val images = selectedPhotos(session)
        val transforms = store.photoTransforms(session.id)
        choice.slots.forEachIndexed { index,slot ->
            val dest = slot.rect(w,h)
            val image = images.getOrNull(index)?.let(::bitmap)
            if (image != null) canvas.drawDottedPhoto(image,dest,dotWidth/227f,transforms[index+1] ?: 0)
            else {
                canvas.drawRect(dest,Paint().apply { color = Color.LTGRAY })
                canvas.drawText("Photo ${index+1}",dest.left+4,dest.top+12,textPaint(8f))
            }
            if (artwork == null) canvas.drawRect(dest,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (choice.name == "Bold") Color.WHITE else Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = if (choice.name == "Bold") 2f else 1f
                if (choice.name == "Dashed") pathEffect = DashPathEffect(floatArrayOf(4f,3f),0f)
            })
        }
    }

    private fun receiptPdf(session: Session, out: OutputStream) {
        val design = templateChoice(session)
        withPdf { doc ->
            val scale = 72f/80f
            val height = kotlin.math.ceil(designHeight(design)*scale).toInt()
            val page = doc.startPage(PdfDocument.PageInfo.Builder(227,height,1).create())
            page.canvas.save()
            page.canvas.translate((227f-227f*scale)/2f,0f)
            page.canvas.scale(scale,scale)
            // Draw the design inside the centered 72 mm image area on 80 mm paper.
            page.canvas.clipRect(0f,0f,227f,designHeight(design).toFloat())
            drawDesign(page.canvas,session,design,thermal.profile()?.width ?: 576)
            page.canvas.restore()
            doc.finishPage(page)
            doc.writeTo(out)
        }
    }
}

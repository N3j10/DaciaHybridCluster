package com.dacia.hybridcluster.car

import android.graphics.*
import android.util.Log
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.dacia.hybridcluster.model.AppStrings
import com.dacia.hybridcluster.model.ConfigManager
import com.dacia.hybridcluster.model.DaciaFonts
import com.dacia.hybridcluster.model.HybridMode
import com.dacia.hybridcluster.model.StringsManager
import com.dacia.hybridcluster.model.StringsPl
import com.dacia.hybridcluster.model.TelemetryData
import com.dacia.hybridcluster.model.UiConfig
import com.dacia.hybridcluster.obd.ObdManager
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.max

/**
 * Wspólna część rendererów powierzchni Android Auto (zegary + ekran środkowy).
 *
 * - Wszystkie sekcje wspólne dla obu ekranów (obrotomierz, pasek mocy, temperatury, baterie, trip)
 *   są tutaj – wcześniej ~570 linii było skopiowanych w obu klasach.
 * - Rysowanie bez alokacji w klatce: obiekty Paint/RectF są brane z puli przypisanej do miejsca
 *   wywołania ([sp], [spPlain], [spCopy], [rf]), kolory stałe są literałami, kolory z konfiguracji
 *   są parsowane raz i cache'owane.
 * - Klatka jest rysowana tylko gdy zmieniła się telemetria / konfiguracja / obszar ekranu albo
 *   trwa animacja pasków (plus odświeżenie kontrolne co [HEARTBEAT_FRAMES] klatek).
 */
abstract class TelemetryRendererBase(private val logTag: String) : SurfaceCallback {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var renderJob: Job? = null
    private val drawLock = Any()

    @Volatile private var visibleArea: Rect? = null
    @Volatile private var stableArea: Rect? = null
    @Volatile private var areaVersion = 0

    // Paleta kolorów - domyślnie głęboka czerń (#000000) dla idealnego wtopienia w ramkę zegarów
    protected val bgPaint = Paint().apply { color = Color.BLACK }
    protected val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF171717.toInt()
        style = Paint.Style.FILL
    }
    protected val cardHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF222222.toInt()
        style = Paint.Style.FILL
    }
    protected val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF363636.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    protected val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0E0E0E.toInt()
        style = Paint.Style.FILL
    }
    protected val textWhiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = DaciaFonts.blockBold
    }
    protected val textLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt()
        typeface = DaciaFonts.readMedium
    }
    // Kolory napędów
    protected val iceColor = 0xFFEC6528.toInt()   // Pomarańcz (Silnik spalinowy ICE)
    protected val rearColor = 0xFFB3CC23.toInt()  // Limonka Dacia (Tylny e-Motor e-AWD)
    protected val hsgColor = 0xFF29B6F6.toInt()   // Błękit elektryczny (Przedni HSG / Starter-Generator)
    protected val regenColor = 0xFF00E676.toInt() // Jasna zieleń (Rekuperacja)

    // Płynna animacja pasków 4x4 i mocy (zbieżność wykładnicza ~30 FPS)
    protected var animFrontPct = 100f
    protected var animIceKw = 0f
    protected var animRearKw = 0f
    protected var animHsgKw = 0f
    protected var animTotalRegenKw = 0f
    protected var animRearRegenKw = 0f
    protected var animMaxDriveScale = 25f
    private var animMoving = false

    protected var strings: AppStrings = StringsPl

    // ---- Pule obiektów rysujących: jeden obiekt na miejsce wywołania (id), bez alokacji w klatce ----
    private val paintPool = arrayOfNulls<Paint>(POOL_SIZE)
    private val rectPool = arrayOfNulls<RectF>(POOL_SIZE)
    private val colorCache = HashMap<String, Int>()

    /** Odpowiednik `Paint(Paint.ANTI_ALIAS_FLAG)`. */
    protected fun sp(id: Int): Paint {
        val p = paintPool[id] ?: Paint().also { paintPool[id] = it }
        p.reset()
        p.isAntiAlias = true
        return p
    }

    /** Odpowiednik `Paint()`. */
    protected fun spPlain(id: Int): Paint {
        val p = paintPool[id] ?: Paint().also { paintPool[id] = it }
        p.reset()
        return p
    }

    /** Odpowiednik `Paint(src)`. */
    protected fun spCopy(id: Int, src: Paint): Paint {
        val p = paintPool[id] ?: Paint().also { paintPool[id] = it }
        p.set(src)
        return p
    }

    /** Odpowiednik `RectF(l, t, r, b)`. */
    protected fun rf(id: Int, l: Float, t: Float, r: Float, b: Float): RectF {
        val rect = rectPool[id] ?: RectF().also { rectPool[id] = it }
        rect.set(l, t, r, b)
        return rect
    }

    private fun cachedColor(hex: String, fallback: Int): Int =
        colorCache.getOrPut(hex) { try { Color.parseColor(hex) } catch (_: Exception) { fallback } }

    private fun approach(current: Float, target: Float, factor: Float = 0.28f): Float {
        val diff = target - current
        val next = if (Math.abs(diff) < 0.02f) target else current + diff * factor
        if (next != current) animMoving = true
        return next
    }

    // ---- Cykl życia powierzchni ----

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        Log.i(logTag, "onSurfaceAvailable: valid=${surface.isValid}, dpi=${container.dpi}, w=${container.width}, h=${container.height}")
        startRenderLoop(surface)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.i(logTag, "onVisibleAreaChanged: $visibleArea")
        this.visibleArea = Rect(visibleArea)
        areaVersion++
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.i(logTag, "onStableAreaChanged: $stableArea")
        this.stableArea = Rect(stableArea)
        areaVersion++
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        Log.i(logTag, "onSurfaceDestroyed")
        stop()
    }

    fun stop() {
        renderJob?.cancel()
        renderJob = null
    }

    /** Obszar zgłoszony przez host (najpierw visible, potem stable); null = cała powierzchnia. */
    protected fun hostSafeArea(): Rect? {
        val v = visibleArea
        val s = stableArea
        return when {
            v != null && v.width() > 100 && v.height() > 100 -> v
            s != null && s.width() > 100 && s.height() > 100 -> s
            else -> null
        }
    }

    private fun startRenderLoop(surface: Surface) {
        stop()
        renderJob = scope.launch {
            var lastData: TelemetryData? = null
            var lastConfig: UiConfig? = null
            var lastArea = -1
            var idleFrames = 0
            while (isActive && surface.isValid) {
                val data = ObdManager.telemetryState.value
                val config = ConfigManager.configState.value
                val moving = synchronized(drawLock) { updateAnimations(data) }
                val dirty = moving || data !== lastData || config !== lastConfig ||
                    areaVersion != lastArea || idleFrames >= HEARTBEAT_FRAMES
                if (dirty) {
                    lastData = data
                    lastConfig = config
                    lastArea = areaVersion
                    idleFrames = 0
                    drawOnce(surface, data, config)
                } else {
                    idleFrames++
                }
                delay(FRAME_MS)
            }
        }
    }

    private var lastErrorClass: Class<*>? = null

    private fun drawOnce(surface: Surface, data: TelemetryData, config: UiConfig) {
        var canvas: Canvas? = null
        try {
            canvas = try { surface.lockHardwareCanvas() } catch (_: Exception) { surface.lockCanvas(null) }
            if (canvas != null) {
                synchronized(drawLock) {
                    prepareFrame(config)
                    drawFrame(canvas, data, config)
                }
            }
        } catch (e: Exception) {
            if (e.javaClass != lastErrorClass) { // nie zalewaj logcata tym samym błędem 30x/s
                lastErrorClass = e.javaClass
                Log.w(logTag, "Błąd rysowania klatki", e)
            }
        } finally {
            if (canvas != null) {
                try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }
    }

    /** Animacje pasków – liczone w każdej klatce (30 FPS), także gdy nic nie trzeba rysować. */
    private fun updateAnimations(data: TelemetryData): Boolean {
        animMoving = false
        val isStandstill = data.speedKmH < 1.0
        val isRegen = !isStandstill && data.totalRegenPowerKw > 0.2 && data.speedKmH > 2.0

        animFrontPct = approach(animFrontPct, data.frontTorquePercent.toFloat().coerceIn(0f, 100f), 0.28f)

        val targetTotalRegen = if (isRegen) data.totalRegenPowerKw.toFloat() else 0f
        val targetRearRegen = if (isRegen) data.rearRegenPowerKw.toFloat() else 0f
        animTotalRegenKw = approach(animTotalRegenKw, targetTotalRegen, 0.28f)
        animRearRegenKw = approach(animRearRegenKw, targetRearRegen, 0.28f)

        val driving = !isStandstill && !isRegen
        animIceKw = approach(animIceKw, if (driving) data.icePowerKw.toFloat().coerceAtLeast(0f) else 0f, 0.28f)
        animRearKw = approach(animRearKw, if (driving) data.rearMotorPowerKw.toFloat().coerceAtLeast(0f) else 0f, 0.28f)
        animHsgKw = approach(animHsgKw, if (driving) data.hsgPowerKw.toFloat().coerceAtLeast(0f) else 0f, 0.28f)

        val targetDriveScale = if (data.icePowerKw <= 0.2 && data.iceRpm < 300) 25f else 110f
        animMaxDriveScale = approach(animMaxDriveScale, targetDriveScale, 0.25f)
        return animMoving
    }

    private fun prepareFrame(config: UiConfig) {
        strings = StringsManager.get(config.appLanguage)
        textWhiteBold.typeface = DaciaFonts.blockBold
        textLabel.typeface = DaciaFonts.readMedium
        bgPaint.color = cachedColor(config.clusterBgColorHex, Color.BLACK)
        cardPaint.color = cachedColor(config.clusterCardColorHex, 0xFF171717.toInt())
    }

    /** Rysuje całą klatkę. Animacje i kolory z konfiguracji są już zaktualizowane. */
    protected abstract fun drawFrame(canvas: Canvas, data: TelemetryData, config: UiConfig)

    protected fun modeColor(mode: HybridMode): Int = when (mode) {
        HybridMode.EAWD_BOOST -> 0xFFFF5252.toInt()
        HybridMode.PURE_EV, HybridMode.REGEN -> 0xFFB3CC23.toInt()
        else -> 0xFFEC6528.toInt()
    }


    /**
     * Sportowy obrotomierz cyfrowy (Tachometer Gauge)
     */
    protected fun drawTachometerSection(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        data: TelemetryData,
        scale: Float
    ) {
        val rect = rf(4, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val cx = rect.centerX()
        val cy = y + h * 0.42f
        val radius = (min(w * 0.42f, h * 0.34f)).coerceAtLeast(56f)
        val totalStroke = radius * 0.14f
        val halfStroke = totalStroke * 0.46f

        val outerRadius = radius + totalStroke * 0.25f
        val innerRadius = radius - totalStroke * 0.25f
        val outerArcRect = rf(5, cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        val innerArcRect = rf(6, cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

        val startAngle = 140f
        val totalSweep = 260f

        // 1. Zewnętrzna połówka paska: Obroty silnika spalinowego ICE (0..6000 obr/min)
        val outerTrackPaint = sp(7).apply {
            color = 0xFF222222.toInt()
            style = Paint.Style.STROKE
            strokeWidth = halfStroke
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(outerArcRect, startAngle, totalSweep, false, outerTrackPaint)

        // Czerwone pole powyżej 4500 RPM na zewnętrznym pasku ICE
        val redlineStart = startAngle + totalSweep * (4500f / 6000f)
        val redlineSweep = totalSweep * (1500f / 6000f)
        val redlinePaint = sp(8).apply {
            color = 0xFF5A2410.toInt()
            style = Paint.Style.STROKE
            strokeWidth = halfStroke
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(outerArcRect, redlineStart, redlineSweep, false, redlinePaint)

        val rpm = data.iceRpm.coerceIn(0, 6000)
        val isEvMode = rpm == 0 || data.hybridMode == HybridMode.PURE_EV
        val rpmRatio = (rpm / 6000f).coerceIn(0f, 1f)
        val activeSweepIce = (totalSweep * rpmRatio).coerceAtLeast(if (isEvMode) 0f else 3f)

        val arcColorIce = when {
            isEvMode -> 0xFFB3CC23.toInt()
            rpm > 4500 -> 0xFFFF5252.toInt()
            rpm > 3000 -> 0xFFEC6528.toInt()
            else -> 0xFFB3CC23.toInt()
        }
        val activePaintIce = sp(9).apply {
            color = arcColorIce
            style = Paint.Style.STROKE
            strokeWidth = halfStroke
            strokeCap = Paint.Cap.ROUND
        }
        if (activeSweepIce > 0f) {
            canvas.drawArc(outerArcRect, startAngle, activeSweepIce, false, activePaintIce)
        }

        // 2. Wewnętrzna połówka paska: Obroty silnika elektrycznego (niebieski jak w SYSTEM 48 MHEV)
        val innerTrackPaint = sp(10).apply {
            color = 0xFF121824.toInt()
            style = Paint.Style.STROKE
            strokeWidth = halfStroke
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(innerArcRect, startAngle, totalSweep, false, innerTrackPaint)

        val rawERpm = Math.abs(data.rearMotorRpm)
        val eRpm = if (data.speedKmH < 1.0 && rawERpm <= 15) 0 else rawERpm
        val eRpmRatio = (eRpm / 15000f).coerceIn(0f, 1f)
        val activeSweepElec = (totalSweep * eRpmRatio)
        if (activeSweepElec > 0f) {
            val elecPaint = sp(11).apply {
                color = 0xFF29B6F6.toInt() // Niebieski elektryczny SYSTEM 48 MHEV
                style = Paint.Style.STROKE
                strokeWidth = halfStroke
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(innerArcRect, startAngle, activeSweepElec, false, elecPaint)
        }

        // 3. Wnętrze obrotomierza:
        // Układ pionowy wg specyfikacji użytkownika:
        // 1. Bieg ICE (zielony/khaki)
        // 2. Obroty ICE (białe)
        // 3. Napis "obr/min" (biały/jasnoszary)
        // 4. Obroty silnika elektrycznego (niebieskie)
        // 5. Bieg silnika elektrycznego (niebieski, tej samej wielkości co bieg ICE: R2, R1, N, E1, E2)
        val gearFontSize = (radius * 0.34f * scale).coerceIn(18f, 42f)
        val rpmFontSize = (radius * 0.25f * scale).coerceIn(14f, 30f)
        val unitFontSize = (radius * 0.12f * scale).coerceIn(8f, 14f)

        // 1. Bieg spalinowy ICE (khaki/limonka)
        val iceGearPaint = sp(12).apply {
            color = 0xFFB3CC23.toInt()
            typeface = DaciaFonts.blockBold
            textSize = gearFontSize
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(data.currentGear, cx, cy - radius * 0.48f, iceGearPaint)

        // 2. Obroty silnika spalinowego (białe)
        val iceRpmPaint = sp(13).apply {
            color = Color.WHITE
            typeface = DaciaFonts.blockBold
            textSize = rpmFontSize
            textAlign = Paint.Align.CENTER
        }
        val rpmText = if (isEvMode) "0" else "$rpm"
        canvas.drawText(rpmText, cx, cy - radius * 0.23f, iceRpmPaint)

        // 3. Jednostka "obr/min" pomiędzy obrotami ICE a obrotami EV
        val obrLabelPaint = sp(14).apply {
            color = 0xFFE0E0E0.toInt()
            typeface = DaciaFonts.readMedium
            textSize = unitFontSize
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(strings.rpmUnit, cx, cy - radius * 0.02f, obrLabelPaint)

        // 4. Obroty silnika elektrycznego (niebieski elektryczny)
        val elecRpmPaint = sp(15).apply {
            color = 0xFF29B6F6.toInt()
            typeface = DaciaFonts.blockBold
            textSize = rpmFontSize
            textAlign = Paint.Align.CENTER
        }
        val eRpmText = "$eRpm"
        canvas.drawText(eRpmText, cx, cy + radius * 0.22f, elecRpmPaint)

        // 5. Bieg silnika elektrycznego (niebieską czcionką dokładnie wielkości biegu spalinowego: R2, R1, N, E1, E2)
        val elecGearPaint = sp(16).apply {
            color = 0xFF29B6F6.toInt()
            typeface = DaciaFonts.blockBold
            textSize = gearFontSize
            textAlign = Paint.Align.CENTER
        }
        val eGearDisplay = if (data.eGear in ERAD_GEARS) data.eGear else "N"
        canvas.drawText(eGearDisplay, cx, cy + radius * 0.50f, elecGearPaint)

        // 4. Dolna część karty pod obrotomierzem:
        // - Wiersz 1: DOTRYSK PALIWA (w kolorze tła karty gdy brak, pomarańczowy #EC6528 gdy aktywny)
        // - Kolejne wiersze (pod DOTRYSK PALIWA): Powody blokady EV (jeden pod drugim gdy jest ich więcej,
        //   w kolorze tła karty gdy brak blokady, pomarańczowe #EC6528 gdy aktywne)
        val inhibitLines = data.evInhibits
        val statusFontSize = (h * (if (inhibitLines.size >= 3) 0.048f else 0.056f) * scale).coerceIn(13f, 22f)
        val activeOrangeColor = 0xFFEC6528.toInt()
        val inactiveBgColor = cardPaint.color

        val boostActive = data.fuelInjectionBoostActive
        val boostPaint = sp(17).apply {
            color = if (boostActive) activeOrangeColor else inactiveBgColor
            typeface = DaciaFonts.readMedium
            textAlign = Paint.Align.CENTER
        }
        val boostY = if (inhibitLines.size >= 3) y + h * 0.75f else y + h * 0.77f
        drawFittedText(
            canvas,
            strings.fuelBoostLabel,
            cx,
            boostY,
            w - 16f,
            statusFontSize,
            boostPaint,
            Paint.Align.CENTER
        )

        if (inhibitLines.isEmpty()) {
            val inactivePaint = sp(18).apply {
                color = inactiveBgColor
                typeface = DaciaFonts.readMedium
                textAlign = Paint.Align.CENTER
            }
            drawFittedText(
                canvas,
                strings.evBlockLabel,
                cx,
                y + h * 0.855f,
                w - 16f,
                statusFontSize,
                inactivePaint,
                Paint.Align.CENTER
            )
        } else {
            val activeInhibitPaint = sp(19).apply {
                color = activeOrangeColor
                typeface = DaciaFonts.readMedium
                textAlign = Paint.Align.CENTER
            }
            val visibleLines = inhibitLines.take(3)
            val linePositions = when (visibleLines.size) {
                1 -> floatArrayOf(0.855f)
                2 -> floatArrayOf(0.855f, 0.94f)
                else -> floatArrayOf(0.825f, 0.895f, 0.965f)
            }
            for (i in visibleLines.indices) {
                drawFittedText(
                    canvas,
                    strings.inhibitLine(visibleLines[i]),
                    cx,
                    y + h * linePositions[i],
                    w - 16f,
                    statusFontSize,
                    activeInhibitPaint,
                    Paint.Align.CENTER
                )
            }
        }
    }

    /**
     * Prawy nagłówek w trybie szerokim (Tryb hybrydowy + Wektor napędu 4x4)
     */
    protected fun drawWideHeaderSection(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        data: TelemetryData,
        config: UiConfig,
        scale: Float
    ) {
        val rect = rf(20, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val modeColor = modeColor(data.hybridMode)
        val modePaint = sp(21).apply {
            color = modeColor
            typeface = DaciaFonts.readMedium
        }
        val titleSize = (h * 0.36f * scale).coerceIn(12f, 22f)
        val halfW = (w - 32f) * 0.46f // Bezpieczna szerokość połówki zapobiegająca nachodzeniu na prawy tekst

        drawFittedText(
            canvas, "${strings.modePrefix}${strings.modeName(data.hybridMode)}",
            x + 14f, y + h * 0.44f,
            halfW, titleSize, modePaint, Paint.Align.LEFT
        )

        val splitText = "4x4: ${data.frontTorquePercent}% ${strings.frontLabel} • ${data.rearTorquePercent}% ${strings.rearLabel}"
        val splitPaint = sp(22).apply {
            color = Color.WHITE
            typeface = DaciaFonts.readMedium
        }
        val splitSize = (h * 0.30f * scale).coerceIn(11f, 19f)
        drawFittedText(
            canvas, splitText,
            x + w - 14f, y + h * 0.44f,
            halfW, splitSize, splitPaint, Paint.Align.RIGHT
        )

        // Dolny pasek podziału 4x4 (płynna animacja)
        val barTop = y + h * 0.70f
        val barBottom = y + h * 0.88f
        val barRect = rf(23, x + 14f, barTop, x + w - 14f, barBottom)
        canvas.drawRoundRect(barRect, 6f, 6f, barTrackPaint)

        val frontW = barRect.width() * (animFrontPct / 100f)
        canvas.drawRect(barRect.left, barTop, barRect.left + frontW, barBottom, spPlain(24).apply { color = iceColor })
        canvas.drawRect(barRect.left + frontW, barTop, barRect.right, barBottom, spPlain(25).apply { color = rearColor })
        canvas.drawRoundRect(barRect, 6f, 6f, cardStrokePaint)
    }

    protected fun fmtPwr(kw: Double): String {
        val absKw = Math.abs(kw)
        return when {
            absKw < 0.05 -> "0"
            absKw < 9.95 -> String.format(java.util.Locale.US, "%.1f", absKw)
            else -> "${Math.round(absKw)}"
        }
    }

    protected fun drawPowerSection(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        data: TelemetryData,
        scale: Float
    ) {
        val rect = rf(26, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val innerPad = 12f
        val innerW = w - 2f * innerPad

        val isStandstill = data.speedKmH < 1.0
        val isRegen = !isStandstill && data.totalRegenPowerKw > 0.2 && data.speedKmH > 2.0

        val mainPowerText = when {
            isStandstill -> "0 kW"
            isRegen -> "-${fmtPwr(data.totalRegenPowerKw)} kW ${strings.regenSuffix}"
            data.totalOutputPowerKw > 0.2 -> "+${fmtPwr(data.totalOutputPowerKw)} kW ${strings.powerSuffix}"
            else -> "0 kW"
        }
        val mainPowerColor = when {
            isRegen -> regenColor
            data.hybridMode == HybridMode.PURE_EV -> 0xFFB3CC23.toInt()
            data.hybridMode == HybridMode.EAWD_BOOST -> 0xFFFF5252.toInt()
            else -> Color.WHITE
        }
        val mainPowerPaint = spCopy(27, textWhiteBold).apply { color = mainPowerColor }
        val bigPowerSize = (h * 0.31f * scale).coerceIn(16f, 42f)

        drawFittedText(
            canvas, mainPowerText,
            x + innerPad, y + h * 0.34f,
            innerW * 0.44f, bigPowerSize, mainPowerPaint, Paint.Align.LEFT
        )

        val detailText = when {
            isStandstill -> if (data.hvBatteryCurrent > 0.5) strings.charging48vStatus else strings.stoppedStatus
            isRegen -> {
                val rearRegen = data.rearRegenPowerKw
                val frontRegen = (data.totalRegenPowerKw - rearRegen).coerceAtLeast(0.0)
                "${strings.rearLabel} -${fmtPwr(rearRegen)}  •  ${strings.frontLabel} -${fmtPwr(frontRegen)} kW"
            }
            data.currentGear == "R" -> strings.reversingStatus
            data.hybridMode == HybridMode.PURE_EV -> {
                "${strings.pureEvDriveStatus} ${fmtPwr(data.rearMotorPowerKw)} kW"
            }
            data.totalOutputPowerKw > 0.2 -> {
                "ICE ${fmtPwr(data.icePowerKw)} • ${strings.rearLabel} ${fmtPwr(data.rearMotorPowerKw)} • HSG ${fmtPwr(data.hsgPowerKw)} kW"
            }
            else -> strings.coastingStatus
        }
        val detailSize = (h * 0.23f * scale).coerceIn(12f, 28f)
        drawFittedText(
            canvas, detailText,
            x + w - innerPad, y + h * 0.33f,
            innerW * 0.54f, detailSize, textLabel, Paint.Align.RIGHT
        )

        // Gruby, czytelny pasek mocy w dolnej połowie karty (płynna animacja)
        val barTop = y + h * 0.48f
        val barBottom = y + h * 0.86f
        val barRect = rf(28, x + innerPad, barTop, x + w - innerPad, barBottom)
        canvas.drawRoundRect(barRect, 8f, 8f, barTrackPaint)

        val midX = barRect.centerX()
        val halfW = barRect.width() / 2f

        // 1. Rekuperacja (w lewo od środka) - płynna animacja ~30 FPS
        if (animTotalRegenKw > 0.1f) {
            val maxRegenScale = 20f
            val regenW = ((animTotalRegenKw / maxRegenScale) * halfW).coerceIn(2f, halfW)
            val rearRatio = if (animTotalRegenKw > 0.01f) (animRearRegenKw / animTotalRegenKw).coerceIn(0f, 1f) else 1f
            val rearRegenW = (regenW * rearRatio).coerceAtLeast(0f)
            if (rearRegenW > 0.5f) {
                canvas.drawRect(midX - rearRegenW, barTop, midX, barBottom, spPlain(29).apply { color = regenColor })
            }
            if (regenW > rearRegenW + 0.5f) {
                canvas.drawRect(midX - regenW, barTop, midX - rearRegenW, barBottom, spPlain(30).apply { color = hsgColor })
            }
        } else if (!isStandstill && (animIceKw + animRearKw + animHsgKw) > 0.1f) {
            // 2. Oddawanie mocy (w prawo od środka) - płynna animacja ~30 FPS
            val driveScale = animMaxDriveScale.coerceAtLeast(20f)
            var startX = midX
            val iceW = ((animIceKw / driveScale) * halfW).coerceAtMost(halfW)
            if (iceW > 0.5f) {
                canvas.drawRect(startX, barTop, startX + iceW, barBottom, spPlain(31).apply { color = iceColor })
                startX += iceW
            }

            val rearW = ((animRearKw / driveScale) * halfW).coerceAtMost(halfW - (startX - midX))
            if (rearW > 0.5f) {
                canvas.drawRect(startX, barTop, startX + rearW, barBottom, spPlain(32).apply { color = rearColor })
                startX += rearW
            }

            val hsgW = ((animHsgKw / driveScale) * halfW).coerceAtMost(halfW - (startX - midX))
            if (hsgW > 0.5f) {
                canvas.drawRect(startX, barTop, startX + hsgW, barBottom, spPlain(33).apply { color = hsgColor })
            }
        }

        // Obrys paska + biała linia środkowa "0 kW"
        canvas.drawRoundRect(barRect, 8f, 8f, cardStrokePaint)
        val zeroPaint = spPlain(34).apply { color = Color.WHITE; strokeWidth = 3f }
        canvas.drawLine(midX, barTop - 4f, midX, barBottom + 4f, zeroPaint)
    }

    protected fun drawTelemetryGrid(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        gap: Float,
        corner: Float,
        data: TelemetryData,
        config: UiConfig,
        scale: Float
    ) {
        if (config.showTemperatures && config.show12vBattery) {
            val rowH = (h - gap) / 2f
            // Wiersz 1: Scalony kafelek 4 temperatur (cała szerokość w)
            drawMergedTemperaturesTile(canvas, x, y, w, rowH, corner, data, scale)

            // Wiersz 2: Dwa identyczne, symetryczne kafelki akumulatorów (48V MHEV oraz 12V DC-DC)
            drawBatteryCards(canvas, x, y + rowH + gap, w, rowH, gap, corner, data, config, scale)
        } else if (config.showTemperatures) {
            // Tylko temperatury (jeden szeroki kafelek na pełną wysokość)
            drawMergedTemperaturesTile(canvas, x, y, w, h, corner, data, scale)
        } else if (config.show12vBattery) {
            // Tylko baterie (2 kafle obok siebie na pełną wysokość h)
            drawBatteryCards(canvas, x, y, w, h, gap, corner, data, config, scale)
        }
    }

    protected fun drawMergedTemperaturesTile(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        data: TelemetryData,
        scale: Float
    ) {
        val rect = rf(35, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val segW = w / 4f
        val divPaint = sp(36).apply {
            color = 0xFF22283A.toInt()
            strokeWidth = 1.5f * scale
        }
        for (i in 1..3) {
            val divX = x + i * segW
            canvas.drawLine(divX, y + h * 0.18f, divX, y + h * 0.82f, divPaint)
        }

        // Nazwy temperatur pobierane ze strings
        val labels = listOf(strings.oilLabelShort, strings.gearboxLabelShort, strings.elecMotorLabelShort, strings.coolantLabelShort)
        val values = listOf(
            fmtTemp(data.oilTempC),
            fmtTemp(data.gearboxTempC),
            fmtTemp(data.rearMotorTempC),
            fmtTemp(data.coolantTempC)
        )

        val maxSegW = segW - 10f * scale

        // Obliczenie wspólnego, identycznego rozmiaru czcionki dla nagłówków
        val labelPaint = sp(37).apply {
            color = Color.WHITE
            typeface = DaciaFonts.readMedium
            textAlign = Paint.Align.CENTER
        }
        var unifiedLabelSize = (h * 0.22f * scale).coerceIn(9f, 15f)
        labelPaint.textSize = unifiedLabelSize
        var maxLabelMeasured = 0f
        for (lbl in labels) {
            val m = labelPaint.measureText(lbl)
            if (m > maxLabelMeasured) maxLabelMeasured = m
        }
        if (maxLabelMeasured > maxSegW && maxSegW > 0f) {
            unifiedLabelSize *= (maxSegW / maxLabelMeasured)
        }
        labelPaint.textSize = unifiedLabelSize

        // Obliczenie wspólnego, identycznego rozmiaru czcionki dla wartości temperatur
        val valPaint = sp(38).apply {
            color = Color.WHITE
            typeface = DaciaFonts.blockRegular
            textAlign = Paint.Align.CENTER
        }
        var unifiedValSize = (h * 0.38f * scale).coerceIn(14f, 30f)
        valPaint.textSize = unifiedValSize
        var maxValMeasured = 0f
        for (v in values) {
            val m = valPaint.measureText(v)
            if (m > maxValMeasured) maxValMeasured = m
        }
        if (maxValMeasured > maxSegW && maxSegW > 0f) {
            unifiedValSize *= (maxSegW / maxValMeasured)
        }
        valPaint.textSize = unifiedValSize

        for (i in 0..3) {
            val cx = x + i * segW + segW / 2f
            canvas.drawText(labels[i], cx, y + h * 0.35f, labelPaint)
            canvas.drawText(values[i], cx, y + h * 0.79f, valPaint)
        }
    }

    protected fun drawBatteryCards(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        gap: Float,
        corner: Float,
        data: TelemetryData,
        config: UiConfig,
        scale: Float
    ) {
        val colW = (w - gap) / 2f

        // --- Dane dla BATERII 48V MHEV ---
        val v48 = if (data.hvBatteryVoltage > 20.0) data.hvBatteryVoltage else 0.0
        val soc48Val = if (data.hvBatterySocPercent in 5.0..100.0) data.hvBatterySocPercent.toInt() else 0
        val color48 = 0xFF29B6F6.toInt() // Błękit elektryczny
        val redDischargeColor = 0xFFFF5252.toInt() // Czerwony dla spadku/rozładowania

        // W Sunwoda BMS:
        // prąd > +0.3A lub rekuperacja = ŁADOWANIE (procenty w górę) -> strzałka "↑", kolor reszty tekstu (#29B6F6)
        // prąd < -0.3A lub napęd = ROZŁADOWANIE (spadek procentów) -> strzałka "↓", kolor CZERWONY
        val (arrow48, arrow48Color) = when {
            data.hvBatteryCurrent > 0.3 || data.totalRegenPowerKw > 0.1 -> Pair("↑", color48)
            data.hvBatteryCurrent < -0.3 || data.rearMotorPowerKw > 0.1 -> Pair("↓", redDischargeColor)
            else -> Pair("", color48)
        }
        val soc48Part = if (soc48Val > 0) "$soc48Val%" else "--%"
        val label48 = strings.bat48vLabel
        val val48Base = if (v48 > 20.0) "${String.format(java.util.Locale.US, "%.1f", v48)}V  •  $soc48Part" else "--V  •  $soc48Part"

        // --- Dane dla BATERII 12V ---
        val label12 = if (config.show12vWattsMode) strings.bat12vWattsLabel else strings.bat12vLabel
        val color12 = 0xFFB3CC23.toInt() // Limonkowy / zielony Dacia
        val isCharging12 = data.bat12vVoltage >= 13.0
        val (arrow12, arrow12Color) = when {
            data.bat12vVoltage <= 5.0 || config.show12vWattsMode -> Pair("", color12)
            isCharging12 -> Pair("↑", color12)
            else -> Pair("↓", redDischargeColor)
        }

        val val12Base = if (config.show12vWattsMode) {
            val totalWatts = data.bat12vPowerWatts.toInt()
            val amps = data.bat12vCurrent.toInt()
            if (data.bat12vVoltage > 5.0) {
                // Moc i prąd wyjściowy przetwornicy DC-DC (wcześniej ta sama wartość W była pokazana dwa razy)
                "${totalWatts}W  •  ${amps}A"
            } else {
                "--W  •  --W"
            }
        } else {
            // Tylko rzeczywisty odczyt SoC z EMS (220141) – bez zgadywania 82% / 75% z napięcia
            val dispSoc12 = if (data.bat12vSocPercent in 10..100) data.bat12vSocPercent else 0
            val soc12Part = if (dispSoc12 > 0) "$dispSoc12%" else "--%"
            if (data.bat12vVoltage > 5.0) "${String.format(java.util.Locale.US, "%.1f", data.bat12vVoltage)}V  •  $soc12Part" else "--V  •  --%"
        }

        // --- Obliczenie WSPÓLNEGO, IDENTYCZNEGO rozmiaru czcionki dla OBU kafelków ---
        val maxValW = colW - 24f
        val maxLabelW = colW - 24f

        val labelPaint = sp(39).apply {
            color = 0xFFB0B0B0.toInt()
            typeface = DaciaFonts.readMedium
        }
        var unifiedLabelSize = (h * 0.24f * scale).coerceIn(10f, 18f)
        labelPaint.textSize = unifiedLabelSize
        val labelW1 = labelPaint.measureText(label48)
        val labelW2 = labelPaint.measureText(label12)
        val maxLabelMeasured = max(labelW1, labelW2)
        if (maxLabelMeasured > maxLabelW && maxLabelW > 0f) {
            unifiedLabelSize *= (maxLabelW / maxLabelMeasured)
        }

        val testValPaint = sp(40).apply {
            typeface = DaciaFonts.blockRegular
        }
        var unifiedValSize = (h * 0.38f * scale).coerceIn(14f, 30f)
        testValPaint.textSize = unifiedValSize
        val fullText1 = if (arrow48.isNotEmpty()) "$val48Base $arrow48" else val48Base
        val fullText2 = if (arrow12.isNotEmpty()) "$val12Base $arrow12" else val12Base
        val valW1 = testValPaint.measureText(fullText1)
        val valW2 = testValPaint.measureText(fullText2)
        val maxValMeasured = max(valW1, valW2)
        if (maxValMeasured > maxValW && maxValW > 0f) {
            unifiedValSize *= (maxValW / maxValMeasured)
        }

        // KAFELEK 1: 48V (po lewej)
        drawCustomMetricTile(
            canvas, x, y, colW, h, corner,
            label = label48,
            value = val48Base,
            valueColor = color48,
            labelSize = unifiedLabelSize,
            valSize = unifiedValSize,
            arrow = arrow48,
            arrowColor = arrow48Color
        )

        // KAFELEK 2: 12V (po prawej)
        drawCustomMetricTile(
            canvas, x + colW + gap, y, colW, h, corner,
            label = label12,
            value = val12Base,
            valueColor = color12,
            labelSize = unifiedLabelSize,
            valSize = unifiedValSize,
            arrow = arrow12,
            arrowColor = arrow12Color
        )
    }

    protected fun drawCustomMetricTile(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        label: String,
        value: String,
        valueColor: Int,
        labelSize: Float,
        valSize: Float,
        arrow: String? = null,
        arrowColor: Int? = null
    ) {
        val rect = rf(41, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val centerX = x + w / 2f

        val labelPaint = sp(42).apply {
            color = 0xFFB0B0B0.toInt()
            typeface = DaciaFonts.readMedium
            textSize = labelSize
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(label, centerX, y + h * 0.35f, labelPaint)

        val valPaint = sp(43).apply {
            color = valueColor
            typeface = DaciaFonts.blockRegular
            textSize = valSize
            textAlign = Paint.Align.LEFT
        }
        val textY = y + h * 0.79f

        val valueWidth = valPaint.measureText(value)
        val arrowStr = if (!arrow.isNullOrEmpty() && arrowColor != null) " $arrow" else ""
        val arrowWidth = if (arrowStr.isNotEmpty()) valPaint.measureText(arrowStr) else 0f
        val totalWidth = valueWidth + arrowWidth
        val startX = centerX - totalWidth / 2f

        canvas.drawText(value, startX, textY, valPaint)

        if (arrowStr.isNotEmpty() && arrowColor != null) {
            val arrowPaint = spCopy(44, valPaint).apply {
                color = arrowColor
            }
            canvas.drawText(arrowStr, startX + valueWidth, textY, arrowPaint)
        }
    }

    protected fun fmtTemp(t: Double): String = if (t <= -100.0) "--" else "${t.toInt()}°C"

    protected fun drawTripSection(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        data: TelemetryData,
        config: UiConfig,
        scale: Float
    ) {
        val rect = rf(45, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val colW = w / 3f

        // Subtelne pionowe linie rozdzielające kolumny
        val divPaint = spPlain(46).apply {
            color = 0xFF262626.toInt()
            strokeWidth = 1.5f
        }
        canvas.drawLine(x + colW, y + 8f, x + colW, y + h - 8f, divPaint)
        canvas.drawLine(x + 2f * colW, y + 8f, x + 2f * colW, y + h - 8f, divPaint)

        val labelPaint = sp(47).apply {
            color = 0xFFB0B0B0.toInt()
            typeface = DaciaFonts.readMedium
        }
        val valPaint = sp(48).apply {
            color = 0xFFB3CC23.toInt()
            typeface = DaciaFonts.blockRegular
        }
        val subPaint = sp(49).apply {
            color = 0xFF8E99A4.toInt()
            typeface = DaciaFonts.readLight
        }

        val lbl1 = strings.usedBatteryLabel
        val lbl2 = strings.regenRecoveryLabel
        val lbl3 = strings.fuelSavedLabel

        val val1 = "${String.format(java.util.Locale.US, "%.2f", data.tripUsedElecKWh)} kWh"
        val val2 = "+${String.format(java.util.Locale.US, "%.2f", data.tripRegenKWh)} kWh"
        val val3 = "+${String.format(java.util.Locale.US, "%.2f", data.tripFuelSavedLiters)} L"

        if (h >= 75f) {
            val maxColW = colW - 14f
            val labelSize = (h * 0.19f * scale).coerceIn(8f, 15f)
            val valSize = (h * 0.32f * scale).coerceIn(12f, 25f)
            val subSize = (h * 0.16f * scale).coerceIn(8f, 13f)

            // Obliczenie wspólnego, identycznego rozmiaru czcionki dla etykiet
            val testLblPaint = spCopy(50, labelPaint).apply { textSize = labelSize }
            val maxLblMeasured = max(testLblPaint.measureText(lbl1), max(testLblPaint.measureText(lbl2), testLblPaint.measureText(lbl3)))
            val unifiedLabelSize = if (maxLblMeasured > maxColW && maxColW > 0f) {
                (labelSize * (maxColW / maxLblMeasured)).coerceAtLeast(8f)
            } else labelSize

            // Obliczenie wspólnego, identycznego rozmiaru czcionki dla wartości (wszystkie 3 kolumny mają ten sam rozmiar)
            val testValPaint = spCopy(51, valPaint).apply { textSize = valSize }
            val maxValMeasured = max(testValPaint.measureText(val1), max(testValPaint.measureText(val2), testValPaint.measureText(val3)))
            val unifiedValSize = if (maxValMeasured > maxColW && maxColW > 0f) {
                (valSize * (maxColW / maxValMeasured)).coerceAtLeast(10f)
            } else valSize

            // Kolumna 1: Zużycie energii z baterii trakcyjnej
            drawFittedText(
                canvas, lbl1,
                x + colW * 0.5f, y + h * 0.28f,
                maxColW, unifiedLabelSize, labelPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, val1,
                x + colW * 0.5f, y + h * 0.60f,
                maxColW, unifiedValSize, valPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, "${data.tripEvDistancePercent.toInt()}% EV (${String.format(java.util.Locale.US, "%.1f", data.tripDistanceKm)} km)",
                x + colW * 0.5f, y + h * 0.85f,
                maxColW, subSize, subPaint, Paint.Align.CENTER
            )

            // Kolumna 2: Energia odzyskana z hamowania (rekuperacja)
            drawFittedText(
                canvas, lbl2,
                x + colW * 1.5f, y + h * 0.28f,
                maxColW, unifiedLabelSize, labelPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, val2,
                x + colW * 1.5f, y + h * 0.60f,
                maxColW, unifiedValSize, valPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, strings.regenAwdSub,
                x + colW * 1.5f, y + h * 0.85f,
                maxColW, subSize, subPaint, Paint.Align.CENTER
            )

            // Kolumna 3: Zaoszczędzone paliwo i dystans
            val col3Sub = if (config.showGForce) {
                "${String.format(java.util.Locale.US, "%.2f", data.gForce)}G"
            } else {
                strings.hybridSavingsSub
            }
            drawFittedText(
                canvas, lbl3,
                x + colW * 2.5f, y + h * 0.28f,
                maxColW, unifiedLabelSize, labelPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, val3,
                x + colW * 2.5f, y + h * 0.60f,
                maxColW, unifiedValSize, valPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, col3Sub,
                x + colW * 2.5f, y + h * 0.85f,
                maxColW, subSize, subPaint, Paint.Align.CENTER
            )
        } else {
            val maxColW = colW - 12f
            val labelSize = (h * 0.22f * scale).coerceIn(8f, 14f)
            val valSize = (h * 0.32f * scale).coerceIn(11f, 22f)

            // Obliczenie wspólnego, identycznego rozmiaru czcionki dla etykiet
            val testLblPaint = spCopy(52, labelPaint).apply { textSize = labelSize }
            val maxLblMeasured = max(testLblPaint.measureText(lbl1), max(testLblPaint.measureText(lbl2), testLblPaint.measureText(lbl3)))
            val unifiedLabelSize = if (maxLblMeasured > maxColW && maxColW > 0f) {
                (labelSize * (maxColW / maxLblMeasured)).coerceAtLeast(8f)
            } else labelSize

            // Obliczenie wspólnego, identycznego rozmiaru czcionki dla wartości (wszystkie 3 kolumny mają ten sam rozmiar)
            val testValPaint = spCopy(53, valPaint).apply { textSize = valSize }
            val maxValMeasured = max(testValPaint.measureText(val1), max(testValPaint.measureText(val2), testValPaint.measureText(val3)))
            val unifiedValSize = if (maxValMeasured > maxColW && maxColW > 0f) {
                (valSize * (maxColW / maxValMeasured)).coerceAtLeast(10f)
            } else valSize

            // Układ kompaktowy dwuwierszowy (bezpieczny odstęp pionowy: 0.32f i 0.74f bez ucinania)
            drawFittedText(
                canvas, lbl1,
                x + colW * 0.5f, y + h * 0.32f,
                maxColW, unifiedLabelSize, labelPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, val1,
                x + colW * 0.5f, y + h * 0.74f,
                maxColW, unifiedValSize, valPaint, Paint.Align.CENTER
            )

            drawFittedText(
                canvas, lbl2,
                x + colW * 1.5f, y + h * 0.32f,
                maxColW, unifiedLabelSize, labelPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, val2,
                x + colW * 1.5f, y + h * 0.74f,
                maxColW, unifiedValSize, valPaint, Paint.Align.CENTER
            )

            drawFittedText(
                canvas, lbl3,
                x + colW * 2.5f, y + h * 0.32f,
                maxColW, unifiedLabelSize, labelPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, val3,
                x + colW * 2.5f, y + h * 0.74f,
                maxColW, unifiedValSize, valPaint, Paint.Align.CENTER
            )
        }
    }

    /**
     * Rysuje tekst o zadanym rozmiarze, a jeśli nie mieści się w `maxWidth`,
     * automatycznie dopasowuje rozmiar czcionki, by nigdy nie uciąć ani nie nałożyć napisów.
     */
    protected fun drawFittedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        desiredSize: Float,
        basePaint: Paint,
        align: Paint.Align
    ) {
        if (maxWidth <= 0f) return
        val paint = spCopy(54, basePaint)
        paint.textAlign = align
        paint.textSize = desiredSize

        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            paint.textSize = (desiredSize * (maxWidth / measured)).coerceAtLeast(8f)
        }
        canvas.drawText(text, x, y, paint)
    }

    companion object {
        private const val POOL_SIZE = 512
        private const val FRAME_MS = 33L
        private const val HEARTBEAT_FRAMES = 30
        /** Biegi tylnej przekładni, które wyświetlamy dosłownie. */
        val ERAD_GEARS = setOf("R2", "R1", "N", "E1", "E2")
    }
}

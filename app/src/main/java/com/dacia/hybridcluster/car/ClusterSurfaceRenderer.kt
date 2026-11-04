package com.dacia.hybridcluster.car

import android.graphics.*
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.dacia.hybridcluster.model.AppStrings
import com.dacia.hybridcluster.model.ConfigManager
import com.dacia.hybridcluster.model.DaciaFonts
import com.dacia.hybridcluster.model.StringsManager
import com.dacia.hybridcluster.model.StringsPl
import com.dacia.hybridcluster.model.TelemetryData
import com.dacia.hybridcluster.model.UiConfig
import com.dacia.hybridcluster.obd.ObdManager
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.max

class ClusterSurfaceRenderer : SurfaceCallback {

    private var renderJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Paleta kolorów - domyślnie głęboka czerń (#000000) dla idealnego wtopienia w ramkę zegarów
    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#171717")
        style = Paint.Style.FILL
    }
    private val cardHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.FILL
    }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#363636")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E0E0E")
        style = Paint.Style.FILL
    }

    private val textWhiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = DaciaFonts.blockBold
    }
    private val textLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        typeface = DaciaFonts.readMedium
    }

    // Kolory napędów
    private val iceColor = Color.parseColor("#EC6528")   // Pomarańcz (Silnik spalinowy ICE)
    private val rearColor = Color.parseColor("#B3CC23")  // Limonka Dacia (Tylny e-Motor e-AWD)
    private val hsgColor = Color.parseColor("#29B6F6")   // Błękit elektryczny (Przedni HSG / Starter-Generator)
    private val regenColor = Color.parseColor("#00E676") // Jasna zieleń (Rekuperacja)

    // Płynna animacja pasków 4x4 i mocy (zbieżność wykładnicza ~30 FPS)
    private var animFrontPct = 100f
    private var animIceKw = 0f
    private var animRearKw = 0f
    private var animHsgKw = 0f
    private var animTotalRegenKw = 0f
    private var animRearRegenKw = 0f
    private var animMaxDriveScale = 25f

    private var strings: AppStrings = StringsPl

    private fun approach(current: Float, target: Float, factor: Float = 0.28f): Float {
        val diff = target - current
        if (Math.abs(diff) < 0.02f) return target
        return current + diff * factor
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        android.util.Log.i("ClusterRenderer", "onSurfaceAvailable: valid=${surface.isValid}, dpi=${container.dpi}, w=${container.width}, h=${container.height}")
        startRenderLoop(surface)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        android.util.Log.i("ClusterRenderer", "onVisibleAreaChanged: $visibleArea")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        android.util.Log.i("ClusterRenderer", "onStableAreaChanged: $stableArea")
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        android.util.Log.i("ClusterRenderer", "onSurfaceDestroyed")
        stop()
    }

    fun stop() {
        renderJob?.cancel()
        renderJob = null
    }

    private fun startRenderLoop(surface: Surface) {
        stop()
        renderJob = scope.launch {
            while (isActive && surface.isValid) {
                var canvas: Canvas? = null
                try {
                    canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        try { surface.lockHardwareCanvas() } catch (_: Exception) { surface.lockCanvas(null) }
                    } else {
                        surface.lockCanvas(null)
                    }

                    if (canvas != null) {
                        drawFullCluster(canvas, canvas.width.toFloat(), canvas.height.toFloat())
                    }
                } catch (_: Exception) {
                } finally {
                    if (canvas != null) {
                        try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                    }
                }
                delay(33)
            }
        }
    }

    /**
     * Rysuje kokpit:
     * - W widoku wąskim 1/3 ekranu zegarów (proporcje zbliżone do 1:1): czysta siatka kafelków 2x2.
     * - W widoku szerokim 2/3 ekranu zegarów (proporcje panoramiczne): dodaje z lewej strony
     *   sportowy, cyfrowy obrotomierz silnika spalinowego z wizualizacją trybu EV i biegu!
     */
    private fun drawFullCluster(canvas: Canvas, w: Float, h: Float) {
        val config = ConfigManager.configState.value
        val data = ObdManager.telemetryState.value
        strings = StringsManager.get(config.appLanguage)
        val scale = config.uiScale.coerceIn(0.8f, 1.6f)

        // Płynna aktualizacja animowanych wartości pasków (zbieżność ~30 FPS)
        val isStandstill = data.speedKmH < 1.0
        val isRegen = !isStandstill && data.totalRegenPowerKw > 0.2 && data.speedKmH > 2.0

        val targetFrontPct = data.frontTorquePercent.toFloat().coerceIn(0f, 100f)
        animFrontPct = approach(animFrontPct, targetFrontPct, 0.28f)

        val targetTotalRegen = if (isRegen) data.totalRegenPowerKw.toFloat() else 0f
        val targetRearRegen = if (isRegen) data.rearRegenPowerKw.toFloat() else 0f
        animTotalRegenKw = approach(animTotalRegenKw, targetTotalRegen, 0.28f)
        animRearRegenKw = approach(animRearRegenKw, targetRearRegen, 0.28f)

        val targetIce = if (!isStandstill && !isRegen) data.icePowerKw.toFloat().coerceAtLeast(0f) else 0f
        val targetRear = if (!isStandstill && !isRegen) data.rearMotorPowerKw.toFloat().coerceAtLeast(0f) else 0f
        val targetHsg = if (!isStandstill && !isRegen) data.hsgPowerKw.toFloat().coerceAtLeast(0f) else 0f
        animIceKw = approach(animIceKw, targetIce, 0.28f)
        animRearKw = approach(animRearKw, targetRear, 0.28f)
        animHsgKw = approach(animHsgKw, targetHsg, 0.28f)

        val targetDriveScale = if (data.icePowerKw <= 0.2 && data.iceRpm < 300) 25f else 110f
        animMaxDriveScale = approach(animMaxDriveScale, targetDriveScale, 0.25f)

        // Odświeżenie fabrycznych czcionek Dacii (DaciaBlock & Read)
        textWhiteBold.typeface = DaciaFonts.blockBold
        textLabel.typeface = DaciaFonts.readMedium

        // Zastosowanie kolorów tła z konfiguracji (domyślnie czysta czerń #000000)
        try {
            bgPaint.color = Color.parseColor(config.clusterBgColorHex)
        } catch (_: Exception) {
            bgPaint.color = Color.BLACK
        }
        try {
            cardPaint.color = Color.parseColor(config.clusterCardColorHex)
        } catch (_: Exception) {
            cardPaint.color = Color.parseColor("#171717")
        }

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val isWide = (w / h) >= 1.25f
        if (isWide) {
            if (config.showTachometerInWide) {
                drawWideClusterWithTacho(canvas, w, h, data, config, scale)
            } else {
                drawWideClusterCardsOnly(canvas, w, h, data, config, scale)
            }
        } else {
            drawNarrowCluster(canvas, w, h, data, config, scale)
        }
    }

    /**
     * WIDOK WĄSKI (1/3 zegarów):
     * Wypełnia 100% wysokości i szerokości, układ 2x2 o dużych, czytelnych czcionkach.
     */
    private fun drawNarrowCluster(canvas: Canvas, w: Float, h: Float, data: TelemetryData, config: UiConfig, scale: Float) {
        val pad = (min(w, h) * 0.022f).coerceAtLeast(6f)
        val gap = (min(w, h) * 0.020f).coerceAtLeast(5f)
        val corner = 12f

        val weightHeader = 1.05f
        val weightPower = if (config.showMultiSegmentPower) 1.25f else 0f
        val weightGrid = when {
            config.showTemperatures && config.show12vBattery -> 1.75f
            config.showTemperatures || config.show12vBattery -> 0.95f
            else -> 0f
        }
        val weightTrip = if (config.showTripStats || config.showGForce) 0.95f else 0f

        val activeSections = listOf(weightHeader, weightPower, weightGrid, weightTrip).count { it > 0f }
        val totalWeight = weightHeader + weightPower + weightGrid + weightTrip
        val availableH = h - 2f * pad - (activeSections - 1) * gap

        var curY = pad
        val contentW = w - 2f * pad

        // 1. BIEG + TRYB + 4x4
        val headerH = availableH * (weightHeader / totalWeight)
        drawHeaderSection(canvas, pad, curY, contentW, headerH, corner, data, config, scale)
        curY += headerH + gap

        // 2. PASEK MOCY I REKUPERACJI
        if (weightPower > 0f) {
            val powerH = availableH * (weightPower / totalWeight)
            drawPowerSection(canvas, pad, curY, contentW, powerH, corner, data, scale)
            curY += powerH + gap
        }

        // 3. SIATKA 2x2 KAFELKÓW (TEMPERATURY + BATERIA + 12V)
        if (weightGrid > 0f) {
            val gridH = availableH * (weightGrid / totalWeight)
            drawTelemetryGrid(canvas, pad, curY, contentW, gridH, gap, corner, data, config, scale)
            curY += gridH + gap
        }

        // 4. STATYSTYKI PODRÓŻY
        if (weightTrip > 0f) {
            val tripH = availableH * (weightTrip / totalWeight)
            drawTripSection(canvas, pad, curY, contentW, tripH, corner, data, config, scale)
        }
    }

    /**
     * WIDOK SZEROKI: PEŁNY SPORTOWY (Obrotomierz RPM + Kafelki telemetrii)
     */
    private fun drawWideClusterWithTacho(canvas: Canvas, w: Float, h: Float, data: TelemetryData, config: UiConfig, scale: Float) {
        val corner = 14f

        // 1. PIONOWY OBSZAR (góra i dół regulowane suwakami)
        val topMargin = h * (config.fullTopMarginPercent / 100f).coerceIn(0.0f, 0.25f)
        val topY = (h * 0.012f + topMargin).coerceAtLeast(4f)
        val bottomMargin = h * (config.fullBottomMarginPercent / 100f).coerceIn(0.05f, 0.45f)
        val bottomY = (h - bottomMargin).coerceAtLeast(topY + 100f)
        val usableH = bottomY - topY

        val gap = (min(w, h) * 0.016f).coerceAtLeast(6f)

        // 2. POZIOMY OBSZAR:
        // Lewe okno (Obrotomierz): pozycja startu i szerokość regulowane suwakami
        val tachoStartRatio = (config.fullLeftOffsetPercent / 100f).coerceIn(0.05f, 0.50f)
        val tachoLeft = w * tachoStartRatio
        val tachoWidthRatio = (config.fullTachoWidthPercent / 100f).coerceIn(0.08f, 0.40f)
        val tachoW = w * tachoWidthRatio
        val tachoRight = tachoLeft + tachoW

        // Prawe okno (Kafelki telemetrii): prawy margines i szerokość regulowane suwakiem
        val rightMargin = w * (config.fullRightMarginPercent / 100f).coerceIn(0.02f, 0.45f)
        val rightX = tachoRight + gap
        val rightW = ((w - rightMargin) - rightX).coerceAtLeast(140f)

        // LEWA STRONA: OBROTOMIERZ
        drawTachometerSection(canvas, tachoLeft, topY, tachoW, usableH, corner, data, scale)

        // PRAWA STRONA: KAFELKI TELEMETRII
        drawWideCardsColumn(canvas, rightX, topY, rightW, usableH, gap, corner, data, config, scale)
    }

    /**
     * WIDOK SZEROKI: ZOPTYMALIZOWANY POD MENU I WIDŻETY AUTA (TYLKO KAFELKI)
     * Lewa strona pozostaje w 100% czarna (#000000) dla fabrycznego prędkościomierza i znaków.
     * Kafelki zajmują prawą stronę ekranu. Po wysunięciu prawego menu kafelki wsuwają się
     * bez zasłaniania czegokolwiek!
     */
    private fun drawWideClusterCardsOnly(canvas: Canvas, w: Float, h: Float, data: TelemetryData, config: UiConfig, scale: Float) {
        val corner = 14f

        val topMargin = h * (config.cardsOnlyTopMarginPercent / 100f).coerceIn(0.0f, 0.25f)
        val topY = (h * 0.012f + topMargin).coerceAtLeast(4f)
        val bottomMargin = h * (config.cardsOnlyBottomMarginPercent / 100f).coerceIn(0.05f, 0.45f)
        val bottomY = (h - bottomMargin).coerceAtLeast(topY + 100f)
        val usableH = bottomY - topY

        val gap = (min(w, h) * 0.016f).coerceAtLeast(6f)

        // Lewe okno (pusta czarna przestrzeń na fabryczny prędkościomierz auta)
        val leftX = w * (config.cardsOnlyLeftOffsetPercent / 100f).coerceIn(0.05f, 0.55f)

        // Prawe okno (Kafelki telemetrii)
        val rightMargin = w * (config.cardsOnlyRightMarginPercent / 100f).coerceIn(0.02f, 0.45f)
        val contentW = ((w - rightMargin) - leftX).coerceAtLeast(140f)

        drawWideCardsColumn(canvas, leftX, topY, contentW, usableH, gap, corner, data, config, scale)
    }

    /**
     * Wspólna kolumna kafelków telemetrii dla obu trybów szerokich:
     */
    private fun drawWideCardsColumn(
        canvas: Canvas,
        x: Float,
        topY: Float,
        w: Float,
        usableH: Float,
        gap: Float,
        corner: Float,
        data: TelemetryData,
        config: UiConfig,
        scale: Float
    ) {
        val hasPower = config.showMultiSegmentPower
        val hasGrid = config.showTemperatures || config.show12vBattery
        val hasTrip = config.showTripStats

        val sectionWeights = mutableListOf<Pair<String, Float>>()
        sectionWeights.add("header" to 1.0f)
        if (hasPower) sectionWeights.add("power" to 1.15f)
        if (hasGrid) sectionWeights.add("grid" to 2.35f)
        if (hasTrip) sectionWeights.add("trip" to 1.15f)

        val totalGaps = (sectionWeights.size - 1) * gap
        val netH = (usableH - totalGaps).coerceAtLeast(100f)
        val sumWeight = sectionWeights.sumOf { it.second.toDouble() }.toFloat()

        var curY = topY
        for ((sec, weight) in sectionWeights) {
            val secH = netH * (weight / sumWeight)
            when (sec) {
                "header" -> drawWideHeaderSection(canvas, x, curY, w, secH, corner, data, config, scale)
                "power" -> drawPowerSection(canvas, x, curY, w, secH, corner, data, scale)
                "grid" -> drawTelemetryGrid(canvas, x, curY, w, secH, gap, corner, data, config, scale)
                "trip" -> drawTripSection(canvas, x, curY, w, secH, corner, data, config, scale)
            }
            curY += secH + gap
        }
    }

    /**
     * Sportowy obrotomierz cyfrowy (Tachometer Gauge)
     */
    private fun drawTachometerSection(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        data: TelemetryData,
        scale: Float
    ) {
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val cx = rect.centerX()
        val cy = y + h * 0.42f
        val radius = (min(w * 0.42f, h * 0.34f)).coerceAtLeast(56f)
        val totalStroke = radius * 0.14f
        val halfStroke = totalStroke * 0.46f

        val outerRadius = radius + totalStroke * 0.25f
        val innerRadius = radius - totalStroke * 0.25f
        val outerArcRect = RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        val innerArcRect = RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

        val startAngle = 140f
        val totalSweep = 260f

        // 1. Zewnętrzna połówka paska: Obroty silnika spalinowego ICE (0..6000 obr/min)
        val outerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#222222")
            style = Paint.Style.STROKE
            strokeWidth = halfStroke
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(outerArcRect, startAngle, totalSweep, false, outerTrackPaint)

        // Czerwone pole powyżej 4500 RPM na zewnętrznym pasku ICE
        val redlineStart = startAngle + totalSweep * (4500f / 6000f)
        val redlineSweep = totalSweep * (1500f / 6000f)
        val redlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5A2410")
            style = Paint.Style.STROKE
            strokeWidth = halfStroke
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(outerArcRect, redlineStart, redlineSweep, false, redlinePaint)

        val rpm = data.iceRpm.coerceIn(0, 6000)
        val isEvMode = rpm == 0 || data.hybridMode == "PURE EV"
        val rpmRatio = (rpm / 6000f).coerceIn(0f, 1f)
        val activeSweepIce = (totalSweep * rpmRatio).coerceAtLeast(if (isEvMode) 0f else 3f)

        val arcColorIce = when {
            isEvMode -> Color.parseColor("#B3CC23")
            rpm > 4500 -> Color.parseColor("#FF5252")
            rpm > 3000 -> Color.parseColor("#EC6528")
            else -> Color.parseColor("#B3CC23")
        }
        val activePaintIce = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = arcColorIce
            style = Paint.Style.STROKE
            strokeWidth = halfStroke
            strokeCap = Paint.Cap.ROUND
        }
        if (activeSweepIce > 0f) {
            canvas.drawArc(outerArcRect, startAngle, activeSweepIce, false, activePaintIce)
        }

        // 2. Wewnętrzna połówka paska: Obroty silnika elektrycznego (niebieski jak w SYSTEM 48 MHEV)
        val innerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#121824")
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
            val elecPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#29B6F6") // Niebieski elektryczny SYSTEM 48 MHEV
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
        val iceGearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B3CC23")
            typeface = DaciaFonts.blockBold
            textSize = gearFontSize
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(data.currentGear, cx, cy - radius * 0.48f, iceGearPaint)

        // 2. Obroty silnika spalinowego (białe)
        val iceRpmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = DaciaFonts.blockBold
            textSize = rpmFontSize
            textAlign = Paint.Align.CENTER
        }
        val rpmText = if (isEvMode) "0" else "$rpm"
        canvas.drawText(rpmText, cx, cy - radius * 0.23f, iceRpmPaint)

        // 3. Jednostka "obr/min" pomiędzy obrotami ICE a obrotami EV
        val obrLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0E0E0")
            typeface = DaciaFonts.readMedium
            textSize = unitFontSize
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(strings.rpmUnit, cx, cy - radius * 0.02f, obrLabelPaint)

        // 4. Obroty silnika elektrycznego (niebieski elektryczny)
        val elecRpmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#29B6F6")
            typeface = DaciaFonts.blockBold
            textSize = rpmFontSize
            textAlign = Paint.Align.CENTER
        }
        val eRpmText = "$eRpm"
        canvas.drawText(eRpmText, cx, cy + radius * 0.22f, elecRpmPaint)

        // 5. Bieg silnika elektrycznego (niebieską czcionką dokładnie wielkości biegu spalinowego: R2, R1, N, E1, E2)
        val elecGearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#29B6F6")
            typeface = DaciaFonts.blockBold
            textSize = gearFontSize
            textAlign = Paint.Align.CENTER
        }
        val eGearDisplay = if (data.eGear in listOf("R2", "R1", "N", "E1", "E2")) data.eGear else "N"
        canvas.drawText(eGearDisplay, cx, cy + radius * 0.50f, elecGearPaint)

        // 4. Dolna część karty pod obrotomierzem:
        // - Wiersz 1: DOTRYSK PALIWA (w kolorze tła karty gdy brak, pomarańczowy #EC6528 gdy aktywny)
        // - Kolejne wiersze (pod DOTRYSK PALIWA): Powody blokady EV (jeden pod drugim gdy jest ich więcej,
        //   w kolorze tła karty gdy brak blokady, pomarańczowe #EC6528 gdy aktywne)
        val inhibitLines = data.startStopInhibitReason
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val statusFontSize = (h * (if (inhibitLines.size >= 3) 0.048f else 0.056f) * scale).coerceIn(13f, 22f)
        val activeOrangeColor = Color.parseColor("#EC6528")
        val inactiveBgColor = cardPaint.color

        val boostActive = data.fuelInjectionBoostActive
        val boostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
            val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
            val activeInhibitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
                    strings.translateReason(visibleLines[i]),
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
    private fun drawWideHeaderSection(
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
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val modeColor = when (data.hybridMode) {
            "E-AWD BOOST" -> Color.parseColor("#FF5252")
            "PURE EV" -> Color.parseColor("#B3CC23")
            "REKUPERACJA" -> Color.parseColor("#B3CC23")
            else -> Color.parseColor("#EC6528")
        }
        val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = modeColor
            typeface = DaciaFonts.readMedium
        }
        val titleSize = (h * 0.36f * scale).coerceIn(12f, 22f)
        val halfW = (w - 32f) * 0.46f // Bezpieczna szerokość połówki zapobiegająca nachodzeniu na prawy tekst

        drawFittedText(
            canvas, "${strings.modePrefix}${strings.translateMode(data.hybridMode)}",
            x + 14f, y + h * 0.44f,
            halfW, titleSize, modePaint, Paint.Align.LEFT
        )

        val splitText = "4x4: ${data.frontTorquePercent}% ${strings.frontLabel} • ${data.rearTorquePercent}% ${strings.rearLabel}"
        val splitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        val barRect = RectF(x + 14f, barTop, x + w - 14f, barBottom)
        canvas.drawRoundRect(barRect, 6f, 6f, barTrackPaint)

        val frontW = barRect.width() * (animFrontPct / 100f)
        canvas.drawRect(barRect.left, barTop, barRect.left + frontW, barBottom, Paint().apply { color = iceColor })
        canvas.drawRect(barRect.left + frontW, barTop, barRect.right, barBottom, Paint().apply { color = rearColor })
        canvas.drawRoundRect(barRect, 6f, 6f, cardStrokePaint)
    }

    private fun drawHeaderSection(
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
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        // 1. Kafelek Biegu po lewej (bardzo duży i czytelny)
        val gearBoxW = (h * 1.25f).coerceAtMost(w * 0.30f)
        val gearRect = RectF(x + 6f, y + 6f, x + gearBoxW, y + h - 6f)
        canvas.drawRoundRect(gearRect, corner - 3f, corner - 3f, cardHighlightPaint)

        val gearLabelSize = (h * 0.19f * scale).coerceIn(11f, 22f)
        drawFittedText(
            canvas, strings.gearLabelShort,
            gearRect.centerX(), gearRect.top + gearLabelSize + 4f,
            gearRect.width() - 8f, gearLabelSize, textLabel, Paint.Align.CENTER
        )

        val gearValSize = (h * 0.52f * scale).coerceIn(22f, 64f)
        val gearColorPaint = Paint(textWhiteBold).apply { color = Color.parseColor("#B3CC23") }
        drawFittedText(
            canvas, data.currentGear,
            gearRect.centerX(), gearRect.bottom - (h * 0.10f),
            gearRect.width() - 8f, gearValSize, gearColorPaint, Paint.Align.CENTER
        )

        // 2. Prawa część: Tryb Hybrydowy na górze + 4x4 i Prędkość na dole
        val rightX = gearRect.right + 12f
        val rightW = (x + w) - rightX - 10f

        val modeColor = when (data.hybridMode) {
            "E-AWD BOOST" -> Color.parseColor("#FF5252")
            "PURE EV" -> Color.parseColor("#B3CC23")
            "REKUPERACJA" -> Color.parseColor("#B3CC23")
            else -> Color.parseColor("#EC6528")
        }
        val modePaint = Paint(textWhiteBold).apply { color = modeColor }
        val modeSize = (h * 0.36f * scale).coerceIn(16f, 44f)
        val modeStr = strings.translateMode(data.hybridMode)

        if (config.showTorqueSplit) {
            drawFittedText(
                canvas, modeStr,
                rightX, y + h * 0.44f,
                rightW, modeSize, modePaint, Paint.Align.LEFT
            )

            // Pasek i tekst 4x4 pod trybem
            val subSize = (h * 0.25f * scale).coerceIn(13f, 28f)
            val splitText = "4x4 ${strings.frontLabel} ${data.frontTorquePercent}%  •  ${strings.rearLabel} ${data.rearTorquePercent}%"
            drawFittedText(
                canvas, splitText,
                rightX, y + h * 0.82f,
                rightW, subSize, textWhiteBold, Paint.Align.LEFT
            )
        } else {
            // Sam tryb wyśrodkowany w pionie
            drawFittedText(
                canvas, modeStr,
                rightX, y + h * 0.62f,
                rightW, modeSize * 1.15f, modePaint, Paint.Align.LEFT
            )
        }
    }

    private fun fmtPwr(kw: Double): String {
        val absKw = Math.abs(kw)
        return when {
            absKw < 0.05 -> "0"
            absKw < 9.95 -> String.format(java.util.Locale.US, "%.1f", absKw)
            else -> "${Math.round(absKw)}"
        }
    }

    private fun drawPowerSection(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        data: TelemetryData,
        scale: Float
    ) {
        val rect = RectF(x, y, x + w, y + h)
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
            data.hybridMode == "PURE EV" -> Color.parseColor("#B3CC23")
            data.hybridMode == "E-AWD BOOST" -> Color.parseColor("#FF5252")
            else -> Color.WHITE
        }
        val mainPowerPaint = Paint(textWhiteBold).apply { color = mainPowerColor }
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
            data.hybridMode == "PURE EV" -> {
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
        val barRect = RectF(x + innerPad, barTop, x + w - innerPad, barBottom)
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
                canvas.drawRect(midX - rearRegenW, barTop, midX, barBottom, Paint().apply { color = regenColor })
            }
            if (regenW > rearRegenW + 0.5f) {
                canvas.drawRect(midX - regenW, barTop, midX - rearRegenW, barBottom, Paint().apply { color = hsgColor })
            }
        } else if (!isStandstill && (animIceKw + animRearKw + animHsgKw) > 0.1f) {
            // 2. Oddawanie mocy (w prawo od środka) - płynna animacja ~30 FPS
            val driveScale = animMaxDriveScale.coerceAtLeast(20f)
            var startX = midX
            val iceW = ((animIceKw / driveScale) * halfW).coerceAtMost(halfW)
            if (iceW > 0.5f) {
                canvas.drawRect(startX, barTop, startX + iceW, barBottom, Paint().apply { color = iceColor })
                startX += iceW
            }

            val rearW = ((animRearKw / driveScale) * halfW).coerceAtMost(halfW - (startX - midX))
            if (rearW > 0.5f) {
                canvas.drawRect(startX, barTop, startX + rearW, barBottom, Paint().apply { color = rearColor })
                startX += rearW
            }

            val hsgW = ((animHsgKw / driveScale) * halfW).coerceAtMost(halfW - (startX - midX))
            if (hsgW > 0.5f) {
                canvas.drawRect(startX, barTop, startX + hsgW, barBottom, Paint().apply { color = hsgColor })
            }
        }

        // Obrys paska + biała linia środkowa "0 kW"
        canvas.drawRoundRect(barRect, 8f, 8f, cardStrokePaint)
        val zeroPaint = Paint().apply { color = Color.WHITE; strokeWidth = 3f }
        canvas.drawLine(midX, barTop - 4f, midX, barBottom + 4f, zeroPaint)
    }

    private fun drawTelemetryGrid(
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

    private fun drawMergedTemperaturesTile(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        data: TelemetryData,
        scale: Float
    ) {
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val segW = w / 4f
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22283A")
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
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private fun drawBatteryCards(
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
        val v48 = if (data.hvBatteryVoltage > 20.0) data.hvBatteryVoltage else (if (data.hsgVoltage48v > 20.0) data.hsgVoltage48v else 0.0)
        val soc48Val = if (data.hvBatterySocPercent in 5.0..100.0) data.hvBatterySocPercent.toInt() else 0
        val color48 = Color.parseColor("#29B6F6") // Błękit elektryczny
        val redDischargeColor = Color.parseColor("#FF5252") // Czerwony dla spadku/rozładowania

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
        val color12 = Color.parseColor("#B3CC23") // Limonkowy / zielony Dacia
        val isCharging12 = data.bat12vVoltage >= 13.0 || data.bat12vNetChargeWatts > 0
        val (arrow12, arrow12Color) = when {
            data.bat12vVoltage <= 5.0 || config.show12vWattsMode -> Pair("", color12)
            isCharging12 -> Pair("↑", color12)
            else -> Pair("↓", redDischargeColor)
        }

        val val12Base = if (config.show12vWattsMode) {
            val totalWatts = data.bat12vPowerWatts.toInt()
            val loadWatts = data.bat12vLoadWatts.toInt()
            if (data.bat12vVoltage > 5.0) {
                if (totalWatts > 0) {
                    "${totalWatts}W  •  ${strings.autoLoadLabel}${loadWatts}W"
                } else {
                    "${loadWatts}W  •  ${strings.dischargeLabel}${loadWatts}W"
                }
            } else {
                "--W  •  --W"
            }
        } else {
            val dispSoc12 = if (data.bat12vSocPercent in 10..100) {
                data.bat12vSocPercent
            } else if (data.bat12vVoltage >= 13.0) {
                82
            } else if (data.bat12vVoltage > 5.0) {
                75
            } else {
                0
            }
            val soc12Part = if (dispSoc12 > 0) "$dispSoc12%" else "--%"
            if (data.bat12vVoltage > 5.0) "${String.format(java.util.Locale.US, "%.1f", data.bat12vVoltage)}V  •  $soc12Part" else "--V  •  --%"
        }

        // --- Obliczenie WSPÓLNEGO, IDENTYCZNEGO rozmiaru czcionki dla OBU kafelków ---
        val maxValW = colW - 24f
        val maxLabelW = colW - 24f

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B0B0B0")
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

        val testValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private fun drawCustomMetricTile(
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
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val centerX = x + w / 2f

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B0B0B0")
            typeface = DaciaFonts.readMedium
            textSize = labelSize
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(label, centerX, y + h * 0.35f, labelPaint)

        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
            val arrowPaint = Paint(valPaint).apply {
                color = arrowColor
            }
            canvas.drawText(arrowStr, startX + valueWidth, textY, arrowPaint)
        }
    }

    private fun fmtTemp(t: Double): String = if (t <= -100.0) "--" else "${t.toInt()}°C"

    private fun drawMetricTile(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        label: String,
        value: String,
        valueColor: Int,
        scale: Float,
        subValue: String? = null,
        subValueColor: Int = Color.parseColor("#8E99A4")
    ) {
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B0B0B0")
            typeface = DaciaFonts.readMedium
        }

        if (subValue != null) {
            val labelSize = (h * 0.20f * scale).coerceIn(9f, 15f)
            drawFittedText(
                canvas, label,
                x + 12f, y + h * 0.28f,
                w - 24f, labelSize, labelPaint, Paint.Align.LEFT
            )

            val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = valueColor
                typeface = DaciaFonts.blockRegular
            }
            val valSize = (h * 0.32f * scale).coerceIn(13f, 26f)
            drawFittedText(
                canvas, value,
                x + 12f, y + h * 0.62f,
                w - 24f, valSize, valPaint, Paint.Align.LEFT
            )

            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = subValueColor
                typeface = DaciaFonts.readMedium
            }
            val subSize = (h * 0.20f * scale).coerceIn(9f, 14f)
            drawFittedText(
                canvas, subValue,
                x + 12f, y + h * 0.88f,
                w - 24f, subSize, subPaint, Paint.Align.LEFT
            )
        } else {
            val labelSize = (h * 0.24f * scale).coerceIn(10f, 19f)
            drawFittedText(
                canvas, label,
                x + 12f, y + h * 0.35f,
                w - 24f, labelSize, labelPaint, Paint.Align.LEFT
            )

            val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = valueColor
                typeface = DaciaFonts.blockRegular
            }
            val valSize = (h * 0.38f * scale).coerceIn(14f, 32f)
            drawFittedText(
                canvas, value,
                x + 12f, y + h * 0.79f,
                w - 24f, valSize, valPaint, Paint.Align.LEFT
            )
        }
    }

    private fun drawTripSection(
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
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val colW = w / 3f

        // Subtelne pionowe linie rozdzielające kolumny
        val divPaint = Paint().apply {
            color = Color.parseColor("#262626")
            strokeWidth = 1.5f
        }
        canvas.drawLine(x + colW, y + 8f, x + colW, y + h - 8f, divPaint)
        canvas.drawLine(x + 2f * colW, y + 8f, x + 2f * colW, y + h - 8f, divPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B0B0B0")
            typeface = DaciaFonts.readMedium
        }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B3CC23")
            typeface = DaciaFonts.blockRegular
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8E99A4")
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
            val testLblPaint = Paint(labelPaint).apply { textSize = labelSize }
            val maxLblMeasured = max(testLblPaint.measureText(lbl1), max(testLblPaint.measureText(lbl2), testLblPaint.measureText(lbl3)))
            val unifiedLabelSize = if (maxLblMeasured > maxColW && maxColW > 0f) {
                (labelSize * (maxColW / maxLblMeasured)).coerceAtLeast(8f)
            } else labelSize

            // Obliczenie wspólnego, identycznego rozmiaru czcionki dla wartości (wszystkie 3 kolumny mają ten sam rozmiar)
            val testValPaint = Paint(valPaint).apply { textSize = valSize }
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
            val testLblPaint = Paint(labelPaint).apply { textSize = labelSize }
            val maxLblMeasured = max(testLblPaint.measureText(lbl1), max(testLblPaint.measureText(lbl2), testLblPaint.measureText(lbl3)))
            val unifiedLabelSize = if (maxLblMeasured > maxColW && maxColW > 0f) {
                (labelSize * (maxColW / maxLblMeasured)).coerceAtLeast(8f)
            } else labelSize

            // Obliczenie wspólnego, identycznego rozmiaru czcionki dla wartości (wszystkie 3 kolumny mają ten sam rozmiar)
            val testValPaint = Paint(valPaint).apply { textSize = valSize }
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
    private fun drawFittedText(
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
        val paint = Paint(basePaint)
        paint.textAlign = align
        paint.textSize = desiredSize

        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            paint.textSize = (desiredSize * (maxWidth / measured)).coerceAtLeast(8f)
        }
        canvas.drawText(text, x, y, paint)
    }
}

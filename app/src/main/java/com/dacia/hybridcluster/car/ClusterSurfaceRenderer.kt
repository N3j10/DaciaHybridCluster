package com.dacia.hybridcluster.car

import android.graphics.*
import com.dacia.hybridcluster.model.DaciaFonts
import com.dacia.hybridcluster.model.TelemetryData
import com.dacia.hybridcluster.model.UiConfig
import kotlin.math.min
import kotlin.math.max

/**
 * Renderer ekranu zegarów (klaster). Wspólne sekcje są w [TelemetryRendererBase].
 */
class ClusterSurfaceRenderer : TelemetryRendererBase("ClusterRenderer") {

    /**
     * Rysuje kokpit:
     * - W widoku wąskim 1/3 ekranu zegarów (proporcje zbliżone do 1:1): siatka kafelków.
     * - W widoku szerokim 2/3 ekranu zegarów: z lewej obrotomierz silnika spalinowego i biegi.
     * - Opcjonalnie (clusterUseHostSafeArea) rysuje tylko w obszarze bezpiecznym zgłoszonym przez host.
     */
    override fun drawFrame(canvas: Canvas, data: TelemetryData, config: UiConfig) {
        val scale = config.uiScale.coerceIn(0.8f, 1.6f)
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint)

        val area = if (config.clusterUseHostSafeArea) hostSafeArea() else null
        if (area != null) {
            canvas.save()
            canvas.translate(area.left.toFloat(), area.top.toFloat())
            canvas.clipRect(0f, 0f, area.width().toFloat(), area.height().toFloat())
        }
        val w = area?.width()?.toFloat() ?: canvas.width.toFloat()
        val h = area?.height()?.toFloat() ?: canvas.height.toFloat()

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
        if (area != null) canvas.restore()
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
        val rect = rf(300, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        // 1. Kafelek Biegu po lewej (bardzo duży i czytelny)
        val gearBoxW = (h * 1.25f).coerceAtMost(w * 0.30f)
        val gearRect = rf(301, x + 6f, y + 6f, x + gearBoxW, y + h - 6f)
        canvas.drawRoundRect(gearRect, corner - 3f, corner - 3f, cardHighlightPaint)

        val gearLabelSize = (h * 0.19f * scale).coerceIn(11f, 22f)
        drawFittedText(
            canvas, strings.gearLabelShort,
            gearRect.centerX(), gearRect.top + gearLabelSize + 4f,
            gearRect.width() - 8f, gearLabelSize, textLabel, Paint.Align.CENTER
        )

        val gearValSize = (h * 0.52f * scale).coerceIn(22f, 64f)
        val gearColorPaint = spCopy(302, textWhiteBold).apply { color = 0xFFB3CC23.toInt() }
        drawFittedText(
            canvas, data.currentGear,
            gearRect.centerX(), gearRect.bottom - (h * 0.10f),
            gearRect.width() - 8f, gearValSize, gearColorPaint, Paint.Align.CENTER
        )

        // 2. Prawa część: Tryb Hybrydowy na górze + 4x4 i Prędkość na dole
        val rightX = gearRect.right + 12f
        val rightW = (x + w) - rightX - 10f

        val modeColor = modeColor(data.hybridMode)
        val modePaint = spCopy(303, textWhiteBold).apply { color = modeColor }
        val modeSize = (h * 0.36f * scale).coerceIn(16f, 44f)
        val modeStr = strings.modeName(data.hybridMode)

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
        subValueColor: Int = 0xFF8E99A4.toInt()
    ) {
        val rect = rf(304, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val labelPaint = sp(305).apply {
            color = 0xFFB0B0B0.toInt()
            typeface = DaciaFonts.readMedium
        }

        if (subValue != null) {
            val labelSize = (h * 0.20f * scale).coerceIn(9f, 15f)
            drawFittedText(
                canvas, label,
                x + 12f, y + h * 0.28f,
                w - 24f, labelSize, labelPaint, Paint.Align.LEFT
            )

            val valPaint = sp(306).apply {
                color = valueColor
                typeface = DaciaFonts.blockRegular
            }
            val valSize = (h * 0.32f * scale).coerceIn(13f, 26f)
            drawFittedText(
                canvas, value,
                x + 12f, y + h * 0.62f,
                w - 24f, valSize, valPaint, Paint.Align.LEFT
            )

            val subPaint = sp(307).apply {
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

            val valPaint = sp(308).apply {
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
}

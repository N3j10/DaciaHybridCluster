package com.dacia.hybridcluster.car

import android.graphics.*
import com.dacia.hybridcluster.model.DaciaFonts
import com.dacia.hybridcluster.model.TelemetryData
import com.dacia.hybridcluster.model.UiConfig
import kotlin.math.max
import kotlin.math.min

/**
 * Renderer powierzchni (Surface) dla ekranu środkowego Infotainment (Android Auto DISPLAY_TYPE_MAIN).
 *
 * - Wyświetla te same sekcje co zegary ([TelemetryRendererBase]),
 * - Posiada niezależną kalibrację (pełny ekran z obrotomierzem vs podzielony ekran tylko z kafelkami).
 */
class InfotainmentSurfaceRenderer : TelemetryRendererBase("InfoRenderer") {

    var onBackClicked: (() -> Unit)? = null

    var onSurfaceTapped: (() -> Unit)? = null

    override fun onClick(x: Float, y: Float) {
        // Kliknięcie w dowolne miejsce ekranu -> powrót do pełnego ekranu (fullscreen) w Android Auto
        onSurfaceTapped?.invoke()
    }

    private val fullBounds = Rect()

    override fun drawFrame(canvas: Canvas, data: TelemetryData, config: UiConfig) {
        val scale = config.infoUiScale.coerceIn(0.7f, 1.8f)
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint)

        fullBounds.set(0, 0, canvas.width, canvas.height)
        val bounds = hostSafeArea() ?: fullBounds

        // Rozróżnienie pełnego ekranu Android Auto vs podzielonego (np. Coolwalk obok YT Music)
        val isCoolwalkSplit = (bounds.width().toFloat() / bounds.height().toFloat()) < 1.35f

        canvas.save()
        canvas.clipRect(bounds)
        if (!isCoolwalkSplit) {
            drawWideInfotainmentWithTacho(canvas, bounds, data, config, scale)
        } else {
            drawSplitInfotainmentCardsOnly(canvas, bounds, data, config, scale)
        }
        canvas.restore()
    }

    /**
     * PEŁNY EKRAN INFOTAINMENT: Obrotomierz po lewej + Kolumna kafelków po prawej
     */
    private fun drawWideInfotainmentWithTacho(
        canvas: Canvas,
        bounds: Rect,
        data: TelemetryData,
        config: UiConfig,
        scale: Float
    ) {
        val corner = 14f
        val areaW = bounds.width().toFloat()
        val areaH = bounds.height().toFloat()

        val topMargin = areaH * (config.infoFullTopMarginPercent / 100f).coerceIn(0.00f, 0.25f)
        val topY = (bounds.top + topMargin + 8f * scale).coerceAtLeast(bounds.top + 4f)
        val bottomMargin = areaH * (config.infoFullBottomMarginPercent / 100f).coerceIn(0.01f, 0.25f)
        val bottomY = (bounds.bottom - bottomMargin).coerceAtLeast(topY + 100f)
        val usableH = bottomY - topY

        val gap = (min(areaW, areaH) * 0.016f).coerceAtLeast(6f)

        // Obrotomierz (lewe okno)
        val tachoLeftMargin = areaW * (config.infoFullLeftOffsetPercent / 100f).coerceIn(0.01f, 0.30f)
        val tachoLeft = bounds.left + tachoLeftMargin
        val tachoWidthRatio = (config.infoFullTachoWidthPercent / 100f).coerceIn(0.12f, 0.35f)
        val tachoW = areaW * tachoWidthRatio
        val tachoRight = tachoLeft + tachoW

        // Kafelki (prawe okno)
        val rightMargin = areaW * (config.infoFullRightMarginPercent / 100f).coerceIn(0.01f, 0.30f)
        val rightX = tachoRight + gap
        val rightW = ((bounds.right - rightMargin) - rightX).coerceAtLeast(140f)

        drawTachometerSection(canvas, tachoLeft, topY, tachoW, usableH, corner, data, scale)
        drawCardsColumn(canvas, rightX, topY, rightW, usableH, gap, corner, data, config, scale, isSplit = false)
    }

    /**
     * PODZIELONY EKRAN INFOTAINMENT: Tylko kolumna kafelków (bez obrotomierza)
     */
    private fun drawSplitInfotainmentCardsOnly(
        canvas: Canvas,
        bounds: Rect,
        data: TelemetryData,
        config: UiConfig,
        scale: Float
    ) {
        val corner = 14f
        val areaW = bounds.width().toFloat()
        val areaH = bounds.height().toFloat()

        val topMargin = areaH * (config.infoSplitTopMarginPercent / 100f).coerceIn(0.00f, 0.25f)
        val topY = (bounds.top + topMargin + 8f * scale).coerceAtLeast(bounds.top + 4f)
        val bottomMargin = areaH * (config.infoSplitBottomMarginPercent / 100f).coerceIn(0.01f, 0.25f)
        val bottomY = (bounds.bottom - bottomMargin).coerceAtLeast(topY + 100f)
        val usableH = bottomY - topY

        val gap = (min(areaW, areaH) * 0.016f).coerceAtLeast(6f)

        val leftMargin = areaW * (config.infoSplitLeftOffsetPercent / 100f).coerceIn(0.01f, 0.30f)
        val rightMargin = areaW * (config.infoSplitRightMarginPercent / 100f).coerceIn(0.01f, 0.30f)
        val contentX = bounds.left + leftMargin
        val contentW = ((bounds.right - rightMargin) - contentX).coerceAtLeast(140f)

        drawCardsColumn(canvas, contentX, topY, contentW, usableH, gap, corner, data, config, scale, isSplit = true)
    }

    private fun drawCardsColumn(
        canvas: Canvas,
        x: Float,
        topY: Float,
        w: Float,
        usableH: Float,
        gap: Float,
        corner: Float,
        data: TelemetryData,
        config: UiConfig,
        scale: Float,
        isSplit: Boolean = false
    ) {
        val hasPower = config.showMultiSegmentPower
        val hasGrid = config.showTemperatures || config.show12vBattery
        val hasTrip = config.showTripStats

        val sectionWeights = mutableListOf<Pair<String, Float>>()
        sectionWeights.add("header" to (if (isSplit) 1.30f else 1.0f))
        if (hasPower) sectionWeights.add("power" to 1.15f)
        if (hasGrid) sectionWeights.add("grid" to (if (isSplit) 2.25f else 2.35f))
        if (hasTrip) sectionWeights.add("trip" to 1.15f)

        val totalGaps = (sectionWeights.size - 1) * gap
        val netH = (usableH - totalGaps).coerceAtLeast(100f)
        val sumWeight = sectionWeights.sumOf { it.second.toDouble() }.toFloat()

        var curY = topY
        for ((sec, weight) in sectionWeights) {
            val secH = netH * (weight / sumWeight)
            when (sec) {
                "header" -> {
                    if (isSplit) {
                        drawSplitHeaderSection(canvas, x, curY, w, secH, corner, data, config, scale)
                    } else {
                        drawWideHeaderSection(canvas, x, curY, w, secH, corner, data, config, scale)
                    }
                }
                "power" -> drawPowerSection(canvas, x, curY, w, secH, corner, data, scale)
                "grid" -> drawTelemetryGrid(canvas, x, curY, w, secH, gap, corner, data, config, scale)
                "trip" -> drawTripSection(canvas, x, curY, w, secH, corner, data, config, scale)
            }
            curY += secH + gap
        }
    }

    /**
     * NAGŁÓWEK DLA PODZIELONEGO EKRANU INFOTAINMENT:
     * Ponieważ w trybie podzielonym obrotomierz jest schowany, ten nagłówek zawiera:
     * - Po lewej: duży, czytelny kafelek z biegiem spalinowym (oraz biegiem elektrycznym eGear),
     * - Po prawej: aktywny tryb jazdy hybrydowej (TRYB) oraz rozdział napędu 4x4 z dwukolorowym paskiem!
     */
    private fun drawSplitHeaderSection(
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
        val rect = rf(400, x, y, x + w, y + h)
        canvas.drawRoundRect(rect, corner, corner, cardPaint)
        canvas.drawRoundRect(rect, corner, corner, cardStrokePaint)

        val hasElecGear = data.eGear in ERAD_GEARS

        // 1. Kafelek Biegu po lewej stronie (poszerzony i dynamicznie skalowany, by biegi mogły być duże)
        val gearBoxW = (h * (if (hasElecGear) 1.35f else 1.25f) * (scale * 0.95f).coerceAtLeast(1.0f))
            .coerceAtMost(w * 0.35f)
            .coerceAtLeast(70f)
        val gearRect = rf(401, x + 6f, y + 6f, x + gearBoxW, y + h - 6f)
        canvas.drawRoundRect(gearRect, corner - 3f, corner - 3f, cardHighlightPaint)

        val gearLabelSize = (h * 0.13f * scale).coerceIn(9f, 13f)
        drawFittedText(
            canvas, strings.gearLabelShort,
            gearRect.centerX(), gearRect.top + gearLabelSize + 3f,
            gearRect.width() - 6f, gearLabelSize, textLabel, Paint.Align.CENTER
        )

        val maxValW = gearRect.width() - 8f

        if (hasElecGear) {
            // OBA BIEGI MAJĄ IDENTYCZNĄ WIELKOŚĆ CZCIONKI ZMNIEJSZONĄ DO WIELKOŚCI CZCIONKI MOCY (ok. 22-26px):
            val unifiedGearSize = (h * 0.25f * scale).coerceIn(14f, 26f)

            val iceColorPaint = spCopy(402, textWhiteBold).apply { color = 0xFFB3CC23.toInt() }
            val elecColorPaint = spCopy(403, textWhiteBold).apply { color = 0xFF29B6F6.toInt() }

            // Pozycjonowanie pionowe z zachowaniem bezpiecznego i estetycznego odstępu pod napisem BIEG:
            val d1Y = gearRect.top + gearLabelSize + 5f + unifiedGearSize * 0.95f
            val e1Y = d1Y + unifiedGearSize * 1.15f

            drawFittedText(
                canvas, data.currentGear,
                gearRect.centerX(), d1Y,
                maxValW, unifiedGearSize, iceColorPaint, Paint.Align.CENTER
            )
            drawFittedText(
                canvas, data.eGear,
                gearRect.centerX(), e1Y,
                maxValW, unifiedGearSize, elecColorPaint, Paint.Align.CENTER
            )
        } else {
            // Pojedynczy bieg (np. P, N, D) również dopasowany do skali wielkości mocy:
            val singleGearSize = (h * 0.30f * scale).coerceIn(16f, 30f)
            val gearColorPaint = spCopy(404, textWhiteBold).apply { color = 0xFFB3CC23.toInt() }
            drawFittedText(
                canvas, data.currentGear,
                gearRect.centerX(), gearRect.bottom - (h * 0.16f),
                maxValW, singleGearSize, gearColorPaint, Paint.Align.CENTER
            )
        }

        // 2. Prawa część: Tryb hybrydowy i 4x4
        val rightX = gearRect.right + 10f
        val rightW = (x + w) - rightX - 10f

        val modeColor = modeColor(data.hybridMode)
        val modePaint = sp(405).apply {
            color = modeColor
            typeface = DaciaFonts.readMedium
        }
        val titleSize = (h * 0.32f * scale).coerceIn(12f, 22f)

        drawFittedText(
            canvas, "${strings.modePrefix}${strings.modeName(data.hybridMode)}",
            rightX, y + h * 0.36f,
            rightW * 0.54f, titleSize, modePaint, Paint.Align.LEFT
        )

        val splitText = "${data.frontTorquePercent}% ${strings.frontLabel} • ${data.rearTorquePercent}% ${strings.rearLabel}"
        val splitPaint = sp(406).apply {
            color = Color.WHITE
            typeface = DaciaFonts.readMedium
        }
        val splitSize = (h * 0.28f * scale).coerceIn(11f, 18f)
        drawFittedText(
            canvas, "4x4: $splitText",
            x + w - 10f, y + h * 0.36f,
            rightW * 0.42f, splitSize, splitPaint, Paint.Align.RIGHT
        )

        // Pasek 4x4 (płynna animacja)
        val barTop = y + h * 0.64f
        val barBottom = y + h * 0.84f
        val barRect = rf(407, rightX, barTop, x + w - 10f, barBottom)
        canvas.drawRoundRect(barRect, 5f, 5f, barTrackPaint)

        val frontW = barRect.width() * (animFrontPct / 100f)
        canvas.drawRect(barRect.left, barTop, barRect.left + frontW, barBottom, spPlain(408).apply { color = iceColor })
        canvas.drawRect(barRect.left + frontW, barTop, barRect.right, barBottom, spPlain(409).apply { color = rearColor })
        canvas.drawRoundRect(barRect, 5f, 5f, cardStrokePaint)
    }
}

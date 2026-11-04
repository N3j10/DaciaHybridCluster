package com.dacia.hybridcluster.model

import android.content.Context
import android.graphics.Typeface

object DaciaFonts {
    var blockBold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private set
    var blockRegular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private set
    var readMedium: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private set
    var readLight: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private set

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        try {
            blockBold = Typeface.createFromAsset(context.assets, "fonts/DaciaBlock-Bold.ttf")
        } catch (_: Exception) {}
        try {
            blockRegular = Typeface.createFromAsset(context.assets, "fonts/DaciaBlock-Regular.ttf")
        } catch (_: Exception) {}
        try {
            readMedium = Typeface.createFromAsset(context.assets, "fonts/DaciaRead-Medium.ttf")
        } catch (_: Exception) {}
        try {
            readLight = Typeface.createFromAsset(context.assets, "fonts/DaciaRead-Light.ttf")
        } catch (_: Exception) {}
        isInitialized = true
    }
}

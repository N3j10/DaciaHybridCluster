package com.dacia.hybridcluster

import android.app.Application
import com.dacia.hybridcluster.model.ConfigManager
import com.dacia.hybridcluster.model.DaciaFonts
import com.dacia.hybridcluster.obd.ObdLogger
import com.dacia.hybridcluster.obd.ObdManager

/**
 * Inicjalizacja globalnego stanu w momencie startu procesu.
 *
 * Wcześniej ConfigManager był inicjalizowany tylko w MainActivity. Gdy Android Auto uruchamiał
 * proces sam (telefon w kieszeni, bez otwierania aplikacji), działała domyślna konfiguracja:
 * brak zapamiętanego adaptera = brak automatycznego połączenia i brak kalibracji zegarów.
 */
class DaciaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ObdLogger.init(this)
        ConfigManager.init(this)
        DaciaFonts.init(this)
        ObdManager.init(this)
    }
}

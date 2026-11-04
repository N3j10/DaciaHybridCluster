package com.dacia.hybridcluster.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dacia.hybridcluster.obd.ObdManager

/**
 * Sesja ekranu zegarów (klastra). Host tworzy ją dopiero, gdy główna sesja
 * zgłosi aktywną nawigację (navigationStarted + updateTrip).
 */
class ClusterCarSession : Session() {

    private val renderer = ClusterSurfaceRenderer()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                renderer.stop()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        com.dacia.hybridcluster.model.DaciaFonts.init(carContext)
        ObdManager.start()
        // Surface klastra jest dostarczana TEJ sesji, nie sesji ekranu środkowego
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        return ClusterScreen(carContext)
    }
}

/**
 * Na klastrze dozwolony jest wyłącznie NavigationTemplate.
 * Sam szablon jest pusty – całą treść rysuje ClusterSurfaceRenderer na Surface pod spodem.
 */
class ClusterScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("OBD").setOnClickListener { }.build())
                    .build()
            )
            .build()
    }
}

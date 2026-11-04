package com.dacia.hybridcluster.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class HybridCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    /**
     * Android Auto tworzy OSOBNĄ sesję dla każdego ekranu:
     *  - DISPLAY_TYPE_MAIN    -> ekran środkowy (pogoda + sygnał aktywnej trasy)
     *  - DISPLAY_TYPE_CLUSTER -> cyfrowe zegary (Surface z telemetrią)
     */
    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return if (sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER) {
            ClusterCarSession()
        } else {
            HybridCarSession()
        }
    }
}

package com.buildsession.betterYAMF.manager.services

import android.service.quicksettings.TileService
import com.buildsession.betterYAMF.manager.core.gateway.WindowGateway

class QSResetAllWindow: TileService() {
    override fun onClick() {
        WindowGateway.resetAllWindow()
    }
}

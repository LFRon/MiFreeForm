package com.buildsession.betterYAMF.manager.broadcastreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buildsession.betterYAMF.application
import com.buildsession.betterYAMF.manager.core.settings.SettingsRepository
import com.buildsession.betterYAMF.manager.sidebar.SidebarLauncher

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (SettingsRepository.load().launchSideBarAtBoot) {
                SidebarLauncher.start(application)
            }
        }
    }
}

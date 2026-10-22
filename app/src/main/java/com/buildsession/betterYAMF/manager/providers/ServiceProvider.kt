package com.buildsession.betterYAMF.manager.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import android.os.Process
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy

class ServiceProvider: ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ) = null

    override fun getType(uri: Uri) = null

    override fun insert(uri: Uri, values: ContentValues?) = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ) = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (Binder.getCallingUid() != Process.SYSTEM_UID || callingPackage != "android" || extras == null) return null
        val binder = extras.getBinder("binder") ?: return null
        if (!YAMFManagerProxy.linkService(binder)) return null
        return Bundle()
    }
}

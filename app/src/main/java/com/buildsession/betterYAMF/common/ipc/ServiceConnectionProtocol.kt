package com.buildsession.betterYAMF.common.ipc

/** Requests the existing app service for settings/window commands; never checks Hook scopes. */
object ServiceConnectionProtocol {
    const val REQUEST = "com.buildsession.betterYAMF.action.REQUEST_APP_SERVICE"
}

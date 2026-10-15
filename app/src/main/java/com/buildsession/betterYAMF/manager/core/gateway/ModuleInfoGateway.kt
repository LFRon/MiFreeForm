package com.buildsession.betterYAMF.manager.core.gateway

import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy

/** 模块信息门面（逻辑层）：版本/构建时间等只读诊断信息。 */
object ModuleInfoGateway {

    val versionName: String? get() = YAMFManagerProxy.versionName

    val versionCode: Int get() = YAMFManagerProxy.versionCode

    val buildTime: Long get() = YAMFManagerProxy.buildTime
}

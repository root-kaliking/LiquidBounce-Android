/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.integration.backend

import com.mojang.blaze3d.systems.RenderSystem
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.BrowserReadyEvent
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.integration.backend.backends.cef.CefBrowserBackend
import net.ccbluex.liquidbounce.integration.backend.backends.external.ExternalSystemBrowserBackend
import net.ccbluex.liquidbounce.integration.backend.backends.minecraft.MinecraftBrowserBackend
import net.ccbluex.liquidbounce.integration.backend.browser.GlobalBrowserSettings
import net.ccbluex.liquidbounce.integration.interop.persistant.PersistentLocalStorage
import net.ccbluex.liquidbounce.integration.task.TaskManager
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.env
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY

val browserBackend = env("LB_BROWSER_BACKEND", "net.ccbluex.liquidbounce.browser.backend")
    ?: if (isMobileArm64()) "minecraft" else "cef"
var isBrowserDisabled = env("LB_BROWSER_SKIP", "net.ccbluex.liquidbounce.browser.skip")?.toBoolean()
    ?: false

/**
 * Detects the target where the provided CEF binaries are unusable.
 *
 * On Android (os.name == "Linux" here, since FCL runs a desktop JRE) the JVM reports
 * `os.arch == aarch64`. The client then would select LINUX_ARM64 and try to load
 * `linux_arm64/libcef.so` - but upstream only publishes an x86-64 artifact for that slot
 * (LiquidBounce #8412), producing `UnsatisfiedLinkError: can't load AMD 64 .so on a
 * AARCH64 platform`. Since there is no usable ARM64 CEF build, any ARM64 Linux is routed
 * to the native-free [MinecraftBrowserBackend] instead. Desktop x86-64 keeps using CEF.
 */
internal fun isMobileArm64(): Boolean {
    val arch = System.getProperty("os.arch")?.lowercase(java.util.Locale.ENGLISH).orEmpty()
    return arch.contains("aarch64") || arch.contains("arm64")
}
val isBrowserAccelerationDisabled = env("LB_BROWSER_DISABLE_ACCELERATION",
    "net.ccbluex.liquidbounce.browser.disableAcceleration")?.toBoolean() ?: false

object BrowserBackendManager : EventListener {

    private val logger = clientLogger("BrowserBackendManager")

    val isInitialized: Boolean
        get() = backend?.isInitialized ?: false
    var backend: BrowserBackend? = null

    fun init() {
        PersistentLocalStorage
    }

    /**
     * Makes the browser dependencies available and initializes the browser
     * when the dependencies are available.
     */
    fun makeDependenciesAvailable(taskManager: TaskManager) {
        if (isBrowserDisabled) {
            logger.warn("Environment variable 'LB_BROWSER_SKIP' is set to 'true'.")
            return
        }

        val browserBackend = when (browserBackend) {
            "none" -> {
                logger.warn("Environment variable 'LB_BROWSER_BACKEND' is set to 'none'.")
                isBrowserDisabled = true
                return
            }
            "cef" -> CefBrowserBackend()
            "minecraft" -> MinecraftBrowserBackend()
            "external" -> ExternalSystemBrowserBackend()
            else -> error("Unknown browser backend: $browserBackend")
        }
        this.backend = browserBackend
        browserBackend.makeDependenciesAvailable(taskManager, ::start)
    }

    /**
     * Initializes the browser.
     */
    fun start() {
        // Ensure that the browser is available
        logger.info("Initializing browser...")

        // Ensure that the browser is started on the render thread
        RenderSystem.assertOnRenderThread()

        val browserBackend = backend ?: return
        browserBackend.start()

        if (isBrowserAccelerationDisabled) {
            logger.warn("Environment variable 'LB_BROWSER_DISABLE_ACCELERATION' is set to 'true'.")
        }
        GlobalBrowserSettings
        EventManager.callEvent(BrowserReadyEvent)
        logger.info("Successfully initialized browser.")
    }

    /**
     * Shuts down the browser.
     */
    fun stop() = runCatching {
        backend?.stop()
    }.onFailure {
        logger.error("Failed to shutdown browser.", it)
    }.onSuccess {
        logger.info("Successfully shutdown browser.")
    }

    /**
     * Causes an update of every browser by re-setting their viewport.
     */
    fun forceUpdate() = mc.execute {
        val browserBackend = backend ?: return@execute

        for (browser in browserBackend.browsers) {
            try {
                browser.viewport = browser.viewport
            } catch (e: Exception) {
                logger.error("Failed to update tab of '${browser.url}'", e)
            }
        }
    }

    @Suppress("unused")
    private val gameRenderHandler = handler<GameRenderEvent>(priority = FIRST_PRIORITY) {
        val browserBackend = backend ?: return@handler
        if (!browserBackend.isInitialized) {
            return@handler
        }

        browserBackend.update()
    }

}

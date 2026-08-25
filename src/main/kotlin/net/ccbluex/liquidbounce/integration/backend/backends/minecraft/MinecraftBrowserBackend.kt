/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Mobile / ARM64 port additions.
 * Copyright (c) 2026
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
package net.ccbluex.liquidbounce.integration.backend.backends.minecraft

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.integration.backend.BrowserAccelerationFlags
import net.ccbluex.liquidbounce.integration.backend.BrowserBackend
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.task.TaskManager
import net.ccbluex.liquidbounce.utils.kotlin.sortedInsert

/**
 * A browser backend that requires no external native libraries like JCEF/MCEF.
 *
 * The CEF (Chromium) backend is not available on ARM64 / Android, since the official
 * java-cef/release pipeline only publishes an x86-64 `linux_arm64` artifact and CCBlueX
 * explicitly does not support Android. This backend is used instead on such platforms:
 *
 *  - It never touches JCEF/MCEF, so it can never fail with
 *    `UnsatisfiedLinkError: can't load AMD 64 .so on a AARCH64 platform`.
 *  - The page is handed over to the OS (which on Android means the default browser),
 *    mirroring the existing `ExternalSystemBrowser`. Additionally it provides a minimal
 *    on-screen placeholder so the client does not render an empty viewport.
 *
 * @author LiquidBounce port <minecraft-browser-backend>
 */
@Suppress("TooManyFunctions")
class MinecraftBrowserBackend : BrowserBackend, EventListener {

    override val isInitialized: Boolean = true
    override var browsers = mutableListOf<MinecraftBrowser>()
    override var accelerationFlags = BrowserAccelerationFlags.UNSUPPORTED

    override val supportsIncognito = false

    override fun makeDependenciesAvailable(taskManager: TaskManager, whenAvailable: () -> Unit) {
        // No external dependencies (no native libs, no downloads). We can start immediately.
        whenAvailable()
    }

    @Suppress("EmptyFunctionBlock")
    override fun start() {
    }

    @Suppress("EmptyFunctionBlock")
    override fun stop() {
    }

    @Suppress("EmptyFunctionBlock")
    override fun update() {
    }

    override fun createBrowser(
        url: String,
        position: BrowserViewport,
        settings: BrowserSettings,
        priority: Short,
        incognito: Boolean,
        inputAcceptor: InputAcceptor?
    ) = MinecraftBrowser(this, url, position, settings, priority, incognito, inputAcceptor)
        .apply(::addBrowser)

    private fun addBrowser(browser: MinecraftBrowser) {
        browsers.sortedInsert(browser, MinecraftBrowser::priority)
    }

    internal fun removeBrowser(browser: MinecraftBrowser) {
        browsers.remove(browser)
    }

}
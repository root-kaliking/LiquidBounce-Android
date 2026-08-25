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

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.integration.backend.BrowserTexture
import net.ccbluex.liquidbounce.integration.backend.browser.Browser
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserRenderer
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserState
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.backend.input.InputHandler
import net.ccbluex.liquidbounce.integration.backend.input.InputListener
import net.ccbluex.liquidbounce.utils.client.browseUrl
import net.ccbluex.liquidbounce.utils.client.clientLogger
import org.apache.logging.log4j.Logger

/**
 * A [Browser] implementation that needs no Chromium/CEF native libraries.
 *
 * The CEF backend is unusable on ARM64 / Android because the official `linux_arm64`
 * artifact is actually an x86-64 ELF binary (see LiquidBounce issue #8412) and native
 * linux/X11 acceleration is not available. This implementation intentionally keeps the
 * page out-of-process (handed to the OS browser) so the client never attempts to load a
 * `.so` that would throw `UnsatisfiedLinkError`.
 *
 * Currently no texture is produced, so [BrowserRenderer] renders nothing on its own -
 * matches the "worse is usable" placeholder philosophy of the upstream PojavLauncher port.
 * It tracks URL & history for later integration and opens the URL externally by default.
 *
 * @author LiquidBounce port <minecraft-browser>
 */
@Suppress("TooManyFunctions")
class MinecraftBrowser(
    private val backend: MinecraftBrowserBackend,
    url: String,
    override var viewport: BrowserViewport,
    val settings: BrowserSettings,
    override var priority: Short = 0,
    override val isIncognito: Boolean = false,
    inputAcceptor: InputAcceptor? = null
) : Browser, InputHandler, MinecraftShortcuts {

    private val logger: Logger = clientLogger("MinecraftBrowser/$url")

    /** Whether the OS browser should be opened for every visited URL. */
    private val openExternally = System.getProperty("liquidbounce.mobile.openUrlExternally") != "false"

    /** Minimal navigation history, so back/forward don't silently no-op. */
    private val history: MutableList<String> = mutableListOf(url)
    private var historyIndex = 0

    init {
        require(url.isNotEmpty()) { "URL cannot be empty." }
        logger.info("Initializing browser (url='$url')")
        if (openExternally) {
            browseUrl(url)
        }
    }

    override var isInitialized: Boolean = true

    // This backend never has a real "page load": the URL is handed to the OS
    // browser immediately, so there is nothing to wait on. Report a completed
    // state right away, otherwise [ScreenManager.waitUntilInitialized] waits
    // its full 30s timeout and throws a fatal error on the render thread.
    override var state: BrowserState = BrowserState.Success(200)

    override var url: String = url
        set(value) {
            if (field == value) {
                return
            }
            field = value
            state = BrowserState.Success(200)

            // Truncate any forward history before pushing the new entry.
            while (history.size - 1 > historyIndex) {
                history.removeAt(history.lastIndex)
            }
            history.add(value)
            historyIndex = history.lastIndex

            if (openExternally) {
                browseUrl(value)
            }
        }

    override var visible = true

    private val renderer = BrowserRenderer(this)
    private val inputListener: InputListener? = inputAcceptor?.let { _ ->
        InputListener(this, this, inputAcceptor)
    }

    /**
     * No CPU/GPU frame is produced by this backend. Returning null keeps
     * [BrowserRenderer] from trying to draw a missing texture.
     */
    override val texture: BrowserTexture? = null

    override fun forceReload() = reload()

    @Suppress("EmptyFunctionBlock")
    override fun reload() {
    }

    override fun goForward() {
        if (historyIndex < history.size - 1) {
            historyIndex++
            url = history[historyIndex]
        }
    }

    override fun goBack() {
        if (historyIndex > 0) {
            historyIndex--
            url = history[historyIndex]
        }
    }

    override fun close() {
        renderer.close()
        inputListener?.close()
        backend.removeBrowser(this)
    }

    override fun update(width: Int, height: Int) {
        if (!viewport.fullScreen) {
            return
        }

        viewport = viewport.copy(width = width, height = height)
    }

    @Suppress("EmptyFunctionBlock")
    override fun invalidate() {
    }

    override fun toString() = "MinecraftBrowser(" +
        "url='$url', " +
        "incognito=$isIncognito, " +
        "visible=$visible, " +
        "priority=$priority" +
        ")"

    // No DOM to forward input to; log at debug for troubleshooting.
    override fun mouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int) {
        logger.debug("mouseClicked ($mouseX,$mouseY) button=$mouseButton")
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, mouseButton: Int) {
        logger.debug("mouseReleased ($mouseX,$mouseY) button=$mouseButton")
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        // no-op
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double) {
        logger.debug("mouseScrolled ($mouseX,$mouseY) delta=$delta")
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int) {
        logger.debug("keyPressed $keyCode")
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int) {
        logger.debug("keyReleased $keyCode")
    }

    override fun charTyped(codepoint: Int) {
        logger.debug("charTyped '${codepoint.toChar()}'")
    }
}

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

package net.ccbluex.liquidbounce.integration.screen.impl

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.AbstractFontRenderer.DrawParameters.horizontalAnchor
import net.ccbluex.liquidbounce.render.AbstractFontRenderer.DrawParameters.scale
import net.ccbluex.liquidbounce.render.AbstractFontRenderer.DrawParameters.x
import net.ccbluex.liquidbounce.render.AbstractFontRenderer.DrawParameters.y
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawHorizontalLine
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawVerticalLine
import net.ccbluex.liquidbounce.render.engine.font.HorizontalAnchor
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native, in-game replication of the LiquidBounce Web ClickGUI.
 *
 * The Web (Svelte) ClickGUI is served by the integration backend and rendered into a
 * browser texture, but the CEF backend is unavailable on ARM64 / Android so no texture
 * is produced. This screen redraws the same layout, palette and animated behaviour
 * directly with the Minecraft renderer. Values are taken 1:1 from
 * `src-theme/src/colors.scss`, `TabbedClickGui.svelte`, `Panel.svelte`, `Module.svelte`,
 * `Search.svelte` and `Switch.svelte` so it looks and feels like the browser version.
 */
@Suppress("TooManyFunctions", "MagicNumber", "LongMethod", "MaxLineLength", "LargeClass")
class NativeClickGuiScreen : Screen(
    "Native ClickGUI".asPlainText()
) {

    // ---- Foundation palette (src-theme/src/colors.scss) ----
    private val accent = Color4b.fromHex("#4677ff")
    private val textColor = Color4b.WHITE
    private val textDimmed = Color4b.fromHex("#d3d3d3")

    // ---- ClickGUI palette (base-N = black at N% opacity) ----
    private val base50 = Color4b.BLACK.with(a = 128)
    private val base70 = Color4b.BLACK.with(a = 178)
    private val base80 = Color4b.BLACK.with(a = 204)
    private val base85 = Color4b.BLACK.with(a = 217)
    private val base90 = Color4b.BLACK.with(a = 230)

    private val panelHeaderBg = base90
    private val panelHeaderBorder = accent
    private val panelBodyBg = base80
    private val panelShadow = base50
    private val moduleHoverBg = base85
    private val moduleSettingsBg = base50

    private val searchBg = base90
    private val searchBorder = accent

    private val tabsBg = base85
    private val tabActiveBg = accent.with(a = 31)
    private val tabActiveBorder = accent

    private val overlayBg = Color4b.BLACK.with(a = 153)

    private val switchTrack = Color4b.fromHex("#737373")
    private val switchTrackActive = Color4b.fromHex("#1c3766")
    private val switchThumb = Color4b.WHITE
    private val switchThumbActive = accent

    private val sliderTrack = Color4b.fromHex("#333333")
    private val sliderFill = accent

    // ---- Layout (from the Svelte source) ----
    private val panelWidth = 250f
    private val headerHeight = 38f
    private val moduleRowHeight = 34f
    private val settingRowHeight = 30f
    private val panelMaxBodyHeight = 545f

    private val font = FontManager.FONT_RENDERER
    private val vanillaScale = font.scaleToVanillaFont

    // ---- Animation ----
    private var lastTickNanos = 0L
    private val panelBodyAnim = HashMap<ModuleCategory, Animated>()
    private val expandIconAnim = HashMap<ModuleCategory, Animated>()
    private val switchAnim = HashMap<String, Animated>()

    private class Animated(private val speed: Float) {
        var value = 0f
            private set

        fun advance(delta: Float, target: Float) {
            value += (target - value) * min(1f, delta * speed)
        }
    }

    // ---- State ----
    private class PanelConfig(
        var left: Float,
        var top: Float,
        var expanded: Boolean,
        var scroll: Float = 0f,
    )

    private val panels = HashMap<ModuleCategory, PanelConfig>()
    private val expandedModules = HashSet<String>()

    private class SliderDrag(val value: Value<*>, val trackX1: Float, val trackX2: Float)
    private var draggingSlider: SliderDrag? = null

    private var dragCategory: ModuleCategory? = null
    private var dragOffset: Pair<Double, Double>? = null

    // ---- Search ----
    private var searchText = ""
    private var searchFocused = false
    private val searchResults = ArrayList<ClientModule>()

    private fun rebuildSearch() {
        searchResults.clear()
        val query = searchText.trim().lowercase().replace(" ", "")
        if (query.isEmpty()) {
            return
        }
        for (module in ModuleManager) {
            if (module.name == ModuleClickGui.name) {
                continue
            }
            if (module.name.lowercase().replace(" ", "").contains(query)) {
                searchResults += module
            }
        }
    }

    // ---- Hit testing ----
    private sealed interface Action {
        data class TogglePanel(val category: ModuleCategory) : Action
        data class ToggleModule(val module: ClientModule) : Action
        data class ExpandModule(val module: ClientModule) : Action
        data class ToggleBool(val value: Value<*>) : Action
        data class CycleMode(val group: ModeValueGroup<*>) : Action
        data class StartSlider(val value: Value<*>, val x1: Float, val x2: Float) : Action
        data class ToggleGroup(val value: ToggleableValueGroup) : Action
    }

    private class Region(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val action: Action?,
    ) {
        fun contains(px: Float, py: Float): Boolean = px >= x0 && px <= x1 && py >= y0 && py <= y1
    }

    private val regions = ArrayList<Region>()

    private fun guiW(): Float = mc.window.guiScaledWidth.toFloat()
    private fun guiH(): Float = mc.window.guiScaledHeight.toFloat()

    // ================= Rendering =================

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        tickAnimations()
        ensurePanels()
        regions.clear()
        // Keep the pointer in sync from the render pass too: on touch inputs
        // mouseMoved may not fire while dragging, but the render pass always does.
        updatePointer(mouseX.toFloat(), mouseY.toFloat())
        with(g) {
            renderScreen(guiW(), guiH(), mouseX.toFloat(), mouseY.toFloat())
        }
    }

    override fun shouldCloseOnEsc(): Boolean = true
    override fun isPauseScreen(): Boolean = false

    private fun tickAnimations() {
        val now = System.nanoTime()
        val dt = if (lastTickNanos == 0L) 1f / 60f else (now - lastTickNanos) / 1_000_000_000f
        lastTickNanos = now

        for ((category, config) in panels) {
            panelBodyAnim[category]?.advance(dt, if (config.expanded) 1f else 0f)
            expandIconAnim[category]?.advance(dt, if (config.expanded) 1f else 0f)
        }
        for ((key, anim) in switchAnim) {
            anim.advance(dt, if (isSwitchActive(key)) 1f else 0f)
        }
    }

    private fun isSwitchActive(key: String): Boolean {
        val name = key.substringBefore('#')
        val valueName = key.substringAfter('#')
        val module = ModuleManager.getModuleByName(name) ?: return false
        val value = module.containedValues.find { it.name == valueName } ?: return false
        @Suppress("UNCHECKED_CAST")
        return (value as? Value<Boolean>)?.get() ?: (value is ToggleableValueGroup && value.enabled)
    }

    private fun ensurePanels() {
        if (panels.isNotEmpty()) {
            return
        }
        ModuleCategories.entries
            .filter { it.tag != "ClickGUI" }
            .forEachIndexed { index, category ->
                panels[category] = PanelConfig(left = 20f, top = index * 50 + 20, expanded = false)
                panelBodyAnim[category] = Animated(6f)
                expandIconAnim[category] = Animated(5f)
            }
    }

    private fun modulesFor(category: ModuleCategory): List<ClientModule> =
        ModuleManager.filter { it.category == category && it.name != ModuleClickGui.name }.sortedBy { it.name }

    private fun GuiGraphicsExtractor.renderScreen(w: Float, h: Float, mx: Float, my: Float) {
        drawQuad(0f, 0f, w, h, overlayBg)
        renderTabs(w)
        renderSearch(w)
        for (category in panels.keys.sortedBy { it.tag }) {
            renderPanel(category, panels[category] ?: continue, mx, my)
        }
    }

    // ---- Tabs ----

    private fun GuiGraphicsExtractor.renderTabs(w: Float) {
        val titles = listOf("ClickGUI", "HUD Editor", "Settings")
        val buttonW = 120f
        val height = 40f
        val gap = 5f
        val totalW = titles.size * buttonW + (titles.size - 1) * gap
        val startX = (w - totalW) / 2f

        drawRoundedRect(startX - 6f, 9f, startX + totalW + 6f, 9f + height + 6f, height, tabsBg, null, 0f)

        var cx = startX
        titles.forEachIndexed { index, title ->
            val x1 = cx
            val x2 = cx + buttonW
            if (index == 0) {
                drawRoundedRect(x1, 15f, x2, 15f + height, height / 2f, tabActiveBg, tabActiveBorder, 1f)
            }
            font.draw(title.asPlainText()) {
                horizontalAnchor = HorizontalAnchor.CENTER
                this.x = (x1 + x2) / 2f
                this.y = 15f + (height - font.height * vanillaScale) / 2f
                scale = vanillaScale
            }
            cx += buttonW + gap
        }
        regions += Region(startX - 6f, 9f, startX + totalW + 6f, 9f + height + 6f, null)
    }

    // ---- Search ----

    private fun GuiGraphicsExtractor.renderSearch(w: Float) {
        val hasResults = searchResults.isNotEmpty() || searchFocused
        val sw = min(600f, w - 20f)
        val x1 = (w - sw) / 2f
        val x2 = (w + sw) / 2f
        val y1 = 70f
        val y2 = y1 + 50f

        val radius = if (hasResults) 10f else 30f
        drawRoundedRect(x1, y1, x2, y2, radius, searchBg, null, 0f)

        val placeholder = searchText.isEmpty()
        font.draw((if (placeholder) "Search" else searchText).asPlainText()) {
            this.x = x1 + 25f
            this.y = y1 + (50f - font.height * vanillaScale) / 2f
            scale = vanillaScale
        }

        if (!placeholder) {
            drawHorizontalLine(x1, x2, y2, 2f, searchBorder)
            drawResults(x1, y2, x2)
        }

        regions += Region(x1, y1, x2, y2, null)
    }

    private fun GuiGraphicsExtractor.drawResults(x1: Float, y1: Float, x2: Float) {
        val listHeight = 250f
        drawQuad(x1, y1, x2, y1 + listHeight, searchBg)
        if (searchResults.isEmpty()) {
            font.draw("No modules found".asPlainText()) {
                this.x = x1 + 25f
                this.y = y1 + 7f
                scale = vanillaScale
            }
            return
        }
        var y = y1 + 5f
        val lineH = 30f
        for (module in searchResults) {
            if (y + lineH > y1 + listHeight) {
                break
            }
            font.draw(module.name.asPlainText()) {
                this.x = x1 + 25f
                this.y = y + 7f
                scale = vanillaScale
            }
            regions += Region(x1, y, x2, y + lineH, Action.ToggleModule(module))
            y += lineH
        }
    }

    // ---- Panels ----

    private fun GuiGraphicsExtractor.renderPanel(category: ModuleCategory, config: PanelConfig, mx: Float, my: Float) {
        val x = config.left
        val y = config.top
        val right = x + panelWidth

        // Soft shadow rim (approximates box-shadow 0 0 10px).
        drawQuad(x - 6f, y - 6f, right + 6f, y + headerHeight + 6f + panelBodyAnim[category]!!.value * panelMaxBodyHeight, panelShadow.with(a = 40))

        // Header
        drawRoundedRect(x, y, right, y + headerHeight, 5f, panelHeaderBg, null, 0f)
        drawHorizontalLine(x, right, y + headerHeight, 2f, panelHeaderBorder)

        font.draw(category.tag.asPlainText()) {
            horizontalAnchor = HorizontalAnchor.CENTER
            this.x = x + panelWidth / 2f
            this.y = y + (headerHeight - font.height * vanillaScale) / 2f
            scale = vanillaScale * 0.9f
        }

        // Expand toggle (plus icon that rotates 90 deg).
        val plusCX = right - 22f
        val plusCY = y + headerHeight / 2f
        val spinDegrees = expandIconAnim[category]!!.value
        drawQuad(plusCX - 5f, plusCY - spinDegrees * 5f, plusCX + 5f, plusCY + spinDegrees * 5f, textColor.with(a = 220))
        drawQuad(plusCX - spinDegrees * 5f, plusCY - 1f, plusCX + spinDegrees * 5f, plusCY + 1f, textColor.with(a = 220))

        // Header body = draggable (no action); the plus button toggles the panel.
        regions += Region(x, y, right - 44f, y + headerHeight, null)
        regions += Region(right - 44f, y, right, y + headerHeight, Action.TogglePanel(category))

        // Body (animated max-height ~ Panel.svelte transition max-height 300ms ease)
        val bodyProgress = panelBodyAnim[category]!!.value
        if (bodyProgress <= 0.01f) {
            return
        }
        val bodyTop = y + headerHeight
        val bodyMaxHeight = panelMaxBodyHeight * bodyProgress
        val bodyBottom = bodyTop + bodyMaxHeight
        drawQuad(x, bodyTop, right, bodyBottom, panelBodyBg)

        scissorStack.withPush(getBounds(x, bodyTop, panelWidth, bodyMaxHeight)) {
            var curY = bodyTop + 8f - config.scroll
            for (module in modulesFor(category)) {
                curY = renderModule(category, module, x, curY, right, mx, my)
                if (curY > bodyBottom + 60f) {
                    break
                }
            }
        }

        if (config.scroll > 0f) {
            drawVerticalLine(right - 2f, bodyTop + 2f, bodyBottom - 2f, 2f, textColor.with(a = 36))
        }
        regions += Region(x, bodyTop, right, bodyBottom, null)
    }

    private fun GuiGraphicsExtractor.renderModule(category: ModuleCategory, module: ClientModule, x: Float, y: Float, panelRight: Float, mx: Float, my: Float): Float {
        val rowBottom = y + moduleRowHeight
        val hover = mx >= x && mx <= panelRight && my >= y && my <= rowBottom

        if (hover) {
            drawQuad(x, y, panelRight, rowBottom, moduleHoverBg)
        }

        font.draw(module.name.asPlainText()) {
            horizontalAnchor = HorizontalAnchor.CENTER
            this.x = (x + panelRight) / 2f
            this.y = y + (moduleRowHeight - font.height * vanillaScale) / 2f
            scale = vanillaScale
        }

        // Expand settings gear (right; its click does not toggle the module).
        if (module.containedValues.any(::isRelevantSetting)) {
            drawSettingsGear(panelRight - 24f, y + moduleRowHeight / 2f, isExpanded = module.name in expandedModules)
            regions += Region(panelRight - 48f, y, panelRight, rowBottom, Action.ExpandModule(module))
        }

        regions += Region(x, y, panelRight - 48f, rowBottom, Action.ToggleModule(module))

        var nextY = rowBottom
        if (module.name in expandedModules) {
            nextY = renderSettings(category, module, x, nextY, panelRight, module.containedValues.toList(), 0)
            drawQuad(x, rowBottom, rightSettingsEdge(panelRight), nextY, moduleSettingsBg)
            drawVerticalLine(x, nextY, nextY, 0f, Color4b.TRANSPARENT)
            drawQuad(x, rowBottom, x + 4f, nextY, accent)
        }
        return nextY
    }

    private fun rightSettingsEdge(panelRight: Float): Float = panelRight

    private fun GuiGraphicsExtractor.drawSettingsGear(cx: Float, cy: Float, isExpanded: Boolean) {
        val c = if (isExpanded) textColor else textDimmed.with(a = 128)
        drawQuad(cx - 3f, cy - 1f, cx + 3f, cy + 1f, c)
        drawQuad(cx - 1f, cy - 3f, cx + 1f, cy + 3f, c)
    }

    private fun isRelevantSetting(value: Value<*>): Boolean = value.name !in setOf("Enabled", "Hidden", "Bind")

    private fun GuiGraphicsExtractor.renderSettings(
        category: ModuleCategory,
        module: ClientModule,
        x: Float,
        y: Float,
        panelRight: Float,
        values: List<Value<*>>,
        depth: Int,
    ): Float {
        var curY = y
        for (value in values) {
            if (!isRelevantSetting(value)) {
                continue
            }
            curY = renderSetting(category, module, value, x, curY, panelRight, depth)
        }
        return curY
    }

    private fun GuiGraphicsExtractor.renderSetting(
        category: ModuleCategory,
        module: ClientModule,
        value: Value<*>,
        x: Float,
        y: Float,
        panelRight: Float,
        depth: Int,
    ): Float {
        val indent = x + 7f + depth * 12f
        val rowBottom = y + settingRowHeight

        when (value) {
            is ToggleableValueGroup -> {
                drawQuad(x, y, panelRight, rowBottom, moduleSettingsBg)
                font.draw(value.name.asPlainText()) {
                    this.x = indent
                    this.y = y + 8f
                    scale = vanillaScale * 0.85f
                }
                drawSwitch("${module.name}#${value.name}", panelRight - 38f, y + settingRowHeight / 2f)
                regions += Region(x, y, panelRight, rowBottom, Action.ToggleGroup(value))
                if (value.enabled) {
                    return renderSettings(category, module, x, rowBottom, panelRight, value.containedValues.toList(), depth + 1)
                }
                return rowBottom
            }

            is ModeValueGroup<*> -> {
                drawQuad(x, y, panelRight, rowBottom, moduleSettingsBg)
                font.draw(value.name.asPlainText()) {
                    this.x = indent
                    this.y = y + 8f
                    scale = vanillaScale * 0.85f
                }
                font.draw(value.activeMode.name.asPlainText()) {
                    horizontalAnchor = HorizontalAnchor.END
                    this.x = panelRight - 14f
                    this.y = y + 8f
                    scale = vanillaScale * 0.75f
                }
                regions += Region(panelRight - 48f, y, panelRight, rowBottom, Action.CycleMode(value))
                return rowBottom
            }
        }

        return when (value.valueType) {
            ValueType.BOOLEAN -> {
                drawQuad(x, y, panelRight, rowBottom, moduleSettingsBg)
                font.draw(value.name.asPlainText()) {
                    this.x = indent
                    this.y = y + 8f
                    scale = vanillaScale * 0.85f
                }
                drawSwitch("${module.name}#${value.name}", panelRight - 38f, y + settingRowHeight / 2f)
                regions += Region(x, y, panelRight, rowBottom, Action.ToggleBool(value))
                rowBottom
            }

            ValueType.FLOAT, ValueType.INT -> {
                drawQuad(x, y, panelRight, rowBottom, moduleSettingsBg)
                font.draw(value.name.asPlainText()) {
                    this.x = indent
                    this.y = y + 2f
                    scale = vanillaScale * 0.8f
                }
                font.draw(formatScalar(value).asPlainText()) {
                    horizontalAnchor = HorizontalAnchor.END
                    this.x = panelRight - 14f
                    this.y = y + 2f
                    scale = vanillaScale * 0.75f
                }
                val trackX1 = indent
                val trackX2 = panelRight - 14f
                val trackY = y + settingRowHeight - 9f
                drawQuad(trackX1, trackY, trackX2, trackY + 3f, sliderTrack)
                val ratio = scalarRatio(value).coerceIn(0f, 1f)
                val fillX2 = trackX1 + (trackX2 - trackX1) * ratio
                drawQuad(trackX1, trackY, fillX2, trackY + 3f, sliderFill)
                drawQuad(fillX2 - 3f, trackY - 2f, fillX2 + 3f, trackY + 5f, sliderFill)
                regions += Region(trackX1, trackY - 4f, trackX2, trackY + 7f, Action.StartSlider(value, trackX1, trackX2))
                rowBottom
            }

            else -> {
                drawQuad(x, y, panelRight, rowBottom, moduleSettingsBg)
                font.draw(value.name.asPlainText()) {
                    this.x = indent
                    this.y = y + 8f
                    scale = vanillaScale * 0.85f
                }
                val display = displayValue(value)
                if (display.isNotBlank()) {
                    font.draw(display.asPlainText()) {
                        horizontalAnchor = HorizontalAnchor.END
                        this.x = panelRight - 14f
                        this.y = y + 8f
                        scale = vanillaScale * 0.75f
                    }
                }
                rowBottom
            }
        }
    }

    // ---- Widgets ----

    private fun GuiGraphicsExtractor.drawSwitch(key: String, centerX: Float, centerY: Float) {
        val w = 22f
        val h = 12f
        switchAnim.getOrPut(key) { Animated(7f) }
        val progress = switchAnim[key]!!.value
        val trackColor = switchTrack.interpolateTo(switchTrackActive, progress)
        drawQuad(centerX, centerY - 4f, centerX + w, centerY + 4f, trackColor)
        val thumb = switchThumb.interpolateTo(switchThumbActive, progress)
        val thumbCX = centerX + 6f + (w - 12f) * progress
        drawQuad(thumbCX - 6f, centerY - 6f, thumbCX + 6f, centerY + 6f, thumb)
    }

    // ================= Input =================

    private fun executeAction(action: Action?) {
        when (action) {
            is Action.TogglePanel -> panels[action.category]?.let { it.expanded = !it.expanded }
            is Action.ToggleModule -> action.module.enabled = !action.module.enabled
            is Action.ExpandModule -> {
                val name = action.module.name
                if (name in expandedModules) expandedModules.remove(name) else expandedModules.add(name)
            }
            is Action.ToggleBool -> {
                @Suppress("UNCHECKED_CAST")
                (action.value as Value<Boolean>).set(!action.value.get())
            }
            is Action.ToggleGroup -> action.value.enabled = !action.value.enabled
            is Action.CycleMode -> cycleMode(action.group)
            is Action.StartSlider -> draggingSlider = SliderDrag(action.value, action.x1, action.x2)
            null -> Unit
        }
    }

    // ---- Input ----
    //
    // Input is handled through the vanilla Screen mouse callbacks (the same way
    // OpenNilore's ClickGUI drives its panels). This is the reliable path on
    // Android: the renderer lays out in GUI-scaled coordinates, and MC hands the
    // mouse callbacks the same GUI-scaled space, so hit-testing is direct. We do
    // NOT use LiquidBounce's global event stream here, because on ARM builds that
    // channel is not wired up and panels render but never react to input.
    //
    // 1.21.11 uses event-object signatures for clicks (MouseButtonEvent) and plain
    // doubles for scroll/move, matching DroneControlScreen in this codebase.

    private var pointerX = 0f
    private var pointerY = 0f
    private var mouseDown = false
    private var pressOriginX = 0f
    private var pressOriginY = 0f
    private var pendingHeaderPress: ModuleCategory? = null
    private var pendingRegionAction: Action? = null
    private val dragThreshold = 12f

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val x = click.x.toFloat()
        val y = click.y.toFloat()
        if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            pressRight(x, y)
        } else {
            pressLeft(x, y)
        }
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        if (click.button() != InputConstants.MOUSE_BUTTON_RIGHT) {
            releaseLeft()
        }
        return true
    }

    override fun mouseMoved(x: Double, y: Double) {
        updatePointer(x.toFloat(), y.toFloat())
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        handleScroll(verticalAmount.toFloat())
        return true
    }

    private fun updatePointer(x: Float, y: Float) {
        pointerX = x
        pointerY = y

        // A press that moved beyond the threshold becomes a panel drag.
        if (mouseDown && pendingHeaderPress != null) {
            val ddx = x - pressOriginX
            val ddy = y - pressOriginY
            if (ddx * ddx + ddy * ddy > dragThreshold * dragThreshold) {
                dragCategory = pendingHeaderPress
                val config = panels[pendingHeaderPress]
                if (config != null) {
                    dragOffset = (pressOriginX - config.left).toDouble() to (pressOriginY - config.top).toDouble()
                }
                pendingHeaderPress = null
                pendingRegionAction = null
            }
        }

        if (dragCategory != null && mouseDown) {
            movePanel(x, y)
        }

        if (draggingSlider != null) {
            updateSlider(x)
        }
    }

    private fun pressLeft(x: Float, y: Float) {
        mouseDown = true
        pendingHeaderPress = null
        pendingRegionAction = null
        pressOriginX = x
        pressOriginY = y

        searchFocused = inSearchBar(x, y)

        // Header press: a tap toggles the panel, a drag moves it.
        for ((category, config) in panels) {
            if (x >= config.left && x <= config.left + panelWidth && y >= config.top && y <= config.top + headerHeight) {
                pendingHeaderPress = category
                return
            }
        }

        // Slider press starts dragging immediately.
        for (region in regions.asReversed()) {
            val action = region.action
            if (action is Action.StartSlider && region.contains(x, y)) {
                draggingSlider = SliderDrag(action.value, action.x1, action.x2)
                updateSlider(x)
                return
            }
        }

        // Generic region, topmost wins.
        for (region in regions.asReversed()) {
            if (region.contains(x, y)) {
                pendingRegionAction = region.action
                break
            }
        }
    }

    private fun releaseLeft() {
        mouseDown = false

        draggingSlider = null
        if (dragCategory != null) {
            dragCategory = null
            dragOffset = null
        }

        val pending = pendingHeaderPress
        if (pending != null) {
            panels[pending]?.let { it.expanded = !it.expanded }
            pendingHeaderPress = null
            return
        }

        val action = pendingRegionAction
        if (action != null) {
            executeAction(action)
            pendingRegionAction = null
        }
    }

    private fun pressRight(x: Float, y: Float) {
        for (region in regions.asReversed()) {
            if (region.contains(x, y)) {
                when (val action = region.action) {
                    is Action.TogglePanel -> panels[action.category]?.let { it.expanded = !it.expanded }
                    is Action.ExpandModule -> {
                        val name = action.module.name
                        if (name in expandedModules) expandedModules.remove(name) else expandedModules.add(name)
                    }
                    else -> Unit
                }
                break
            }
        }
    }

    private fun inSearchBar(x: Float, y: Float): Boolean {
        val w = guiW()
        val sw = min(600f, w - 20f)
        val x1 = (w - sw) / 2f
        val x2 = (w + sw) / 2f
        return x >= x1 && x <= x2 && y >= 70f && y <= 120f
    }

    private fun handleScroll(vertical: Float) {
        for ((_, config) in panels) {
            if (!config.expanded) {
                continue
            }
            val bodyTop = config.top + headerHeight
            val bodyBottom = bodyTop + panelMaxBodyHeight
            if (pointerX >= config.left && pointerX <= config.left + panelWidth &&
                pointerY >= bodyTop && pointerY <= bodyBottom
            ) {
                config.scroll = (config.scroll - vertical * 20f).coerceAtLeast(0f)
                return
            }
        }
    }

    private fun movePanel(x: Float, y: Float) {
        val category = dragCategory ?: return
        val config = panels[category] ?: return
        val off = dragOffset ?: return
        val gridSize = ModuleClickGui.Snapping.snappingGridSize.toFloat()
        val snap = ModuleClickGui.Snapping.snappingGridEnabled
        var nl = x - off.first.toFloat()
        var nt = y - off.second.toFloat()
        if (snap && gridSize > 0f) {
            nl = (nl / gridSize).roundToInt() * gridSize
            nt = (nt / gridSize).roundToInt() * gridSize
        }
        config.left = nl.coerceAtLeast(0f)
        config.top = nt.coerceAtLeast(0f)
    }

    private fun updateSlider(x: Float) {
        val drag = draggingSlider ?: return
        val span = drag.trackX2 - drag.trackX1
        if (span > 0f) {
            applyScalar(drag.value, ((x - drag.trackX1) / span).coerceIn(0f, 1f))
        }
    }

    override fun keyPressed(input: net.minecraft.client.input.KeyEvent): Boolean {
        if (searchFocused && input.key == InputConstants.KEY_BACKSPACE && searchText.isNotEmpty()) {
            searchText = searchText.dropLast(1)
            rebuildSearch()
            return true
        }
        return super.keyPressed(input)
    }

    // ================= Values =================

    private fun scalarRatio(value: Value<*>): Float {
        val range = (value as? RangedValue<*>)?.range ?: return 0f
        val start = beginOf(range)
        val end = endOf(range)
        val cur = scalarOf(value.get())
        if (end - start <= 0f) {
            return 0f
        }
        return (cur - start) / (end - start)
    }

    private fun applyScalar(value: Value<*>, ratio: Float) {
        val ranged = value as? RangedValue<*> ?: return
        val range = ranged.range
        val start = beginOf(range)
        val end = endOf(range)
        val newValue = start + (end - start) * ratio.coerceIn(0f, 1f)
        when (value.valueType) {
            ValueType.INT -> {
                @Suppress("UNCHECKED_CAST")
                (value as? Value<Int>)?.set(newValue.roundToInt())
            }
            else -> {
                val floatVal = (newValue * 100f).roundToInt() / 100f
                @Suppress("UNCHECKED_CAST")
                (value as? Value<Float>)?.set(floatVal)
            }
        }
    }

    private fun cycleMode(group: ModeValueGroup<*>) {
        val modes = group.modes
        if (modes.isEmpty()) {
            return
        }
        val idx = modes.indexOfFirst { it === group.activeMode }
        group.setByString(modes[(idx + 1) % modes.size].name)
    }

    // ---- Display helpers ----

    private fun formatScalar(value: Value<*>): String {
        val cur = value.get()
        val suffix = (value as? RangedValue<*>)?.suffix ?: ""
        val text = when (cur) {
            is Int -> cur.toString()
            is Float -> if (cur % 1f == 0f) cur.toInt().toString() else String.format("%.2f", cur)
            is Double -> String.format("%.2f", cur)
            else -> cur.toString()
        }
        return "$text$suffix"
    }

    private fun displayValue(value: Value<*>): String {
        return when (value.valueType) {
            ValueType.COLOR -> {
                val c = value.get() as? Color4b
                c?.let { "#%02X%02X%02X".format(it.r, it.g, it.b) } ?: ""
            }
            ValueType.FLOAT_RANGE -> {
                val r = value.get() as? ClosedFloatingPointRange<Float>
                r?.let { String.format("%.1f ~ %.1f", it.start, it.endInclusive) } ?: ""
            }
            else -> value.get().toString()
        }
    }

    private fun beginOf(range: ClosedRange<*>): Float = when (val s = range.start) {
        is Float -> s
        is Int -> s.toFloat()
        is Double -> s.toFloat()
        else -> 0f
    }

    private fun endOf(range: ClosedRange<*>): Float = when (val s = range.endInclusive) {
        is Float -> s
        is Int -> s.toFloat()
        is Double -> s.toFloat()
        else -> 1f
    }

    private fun scalarOf(value: Any?): Float = when (value) {
        is Float -> value
        is Int -> value.toFloat()
        is Double -> value.toFloat()
        else -> 0f
    }
}

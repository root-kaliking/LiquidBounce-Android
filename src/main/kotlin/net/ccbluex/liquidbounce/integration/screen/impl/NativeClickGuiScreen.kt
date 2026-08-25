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
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.drawVerticalLine
import net.ccbluex.liquidbounce.render.engine.font.HorizontalAnchor
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.roundToInt

/**
 * Native, in-game replication of the LiquidBounce Web ClickGUI.
 *
 * The Web (Svelte) ClickGUI cannot render on ARM64 / Android because the integration
 * (CEF) backend produces no visible texture. This screen redraws the same layout, palette
 * and interaction directly with the Minecraft renderer, so pressing the [ModuleClickGui]
 * bind (right Shift) opens a fully usable configuration menu in-game.
 */
@Suppress("TooManyFunctions", "MagicNumber")
class NativeClickGuiScreen : Screen("Native ClickGUI".asPlainText()) {

    // ---- Palette (mirrors src-theme/src/colors.scss) ----
    private val accent = Color4b.fromHex("#4677ff")
    private val textColor = Color4b.WHITE
    private val dimmedText = Color4b.fromHex("#d3d3d3")
    private val headerBg = Color4b.fromHex("#1a1c21")
    private val headerBorder = accent
    private val bodyBg = Color4b.fromHex("#0d0e12").with(a = 250)
    private val rowHoverBg = Color4b.fromHex("#25282e")
    private val settingsBg = Color4b.fromHex("#15171c")
    private val overlayBg = Color4b.fromHex("#05060a").with(a = 235)
    private val tabActiveBg = accent.with(a = 34)
    private val tabActiveBorder = accent
    private val tabBarBg = Color4b.fromHex("#101116").with(a = 250)
    private val sliderTrack = Color4b.fromHex("#3a3f4a")
    private val sliderFill = accent

    // ---- Layout constants ----
    private val panelWidth = 252f
    private val headerHeight = 30f
    private val rowHeight = 26f
    private val settingRowHeight = 28f
    private val tabY = 48f
    private val contentY = 56f
    private val bodyHeight = 400f

    private val font = FontManager.FONT_RENDERER
    private val vanilla = font.scaleToVanillaFont

    // ---- UI state ----
    private var searchText = ""
    private var searchFocused = false
    private var activeTab = 0
    private val expandedModules = HashSet<String>()
    private val expandedGroups = HashSet<String>()

    private class Panel(var left: Float, var top: Float, var collapsed: Boolean, var scroll: Float = 0f)
    private val panels = HashMap<ModuleCategory, Panel>()

    private class SliderDrag(val value: Value<*>, val trackX1: Float, val trackX2: Float)
    private var draggingSlider: SliderDrag? = null

    // ---- Hit testing (rebuilt every render) ----
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
        fun contains(mx: Float, my: Float): Boolean = mx >= x0 && mx <= x1 && my >= y0 && my <= y1
    }

    private val regions = ArrayList<Region>()

    // ================= Rendering =================

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val w = mc.window.guiScaledWidth.toFloat()
        val h = mc.window.guiScaledHeight.toFloat()
        ensureLayout(w)
        regions.clear()
        with(g) {
            renderScreen(w, h, mouseX.toFloat(), mouseY.toFloat())
        }
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // Full-screen dimming happens in [extractRenderState].
    }

    override fun shouldCloseOnEsc(): Boolean = true
    override fun isPauseScreen(): Boolean = false

    private fun ensureLayout(width: Float) {
        if (panels.isNotEmpty()) {
            return
        }
        val cats = ModuleCategories.entries.filter { it.tag != "ClickGUI" }.toList()
        val cols = maxOf(1, ((width - 20f) / (panelWidth + 12f)).toInt().coerceAtLeast(1))
        cats.forEachIndexed { index, category ->
            val col = index % cols
            val row = index / cols
            panels[category] = Panel(16f + col * (panelWidth + 12f), contentY + row * 48f, collapsed = false)
        }
    }

    private fun GuiGraphicsExtractor.renderScreen(w: Float, h: Float, mx: Float, my: Float) {
        drawQuad(0f, 0f, w, h, overlayBg)
        renderSearchBox(w, mx, my)
        renderTabs(w)
        renderPanels(mx, my)
    }

    private fun searchMode(): Boolean = searchText.isNotBlank()

    private fun visibleCategories(): List<ModuleCategory> {
        val used = ModuleManager.filter { it.category != ModuleClickGui.category }.mapTo(HashSet()) { it.category }
        return ModuleCategories.entries.filter { it.tag != "ClickGUI" && it in used }.sortedBy { it.tag }
    }

    // ---- Tabs ----

    private fun GuiGraphicsExtractor.renderTabs(w: Float) {
        drawQuad(0f, 0f, w, tabY, tabBarBg)
        drawHorizontalLine(0f, w, tabY, 1.5f, accent.with(a = 70))

        val labels = listOf("ClickGUI", "HUD Editor", "Settings")
        val tabWidth = 170f
        val total = labels.size * tabWidth + (labels.size - 1) * 8f
        var xx = (w - total) / 2f

        labels.forEachIndexed { index, label ->
            val x1 = xx
            val x2 = xx + tabWidth
            if (index == activeTab) {
                drawRoundedRect(x1, 8f, x2, tabY - 4f, 8f, tabActiveBg, tabActiveBorder, 1.5f)
            }
            font.draw(label.asPlainText()) {
                horizontalAnchor = HorizontalAnchor.CENTER
                x = (x1 + x2) / 2f
                y = 14f
                scale = vanilla * 1.15f
            }
            xx += tabWidth + 8f
        }
    }

    private fun GuiGraphicsExtractor.renderSearchBox(w: Float, mx: Float, my: Float) {
        val sw = 360f
        val x1 = (w - sw) / 2f
        val x2 = (w + sw) / 2f
        val y1 = tabY + 6f
        val y2 = y1 + 36f

        drawRoundedRect(x1, y1, x2, y2, 10f, headerBg, headerBorder, 1.5f)

        val text = if (searchText.isBlank()) "Search modules..." else searchText
        val color = if (searchText.isBlank()) dimmedText.with(a = 140) else textColor
        font.draw(text.asPlainText()) {
            x = x1 + 14f
            y = y1 + 9f
            scale = vanilla
        }

        regions += Region(x1, y1, x2, y2, null)
    }

    // ---- Panels ----

    private fun GuiGraphicsExtractor.renderPanels(mx: Float, my: Float) {
        for (category in visibleCategories()) {
            val panel = panels[category] ?: continue
            renderPanel(category, panel, mx, my)
        }
    }

    private fun GuiGraphicsExtractor.renderPanel(category: ModuleCategory, panel: Panel, mx: Float, my: Float) {
        val x = panel.left
        val y = panel.top
        val panelRight = x + panelWidth

        // Header
        drawRoundedRect(x, y, panelRight, y + headerHeight, 6f, headerBg, headerBorder, 1.5f)

        font.draw(category.tag.asPlainText()) {
            x = x + 12f
            y = y + 6f
            scale = vanilla * 1.1f
        }

        // Chevron
        val chevY = y + headerHeight / 2f
        if (panel.collapsed) {
            drawTriangle(panelRight - 30f, chevY - 3f, panelRight - 20f, chevY, panelRight - 30f, chevY + 3f, Color4b.WHITE.with(a = 120))
        } else {
            drawTriangle(panelRight - 30f, chevY - 3f, panelRight - 20f, chevY - 3f, panelRight - 25f, chevY + 3f, Color4b.WHITE.with(a = 120))
        }
        regions += Region(x, y, panelRight, y + headerHeight, Action.TogglePanel(category))

        if (panel.collapsed) {
            return
        }

        // Body
        val bodyTop = y + headerHeight
        val bodyBottom = bodyTop + bodyHeight
        drawRoundedRect(x, bodyTop, panelRight, bodyBottom, 0f, bodyBg)

        scissorStack.withPush(getBounds(x, bodyTop, panelWidth, bodyHeight - 1f)) {
            var curY = bodyTop + 4f - panel.scroll
            for (module in modulesFor(category)) {
                curY = renderModule(module, x, curY, panelRight, mx, my)
            }
        }

        // scroll hint
        drawVerticalLine(panelRight - 2f, bodyTop + 2f, bodyBottom - 2f, 2f, Color4b.WHITE.with(a = 36))
        regions += Region(x, bodyTop, panelRight, bodyBottom, null)
    }

    private fun modulesFor(category: ModuleCategory): List<ClientModule> =
        ModuleManager.filter { it.category == category && it !== ModuleClickGui }.sortedBy { it.name }

    private fun GuiGraphicsExtractor.renderModule(
        module: ClientModule,
        x: Float,
        y: Float,
        panelRight: Float,
        mx: Float,
        my: Float,
    ): Float {
        val rowBottom = y + rowHeight
        val hover = mx >= x && mx <= panelRight && my >= y && my <= rowBottom

        if (hover) {
            drawQuad(x, y, panelRight, rowBottom, rowHoverBg)
        }

        val isEnabled = module.enabled
        val color = when {
            hover -> textColor
            isEnabled -> accent
            else -> dimmedText
        }

        font.draw(module.name.asPlainText()) {
            horizontalAnchor = HorizontalAnchor.CENTER
            x = (x + panelRight) / 2f
            y = y + (rowHeight - font.height * vanilla) / 2f
            scale = vanilla
        }

        // left toggle region
        regions += Region(x, y, panelRight - 40f, rowBottom, Action.ToggleModule(module))

        if (module.containedValues.any(::isRelevantSetting)) {
            val isExpanded = module.name in expandedModules
            val chevY = y + rowHeight / 2f
            if (isExpanded) {
                drawTriangle(panelRight - 36f, chevY - 3f, panelRight - 16f, chevY - 3f, panelRight - 26f, chevY + 3f, Color4b.WHITE.with(a = 170))
            } else {
                drawTriangle(panelRight - 36f, chevY - 3f, panelRight - 36f, chevY + 3f, panelRight - 26f, chevY, Color4b.WHITE.with(a = 170))
            }
            regions += Region(panelRight - 44f, y, panelRight, rowBottom, Action.ExpandModule(module))
        }

        var nextY = rowBottom

        if (module.name in expandedModules) {
            nextY = renderSettings(module, x, nextY, panelRight, module.containedValues.toList(), 0)
        }

        return nextY
    }

    private fun isRelevantSetting(value: Value<*>): Boolean = value.name !in setOf("Enabled", "Hidden", "Bind")

    private fun GuiGraphicsExtractor.renderSettings(
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
            curY = renderSetting(module, value, x, curY, panelRight, depth)
        }
        return curY
    }

    private fun GuiGraphicsExtractor.renderSetting(
        module: ClientModule,
        value: Value<*>,
        x: Float,
        y: Float,
        panelRight: Float,
        depth: Int,
    ): Float {
        val indent = x + 6f + depth * 12f
        val rowBottom = y + settingRowHeight

        when (value) {
            is ToggleableValueGroup -> {
                drawQuad(x, y, panelRight, rowBottom, settingsBg)
                val outerEnabled = value.enabled
                font.draw(value.name.asPlainText()) {
                    x = indent
                    y = y + (settingRowHeight - font.height * vanilla) / 2f
                    scale = vanilla * 0.9f
                }
                drawSwitch(panelRight - 42f, y + settingRowHeight / 2f, outerEnabled)
                regions += Region(x, y, panelRight, rowBottom, Action.ToggleGroup(value))

                var nextY = rowBottom
                val groupKey = "${module.name}/${value.name}"
                if (outerEnabled && groupKey in expandedGroups) {
                    nextY = renderSettings(module, x, nextY, panelRight, value.containedValues.toList(), depth + 1)
                }
                return nextY
            }

            is ModeValueGroup<*> -> {
                drawQuad(x, y, panelRight, rowBottom, settingsBg)
                font.draw(value.name.asPlainText()) {
                    x = indent
                    y = y + (settingRowHeight - font.height * vanilla) / 2f
                    scale = vanilla * 0.9f
                }
                val cur = value.activeMode.name
                font.draw(cur.asPlainText()) {
                    horizontalAnchor = HorizontalAnchor.END
                    x = panelRight - 12f
                    y = y + (settingRowHeight - font.height * vanilla) / 2f
                    scale = vanilla * 0.8f
                }
                drawModeChevron(panelRight - 40f, y + settingRowHeight / 2f)
                regions += Region(panelRight - 60f, y, panelRight, rowBottom, Action.CycleMode(value))
                return rowBottom
            }
        }

        return when (value.valueType) {
            ValueType.BOOLEAN -> {
                drawQuad(x, y, panelRight, rowBottom, settingsBg)
                val cur = value.get() as Boolean
                font.draw(value.name.asPlainText()) {
                    x = indent
                    y = y + (settingRowHeight - font.height * vanilla) / 2f
                    scale = vanilla * 0.9f
                }
                drawSwitch(panelRight - 42f, y + settingRowHeight / 2f, cur)
                regions += Region(x, y, panelRight, rowBottom, Action.ToggleBool(value))
                rowBottom
            }

            ValueType.FLOAT, ValueType.INT -> {
                drawQuad(x, y, panelRight, rowBottom, settingsBg)
                font.draw(value.name.asPlainText()) {
                    x = indent
                    y = y + 3f
                    scale = vanilla * 0.85f
                }
                font.draw(formatScalar(value).asPlainText()) {
                    horizontalAnchor = HorizontalAnchor.END
                    x = panelRight - 12f
                    y = y + 3f
                    scale = vanilla * 0.8f
                }
                // slider
                val trackX1 = indent
                val trackX2 = panelRight - 12f
                val trackY = y + settingRowHeight - 9f
                drawQuad(trackX1, trackY, trackX2, trackY + 3f, sliderTrack)
                val ratio = scalarRatio(value).coerceIn(0f, 1f)
                val fillX2 = trackX1 + (trackX2 - trackX1) * ratio
                drawQuad(trackX1, trackY, fillX2, trackY + 3f, sliderFill)
                drawCircleHandle(fillX2, trackY + 1.5f)
                regions += Region(trackX1, trackY - 3f, trackX2, trackY + 6f, Action.StartSlider(value, trackX1, trackX2))
                rowBottom
            }

            else -> {
                drawQuad(x, y, panelRight, rowBottom, settingsBg)
                font.draw(value.name.asPlainText()) {
                    x = indent
                    y = y + (settingRowHeight - font.height * vanilla) / 2f
                    scale = vanilla * 0.9f
                }
                val display = displayValue(value)
                if (display.isNotBlank()) {
                    font.draw(display.asPlainText()) {
                        horizontalAnchor = HorizontalAnchor.END
                        x = panelRight - 12f
                        y = y + (settingRowHeight - font.height * vanilla) / 2f
                        scale = vanilla * 0.8f
                    }
                }
                rowBottom
            }
        }
    }

    // ---- Widgets ----

    private fun GuiGraphicsExtractor.drawSwitch(centerX: Float, centerY: Float, on: Boolean) {
        val width = 30f
        val height = 17f
        val track = if (on) accent else sliderTrack
        drawRoundedRect(centerX, centerY - height / 2f, centerX + width, centerY + height / 2f, height / 2f, track, null, 0f)
        val thumb = if (on) centerX + width - 9f else centerX + 6f
        drawCircleHandle(thumb, centerY, radius = 5.5f, color = if (on) Color4b.WHITE else Color4b.fromHex("#c9cdd4"))
    }

    private fun GuiGraphicsExtractor.drawModeChevron(x: Float, y: Float) {
        drawTriangle(x, y - 3f, x + 9f, y - 3f, x + 9f, y + 3f, Color4b.GRAY.with(a = 180))
    }

    private fun GuiGraphicsExtractor.drawCircleHandle(cx: Float, cy: Float, radius: Float = 4f, color: Color4b = Color4b.WHITE) {
        val r = radius
        drawTriangle(cx - r, cy, cx, cy - r, cx + r, cy, color)
        drawTriangle(cx - r, cy, cx, cy + r, cx + r, cy, color)
    }

    // ================= Input =================

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return true
        }
        val mx = click.x.toFloat()
        val my = click.y.toFloat()
        val w = mc.window.guiScaledWidth.toFloat()
        val sw = 360f
        val searchX1 = (w - sw) / 2f
        val searchX2 = (w + sw) / 2f

        searchFocused = mx >= searchX1 && mx <= searchX2 && my >= tabY + 6f && my <= tabY + 42f
        if (searchFocused) {
            return true
        }

        for (region in regions) {
            if (!region.contains(mx, my)) {
                continue
            }
            when (val action = region.action) {
                is Action.TogglePanel -> panels[action.category]?.let { it.collapsed = !it.collapsed }
                is Action.ToggleModule -> action.module.enabled = !action.module.enabled
                is Action.ExpandModule -> {
                    val name = action.module.name
                    if (name in expandedModules) expandedModules.remove(name) else expandedModules.add(name)
                }
                is Action.ToggleBool -> {
                    val v = action.value
                    if (v is Value<Boolean>) {
                        v.set(!v.get())
                    }
                }
                is Action.ToggleGroup -> action.value.enabled = !action.value.enabled
                is Action.CycleMode -> cycleMode(action.group)
                is Action.StartSlider -> draggingSlider = SliderDrag(action.value, action.x1, action.x2)
                null -> Unit
            }
            break
        }
        return true
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        val drag = draggingSlider ?: return false
        val mx = click.x.toFloat()
        val span = drag.trackX2 - drag.trackX1
        if (span <= 0f) {
            return true
        }
        val ratio = ((mx - drag.trackX1) / span).coerceIn(0f, 1f)
        applyScalar(drag.value, ratio)
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        draggingSlider = null
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) = Unit

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val mx = mouseX.toFloat()
        val my = mouseY.toFloat()
        for (panel in panels.values) {
            val bodyTop = panel.top + headerHeight
            val bodyBottom = bodyTop + bodyHeight
            if (mx >= panel.left && mx <= panel.left + panelWidth && my >= bodyTop && my <= bodyBottom) {
                panel.scroll = (panel.scroll - verticalAmount.toFloat() * 20f).coerceAtLeast(0f)
                return true
            }
        }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (searchFocused && input.key == InputConstants.KEY_BACKSPACE && searchText.isNotEmpty()) {
            searchText = searchText.dropLast(1)
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
                val intVal = newValue.roundToInt()
                (value as? Value<Int>)?.set(intVal)
            }
            else -> {
                val floatVal = (newValue * 100f).roundToInt() / 100f
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
        val next = modes[(idx + 1) % modes.size]
        group.setByString(next.name)
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
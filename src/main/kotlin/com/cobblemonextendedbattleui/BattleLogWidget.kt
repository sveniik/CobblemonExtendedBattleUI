package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.render.drawScaledText
import com.cobblemon.mod.common.util.cobblemonResource
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Battle log widget styled to match Cobblemon's visual aesthetic.
 * Uses Cobblemon's actual textures with 9-slice rendering for resize support.
 *
 * Features:
 * - Moveable (drag from header)
 * - Resizable (drag from edges/corners) with 9-slice texture rendering
 * - Font size adjustable ([ and ] keys, or Ctrl+Scroll when hovering)
 * - Expandable/collapsible
 * - Turn separators
 * - Scrollable content
 */
object BattleLogWidget {

    // ═══════════════════════════════════════════════════════════════════════════
    // Cobblemon textures
    // ═══════════════════════════════════════════════════════════════════════════

    private val FRAME_TEXTURE: Identifier = cobblemonResource("textures/gui/battle/battle_log.png")
    private val FRAME_EXPANDED_TEXTURE: Identifier = cobblemonResource("textures/gui/battle/battle_log_expanded.png")

    // Original texture dimensions
    private const val TEXTURE_WIDTH = 169
    private const val TEXTURE_HEIGHT_COLLAPSED = 55
    private const val TEXTURE_HEIGHT_EXPANDED = 101

    // 9-slice border sizes (how much of the texture edges to preserve)
    private const val SLICE_LEFT = 6
    private const val SLICE_RIGHT = 6
    private const val SLICE_TOP = 6
    private const val SLICE_BOTTOM = 6

    // ═══════════════════════════════════════════════════════════════════════════
    // Layout constants
    // ═══════════════════════════════════════════════════════════════════════════

    private const val HEADER_HEIGHT = 16
    private const val COLLAPSED_HEIGHT = 70  // Small preset size
    private const val PADDING = 6
    private const val LOG_TEXT_INDENT = 4    // Extra indent for log entries
    private const val BASE_LINE_HEIGHT = 11
    private const val RESIZE_HANDLE_SIZE = 5
    private const val SCROLLBAR_WIDTH = 3

    // ═══════════════════════════════════════════════════════════════════════════
    // Colors - matching Cobblemon's battle log style
    // ═══════════════════════════════════════════════════════════════════════════

    private fun color(r: Int, g: Int, b: Int, a: Int = 255): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    // Text colors
    private val TEXT_WHITE = color(255, 255, 255)
    private val TEXT_DIM = color(150, 160, 175)
    private val TEXT_GOLD = color(255, 210, 90)

    // Entry type colors
    private val COLOR_MOVE = color(255, 255, 255)
    private val COLOR_HP = color(120, 195, 255)
    private val COLOR_EFFECT = color(255, 215, 90)
    private val COLOR_FIELD = color(160, 255, 160)
    private val COLOR_OTHER = color(175, 175, 185)

    // Turn separator - subtle styling that blends with Cobblemon texture
    private val TURN_LINE_COLOR = color(90, 100, 120, 100)  // Very subtle, semi-transparent
    private val TURN_TEXT_COLOR = color(160, 150, 130)      // Muted gold/tan to match texture

    // Scrollbar
    private val SCROLLBAR_BG = color(30, 38, 50, 180)
    private val SCROLLBAR_THUMB = color(80, 100, 130, 220)

    // Resize handles
    private val RESIZE_HANDLE_COLOR = color(80, 100, 130, 180)
    private val RESIZE_HANDLE_HOVER = color(120, 150, 190, 255)

    // ═══════════════════════════════════════════════════════════════════════════
    // Resize zones
    // ═══════════════════════════════════════════════════════════════════════════

    enum class ResizeZone {
        NONE, LEFT, RIGHT, TOP, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private var scrollOffset: Int = 0
    private var contentHeight: Int = 0
    private var visibleHeight: Int = 0

    // Input tracking
    private var wasMousePressed: Boolean = false
    private var wasIncreaseFontKeyPressed: Boolean = false
    private var wasDecreaseFontKeyPressed: Boolean = false

    // Dragging state
    private var isDragging: Boolean = false
    private var dragOffsetX: Int = 0
    private var dragOffsetY: Int = 0

    // Resize state
    private var isResizing: Boolean = false
    private var resizeZone: ResizeZone = ResizeZone.NONE
    private var resizeStartX: Int = 0
    private var resizeStartY: Int = 0
    private var resizeStartWidth: Int = 0
    private var resizeStartHeight: Int = 0
    private var resizeStartPanelX: Int = 0
    private var resizeStartPanelY: Int = 0

    // Hover state
    private var hoveredZone: ResizeZone = ResizeZone.NONE

    // Bounds for click detection
    private var widgetX: Int = 0
    private var widgetY: Int = 0
    private var widgetW: Int = 0
    private var widgetH: Int = 0
    private var headerEndY: Int = 0

    // Scissor bounds
    private var scissorMinY: Int = 0
    private var scissorMaxY: Int = 0

    // Computed line height based on font scale
    private var lineHeight: Int = BASE_LINE_HEIGHT

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    fun render(context: DrawContext) {
        if (!PanelConfig.replaceBattleLog) return
        val battle = CobblemonClient.battle ?: return
        if (battle.minimised) return

        val mc = MinecraftClient.getInstance()

        // Update line height based on font scale
        lineHeight = (BASE_LINE_HEIGHT * PanelConfig.logFontScale).toInt().coerceAtLeast(8)

        handleInput(mc)

        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight

        val isExpanded = PanelConfig.logExpanded

        // Get dimensions from config or use defaults
        val width = PanelConfig.logWidth ?: PanelConfig.DEFAULT_LOG_WIDTH
        val expandedHeight = PanelConfig.logHeight ?: PanelConfig.DEFAULT_LOG_HEIGHT
        val height = if (isExpanded) expandedHeight else COLLAPSED_HEIGHT

        // Default position: bottom center, above battle controls
        // We track position by BOTTOM edge so collapse/expand keeps bottom fixed
        val defaultX = (screenWidth - width) / 2
        val defaultBottomY = screenHeight - 55  // Bottom edge position

        // Calculate Y from bottom edge (collapse downwards behavior)
        val bottomY = (PanelConfig.logY ?: (defaultBottomY - expandedHeight)) + expandedHeight
        val y = (bottomY - height).coerceIn(0, screenHeight - height)
        val x = (PanelConfig.logX ?: defaultX).coerceIn(0, screenWidth - width)

        widgetX = x
        widgetY = y
        widgetW = width
        widgetH = height

        // Render frame using 9-slice with Cobblemon textures
        renderFrame9Slice(context, x, y, width, height, isExpanded)
        headerEndY = y + HEADER_HEIGHT

        // Always render the same content - "collapsed" is just a smaller preset size
        renderContent(context, x, y, width, height)

        // Resize handles only when expanded (full size mode allows resizing)
        if (isExpanded && (hoveredZone != ResizeZone.NONE || isResizing)) {
            renderResizeHandles(context, x, y, width, height)
        }
    }

    fun onScroll(mouseX: Double, mouseY: Double, deltaY: Double): Boolean {
        if (!PanelConfig.replaceBattleLog) return false

        val mc = MinecraftClient.getInstance()
        val scaledX = (mouseX * mc.window.scaledWidth / mc.window.width).toInt()
        val scaledY = (mouseY * mc.window.scaledHeight / mc.window.height).toInt()

        val isOver = scaledX >= widgetX && scaledX <= widgetX + widgetW &&
                     scaledY >= widgetY && scaledY <= widgetY + widgetH

        if (!isOver) return false

        // Ctrl+Scroll for font size
        val handle = mc.window.handle
        val ctrlDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                       GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS

        if (ctrlDown) {
            val delta = if (deltaY > 0) PanelConfig.FONT_SCALE_STEP else -PanelConfig.FONT_SCALE_STEP
            PanelConfig.adjustLogFontScale(delta)
            PanelConfig.save()
            return true
        }

        // Regular scroll
        if (contentHeight > visibleHeight) {
            val scrollAmount = (lineHeight * 2 * if (deltaY > 0) -1 else 1)
            val maxScroll = (contentHeight - visibleHeight).coerceAtLeast(0)
            scrollOffset = (scrollOffset + scrollAmount).coerceIn(0, maxScroll)
            return true
        }
        return false
    }

    fun clear() {
        scrollOffset = 0
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 9-Slice Texture Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Renders the frame using 9-slice technique with Cobblemon's textures.
     * This allows the texture to be stretched to any size while preserving
     * the corners and edges properly.
     *
     * Uses DrawContext.drawTexture which properly stretches (not tiles) when
     * render size differs from source region size.
     */
    private fun renderFrame9Slice(context: DrawContext, x: Int, y: Int, width: Int, height: Int, isExpanded: Boolean) {
        val texture = if (isExpanded) FRAME_EXPANDED_TEXTURE else FRAME_TEXTURE
        val texH = if (isExpanded) TEXTURE_HEIGHT_EXPANDED else TEXTURE_HEIGHT_COLLAPSED

        // Calculate the sizes of the center regions in the texture
        val centerTexW = TEXTURE_WIDTH - SLICE_LEFT - SLICE_RIGHT
        val centerTexH = texH - SLICE_TOP - SLICE_BOTTOM

        // Calculate the sizes of the center regions in the render
        val centerW = width - SLICE_LEFT - SLICE_RIGHT
        val centerH = height - SLICE_TOP - SLICE_BOTTOM

        // Top-left corner (no stretching needed)
        context.drawTexture(
            texture,
            x, y,                           // render position
            SLICE_LEFT, SLICE_TOP,          // render size
            0f, 0f,                         // UV offset
            SLICE_LEFT, SLICE_TOP,          // source region size
            TEXTURE_WIDTH, texH             // full texture size
        )

        // Top-right corner
        context.drawTexture(
            texture,
            x + width - SLICE_RIGHT, y,
            SLICE_RIGHT, SLICE_TOP,
            (TEXTURE_WIDTH - SLICE_RIGHT).toFloat(), 0f,
            SLICE_RIGHT, SLICE_TOP,
            TEXTURE_WIDTH, texH
        )

        // Bottom-left corner
        context.drawTexture(
            texture,
            x, y + height - SLICE_BOTTOM,
            SLICE_LEFT, SLICE_BOTTOM,
            0f, (texH - SLICE_BOTTOM).toFloat(),
            SLICE_LEFT, SLICE_BOTTOM,
            TEXTURE_WIDTH, texH
        )

        // Bottom-right corner
        context.drawTexture(
            texture,
            x + width - SLICE_RIGHT, y + height - SLICE_BOTTOM,
            SLICE_RIGHT, SLICE_BOTTOM,
            (TEXTURE_WIDTH - SLICE_RIGHT).toFloat(), (texH - SLICE_BOTTOM).toFloat(),
            SLICE_RIGHT, SLICE_BOTTOM,
            TEXTURE_WIDTH, texH
        )

        // Top edge (stretched horizontally)
        if (centerW > 0) {
            context.drawTexture(
                texture,
                x + SLICE_LEFT, y,              // render position
                centerW, SLICE_TOP,             // render size (stretched width)
                SLICE_LEFT.toFloat(), 0f,       // UV offset
                centerTexW, SLICE_TOP,          // source region size
                TEXTURE_WIDTH, texH
            )
        }

        // Bottom edge (stretched horizontally)
        if (centerW > 0) {
            context.drawTexture(
                texture,
                x + SLICE_LEFT, y + height - SLICE_BOTTOM,
                centerW, SLICE_BOTTOM,
                SLICE_LEFT.toFloat(), (texH - SLICE_BOTTOM).toFloat(),
                centerTexW, SLICE_BOTTOM,
                TEXTURE_WIDTH, texH
            )
        }

        // Left edge (stretched vertically)
        if (centerH > 0) {
            context.drawTexture(
                texture,
                x, y + SLICE_TOP,
                SLICE_LEFT, centerH,            // render size (stretched height)
                0f, SLICE_TOP.toFloat(),
                SLICE_LEFT, centerTexH,         // source region size
                TEXTURE_WIDTH, texH
            )
        }

        // Right edge (stretched vertically)
        if (centerH > 0) {
            context.drawTexture(
                texture,
                x + width - SLICE_RIGHT, y + SLICE_TOP,
                SLICE_RIGHT, centerH,
                (TEXTURE_WIDTH - SLICE_RIGHT).toFloat(), SLICE_TOP.toFloat(),
                SLICE_RIGHT, centerTexH,
                TEXTURE_WIDTH, texH
            )
        }

        // Center (stretched both ways)
        if (centerW > 0 && centerH > 0) {
            context.drawTexture(
                texture,
                x + SLICE_LEFT, y + SLICE_TOP,
                centerW, centerH,               // render size (stretched both)
                SLICE_LEFT.toFloat(), SLICE_TOP.toFloat(),
                centerTexW, centerTexH,         // source region size
                TEXTURE_WIDTH, texH
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Input handling
    // ═══════════════════════════════════════════════════════════════════════════

    private fun handleInput(mc: MinecraftClient) {
        val handle = mc.window.handle
        val mouseX = (mc.mouse.x * mc.window.scaledWidth / mc.window.width).toInt()
        val mouseY = (mc.mouse.y * mc.window.scaledHeight / mc.window.height).toInt()
        val isMouseDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS

        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight

        // Check if mouse is over this widget for keybind handling
        val isOverWidget = mouseX >= widgetX && mouseX <= widgetX + widgetW &&
                           mouseY >= widgetY && mouseY <= widgetY + widgetH

        // Font size keybinds (only when hovering over widget)
        if (isOverWidget) {
            handleFontKeybinds(handle)
        }

        // Update hover state
        if (!isDragging && !isResizing) {
            hoveredZone = getResizeZone(mouseX, mouseY)
        }

        val isOverHeader = isOverWidget && mouseY <= headerEndY

        // Expand/collapse toggle zone (bottom-right corner, matching Cobblemon's arrow location)
        val toggleZoneX = widgetX + widgetW - 16
        val toggleZoneY = widgetY + widgetH - 14
        val isOverToggle = mouseX >= toggleZoneX && mouseX <= toggleZoneX + 14 &&
                           mouseY >= toggleZoneY && mouseY <= toggleZoneY + 12

        if (isMouseDown) {
            when {
                // Toggle expand/collapse
                !wasMousePressed && isOverToggle -> {
                    PanelConfig.setLogExpanded(!PanelConfig.logExpanded)
                    scrollOffset = 0
                    PanelConfig.save()
                }
                // Start resize
                !wasMousePressed && hoveredZone != ResizeZone.NONE -> {
                    isResizing = true
                    resizeZone = hoveredZone
                    resizeStartX = mouseX
                    resizeStartY = mouseY
                    resizeStartWidth = widgetW
                    resizeStartHeight = widgetH
                    resizeStartPanelX = widgetX
                    resizeStartPanelY = widgetY
                }
                // Continue resizing
                isResizing -> {
                    handleResize(mouseX, mouseY, screenWidth, screenHeight)
                }
                // Start dragging (from header, excluding toggle)
                !wasMousePressed && isOverHeader && !isOverToggle -> {
                    isDragging = true
                    dragOffsetX = mouseX - widgetX
                    dragOffsetY = mouseY - widgetY
                }
                // Continue dragging
                isDragging -> {
                    val newX = (mouseX - dragOffsetX).coerceIn(0, screenWidth - widgetW)
                    val newY = (mouseY - dragOffsetY).coerceIn(0, screenHeight - widgetH)
                    PanelConfig.setLogPosition(newX, newY)
                }
            }
        } else {
            if (isDragging || isResizing) {
                PanelConfig.save()
            }
            isDragging = false
            isResizing = false
        }

        wasMousePressed = isMouseDown
    }

    private fun handleFontKeybinds(handle: Long) {
        val increaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.increaseFontKey.boundKeyTranslationKey)
        val isIncreaseDown = isKeyOrButtonPressed(handle, increaseKey)
        if (isIncreaseDown && !wasIncreaseFontKeyPressed) {
            PanelConfig.adjustLogFontScale(PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasIncreaseFontKeyPressed = isIncreaseDown

        val decreaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.decreaseFontKey.boundKeyTranslationKey)
        val isDecreaseDown = isKeyOrButtonPressed(handle, decreaseKey)
        if (isDecreaseDown && !wasDecreaseFontKeyPressed) {
            PanelConfig.adjustLogFontScale(-PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasDecreaseFontKeyPressed = isDecreaseDown
    }

    private fun isKeyOrButtonPressed(handle: Long, key: InputUtil.Key): Boolean {
        return when (key.category) {
            InputUtil.Type.MOUSE -> GLFW.glfwGetMouseButton(handle, key.code) == GLFW.GLFW_PRESS
            else -> GLFW.glfwGetKey(handle, key.code) == GLFW.GLFW_PRESS
        }
    }

    private fun getResizeZone(mouseX: Int, mouseY: Int): ResizeZone {
        if (!PanelConfig.logExpanded) return ResizeZone.NONE

        val x = widgetX
        val y = widgetY
        val w = widgetW
        val h = widgetH

        val onLeft = mouseX >= x - RESIZE_HANDLE_SIZE && mouseX <= x + RESIZE_HANDLE_SIZE
        val onRight = mouseX >= x + w - RESIZE_HANDLE_SIZE && mouseX <= x + w + RESIZE_HANDLE_SIZE
        val onTop = mouseY >= y - RESIZE_HANDLE_SIZE && mouseY <= y + 2
        val onBottom = mouseY >= y + h - RESIZE_HANDLE_SIZE && mouseY <= y + h + RESIZE_HANDLE_SIZE
        val withinX = mouseX >= x - RESIZE_HANDLE_SIZE && mouseX <= x + w + RESIZE_HANDLE_SIZE
        val withinY = mouseY >= y - RESIZE_HANDLE_SIZE && mouseY <= y + h + RESIZE_HANDLE_SIZE

        return when {
            onTop && onLeft && withinX && withinY -> ResizeZone.TOP_LEFT
            onTop && onRight && withinX && withinY -> ResizeZone.TOP_RIGHT
            onBottom && onLeft && withinX && withinY -> ResizeZone.BOTTOM_LEFT
            onBottom && onRight && withinX && withinY -> ResizeZone.BOTTOM_RIGHT
            onLeft && withinY -> ResizeZone.LEFT
            onRight && withinY -> ResizeZone.RIGHT
            onTop && withinX -> ResizeZone.TOP
            onBottom && withinX -> ResizeZone.BOTTOM
            else -> ResizeZone.NONE
        }
    }

    private fun handleResize(mouseX: Int, mouseY: Int, screenWidth: Int, screenHeight: Int) {
        val deltaX = mouseX - resizeStartX
        val deltaY = mouseY - resizeStartY

        var newWidth = resizeStartWidth
        var newHeight = resizeStartHeight
        var newX = resizeStartPanelX
        var newY = resizeStartPanelY

        when (resizeZone) {
            ResizeZone.RIGHT -> newWidth = resizeStartWidth + deltaX
            ResizeZone.BOTTOM -> newHeight = resizeStartHeight + deltaY
            ResizeZone.BOTTOM_RIGHT -> {
                newWidth = resizeStartWidth + deltaX
                newHeight = resizeStartHeight + deltaY
            }
            ResizeZone.LEFT -> {
                newWidth = resizeStartWidth - deltaX
                newX = resizeStartPanelX + deltaX
            }
            ResizeZone.TOP -> {
                newHeight = resizeStartHeight - deltaY
                newY = resizeStartPanelY + deltaY
            }
            ResizeZone.TOP_LEFT -> {
                newWidth = resizeStartWidth - deltaX
                newHeight = resizeStartHeight - deltaY
                newX = resizeStartPanelX + deltaX
                newY = resizeStartPanelY + deltaY
            }
            ResizeZone.TOP_RIGHT -> {
                newWidth = resizeStartWidth + deltaX
                newHeight = resizeStartHeight - deltaY
                newY = resizeStartPanelY + deltaY
            }
            ResizeZone.BOTTOM_LEFT -> {
                newWidth = resizeStartWidth - deltaX
                newHeight = resizeStartHeight + deltaY
                newX = resizeStartPanelX + deltaX
            }
            ResizeZone.NONE -> {}
        }

        // Apply constraints
        newWidth = newWidth.coerceIn(PanelConfig.MIN_LOG_WIDTH, PanelConfig.MAX_LOG_WIDTH)
        newHeight = newHeight.coerceIn(PanelConfig.MIN_LOG_HEIGHT, PanelConfig.MAX_LOG_HEIGHT)
        newX = newX.coerceIn(0, screenWidth - newWidth)
        newY = newY.coerceIn(0, screenHeight - newHeight)

        PanelConfig.setLogDimensions(newWidth, newHeight)
        PanelConfig.setLogPosition(newX, newY)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    private fun renderResizeHandles(context: DrawContext, x: Int, y: Int, width: Int, height: Int) {
        val handleColor = if (isResizing) RESIZE_HANDLE_HOVER else RESIZE_HANDLE_COLOR
        val hs = 3  // Handle visual size

        // Corner handles
        when (hoveredZone) {
            ResizeZone.TOP_LEFT, ResizeZone.LEFT, ResizeZone.TOP -> {
                context.fill(x - 1, y - 1, x + hs, y + hs, handleColor)
            }
            else -> {}
        }
        when (hoveredZone) {
            ResizeZone.TOP_RIGHT, ResizeZone.RIGHT, ResizeZone.TOP -> {
                context.fill(x + width - hs, y - 1, x + width + 1, y + hs, handleColor)
            }
            else -> {}
        }
        when (hoveredZone) {
            ResizeZone.BOTTOM_LEFT, ResizeZone.LEFT, ResizeZone.BOTTOM -> {
                context.fill(x - 1, y + height - hs, x + hs, y + height + 1, handleColor)
            }
            else -> {}
        }
        when (hoveredZone) {
            ResizeZone.BOTTOM_RIGHT, ResizeZone.RIGHT, ResizeZone.BOTTOM -> {
                context.fill(x + width - hs, y + height - hs, x + width + 1, y + height + 1, handleColor)
            }
            else -> {}
        }
    }

    private fun renderContent(context: DrawContext, x: Int, y: Int, width: Int, height: Int) {
        val headerTextY = y + SLICE_TOP + 2  // Push down to avoid outline

        // Header title
        drawText(context, "Battle Log", (x + PADDING + LOG_TEXT_INDENT).toFloat(), headerTextY.toFloat(), TEXT_WHITE, 0.8f)

        // Turn indicator
        val turnText = "Turn ${BattleStateTracker.currentTurn}"
        drawText(context, turnText, (x + width - PADDING - 45).toFloat(), headerTextY.toFloat(), TEXT_GOLD, 0.7f)

        // No drawn arrow - the Cobblemon texture has a built-in indicator in bottom-right

        // Content area (leave space at bottom for texture's built-in arrow area)
        val contentY = y + HEADER_HEIGHT + 2
        val contentAreaHeight = height - HEADER_HEIGHT - 14  // Space at bottom for arrow region

        // Calculate text width (assume scrollbar present for height calc to avoid circular dependency)
        val fullContentX = x + PADDING  // Full width start (for separators)
        val textStartX = x + PADDING + LOG_TEXT_INDENT  // Indented start (for text)
        val maxFullContentWidth = width - PADDING * 2 - SCROLLBAR_WIDTH - 4  // Assume scrollbar for height calc
        val maxTextContentWidth = maxFullContentWidth - LOG_TEXT_INDENT

        // Calculate content height with text wrapping
        val mc = MinecraftClient.getInstance()
        contentHeight = calculateContentHeight(mc, maxTextContentWidth)
        visibleHeight = contentAreaHeight

        val maxScroll = (contentHeight - visibleHeight).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)

        // Now calculate actual widths based on whether scrollbar is needed
        val scrollbarSpace = if (contentHeight > visibleHeight) SCROLLBAR_WIDTH + 4 else 0
        val fullContentWidth = width - PADDING * 2 - scrollbarSpace
        val textContentWidth = fullContentWidth - LOG_TEXT_INDENT

        // Render log entries with scissor (use full width for scissor to allow separators)
        enableScissor(context, fullContentX, contentY, fullContentWidth, contentAreaHeight)
        renderLogEntries(context, mc, fullContentX, textStartX, contentY, fullContentWidth, textContentWidth)
        disableScissor()

        // Scrollbar if needed
        if (contentHeight > visibleHeight) {
            renderScrollbar(context, x + width - SCROLLBAR_WIDTH - 3, contentY, contentAreaHeight)
        }
    }

    private fun calculateContentHeight(mc: MinecraftClient, textWidth: Int): Int {
        val entries = BattleLog.getEntries()
        if (entries.isEmpty()) return lineHeight

        val fontScale = 0.7f * PanelConfig.logFontScale
        var height = 0
        var lastTurn = -1

        for (entry in entries) {
            if (entry.turn != lastTurn) {
                if (lastTurn != -1) {
                    height += 3  // Gap before separator
                }
                height += lineHeight  // Turn header
                height += 2  // Gap after separator
                lastTurn = entry.turn
            }

            if (entry.type == BattleLog.EntryType.TURN) continue

            // Calculate wrapped line count for this entry
            val lines = wrapText(mc, entry.message.string, textWidth, fontScale)
            height += lineHeight * lines.size
        }

        return height.coerceAtLeast(lineHeight)
    }

    /**
     * Wraps text into multiple lines that fit within the given width.
     */
    private fun wrapText(mc: MinecraftClient, text: String, maxWidth: Int, scale: Float): List<String> {
        if (text.isEmpty()) return listOf("")

        val textRenderer = mc.textRenderer
        val scaledMaxWidth = (maxWidth / scale).toInt()

        val lines = mutableListOf<String>()
        val words = text.split(" ")
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testWidth = textRenderer.getWidth(testLine)

            if (testWidth <= scaledMaxWidth) {
                currentLine = StringBuilder(testLine)
            } else {
                // Current line is full, start a new one
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                // Check if single word is too long
                if (textRenderer.getWidth(word) > scaledMaxWidth) {
                    // Word itself is too long, need to break it
                    var remaining = word
                    while (remaining.isNotEmpty()) {
                        var fit = remaining
                        while (textRenderer.getWidth(fit) > scaledMaxWidth && fit.length > 1) {
                            fit = fit.dropLast(1)
                        }
                        lines.add(fit)
                        remaining = remaining.drop(fit.length)
                    }
                    currentLine = StringBuilder()
                } else {
                    currentLine = StringBuilder(word)
                }
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return if (lines.isEmpty()) listOf("") else lines
    }

    private fun renderLogEntries(context: DrawContext, mc: MinecraftClient, separatorX: Int, textX: Int, startY: Int, separatorWidth: Int, textWidth: Int) {
        val entries = BattleLog.getEntries()

        if (entries.isEmpty()) {
            val emptyY = startY + 4
            if (emptyY >= scissorMinY && emptyY <= scissorMaxY) {
                drawText(context, "No battle messages", textX.toFloat(), emptyY.toFloat(), TEXT_DIM, 0.7f * PanelConfig.logFontScale)
            }
            return
        }

        var currentY = startY - scrollOffset
        var lastTurn = -1
        val fontScale = 0.7f * PanelConfig.logFontScale

        for (entry in entries) {
            // Turn separator (uses full width, no indent)
            if (entry.turn != lastTurn) {
                if (lastTurn != -1) {
                    currentY += 3
                }

                if (currentY >= scissorMinY - lineHeight && currentY <= scissorMaxY + lineHeight) {
                    renderTurnSeparator(context, separatorX, currentY, separatorWidth, entry.turn)
                }
                currentY += lineHeight + 2
                lastTurn = entry.turn
            }

            if (entry.type == BattleLog.EntryType.TURN) continue

            // Render entry with text wrapping (uses indented position)
            val color = getEntryColor(entry.type)
            val lines = wrapText(mc, entry.message.string, textWidth, fontScale)

            for (line in lines) {
                if (currentY >= scissorMinY - lineHeight && currentY <= scissorMaxY) {
                    drawText(context, line, textX.toFloat(), currentY.toFloat(), color, fontScale)
                }
                currentY += lineHeight
            }
        }
    }

    private fun renderTurnSeparator(context: DrawContext, x: Int, y: Int, width: Int, turn: Int) {
        val centerY = y + lineHeight / 2
        val fontScale = 0.6f * PanelConfig.logFontScale

        // No background - just subtle lines and text that blend with the texture
        val turnText = "Turn $turn"
        val textWidth = (32 * PanelConfig.logFontScale).toInt()
        val lineEndLeft = x + (width - textWidth) / 2 - 6
        val lineStartRight = x + (width + textWidth) / 2 + 6

        // Subtle horizontal lines
        context.fill(x, centerY, lineEndLeft.coerceAtLeast(x), centerY + 1, TURN_LINE_COLOR)
        context.fill(lineStartRight.coerceAtMost(x + width), centerY, x + width, centerY + 1, TURN_LINE_COLOR)

        // Centered turn text
        val textX = x + (width - textWidth) / 2
        drawText(context, turnText, textX.toFloat(), (y + 1).toFloat(), TURN_TEXT_COLOR, fontScale)
    }

    private fun renderScrollbar(context: DrawContext, x: Int, y: Int, height: Int) {
        context.fill(x, y, x + SCROLLBAR_WIDTH, y + height, SCROLLBAR_BG)

        val thumbHeight = ((visibleHeight.toFloat() / contentHeight) * height).toInt().coerceAtLeast(10)
        val maxScroll = contentHeight - visibleHeight
        val ratio = if (maxScroll > 0) scrollOffset.toFloat() / maxScroll else 0f
        val thumbY = y + ((height - thumbHeight) * ratio).toInt()

        context.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, SCROLLBAR_THUMB)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getEntryColor(type: BattleLog.EntryType): Int {
        return when (type) {
            BattleLog.EntryType.MOVE -> COLOR_MOVE
            BattleLog.EntryType.HP -> COLOR_HP
            BattleLog.EntryType.EFFECT -> COLOR_EFFECT
            BattleLog.EntryType.FIELD -> COLOR_FIELD
            BattleLog.EntryType.TURN -> TURN_TEXT_COLOR
            else -> COLOR_OTHER
        }
    }

    private fun drawText(context: DrawContext, text: String, x: Float, y: Float, color: Int, scale: Float) {
        drawScaledText(
            context = context,
            text = Text.literal(text),
            x = x,
            y = y,
            scale = scale,
            colour = color,
            shadow = true
        )
    }

    private fun enableScissor(context: DrawContext, x: Int, y: Int, width: Int, height: Int) {
        scissorMinY = y
        scissorMaxY = y + height

        val mc = MinecraftClient.getInstance()
        val scale = mc.window.scaleFactor

        val scaledX = (x * scale).toInt()
        val scaledY = ((mc.window.scaledHeight - y - height) * scale).toInt()
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        RenderSystem.enableScissor(scaledX, scaledY, scaledWidth, scaledHeight)
    }

    private fun disableScissor() {
        RenderSystem.disableScissor()
    }
}

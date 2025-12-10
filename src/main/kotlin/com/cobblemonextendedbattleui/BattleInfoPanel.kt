package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.render.drawScaledText
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import java.util.UUID

/**
 * Pokemon Scarlet/Violet inspired battle information panel.
 * Features draggable positioning, edge/corner resizing, and scrollable content.
 */
object BattleInfoPanel {

    // Panel state
    var isExpanded: Boolean = false
        private set

    // Input tracking
    private var wasToggleKeyPressed: Boolean = false
    private var wasIncreaseFontKeyPressed: Boolean = false
    private var wasDecreaseFontKeyPressed: Boolean = false
    private var wasMousePressed: Boolean = false

    // Dragging state (for moving the panel)
    private var isDragging: Boolean = false
    private var dragOffsetX: Int = 0
    private var dragOffsetY: Int = 0
    private var dragStartX: Int = 0
    private var dragStartY: Int = 0
    private var hasDragged: Boolean = false
    private const val DRAG_THRESHOLD = 5

    // Resize state
    private var isResizing: Boolean = false
    private var resizeZone: ResizeZone = ResizeZone.NONE
    private var resizeStartX: Int = 0
    private var resizeStartY: Int = 0
    private var resizeStartWidth: Int = 0
    private var resizeStartHeight: Int = 0
    private var resizeStartPanelX: Int = 0
    private var resizeStartPanelY: Int = 0
    private const val RESIZE_HANDLE_SIZE = 6

    // Scrollbar dragging
    private var isScrollbarDragging: Boolean = false
    private var scrollbarDragStartY: Int = 0
    private var scrollbarDragStartOffset: Int = 0

    // Resize zones
    enum class ResizeZone {
        NONE,
        LEFT,
        RIGHT,
        TOP,
        BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    // Colors
    private val PANEL_BG = color(22, 27, 34, 240)
    private val HEADER_BG = color(30, 37, 46, 255)
    private val SECTION_BG = color(26, 32, 40, 255)
    private val BORDER_COLOR = color(55, 65, 80, 255)
    private val RESIZE_HANDLE_COLOR = color(100, 120, 140, 200)
    private val RESIZE_HANDLE_HOVER = color(130, 160, 190, 255)
    private val SCROLLBAR_BG = color(40, 48, 58, 200)
    private val SCROLLBAR_THUMB = color(80, 95, 115, 255)
    private val SCROLLBAR_THUMB_HOVER = color(100, 120, 145, 255)
    private val TEXT_WHITE = color(255, 255, 255, 255)
    private val TEXT_LIGHT = color(220, 225, 230, 255)
    private val TEXT_DIM = color(140, 150, 165, 255)
    private val TEXT_GOLD = color(255, 210, 80, 255)
    private val STAT_BOOST = color(255, 100, 100, 255)
    private val STAT_DROP = color(100, 160, 255, 255)
    private val ACCENT_PLAYER = color(100, 200, 255, 255)
    private val ACCENT_OPPONENT = color(255, 130, 110, 255)
    private val ACCENT_FIELD = color(255, 200, 100, 255)

    // Base layout constants (will be scaled based on panel size)
    private const val BASE_PANEL_MARGIN = 10
    private const val BASE_PADDING = 8
    private const val BASE_LINE_HEIGHT = 12
    private const val BASE_SECTION_GAP = 6
    private const val BASE_HEADER_HEIGHT = 22
    private const val SCROLLBAR_WIDTH = 3

    // Base font multiplier (makes default text ~20% larger)
    private const val BASE_FONT_MULTIPLIER = 1.2f

    // Text scale (base * user font preference only, no auto-scaling)
    private var textScale: Float = BASE_FONT_MULTIPLIER

    // Fixed layout values
    private const val PADDING = BASE_PADDING
    private const val SECTION_GAP = BASE_SECTION_GAP
    private const val HEADER_HEIGHT = BASE_HEADER_HEIGHT

    // Line height scales with font
    private var lineHeight: Int = BASE_LINE_HEIGHT

    // Cached panel bounds for input detection
    private var panelBoundsX = 0
    private var panelBoundsY = 0
    private var panelBoundsW = 0
    private var panelBoundsH = 0
    private var headerEndY = 0

    // Content dimensions (actual content height vs visible height)
    private var contentHeight = 0
    private var visibleContentHeight = 0

    // Scissor bounds for manual clipping (drawScaledText doesn't respect GL scissor)
    private var scissorMinY = 0
    private var scissorMaxY = 0

    // Hover state for visual feedback
    private var hoveredZone: ResizeZone = ResizeZone.NONE
    private var isOverScrollbar = false

    // Track previously active Pokemon to detect switches and clear their stats
    private var previouslyActiveUUIDs: Set<UUID> = emptySet()

    fun clearBattleState() {
        previouslyActiveUUIDs = emptySet()
    }

    /**
     * Check if a point is within the given bounds array [x, y, w, h].
     */
    private fun isOverBounds(mouseX: Int, mouseY: Int, bounds: IntArray): Boolean {
        if (bounds.size < 4) return false
        return mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2] &&
               mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3]
    }

    private fun color(r: Int, g: Int, b: Int, a: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun updateScaledValues() {
        // Text scale: base multiplier * user font preference (no auto-scaling)
        textScale = BASE_FONT_MULTIPLIER * PanelConfig.fontScale
        lineHeight = (BASE_LINE_HEIGHT * textScale).toInt()
    }

    fun toggle() {
        isExpanded = !isExpanded
        PanelConfig.setStartExpanded(isExpanded)
        PanelConfig.scrollOffset = 0
        PanelConfig.save()
    }

    fun initialize() {
        PanelConfig.load()
        isExpanded = PanelConfig.startExpanded
    }

    private fun getResizeZone(mouseX: Int, mouseY: Int): ResizeZone {
        // No resizing in collapsed mode
        if (!isExpanded) return ResizeZone.NONE

        val x = panelBoundsX
        val y = panelBoundsY
        val w = panelBoundsW
        val h = panelBoundsH

        val onLeft = mouseX >= x - RESIZE_HANDLE_SIZE && mouseX <= x + RESIZE_HANDLE_SIZE
        val onRight = mouseX >= x + w - RESIZE_HANDLE_SIZE && mouseX <= x + w + RESIZE_HANDLE_SIZE
        val onTop = mouseY >= y - RESIZE_HANDLE_SIZE && mouseY <= y + 2  // Reduced to avoid header conflict
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

    private fun isOverScrollbarThumb(mouseX: Int, mouseY: Int): Boolean {
        if (contentHeight <= visibleContentHeight) return false

        val scrollbarX = panelBoundsX + panelBoundsW - SCROLLBAR_WIDTH - 2
        val scrollbarY = headerEndY + 2
        val scrollbarHeight = panelBoundsY + panelBoundsH - headerEndY - 4

        if (mouseX < scrollbarX || mouseX > scrollbarX + SCROLLBAR_WIDTH) return false
        if (mouseY < scrollbarY || mouseY > scrollbarY + scrollbarHeight) return false

        val thumbHeight = ((visibleContentHeight.toFloat() / contentHeight) * scrollbarHeight).toInt().coerceAtLeast(20)
        val maxScroll = contentHeight - visibleContentHeight
        val scrollRatio = if (maxScroll > 0) PanelConfig.scrollOffset.toFloat() / maxScroll else 0f
        val thumbY = scrollbarY + ((scrollbarHeight - thumbHeight) * scrollRatio).toInt()

        return mouseY >= thumbY && mouseY <= thumbY + thumbHeight
    }

    private fun isKeyOrButtonPressed(handle: Long, key: InputUtil.Key): Boolean {
        return when (key.category) {
            InputUtil.Type.MOUSE -> GLFW.glfwGetMouseButton(handle, key.code) == GLFW.GLFW_PRESS
            else -> GLFW.glfwGetKey(handle, key.code) == GLFW.GLFW_PRESS
        }
    }

    private fun handleInput(mc: MinecraftClient) {
        val handle = mc.window.handle

        // Poll keybinds directly with GLFW (Minecraft's keybinding system doesn't work during Cobblemon battle overlay)
        val toggleKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.togglePanelKey.boundKeyTranslationKey)
        val isToggleDown = isKeyOrButtonPressed(handle, toggleKey)
        if (isToggleDown && !wasToggleKeyPressed) toggle()
        wasToggleKeyPressed = isToggleDown

        val increaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.increaseFontKey.boundKeyTranslationKey)
        val isIncreaseDown = isKeyOrButtonPressed(handle, increaseKey)
        if (isIncreaseDown && !wasIncreaseFontKeyPressed) {
            PanelConfig.adjustFontScale(PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasIncreaseFontKeyPressed = isIncreaseDown

        val decreaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.decreaseFontKey.boundKeyTranslationKey)
        val isDecreaseDown = isKeyOrButtonPressed(handle, decreaseKey)
        if (isDecreaseDown && !wasDecreaseFontKeyPressed) {
            PanelConfig.adjustFontScale(-PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasDecreaseFontKeyPressed = isDecreaseDown

        val mouseX = (mc.mouse.x * mc.window.scaledWidth / mc.window.width).toInt()
        val mouseY = (mc.mouse.y * mc.window.scaledHeight / mc.window.height).toInt()
        val isMouseDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS

        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight

        hoveredZone = if (!isDragging && !isResizing && !isScrollbarDragging) getResizeZone(mouseX, mouseY) else hoveredZone
        isOverScrollbar = if (!isDragging && !isResizing && !isScrollbarDragging) isOverScrollbarThumb(mouseX, mouseY) else isOverScrollbar

        val isOverPanel = mouseX >= panelBoundsX && mouseX <= panelBoundsX + panelBoundsW &&
                          mouseY >= panelBoundsY && mouseY <= panelBoundsY + panelBoundsH
        val isOverHeader = isOverPanel && mouseY <= headerEndY

        if (isMouseDown) {
            when {
                !wasMousePressed && isOverScrollbarThumb(mouseX, mouseY) -> {
                    isScrollbarDragging = true
                    scrollbarDragStartY = mouseY
                    scrollbarDragStartOffset = PanelConfig.scrollOffset
                }
                isScrollbarDragging -> {
                    val scrollbarHeight = panelBoundsY + panelBoundsH - headerEndY - 4
                    val thumbHeight = ((visibleContentHeight.toFloat() / contentHeight) * scrollbarHeight).toInt().coerceAtLeast(20)
                    val trackHeight = scrollbarHeight - thumbHeight

                    if (trackHeight > 0) {
                        val deltaY = mouseY - scrollbarDragStartY
                        val maxScroll = contentHeight - visibleContentHeight
                        val scrollDelta = (deltaY.toFloat() / trackHeight * maxScroll).toInt()
                        PanelConfig.scrollOffset = (scrollbarDragStartOffset + scrollDelta).coerceIn(0, maxScroll)
                    }
                }
                !wasMousePressed && hoveredZone != ResizeZone.NONE -> {
                    isResizing = true
                    resizeZone = hoveredZone
                    resizeStartX = mouseX
                    resizeStartY = mouseY
                    resizeStartWidth = panelBoundsW
                    resizeStartHeight = panelBoundsH
                    resizeStartPanelX = panelBoundsX
                    resizeStartPanelY = panelBoundsY
                }
                isResizing -> {
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

                    val minW = PanelConfig.getMinWidth()
                    val minH = PanelConfig.getMinHeight()
                    val maxW = PanelConfig.getMaxWidth(screenWidth)
                    val maxH = PanelConfig.getMaxHeight(screenHeight)

                    if (resizeZone in listOf(ResizeZone.LEFT, ResizeZone.TOP_LEFT, ResizeZone.BOTTOM_LEFT)) {
                        if (newWidth < minW) {
                            newX = resizeStartPanelX + resizeStartWidth - minW
                            newWidth = minW
                        }
                        if (newWidth > maxW) {
                            newX = resizeStartPanelX + resizeStartWidth - maxW
                            newWidth = maxW
                        }
                    }
                    if (resizeZone in listOf(ResizeZone.TOP, ResizeZone.TOP_LEFT, ResizeZone.TOP_RIGHT)) {
                        if (newHeight < minH) {
                            newY = resizeStartPanelY + resizeStartHeight - minH
                            newHeight = minH
                        }
                        if (newHeight > maxH) {
                            newY = resizeStartPanelY + resizeStartHeight - maxH
                            newHeight = maxH
                        }
                    }

                    newWidth = newWidth.coerceIn(minW, maxW)
                    newHeight = newHeight.coerceIn(minH, maxH)
                    newX = newX.coerceIn(0, screenWidth - newWidth)
                    newY = newY.coerceIn(0, screenHeight - newHeight)

                    PanelConfig.setDimensions(newWidth, newHeight)
                    PanelConfig.setPosition(newX, newY)
                }
                !wasMousePressed && isOverHeader -> {
                    isDragging = true
                    hasDragged = false
                    dragOffsetX = mouseX - panelBoundsX
                    dragOffsetY = mouseY - panelBoundsY
                    dragStartX = mouseX
                    dragStartY = mouseY
                }
                isDragging -> {
                    val deltaX = kotlin.math.abs(mouseX - dragStartX)
                    val deltaY = kotlin.math.abs(mouseY - dragStartY)
                    if (deltaX > DRAG_THRESHOLD || deltaY > DRAG_THRESHOLD) {
                        hasDragged = true
                    }
                    if (hasDragged) {
                        PanelConfig.setPosition(mouseX - dragOffsetX, mouseY - dragOffsetY)
                    }
                }
            }
        } else {
            if (isScrollbarDragging) {
                isScrollbarDragging = false
            }
            if (isResizing) {
                isResizing = false
                resizeZone = ResizeZone.NONE
                PanelConfig.save()
            }
            if (isDragging) {
                if (hasDragged) {
                    PanelConfig.save()
                } else {
                    toggle()
                }
                isDragging = false
                hasDragged = false
            }
        }
        wasMousePressed = isMouseDown
    }

    fun onScroll(mouseX: Double, mouseY: Double, deltaY: Double): Boolean {
        val mc = MinecraftClient.getInstance()
        val scaledX = (mouseX * mc.window.scaledWidth / mc.window.width).toInt()
        val scaledY = (mouseY * mc.window.scaledHeight / mc.window.height).toInt()

        val isOverPanel = scaledX >= panelBoundsX && scaledX <= panelBoundsX + panelBoundsW &&
                          scaledY >= panelBoundsY && scaledY <= panelBoundsY + panelBoundsH

        if (isOverPanel && deltaY != 0.0) {
            // Check if Ctrl is held for font scaling
            val isCtrlHeld = GLFW.glfwGetKey(mc.window.handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                             GLFW.glfwGetKey(mc.window.handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS

            if (isCtrlHeld) {
                // Ctrl+Scroll: adjust font size
                val delta = if (deltaY > 0) PanelConfig.FONT_SCALE_STEP else -PanelConfig.FONT_SCALE_STEP
                PanelConfig.adjustFontScale(delta)
                PanelConfig.save()
                return true
            } else {
                // Normal scroll: scroll content
                val scrollAmount = (lineHeight * 2 * if (deltaY > 0) -1 else 1)
                if (contentHeight > visibleContentHeight) {
                    val maxScroll = (contentHeight - visibleContentHeight).coerceAtLeast(0)
                    PanelConfig.scrollOffset = (PanelConfig.scrollOffset + scrollAmount).coerceIn(0, maxScroll)
                    return true
                }
            }
        }
        return false
    }

    fun render(context: DrawContext) {
        val battle = CobblemonClient.battle ?: return
        if (battle.minimised) return

        // Clear state if this is a new battle
        BattleStateTracker.checkBattleChanged(battle.battleId)

        val mc = MinecraftClient.getInstance()
        handleInput(mc)

        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight

        val playerUUID = mc.player?.uuid ?: return
        val playerSide = if (battle.side1.actors.any { it.uuid == playerUUID }) battle.side1 else battle.side2
        val opponentSide = if (playerSide == battle.side1) battle.side2 else battle.side1

        val allPokemon = (playerSide.activeClientBattlePokemon + opponentSide.activeClientBattlePokemon)
            .mapNotNull { it.battlePokemon }

        // Get current active Pokemon UUIDs
        val currentActiveUUIDs = allPokemon.map { it.uuid }.toSet()

        // Clear stats for any Pokemon that was previously active but is no longer
        // (switching out resets stat changes in Pokemon)
        for (uuid in previouslyActiveUUIDs) {
            if (uuid !in currentActiveUUIDs) {
                BattleStateTracker.clearPokemonStats(uuid)
            }
        }
        previouslyActiveUUIDs = currentActiveUUIDs

        for (pokemon in allPokemon) {
            BattleStateTracker.registerPokemon(pokemon.uuid, pokemon.displayName.string)
        }

        // Build Pokemon data with stat changes for height calculation and rendering
        val pokemonWithStats = allPokemon.map { pokemon ->
            val stats = BattleStateTracker.getStatChanges(pokemon.uuid)
            val changedStats = stats.entries.filter { it.value != 0 }
            Triple(pokemon.uuid, pokemon.displayName.string, changedStats)
        }

        // Update font scaling based on user preference
        updateScaledValues()

        // Get panel dimensions - collapsed auto-fits content, expanded uses user settings
        val panelWidth: Int
        val panelHeight: Int

        if (isExpanded) {
            panelWidth = PanelConfig.panelWidth ?: PanelConfig.DEFAULT_WIDTH
            contentHeight = calculateExpandedContentHeight(pokemonWithStats, panelWidth)
            panelHeight = PanelConfig.panelHeight ?: (contentHeight + HEADER_HEIGHT + PADDING * 2)
        } else {
            // Collapsed: use same width, auto-fit height to content (never scroll)
            panelWidth = PanelConfig.panelWidth ?: PanelConfig.DEFAULT_WIDTH
            contentHeight = calculateCollapsedContentHeight(pokemonWithStats)
            // Use PADDING * 2 to match visibleContentHeight calculation and prevent scrollbar
            panelHeight = contentHeight + HEADER_HEIGHT + PADDING * 2
        }

        val panelX = PanelConfig.panelX ?: (screenWidth - panelWidth - BASE_PANEL_MARGIN)
        val panelY = PanelConfig.panelY ?: ((screenHeight - panelHeight) / 2)

        val clampedX = panelX.coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0))
        val clampedY = panelY.coerceIn(0, (screenHeight - panelHeight).coerceAtLeast(0))

        panelBoundsX = clampedX
        panelBoundsY = clampedY
        panelBoundsW = panelWidth
        panelBoundsH = panelHeight

        // Must match contentAreaHeight used in scissor: height - HEADER_HEIGHT - PADDING
        visibleContentHeight = panelHeight - HEADER_HEIGHT - PADDING

        val maxScroll = (contentHeight - visibleContentHeight).coerceAtLeast(0)
        PanelConfig.scrollOffset = PanelConfig.scrollOffset.coerceIn(0, maxScroll)

        if (isExpanded) {
            renderExpanded(context, clampedX, clampedY, panelWidth, panelHeight, pokemonWithStats)
        } else {
            renderCollapsed(context, clampedX, clampedY, panelWidth, panelHeight, pokemonWithStats)
        }

        // Only draw resize handles in expanded mode
        if (isExpanded) {
            drawResizeHandles(context, clampedX, clampedY, panelWidth, panelHeight)
        }
    }

    private fun drawResizeHandles(context: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        val handleColor = if (hoveredZone != ResizeZone.NONE || isResizing) RESIZE_HANDLE_HOVER else RESIZE_HANDLE_COLOR
        val cornerLength = 12  // Length of each arm of the L-shape
        val thickness = 2      // Thickness of the lines

        // Always show bottom-right corner handle (most common resize)
        drawCornerHandle(context, x + w, y + h, cornerLength, thickness, handleColor, bottomRight = true)

        // Show all handles when hovering or resizing
        if (hoveredZone != ResizeZone.NONE || isResizing) {
            // Corner L-shapes
            drawCornerHandle(context, x, y, cornerLength, thickness, handleColor, topLeft = true)
            drawCornerHandle(context, x + w, y, cornerLength, thickness, handleColor, topRight = true)
            drawCornerHandle(context, x, y + h, cornerLength, thickness, handleColor, bottomLeft = true)

            // Edge handles (small lines in the middle of each edge)
            val edgeLength = 16
            val midX = x + w / 2
            val midY = y + h / 2

            // Top edge
            context.fill(midX - edgeLength / 2, y, midX + edgeLength / 2, y + thickness, handleColor)
            // Bottom edge
            context.fill(midX - edgeLength / 2, y + h - thickness, midX + edgeLength / 2, y + h, handleColor)
            // Left edge
            context.fill(x, midY - edgeLength / 2, x + thickness, midY + edgeLength / 2, handleColor)
            // Right edge
            context.fill(x + w - thickness, midY - edgeLength / 2, x + w, midY + edgeLength / 2, handleColor)
        }
    }

    private fun drawCornerHandle(
        context: DrawContext,
        cornerX: Int,
        cornerY: Int,
        length: Int,
        thickness: Int,
        color: Int,
        topLeft: Boolean = false,
        topRight: Boolean = false,
        bottomLeft: Boolean = false,
        bottomRight: Boolean = false
    ) {
        when {
            topLeft -> {
                // Horizontal arm going right
                context.fill(cornerX, cornerY, cornerX + length, cornerY + thickness, color)
                // Vertical arm going down
                context.fill(cornerX, cornerY, cornerX + thickness, cornerY + length, color)
            }
            topRight -> {
                // Horizontal arm going left
                context.fill(cornerX - length, cornerY, cornerX, cornerY + thickness, color)
                // Vertical arm going down
                context.fill(cornerX - thickness, cornerY, cornerX, cornerY + length, color)
            }
            bottomLeft -> {
                // Horizontal arm going right
                context.fill(cornerX, cornerY - thickness, cornerX + length, cornerY, color)
                // Vertical arm going up
                context.fill(cornerX, cornerY - length, cornerX + thickness, cornerY, color)
            }
            bottomRight -> {
                // Horizontal arm going left
                context.fill(cornerX - length, cornerY - thickness, cornerX, cornerY, color)
                // Vertical arm going up
                context.fill(cornerX - thickness, cornerY - length, cornerX, cornerY, color)
            }
        }
    }

    private fun renderScrollbar(context: DrawContext, x: Int, y: Int, height: Int) {
        if (contentHeight <= visibleContentHeight) return

        context.fill(x, y, x + SCROLLBAR_WIDTH, y + height, SCROLLBAR_BG)

        val thumbHeight = ((visibleContentHeight.toFloat() / contentHeight) * height).toInt().coerceAtLeast(20)
        val maxScroll = contentHeight - visibleContentHeight
        val scrollRatio = if (maxScroll > 0) PanelConfig.scrollOffset.toFloat() / maxScroll else 0f
        val thumbY = y + ((height - thumbHeight) * scrollRatio).toInt()

        val thumbColor = if (isOverScrollbar || isScrollbarDragging) SCROLLBAR_THUMB_HOVER else SCROLLBAR_THUMB
        context.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, thumbColor)
    }

    private fun renderCollapsed(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pokemonWithStats: List<Triple<UUID, String, List<Map.Entry<com.cobblemon.mod.common.api.pokemon.stats.Stat, Int>>>>
    ) {
        drawRoundedRect(context, x, y, width, height, PANEL_BG, BORDER_COLOR)
        renderHeader(context, x, y, width)

        val scissorStartY = y + HEADER_HEIGHT + 2
        val contentAreaHeight = height - HEADER_HEIGHT - PADDING / 2

        enableScissor(context, x + 1, scissorStartY, width - 2, contentAreaHeight)

        var currentY = scissorStartY + 2

        val hasWeather = BattleStateTracker.weather != null
        val hasTerrain = BattleStateTracker.terrain != null
        val fieldConditions = BattleStateTracker.getFieldConditions()
        val playerConds = BattleStateTracker.getPlayerSideConditions()
        val oppConds = BattleStateTracker.getOpponentSideConditions()

        // Count only ACTIVE Pokemon with stat changes (not switched out or fainted)
        val statChangeCount = pokemonWithStats.count { (_, _, changedStats) ->
            changedStats.isNotEmpty()
        }

        val hasAnyEffects = hasWeather || hasTerrain || fieldConditions.isNotEmpty() ||
                           playerConds.isNotEmpty() || oppConds.isNotEmpty() || statChangeCount > 0

        if (!hasAnyEffects) {
            drawText(context, "No effects", (x + PADDING).toFloat(), currentY.toFloat(), TEXT_DIM, 0.8f * textScale)
        } else {
            BattleStateTracker.weather?.let { w ->
                val turns = BattleStateTracker.getWeatherTurnsRemaining() ?: "?"
                drawText(context, "${w.type.icon} ${w.type.displayName}", (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_FIELD, 0.8f * textScale)
                drawText(context, turns, (x + width - PADDING - turns.length * (5 * textScale).toInt()).toFloat(), currentY.toFloat(), TEXT_DIM, 0.75f * textScale)
                currentY += lineHeight
            }

            BattleStateTracker.terrain?.let { t ->
                val turns = BattleStateTracker.getTerrainTurnsRemaining() ?: "?"
                drawText(context, "${t.type.icon} ${t.type.displayName}", (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_FIELD, 0.8f * textScale)
                drawText(context, turns, (x + width - PADDING - turns.length * (5 * textScale).toInt()).toFloat(), currentY.toFloat(), TEXT_DIM, 0.75f * textScale)
                currentY += lineHeight
            }

            fieldConditions.forEach { (type, _) ->
                val turns = BattleStateTracker.getFieldConditionTurnsRemaining(type) ?: "?"
                drawText(context, "${type.icon} ${type.displayName}", (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_FIELD, 0.8f * textScale)
                drawText(context, turns, (x + width - PADDING - turns.length * (5 * textScale).toInt()).toFloat(), currentY.toFloat(), TEXT_DIM, 0.75f * textScale)
                currentY += lineHeight
            }

            if (playerConds.isNotEmpty()) {
                drawText(context, "Ally: ${playerConds.size} effect${if (playerConds.size > 1) "s" else ""}",
                    (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_PLAYER, 0.8f * textScale)
                currentY += (lineHeight * 0.9).toInt()
            }

            if (oppConds.isNotEmpty()) {
                drawText(context, "Enemy: ${oppConds.size} effect${if (oppConds.size > 1) "s" else ""}",
                    (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_OPPONENT, 0.8f * textScale)
                currentY += (lineHeight * 0.9).toInt()
            }

            if (statChangeCount > 0) {
                val pokemonText = if (statChangeCount == 1) "1 Pokémon" else "$statChangeCount Pokémon"
                drawText(context, "Stats: $pokemonText modified",
                    (x + PADDING).toFloat(), currentY.toFloat(), TEXT_GOLD, 0.8f * textScale)
            }
        }

        disableScissor()
    }

    private fun renderExpanded(context: DrawContext, x: Int, y: Int, width: Int, height: Int, pokemonWithStats: List<Triple<UUID, String, List<Map.Entry<com.cobblemon.mod.common.api.pokemon.stats.Stat, Int>>>>) {
        drawRoundedRect(context, x, y, width, height, PANEL_BG, BORDER_COLOR)
        renderHeader(context, x, y, width)

        // Render content
        renderInfoTab(context, x, y, width, height, pokemonWithStats)
    }

    /**
     * Render the Info tab content (field conditions, side conditions, stat stages).
     */
    private fun renderInfoTab(context: DrawContext, x: Int, y: Int, width: Int, height: Int, pokemonWithStats: List<Triple<UUID, String, List<Map.Entry<com.cobblemon.mod.common.api.pokemon.stats.Stat, Int>>>>) {
        val contentStartY = y + HEADER_HEIGHT + SECTION_GAP
        val contentAreaHeight = height - HEADER_HEIGHT - PADDING
        val scrollbarSpace = if (contentHeight > visibleContentHeight) SCROLLBAR_WIDTH + 4 else 0
        val contentWidth = width - scrollbarSpace

        enableScissor(context, x + 1, contentStartY, contentWidth - 2, contentAreaHeight)

        var currentY = contentStartY - PanelConfig.scrollOffset

        currentY = renderSection(context, x, currentY, contentWidth, "FIELD", ACCENT_FIELD) { sectionY ->
            var sy = sectionY
            var hasContent = false

            BattleStateTracker.weather?.let { w ->
                val turns = BattleStateTracker.getWeatherTurnsRemaining() ?: "?"
                drawConditionLine(context, x + PADDING, sy, contentWidth - PADDING * 2,
                    w.type.icon, w.type.displayName, turns, ACCENT_FIELD)
                sy += lineHeight
                hasContent = true
            }

            BattleStateTracker.terrain?.let { t ->
                val turns = BattleStateTracker.getTerrainTurnsRemaining() ?: "?"
                drawConditionLine(context, x + PADDING, sy, contentWidth - PADDING * 2,
                    t.type.icon, t.type.displayName, turns, ACCENT_FIELD)
                sy += lineHeight
                hasContent = true
            }

            BattleStateTracker.getFieldConditions().forEach { (type, _) ->
                val turns = BattleStateTracker.getFieldConditionTurnsRemaining(type) ?: "?"
                drawConditionLine(context, x + PADDING, sy, contentWidth - PADDING * 2,
                    type.icon, type.displayName, turns, ACCENT_FIELD)
                sy += lineHeight
                hasContent = true
            }

            if (!hasContent) {
                drawText(context, "None active", (x + PADDING).toFloat(), sy.toFloat(), TEXT_DIM, 0.8f * textScale)
                sy += lineHeight
            }
            sy
        }

        currentY += SECTION_GAP

        currentY = renderSection(context, x, currentY, contentWidth, "ALLY EFFECTS", ACCENT_PLAYER) { sectionY ->
            var sy = sectionY
            val conditions = BattleStateTracker.getPlayerSideConditions()

            if (conditions.isNotEmpty()) {
                conditions.forEach { (type, state) ->
                    val info = when {
                        BattleStateTracker.getSideConditionTurnsRemaining(true, type) != null ->
                            BattleStateTracker.getSideConditionTurnsRemaining(true, type)!!
                        state.stacks > 1 -> "x${state.stacks}"
                        else -> ""
                    }
                    drawConditionLine(context, x + PADDING, sy, contentWidth - PADDING * 2,
                        type.icon, type.displayName, info, ACCENT_PLAYER)
                    sy += lineHeight
                }
            } else {
                drawText(context, "None active", (x + PADDING).toFloat(), sy.toFloat(), TEXT_DIM, 0.8f * textScale)
                sy += lineHeight
            }
            sy
        }

        currentY += SECTION_GAP

        currentY = renderSection(context, x, currentY, contentWidth, "ENEMY EFFECTS", ACCENT_OPPONENT) { sectionY ->
            var sy = sectionY
            val conditions = BattleStateTracker.getOpponentSideConditions()

            if (conditions.isNotEmpty()) {
                conditions.forEach { (type, state) ->
                    val info = when {
                        BattleStateTracker.getSideConditionTurnsRemaining(false, type) != null ->
                            BattleStateTracker.getSideConditionTurnsRemaining(false, type)!!
                        state.stacks > 1 -> "x${state.stacks}"
                        else -> ""
                    }
                    drawConditionLine(context, x + PADDING, sy, contentWidth - PADDING * 2,
                        type.icon, type.displayName, info, ACCENT_OPPONENT)
                    sy += lineHeight
                }
            } else {
                drawText(context, "None active", (x + PADDING).toFloat(), sy.toFloat(), TEXT_DIM, 0.8f * textScale)
                sy += lineHeight
            }
            sy
        }

        currentY += SECTION_GAP

        val hasAnyStatChanges = pokemonWithStats.any { it.third.isNotEmpty() }

        renderSection(context, x, currentY, contentWidth, "STAT STAGES", TEXT_GOLD) { sectionY ->
            var sy = sectionY

            if (!hasAnyStatChanges) {
                drawTextClipped(context, "None active", (x + PADDING).toFloat(), sy.toFloat(), TEXT_DIM, 0.8f * textScale)
                sy += lineHeight
            } else {
                for ((_, name, changedStats) in pokemonWithStats) {
                    if (changedStats.isEmpty()) continue

                    drawTextClipped(context, name, (x + PADDING).toFloat(), sy.toFloat(), TEXT_WHITE, 0.85f * textScale)
                    sy += (lineHeight * 0.95).toInt()

                    val sortedStats = changedStats.sortedBy { getStatSortOrder(it.key.identifier.toString()) }
                    val charWidth = (5 * textScale).toInt()
                    val startX = x + PADDING + (8 * textScale).toInt()

                    var statX = startX
                    for ((stat, value) in sortedStats) {
                        val abbr = getStatAbbr(stat.identifier.toString())
                        val arrows = if (value > 0) "↑".repeat(value) else "↓".repeat(-value)
                        val color = if (value > 0) STAT_BOOST else STAT_DROP

                        val entryWidth = ((abbr.length * charWidth) + 2 + (arrows.length * charWidth) + (8 * textScale)).toInt()

                        if (statX + entryWidth > x + contentWidth - PADDING && statX != startX) {
                            sy += (lineHeight * 0.9).toInt()
                            statX = startX
                        }

                        drawTextClipped(context, abbr, statX.toFloat(), sy.toFloat(), TEXT_LIGHT, 0.75f * textScale)
                        statX += (abbr.length * charWidth) + 2
                        drawTextClipped(context, arrows, statX.toFloat(), sy.toFloat(), color, 0.75f * textScale)
                        statX += (arrows.length * charWidth) + (8 * textScale).toInt()
                    }
                    sy += (lineHeight * 0.9).toInt()
                }
            }
            sy
        }

        disableScissor()

        if (contentHeight > visibleContentHeight) {
            renderScrollbar(context, x + width - SCROLLBAR_WIDTH - 2, contentStartY, contentAreaHeight)
        }
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

    private fun renderHeader(context: DrawContext, x: Int, y: Int, width: Int) {
        context.fill(x + 1, y + 1, x + width - 1, y + HEADER_HEIGHT, HEADER_BG)
        headerEndY = y + HEADER_HEIGHT
        context.fill(x, y + HEADER_HEIGHT - 1, x + width, y + HEADER_HEIGHT, BORDER_COLOR)

        val headerTextY = y + (HEADER_HEIGHT - (8 * textScale).toInt()) / 2

        if (isExpanded) {
            // Expanded mode: show title and turn indicator
            val arrow = "▼"
            drawText(context, arrow, (x + PADDING).toFloat(), headerTextY.toFloat(), TEXT_GOLD, 0.85f * textScale)
            drawText(context, "BATTLE INFO", (x + PADDING + (12 * textScale).toInt()).toFloat(), headerTextY.toFloat(), TEXT_WHITE, 0.85f * textScale)

            val turnText = "T${BattleStateTracker.currentTurn}"
            val charWidth = (5 * textScale).toInt()
            drawText(context, turnText, (x + width - PADDING - turnText.length * charWidth).toFloat(),
                headerTextY.toFloat(), TEXT_GOLD, 0.85f * textScale)
        } else {
            // Collapsed mode: show arrow and title
            val arrow = "▶"
            drawText(context, arrow, (x + PADDING).toFloat(), headerTextY.toFloat(), TEXT_GOLD, 0.85f * textScale)
            drawText(context, "BATTLE INFO", (x + PADDING + (12 * textScale).toInt()).toFloat(), headerTextY.toFloat(), TEXT_WHITE, 0.85f * textScale)

            val turnText = "T${BattleStateTracker.currentTurn}"
            val charWidth = (5 * textScale).toInt()
            drawText(context, turnText, (x + width - PADDING - turnText.length * charWidth).toFloat(),
                headerTextY.toFloat(), TEXT_GOLD, 0.85f * textScale)
        }
    }

    private fun renderSection(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        title: String,
        accentColor: Int,
        contentRenderer: (Int) -> Int
    ): Int {
        val sectionHeight = lineHeight + 1
        context.fill(x + 1, y, x + width - 1, y + sectionHeight, SECTION_BG)
        context.fill(x + 1, y, x + 3, y + sectionHeight, accentColor)

        // Vertically center the title text in the section header
        val textHeight = (8 * 0.7f * textScale).toInt()
        val titleY = y + (sectionHeight - textHeight) / 2
        drawText(context, title, (x + PADDING).toFloat(), titleY.toFloat(), accentColor, 0.7f * textScale)

        return contentRenderer(y + sectionHeight + 1)
    }

    private fun drawConditionLine(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        icon: String,
        name: String,
        info: String,
        accentColor: Int
    ) {
        val charWidth = (5 * textScale).toInt()
        drawText(context, icon, x.toFloat(), y.toFloat(), accentColor, 0.8f * textScale)
        drawText(context, name, (x + (14 * textScale).toInt()).toFloat(), y.toFloat(), TEXT_LIGHT, 0.8f * textScale)
        if (info.isNotEmpty()) {
            val infoWidth = info.length * charWidth
            drawText(context, info, (x + width - infoWidth).toFloat(), y.toFloat(), TEXT_DIM, 0.75f * textScale)
        }
    }

    private fun drawRoundedRect(context: DrawContext, x: Int, y: Int, w: Int, h: Int, fillColor: Int, borderColor: Int) {
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, fillColor)
        context.fill(x, y + 1, x + 1, y + h - 1, borderColor)
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, borderColor)
        context.fill(x + 1, y, x + w - 1, y + 1, borderColor)
        context.fill(x + 1, y + h - 1, x + w - 1, y + h, borderColor)
    }

    private fun calculateCollapsedContentHeight(
        pokemonWithStats: List<Triple<UUID, String, List<Map.Entry<com.cobblemon.mod.common.api.pokemon.stats.Stat, Int>>>>
    ): Int {
        var height = PADDING / 2

        val hasWeather = BattleStateTracker.weather != null
        val hasTerrain = BattleStateTracker.terrain != null
        val fieldCount = BattleStateTracker.getFieldConditions().size
        val playerConds = BattleStateTracker.getPlayerSideConditions()
        val oppConds = BattleStateTracker.getOpponentSideConditions()
        // Count only ACTIVE Pokemon with stat changes
        val statChangeCount = pokemonWithStats.count { (_, _, changedStats) ->
            changedStats.isNotEmpty()
        }

        val hasAnyEffects = hasWeather || hasTerrain || fieldCount > 0 ||
                           playerConds.isNotEmpty() || oppConds.isNotEmpty() || statChangeCount > 0

        if (!hasAnyEffects) {
            height += lineHeight  // "No effects" text
        } else {
            if (hasWeather) height += lineHeight
            if (hasTerrain) height += lineHeight
            height += fieldCount * lineHeight
            if (playerConds.isNotEmpty()) height += (lineHeight * 0.9).toInt()
            if (oppConds.isNotEmpty()) height += (lineHeight * 0.9).toInt()
            if (statChangeCount > 0) height += (lineHeight * 0.9).toInt()
        }

        return height
    }

    private fun calculateExpandedContentHeight(
        pokemonWithStats: List<Triple<UUID, String, List<Map.Entry<com.cobblemon.mod.common.api.pokemon.stats.Stat, Int>>>>,
        panelWidth: Int
    ): Int {
        // Content starts at contentStartY (no initial gap - first section starts immediately)
        var height = 0

        // Field section
        height += lineHeight + 2  // Section header (reduced)
        val fieldCount = listOfNotNull(BattleStateTracker.weather, BattleStateTracker.terrain).size +
                         BattleStateTracker.getFieldConditions().size
        height += (if (fieldCount > 0) fieldCount else 1) * lineHeight
        height += SECTION_GAP

        // Ally section
        height += lineHeight + 2  // Section header (reduced)
        val playerCount = BattleStateTracker.getPlayerSideConditions().size
        height += (if (playerCount > 0) playerCount else 1) * lineHeight
        height += SECTION_GAP

        // Enemy section
        height += lineHeight + 2  // Section header (reduced)
        val opponentCount = BattleStateTracker.getOpponentSideConditions().size
        height += (if (opponentCount > 0) opponentCount else 1) * lineHeight

        // Stats section - always shown
        height += SECTION_GAP
        height += lineHeight + 2  // Section header (reduced)
        val pokemonWithChanges = pokemonWithStats.filter { it.third.isNotEmpty() }
        if (pokemonWithChanges.isEmpty()) {
            height += lineHeight  // "None active" text
        } else {
            // Always assume scrollbar is present for height calculation (worst case)
            val scrollbarSpace = SCROLLBAR_WIDTH + 4
            val contentWidth = panelWidth - scrollbarSpace
            val charWidth = (5 * textScale).toInt()
            val startX = PADDING + (8 * textScale).toInt()
            val maxX = contentWidth - PADDING

            for ((_, _, changedStats) in pokemonWithChanges) {
                height += (lineHeight * 0.95).toInt()  // Pokemon name

                // Simulate wrapping to count actual lines needed
                var statX = startX
                val sortedStats = changedStats.sortedBy { getStatSortOrder(it.key.identifier.toString()) }

                for ((stat, value) in sortedStats) {
                    val abbr = getStatAbbr(stat.identifier.toString())
                    val arrowCount = kotlin.math.abs(value)
                    val entryWidth = ((abbr.length * charWidth) + 2 + (arrowCount * charWidth) + (8 * textScale)).toInt()

                    if (statX + entryWidth > maxX && statX != startX) {
                        height += (lineHeight * 0.9).toInt()  // Line wrap
                        statX = startX
                    }
                    statX += entryWidth
                }
                height += (lineHeight * 0.9).toInt()  // Final line for this Pokemon's stats
            }
        }

        return height
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

    private fun drawTextClipped(context: DrawContext, text: String, x: Float, y: Float, color: Int, scale: Float) {
        val textHeight = (8 * scale).toInt()
        if (y + textHeight < scissorMinY || y > scissorMaxY) return
        drawText(context, text, x, y, color, scale)
    }

    private fun getStatAbbr(identifier: String): String {
        return when {
            identifier.contains("attack") && identifier.contains("special") -> "SpA"
            identifier.contains("attack") -> "Atk"
            identifier.contains("defense") && identifier.contains("special") -> "SpD"
            identifier.contains("defence") && identifier.contains("special") -> "SpD"
            identifier.contains("defense") -> "Def"
            identifier.contains("defence") -> "Def"
            identifier.contains("speed") -> "Spe"
            identifier.contains("evasion") -> "Eva"
            identifier.contains("accuracy") -> "Acc"
            else -> identifier.take(3).uppercase()
        }
    }

    private fun getStatSortOrder(identifier: String): Int {
        val id = identifier.lowercase()
        return when {
            id.contains("attack") && id.contains("special") -> 2
            id.contains("attack") -> 0
            (id.contains("defense") || id.contains("defence")) && id.contains("special") -> 3
            id.contains("defense") || id.contains("defence") -> 1
            id.contains("speed") -> 4
            id.contains("accuracy") -> 5
            id.contains("evasion") -> 6
            else -> 7
        }
    }
}

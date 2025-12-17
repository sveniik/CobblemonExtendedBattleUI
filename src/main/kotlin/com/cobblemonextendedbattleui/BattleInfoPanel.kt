package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
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
    private var resizeZone: UIUtils.ResizeZone = UIUtils.ResizeZone.NONE
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

    // Colors
    private val PANEL_BG = UIUtils.color(22, 27, 34, 240)
    private val HEADER_BG = UIUtils.color(30, 37, 46, 255)
    private val SECTION_BG = UIUtils.color(26, 32, 40, 255)
    private val BORDER_COLOR = UIUtils.color(55, 65, 80, 255)
    private val RESIZE_HANDLE_COLOR = UIUtils.color(100, 120, 140, 200)
    private val RESIZE_HANDLE_HOVER = UIUtils.color(130, 160, 190, 255)
    private val SCROLLBAR_BG = UIUtils.color(40, 48, 58, 200)
    private val SCROLLBAR_THUMB = UIUtils.color(80, 95, 115, 255)
    private val SCROLLBAR_THUMB_HOVER = UIUtils.color(100, 120, 145, 255)
    private val TEXT_WHITE = UIUtils.color(255, 255, 255, 255)
    private val TEXT_LIGHT = UIUtils.color(220, 225, 230, 255)
    private val TEXT_DIM = UIUtils.color(140, 150, 165, 255)
    private val TEXT_GOLD = UIUtils.color(255, 210, 80, 255)
    private val STAT_BOOST = UIUtils.color(255, 100, 100, 255)
    private val STAT_DROP = UIUtils.color(100, 160, 255, 255)
    private val ACCENT_PLAYER = UIUtils.color(100, 200, 255, 255)
    private val ACCENT_OPPONENT = UIUtils.color(255, 130, 110, 255)
    private val ACCENT_FIELD = UIUtils.color(255, 200, 100, 255)

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
    private var scissorBounds = UIUtils.ScissorBounds()

    // Hover state for visual feedback
    private var hoveredZone: UIUtils.ResizeZone = UIUtils.ResizeZone.NONE
    private var isOverScrollbar = false

    // Track previously active Pokemon to detect switches and clear their stats
    private var previouslyActiveUUIDs: Set<UUID> = emptySet()

    // Track if we were in a battle last frame (for detecting spectate exit)
    private var wasInBattle: Boolean = false

    data class PokemonBattleData(
        val uuid: UUID,
        val name: String,
        val statChanges: Map<BattleStateTracker.BattleStat, Int>,
        val volatiles: Set<BattleStateTracker.VolatileStatusState>,
        val isAlly: Boolean
    ) {
        fun hasAnyEffects(): Boolean = statChanges.isNotEmpty() || volatiles.isNotEmpty()
    }

    fun clearBattleState() {
        previouslyActiveUUIDs = emptySet()
    }

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

    private fun getResizeZone(mouseX: Int, mouseY: Int): UIUtils.ResizeZone {
        // No resizing in collapsed mode - use custom logic due to reduced top zone
        if (!isExpanded) return UIUtils.ResizeZone.NONE

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
            onTop && onLeft && withinX && withinY -> UIUtils.ResizeZone.TOP_LEFT
            onTop && onRight && withinX && withinY -> UIUtils.ResizeZone.TOP_RIGHT
            onBottom && onLeft && withinX && withinY -> UIUtils.ResizeZone.BOTTOM_LEFT
            onBottom && onRight && withinX && withinY -> UIUtils.ResizeZone.BOTTOM_RIGHT
            onLeft && withinY -> UIUtils.ResizeZone.LEFT
            onRight && withinY -> UIUtils.ResizeZone.RIGHT
            onTop && withinX -> UIUtils.ResizeZone.TOP
            onBottom && withinX -> UIUtils.ResizeZone.BOTTOM
            else -> UIUtils.ResizeZone.NONE
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

    private fun handleInput(mc: MinecraftClient) {
        val handle = mc.window.handle

        // Poll keybinds directly with GLFW (Minecraft's keybinding system doesn't work during Cobblemon battle overlay)
        val toggleKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.togglePanelKey.boundKeyTranslationKey)
        val isToggleDown = UIUtils.isKeyOrButtonPressed(handle, toggleKey)
        if (isToggleDown && !wasToggleKeyPressed) toggle()
        wasToggleKeyPressed = isToggleDown

        val increaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.increaseFontKey.boundKeyTranslationKey)
        val isIncreaseDown = UIUtils.isKeyOrButtonPressed(handle, increaseKey)
        if (isIncreaseDown && !wasIncreaseFontKeyPressed) {
            PanelConfig.adjustFontScale(PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasIncreaseFontKeyPressed = isIncreaseDown

        val decreaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.decreaseFontKey.boundKeyTranslationKey)
        val isDecreaseDown = UIUtils.isKeyOrButtonPressed(handle, decreaseKey)
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

        // Only update hover state when this panel can interact
        val canInteract = UIUtils.canInteract(UIUtils.ActivePanel.INFO_PANEL)
        hoveredZone = if (!isDragging && !isResizing && !isScrollbarDragging && canInteract) getResizeZone(mouseX, mouseY) else if (!canInteract) UIUtils.ResizeZone.NONE else hoveredZone
        isOverScrollbar = if (!isDragging && !isResizing && !isScrollbarDragging && canInteract) isOverScrollbarThumb(mouseX, mouseY) else if (!canInteract) false else isOverScrollbar

        val isOverPanel = mouseX >= panelBoundsX && mouseX <= panelBoundsX + panelBoundsW &&
                          mouseY >= panelBoundsY && mouseY <= panelBoundsY + panelBoundsH
        val isOverHeader = isOverPanel && mouseY <= headerEndY

        if (isMouseDown) {
            when {
                !wasMousePressed && canInteract && isOverScrollbarThumb(mouseX, mouseY) -> {
                    UIUtils.claimInteraction(UIUtils.ActivePanel.INFO_PANEL)
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
                !wasMousePressed && canInteract && hoveredZone != UIUtils.ResizeZone.NONE -> {
                    UIUtils.claimInteraction(UIUtils.ActivePanel.INFO_PANEL)
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
                        UIUtils.ResizeZone.RIGHT -> newWidth = resizeStartWidth + deltaX
                        UIUtils.ResizeZone.BOTTOM -> newHeight = resizeStartHeight + deltaY
                        UIUtils.ResizeZone.BOTTOM_RIGHT -> {
                            newWidth = resizeStartWidth + deltaX
                            newHeight = resizeStartHeight + deltaY
                        }
                        UIUtils.ResizeZone.LEFT -> {
                            newWidth = resizeStartWidth - deltaX
                            newX = resizeStartPanelX + deltaX
                        }
                        UIUtils.ResizeZone.TOP -> {
                            newHeight = resizeStartHeight - deltaY
                            newY = resizeStartPanelY + deltaY
                        }
                        UIUtils.ResizeZone.TOP_LEFT -> {
                            newWidth = resizeStartWidth - deltaX
                            newHeight = resizeStartHeight - deltaY
                            newX = resizeStartPanelX + deltaX
                            newY = resizeStartPanelY + deltaY
                        }
                        UIUtils.ResizeZone.TOP_RIGHT -> {
                            newWidth = resizeStartWidth + deltaX
                            newHeight = resizeStartHeight - deltaY
                            newY = resizeStartPanelY + deltaY
                        }
                        UIUtils.ResizeZone.BOTTOM_LEFT -> {
                            newWidth = resizeStartWidth - deltaX
                            newHeight = resizeStartHeight + deltaY
                            newX = resizeStartPanelX + deltaX
                        }
                        UIUtils.ResizeZone.NONE -> {}
                    }

                    val minW = PanelConfig.getMinWidth()
                    val minH = PanelConfig.getMinHeight()
                    val maxW = PanelConfig.getMaxWidth(screenWidth)
                    val maxH = PanelConfig.getMaxHeight(screenHeight)

                    if (resizeZone in listOf(UIUtils.ResizeZone.LEFT, UIUtils.ResizeZone.TOP_LEFT, UIUtils.ResizeZone.BOTTOM_LEFT)) {
                        if (newWidth < minW) {
                            newX = resizeStartPanelX + resizeStartWidth - minW
                            newWidth = minW
                        }
                        if (newWidth > maxW) {
                            newX = resizeStartPanelX + resizeStartWidth - maxW
                            newWidth = maxW
                        }
                    }
                    if (resizeZone in listOf(UIUtils.ResizeZone.TOP, UIUtils.ResizeZone.TOP_LEFT, UIUtils.ResizeZone.TOP_RIGHT)) {
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
                !wasMousePressed && canInteract && isOverHeader -> {
                    UIUtils.claimInteraction(UIUtils.ActivePanel.INFO_PANEL)
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
            // Release interaction when mouse is released
            val wasInteracting = isScrollbarDragging || isResizing || isDragging
            if (isScrollbarDragging) {
                isScrollbarDragging = false
            }
            if (isResizing) {
                isResizing = false
                resizeZone = UIUtils.ResizeZone.NONE
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
            if (wasInteracting) {
                UIUtils.releaseInteraction(UIUtils.ActivePanel.INFO_PANEL)
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
        val battle = CobblemonClient.battle

        // Handle battle exit (including spectator exit)
        if (battle == null) {
            if (wasInBattle) {
                // We just exited a battle - clear all state
                BattleStateTracker.clear()
                TeamIndicatorUI.clear()
                clearBattleState()
                BattleLog.clear()
                BattleLogWidget.clear()
                wasInBattle = false
                CobblemonExtendedBattleUI.LOGGER.debug("BattleInfoPanel: Cleared state on battle exit")
            }
            return
        }

        if (battle.minimised) return

        // Mark that we're now in a battle
        wasInBattle = true

        // Clear state if this is a new battle
        BattleStateTracker.checkBattleChanged(battle.battleId)

        val mc = MinecraftClient.getInstance()
        handleInput(mc)

        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight

        val playerUUID = mc.player?.uuid ?: return

        // Determine if player is in the battle and which side they're on
        val playerInSide1 = battle.side1.actors.any { it.uuid == playerUUID }
        val playerInSide2 = battle.side2.actors.any { it.uuid == playerUUID }
        val isSpectating = !playerInSide1 && !playerInSide2

        // Determine player/opponent sides (or left/right when spectating)
        val playerSide = when {
            playerInSide1 -> battle.side1
            playerInSide2 -> battle.side2
            else -> battle.side1  // When spectating, treat side1 as "left" side
        }
        val opponentSide = if (playerSide == battle.side1) battle.side2 else battle.side1

        // Get player names for section headers
        val playerSideNames = getPlayerSideNames(playerSide, isSpectating, isPlayerSide = true)
        val opponentSideNames = getPlayerSideNames(opponentSide, isSpectating, isPlayerSide = false)

        // Set player names in BattleStateTracker for disambiguating mirror matches
        // Use the raw actor names (not truncated) for matching
        val allyActorName = playerSide.actors.firstOrNull()?.displayName?.string ?: ""
        val opponentActorName = opponentSide.actors.firstOrNull()?.displayName?.string ?: ""
        BattleStateTracker.setPlayerNames(allyActorName, opponentActorName)

        // Get ally and opponent Pokemon separately
        val allyPokemon = playerSide.activeClientBattlePokemon.mapNotNull { it.battlePokemon }
        val opponentPokemon = opponentSide.activeClientBattlePokemon.mapNotNull { it.battlePokemon }

        // Get current active Pokemon UUIDs
        val currentActiveUUIDs = (allyPokemon + opponentPokemon).map { it.uuid }.toSet()

        // Handle Pokemon switching out
        // Check for Baton Pass before clearing - if used, stats/volatiles transfer to replacement
        for (uuid in previouslyActiveUUIDs) {
            if (uuid !in currentActiveUUIDs) {
                // Check if this Pokemon used Baton Pass - if so, preserve stats for transfer
                val usedBatonPass = BattleStateTracker.prepareBatonPassIfUsed(uuid)
                if (!usedBatonPass) {
                    // Normal switch: clear stats and volatiles
                    BattleStateTracker.clearPokemonStats(uuid)
                    BattleStateTracker.clearPokemonVolatiles(uuid)
                }
            }
        }
        previouslyActiveUUIDs = currentActiveUUIDs

        // Register Pokemon with ally status
        for (pokemon in allyPokemon) {
            BattleStateTracker.registerPokemon(pokemon.uuid, pokemon.displayName.string, isAlly = true)
        }
        for (pokemon in opponentPokemon) {
            BattleStateTracker.registerPokemon(pokemon.uuid, pokemon.displayName.string, isAlly = false)
        }

        // Apply any pending Baton Pass data to newly switched-in Pokemon
        for (pokemon in allyPokemon) {
            BattleStateTracker.applyBatonPassIfPending(pokemon.uuid)
        }
        for (pokemon in opponentPokemon) {
            BattleStateTracker.applyBatonPassIfPending(pokemon.uuid)
        }

        // Build Pokemon data with stat changes and volatile statuses, separated by side
        // Stat changes are tracked via message parsing in BattleStateTracker
        val allyPokemonData = allyPokemon.map { pokemon ->
            val statChanges = BattleStateTracker.getStatChanges(pokemon.uuid)
            val volatiles = BattleStateTracker.getVolatileStatuses(pokemon.uuid)
            PokemonBattleData(pokemon.uuid, pokemon.displayName.string, statChanges, volatiles, isAlly = true)
        }
        val opponentPokemonData = opponentPokemon.map { pokemon ->
            val statChanges = BattleStateTracker.getStatChanges(pokemon.uuid)
            val volatiles = BattleStateTracker.getVolatileStatuses(pokemon.uuid)
            PokemonBattleData(pokemon.uuid, pokemon.displayName.string, statChanges, volatiles, isAlly = false)
        }

        // Update font scaling based on user preference
        updateScaledValues()

        // Get panel dimensions - collapsed auto-fits content, expanded uses user settings
        val panelWidth: Int
        val panelHeight: Int

        if (isExpanded) {
            panelWidth = PanelConfig.panelWidth ?: PanelConfig.DEFAULT_WIDTH
            contentHeight = calculateExpandedContentHeight(allyPokemonData, opponentPokemonData, panelWidth)
            panelHeight = PanelConfig.panelHeight ?: (contentHeight + HEADER_HEIGHT + PADDING * 2)
        } else {
            // Collapsed: use same width, auto-fit height to content (never scroll)
            panelWidth = PanelConfig.panelWidth ?: PanelConfig.DEFAULT_WIDTH
            contentHeight = calculateCollapsedContentHeight(allyPokemonData, opponentPokemonData)
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
            renderExpanded(context, clampedX, clampedY, panelWidth, panelHeight, allyPokemonData, opponentPokemonData, playerSideNames, opponentSideNames)
        } else {
            renderCollapsed(context, clampedX, clampedY, panelWidth, panelHeight, allyPokemonData, opponentPokemonData, playerSideNames, opponentSideNames)
        }

        // Only draw resize handles in expanded mode
        if (isExpanded) {
            drawResizeHandles(context, clampedX, clampedY, panelWidth, panelHeight)
        }
    }

    private fun drawResizeHandles(context: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        val handleColor = if (hoveredZone != UIUtils.ResizeZone.NONE || isResizing) RESIZE_HANDLE_HOVER else RESIZE_HANDLE_COLOR
        val cornerLength = 12
        val thickness = 2

        UIUtils.drawCornerHandle(context, x + w, y + h, cornerLength, thickness, handleColor, bottomRight = true)

        if (hoveredZone != UIUtils.ResizeZone.NONE || isResizing) {
            UIUtils.drawCornerHandle(context, x, y, cornerLength, thickness, handleColor, topLeft = true)
            UIUtils.drawCornerHandle(context, x + w, y, cornerLength, thickness, handleColor, topRight = true)
            UIUtils.drawCornerHandle(context, x, y + h, cornerLength, thickness, handleColor, bottomLeft = true)

            val edgeLength = 16
            val midX = x + w / 2
            val midY = y + h / 2

            context.fill(midX - edgeLength / 2, y, midX + edgeLength / 2, y + thickness, handleColor)
            context.fill(midX - edgeLength / 2, y + h - thickness, midX + edgeLength / 2, y + h, handleColor)
            context.fill(x, midY - edgeLength / 2, x + thickness, midY + edgeLength / 2, handleColor)
            context.fill(x + w - thickness, midY - edgeLength / 2, x + w, midY + edgeLength / 2, handleColor)
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
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        playerSideNames: SideNames,
        opponentSideNames: SideNames
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

        // Count Pokemon with any effects (stats or volatiles)
        val allyEffectCount = allyPokemonData.count { it.hasAnyEffects() }
        val enemyEffectCount = opponentPokemonData.count { it.hasAnyEffects() }

        val hasAnyEffects = hasWeather || hasTerrain || fieldConditions.isNotEmpty() ||
                           playerConds.isNotEmpty() || oppConds.isNotEmpty() ||
                           allyEffectCount > 0 || enemyEffectCount > 0

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
                drawText(context, "${playerSideNames.sideName}: ${playerConds.size} effect${if (playerConds.size > 1) "s" else ""}",
                    (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_PLAYER, 0.8f * textScale)
                currentY += (lineHeight * 0.9).toInt()
            }

            if (oppConds.isNotEmpty()) {
                drawText(context, "${opponentSideNames.sideName}: ${oppConds.size} effect${if (oppConds.size > 1) "s" else ""}",
                    (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_OPPONENT, 0.8f * textScale)
                currentY += (lineHeight * 0.9).toInt()
            }

            if (allyEffectCount > 0) {
                val pokemonText = if (allyEffectCount == 1) "1 Pokémon" else "$allyEffectCount Pokémon"
                drawText(context, "${playerSideNames.possessiveName}: $pokemonText affected",
                    (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_PLAYER, 0.8f * textScale)
                currentY += (lineHeight * 0.9).toInt()
            }

            if (enemyEffectCount > 0) {
                val pokemonText = if (enemyEffectCount == 1) "1 Pokémon" else "$enemyEffectCount Pokémon"
                drawText(context, "${opponentSideNames.possessiveName}: $pokemonText affected",
                    (x + PADDING).toFloat(), currentY.toFloat(), ACCENT_OPPONENT, 0.8f * textScale)
            }
        }

        disableScissor()
    }

    private fun renderExpanded(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        playerSideNames: SideNames,
        opponentSideNames: SideNames
    ) {
        drawRoundedRect(context, x, y, width, height, PANEL_BG, BORDER_COLOR)
        renderHeader(context, x, y, width)

        // Render content
        renderInfoTab(context, x, y, width, height, allyPokemonData, opponentPokemonData, playerSideNames, opponentSideNames)
    }

    private fun renderInfoTab(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        playerSideNames: SideNames,
        opponentSideNames: SideNames
    ) {
        val contentStartY = y + HEADER_HEIGHT + SECTION_GAP
        val contentAreaHeight = height - HEADER_HEIGHT - PADDING
        val scrollbarSpace = if (contentHeight > visibleContentHeight) SCROLLBAR_WIDTH + 4 else 0
        val contentWidth = width - scrollbarSpace

        enableScissor(context, x + 1, contentStartY, contentWidth - 2, contentAreaHeight)

        var currentY = contentStartY - PanelConfig.scrollOffset

        // FIELD section
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

        // ALLY SIDE EFFECTS section (screens, hazards on your side)
        currentY = renderSection(context, x, currentY, contentWidth, "${playerSideNames.sideName} SIDE", ACCENT_PLAYER) { sectionY ->
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

        // ENEMY SIDE EFFECTS section (screens, hazards on enemy side)
        currentY = renderSection(context, x, currentY, contentWidth, "${opponentSideNames.sideName} SIDE", ACCENT_OPPONENT) { sectionY ->
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

        // YOUR POKÉMON section
        currentY = renderSection(context, x, currentY, contentWidth, "${playerSideNames.possessiveName} POKÉMON", ACCENT_PLAYER) { sectionY ->
            renderPokemonSection(context, x, sectionY, contentWidth, allyPokemonData)
        }

        currentY += SECTION_GAP

        // ENEMY POKÉMON section
        renderSection(context, x, currentY, contentWidth, "${opponentSideNames.possessiveName} POKÉMON", ACCENT_OPPONENT) { sectionY ->
            renderPokemonSection(context, x, sectionY, contentWidth, opponentPokemonData)
        }

        disableScissor()

        if (contentHeight > visibleContentHeight) {
            renderScrollbar(context, x + width - SCROLLBAR_WIDTH - 2, contentStartY, contentAreaHeight)
        }
    }

    private fun renderPokemonSection(
        context: DrawContext,
        x: Int,
        startY: Int,
        contentWidth: Int,
        pokemonData: List<PokemonBattleData>
    ): Int {
        var sy = startY

        val hasAnyEffects = pokemonData.any { it.hasAnyEffects() }

        if (!hasAnyEffects) {
            drawTextClipped(context, "No effects", (x + PADDING).toFloat(), sy.toFloat(), TEXT_DIM, 0.8f * textScale)
            sy += lineHeight
            return sy
        }

        for (pokemon in pokemonData) {
            if (!pokemon.hasAnyEffects()) continue

            // Pokemon name
            drawTextClipped(context, pokemon.name, (x + PADDING).toFloat(), sy.toFloat(), TEXT_WHITE, 0.85f * textScale)
            sy += (lineHeight * 0.95).toInt()

            // Stat changes (if any)
            if (pokemon.statChanges.isNotEmpty()) {
                val sortedStats = pokemon.statChanges.entries.sortedBy { getStatSortOrderFromBattleStat(it.key) }
                val charWidth = (5 * textScale).toInt()
                val startX = x + PADDING + (8 * textScale).toInt()

                var statX = startX
                for ((stat, value) in sortedStats) {
                    val abbr = stat.abbr
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

            // Volatile effects (if any)
            if (pokemon.volatiles.isNotEmpty()) {
                val charWidth = (5 * textScale).toInt()
                val startX = x + PADDING + (8 * textScale).toInt()
                var effectX = startX

                for (volatileState in pokemon.volatiles) {
                    val volatile = volatileState.type
                    // Add turn count if available
                    val turnsRemaining = BattleStateTracker.getVolatileTurnsRemaining(volatileState)
                    val display = if (turnsRemaining != null) {
                        "${volatile.icon} ${volatile.displayName} ($turnsRemaining)"
                    } else {
                        "${volatile.icon} ${volatile.displayName}"
                    }
                    val effectWidth = (display.length * charWidth * 0.8).toInt() + (10 * textScale).toInt()

                    // Wrap to next line if needed
                    if (effectX + effectWidth > x + contentWidth - PADDING && effectX != startX) {
                        sy += (lineHeight * 0.85).toInt()
                        effectX = startX
                    }

                    // Color based on whether effect is negative for the Pokemon
                    val effectColor = if (volatile.isNegative) STAT_DROP else STAT_BOOST
                    drawTextClipped(context, display, effectX.toFloat(), sy.toFloat(), effectColor, 0.75f * textScale)
                    effectX += effectWidth
                }
                sy += (lineHeight * 0.9).toInt()
            }
        }

        return sy
    }

    private fun enableScissor(context: DrawContext, x: Int, y: Int, width: Int, height: Int) {
        scissorBounds = UIUtils.enableScissor(x, y, width, height)
    }

    private fun disableScissor() {
        UIUtils.disableScissor()
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
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>
    ): Int {
        var height = PADDING / 2

        val hasWeather = BattleStateTracker.weather != null
        val hasTerrain = BattleStateTracker.terrain != null
        val fieldCount = BattleStateTracker.getFieldConditions().size
        val playerConds = BattleStateTracker.getPlayerSideConditions()
        val oppConds = BattleStateTracker.getOpponentSideConditions()

        // Count Pokemon with any effects (stats or volatiles)
        val allyEffectCount = allyPokemonData.count { it.hasAnyEffects() }
        val enemyEffectCount = opponentPokemonData.count { it.hasAnyEffects() }

        val hasAnyEffects = hasWeather || hasTerrain || fieldCount > 0 ||
                           playerConds.isNotEmpty() || oppConds.isNotEmpty() ||
                           allyEffectCount > 0 || enemyEffectCount > 0

        if (!hasAnyEffects) {
            height += lineHeight  // "No effects" text
        } else {
            if (hasWeather) height += lineHeight
            if (hasTerrain) height += lineHeight
            height += fieldCount * lineHeight
            if (playerConds.isNotEmpty()) height += (lineHeight * 0.9).toInt()
            if (oppConds.isNotEmpty()) height += (lineHeight * 0.9).toInt()
            if (allyEffectCount > 0) height += (lineHeight * 0.9).toInt()
            if (enemyEffectCount > 0) height += (lineHeight * 0.9).toInt()
        }

        return height
    }

    private fun calculateExpandedContentHeight(
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        panelWidth: Int
    ): Int {
        // Content starts at contentStartY (no initial gap - first section starts immediately)
        var height = 0

        // Field section
        height += lineHeight + 2  // Section header
        val fieldCount = listOfNotNull(BattleStateTracker.weather, BattleStateTracker.terrain).size +
                         BattleStateTracker.getFieldConditions().size
        height += (if (fieldCount > 0) fieldCount else 1) * lineHeight
        height += SECTION_GAP

        // Ally Side section
        height += lineHeight + 2  // Section header
        val playerCount = BattleStateTracker.getPlayerSideConditions().size
        height += (if (playerCount > 0) playerCount else 1) * lineHeight
        height += SECTION_GAP

        // Enemy Side section
        height += lineHeight + 2  // Section header
        val opponentCount = BattleStateTracker.getOpponentSideConditions().size
        height += (if (opponentCount > 0) opponentCount else 1) * lineHeight
        height += SECTION_GAP

        // Your Pokemon section
        height += lineHeight + 2  // Section header
        height += calculatePokemonSectionHeight(allyPokemonData, panelWidth)
        height += SECTION_GAP

        // Enemy Pokemon section
        height += lineHeight + 2  // Section header
        height += calculatePokemonSectionHeight(opponentPokemonData, panelWidth)

        return height
    }

    private fun calculatePokemonSectionHeight(
        pokemonData: List<PokemonBattleData>,
        panelWidth: Int
    ): Int {
        val pokemonWithEffects = pokemonData.filter { it.hasAnyEffects() }

        if (pokemonWithEffects.isEmpty()) {
            return lineHeight  // "No effects" text
        }

        var height = 0

        // Always assume scrollbar is present for height calculation (worst case)
        val scrollbarSpace = SCROLLBAR_WIDTH + 4
        val contentWidth = panelWidth - scrollbarSpace
        val charWidth = (5 * textScale).toInt()
        val startX = PADDING + (8 * textScale).toInt()
        val maxX = contentWidth - PADDING

        for (pokemon in pokemonWithEffects) {
            height += (lineHeight * 0.95).toInt()  // Pokemon name

            // Stats line(s)
            if (pokemon.statChanges.isNotEmpty()) {
                var statX = startX
                val sortedStats = pokemon.statChanges.entries.sortedBy { getStatSortOrderFromBattleStat(it.key) }

                for ((stat, value) in sortedStats) {
                    val abbr = stat.abbr
                    val arrowCount = kotlin.math.abs(value)
                    val entryWidth = ((abbr.length * charWidth) + 2 + (arrowCount * charWidth) + (8 * textScale)).toInt()

                    if (statX + entryWidth > maxX && statX != startX) {
                        height += (lineHeight * 0.9).toInt()  // Line wrap
                        statX = startX
                    }
                    statX += entryWidth
                }
                height += (lineHeight * 0.9).toInt()  // Final stat line
            }

            // Volatile effects line(s)
            if (pokemon.volatiles.isNotEmpty()) {
                var effectX = startX

                for (volatileState in pokemon.volatiles) {
                    val volatile = volatileState.type
                    // Account for turn count in width calculation
                    val turnsRemaining = BattleStateTracker.getVolatileTurnsRemaining(volatileState)
                    val display = if (turnsRemaining != null) {
                        "${volatile.icon} ${volatile.displayName} ($turnsRemaining)"
                    } else {
                        "${volatile.icon} ${volatile.displayName}"
                    }
                    val effectWidth = (display.length * charWidth * 0.8).toInt() + (10 * textScale).toInt()

                    if (effectX + effectWidth > maxX && effectX != startX) {
                        height += (lineHeight * 0.85).toInt()  // Line wrap
                        effectX = startX
                    }
                    effectX += effectWidth
                }
                height += (lineHeight * 0.9).toInt()  // Final volatile line
            }
        }

        return height
    }

    private fun drawText(context: DrawContext, text: String, x: Float, y: Float, color: Int, scale: Float) {
        UIUtils.drawText(context, text, x, y, color, scale)
    }

    private fun drawTextClipped(context: DrawContext, text: String, x: Float, y: Float, color: Int, scale: Float) {
        UIUtils.drawTextClipped(context, text, x, y, color, scale, scissorBounds)
    }

    private fun getStatSortOrderFromBattleStat(stat: BattleStateTracker.BattleStat): Int {
        return when (stat) {
            BattleStateTracker.BattleStat.ATTACK -> 0
            BattleStateTracker.BattleStat.DEFENSE -> 1
            BattleStateTracker.BattleStat.SPECIAL_ATTACK -> 2
            BattleStateTracker.BattleStat.SPECIAL_DEFENSE -> 3
            BattleStateTracker.BattleStat.SPEED -> 4
            BattleStateTracker.BattleStat.ACCURACY -> 5
            BattleStateTracker.BattleStat.EVASION -> 6
        }
    }

    data class SideNames(
        val sideName: String,      // e.g., "PLAYER123"
        val possessiveName: String // e.g., "PLAYER123"
    )

    private fun getPlayerSideNames(side: ClientBattleSide, isSpectating: Boolean, isPlayerSide: Boolean): SideNames {
        val actors = side.actors
        if (actors.isEmpty()) {
            return if (isPlayerSide) SideNames("ALLY", "ALLY") else SideNames("ENEMY", "ENEMY")
        }

        // Get the first actor's display name
        val firstActorName = actors.firstOrNull()?.displayName?.string
            ?: return if (isPlayerSide) SideNames("ALLY", "ALLY") else SideNames("ENEMY", "ENEMY")

        // Truncate long names to fit in section headers
        val truncatedName = if (firstActorName.length > 12) {
            firstActorName.take(11) + "…"
        } else {
            firstActorName
        }

        return SideNames(truncatedName.uppercase(), truncatedName.uppercase())
    }
}

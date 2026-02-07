package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import java.util.UUID

/**
 * Battle information panel with 9-slice textured frame and single-fill-cell layout.
 * Header cell (fixed) + one content cell that fills all remaining space.
 * Content groups separated by dividers within the cell.
 * Features draggable positioning, edge/corner resizing, and scrollable content.
 */
object BattleInfoPanel {

    // ═══════════════════════════════════════════════════════════════════════════
    // UI Translation Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private object UI {
        fun translate(key: String): String = Text.translatable("cobblemonextendedbattleui.ui.$key").string

        val noEffects: String get() = translate("no_effects")
        val ally: String get() = translate("ally")
        val enemy: String get() = translate("enemy")
        val affected: String get() = translate("affected")
        val effectSingular: String get() = translate("effect")
        val effectPlural: String get() = translate("effects")
        val pokemonSingular: String get() = translate("pokemon_count.singular")
        val pokemonPlural: String get() = translate("pokemon_count.plural")

        fun effectCount(count: Int): String = if (count == 1) effectSingular else effectPlural
        fun pokemonCount(count: Int): String = if (count == 1) "$count $pokemonSingular" else "$count $pokemonPlural"
    }

    private var isMinimised: Boolean = false

    /**
     * Applies the current opacity (minimized state) to a color's alpha channel.
     */
    private fun applyOpacity(color: Int): Int = UIUtils.applyMinimisedOpacity(color, isMinimised)

    /**
     * Returns a color transform lambda for drawPopupCell/drawPopupRowDivider.
     */
    private fun colorTransform(): (Int) -> Int = ::applyOpacity

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

    // Frame tint (cool blue-gray)
    private const val FRAME_TINT_R = 0.6f
    private const val FRAME_TINT_G = 0.7f
    private const val FRAME_TINT_B = 0.85f

    // Colors
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
    private val HEADER_ACCENT = UIUtils.color(200, 165, 60, 180)

    // Layout constants
    private const val BASE_PANEL_MARGIN = 10
    private const val BASE_LINE_HEIGHT = 12
    private const val SCROLLBAR_WIDTH = 3
    private const val SECTION_LABEL_SCALE = 0.7f
    private const val GROUP_DIVIDER_EXTRA = 4  // Extra spacing for group separators

    // Base font multiplier (makes default text ~20% larger)
    private const val BASE_FONT_MULTIPLIER = 1.2f

    // Text scale (base * user font preference only, no auto-scaling)
    private var textScale: Float = BASE_FONT_MULTIPLIER

    // Line height scales with font
    private var lineHeight: Int = BASE_LINE_HEIGHT

    // Header padding (more generous than content cells)
    private const val HEADER_VPAD_TOP = 6
    private const val HEADER_VPAD_BOTTOM = 5
    private const val HEADER_ACCENT_H = 1  // Gold accent line at bottom

    // Header cell height (computed from text scale)
    private val headerCellH: Int get() = HEADER_VPAD_TOP + (8 * 0.85f * textScale).toInt() + HEADER_VPAD_BOTTOM + HEADER_ACCENT_H

    // Sub-label height (for side names inside content cell)
    private fun subLabelHeight(): Int = (8 * SECTION_LABEL_SCALE * textScale).toInt() + 2

    // Group divider height (row divider + extra spacing between major groups)
    private fun groupDividerHeight(): Int = UIUtils.DIVIDER_H + GROUP_DIVIDER_EXTRA

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
        val x = panelBoundsX
        val y = panelBoundsY
        val w = panelBoundsW
        val h = panelBoundsH

        val onLeft = mouseX >= x - RESIZE_HANDLE_SIZE && mouseX <= x + RESIZE_HANDLE_SIZE
        val onRight = mouseX >= x + w - RESIZE_HANDLE_SIZE && mouseX <= x + w + RESIZE_HANDLE_SIZE
        val onTop = mouseY >= y - RESIZE_HANDLE_SIZE && mouseY <= y + 2
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

    private fun calculateThumbHeight(trackHeight: Int): Int {
        val minThumb = (trackHeight / 4).coerceIn(6, 20)
        return ((visibleContentHeight.toFloat() / contentHeight) * trackHeight).toInt()
            .coerceIn(minThumb, trackHeight)
    }

    private fun isOverScrollbarThumb(mouseX: Int, mouseY: Int): Boolean {
        if (contentHeight <= visibleContentHeight) return false

        val scrollbarX = panelBoundsX + panelBoundsW - UIUtils.FRAME_INSET - SCROLLBAR_WIDTH
        val scrollbarY = headerEndY + UIUtils.CELL_GAP
        val scrollbarHeight = panelBoundsY + panelBoundsH - UIUtils.FRAME_INSET - scrollbarY

        if (mouseX < scrollbarX || mouseX > scrollbarX + SCROLLBAR_WIDTH) return false
        if (mouseY < scrollbarY || mouseY > scrollbarY + scrollbarHeight) return false

        val thumbHeight = calculateThumbHeight(scrollbarHeight)
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

        // Font keybinds - only handle if no other panel has priority
        val otherPanelHasPriority = TeamIndicatorUI.shouldHandleFontInput() || BattleLogWidget.isMouseOverWidget() || MoveTooltipRenderer.shouldHandleFontInput()

        val increaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.increaseFontKey.boundKeyTranslationKey)
        val isIncreaseDown = UIUtils.isKeyOrButtonPressed(handle, increaseKey)
        if (isIncreaseDown && !wasIncreaseFontKeyPressed && !otherPanelHasPriority) {
            PanelConfig.adjustFontScale(PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasIncreaseFontKeyPressed = isIncreaseDown

        val decreaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.decreaseFontKey.boundKeyTranslationKey)
        val isDecreaseDown = UIUtils.isKeyOrButtonPressed(handle, decreaseKey)
        if (isDecreaseDown && !wasDecreaseFontKeyPressed && !otherPanelHasPriority) {
            PanelConfig.adjustFontScale(-PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasDecreaseFontKeyPressed = isDecreaseDown

        val mouseX = (mc.mouse.x * mc.window.scaledWidth / mc.window.width).toInt()
        val mouseY = (mc.mouse.y * mc.window.scaledHeight / mc.window.height).toInt()
        val isMouseDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS

        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight

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
                    val scrollbarHeight = panelBoundsY + panelBoundsH - UIUtils.FRAME_INSET - headerEndY - UIUtils.CELL_GAP
                    val thumbHeight = calculateThumbHeight(scrollbarHeight)
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
                    val minH = if (isExpanded) PanelConfig.getMinHeight() else PanelConfig.getMinCollapsedHeight()
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

                    if (isExpanded) {
                        PanelConfig.setDimensions(newWidth, newHeight)
                    } else {
                        PanelConfig.setCollapsedDimensions(newWidth, newHeight)
                    }
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
            val isCtrlHeld = GLFW.glfwGetKey(mc.window.handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                             GLFW.glfwGetKey(mc.window.handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS

            if (isCtrlHeld) {
                val delta = if (deltaY > 0) PanelConfig.FONT_SCALE_STEP else -PanelConfig.FONT_SCALE_STEP
                PanelConfig.adjustFontScale(delta)
                PanelConfig.save()
                return true
            } else {
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

        isMinimised = battle.minimised
        wasInBattle = true
        BattleStateTracker.checkBattleChanged(battle.battleId)

        val mc = MinecraftClient.getInstance()

        if (!isMinimised) {
            handleInput(mc)
        }

        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight
        val playerUUID = mc.player?.uuid ?: return

        val playerInSide1 = battle.side1.actors.any { it.uuid == playerUUID }
        val playerInSide2 = battle.side2.actors.any { it.uuid == playerUUID }
        val isSpectating = !playerInSide1 && !playerInSide2

        val playerSide = when {
            playerInSide1 -> battle.side1
            playerInSide2 -> battle.side2
            else -> battle.side2
        }
        val opponentSide = if (playerSide == battle.side1) battle.side2 else battle.side1

        val playerSideName = getPlayerSideName(playerSide, isSpectating, isPlayerSide = true)
        val opponentSideName = getPlayerSideName(opponentSide, isSpectating, isPlayerSide = false)

        val allyActorName = playerSide.actors.firstOrNull()?.displayName?.string ?: ""
        val opponentActorName = opponentSide.actors.firstOrNull()?.displayName?.string ?: ""
        BattleStateTracker.setPlayerNames(allyActorName, opponentActorName)

        val allyPokemon = playerSide.activeClientBattlePokemon.mapNotNull { it.battlePokemon }
        val opponentPokemon = opponentSide.activeClientBattlePokemon.mapNotNull { it.battlePokemon }
        val currentActiveUUIDs = (allyPokemon + opponentPokemon).map { it.uuid }.toSet()

        for (uuid in previouslyActiveUUIDs) {
            if (uuid !in currentActiveUUIDs) {
                val usedBatonPass = BattleStateTracker.prepareBatonPassIfUsed(uuid)
                if (!usedBatonPass) {
                    BattleStateTracker.clearPokemonStats(uuid)
                    BattleStateTracker.clearPokemonVolatiles(uuid)
                }
                BattleStateTracker.restoreOriginalTypes(uuid)
            }
        }
        previouslyActiveUUIDs = currentActiveUUIDs

        for (pokemon in allyPokemon) {
            BattleStateTracker.registerPokemon(pokemon.uuid, pokemon.displayName.string, isAlly = true)
            pokemon.properties.species?.let { speciesName ->
                BattleStateTracker.registerPokemon(pokemon.uuid, speciesName, isAlly = true)
                BattleStateTracker.registerSpeciesId(pokemon.uuid, Identifier.of("cobblemon", speciesName))
            }
        }
        for (pokemon in opponentPokemon) {
            BattleStateTracker.registerPokemon(pokemon.uuid, pokemon.displayName.string, isAlly = false)
            pokemon.properties.species?.let { speciesName ->
                BattleStateTracker.registerPokemon(pokemon.uuid, speciesName, isAlly = false)
                BattleStateTracker.registerSpeciesId(pokemon.uuid, Identifier.of("cobblemon", speciesName))
            }
        }

        for (pokemon in allyPokemon) {
            BattleStateTracker.applyBatonPassIfPending(pokemon.uuid)
        }
        for (pokemon in opponentPokemon) {
            BattleStateTracker.applyBatonPassIfPending(pokemon.uuid)
        }

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

        updateScaledValues()

        val panelWidth: Int
        val panelHeight: Int

        if (isExpanded) {
            panelWidth = PanelConfig.panelWidth ?: PanelConfig.DEFAULT_WIDTH
            contentHeight = calculateExpandedContentHeight(allyPokemonData, opponentPokemonData, panelWidth)
            val autoHeight = UIUtils.FRAME_INSET * 2 + headerCellH + UIUtils.CELL_GAP + contentHeight
            panelHeight = PanelConfig.panelHeight ?: autoHeight
        } else {
            panelWidth = PanelConfig.collapsedWidth ?: PanelConfig.DEFAULT_WIDTH
            contentHeight = calculateCollapsedContentHeight(allyPokemonData, opponentPokemonData, panelWidth)
            val autoHeight = UIUtils.FRAME_INSET * 2 + headerCellH + UIUtils.CELL_GAP + contentHeight
            panelHeight = PanelConfig.collapsedHeight ?: autoHeight
        }

        val panelX = PanelConfig.panelX ?: (screenWidth - panelWidth - BASE_PANEL_MARGIN)
        val panelY = PanelConfig.panelY ?: ((screenHeight - panelHeight) / 2)

        val clampedX = panelX.coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0))
        val clampedY = panelY.coerceIn(0, (screenHeight - panelHeight).coerceAtLeast(0))

        panelBoundsX = clampedX
        panelBoundsY = clampedY
        panelBoundsW = panelWidth
        panelBoundsH = panelHeight

        // Visible content area: panel height minus frame insets, header cell, and gap
        visibleContentHeight = panelHeight - UIUtils.FRAME_INSET * 2 - headerCellH - UIUtils.CELL_GAP

        val maxScroll = (contentHeight - visibleContentHeight).coerceAtLeast(0)
        PanelConfig.scrollOffset = PanelConfig.scrollOffset.coerceIn(0, maxScroll)

        if (isExpanded) {
            renderExpanded(context, clampedX, clampedY, panelWidth, panelHeight, allyPokemonData, opponentPokemonData, playerSideName, opponentSideName)
        } else {
            renderCollapsed(context, clampedX, clampedY, panelWidth, panelHeight, allyPokemonData, opponentPokemonData, playerSideName, opponentSideName)
        }

        if (!isMinimised) {
            drawResizeHandles(context, clampedX, clampedY, panelWidth, panelHeight)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Frame and header rendering
    // ═══════════════════════════════════════════════════════════════════════════

    private fun renderFrame(context: DrawContext, x: Int, y: Int, width: Int, height: Int) {
        val opacity = if (isMinimised) UIUtils.MINIMISED_OPACITY else 1f
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(FRAME_TINT_R, FRAME_TINT_G, FRAME_TINT_B, opacity)
        UIUtils.renderPopupFrame(context, x, y, width, height)
        context.draw()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }

    private fun renderHeader(context: DrawContext, x: Int, y: Int, width: Int) {
        val cellX = x + UIUtils.FRAME_INSET
        val cellW = width - UIUtils.FRAME_INSET * 2
        val cellY = y + UIUtils.FRAME_INSET

        UIUtils.drawPopupCell(context, cellX, cellY, cellW, headerCellH, colorTransform())
        headerEndY = cellY + headerCellH

        // Gold accent line at the bottom of the header cell
        val accentY = cellY + headerCellH - HEADER_ACCENT_H - 1  // -1 to sit inside the cell border
        context.fill(cellX + 1, accentY, cellX + cellW - 1, accentY + HEADER_ACCENT_H, applyOpacity(HEADER_ACCENT))

        // Text positioned with top padding (visual centering between top pad and accent line)
        val textH = (8 * 0.85f * textScale).toInt()
        val availableH = headerCellH - HEADER_ACCENT_H - 1  // 1px cell border
        val textY = cellY + (availableH - textH) / 2
        val textX = cellX + UIUtils.CELL_PAD

        val arrow = if (isExpanded) "▼" else "▶"
        drawText(context, arrow, textX.toFloat(), textY.toFloat(), TEXT_GOLD, 0.85f * textScale)
        drawText(context, "BATTLE INFO", (textX + (12 * textScale).toInt()).toFloat(), textY.toFloat(), TEXT_WHITE, 0.85f * textScale)

        val turnText = "T${BattleStateTracker.currentTurn}"
        val charWidth = (5 * textScale).toInt()
        drawText(context, turnText, (cellX + cellW - UIUtils.CELL_PAD - turnText.length * charWidth).toFloat(),
            textY.toFloat(), TEXT_GOLD, 0.85f * textScale)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Expanded mode
    // ═══════════════════════════════════════════════════════════════════════════

    private fun renderExpanded(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        playerSideName: SideName,
        opponentSideName: SideName
    ) {
        renderFrame(context, x, y, width, height)
        renderHeader(context, x, y, width)

        val cellX = x + UIUtils.FRAME_INSET
        val cellW = width - UIUtils.FRAME_INSET * 2
        val contentCellY = headerEndY + UIUtils.CELL_GAP
        val contentCellH = height - UIUtils.FRAME_INSET * 2 - headerCellH - UIUtils.CELL_GAP

        // Reserve scrollbar space
        val needsScrollbar = contentHeight > visibleContentHeight
        val scrollbarSpace = if (needsScrollbar) SCROLLBAR_WIDTH + 2 else 0
        val effectiveW = cellW - scrollbarSpace

        // Single cell fills ALL remaining space
        UIUtils.drawPopupCell(context, cellX, contentCellY, effectiveW, contentCellH, colorTransform())

        val contentX = cellX + UIUtils.CELL_PAD
        val contentW = effectiveW - UIUtils.CELL_PAD * 2

        enableScissor(cellX, contentCellY, effectiveW, contentCellH)

        val startY = contentCellY + UIUtils.CELL_VPAD_TOP - PanelConfig.scrollOffset

        renderExpandedContent(context, contentX, startY, contentW, allyPokemonData, opponentPokemonData, playerSideName, opponentSideName)

        disableScissor()

        if (needsScrollbar) {
            val scrollbarX = x + width - UIUtils.FRAME_INSET - SCROLLBAR_WIDTH
            renderScrollbar(context, scrollbarX, contentCellY, contentCellH)
        }
    }

    /**
     * Renders all expanded content groups sequentially within the single fill cell.
     * Groups are separated by group dividers (row divider + extra spacing).
     */
    private fun renderExpandedContent(
        context: DrawContext,
        x: Int,
        startY: Int,
        width: Int,
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        playerSideName: SideName,
        opponentSideName: SideName
    ) {
        val hasGlobal = hasFieldEffects()
        val hasAlly = hasAnySideConditions(true)
        val hasEnemy = hasAnySideConditions(false)
        val allPokemon = allyPokemonData + opponentPokemonData
        val hasPokemonEffects = allPokemon.any { it.hasAnyEffects() }
        val hasConditions = hasGlobal || hasAlly || hasEnemy

        if (!hasConditions && !hasPokemonEffects) {
            drawTextClipped(context, UI.noEffects, x.toFloat(), startY.toFloat(), TEXT_DIM, 0.8f * textScale)
            return
        }

        val dividerX = x - UIUtils.CELL_PAD
        val dividerW = width + UIUtils.CELL_PAD * 2
        var curY = startY

        if (hasGlobal) {
            curY = renderFieldContent(context, x, curY, width)
        }

        if (hasGlobal && (hasAlly || hasEnemy)) {
            curY = drawGroupDivider(context, dividerX, curY, dividerW)
        }

        if (hasAlly) {
            drawTextClipped(context, playerSideName.name, x.toFloat(), curY.toFloat(), ACCENT_PLAYER, SECTION_LABEL_SCALE * textScale)
            curY += subLabelHeight()
            curY = renderSideContent(context, x, curY, width, isPlayer = true)
        }

        if (hasAlly && hasEnemy) {
            curY = drawGroupDivider(context, dividerX, curY, dividerW)
        }

        if (hasEnemy) {
            drawTextClipped(context, opponentSideName.name, x.toFloat(), curY.toFloat(), ACCENT_OPPONENT, SECTION_LABEL_SCALE * textScale)
            curY += subLabelHeight()
            curY = renderSideContent(context, x, curY, width, isPlayer = false)
        }

        if (hasConditions && hasPokemonEffects) {
            curY = drawGroupDivider(context, dividerX, curY, dividerW)
        }

        if (hasPokemonEffects) {
            renderPokemonContent(context, x, curY, width, allPokemon)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Collapsed mode
    // ═══════════════════════════════════════════════════════════════════════════

    private fun renderCollapsed(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        playerSideName: SideName,
        opponentSideName: SideName
    ) {
        renderFrame(context, x, y, width, height)
        renderHeader(context, x, y, width)

        val cellX = x + UIUtils.FRAME_INSET
        val cellW = width - UIUtils.FRAME_INSET * 2
        val contentCellY = headerEndY + UIUtils.CELL_GAP
        val contentCellH = height - UIUtils.FRAME_INSET * 2 - headerCellH - UIUtils.CELL_GAP

        val needsScrollbar = contentHeight > visibleContentHeight
        val scrollbarSpace = if (needsScrollbar) SCROLLBAR_WIDTH + 2 else 0
        val effectiveW = cellW - scrollbarSpace

        // Draw cell at FIXED position filling remaining space
        UIUtils.drawPopupCell(context, cellX, contentCellY, effectiveW, contentCellH, colorTransform())

        enableScissor(cellX, contentCellY, effectiveW, contentCellH)

        // Content scrolls WITHIN the cell
        val startY = contentCellY + UIUtils.CELL_VPAD_TOP - PanelConfig.scrollOffset
        val textX = cellX + UIUtils.CELL_PAD
        val textW = effectiveW - UIUtils.CELL_PAD * 2
        renderCollapsedContent(context, textX, startY, textW, allyPokemonData, opponentPokemonData, playerSideName, opponentSideName)

        disableScissor()

        if (needsScrollbar) {
            val scrollbarX = x + width - UIUtils.FRAME_INSET - SCROLLBAR_WIDTH
            renderScrollbar(context, scrollbarX, contentCellY, contentCellH)
        }
    }

    private fun renderCollapsedContent(
        context: DrawContext,
        x: Int,
        startY: Int,
        width: Int,
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        playerSideName: SideName,
        opponentSideName: SideName
    ) {
        var currentY = startY

        val hasWeather = BattleStateTracker.weather != null
        val hasTerrain = BattleStateTracker.terrain != null
        val fieldConditions = BattleStateTracker.getFieldConditions()
        val playerConds = BattleStateTracker.getPlayerSideConditions()
        val oppConds = BattleStateTracker.getOpponentSideConditions()
        val allyEffectCount = allyPokemonData.count { it.hasAnyEffects() }
        val enemyEffectCount = opponentPokemonData.count { it.hasAnyEffects() }

        val hasAnyEffects = hasWeather || hasTerrain || fieldConditions.isNotEmpty() ||
                           playerConds.isNotEmpty() || oppConds.isNotEmpty() ||
                           allyEffectCount > 0 || enemyEffectCount > 0

        if (!hasAnyEffects) {
            drawTextClipped(context, UI.noEffects, x.toFloat(), currentY.toFloat(), TEXT_DIM, 0.8f * textScale)
            return
        }

        val dividerX = x - UIUtils.CELL_PAD
        val dividerW = width + UIUtils.CELL_PAD * 2
        var rowIndex = 0

        BattleStateTracker.weather?.let { w ->
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, dividerX, dividerW, currentY, colorTransform())
                currentY += UIUtils.DIVIDER_H
            }
            val turns = BattleStateTracker.getWeatherTurnsRemaining() ?: "?"
            drawConditionLine(context, x, currentY, width, w.type.icon, w.type.displayName, turns, ACCENT_FIELD)
            currentY += lineHeight
            rowIndex++
        }

        BattleStateTracker.terrain?.let { t ->
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, dividerX, dividerW, currentY, colorTransform())
                currentY += UIUtils.DIVIDER_H
            }
            val turns = BattleStateTracker.getTerrainTurnsRemaining() ?: "?"
            drawConditionLine(context, x, currentY, width, t.type.icon, t.type.displayName, turns, ACCENT_FIELD)
            currentY += lineHeight
            rowIndex++
        }

        fieldConditions.forEach { (type, _) ->
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, dividerX, dividerW, currentY, colorTransform())
                currentY += UIUtils.DIVIDER_H
            }
            val turns = BattleStateTracker.getFieldConditionTurnsRemaining(type) ?: "?"
            drawConditionLine(context, x, currentY, width, type.icon, type.displayName, turns, ACCENT_FIELD)
            currentY += lineHeight
            rowIndex++
        }

        if (playerConds.isNotEmpty()) {
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, dividerX, dividerW, currentY, colorTransform())
                currentY += UIUtils.DIVIDER_H
            }
            drawTextClipped(context, "${playerSideName.name}: ${playerConds.size} ${UI.effectCount(playerConds.size)}",
                x.toFloat(), currentY.toFloat(), ACCENT_PLAYER, 0.8f * textScale)
            currentY += (lineHeight * 0.9).toInt()
            rowIndex++
        }

        if (oppConds.isNotEmpty()) {
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, dividerX, dividerW, currentY, colorTransform())
                currentY += UIUtils.DIVIDER_H
            }
            drawTextClipped(context, "${opponentSideName.name}: ${oppConds.size} ${UI.effectCount(oppConds.size)}",
                x.toFloat(), currentY.toFloat(), ACCENT_OPPONENT, 0.8f * textScale)
            currentY += (lineHeight * 0.9).toInt()
            rowIndex++
        }

        if (allyEffectCount > 0) {
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, dividerX, dividerW, currentY, colorTransform())
                currentY += UIUtils.DIVIDER_H
            }
            drawTextClipped(context, "${playerSideName.name}: ${UI.pokemonCount(allyEffectCount)} ${UI.affected}",
                x.toFloat(), currentY.toFloat(), ACCENT_PLAYER, 0.8f * textScale)
            currentY += (lineHeight * 0.9).toInt()
            rowIndex++
        }

        if (enemyEffectCount > 0) {
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, dividerX, dividerW, currentY, colorTransform())
                currentY += UIUtils.DIVIDER_H
            }
            drawTextClipped(context, "${opponentSideName.name}: ${UI.pokemonCount(enemyEffectCount)} ${UI.affected}",
                x.toFloat(), currentY.toFloat(), ACCENT_OPPONENT, 0.8f * textScale)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Content renderers (expanded mode)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun renderFieldContent(context: DrawContext, x: Int, startY: Int, width: Int): Int {
        var sy = startY
        var rowIndex = 0

        BattleStateTracker.weather?.let { w ->
            val turns = BattleStateTracker.getWeatherTurnsRemaining() ?: "?"
            drawConditionLine(context, x, sy, width, w.type.icon, w.type.displayName, turns, ACCENT_FIELD)
            sy += lineHeight
            rowIndex++
        }

        BattleStateTracker.terrain?.let { t ->
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, x - UIUtils.CELL_PAD, width + UIUtils.CELL_PAD * 2, sy, colorTransform())
                sy += UIUtils.DIVIDER_H
            }
            val turns = BattleStateTracker.getTerrainTurnsRemaining() ?: "?"
            drawConditionLine(context, x, sy, width, t.type.icon, t.type.displayName, turns, ACCENT_FIELD)
            sy += lineHeight
            rowIndex++
        }

        BattleStateTracker.getFieldConditions().forEach { (type, _) ->
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, x - UIUtils.CELL_PAD, width + UIUtils.CELL_PAD * 2, sy, colorTransform())
                sy += UIUtils.DIVIDER_H
            }
            val turns = BattleStateTracker.getFieldConditionTurnsRemaining(type) ?: "?"
            drawConditionLine(context, x, sy, width, type.icon, type.displayName, turns, ACCENT_FIELD)
            sy += lineHeight
            rowIndex++
        }

        return sy
    }

    private fun renderSideContent(context: DrawContext, x: Int, startY: Int, width: Int, isPlayer: Boolean): Int {
        var sy = startY
        val conditions = if (isPlayer) BattleStateTracker.getPlayerSideConditions() else BattleStateTracker.getOpponentSideConditions()
        val accentColor = if (isPlayer) ACCENT_PLAYER else ACCENT_OPPONENT

        var rowIndex = 0
        conditions.forEach { (type, state) ->
            if (rowIndex > 0) {
                UIUtils.drawPopupRowDivider(context, x - UIUtils.CELL_PAD, width + UIUtils.CELL_PAD * 2, sy, colorTransform())
                sy += UIUtils.DIVIDER_H
            }
            val turnsRemaining = BattleStateTracker.getSideConditionTurnsRemaining(isPlayer, type)
            val info = when {
                turnsRemaining != null -> turnsRemaining
                state.stacks > 1 -> "x${state.stacks}"
                else -> ""
            }
            drawConditionLine(context, x, sy, width, type.icon, type.displayName, info, accentColor)
            sy += lineHeight
            rowIndex++
        }

        return sy
    }

    private fun renderPokemonContent(context: DrawContext, x: Int, startY: Int, width: Int, pokemonData: List<PokemonBattleData>): Int {
        var sy = startY

        var pokemonIndex = 0
        for (pokemon in pokemonData) {
            if (!pokemon.hasAnyEffects()) continue

            if (pokemonIndex > 0) {
                UIUtils.drawPopupRowDivider(context, x - UIUtils.CELL_PAD, width + UIUtils.CELL_PAD * 2, sy, colorTransform())
                sy += UIUtils.DIVIDER_H
            }

            // Pokemon name (colored by side)
            val nameColor = if (pokemon.isAlly) ACCENT_PLAYER else ACCENT_OPPONENT
            drawTextClipped(context, pokemon.name, x.toFloat(), sy.toFloat(), nameColor, 0.85f * textScale)
            sy += (lineHeight * 0.95).toInt()

            // Stat changes
            if (pokemon.statChanges.isNotEmpty()) {
                val sortedStats = pokemon.statChanges.entries.sortedBy { getStatSortOrderFromBattleStat(it.key) }
                val charWidth = (5 * textScale).toInt()
                val indentX = x + (8 * textScale).toInt()

                var statX = indentX
                for ((stat, value) in sortedStats) {
                    val abbr = stat.abbr
                    val arrows = if (value > 0) "↑".repeat(value) else "↓".repeat(-value)
                    val color = if (value > 0) STAT_BOOST else STAT_DROP
                    val entryWidth = ((abbr.length * charWidth) + 2 + (arrows.length * charWidth) + (8 * textScale)).toInt()

                    if (statX + entryWidth > x + width && statX != indentX) {
                        sy += (lineHeight * 0.9).toInt()
                        statX = indentX
                    }

                    drawTextClipped(context, abbr, statX.toFloat(), sy.toFloat(), TEXT_LIGHT, 0.75f * textScale)
                    statX += (abbr.length * charWidth) + 2
                    drawTextClipped(context, arrows, statX.toFloat(), sy.toFloat(), color, 0.75f * textScale)
                    statX += (arrows.length * charWidth) + (8 * textScale).toInt()
                }
                sy += (lineHeight * 0.9).toInt()
            }

            // Volatile effects
            if (pokemon.volatiles.isNotEmpty()) {
                val charWidth = (5 * textScale).toInt()
                val indentX = x + (8 * textScale).toInt()
                var effectX = indentX

                for (volatileState in pokemon.volatiles) {
                    val volatile = volatileState.type
                    val turnsRemaining = BattleStateTracker.getVolatileTurnsRemaining(volatileState)
                    val display = if (turnsRemaining != null) {
                        "${volatile.icon} ${volatile.displayName} ($turnsRemaining)"
                    } else {
                        "${volatile.icon} ${volatile.displayName}"
                    }
                    val effectWidth = (display.length * charWidth * 0.8).toInt() + (10 * textScale).toInt()

                    if (effectX + effectWidth > x + width && effectX != indentX) {
                        sy += (lineHeight * 0.85).toInt()
                        effectX = indentX
                    }

                    val effectColor = if (volatile.isNegative) STAT_DROP else STAT_BOOST
                    drawTextClipped(context, display, effectX.toFloat(), sy.toFloat(), effectColor, 0.75f * textScale)
                    effectX += effectWidth
                }
                sy += (lineHeight * 0.9).toInt()
            }

            pokemonIndex++
        }

        return sy
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Content detection and height calculation
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hasFieldEffects(): Boolean {
        return BattleStateTracker.weather != null ||
               BattleStateTracker.terrain != null ||
               BattleStateTracker.getFieldConditions().isNotEmpty()
    }

    private fun hasAnySideConditions(isPlayer: Boolean): Boolean {
        return if (isPlayer) BattleStateTracker.getPlayerSideConditions().isNotEmpty()
               else BattleStateTracker.getOpponentSideConditions().isNotEmpty()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Height calculations
    // ═══════════════════════════════════════════════════════════════════════════

    private fun calculateFieldContentHeight(): Int {
        val fieldCount = listOfNotNull(BattleStateTracker.weather, BattleStateTracker.terrain).size +
                         BattleStateTracker.getFieldConditions().size
        if (fieldCount == 0) return 0
        val dividers = if (fieldCount > 1) (fieldCount - 1) * UIUtils.DIVIDER_H else 0
        return fieldCount * lineHeight + dividers
    }

    private fun calculateSideContentHeight(isPlayer: Boolean): Int {
        val count = if (isPlayer) BattleStateTracker.getPlayerSideConditions().size
                    else BattleStateTracker.getOpponentSideConditions().size
        if (count == 0) return 0
        val dividers = if (count > 1) (count - 1) * UIUtils.DIVIDER_H else 0
        return count * lineHeight + dividers
    }

    private fun calculatePokemonContentHeight(pokemonData: List<PokemonBattleData>, contentWidth: Int): Int {
        val pokemonWithEffects = pokemonData.filter { it.hasAnyEffects() }
        if (pokemonWithEffects.isEmpty()) return 0

        var height = 0
        val charWidth = (5 * textScale).toInt()
        val indentX = (8 * textScale).toInt()
        val maxX = contentWidth

        for ((i, pokemon) in pokemonWithEffects.withIndex()) {
            if (i > 0) height += UIUtils.DIVIDER_H  // divider between pokemon

            height += (lineHeight * 0.95).toInt()  // Pokemon name

            if (pokemon.statChanges.isNotEmpty()) {
                var statX = indentX
                val sortedStats = pokemon.statChanges.entries.sortedBy { getStatSortOrderFromBattleStat(it.key) }

                for ((stat, value) in sortedStats) {
                    val abbr = stat.abbr
                    val arrowCount = kotlin.math.abs(value)
                    val entryWidth = ((abbr.length * charWidth) + 2 + (arrowCount * charWidth) + (8 * textScale)).toInt()

                    if (statX + entryWidth > maxX && statX != indentX) {
                        height += (lineHeight * 0.9).toInt()
                        statX = indentX
                    }
                    statX += entryWidth
                }
                height += (lineHeight * 0.9).toInt()
            }

            if (pokemon.volatiles.isNotEmpty()) {
                var effectX = indentX

                for (volatileState in pokemon.volatiles) {
                    val volatile = volatileState.type
                    val turnsRemaining = BattleStateTracker.getVolatileTurnsRemaining(volatileState)
                    val display = if (turnsRemaining != null) {
                        "${volatile.icon} ${volatile.displayName} ($turnsRemaining)"
                    } else {
                        "${volatile.icon} ${volatile.displayName}"
                    }
                    val effectWidth = (display.length * charWidth * 0.8).toInt() + (10 * textScale).toInt()

                    if (effectX + effectWidth > maxX && effectX != indentX) {
                        height += (lineHeight * 0.85).toInt()
                        effectX = indentX
                    }
                    effectX += effectWidth
                }
                height += (lineHeight * 0.9).toInt()
            }
        }

        return height
    }

    private fun calculateExpandedContentHeight(
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        panelWidth: Int
    ): Int {
        // Always assume scrollbar for worst-case width
        val effectiveW = panelWidth - UIUtils.FRAME_INSET * 2 - SCROLLBAR_WIDTH - 2
        val contentWidth = effectiveW - UIUtils.CELL_PAD * 2

        val hasGlobal = hasFieldEffects()
        val hasAlly = hasAnySideConditions(true)
        val hasEnemy = hasAnySideConditions(false)
        val allPokemon = allyPokemonData + opponentPokemonData
        val hasPokemonEffects = allPokemon.any { it.hasAnyEffects() }
        val hasConditions = hasGlobal || hasAlly || hasEnemy

        if (!hasConditions && !hasPokemonEffects) {
            return UIUtils.CELL_VPAD_TOP + lineHeight + UIUtils.CELL_VPAD_BOTTOM
        }

        var height = UIUtils.CELL_VPAD_TOP

        if (hasGlobal) height += calculateFieldContentHeight()
        if (hasGlobal && (hasAlly || hasEnemy)) height += groupDividerHeight()

        if (hasAlly) {
            height += subLabelHeight()
            height += calculateSideContentHeight(true)
        }
        if (hasAlly && hasEnemy) height += groupDividerHeight()

        if (hasEnemy) {
            height += subLabelHeight()
            height += calculateSideContentHeight(false)
        }

        if (hasConditions && hasPokemonEffects) height += groupDividerHeight()

        if (hasPokemonEffects) height += calculatePokemonContentHeight(allPokemon, contentWidth)

        height += UIUtils.CELL_VPAD_BOTTOM
        return height
    }

    private fun calculateCollapsedContentHeight(
        allyPokemonData: List<PokemonBattleData>,
        opponentPokemonData: List<PokemonBattleData>,
        panelWidth: Int
    ): Int {
        var rowCount = 0

        val hasWeather = BattleStateTracker.weather != null
        val hasTerrain = BattleStateTracker.terrain != null
        val fieldCount = BattleStateTracker.getFieldConditions().size
        val playerConds = BattleStateTracker.getPlayerSideConditions()
        val oppConds = BattleStateTracker.getOpponentSideConditions()
        val allyEffectCount = allyPokemonData.count { it.hasAnyEffects() }
        val enemyEffectCount = opponentPokemonData.count { it.hasAnyEffects() }

        val hasAnyEffects = hasWeather || hasTerrain || fieldCount > 0 ||
                           playerConds.isNotEmpty() || oppConds.isNotEmpty() ||
                           allyEffectCount > 0 || enemyEffectCount > 0

        if (!hasAnyEffects) {
            return UIUtils.CELL_VPAD_TOP + lineHeight + UIUtils.CELL_VPAD_BOTTOM
        }

        var contentH = 0
        if (hasWeather) { contentH += lineHeight; rowCount++ }
        if (hasTerrain) { contentH += lineHeight; rowCount++ }
        contentH += fieldCount * lineHeight; rowCount += fieldCount
        if (playerConds.isNotEmpty()) { contentH += (lineHeight * 0.9).toInt(); rowCount++ }
        if (oppConds.isNotEmpty()) { contentH += (lineHeight * 0.9).toInt(); rowCount++ }
        if (allyEffectCount > 0) { contentH += (lineHeight * 0.9).toInt(); rowCount++ }
        if (enemyEffectCount > 0) { contentH += (lineHeight * 0.9).toInt(); rowCount++ }

        // Add dividers between rows
        val dividers = if (rowCount > 1) (rowCount - 1) * UIUtils.DIVIDER_H else 0

        return UIUtils.CELL_VPAD_TOP + contentH + dividers + UIUtils.CELL_VPAD_BOTTOM
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Common rendering helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun drawGroupDivider(context: DrawContext, x: Int, y: Int, width: Int): Int {
        val divY = y + GROUP_DIVIDER_EXTRA / 2
        UIUtils.drawPopupRowDivider(context, x, width, divY, colorTransform())
        return divY + UIUtils.DIVIDER_H + GROUP_DIVIDER_EXTRA / 2
    }

    private fun drawResizeHandles(context: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        val handleColor = applyOpacity(if (hoveredZone != UIUtils.ResizeZone.NONE || isResizing) RESIZE_HANDLE_HOVER else RESIZE_HANDLE_COLOR)
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

        context.fill(x, y, x + SCROLLBAR_WIDTH, y + height, applyOpacity(SCROLLBAR_BG))

        val thumbHeight = calculateThumbHeight(height)
        val maxScroll = contentHeight - visibleContentHeight
        val scrollRatio = if (maxScroll > 0) PanelConfig.scrollOffset.toFloat() / maxScroll else 0f
        val thumbY = y + ((height - thumbHeight) * scrollRatio).toInt()

        val thumbColor = if (isOverScrollbar || isScrollbarDragging) SCROLLBAR_THUMB_HOVER else SCROLLBAR_THUMB
        context.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, applyOpacity(thumbColor))
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
        drawTextClipped(context, icon, x.toFloat(), y.toFloat(), accentColor, 0.8f * textScale)
        drawTextClipped(context, name, (x + (14 * textScale).toInt()).toFloat(), y.toFloat(), TEXT_LIGHT, 0.8f * textScale)
        if (info.isNotEmpty()) {
            val infoWidth = info.length * charWidth
            drawTextClipped(context, info, (x + width - infoWidth).toFloat(), y.toFloat(), TEXT_DIM, 0.75f * textScale)
        }
    }

    private fun enableScissor(x: Int, y: Int, width: Int, height: Int) {
        scissorBounds = UIUtils.enableScissor(x, y, width, height)
    }

    private fun disableScissor() {
        UIUtils.disableScissor()
    }

    private fun drawText(context: DrawContext, text: String, x: Float, y: Float, color: Int, scale: Float) {
        UIUtils.drawText(context, text, x, y, applyOpacity(color), scale)
    }

    private fun drawTextClipped(context: DrawContext, text: String, x: Float, y: Float, color: Int, scale: Float) {
        UIUtils.drawTextClipped(context, text, x, y, applyOpacity(color), scale, scissorBounds)
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

    @JvmInline
    value class SideName(val name: String)

    private fun getPlayerSideName(side: ClientBattleSide, isSpectating: Boolean, isPlayerSide: Boolean): SideName {
        val actors = side.actors
        if (actors.isEmpty()) {
            return if (isPlayerSide) SideName(UI.ally) else SideName(UI.enemy)
        }

        val firstActorName = actors.firstOrNull()?.displayName?.string
            ?: return if (isPlayerSide) SideName(UI.ally) else SideName(UI.enemy)

        val truncatedName = if (firstActorName.length > 12) {
            firstActorName.take(11) + "…"
        } else {
            firstActorName
        }

        return SideName(truncatedName.uppercase())
    }
}

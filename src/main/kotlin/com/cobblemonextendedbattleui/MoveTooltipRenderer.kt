package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.categories.DamageCategories
import com.cobblemon.mod.common.battles.ai.strongBattleAI.AIUtility
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.render.drawScaledText
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * Renders tooltips for moves in the Fight selection menu.
 * Shows move details (power, accuracy, description) and effectiveness against the opponent.
 */
object MoveTooltipRenderer {
    // Tooltip styling constants (matching TeamIndicatorUI)
    private const val TOOLTIP_PADDING = 6
    private const val TOOLTIP_CORNER = 3
    private const val TOOLTIP_BASE_LINE_HEIGHT = 10
    private const val TOOLTIP_FONT_SCALE = 0.85f

    // Base colors
    private val TOOLTIP_BG = color(22, 27, 34, 245)
    private val TOOLTIP_LABEL = color(140, 150, 165, 255)
    private val TOOLTIP_DIM = color(100, 110, 120, 255)

    // Stat colors
    private val COLOR_POWER = color(255, 140, 90, 255)
    private val COLOR_ACCURACY = color(100, 180, 255, 255)
    private val COLOR_PP = color(255, 210, 80, 255)
    private val COLOR_PP_LOW = color(255, 100, 80, 255)

    // Category colors
    private val COLOR_PHYSICAL = color(255, 150, 100, 255)
    private val COLOR_SPECIAL = color(160, 140, 255, 255)
    private val COLOR_STATUS = color(170, 170, 180, 255)

    // Priority colors
    private val COLOR_PRIORITY_POSITIVE = color(100, 220, 200, 255)
    private val COLOR_PRIORITY_NEGATIVE = color(220, 100, 120, 255)

    // Effectiveness colors
    private val SUPER_EFFECTIVE_4X = color(50, 220, 50, 255)
    private val SUPER_EFFECTIVE_2X = color(80, 200, 80, 255)
    private val NEUTRAL = color(180, 185, 190, 255)
    private val NOT_EFFECTIVE = color(220, 100, 80, 255)
    private val IMMUNE = color(120, 120, 120, 255)

    // Data class for tracking move tile bounds
    data class MoveTileBounds(
        val x: Float,
        val y: Float,
        val width: Int,
        val height: Int,
        val moveTemplate: MoveTemplate,
        val currentPp: Int,
        val maxPp: Int
    )

    // Currently tracked move tiles (cleared each frame)
    private val moveTileBounds = mutableListOf<MoveTileBounds>()

    // Currently hovered move (null if none)
    private var hoveredMove: MoveTileBounds? = null

    // Input state tracking for font keybinds
    private var wasIncreaseFontKeyPressed = false
    private var wasDecreaseFontKeyPressed = false

    /**
     * Clear all tracked move tile bounds. Called at start of each render frame.
     */
    fun clear() {
        moveTileBounds.clear()
        hoveredMove = null
    }

    /**
     * Register a move tile's bounds for hover detection.
     */
    fun registerMoveTile(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        moveTemplate: MoveTemplate,
        currentPp: Int,
        maxPp: Int
    ) {
        moveTileBounds.add(MoveTileBounds(x, y, width, height, moveTemplate, currentPp, maxPp))
    }

    /**
     * Update hover state based on mouse position.
     */
    fun updateHoverState(mouseX: Int, mouseY: Int) {
        hoveredMove = moveTileBounds.find { bounds ->
            mouseX >= bounds.x && mouseX <= bounds.x + bounds.width &&
            mouseY >= bounds.y && mouseY <= bounds.y + bounds.height
        }
    }

    /**
     * Render the tooltip if a move is hovered.
     */
    fun renderTooltip(context: DrawContext) {
        val move = hoveredMove ?: return
        if (!PanelConfig.enableMoveTooltips) return

        val mc = MinecraftClient.getInstance()
        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight
        val textRenderer = mc.textRenderer

        val fontScale = TOOLTIP_FONT_SCALE * PanelConfig.moveTooltipFontScale
        val lineHeight = (TOOLTIP_BASE_LINE_HEIGHT * fontScale).toInt().coerceAtLeast(8)

        // Build tooltip lines (each line can have multiple colored segments)
        val lines = buildTooltipLines(move)

        // Calculate dimensions - sum up segment widths for each line
        val maxLineWidth = lines.maxOfOrNull { segments ->
            segments.sumOf { (text, _) -> (textRenderer.getWidth(text) * fontScale).toInt() }
        } ?: 80
        val tooltipWidth = maxLineWidth + TOOLTIP_PADDING * 2
        val tooltipHeight = lines.size * lineHeight + TOOLTIP_PADDING * 2

        // Position tooltip above the move tile
        var tooltipX = move.x.toInt() + (move.width / 2) - (tooltipWidth / 2)
        var tooltipY = move.y.toInt() - tooltipHeight - 4

        // If not enough space above, show below
        if (tooltipY < 4) {
            tooltipY = move.y.toInt() + move.height + 4
        }

        // Clamp to screen bounds
        tooltipX = tooltipX.coerceIn(4, screenWidth - tooltipWidth - 4)
        tooltipY = tooltipY.coerceIn(4, screenHeight - tooltipHeight - 4)

        // Push z-level forward so tooltip renders on top of move tiles
        val matrices = context.matrices
        matrices.push()
        matrices.translate(0.0, 0.0, 400.0)

        // Get type color for border
        val typeColor = UIUtils.getTypeColor(move.moveTemplate.elementalType)

        // Draw background with type-colored border
        drawTooltipBackground(context, tooltipX, tooltipY, tooltipWidth, tooltipHeight, typeColor)

        // Draw text lines (each line can have multiple colored segments)
        var lineY = tooltipY + TOOLTIP_PADDING
        for (segments in lines) {
            var segmentX = (tooltipX + TOOLTIP_PADDING).toFloat()
            for ((text, textColor) in segments) {
                drawScaledText(
                    context = context,
                    text = Text.literal(text),
                    x = segmentX,
                    y = lineY.toFloat(),
                    colour = textColor,
                    scale = fontScale,
                    shadow = false
                )
                segmentX += textRenderer.getWidth(text) * fontScale
            }
            lineY += lineHeight
        }

        matrices.pop()
    }

    /**
     * Build the tooltip content lines. Each line is a list of (text, color) segments.
     */
    private fun buildTooltipLines(move: MoveTileBounds): List<List<Pair<String, Int>>> {
        val lines = mutableListOf<List<Pair<String, Int>>>()
        val template = move.moveTemplate

        val typeColor = UIUtils.getTypeColor(template.elementalType)

        // Move name
        lines.add(listOf(template.displayName.string to typeColor))

        // Type and Category
        val typeName = template.elementalType.displayName.string
        val categoryName = template.damageCategory.displayName.string
        val categoryColor = when (template.damageCategory) {
            DamageCategories.PHYSICAL -> COLOR_PHYSICAL
            DamageCategories.SPECIAL -> COLOR_SPECIAL
            else -> COLOR_STATUS
        }
        lines.add(listOf(
            typeName to typeColor,
            " • " to TOOLTIP_DIM,
            categoryName to categoryColor
        ))

        val power = if (template.power > 0) template.power.toInt().toString() else "--"
        lines.add(listOf("Power: $power" to COLOR_POWER))

        val accuracy = if (template.accuracy > 0) "${template.accuracy.toInt()}%" else "--"
        lines.add(listOf("Accuracy: $accuracy" to COLOR_ACCURACY))

        val ppRatio = move.currentPp.toFloat() / move.maxPp.coerceAtLeast(1)
        val ppColor = if (ppRatio <= 0.25f) COLOR_PP_LOW else COLOR_PP
        lines.add(listOf("PP: ${move.currentPp}/${move.maxPp}" to ppColor))

        // Priority (only shown if non-zero)
        if (template.priority != 0) {
            val prioritySign = if (template.priority > 0) "+" else ""
            val priorityColor = if (template.priority > 0) COLOR_PRIORITY_POSITIVE else COLOR_PRIORITY_NEGATIVE
            val priorityLabel = if (template.priority > 0) "Priority (Fast)" else "Priority (Slow)"
            lines.add(listOf("$priorityLabel: $prioritySign${template.priority}" to priorityColor))
        }

        // Description
        val description = template.description.string
        if (description.isNotEmpty()) {
            // Wrap long descriptions
            val wrappedDesc = wrapText(description, 35)
            for (line in wrappedDesc) {
                lines.add(listOf(line to TOOLTIP_DIM))
            }
        }

        // Effectiveness against opponent
        val effectivenessLines = getEffectivenessLines()
        if (effectivenessLines.isNotEmpty()) {
            lines.add(listOf("" to 0)) // Spacer
            lines.addAll(effectivenessLines)
        }

        return lines
    }

    /**
     * Get lines showing effectiveness against the opponent.
     * For status moves, only shows immunity (0x) since type effectiveness doesn't affect them otherwise.
     */
    private fun getEffectivenessLines(): List<List<Pair<String, Int>>> {
        val move = hoveredMove ?: return emptyList()
        val lines = mutableListOf<List<Pair<String, Int>>>()

        val battle = CobblemonClient.battle ?: return emptyList()
        val playerUUID = MinecraftClient.getInstance().player?.uuid ?: return emptyList()

        // Find player's side
        val playerSide = battle.side1.actors.any { it.uuid == playerUUID }
        val opponentSide = if (playerSide) battle.side2 else battle.side1

        // Get active opponent Pokemon
        val opponents = opponentSide.activeClientBattlePokemon.mapNotNull { it.battlePokemon }
        if (opponents.isEmpty()) return emptyList()

        val moveType = move.moveTemplate.elementalType
        val isStatusMove = move.moveTemplate.damageCategory == DamageCategories.STATUS

        for (opponent in opponents) {
            val species = opponent.species ?: continue
            val types = listOfNotNull(species.primaryType, species.secondaryType)

            if (types.isEmpty()) continue

            // Calculate effectiveness
            var multiplier = 1.0
            for (defType in types) {
                multiplier *= AIUtility.getDamageMultiplier(moveType, defType)
            }

            // For status moves, only show if there's an immunity
            if (isStatusMove && multiplier != 0.0) continue

            val opponentName = opponent.displayName.string
            val (effectText, effectColor) = getEffectivenessText(multiplier)

            lines.add(listOf("vs $opponentName:" to TOOLTIP_LABEL))
            lines.add(listOf(effectText to effectColor))
        }

        return lines
    }

    /**
     * Get display text and color for an effectiveness multiplier.
     */
    private fun getEffectivenessText(multiplier: Double): Pair<String, Int> {
        return when {
            multiplier == 0.0 -> "Immune (0x)" to IMMUNE
            multiplier < 0.5 -> "Not effective (${formatMultiplier(multiplier)}x)" to NOT_EFFECTIVE
            multiplier < 1.0 -> "Not very effective (${formatMultiplier(multiplier)}x)" to NOT_EFFECTIVE
            multiplier > 2.0 -> "Super effective! (${formatMultiplier(multiplier)}x)" to SUPER_EFFECTIVE_4X
            multiplier > 1.0 -> "Super effective! (${formatMultiplier(multiplier)}x)" to SUPER_EFFECTIVE_2X
            else -> "Normal damage (1x)" to NEUTRAL
        }
    }

    /**
     * Format multiplier for display (e.g., 0.25, 0.5, 2, 4).
     */
    private fun formatMultiplier(multiplier: Double): String {
        return if (multiplier == multiplier.toInt().toDouble()) {
            multiplier.toInt().toString()
        } else {
            String.format("%.2g", multiplier)
        }
    }

    /**
     * Wrap text to fit within a max character width.
     */
    private fun wrapText(text: String, maxChars: Int): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.length + word.length + 1 > maxChars && currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
                currentLine = StringBuilder()
            }
            if (currentLine.isNotEmpty()) currentLine.append(" ")
            currentLine.append(word)
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines
    }

    /**
     * Draw rounded tooltip background with type-colored border.
     */
    private fun drawTooltipBackground(context: DrawContext, x: Int, y: Int, width: Int, height: Int, borderColor: Int) {
        val bg = TOOLTIP_BG
        val border = borderColor
        val corner = TOOLTIP_CORNER

        // Draw main background (cross pattern for rounded corners)
        context.fill(x + corner, y, x + width - corner, y + height, bg)
        context.fill(x, y + corner, x + width, y + height - corner, bg)

        // Fill corners with graduated rounding
        // Top-left corner
        context.fill(x + 2, y + 1, x + corner, y + 2, bg)
        context.fill(x + 1, y + 2, x + corner, y + corner, bg)
        // Top-right corner
        context.fill(x + width - corner, y + 1, x + width - 2, y + 2, bg)
        context.fill(x + width - corner, y + 2, x + width - 1, y + corner, bg)
        // Bottom-left corner
        context.fill(x + 2, y + height - 2, x + corner, y + height - 1, bg)
        context.fill(x + 1, y + height - corner, x + corner, y + height - 2, bg)
        // Bottom-right corner
        context.fill(x + width - corner, y + height - 2, x + width - 2, y + height - 1, bg)
        context.fill(x + width - corner, y + height - corner, x + width - 1, y + height - 2, bg)

        // Draw border
        // Top edge
        context.fill(x + corner, y, x + width - corner, y + 1, border)
        // Bottom edge
        context.fill(x + corner, y + height - 1, x + width - corner, y + height, border)
        // Left edge
        context.fill(x, y + corner, x + 1, y + height - corner, border)
        // Right edge
        context.fill(x + width - 1, y + corner, x + width, y + height - corner, border)

        // Corner pixels
        context.fill(x + 1, y + 1, x + 2, y + 2, border)
        context.fill(x + width - 2, y + 1, x + width - 1, y + 2, border)
        context.fill(x + 1, y + height - 2, x + 2, y + height - 1, border)
        context.fill(x + width - 2, y + height - 2, x + width - 1, y + height - 1, border)
    }

    /**
     * Create ARGB color integer.
     */
    private fun color(r: Int, g: Int, b: Int, a: Int = 255): Int {
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // Input Handling
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if move tooltip should handle font input (i.e., a move is hovered).
     */
    fun shouldHandleFontInput(): Boolean {
        return hoveredMove != null
    }

    /**
     * Handle input for font scaling. Called during render when move tooltip is visible.
     */
    fun handleInput() {
        if (!shouldHandleFontInput()) return

        val mc = MinecraftClient.getInstance()
        val handle = mc.window.handle
        handleFontKeybinds(handle)
    }

    /**
     * Handle font size keybinds ([ and ] keys).
     */
    private fun handleFontKeybinds(handle: Long) {
        val increaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.increaseFontKey.boundKeyTranslationKey)
        val isIncreaseDown = isKeyOrButtonPressed(handle, increaseKey)
        if (isIncreaseDown && !wasIncreaseFontKeyPressed) {
            PanelConfig.adjustMoveTooltipFontScale(PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasIncreaseFontKeyPressed = isIncreaseDown

        val decreaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.decreaseFontKey.boundKeyTranslationKey)
        val isDecreaseDown = isKeyOrButtonPressed(handle, decreaseKey)
        if (isDecreaseDown && !wasDecreaseFontKeyPressed) {
            PanelConfig.adjustMoveTooltipFontScale(-PanelConfig.FONT_SCALE_STEP)
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

    /**
     * Handle scroll event for font scaling when Ctrl is held and hovering a move.
     * Returns true if the event was consumed.
     */
    fun handleScroll(deltaY: Double): Boolean {
        if (hoveredMove == null) return false

        val mc = MinecraftClient.getInstance()
        val handle = mc.window.handle
        val isCtrlDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                         GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS

        if (isCtrlDown) {
            val delta = if (deltaY > 0) PanelConfig.FONT_SCALE_STEP else -PanelConfig.FONT_SCALE_STEP
            PanelConfig.adjustMoveTooltipFontScale(delta)
            PanelConfig.save()
            return true
        }
        return false
    }
}

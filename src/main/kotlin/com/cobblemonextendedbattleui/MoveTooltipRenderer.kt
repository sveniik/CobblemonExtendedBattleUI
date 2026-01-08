package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.categories.DamageCategories
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.battles.ai.strongBattleAI.AIUtility
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.render.drawScaledText
import com.cobblemon.mod.common.CobblemonItemComponents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.registry.Registries
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

    // New stat colors
    private val COLOR_CRIT = color(255, 200, 50, 255)      // Gold for high crit
    private val COLOR_EFFECT = color(200, 160, 255, 255)   // Purple for effects

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

        // Power with STAB, Sheer Force, and held item calculation
        if (template.power > 0) {
            val basePower = template.power.toInt()
            val playerTypes = getPlayerPokemonTypes()
            val playerAbility = getPlayerPokemonAbility()
            val hasStab = playerTypes.any { it.name == template.elementalType.name }
            val hasSheerForce = playerAbility == "sheerforce" &&
                template.effectChances.isNotEmpty() && template.effectChances[0] > 0

            // Check for held item power boost
            val itemBoost = getHeldItemPowerBoost(template.elementalType.name)

            // Calculate effective power with modifiers
            var effectivePower = template.power
            val modifiers = mutableListOf<String>()

            if (hasStab) {
                effectivePower *= 1.5
                modifiers.add("STAB")
            }
            if (hasSheerForce) {
                effectivePower *= 1.3
                modifiers.add("Sheer Force")
            }
            if (itemBoost != null) {
                effectivePower *= itemBoost.multiplier
                modifiers.add(itemBoost.displayName)
            }

            if (modifiers.isNotEmpty()) {
                val modifierText = modifiers.joinToString(" + ")
                lines.add(listOf(
                    "Power: $basePower " to COLOR_POWER,
                    "($modifierText: ${effectivePower.toInt()})" to SUPER_EFFECTIVE_2X
                ))
            } else {
                lines.add(listOf("Power: $basePower" to COLOR_POWER))
            }
        } else {
            lines.add(listOf("Power: --" to COLOR_POWER))
        }

        val accuracy = if (template.accuracy > 0) "${template.accuracy.toInt()}%" else "--"
        lines.add(listOf("Accuracy: $accuracy" to COLOR_ACCURACY))

        // Critical hit rate display
        // Gen 6+ crit stages: Stage 0 = 1/24 (4.17%), +1 = 1/8 (12.5%), +2 = 1/2 (50%), +3+ = 100%
        // critRatio in Showdown: 1 = Stage 0, 2 = Stage +1, 3 = Stage +2, 4+ = Stage +3+

        if (template.power > 0) {
            val baseCritRatio = template.critRatio

            val hasSuperLuck = getPlayerPokemonAbility() == "superluck"
            val heldItemName = getPlayerPokemonHeldItem(true)
            val hasCritItem = heldItemName == "cobblemon:scope_lens" || heldItemName == "cobblemon:razor_claw"

            var critBonus = 0
            val boostSources = mutableListOf<String>()

            if (hasSuperLuck) {
                critBonus++
                boostSources.add("Super Luck")
            }

            if (hasCritItem) {
                critBonus++
                boostSources.add(
                    if (heldItemName == "cobblemon:scope_lens") "Scope Lens" else "Razor Claw"
                )
            }

            val effectiveCritRatio = (baseCritRatio + critBonus).coerceAtMost(4.0)

            val baseCritPercent = critRatioToPercent(baseCritRatio)
            val effectiveCritPercent = critRatioToPercent(effectiveCritRatio)

            // Decide label
            val critLabel = if (baseCritRatio > 1.0) "High Crit" else "Crit"

            // Always show base crit
            val line = mutableListOf<Pair<String, Int>>()
            line.add("$critLabel: $baseCritPercent" to COLOR_CRIT)

            // If boosted, show improvement
            if (critBonus > 0) {
                val boostText = boostSources.joinToString(" + ")
                line.add(" ($boostText → $effectiveCritPercent)" to SUPER_EFFECTIVE_2X)
            }

            lines.add(line)
        }


        // Effect chance (e.g., 10% for Ember's burn)
        // Abilities: Serene Grace doubles chance, Sheer Force removes effect for power boost
        if (template.effectChances.isNotEmpty() && template.effectChances[0] > 0) {
            val baseEffect = template.effectChances[0]
            val playerAbility = getPlayerPokemonAbility()

            when (playerAbility) {
                "sheerforce" -> {
                    // Sheer Force removes secondary effects for 30% power boost
                    lines.add(listOf(
                        "Effect: " to COLOR_EFFECT,
                        "N/A (Sheer Force)" to TOOLTIP_DIM
                    ))
                }
                "serenegrace" -> {
                    // Serene Grace doubles secondary effect chance
                    val doubledEffect = (baseEffect * 2).coerceAtMost(100.0).toInt()
                    lines.add(listOf(
                        "Effect: ${baseEffect.toInt()}% " to COLOR_EFFECT,
                        "(Serene Grace: $doubledEffect%)" to SUPER_EFFECTIVE_2X
                    ))
                }
                else -> {
                    lines.add(listOf("Effect: ${baseEffect.toInt()}%" to COLOR_EFFECT))
                }
            }
        }

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
     * Uses "Extremely Effective/Ineffective" for 4x multipliers like Pokemon Champions.
     */
    private fun getEffectivenessText(multiplier: Double): Pair<String, Int> {
        return when {
            multiplier == 0.0 -> "Immune (0x)" to IMMUNE
            multiplier <= 0.25 -> "Extremely Ineffective (${formatMultiplier(multiplier)}x)" to NOT_EFFECTIVE
            multiplier < 0.5 -> "Not effective (${formatMultiplier(multiplier)}x)" to NOT_EFFECTIVE
            multiplier < 1.0 -> "Not very effective (${formatMultiplier(multiplier)}x)" to NOT_EFFECTIVE
            multiplier >= 4.0 -> "Extremely Effective! (${formatMultiplier(multiplier)}x)" to SUPER_EFFECTIVE_4X
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
     * Convert Showdown critRatio to percentage string.
     * Gen 6+ mechanics:
     *   critRatio 1 (Stage 0)  = 1/24 ≈ 4.17%
     *   critRatio 2 (Stage +1) = 1/8  = 12.5%
     *   critRatio 3 (Stage +2) = 1/2  = 50%
     *   critRatio 4+ (Stage +3+) = 100%
     */
    private fun critRatioToPercent(critRatio: Double): String {
        return when {
            critRatio >= 4.0 -> "100%"
            critRatio >= 3.0 -> "50%"
            critRatio >= 2.0 -> "12.5%"
            else -> "4.17%"
        }
    }

    /**
     * Get the types of the player's active Pokemon for STAB calculation.
     */
    private fun getPlayerPokemonTypes(): List<ElementalType> {
        val battle = CobblemonClient.battle ?: return emptyList()
        val playerUUID = MinecraftClient.getInstance().player?.uuid ?: return emptyList()
        val playerSide = if (battle.side1.actors.any { it.uuid == playerUUID }) battle.side1 else battle.side2
        val playerPokemon = playerSide.activeClientBattlePokemon.firstOrNull()?.battlePokemon ?: return emptyList()
        val species = playerPokemon.species ?: return emptyList()
        return listOfNotNull(species.primaryType, species.secondaryType)
    }

    /**
     * Get the ability name of the player's active Pokemon.
     * Used for effect chance modifiers (Serene Grace, Sheer Force).
     *
     * Note: Battle packets don't include ability data, so we look up the Pokemon
     * in the player's party by UUID to get the full ability information.
     */
    private fun getPlayerPokemonAbility(): String? {
        val partyPokemon = getPlayerPartyPokemon() ?: return null
        return partyPokemon.ability.name
    }

    /**
     * Get the held item name of the player's active Pokemon.
     * Returns the Showdown ID for the item (e.g., "charcoal", "lifeorb").
     * Returns null if the item is empty or has been consumed (e.g., gems after use).
     */
    private fun getPlayerPokemonHeldItem(id: Boolean): String? {
        val battle = CobblemonClient.battle ?: return null
        val playerUUID = MinecraftClient.getInstance().player?.uuid ?: return null
        val playerSide = if (battle.side1.actors.any { it.uuid == playerUUID }) battle.side1 else battle.side2
        val battlePokemon = playerSide.activeClientBattlePokemon.firstOrNull()?.battlePokemon ?: return null

        // Check BattleStateTracker first - this tracks item consumption via battle messages
        // and is more reliable than party storage during battle
        val trackedItem = BattleStateTracker.getItem(battlePokemon.uuid)
        if (trackedItem != null) {
            // If item was consumed, knocked off, or stolen, return null
            if (trackedItem.status != BattleStateTracker.ItemStatus.HELD) {
                return null
            }
        }

        // Get item from party storage
        val partyPokemon = CobblemonClient.storage.party.findByUUID(battlePokemon.uuid) ?: return null
        val heldItem = partyPokemon.heldItem()
        if (heldItem.isEmpty) return null

        // First try to get the Showdown ID from the HELD_ITEM_EFFECT component
        // This handles remapped items like charcoal_stick -> charcoal
        val heldItemEffect = heldItem.get(CobblemonItemComponents.HELD_ITEM_EFFECT)
        if (heldItemEffect != null) {
            // Check if the item has been consumed (e.g., gems after use)
            if (heldItemEffect.consumed) {
                return null
            }
            return heldItemEffect.showdownId
        }

        // Fallback: get from registry path and remove underscores (Showdown format)
        val registryPath = Registries.ITEM.getId(heldItem.item).path
        return if (id) heldItem.item.toString()
        else registryPath.replace("_", "")
    }

    /**
     * Get power boost from held item if applicable to the move type.
     * @param moveType The type of the move being used
     * @return The item boost info if applicable, null otherwise
     */
    private fun getHeldItemPowerBoost(moveType: String): ItemPowerBoostParser.ItemPowerBoost? {
        val heldItemName = getPlayerPokemonHeldItem(false) ?: return null
        val boost = ItemPowerBoostParser.getBoostForItem(heldItemName) ?: return null

        // Check if boost applies to this move type
        return when {
            boost.boostedType == null -> boost  // Life Orb - applies to all damaging moves
            boost.boostedType.equals(moveType, ignoreCase = true) -> boost
            else -> null
        }
    }

    /**
     * Get the player's active Pokemon from the party storage.
     * Battle packets don't include full Pokemon data, so we look up by UUID.
     */
    private fun getPlayerPartyPokemon(): com.cobblemon.mod.common.pokemon.Pokemon? {
        val battle = CobblemonClient.battle ?: return null
        val playerUUID = MinecraftClient.getInstance().player?.uuid ?: return null
        val playerSide = if (battle.side1.actors.any { it.uuid == playerUUID }) battle.side1 else battle.side2
        val battlePokemon = playerSide.activeClientBattlePokemon.firstOrNull()?.battlePokemon ?: return null
        return CobblemonClient.storage.party.findByUUID(battlePokemon.uuid)
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

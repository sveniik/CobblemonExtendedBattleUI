package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.api.pokemon.status.Statuses
import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.drawScaledText
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.joml.Quaternionf
import org.lwjgl.glfw.GLFW
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Displays team indicators for each side's Pokemon using rendered 3D models.
 * Shows status conditions and KO'd Pokemon at a glance via color tinting.
 *
 * Supports both participating in battles and spectating:
 * - When in battle: Uses battle actor's pokemon list for authoritative HP/status data
 * - When spectating: Uses battle data to track both sides as Pokemon are revealed
 *
 * Hover tooltips show detailed info including moves, items, and abilities.
 * Falls back to pokeball rendering if model loading fails.
 *
 * Note: Uses battle data directly instead of client party storage to ensure
 * correct updates on servers where party storage may not sync during battle.
 */
object TeamIndicatorUI {

    // Match Cobblemon's exact positioning constants from BattleOverlay.kt
    private const val HORIZONTAL_INSET = 12
    private const val VERTICAL_INSET = 10

    // Cobblemon tile dimensions (from BattleOverlay companion object)
    private const val TILE_HEIGHT = 40
    private const val COMPACT_TILE_HEIGHT = 28

    // Pokemon model indicator settings
    private const val MODEL_SIZE = 24      // Compact size for indicators
    private const val MODEL_SPACING = 3    // Tight spacing between models
    private const val MODEL_OFFSET_Y = 10  // Gap below the last tile (moves panel down)

    // Background panel settings
    private const val PANEL_PADDING_V = 2  // Vertical padding (top/bottom)
    private const val PANEL_PADDING_H = 5  // Horizontal padding (left/right)
    private const val PANEL_CORNER = 3     // Corner rounding radius
    private val PANEL_BG = color(15, 20, 25, 180)       // Semi-transparent dark background
    private val PANEL_BORDER = color(60, 70, 85, 200)   // Subtle border

    // Fallback pokeball settings (used when model rendering fails)
    private const val BALL_SIZE = 10
    private const val BALL_SPACING = 3

    // Colors
    private val COLOR_NORMAL_TOP = color(255, 80, 80)      // Red top half
    private val COLOR_NORMAL_BOTTOM = color(240, 240, 240) // White bottom half
    private val COLOR_NORMAL_BAND = color(40, 40, 40)      // Dark band
    private val COLOR_NORMAL_CENTER = color(255, 255, 255) // White center button

    // Status colors (replace top half color)
    private val COLOR_POISON = color(160, 90, 200)         // Purple
    private val COLOR_BURN = color(255, 140, 50)           // Orange
    private val COLOR_PARALYSIS = color(255, 220, 50)      // Yellow
    private val COLOR_FREEZE = color(100, 200, 255)        // Light blue
    private val COLOR_SLEEP = color(150, 150, 170)         // Gray-ish

    // KO colors
    private val COLOR_KO_TOP = color(80, 80, 80)
    private val COLOR_KO_BOTTOM = color(60, 60, 60)
    private val COLOR_KO_BAND = color(40, 40, 40)
    private val COLOR_KO_CENTER = color(100, 100, 100)

    private fun color(r: Int, g: Int, b: Int, a: Int = 255): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    // Track Pokemon as they're revealed in battle
    data class TrackedPokemon(
        val uuid: UUID,
        var hpPercent: Float,  // 0.0 to 1.0
        var status: Status?,
        var isKO: Boolean,
        // For model rendering
        var speciesIdentifier: Identifier? = null,
        var aspects: Set<String> = emptySet()
    )

    // Track Pokemon for both sides separately (for spectating and opponent tracking)
    private val trackedSide1Pokemon = ConcurrentHashMap<UUID, TrackedPokemon>()
    private val trackedSide2Pokemon = ConcurrentHashMap<UUID, TrackedPokemon>()

    // Persistent KO tracking - Pokemon removed from activePokemon after fainting
    // still need to show as KO'd in pokeball indicators
    private val knockedOutPokemon = ConcurrentHashMap.newKeySet<UUID>()

    // FloatingState cache for Pokemon model rendering (one per UUID)
    private val floatingStates = ConcurrentHashMap<UUID, FloatingState>()

    private var lastBattleId: UUID? = null

    private fun getOrCreateFloatingState(uuid: UUID): FloatingState {
        return floatingStates.computeIfAbsent(uuid) { FloatingState() }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Hover Tooltip Support
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Bounds for a rendered pokeball, used for hover detection.
     */
    data class PokeballBounds(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val uuid: UUID,
        val isLeftSide: Boolean,
        val isPlayerPokemon: Boolean  // True if this is the player's own Pokemon (not opponent/spectated)
    )

    /**
     * Aggregated data for tooltip display.
     */
    data class TooltipData(
        val pokemonName: String,
        val hpPercent: Float,
        val statusCondition: Status?,
        val isKO: Boolean,
        val revealedMoves: Set<String>,
        val item: BattleStateTracker.TrackedItem?,
        val statChanges: Map<BattleStateTracker.BattleStat, Int>,
        val volatileStatuses: Set<BattleStateTracker.VolatileStatusState>
    )

    // Currently rendered pokeball bounds (refreshed each frame)
    private val pokeballBounds = mutableListOf<PokeballBounds>()

    // Currently hovered pokeball (null if none)
    private var hoveredPokeball: PokeballBounds? = null

    // Currently rendered tooltip bounds (for input handling)
    private var tooltipBounds: TooltipBoundsData? = null
    private data class TooltipBoundsData(val x: Int, val y: Int, val width: Int, val height: Int)

    // Team panel bounds (for input handling - covers all pokeball indicators)
    private var leftTeamPanelBounds: TooltipBoundsData? = null
    private var rightTeamPanelBounds: TooltipBoundsData? = null

    // Key state tracking for font size adjustment
    private var wasIncreaseFontKeyPressed = false
    private var wasDecreaseFontKeyPressed = false

    // Tooltip colors
    private val TOOLTIP_BG = color(22, 27, 34, 245)
    private val TOOLTIP_BORDER = color(55, 65, 80, 255)
    private val TOOLTIP_TEXT = color(220, 225, 230, 255)
    private val TOOLTIP_HEADER = color(255, 255, 255, 255)
    private val TOOLTIP_LABEL = color(140, 150, 165, 255)
    private val TOOLTIP_HP_HIGH = color(100, 220, 100, 255)
    private val TOOLTIP_HP_MED = color(220, 180, 50, 255)
    private val TOOLTIP_HP_LOW = color(220, 80, 80, 255)
    private val TOOLTIP_STAT_BOOST = color(100, 200, 100, 255)
    private val TOOLTIP_STAT_DROP = color(200, 100, 100, 255)
    private const val TOOLTIP_PADDING = 6
    private const val TOOLTIP_CORNER = 3
    private const val TOOLTIP_BASE_LINE_HEIGHT = 10  // Base line height before scaling
    private const val TOOLTIP_FONT_SCALE = 0.85f     // Base font scale multiplier

    /**
     * Clear tracking when battle ends.
     */
    fun clear() {
        trackedSide1Pokemon.clear()
        trackedSide2Pokemon.clear()
        knockedOutPokemon.clear()
        floatingStates.clear()
        pokeballBounds.clear()
        hoveredPokeball = null
        tooltipBounds = null
        leftTeamPanelBounds = null
        rightTeamPanelBounds = null
        wasIncreaseFontKeyPressed = false
        wasDecreaseFontKeyPressed = false
        lastBattleId = null
    }

    /**
     * Mark a Pokemon as KO'd by name. Called from BattleMessageInterceptor when faint messages arrive.
     * This ensures pokeballs show as KO'd even after Pokemon is removed from activePokemon.
     */
    fun markPokemonAsKO(pokemonName: String) {
        val uuid = BattleStateTracker.getPokemonUuid(pokemonName) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("TeamIndicatorUI: Unknown Pokemon '$pokemonName' for KO marking")
            return
        }
        markPokemonAsKO(uuid)
    }

    /**
     * Mark a Pokemon as KO'd by UUID.
     */
    fun markPokemonAsKO(uuid: UUID) {
        knockedOutPokemon.add(uuid)

        // Also update tracked Pokemon maps if present
        trackedSide1Pokemon[uuid]?.isKO = true
        trackedSide2Pokemon[uuid]?.isKO = true

        CobblemonExtendedBattleUI.LOGGER.debug("TeamIndicatorUI: Marked UUID $uuid as KO'd")
    }

    /**
     * Check if a Pokemon is KO'd.
     */
    fun isPokemonKO(uuid: UUID): Boolean = knockedOutPokemon.contains(uuid) || BattleStateTracker.isKO(uuid)

    fun render(context: DrawContext) {
        val battle = CobblemonClient.battle ?: return
        if (battle.minimised) return

        // Clear tracking if this is a new battle
        if (lastBattleId != battle.battleId) {
            clear()
            lastBattleId = battle.battleId
        }

        // Clear pokeball bounds for this frame
        pokeballBounds.clear()

        val mc = MinecraftClient.getInstance()
        val screenWidth = mc.window.scaledWidth
        val player = mc.player ?: return
        val playerUUID = player.uuid

        // Get mouse position for hover detection
        val mouseX = (mc.mouse.x * mc.window.scaledWidth / mc.window.width).toInt()
        val mouseY = (mc.mouse.y * mc.window.scaledHeight / mc.window.height).toInt()

        // Determine if player is in the battle and which side they're on
        val playerInSide1 = battle.side1.actors.any { it.uuid == playerUUID }
        val playerInSide2 = battle.side2.actors.any { it.uuid == playerUUID }
        val isSpectating = !playerInSide1 && !playerInSide2

        // Debug logging for spectator mode to help diagnose flip issues
        if (isSpectating && lastBattleId != battle.battleId) {
            val side1Names = battle.side1.actors.map { it.displayName.string }
            val side2Names = battle.side2.actors.map { it.displayName.string }
            CobblemonExtendedBattleUI.LOGGER.debug(
                "TeamIndicatorUI: Spectating - Side1 (LEFT): $side1Names, Side2 (RIGHT): $side2Names"
            )
        }

        // In Cobblemon's BattleOverlay:
        // - Side1 tiles are ALWAYS displayed on the LEFT of the screen
        // - Side2 tiles are ALWAYS displayed on the RIGHT of the screen
        // We must match this positioning for pokeballs to align with the battle tiles.

        // Update tracked Pokemon for both sides from battle data
        updateTrackedPokemonForSide(battle.side1, trackedSide1Pokemon)
        updateTrackedPokemonForSide(battle.side2, trackedSide2Pokemon)

        // Count active Pokemon for positioning (determines how many tiles are shown)
        val side1ActiveCount = battle.side1.actors.sumOf { it.activePokemon.size }
        val side2ActiveCount = battle.side2.actors.sumOf { it.activePokemon.size }

        val leftY = calculateIndicatorY(side1ActiveCount)
        val rightY = calculateIndicatorY(side2ActiveCount)

        // Find the player's actor if they're in the battle
        val playerActor = battle.side1.actors.find { it.uuid == playerUUID }
            ?: battle.side2.actors.find { it.uuid == playerUUID }

        // Render LEFT side (side1) - player's team if they're on side1, otherwise tracked
        if (playerInSide1 && playerActor != null) {
            // Player is on side1 (left) - use battle actor's pokemon list for authoritative data
            val playerTeam = playerActor.pokemon
            renderBattleTeam(context, HORIZONTAL_INSET, leftY, playerTeam, isLeftSide = true)
        } else {
            // Side1 is opponent or we're spectating - use tracked Pokemon from battle data
            val side1Team = trackedSide1Pokemon.values.toList()
            if (side1Team.isNotEmpty()) {
                renderTrackedTeam(context, HORIZONTAL_INSET, leftY, side1Team, isLeftSide = true)
            }
        }

        // Render RIGHT side (side2) - player's team if they're on side2, otherwise tracked
        if (playerInSide2 && playerActor != null) {
            // Player is on side2 (right) - use battle actor's pokemon list for authoritative data
            val playerTeam = playerActor.pokemon
            val rightWidth = playerTeam.size * (MODEL_SIZE + MODEL_SPACING) - MODEL_SPACING
            renderBattleTeam(context, screenWidth - HORIZONTAL_INSET - rightWidth, rightY, playerTeam, isLeftSide = false)
        } else {
            // Side2 is opponent or we're spectating - use tracked Pokemon from battle data
            val side2Team = trackedSide2Pokemon.values.toList()
            if (side2Team.isNotEmpty()) {
                val rightWidth = side2Team.size * (MODEL_SIZE + MODEL_SPACING) - MODEL_SPACING
                renderTrackedTeam(context, screenWidth - HORIZONTAL_INSET - rightWidth, rightY, side2Team, isLeftSide = false)
            }
        }

        // Detect hovered pokeball
        hoveredPokeball = pokeballBounds.find { bounds ->
            mouseX >= bounds.x && mouseX <= bounds.x + bounds.width &&
            mouseY >= bounds.y && mouseY <= bounds.y + bounds.height
        }

        // Handle input when hovering over team panels (even if tooltip not visible)
        handleInput(mc)
    }

    /**
     * Check if mouse is over any team panel.
     */
    private fun isMouseOverTeamPanels(): Boolean {
        val mc = MinecraftClient.getInstance()
        val mouseX = (mc.mouse.x * mc.window.scaledWidth / mc.window.width).toInt()
        val mouseY = (mc.mouse.y * mc.window.scaledHeight / mc.window.height).toInt()

        leftTeamPanelBounds?.let { bounds ->
            if (mouseX >= bounds.x && mouseX <= bounds.x + bounds.width &&
                mouseY >= bounds.y && mouseY <= bounds.y + bounds.height) {
                return true
            }
        }
        rightTeamPanelBounds?.let { bounds ->
            if (mouseX >= bounds.x && mouseX <= bounds.x + bounds.width &&
                mouseY >= bounds.y && mouseY <= bounds.y + bounds.height) {
                return true
            }
        }
        return false
    }

    /**
     * Check if TeamIndicatorUI should have priority for font input handling.
     * Returns true if tooltip is visible OR mouse is over team panels.
     */
    fun shouldHandleFontInput(): Boolean {
        return hoveredPokeball != null || isMouseOverTeamPanels()
    }

    /**
     * Handle input for font keybinds when we have priority.
     */
    private fun handleInput(mc: MinecraftClient) {
        if (!shouldHandleFontInput()) return

        val handle = mc.window.handle
        handleFontKeybinds(handle)
    }

    /**
     * Update tracked Pokemon for a battle side (used for opponent and spectator views).
     */
    private fun updateTrackedPokemonForSide(side: ClientBattleSide, tracked: ConcurrentHashMap<UUID, TrackedPokemon>) {
        for (actor in side.actors) {
            for (activePokemon in actor.activePokemon) {
                val battlePokemon = activePokemon.battlePokemon ?: continue
                updateTrackedPokemonInMap(battlePokemon, tracked)
            }
        }
    }

    private fun calculateIndicatorY(activeCount: Int): Int {
        if (activeCount <= 0) return VERTICAL_INSET + TILE_HEIGHT + MODEL_OFFSET_Y

        // Cobblemon uses compact mode when there are 3+ active Pokemon on a side
        val isCompact = activeCount >= 3
        val tileHeight = if (isCompact) COMPACT_TILE_HEIGHT else TILE_HEIGHT

        // Visual tile stacking - empirically adjusted based on in-game testing
        // Singles/Doubles: tiles are spaced 15px apart
        // Triples+: tiles use compact mode with tighter spacing, but need more total space
        val effectiveSpacing = when {
            activeCount >= 3 -> 30  // Triple battles need more spacing to clear all tiles
            else -> 15              // Singles and doubles
        }

        val bottomOfTiles = VERTICAL_INSET + (activeCount - 1) * effectiveSpacing + tileHeight

        return bottomOfTiles + MODEL_OFFSET_Y
    }

    /**
     * Update tracked Pokemon in the specified map.
     * Also adds to knockedOutPokemon set when HP reaches 0 for reliable KO tracking.
     */
    private fun updateTrackedPokemonInMap(battlePokemon: ClientBattlePokemon, targetMap: ConcurrentHashMap<UUID, TrackedPokemon>) {
        val uuid = battlePokemon.uuid
        // For opponent Pokemon, hpValue is already a 0.0-1.0 percentage (isHpFlat = false)
        // For player Pokemon, hpValue is absolute and needs to be divided by maxHp (isHpFlat = true)
        val hpPercent = if (battlePokemon.isHpFlat && battlePokemon.maxHp > 0) {
            battlePokemon.hpValue / battlePokemon.maxHp
        } else {
            battlePokemon.hpValue  // Already 0.0-1.0
        }
        val isKO = battlePokemon.hpValue <= 0
        val status = battlePokemon.status

        // Get species identifier for model rendering
        // properties.species returns a String like "pikachu", convert to Identifier
        val speciesName = battlePokemon.properties.species
        val speciesId = speciesName?.let { Identifier.of("cobblemon", it) }
        val aspects = battlePokemon.state.currentAspects

        // If HP is 0, add to persistent KO tracking
        // This catches KO status even if faint message hasn't arrived yet
        if (isKO) {
            knockedOutPokemon.add(uuid)
        }

        targetMap.compute(uuid) { _, existing ->
            if (existing != null) {
                // Update existing - also check persistent KO set
                existing.hpPercent = hpPercent
                existing.status = status
                existing.isKO = isKO || knockedOutPokemon.contains(uuid)
                existing.speciesIdentifier = speciesId ?: existing.speciesIdentifier
                existing.aspects = aspects.ifEmpty { existing.aspects }
                existing
            } else {
                // New Pokemon revealed - check persistent KO set
                TrackedPokemon(uuid, hpPercent, status, isKO || knockedOutPokemon.contains(uuid), speciesId, aspects)
            }
        }
    }

    /**
     * Draw a background panel behind the team's Pokemon models.
     */
    private fun drawTeamPanel(context: DrawContext, x: Int, y: Int, teamSize: Int) {
        if (teamSize <= 0) return

        val panelWidth = teamSize * MODEL_SIZE + (teamSize - 1) * MODEL_SPACING + PANEL_PADDING_H * 2
        val panelHeight = MODEL_SIZE + PANEL_PADDING_V * 2

        val panelX = x - PANEL_PADDING_H
        val panelY = y - PANEL_PADDING_V

        // Draw main background (cross pattern for rounded corners)
        context.fill(panelX + PANEL_CORNER, panelY, panelX + panelWidth - PANEL_CORNER, panelY + panelHeight, PANEL_BG)
        context.fill(panelX, panelY + PANEL_CORNER, panelX + panelWidth, panelY + panelHeight - PANEL_CORNER, PANEL_BG)

        // Fill corners with graduated rounding (creates smooth curve)
        // Top-left corner
        context.fill(panelX + 2, panelY + 1, panelX + PANEL_CORNER, panelY + 2, PANEL_BG)
        context.fill(panelX + 1, panelY + 2, panelX + PANEL_CORNER, panelY + PANEL_CORNER, PANEL_BG)
        // Top-right corner
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + 1, panelX + panelWidth - 2, panelY + 2, PANEL_BG)
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + 2, panelX + panelWidth - 1, panelY + PANEL_CORNER, PANEL_BG)
        // Bottom-left corner
        context.fill(panelX + 2, panelY + panelHeight - 2, panelX + PANEL_CORNER, panelY + panelHeight - 1, PANEL_BG)
        context.fill(panelX + 1, panelY + panelHeight - PANEL_CORNER, panelX + PANEL_CORNER, panelY + panelHeight - 2, PANEL_BG)
        // Bottom-right corner
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + panelHeight - 2, panelX + panelWidth - 2, panelY + panelHeight - 1, PANEL_BG)
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + panelHeight - PANEL_CORNER, panelX + panelWidth - 1, panelY + panelHeight - 2, PANEL_BG)

        // Draw border - top
        context.fill(panelX + PANEL_CORNER, panelY, panelX + panelWidth - PANEL_CORNER, panelY + 1, PANEL_BORDER)
        // Draw border - bottom
        context.fill(panelX + PANEL_CORNER, panelY + panelHeight - 1, panelX + panelWidth - PANEL_CORNER, panelY + panelHeight, PANEL_BORDER)
        // Draw border - left
        context.fill(panelX, panelY + PANEL_CORNER, panelX + 1, panelY + panelHeight - PANEL_CORNER, PANEL_BORDER)
        // Draw border - right
        context.fill(panelX + panelWidth - 1, panelY + PANEL_CORNER, panelX + panelWidth, panelY + panelHeight - PANEL_CORNER, PANEL_BORDER)

        // Draw rounded corner borders (curved edges)
        // Top-left
        context.fill(panelX + 2, panelY, panelX + PANEL_CORNER, panelY + 1, PANEL_BORDER)
        context.fill(panelX + 1, panelY + 1, panelX + 2, panelY + 2, PANEL_BORDER)
        context.fill(panelX, panelY + 2, panelX + 1, panelY + PANEL_CORNER, PANEL_BORDER)
        // Top-right
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY, panelX + panelWidth - 2, panelY + 1, PANEL_BORDER)
        context.fill(panelX + panelWidth - 2, panelY + 1, panelX + panelWidth - 1, panelY + 2, PANEL_BORDER)
        context.fill(panelX + panelWidth - 1, panelY + 2, panelX + panelWidth, panelY + PANEL_CORNER, PANEL_BORDER)
        // Bottom-left
        context.fill(panelX + 2, panelY + panelHeight - 1, panelX + PANEL_CORNER, panelY + panelHeight, PANEL_BORDER)
        context.fill(panelX + 1, panelY + panelHeight - 2, panelX + 2, panelY + panelHeight - 1, PANEL_BORDER)
        context.fill(panelX, panelY + panelHeight - PANEL_CORNER, panelX + 1, panelY + panelHeight - 2, PANEL_BORDER)
        // Bottom-right
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + panelHeight - 1, panelX + panelWidth - 2, panelY + panelHeight, PANEL_BORDER)
        context.fill(panelX + panelWidth - 2, panelY + panelHeight - 2, panelX + panelWidth - 1, panelY + panelHeight - 1, PANEL_BORDER)
        context.fill(panelX + panelWidth - 1, panelY + panelHeight - PANEL_CORNER, panelX + panelWidth, panelY + panelHeight - 2, PANEL_BORDER)
    }

    /**
     * Draw corner overlays AFTER models to ensure rounded corners appear on top of any model overflow.
     * Uses z-translate to render in front of 3D Pokemon models.
     */
    private fun drawPanelCornerOverlays(context: DrawContext, x: Int, y: Int, teamSize: Int) {
        if (teamSize <= 0) return

        val panelWidth = teamSize * MODEL_SIZE + (teamSize - 1) * MODEL_SPACING + PANEL_PADDING_H * 2
        val panelHeight = MODEL_SIZE + PANEL_PADDING_V * 2

        val panelX = x - PANEL_PADDING_H
        val panelY = y - PANEL_PADDING_V

        val matrices = context.matrices
        matrices.push()
        // Push z-level forward to render on top of 3D models
        matrices.translate(0.0, 0.0, 200.0)

        // Redraw rounded corner borders to overlay any model bleed
        // Top-left
        context.fill(panelX + 2, panelY, panelX + PANEL_CORNER, panelY + 1, PANEL_BORDER)
        context.fill(panelX + 1, panelY + 1, panelX + 2, panelY + 2, PANEL_BORDER)
        context.fill(panelX, panelY + 2, panelX + 1, panelY + PANEL_CORNER, PANEL_BORDER)
        // Top-right
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY, panelX + panelWidth - 2, panelY + 1, PANEL_BORDER)
        context.fill(panelX + panelWidth - 2, panelY + 1, panelX + panelWidth - 1, panelY + 2, PANEL_BORDER)
        context.fill(panelX + panelWidth - 1, panelY + 2, panelX + panelWidth, panelY + PANEL_CORNER, PANEL_BORDER)
        // Bottom-left
        context.fill(panelX + 2, panelY + panelHeight - 1, panelX + PANEL_CORNER, panelY + panelHeight, PANEL_BORDER)
        context.fill(panelX + 1, panelY + panelHeight - 2, panelX + 2, panelY + panelHeight - 1, PANEL_BORDER)
        context.fill(panelX, panelY + panelHeight - PANEL_CORNER, panelX + 1, panelY + panelHeight - 2, PANEL_BORDER)
        // Bottom-right
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + panelHeight - 1, panelX + panelWidth - 2, panelY + panelHeight, PANEL_BORDER)
        context.fill(panelX + panelWidth - 2, panelY + panelHeight - 2, panelX + panelWidth - 1, panelY + panelHeight - 1, PANEL_BORDER)
        context.fill(panelX + panelWidth - 1, panelY + panelHeight - PANEL_CORNER, panelX + panelWidth, panelY + panelHeight - 2, PANEL_BORDER)

        matrices.pop()
    }

    /**
     * Render a team using battle actor's pokemon list.
     * This uses authoritative battle data which works correctly on servers.
     * Also checks persistent KO tracking as a fallback for race conditions.
     */
    private fun renderBattleTeam(context: DrawContext, startX: Int, startY: Int, team: List<Pokemon>, isLeftSide: Boolean) {
        // Draw background panel first
        drawTeamPanel(context, startX, startY, team.size)

        // Track panel bounds for input handling
        val panelWidth = team.size * MODEL_SIZE + (team.size - 1) * MODEL_SPACING + PANEL_PADDING_H * 2
        val panelHeight = MODEL_SIZE + PANEL_PADDING_V * 2
        val bounds = TooltipBoundsData(startX - PANEL_PADDING_H, startY - PANEL_PADDING_V, panelWidth, panelHeight)
        if (isLeftSide) leftTeamPanelBounds = bounds else rightTeamPanelBounds = bounds

        var x = startX

        for (pokemon in team) {
            // Use battle-authoritative data, with KO tracking as fallback
            val isKO = pokemon.currentHealth <= 0 || isPokemonKO(pokemon.uuid)
            val status = pokemon.status?.status

            drawPokemonModel(
                context = context,
                x = x,
                y = startY,
                renderablePokemon = pokemon.asRenderablePokemon(),
                speciesIdentifier = null,
                aspects = pokemon.aspects,
                uuid = pokemon.uuid,
                isKO = isKO,
                status = status,
                isLeftSide = isLeftSide
            )

            // Store bounds for hover detection (this is player's own Pokemon)
            pokeballBounds.add(PokeballBounds(x, startY, MODEL_SIZE, MODEL_SIZE, pokemon.uuid, isLeftSide, isPlayerPokemon = true))

            x += MODEL_SIZE + MODEL_SPACING
        }

        // Draw corner overlays AFTER models to ensure rounded corners appear on top
        drawPanelCornerOverlays(context, startX, startY, team.size)
    }

    /**
     * Render a team using tracked battle data (used for opponent team and when spectating).
     * Uses both the tracked Pokemon's isKO field AND the persistent knockedOutPokemon set
     * to handle race conditions where Pokemon is removed from activePokemon before we render.
     */
    private fun renderTrackedTeam(context: DrawContext, startX: Int, startY: Int, team: List<TrackedPokemon>, isLeftSide: Boolean) {
        // Draw background panel first
        drawTeamPanel(context, startX, startY, team.size)

        // Track panel bounds for input handling
        val panelWidth = team.size * MODEL_SIZE + (team.size - 1) * MODEL_SPACING + PANEL_PADDING_H * 2
        val panelHeight = MODEL_SIZE + PANEL_PADDING_V * 2
        val bounds = TooltipBoundsData(startX - PANEL_PADDING_H, startY - PANEL_PADDING_V, panelWidth, panelHeight)
        if (isLeftSide) leftTeamPanelBounds = bounds else rightTeamPanelBounds = bounds

        var x = startX

        for (pokemon in team) {
            // Check both the tracked isKO flag and the persistent KO set
            val isKO = pokemon.isKO || isPokemonKO(pokemon.uuid)

            drawPokemonModel(
                context = context,
                x = x,
                y = startY,
                renderablePokemon = null,
                speciesIdentifier = pokemon.speciesIdentifier,
                aspects = pokemon.aspects,
                uuid = pokemon.uuid,
                isKO = isKO,
                status = pokemon.status,
                isLeftSide = isLeftSide
            )

            // Store bounds for hover detection (opponent or spectated team)
            pokeballBounds.add(PokeballBounds(x, startY, MODEL_SIZE, MODEL_SIZE, pokemon.uuid, isLeftSide, isPlayerPokemon = false))

            x += MODEL_SIZE + MODEL_SPACING
        }

        // Draw corner overlays AFTER models to ensure rounded corners appear on top
        drawPanelCornerOverlays(context, startX, startY, team.size)
    }

    private fun getStatusColor(status: Status): Int {
        return when (status) {
            Statuses.POISON, Statuses.POISON_BADLY -> COLOR_POISON
            Statuses.BURN -> COLOR_BURN
            Statuses.PARALYSIS -> COLOR_PARALYSIS
            Statuses.FROZEN -> COLOR_FREEZE
            Statuses.SLEEP -> COLOR_SLEEP
            else -> COLOR_NORMAL_TOP
        }
    }

    private fun drawPokeball(context: DrawContext, x: Int, y: Int, topColor: Int, bottomColor: Int, bandColor: Int, centerColor: Int) {
        val halfSize = BALL_SIZE / 2
        val centerSize = 4
        val centerOffset = (BALL_SIZE - centerSize) / 2

        // Top half (status/normal color)
        context.fill(x + 1, y, x + BALL_SIZE - 1, y + halfSize, topColor)
        context.fill(x, y + 1, x + BALL_SIZE, y + halfSize, topColor)

        // Bottom half (white/gray)
        context.fill(x + 1, y + halfSize, x + BALL_SIZE - 1, y + BALL_SIZE, bottomColor)
        context.fill(x, y + halfSize, x + BALL_SIZE, y + BALL_SIZE - 1, bottomColor)

        // Center band
        context.fill(x, y + halfSize - 1, x + BALL_SIZE, y + halfSize + 1, bandColor)

        // Center button
        context.fill(x + centerOffset, y + centerOffset, x + centerOffset + centerSize, y + centerOffset + centerSize, centerColor)
        // Button outline
        context.fill(x + centerOffset, y + centerOffset, x + centerOffset + centerSize, y + centerOffset + 1, bandColor)
        context.fill(x + centerOffset, y + centerOffset + centerSize - 1, x + centerOffset + centerSize, y + centerOffset + centerSize, bandColor)
        context.fill(x + centerOffset, y + centerOffset, x + centerOffset + 1, y + centerOffset + centerSize, bandColor)
        context.fill(x + centerOffset + centerSize - 1, y + centerOffset, x + centerOffset + centerSize, y + centerOffset + centerSize, bandColor)
    }

    private data class Quad<T>(val first: T, val second: T, val third: T, val fourth: T)

    /**
     * RGBA color tint for model rendering.
     */
    private data class ColorTint(val r: Float, val g: Float, val b: Float, val a: Float)

    /**
     * Get RGBA tint values for Pokemon model based on status/KO state.
     * KO takes priority over status.
     */
    private fun getModelTint(isKO: Boolean, status: Status?): ColorTint {
        if (isKO) return ColorTint(0.4f, 0.4f, 0.4f, 0.7f)
        return when (status) {
            Statuses.POISON, Statuses.POISON_BADLY -> ColorTint(0.7f, 0.4f, 0.9f, 1f)
            Statuses.BURN -> ColorTint(1f, 0.5f, 0.2f, 1f)
            Statuses.PARALYSIS -> ColorTint(1f, 0.9f, 0.3f, 1f)
            Statuses.FROZEN -> ColorTint(0.4f, 0.8f, 1f, 1f)
            Statuses.SLEEP -> ColorTint(0.6f, 0.6f, 0.7f, 1f)
            else -> ColorTint(1f, 1f, 1f, 1f)
        }
    }

    /**
     * Draw a Pokemon model at the specified position.
     * Falls back to pokeball rendering if model fails to load.
     */
    private fun drawPokemonModel(
        context: DrawContext,
        x: Int,
        y: Int,
        renderablePokemon: RenderablePokemon?,
        speciesIdentifier: Identifier?,
        aspects: Set<String>,
        uuid: UUID,
        isKO: Boolean,
        status: Status?,
        isLeftSide: Boolean
    ) {
        val matrixStack = context.matrices
        val state = getOrCreateFloatingState(uuid)

        // Set aspects on state for proper form rendering
        if (renderablePokemon != null) {
            state.currentAspects = renderablePokemon.aspects
        } else if (aspects.isNotEmpty()) {
            state.currentAspects = aspects
        }

        // Get tint colors
        val tint = getModelTint(isKO, status)

        // Calculate center position for model (centered in bounds)
        val centerX = x + MODEL_SIZE / 2.0
        val centerY = y + MODEL_SIZE / 2.0

        // PC-style rotation with slight tilt, facing towards center
        // Left side Pokemon look RIGHT (towards center): negative Y rotation
        // Right side Pokemon look LEFT (towards center): positive Y rotation
        val yRotation = if (isLeftSide) -35f else 35f
        val rotation = Quaternionf().rotationXYZ(
            Math.toRadians(13.0).toFloat(),   // X tilt (forward lean like PC)
            Math.toRadians(yRotation.toDouble()).toFloat(),  // Y rotation (face center)
            0f
        )

        // Scale to fit in MODEL_SIZE
        val scale = MODEL_SIZE / 3.0f

        matrixStack.push()
        try {
            // Models render downward from translation point, so position near top of bounds
            // to have the model fill the space and appear vertically centered
            val renderY = y + MODEL_SIZE * 0.1  // Position at ~10% from top
            matrixStack.translate(centerX, renderY, 0.0)

            if (renderablePokemon != null) {
                // Player's Pokemon - use full RenderablePokemon
                drawProfilePokemon(
                    renderablePokemon = renderablePokemon,
                    matrixStack = matrixStack,
                    rotation = rotation,
                    poseType = PoseType.PORTRAIT,
                    state = state,
                    partialTicks = 0f,  // Static pose
                    scale = scale,
                    r = tint.r, g = tint.g, b = tint.b, a = tint.a
                )
            } else if (speciesIdentifier != null) {
                // Opponent's Pokemon - use species identifier
                drawProfilePokemon(
                    species = speciesIdentifier,
                    matrixStack = matrixStack,
                    rotation = rotation,
                    poseType = PoseType.PORTRAIT,
                    state = state,
                    partialTicks = 0f,  // Static pose
                    scale = scale,
                    r = tint.r, g = tint.g, b = tint.b, a = tint.a
                )
            } else {
                // No model data - draw fallback pokeball
                matrixStack.pop()
                drawPokeballFallback(context, x, y, isKO, status)
                return
            }
        } catch (e: Exception) {
            // Model rendering failed - draw fallback pokeball
            CobblemonExtendedBattleUI.LOGGER.debug("Failed to render Pokemon model: ${e.message}")
            matrixStack.pop()
            drawPokeballFallback(context, x, y, isKO, status)
            return
        }
        matrixStack.pop()
    }

    /**
     * Draw a pokeball as fallback when model rendering fails.
     * Uses the original pokeball rendering but centered in the larger MODEL_SIZE space.
     */
    private fun drawPokeballFallback(context: DrawContext, x: Int, y: Int, isKO: Boolean, status: Status?) {
        // Center the smaller pokeball in the larger model space
        val offsetX = x + (MODEL_SIZE - BALL_SIZE) / 2
        val offsetY = y + (MODEL_SIZE - BALL_SIZE) / 2

        val colors = when {
            isKO -> Quad(COLOR_KO_TOP, COLOR_KO_BOTTOM, COLOR_KO_BAND, COLOR_KO_CENTER)
            status != null -> {
                val statusColor = getStatusColor(status)
                Quad(statusColor, COLOR_NORMAL_BOTTOM, COLOR_NORMAL_BAND, COLOR_NORMAL_CENTER)
            }
            else -> Quad(COLOR_NORMAL_TOP, COLOR_NORMAL_BOTTOM, COLOR_NORMAL_BAND, COLOR_NORMAL_CENTER)
        }

        drawPokeball(context, offsetX, offsetY, colors.first, colors.second, colors.third, colors.fourth)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tooltip Rendering (called from BattleInfoRenderer after all other UI)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Render tooltip for hovered pokeball.
     * Should be called LAST in render pipeline to appear on top.
     */
    fun renderHoverTooltip(context: DrawContext) {
        val hovered = hoveredPokeball ?: return
        val battle = CobblemonClient.battle ?: return

        // Get tracked pokemon data
        val trackedPokemon = trackedSide1Pokemon[hovered.uuid] ?: trackedSide2Pokemon[hovered.uuid]

        // Get battle pokemon for additional data
        val battlePokemon = getBattlePokemonByUuid(hovered.uuid, battle)

        val tooltipData = getTooltipData(hovered.uuid, trackedPokemon, battlePokemon, hovered.isPlayerPokemon)
        renderTooltip(context, hovered, tooltipData)
    }

    private fun getBattlePokemonByUuid(uuid: UUID, battle: com.cobblemon.mod.common.client.battle.ClientBattle): Pokemon? {
        for (side in listOf(battle.side1, battle.side2)) {
            for (actor in side.actors) {
                actor.pokemon.find { it.uuid == uuid }?.let { return it }
            }
        }
        return null
    }

    private fun getPokemonNameFromUuid(uuid: UUID): String? {
        val battle = CobblemonClient.battle ?: return null
        for (side in listOf(battle.side1, battle.side2)) {
            for (actor in side.actors) {
                for (pokemon in actor.pokemon) {
                    if (pokemon.uuid == uuid) return pokemon.getDisplayName().string
                }
                for (active in actor.activePokemon) {
                    active.battlePokemon?.let {
                        if (it.uuid == uuid) return it.displayName.string
                    }
                }
            }
        }
        return null
    }

    private fun getTooltipData(uuid: UUID, trackedPokemon: TrackedPokemon?, battlePokemon: Pokemon?, isPlayerPokemon: Boolean): TooltipData {
        val name = battlePokemon?.getDisplayName()?.string
            ?: getPokemonNameFromUuid(uuid)
            ?: "Unknown"

        val hpPercent = trackedPokemon?.hpPercent
            ?: battlePokemon?.let {
                if (it.maxHealth > 0) it.currentHealth.toFloat() / it.maxHealth else 0f
            }
            ?: 0f

        // For player's own Pokemon, show ALL moves and item directly from Pokemon data
        // For opponent/spectated Pokemon, only show revealed moves and tracked items
        val moves: Set<String>
        val item: BattleStateTracker.TrackedItem?

        if (isPlayerPokemon && battlePokemon != null) {
            // Player's Pokemon: show all moves
            moves = battlePokemon.moveSet.getMoves()
                .map { it.displayName.string }
                .toSet()
            // Player's Pokemon: show actual held item (if any)
            val heldItem = battlePokemon.heldItem()
            item = if (!heldItem.isEmpty) {
                // Check if we have tracked info about this item being consumed/knocked off
                val trackedItem = BattleStateTracker.getItem(uuid)
                if (trackedItem != null && trackedItem.status != BattleStateTracker.ItemStatus.HELD) {
                    trackedItem  // Use tracked status if item was consumed/knocked off
                } else {
                    BattleStateTracker.TrackedItem(heldItem.name.string, BattleStateTracker.ItemStatus.HELD, BattleStateTracker.currentTurn)
                }
            } else {
                // No item held - check if one was consumed/knocked off
                BattleStateTracker.getItem(uuid)
            }
        } else {
            // Opponent/spectated: only show revealed info
            moves = BattleStateTracker.getRevealedMoves(uuid)
            item = BattleStateTracker.getItem(uuid)
        }

        return TooltipData(
            pokemonName = name,
            hpPercent = hpPercent,
            statusCondition = trackedPokemon?.status ?: battlePokemon?.status?.status,
            isKO = trackedPokemon?.isKO ?: isPokemonKO(uuid),
            revealedMoves = moves,
            item = item,
            statChanges = BattleStateTracker.getStatChanges(uuid),
            volatileStatuses = BattleStateTracker.getVolatileStatuses(uuid)
        )
    }

    private fun renderTooltip(context: DrawContext, bounds: PokeballBounds, data: TooltipData) {
        val mc = MinecraftClient.getInstance()
        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight
        val textRenderer = mc.textRenderer

        // Calculate font scale based on tooltip-specific config
        val fontScale = TOOLTIP_FONT_SCALE * PanelConfig.tooltipFontScale
        val lineHeight = (TOOLTIP_BASE_LINE_HEIGHT * fontScale).toInt().coerceAtLeast(8)

        // Build tooltip lines: (text, color)
        val lines = mutableListOf<Pair<String, Int>>()

        // Pokemon name header
        lines.add(data.pokemonName to TOOLTIP_HEADER)

        // HP percentage
        val hpColor = when {
            data.isKO -> color(100, 100, 100)
            data.hpPercent > 0.5f -> TOOLTIP_HP_HIGH
            data.hpPercent > 0.25f -> TOOLTIP_HP_MED
            else -> TOOLTIP_HP_LOW
        }
        val hpText = if (data.isKO) "HP: KO'd" else "HP: ${(data.hpPercent * 100).toInt()}%"
        lines.add(hpText to hpColor)

        // Status condition
        data.statusCondition?.let { status ->
            val statusName = getStatusDisplayName(status)
            lines.add("Status: $statusName" to getStatusTextColor(status))
        }

        // Revealed moves
        if (data.revealedMoves.isNotEmpty()) {
            lines.add("Moves:" to TOOLTIP_LABEL)
            for (move in data.revealedMoves.take(4)) {
                lines.add("  $move" to TOOLTIP_TEXT)
            }
        }

        // Item
        data.item?.let { item ->
            val itemText = when (item.status) {
                BattleStateTracker.ItemStatus.HELD -> "Item: ${item.name}"
                BattleStateTracker.ItemStatus.KNOCKED_OFF -> "Item: ${item.name} (knocked off)"
                BattleStateTracker.ItemStatus.STOLEN -> "Item: ${item.name} (stolen)"
                BattleStateTracker.ItemStatus.CONSUMED -> "Item: ${item.name} (used)"
            }
            val itemColor = if (item.status == BattleStateTracker.ItemStatus.HELD) TOOLTIP_TEXT else TOOLTIP_LABEL
            lines.add(itemText to itemColor)
        }

        // Stat changes
        if (data.statChanges.isNotEmpty()) {
            val statText = data.statChanges.entries
                .sortedBy { it.key.ordinal }
                .joinToString(" ") { (stat, value) ->
                    val sign = if (value > 0) "+" else ""
                    "${stat.abbr}$sign$value"
                }
            val hasBoosts = data.statChanges.values.any { it > 0 }
            val hasDrops = data.statChanges.values.any { it < 0 }
            val statColor = when {
                hasBoosts && hasDrops -> TOOLTIP_TEXT
                hasBoosts -> TOOLTIP_STAT_BOOST
                else -> TOOLTIP_STAT_DROP
            }
            lines.add("Stats: $statText" to statColor)
        }

        // Volatile statuses
        if (data.volatileStatuses.isNotEmpty()) {
            val volatileText = data.volatileStatuses.take(3).joinToString(", ") { it.type.displayName }
            val suffix = if (data.volatileStatuses.size > 3) "..." else ""
            lines.add("Effects: $volatileText$suffix" to TOOLTIP_LABEL)
        }

        // Calculate dimensions with scaled text
        val maxLineWidth = lines.maxOfOrNull { (textRenderer.getWidth(it.first) * fontScale).toInt() } ?: 80
        val tooltipWidth = maxLineWidth + TOOLTIP_PADDING * 2
        val tooltipHeight = lines.size * lineHeight + TOOLTIP_PADDING * 2

        // Position tooltip below the pokeball (centered)
        var tooltipX = bounds.x + (bounds.width / 2) - (tooltipWidth / 2)
        var tooltipY = bounds.y + bounds.height + 4

        // Clamp to screen bounds
        tooltipX = tooltipX.coerceIn(4, screenWidth - tooltipWidth - 4)
        if (tooltipY + tooltipHeight > screenHeight - 4) {
            // Show above pokeball if not enough space below
            tooltipY = bounds.y - tooltipHeight - 4
        }
        tooltipY = tooltipY.coerceAtLeast(4)

        // Store tooltip bounds for input handling
        tooltipBounds = TooltipBoundsData(tooltipX, tooltipY, tooltipWidth, tooltipHeight)

        // Draw rounded background
        drawTooltipBackground(context, tooltipX, tooltipY, tooltipWidth, tooltipHeight)

        // Draw text lines with scaling
        var lineY = tooltipY + TOOLTIP_PADDING
        for ((text, textColor) in lines) {
            drawScaledText(
                context = context,
                text = Text.literal(text),
                x = (tooltipX + TOOLTIP_PADDING).toFloat(),
                y = lineY.toFloat(),
                colour = textColor,
                scale = fontScale,
                shadow = false
            )
            lineY += lineHeight
        }
    }

    /**
     * Draw a rounded tooltip background with border.
     */
    private fun drawTooltipBackground(context: DrawContext, x: Int, y: Int, width: Int, height: Int) {
        val corner = TOOLTIP_CORNER

        // Draw main background (cross pattern for rounded corners)
        context.fill(x + corner, y, x + width - corner, y + height, TOOLTIP_BG)
        context.fill(x, y + corner, x + width, y + height - corner, TOOLTIP_BG)

        // Fill corners with graduated rounding
        // Top-left corner
        context.fill(x + 2, y + 1, x + corner, y + 2, TOOLTIP_BG)
        context.fill(x + 1, y + 2, x + corner, y + corner, TOOLTIP_BG)
        // Top-right corner
        context.fill(x + width - corner, y + 1, x + width - 2, y + 2, TOOLTIP_BG)
        context.fill(x + width - corner, y + 2, x + width - 1, y + corner, TOOLTIP_BG)
        // Bottom-left corner
        context.fill(x + 2, y + height - 2, x + corner, y + height - 1, TOOLTIP_BG)
        context.fill(x + 1, y + height - corner, x + corner, y + height - 2, TOOLTIP_BG)
        // Bottom-right corner
        context.fill(x + width - corner, y + height - 2, x + width - 2, y + height - 1, TOOLTIP_BG)
        context.fill(x + width - corner, y + height - corner, x + width - 1, y + height - 2, TOOLTIP_BG)

        // Draw border - straight edges
        context.fill(x + corner, y, x + width - corner, y + 1, TOOLTIP_BORDER)
        context.fill(x + corner, y + height - 1, x + width - corner, y + height, TOOLTIP_BORDER)
        context.fill(x, y + corner, x + 1, y + height - corner, TOOLTIP_BORDER)
        context.fill(x + width - 1, y + corner, x + width, y + height - corner, TOOLTIP_BORDER)

        // Draw rounded corner borders
        // Top-left
        context.fill(x + 2, y, x + corner, y + 1, TOOLTIP_BORDER)
        context.fill(x + 1, y + 1, x + 2, y + 2, TOOLTIP_BORDER)
        context.fill(x, y + 2, x + 1, y + corner, TOOLTIP_BORDER)
        // Top-right
        context.fill(x + width - corner, y, x + width - 2, y + 1, TOOLTIP_BORDER)
        context.fill(x + width - 2, y + 1, x + width - 1, y + 2, TOOLTIP_BORDER)
        context.fill(x + width - 1, y + 2, x + width, y + corner, TOOLTIP_BORDER)
        // Bottom-left
        context.fill(x + 2, y + height - 1, x + corner, y + height, TOOLTIP_BORDER)
        context.fill(x + 1, y + height - 2, x + 2, y + height - 1, TOOLTIP_BORDER)
        context.fill(x, y + height - corner, x + 1, y + height - 2, TOOLTIP_BORDER)
        // Bottom-right
        context.fill(x + width - corner, y + height - 1, x + width - 2, y + height, TOOLTIP_BORDER)
        context.fill(x + width - 2, y + height - 2, x + width - 1, y + height - 1, TOOLTIP_BORDER)
        context.fill(x + width - 1, y + height - corner, x + width, y + height - 2, TOOLTIP_BORDER)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tooltip Input Handling
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle font size keybinds ([ and ] keys).
     */
    private fun handleFontKeybinds(handle: Long) {
        val increaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.increaseFontKey.boundKeyTranslationKey)
        val isIncreaseDown = isKeyOrButtonPressed(handle, increaseKey)
        if (isIncreaseDown && !wasIncreaseFontKeyPressed) {
            PanelConfig.adjustTooltipFontScale(PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasIncreaseFontKeyPressed = isIncreaseDown

        val decreaseKey = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.decreaseFontKey.boundKeyTranslationKey)
        val isDecreaseDown = isKeyOrButtonPressed(handle, decreaseKey)
        if (isDecreaseDown && !wasDecreaseFontKeyPressed) {
            PanelConfig.adjustTooltipFontScale(-PanelConfig.FONT_SCALE_STEP)
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
     * Handle scroll event for font scaling when Ctrl is held.
     * Returns true if the event was consumed.
     */
    fun handleScroll(deltaY: Double): Boolean {
        if (!shouldHandleFontInput()) return false

        val mc = MinecraftClient.getInstance()
        val handle = mc.window.handle
        val isCtrlDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                         GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS

        if (isCtrlDown) {
            val delta = if (deltaY > 0) PanelConfig.FONT_SCALE_STEP else -PanelConfig.FONT_SCALE_STEP
            PanelConfig.adjustTooltipFontScale(delta)
            PanelConfig.save()
            return true
        }
        return false
    }

    private fun getStatusDisplayName(status: Status): String {
        return when (status) {
            Statuses.POISON -> "Poisoned"
            Statuses.POISON_BADLY -> "Badly Poisoned"
            Statuses.BURN -> "Burned"
            Statuses.PARALYSIS -> "Paralyzed"
            Statuses.FROZEN -> "Frozen"
            Statuses.SLEEP -> "Asleep"
            else -> status.name.path.replaceFirstChar { it.uppercase() }
        }
    }

    private fun getStatusTextColor(status: Status): Int {
        return when (status) {
            Statuses.POISON, Statuses.POISON_BADLY -> color(160, 90, 200)
            Statuses.BURN -> color(255, 140, 50)
            Statuses.PARALYSIS -> color(255, 220, 50)
            Statuses.FROZEN -> color(100, 200, 255)
            Statuses.SLEEP -> color(150, 150, 170)
            else -> TOOLTIP_TEXT
        }
    }
}

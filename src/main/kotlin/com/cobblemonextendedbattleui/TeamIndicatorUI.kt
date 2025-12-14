package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.api.pokemon.status.Statuses
import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Displays pokeball indicators for each team's Pokemon.
 * Shows status conditions and KO'd Pokemon at a glance.
 *
 * Supports both participating in battles and spectating:
 * - When in battle: Uses client party storage for player's team, tracks opponent via battle data
 * - When spectating: Uses battle data to track both sides as Pokemon are revealed
 */
object TeamIndicatorUI {

    // Match Cobblemon's exact positioning constants from BattleOverlay.kt
    private const val HORIZONTAL_INSET = 12
    private const val VERTICAL_INSET = 10

    // Cobblemon tile dimensions (from BattleOverlay companion object)
    private const val TILE_HEIGHT = 40
    private const val COMPACT_TILE_HEIGHT = 28

    // Pokeball indicator settings
    private const val BALL_SIZE = 10
    private const val BALL_SPACING = 3
    private const val BALL_OFFSET_Y = 4  // Gap below the last tile

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
        var isKO: Boolean
    )

    // Track Pokemon for both sides separately (for spectating support)
    private val trackedSide1Pokemon = ConcurrentHashMap<UUID, TrackedPokemon>()
    private val trackedSide2Pokemon = ConcurrentHashMap<UUID, TrackedPokemon>()

    // Also track by UUID for cross-referencing with player party
    private val allTrackedPokemon = ConcurrentHashMap<UUID, TrackedPokemon>()

    private var lastBattleId: UUID? = null

    /**
     * Clear tracking when battle ends.
     */
    fun clear() {
        trackedSide1Pokemon.clear()
        trackedSide2Pokemon.clear()
        allTrackedPokemon.clear()
        lastBattleId = null
    }

    fun render(context: DrawContext) {
        val battle = CobblemonClient.battle ?: return
        if (battle.minimised) return

        // Clear tracking if this is a new battle
        if (lastBattleId != battle.battleId) {
            clear()
            lastBattleId = battle.battleId
        }

        val mc = MinecraftClient.getInstance()
        val screenWidth = mc.window.scaledWidth
        val player = mc.player ?: return
        val playerUUID = player.uuid

        // Determine if player is in the battle and which side they're on
        val playerInSide1 = battle.side1.actors.any { it.uuid == playerUUID }
        val playerInSide2 = battle.side2.actors.any { it.uuid == playerUUID }
        val isSpectating = !playerInSide1 && !playerInSide2

        // Determine left/right sides based on player position or default for spectating
        val leftSide: ClientBattleSide
        val rightSide: ClientBattleSide
        val leftTracked: ConcurrentHashMap<UUID, TrackedPokemon>
        val rightTracked: ConcurrentHashMap<UUID, TrackedPokemon>

        if (playerInSide1) {
            leftSide = battle.side1
            rightSide = battle.side2
            leftTracked = trackedSide1Pokemon
            rightTracked = trackedSide2Pokemon
        } else {
            // Player is on side2, or spectating (default to side1 on left)
            leftSide = if (isSpectating) battle.side1 else battle.side2
            rightSide = if (isSpectating) battle.side2 else battle.side1
            leftTracked = if (isSpectating) trackedSide1Pokemon else trackedSide2Pokemon
            rightTracked = if (isSpectating) trackedSide2Pokemon else trackedSide1Pokemon
        }

        // Update tracked Pokemon for both sides from battle data
        updateTrackedPokemonForSide(leftSide, leftTracked)
        updateTrackedPokemonForSide(rightSide, rightTracked)

        // Count active Pokemon for positioning (determines how many tiles are shown)
        val leftActiveCount = leftSide.actors.sumOf { it.activePokemon.size }
        val rightActiveCount = rightSide.actors.sumOf { it.activePokemon.size }

        val leftY = calculatePokeballY(leftActiveCount)
        val rightY = calculatePokeballY(rightActiveCount)

        // Render left side (player's side when in battle, side1 when spectating)
        if (!isSpectating) {
            // Player is in battle - use client storage for full party view,
            // but cross-reference with battle data for accurate HP/status
            val playerParty = CobblemonClient.storage.party
            val playerTeam = playerParty.slots.filterNotNull()
            renderPlayerTeamWithBattleData(context, HORIZONTAL_INSET, leftY, playerTeam)
        } else {
            // Spectating - use tracked Pokemon from battle data
            val leftTeam = leftTracked.values.toList()
            if (leftTeam.isNotEmpty()) {
                renderTrackedTeam(context, HORIZONTAL_INSET, leftY, leftTeam)
            }
        }

        // Render right side (always use tracked data from battle)
        val rightTeam = rightTracked.values.toList()
        if (rightTeam.isNotEmpty()) {
            val rightWidth = rightTeam.size * (BALL_SIZE + BALL_SPACING) - BALL_SPACING
            renderTrackedTeam(context, screenWidth - HORIZONTAL_INSET - rightWidth, rightY, rightTeam)
        }
    }

    /**
     * Update tracked Pokemon for a battle side.
     */
    private fun updateTrackedPokemonForSide(side: ClientBattleSide, tracked: ConcurrentHashMap<UUID, TrackedPokemon>) {
        for (actor in side.actors) {
            for (activePokemon in actor.activePokemon) {
                val battlePokemon = activePokemon.battlePokemon ?: continue
                updateTrackedPokemonInMap(battlePokemon, tracked)
                // Also update the global map for cross-referencing
                updateTrackedPokemonInMap(battlePokemon, allTrackedPokemon)
            }
        }
    }

    private fun calculatePokeballY(activeCount: Int): Int {
        if (activeCount <= 0) return VERTICAL_INSET + TILE_HEIGHT + BALL_OFFSET_Y

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

        return bottomOfTiles + BALL_OFFSET_Y
    }

    /**
     * Update tracked Pokemon in the specified map.
     */
    private fun updateTrackedPokemonInMap(battlePokemon: ClientBattlePokemon, targetMap: ConcurrentHashMap<UUID, TrackedPokemon>) {
        val uuid = battlePokemon.uuid
        val hpPercent = if (battlePokemon.maxHp > 0) battlePokemon.hpValue / battlePokemon.maxHp else 0f
        val isKO = battlePokemon.hpValue <= 0
        val status = battlePokemon.status

        targetMap.compute(uuid) { _, existing ->
            if (existing != null) {
                // Update existing
                existing.hpPercent = hpPercent
                existing.status = status
                existing.isKO = isKO
                existing
            } else {
                // New Pokemon revealed
                TrackedPokemon(uuid, hpPercent, status, isKO)
            }
        }
    }

    /**
     * Render player team using client party data, but cross-reference with battle data
     * for more accurate HP/status information during battle.
     */
    private fun renderPlayerTeamWithBattleData(context: DrawContext, startX: Int, startY: Int, team: List<Pokemon>) {
        var x = startX

        for (pokemon in team) {
            // Try to get battle data for this Pokemon for more accurate HP/status
            val battleData = allTrackedPokemon[pokemon.uuid]

            val isKO: Boolean
            val status: Status?

            if (battleData != null) {
                // Use battle data (more accurate during battle)
                isKO = battleData.isKO
                status = battleData.status
            } else {
                // Fall back to party data (Pokemon hasn't been in battle yet, or no battle data)
                isKO = pokemon.currentHealth <= 0
                status = pokemon.status?.status
            }

            val (topColor, bottomColor, bandColor, centerColor) = when {
                isKO -> Quad(COLOR_KO_TOP, COLOR_KO_BOTTOM, COLOR_KO_BAND, COLOR_KO_CENTER)
                status != null -> {
                    val statusColor = getStatusColor(status)
                    Quad(statusColor, COLOR_NORMAL_BOTTOM, COLOR_NORMAL_BAND, COLOR_NORMAL_CENTER)
                }
                else -> Quad(COLOR_NORMAL_TOP, COLOR_NORMAL_BOTTOM, COLOR_NORMAL_BAND, COLOR_NORMAL_CENTER)
            }

            drawPokeball(context, x, startY, topColor, bottomColor, bandColor, centerColor)
            x += BALL_SIZE + BALL_SPACING
        }
    }

    /**
     * Render a team using tracked battle data (used for opponent team and when spectating).
     */
    private fun renderTrackedTeam(context: DrawContext, startX: Int, startY: Int, team: List<TrackedPokemon>) {
        var x = startX

        for (pokemon in team) {
            val (topColor, bottomColor, bandColor, centerColor) = when {
                pokemon.isKO -> Quad(COLOR_KO_TOP, COLOR_KO_BOTTOM, COLOR_KO_BAND, COLOR_KO_CENTER)
                pokemon.status != null -> {
                    val statusColor = getStatusColor(pokemon.status!!)
                    Quad(statusColor, COLOR_NORMAL_BOTTOM, COLOR_NORMAL_BAND, COLOR_NORMAL_CENTER)
                }
                else -> Quad(COLOR_NORMAL_TOP, COLOR_NORMAL_BOTTOM, COLOR_NORMAL_BAND, COLOR_NORMAL_CENTER)
            }

            drawPokeball(context, x, startY, topColor, bottomColor, bandColor, centerColor)
            x += BALL_SIZE + BALL_SPACING
        }
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
}

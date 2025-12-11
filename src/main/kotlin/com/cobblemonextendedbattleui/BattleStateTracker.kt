package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks weather, terrain, field conditions, side conditions, and stat changes.
 */
object BattleStateTracker {

    private var lastBattleId: UUID? = null

    var currentTurn: Int = 0
        private set

    fun checkBattleChanged(battleId: UUID) {
        if (lastBattleId != battleId) {
            clear()
            lastBattleId = battleId
        }
    }

    enum class Weather(val displayName: String, val icon: String) {
        RAIN("Rain", "🌧"),
        SUN("Harsh Sunlight", "☀"),
        SANDSTORM("Sandstorm", "🏜"),
        HAIL("Hail", "🌨"),
        SNOW("Snow", "❄")
    }

    data class WeatherState(
        val type: Weather,
        val startTurn: Int,
        var confirmedExtended: Boolean = false  // True if we know they have weather rock
    )

    var weather: WeatherState? = null
        private set

    enum class Terrain(val displayName: String, val icon: String) {
        ELECTRIC("Electric Terrain", "⚡"),
        GRASSY("Grassy Terrain", "🌿"),
        MISTY("Misty Terrain", "🌫"),
        PSYCHIC("Psychic Terrain", "🔮")
    }

    data class TerrainState(
        val type: Terrain,
        val startTurn: Int,
        var confirmedExtended: Boolean = false  // True if we know they have Terrain Extender
    )

    var terrain: TerrainState? = null
        private set

    enum class FieldCondition(val displayName: String, val icon: String, val baseDuration: Int) {
        TRICK_ROOM("Trick Room", "🔄", 5),
        GRAVITY("Gravity", "⬇", 5),
        MAGIC_ROOM("Magic Room", "✨", 5),
        WONDER_ROOM("Wonder Room", "🔀", 5)
    }

    data class FieldConditionState(
        val type: FieldCondition,
        val startTurn: Int
    )

    private val fieldConditions = ConcurrentHashMap<FieldCondition, FieldConditionState>()

    enum class SideCondition(val displayName: String, val icon: String, val baseDuration: Int?, val maxStacks: Int = 1) {
        REFLECT("Reflect", "🛡", 5),
        LIGHT_SCREEN("Light Screen", "💡", 5),
        AURORA_VEIL("Aurora Veil", "🌈", 5),
        TAILWIND("Tailwind", "💨", 4),
        SAFEGUARD("Safeguard", "🔰", 5),
        LUCKY_CHANT("Lucky Chant", "🍀", 5),
        MIST("Mist", "🌁", 5),
        STEALTH_ROCK("Stealth Rock", "🪨", null),
        SPIKES("Spikes", "📌", null, 3),
        TOXIC_SPIKES("Toxic Spikes", "☠", null, 2),
        STICKY_WEB("Sticky Web", "🕸", null)
    }

    /**
     * Volatile statuses are per-Pokemon conditions that clear on switch.
     * Based on official Pokemon mechanics from Bulbapedia.
     * @see https://bulbapedia.bulbagarden.net/wiki/Status_condition#Volatile_status
     *
     * @param baseDuration Number of turns the effect lasts (null = indefinite/until cured)
     * @param countsDown If true, displays as countdown (3, 2, 1). If false, displays elapsed turns.
     */
    enum class VolatileStatus(
        val displayName: String,
        val icon: String,
        val isNegative: Boolean = true,
        val baseDuration: Int? = null,
        val countsDown: Boolean = false
    ) {
        // Seeding/Draining effects
        LEECH_SEED("Leech Seed", "🌱"),

        // Mental/Behavioral effects
        CONFUSION("Confusion", "💫", baseDuration = 4),  // 2-5 turns, use 4 as average
        TAUNT("Taunt", "😤", baseDuration = 3),
        ENCORE("Encore", "🔁", baseDuration = 3),
        DISABLE("Disable", "🚫", baseDuration = 4),
        TORMENT("Torment", "😈"),
        INFATUATION("Infatuation", "💕"),

        // Countdown effects
        PERISH_SONG("Perish Song", "💀", baseDuration = 4, countsDown = true),  // 3, 2, 1, faint
        DROWSY("Drowsy", "😴", baseDuration = 1),  // Yawn - will sleep next turn

        // Damage over time
        CURSE("Curse", "👻"),  // Ghost-type curse - indefinite
        NIGHTMARE("Nightmare", "😱"),
        BOUND("Bound", "⛓", baseDuration = 5),  // Wrap, Bind, Fire Spin, etc. - 4-5 turns

        // Movement restriction
        TRAPPED("Trapped", "🚷"),  // Mean Look, Block, Spider Web - indefinite

        // Protection/Healing (positive for the affected Pokemon)
        SUBSTITUTE("Substitute", "🎭", isNegative = false),
        AQUA_RING("Aqua Ring", "💧", isNegative = false),
        INGRAIN("Ingrain", "🌳", isNegative = false),
        FOCUS_ENERGY("Focus Energy", "🎯", isNegative = false),
        MAGNET_RISE("Magnet Rise", "🧲", isNegative = false, baseDuration = 5),

        // Prevention effects
        EMBARGO("Embargo", "📦", baseDuration = 5),
        HEAL_BLOCK("Heal Block", "💔", baseDuration = 5),

        // Other
        DESTINY_BOND("Destiny Bond", "🔗", isNegative = false),
        FLINCH("Flinch", "💥")
    }

    data class VolatileStatusState(
        val type: VolatileStatus,
        val startTurn: Int
    )

    data class SideConditionState(
        val type: SideCondition,
        val startTurn: Int,
        var stacks: Int = 1,
        var confirmedExtended: Boolean = false
    )

    private val playerSideConditions = ConcurrentHashMap<SideCondition, SideConditionState>()
    private val opponentSideConditions = ConcurrentHashMap<SideCondition, SideConditionState>()
    private val statChanges = ConcurrentHashMap<UUID, MutableMap<Stat, Int>>()
    private val volatileStatuses = ConcurrentHashMap<UUID, MutableSet<VolatileStatusState>>()
    private val nameToUuid = ConcurrentHashMap<String, UUID>()
    private val uuidIsAlly = ConcurrentHashMap<UUID, Boolean>()

    fun clear() {
        lastBattleId = null
        currentTurn = 0
        weather = null
        terrain = null
        fieldConditions.clear()
        playerSideConditions.clear()
        opponentSideConditions.clear()
        statChanges.clear()
        volatileStatuses.clear()
        nameToUuid.clear()
        uuidIsAlly.clear()
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared all state")
    }

    fun setTurn(turn: Int) {
        currentTurn = turn
        checkForExpiredConditions()
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Turn $turn")
    }

    fun setWeather(type: Weather) {
        val effectiveStartTurn = maxOf(1, currentTurn)
        weather = WeatherState(type, effectiveStartTurn)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Weather set to ${type.displayName} on turn $effectiveStartTurn")
    }

    fun clearWeather() {
        val prev = weather
        weather = null
        if (prev != null) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Weather ${prev.type.displayName} ended on turn $currentTurn")
        }
    }

    fun getWeatherTurnsRemaining(): String? {
        val w = weather ?: return null
        val turnsElapsed = currentTurn - w.startTurn
        val minRemaining = 5 - turnsElapsed
        val maxRemaining = 8 - turnsElapsed

        return when {
            w.confirmedExtended -> maxRemaining.coerceAtLeast(1).toString()
            minRemaining <= 0 -> {
                // Still active past turn 5, so must be extended
                w.confirmedExtended = true
                maxRemaining.coerceAtLeast(1).toString()
            }
            minRemaining == maxRemaining -> minRemaining.toString()
            else -> "$minRemaining-$maxRemaining"
        }
    }

    fun setTerrain(type: Terrain) {
        val effectiveStartTurn = maxOf(1, currentTurn)
        terrain = TerrainState(type, effectiveStartTurn)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Terrain set to ${type.displayName} on turn $effectiveStartTurn")
    }

    fun clearTerrain() {
        val prev = terrain
        terrain = null
        if (prev != null) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Terrain ${prev.type.displayName} ended on turn $currentTurn")
        }
    }

    fun getTerrainTurnsRemaining(): String? {
        val t = terrain ?: return null
        val turnsElapsed = currentTurn - t.startTurn
        val minRemaining = 5 - turnsElapsed
        val maxRemaining = 8 - turnsElapsed

        return when {
            t.confirmedExtended -> maxRemaining.coerceAtLeast(1).toString()
            minRemaining <= 0 -> {
                t.confirmedExtended = true
                maxRemaining.coerceAtLeast(1).toString()
            }
            minRemaining == maxRemaining -> minRemaining.toString()
            else -> "$minRemaining-$maxRemaining"
        }
    }

    fun setFieldCondition(type: FieldCondition) {
        val effectiveStartTurn = maxOf(1, currentTurn)
        fieldConditions[type] = FieldConditionState(type, effectiveStartTurn)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Field condition ${type.displayName} started on turn $effectiveStartTurn")
    }

    fun clearFieldCondition(type: FieldCondition) {
        fieldConditions.remove(type)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Field condition ${type.displayName} ended")
    }

    fun getFieldConditions(): Map<FieldCondition, FieldConditionState> = fieldConditions.toMap()

    fun getFieldConditionTurnsRemaining(type: FieldCondition): String? {
        val fc = fieldConditions[type] ?: return null
        val turnsElapsed = currentTurn - fc.startTurn
        val remaining = fc.type.baseDuration - turnsElapsed
        return remaining.coerceAtLeast(1).toString()
    }

    fun setSideCondition(isPlayerSide: Boolean, type: SideCondition) {
        val conditions = if (isPlayerSide) playerSideConditions else opponentSideConditions
        val existing = conditions[type]

        val effectiveStartTurn = maxOf(1, currentTurn)
        if (existing != null && type.maxStacks > 1) {
            // Stack the condition (e.g., multiple layers of Spikes)
            existing.stacks = (existing.stacks + 1).coerceAtMost(type.maxStacks)
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: ${if (isPlayerSide) "Player" else "Opponent"} ${type.displayName} stacked to ${existing.stacks}")
        } else {
            conditions[type] = SideConditionState(type, effectiveStartTurn)
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: ${if (isPlayerSide) "Player" else "Opponent"} ${type.displayName} started on turn $effectiveStartTurn")
        }
    }

    fun clearSideCondition(isPlayerSide: Boolean, type: SideCondition) {
        val conditions = if (isPlayerSide) playerSideConditions else opponentSideConditions
        conditions.remove(type)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: ${if (isPlayerSide) "Player" else "Opponent"} ${type.displayName} ended")
    }

    fun getPlayerSideConditions(): Map<SideCondition, SideConditionState> = playerSideConditions.toMap()
    fun getOpponentSideConditions(): Map<SideCondition, SideConditionState> = opponentSideConditions.toMap()

    fun getSideConditionTurnsRemaining(isPlayerSide: Boolean, type: SideCondition): String? {
        val conditions = if (isPlayerSide) playerSideConditions else opponentSideConditions
        val sc = conditions[type] ?: return null

        // Hazards don't have duration
        if (sc.type.baseDuration == null) return null

        val turnsElapsed = currentTurn - sc.startTurn

        // Screens can be extended by Light Clay (5 -> 8)
        if (type in listOf(SideCondition.REFLECT, SideCondition.LIGHT_SCREEN, SideCondition.AURORA_VEIL)) {
            val minRemaining = 5 - turnsElapsed
            val maxRemaining = 8 - turnsElapsed

            return when {
                sc.confirmedExtended -> maxRemaining.coerceAtLeast(1).toString()
                minRemaining <= 0 -> {
                    sc.confirmedExtended = true
                    maxRemaining.coerceAtLeast(1).toString()
                }
                minRemaining == maxRemaining -> minRemaining.toString()
                else -> "$minRemaining-$maxRemaining"
            }
        }

        // Other timed conditions have fixed duration
        val remaining = sc.type.baseDuration - turnsElapsed
        return remaining.coerceAtLeast(1).toString()
    }

    fun registerPokemon(uuid: UUID, name: String, isAlly: Boolean) {
        nameToUuid[name.lowercase()] = uuid
        uuidIsAlly[uuid] = isAlly
        statChanges.computeIfAbsent(uuid) { ConcurrentHashMap() }
        volatileStatuses.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }
    }

    fun isPokemonAlly(uuid: UUID): Boolean = uuidIsAlly[uuid] ?: false

    fun applyStatChange(pokemonName: String, statName: String, stages: Int) {
        val uuid = resolvePokemonUuid(pokemonName) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName'")
            return
        }

        val stat = getStatFromName(statName) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown stat '$statName'")
            return
        }

        val pokemonStats = statChanges.computeIfAbsent(uuid) { ConcurrentHashMap() }
        val currentStage = pokemonStats.getOrDefault(stat, 0)
        val newStage = (currentStage + stages).coerceIn(-6, 6)
        pokemonStats[stat] = newStage

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName $statName ${if (stages > 0) "+" else ""}$stages (now at $newStage)")
    }

    fun clearPokemonStats(uuid: UUID) {
        statChanges[uuid]?.clear()
        volatileStatuses[uuid]?.clear()  // Volatiles also clear on switch
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared stats and volatiles for UUID $uuid")
    }

    fun clearPokemonStatsByName(pokemonName: String) {
        val uuid = resolvePokemonUuid(pokemonName)
        if (uuid != null) {
            clearPokemonStats(uuid)
        } else {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Could not find UUID for '$pokemonName' to clear stats")
        }
    }

    fun getStatChanges(uuid: UUID): Map<Stat, Int> = statChanges[uuid] ?: emptyMap()

    // Volatile status management
    fun setVolatileStatus(pokemonName: String, type: VolatileStatus) {
        val uuid = resolvePokemonUuid(pokemonName) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for volatile status")
            return
        }

        val effectiveStartTurn = maxOf(1, currentTurn)
        val statuses = volatileStatuses.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }

        // Remove any existing status of the same type before adding (in case of refresh)
        statuses.removeIf { it.type == type }
        statuses.add(VolatileStatusState(type, effectiveStartTurn))

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName gained volatile ${type.displayName}")
    }

    fun clearVolatileStatus(pokemonName: String, type: VolatileStatus) {
        val uuid = resolvePokemonUuid(pokemonName) ?: return
        volatileStatuses[uuid]?.removeIf { it.type == type }
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName lost volatile ${type.displayName}")
    }

    fun clearPokemonVolatiles(uuid: UUID) {
        volatileStatuses[uuid]?.clear()
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared volatiles for UUID $uuid")
    }

    fun clearPokemonVolatilesByName(pokemonName: String) {
        val uuid = resolvePokemonUuid(pokemonName) ?: return
        clearPokemonVolatiles(uuid)
    }

    fun getVolatileStatuses(uuid: UUID): Set<VolatileStatusState> =
        volatileStatuses[uuid]?.toSet() ?: emptySet()

    /**
     * Get the turns remaining for a volatile status, or null if it has no duration.
     * For countdown effects (like Perish Song), returns the countdown value (3, 2, 1).
     * For other timed effects, returns turns remaining.
     */
    fun getVolatileTurnsRemaining(state: VolatileStatusState): String? {
        val duration = state.type.baseDuration ?: return null
        val turnsElapsed = currentTurn - state.startTurn

        return if (state.type.countsDown) {
            // Countdown display (e.g., Perish Song: 3, 2, 1)
            val countdown = (duration - 1) - turnsElapsed  // -1 because it starts at 3, not 4
            countdown.coerceAtLeast(0).toString()
        } else {
            // Turns remaining display
            val remaining = duration - turnsElapsed
            remaining.coerceAtLeast(1).toString()
        }
    }

    /**
     * Apply Perish Song to ALL registered Pokemon.
     * Called when "cobblemon.battle.fieldactivate.perishsong" is detected.
     */
    fun applyPerishSongToAll() {
        val effectiveStartTurn = maxOf(1, currentTurn)
        var count = 0

        // nameToUuid maps name -> UUID, so iterate over its values
        for ((_, uuid) in nameToUuid) {
            val statuses = volatileStatuses.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }

            // Only add if not already affected
            if (statuses.none { it.type == VolatileStatus.PERISH_SONG }) {
                statuses.add(VolatileStatusState(VolatileStatus.PERISH_SONG, effectiveStartTurn))
                count++
            }
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Perish Song applied to $count Pokemon")
    }

    private fun resolvePokemonUuid(pokemonName: String): UUID? {
        // Try direct lookup first
        var uuid = nameToUuid[pokemonName.lowercase()]

        // If not found, try stripping possessive prefix (e.g., "Player126's Tyranitar" -> "Tyranitar")
        if (uuid == null && pokemonName.contains("'s ")) {
            val strippedName = pokemonName.substringAfter("'s ").lowercase()
            uuid = nameToUuid[strippedName]
        }

        return uuid
    }

    private fun checkForExpiredConditions() {
        weather?.let { w ->
            val turnsElapsed = currentTurn - w.startTurn
            if (turnsElapsed >= 5 && !w.confirmedExtended) {
                w.confirmedExtended = true
                CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Weather confirmed extended (still active after 5 turns)")
            }
        }

        terrain?.let { t ->
            val turnsElapsed = currentTurn - t.startTurn
            if (turnsElapsed >= 5 && !t.confirmedExtended) {
                t.confirmedExtended = true
                CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Terrain confirmed extended (still active after 5 turns)")
            }
        }

        listOf(playerSideConditions, opponentSideConditions).forEach { conditions ->
            conditions.values.forEach { sc ->
                if (sc.type in listOf(SideCondition.REFLECT, SideCondition.LIGHT_SCREEN, SideCondition.AURORA_VEIL)) {
                    val turnsElapsed = currentTurn - sc.startTurn
                    if (turnsElapsed >= 5 && !sc.confirmedExtended) {
                        sc.confirmedExtended = true
                    }
                }
            }
        }
    }

    private fun getStatFromName(name: String): Stat? {
        val normalized = name.lowercase().trim()
        return when {
            normalized.contains("attack") && (normalized.contains("sp") || normalized.contains("special")) -> Stats.SPECIAL_ATTACK
            normalized.contains("attack") -> Stats.ATTACK
            normalized.contains("defense") && (normalized.contains("sp") || normalized.contains("special")) -> Stats.SPECIAL_DEFENCE
            normalized.contains("defence") && (normalized.contains("sp") || normalized.contains("special")) -> Stats.SPECIAL_DEFENCE
            normalized.contains("defense") -> Stats.DEFENCE
            normalized.contains("defence") -> Stats.DEFENCE
            normalized.contains("speed") -> Stats.SPEED
            normalized.contains("evasion") || normalized.contains("evasiveness") -> Stats.EVASION
            normalized.contains("accuracy") -> Stats.ACCURACY
            else -> null
        }
    }
}

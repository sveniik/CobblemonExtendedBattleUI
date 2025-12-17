package com.cobblemonextendedbattleui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks weather, terrain, field conditions, side conditions, stat changes, and volatile statuses.
 */
object BattleStateTracker {

    // ═══════════════════════════════════════════════════════════════════════════
    // Stat Change Tracking
    // ═══════════════════════════════════════════════════════════════════════════

    enum class BattleStat(val displayName: String, val abbr: String) {
        ATTACK("Attack", "Atk"),
        DEFENSE("Defense", "Def"),
        SPECIAL_ATTACK("Sp. Atk", "SpA"),
        SPECIAL_DEFENSE("Sp. Def", "SpD"),
        SPEED("Speed", "Spe"),
        ACCURACY("Accuracy", "Acc"),
        EVASION("Evasion", "Eva")
    }

    // Maps UUID -> Map<BattleStat, Int> for stat stages (-6 to +6)
    private val statChanges = ConcurrentHashMap<UUID, ConcurrentHashMap<BattleStat, Int>>()

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
     * Per-Pokemon conditions that clear on switch.
     * baseDuration = turns until expiry (null = indefinite), countsDown = display as countdown.
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
    private val volatileStatuses = ConcurrentHashMap<UUID, MutableSet<VolatileStatusState>>()
    // Maps lowercase name -> list of (UUID, isAlly) pairs to handle mirror matches
    private val nameToUuids = ConcurrentHashMap<String, MutableList<Pair<UUID, Boolean>>>()
    private val uuidIsAlly = ConcurrentHashMap<UUID, Boolean>()
    // Track player names for each side to disambiguate owner prefixes in messages (for volatiles)
    private var allyPlayerName: String? = null
    private var opponentPlayerName: String? = null

    // ═══════════════════════════════════════════════════════════════════════════
    // Baton Pass Support
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Data class to hold stats and volatiles that transfer via Baton Pass.
     */
    data class BatonPassData(
        val stats: Map<BattleStat, Int>,
        val volatiles: Set<VolatileStatusState>
    )

    /**
     * Volatiles that transfer via Baton Pass (based on official Pokemon mechanics).
     * Most move-specific effects and negative conditions don't transfer.
     */
    private val BATON_PASS_VOLATILES = setOf(
        VolatileStatus.SUBSTITUTE,
        VolatileStatus.FOCUS_ENERGY,
        VolatileStatus.INGRAIN,
        VolatileStatus.AQUA_RING,
        VolatileStatus.MAGNET_RISE,
        VolatileStatus.CONFUSION,  // Confusion does transfer
        VolatileStatus.EMBARGO,
        VolatileStatus.HEAL_BLOCK
    )

    // Pending Baton Pass data by side (true = ally, false = opponent)
    private val pendingBatonPass = ConcurrentHashMap<Boolean, BatonPassData>()

    // Track which Pokemon used Baton Pass (by UUID) - cleared on switch
    private val batonPassUsers = ConcurrentHashMap.newKeySet<UUID>()

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
        nameToUuids.clear()
        uuidIsAlly.clear()
        allyPlayerName = null
        opponentPlayerName = null
        pendingBatonPass.clear()
        batonPassUsers.clear()
        BattleMessageInterceptor.clearMoveTracking()
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared all state")
    }

    /**
     * Set the player names for each side. Used to disambiguate Pokemon ownership
     * when messages include owner prefixes like "Player123's Togekiss".
     */
    fun setPlayerNames(allyName: String, opponentName: String) {
        allyPlayerName = allyName.lowercase()
        opponentPlayerName = opponentName.lowercase()
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Player names set - Ally: $allyName, Opponent: $opponentName")
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
        val lowerName = name.lowercase()
        val uuidList = nameToUuids.computeIfAbsent(lowerName) { mutableListOf() }

        // Check if this UUID is already registered under this name
        val existingEntry = uuidList.find { it.first == uuid }
        if (existingEntry == null) {
            uuidList.add(Pair(uuid, isAlly))
        } else if (existingEntry.second != isAlly) {
            // Update ally status if it changed (shouldn't happen, but be safe)
            uuidList.remove(existingEntry)
            uuidList.add(Pair(uuid, isAlly))
        }

        uuidIsAlly[uuid] = isAlly
        volatileStatuses.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }
    }

    fun isPokemonAlly(uuid: UUID): Boolean = uuidIsAlly[uuid] ?: false

    // ═══════════════════════════════════════════════════════════════════════════
    // Stat Change Functions
    // ═══════════════════════════════════════════════════════════════════════════

    fun applyStatChange(pokemonName: String, stat: BattleStat, stages: Int, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for stat change")
            return
        }

        val pokemonStats = statChanges.computeIfAbsent(uuid) { ConcurrentHashMap() }
        val currentStage = pokemonStats[stat] ?: 0
        val newStage = (currentStage + stages).coerceIn(-6, 6)
        pokemonStats[stat] = newStage

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName ${stat.abbr} $currentStage -> $newStage")
    }

    fun setStatStage(pokemonName: String, stat: BattleStat, stage: Int, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        val pokemonStats = statChanges.computeIfAbsent(uuid) { ConcurrentHashMap() }
        pokemonStats[stat] = stage.coerceIn(-6, 6)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName ${stat.abbr} set to $stage")
    }

    fun clearPokemonStats(uuid: UUID) {
        statChanges[uuid]?.clear()
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared stats for UUID $uuid")
    }

    fun clearPokemonStatsByName(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        clearPokemonStats(uuid)
    }

    // Haze
    fun clearAllStatsForAll() {
        statChanges.values.forEach { it.clear() }
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared all stats for all Pokemon (Haze)")
    }

    // Topsy-Turvy
    fun invertStats(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        statChanges[uuid]?.let { stats ->
            stats.entries.forEach { (stat, value) ->
                stats[stat] = -value
            }
        }
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Inverted stats for $pokemonName")
    }

    // Psych Up
    fun copyStats(sourceName: String, targetName: String, sourceIsAlly: Boolean? = null, targetIsAlly: Boolean? = null) {
        val sourceUuid = resolvePokemonUuid(sourceName, sourceIsAlly) ?: return
        val targetUuid = resolvePokemonUuid(targetName, targetIsAlly) ?: return
        val sourceStats = statChanges[sourceUuid] ?: return

        val targetStats = statChanges.computeIfAbsent(targetUuid) { ConcurrentHashMap() }
        targetStats.clear()
        targetStats.putAll(sourceStats)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $targetName copied stats from $sourceName")
    }

    // Heart Swap
    fun swapStats(pokemon1Name: String, pokemon2Name: String, pokemon1IsAlly: Boolean? = null, pokemon2IsAlly: Boolean? = null) {
        val uuid1 = resolvePokemonUuid(pokemon1Name, pokemon1IsAlly) ?: return
        val uuid2 = resolvePokemonUuid(pokemon2Name, pokemon2IsAlly) ?: return

        val stats1 = statChanges[uuid1]?.toMap() ?: emptyMap()
        val stats2 = statChanges[uuid2]?.toMap() ?: emptyMap()

        val pokemonStats1 = statChanges.computeIfAbsent(uuid1) { ConcurrentHashMap() }
        val pokemonStats2 = statChanges.computeIfAbsent(uuid2) { ConcurrentHashMap() }

        pokemonStats1.clear()
        pokemonStats1.putAll(stats2)
        pokemonStats2.clear()
        pokemonStats2.putAll(stats1)

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Swapped stats between $pokemon1Name and $pokemon2Name")
    }

    // Power Swap, Guard Swap, Speed Swap
    fun swapSpecificStats(pokemon1Name: String, pokemon2Name: String, statsToSwap: List<BattleStat>,
                          pokemon1IsAlly: Boolean? = null, pokemon2IsAlly: Boolean? = null) {
        val uuid1 = resolvePokemonUuid(pokemon1Name, pokemon1IsAlly) ?: return
        val uuid2 = resolvePokemonUuid(pokemon2Name, pokemon2IsAlly) ?: return

        val pokemonStats1 = statChanges.computeIfAbsent(uuid1) { ConcurrentHashMap() }
        val pokemonStats2 = statChanges.computeIfAbsent(uuid2) { ConcurrentHashMap() }

        for (stat in statsToSwap) {
            val val1 = pokemonStats1[stat] ?: 0
            val val2 = pokemonStats2[stat] ?: 0
            pokemonStats1[stat] = val2
            pokemonStats2[stat] = val1
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Swapped ${statsToSwap.map { it.abbr }} between $pokemon1Name and $pokemon2Name")
    }

    fun getStatChanges(uuid: UUID): Map<BattleStat, Int> {
        return statChanges[uuid]?.filter { it.value != 0 } ?: emptyMap()
    }

    fun getStatFromName(name: String): BattleStat? {
        return when (name.lowercase()) {
            "atk", "attack" -> BattleStat.ATTACK
            "def", "defense" -> BattleStat.DEFENSE
            "spa", "specialattack", "spattack", "spatk" -> BattleStat.SPECIAL_ATTACK
            "spd", "specialdefense", "spdefense", "spdef" -> BattleStat.SPECIAL_DEFENSE
            "spe", "speed" -> BattleStat.SPEED
            "accuracy", "acc" -> BattleStat.ACCURACY
            "evasion", "evasiveness", "eva" -> BattleStat.EVASION
            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Volatile Status Management
    // ═══════════════════════════════════════════════════════════════════════════

    fun setVolatileStatus(pokemonName: String, type: VolatileStatus, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
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

    fun clearVolatileStatus(pokemonName: String, type: VolatileStatus, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        volatileStatuses[uuid]?.removeIf { it.type == type }
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName lost volatile ${type.displayName}")
    }

    fun clearPokemonVolatiles(uuid: UUID) {
        volatileStatuses[uuid]?.clear()
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared volatiles for UUID $uuid")
    }

    fun clearPokemonVolatilesByName(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        clearPokemonVolatiles(uuid)
    }

    fun getVolatileStatuses(uuid: UUID): Set<VolatileStatusState> =
        volatileStatuses[uuid]?.toSet() ?: emptySet()

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

    fun applyPerishSongToAll() {
        val effectiveStartTurn = maxOf(1, currentTurn)
        var count = 0

        // Iterate over all registered Pokemon UUIDs
        for ((_, uuidList) in nameToUuids) {
            for ((uuid, _) in uuidList) {
                val statuses = volatileStatuses.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }

                // Only add if not already affected
                if (statuses.none { it.type == VolatileStatus.PERISH_SONG }) {
                    statuses.add(VolatileStatusState(VolatileStatus.PERISH_SONG, effectiveStartTurn))
                    count++
                }
            }
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Perish Song applied to $count Pokemon")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Baton Pass Handling
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Mark a Pokemon as having used Baton Pass. Call this when the move is used.
     * The actual stat/volatile transfer happens when the Pokemon leaves the field.
     */
    fun markBatonPassUsed(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        batonPassUsers.add(uuid)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName used Baton Pass")
    }

    /**
     * Check if a Pokemon used Baton Pass and prepare the transfer data.
     * Copies stats/volatiles to pending storage, then clears them from the original Pokemon.
     * Returns true if Baton Pass was used.
     */
    fun prepareBatonPassIfUsed(uuid: UUID): Boolean {
        if (!batonPassUsers.remove(uuid)) {
            return false  // This Pokemon didn't use Baton Pass
        }

        val isAlly = uuidIsAlly[uuid] ?: return false
        val stats = statChanges[uuid]?.filter { it.value != 0 }?.toMap() ?: emptyMap()
        val volatiles = volatileStatuses[uuid]
            ?.filter { it.type in BATON_PASS_VOLATILES }
            ?.toSet() ?: emptySet()

        if (stats.isNotEmpty() || volatiles.isNotEmpty()) {
            pendingBatonPass[isAlly] = BatonPassData(stats, volatiles)
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: Prepared Baton Pass data for ${if (isAlly) "ally" else "opponent"} side: " +
                "${stats.size} stats, ${volatiles.size} volatiles"
            )
        }

        // Clear the original Pokemon's stats/volatiles after copying
        // This prevents them from persisting if this Pokemon switches back in later
        statChanges[uuid]?.clear()
        volatileStatuses[uuid]?.clear()

        return true
    }

    /**
     * Apply pending Baton Pass data to a newly switched-in Pokemon.
     * Call this after registering the new Pokemon.
     */
    fun applyBatonPassIfPending(uuid: UUID) {
        val isAlly = uuidIsAlly[uuid] ?: return
        val batonData = pendingBatonPass.remove(isAlly) ?: return

        // Apply stats
        if (batonData.stats.isNotEmpty()) {
            val pokemonStats = statChanges.computeIfAbsent(uuid) { ConcurrentHashMap() }
            pokemonStats.putAll(batonData.stats)
        }

        // Apply volatiles (refresh their start turn to current turn)
        if (batonData.volatiles.isNotEmpty()) {
            val statuses = volatileStatuses.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }
            val effectiveStartTurn = maxOf(1, currentTurn)
            for (volatileState in batonData.volatiles) {
                statuses.add(VolatileStatusState(volatileState.type, effectiveStartTurn))
            }
        }

        CobblemonExtendedBattleUI.LOGGER.debug(
            "BattleStateTracker: Applied Baton Pass to UUID $uuid: " +
            "${batonData.stats.size} stats, ${batonData.volatiles.size} volatiles"
        )
    }

    /**
     * Check if there's pending Baton Pass data for a side.
     */
    fun hasPendingBatonPass(isAlly: Boolean): Boolean = pendingBatonPass.containsKey(isAlly)

    // ═══════════════════════════════════════════════════════════════════════════
    // Spectral Thief Handling
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle Spectral Thief: steal all positive stat boosts from target and add to user.
     * The target's positive boosts are reset to 0, negative boosts remain.
     */
    fun stealPositiveStats(userPokemonName: String, targetPokemonName: String,
                           userIsAlly: Boolean? = null, targetIsAlly: Boolean? = null) {
        val userUuid = resolvePokemonUuid(userPokemonName, userIsAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown user '$userPokemonName' for Spectral Thief")
            return
        }
        val targetUuid = resolvePokemonUuid(targetPokemonName, targetIsAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown target '$targetPokemonName' for Spectral Thief")
            return
        }

        val targetStats = statChanges[targetUuid] ?: return
        val userStats = statChanges.computeIfAbsent(userUuid) { ConcurrentHashMap() }

        var stolenCount = 0
        for (stat in BattleStat.entries) {
            val targetValue = targetStats[stat] ?: 0
            if (targetValue > 0) {
                // Add to user's stats (capped at +6)
                val userValue = userStats[stat] ?: 0
                userStats[stat] = (userValue + targetValue).coerceIn(-6, 6)
                // Clear target's positive boost
                targetStats[stat] = 0
                stolenCount++
            }
        }

        if (stolenCount > 0) {
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: $userPokemonName stole $stolenCount stat boosts from $targetPokemonName via Spectral Thief"
            )
        }
    }

    /**
     * Resolve Pokemon name to UUID. For mirror matches, disambiguates via:
     * 1. Owner prefix (e.g., "Player123's Togekiss")
     * 2. preferAlly hint
     * 3. First registered (fallback)
     */
    private fun resolvePokemonUuid(pokemonName: String, preferAlly: Boolean? = null): UUID? {
        var lookupName = pokemonName.lowercase()
        var ownerDeterminedSide: Boolean? = null  // true = ally, false = opponent

        // Check for owner prefix (e.g., "Player126's Tyranitar" -> owner="Player126", pokemon="Tyranitar")
        // This is the most reliable way to disambiguate in mirror matches
        if (pokemonName.contains("'s ")) {
            val ownerName = pokemonName.substringBefore("'s ").lowercase()
            val strippedName = pokemonName.substringAfter("'s ").lowercase()

            // Try to match owner name against known player names
            val allyName = allyPlayerName
            val oppName = opponentPlayerName

            if (allyName != null && (ownerName == allyName || ownerName.contains(allyName) || allyName.contains(ownerName))) {
                ownerDeterminedSide = true  // This is the ally's Pokemon
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "BattleStateTracker: Owner '$ownerName' matched ally player '$allyName'"
                )
            } else if (oppName != null && (ownerName == oppName || ownerName.contains(oppName) || oppName.contains(ownerName))) {
                ownerDeterminedSide = false  // This is the opponent's Pokemon
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "BattleStateTracker: Owner '$ownerName' matched opponent player '$oppName'"
                )
            }

            // Use stripped name for lookup
            if (nameToUuids[lookupName] == null) {
                lookupName = strippedName
            }
        }

        val uuidList = nameToUuids[lookupName] ?: return null

        // If only one Pokemon with this name, return it
        if (uuidList.size == 1) {
            return uuidList[0].first
        }

        // Multiple Pokemon with same name (mirror match) - need to disambiguate
        if (uuidList.size > 1) {
            // Priority: owner name match > preferAlly hint > fallback
            val targetIsAlly = ownerDeterminedSide ?: preferAlly

            if (targetIsAlly != null) {
                // Find the Pokemon on the preferred side
                val match = uuidList.find { it.second == targetIsAlly }
                if (match != null) {
                    val method = if (ownerDeterminedSide != null) "owner name" else "preferAlly hint"
                    CobblemonExtendedBattleUI.LOGGER.debug(
                        "BattleStateTracker: Resolved '$pokemonName' to ${if (targetIsAlly) "ally" else "opponent"} via $method"
                    )
                    return match.first
                }
            }

            // Fallback: return the first one and log a warning
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: Ambiguous Pokemon '$pokemonName' in mirror match, using first registered (ally=$allyPlayerName, opp=$opponentPlayerName)"
            )
            return uuidList[0].first
        }

        return null
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
}

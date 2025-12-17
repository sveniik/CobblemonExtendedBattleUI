package com.cobblemonextendedbattleui

import net.minecraft.text.Text
import net.minecraft.text.TranslatableTextContent

/**
 * Parses Cobblemon battle messages (via TranslatableTextContent) and updates BattleStateTracker.
 */
object BattleMessageInterceptor {

    // ═══════════════════════════════════════════════════════════════════════════
    // Stat Boost/Unboost Keys
    // ═══════════════════════════════════════════════════════════════════════════

    // Boost magnitude keys - the stat name comes from args, not the key
    private val BOOST_MAGNITUDE_KEYS = mapOf(
        "cobblemon.battle.boost.slight" to 1,
        "cobblemon.battle.boost.sharp" to 2,
        "cobblemon.battle.boost.severe" to 3,
        "cobblemon.battle.boost.slight.zeffect" to 1,
        "cobblemon.battle.boost.sharp.zeffect" to 2,
        "cobblemon.battle.boost.severe.zeffect" to 3
    )

    private val UNBOOST_MAGNITUDE_KEYS = mapOf(
        "cobblemon.battle.unboost.slight" to 1,
        "cobblemon.battle.unboost.sharp" to 2,
        "cobblemon.battle.unboost.severe" to 3
    )

    // Maps displayed stat names to BattleStat enum
    private val STAT_NAME_MAPPING = mapOf(
        "attack" to BattleStateTracker.BattleStat.ATTACK,
        "defense" to BattleStateTracker.BattleStat.DEFENSE,
        "defence" to BattleStateTracker.BattleStat.DEFENSE,
        "special attack" to BattleStateTracker.BattleStat.SPECIAL_ATTACK,
        "sp. atk" to BattleStateTracker.BattleStat.SPECIAL_ATTACK,
        "special defense" to BattleStateTracker.BattleStat.SPECIAL_DEFENSE,
        "special defence" to BattleStateTracker.BattleStat.SPECIAL_DEFENSE,
        "sp. def" to BattleStateTracker.BattleStat.SPECIAL_DEFENSE,
        "speed" to BattleStateTracker.BattleStat.SPEED,
        "accuracy" to BattleStateTracker.BattleStat.ACCURACY,
        "evasion" to BattleStateTracker.BattleStat.EVASION,
        "evasiveness" to BattleStateTracker.BattleStat.EVASION
    )

    // Track last move user and target for moves that don't specify target in swap messages
    private var lastMoveUser: String? = null
    private var lastMoveTarget: String? = null
    private var lastMoveName: String? = null

    /**
     * Clear stale move tracking data. Called when battle state is cleared.
     */
    fun clearMoveTracking() {
        lastMoveUser = null
        lastMoveTarget = null
        lastMoveName = null
    }

    // Move names that require special handling (case-insensitive matching)
    private const val BATON_PASS = "baton pass"
    private const val SPECTRAL_THIEF = "spectral thief"

    // ═══════════════════════════════════════════════════════════════════════════
    // Weather, Terrain, Field, Side Condition Keys
    // ═══════════════════════════════════════════════════════════════════════════

    private val WEATHER_START_KEYS = mapOf(
        "cobblemon.battle.weather.raindance.start" to BattleStateTracker.Weather.RAIN,
        "cobblemon.battle.weather.sunnyday.start" to BattleStateTracker.Weather.SUN,
        "cobblemon.battle.weather.sandstorm.start" to BattleStateTracker.Weather.SANDSTORM,
        "cobblemon.battle.weather.hail.start" to BattleStateTracker.Weather.HAIL,
        "cobblemon.battle.weather.snow.start" to BattleStateTracker.Weather.SNOW
    )

    private val WEATHER_END_KEYS = setOf(
        "cobblemon.battle.weather.raindance.end",
        "cobblemon.battle.weather.sunnyday.end",
        "cobblemon.battle.weather.sandstorm.end",
        "cobblemon.battle.weather.hail.end",
        "cobblemon.battle.weather.snow.end"
    )

    private val TERRAIN_START_KEYS = mapOf(
        "cobblemon.battle.fieldstart.electricterrain" to BattleStateTracker.Terrain.ELECTRIC,
        "cobblemon.battle.fieldstart.grassyterrain" to BattleStateTracker.Terrain.GRASSY,
        "cobblemon.battle.fieldstart.mistyterrain" to BattleStateTracker.Terrain.MISTY,
        "cobblemon.battle.fieldstart.psychicterrain" to BattleStateTracker.Terrain.PSYCHIC
    )

    private val TERRAIN_END_KEYS = setOf(
        "cobblemon.battle.fieldend.electricterrain",
        "cobblemon.battle.fieldend.grassyterrain",
        "cobblemon.battle.fieldend.mistyterrain",
        "cobblemon.battle.fieldend.psychicterrain"
    )

    private val FIELD_START_KEYS = mapOf(
        "cobblemon.battle.fieldstart.trickroom" to BattleStateTracker.FieldCondition.TRICK_ROOM,
        "cobblemon.battle.fieldstart.gravity" to BattleStateTracker.FieldCondition.GRAVITY,
        "cobblemon.battle.fieldstart.magicroom" to BattleStateTracker.FieldCondition.MAGIC_ROOM,
        "cobblemon.battle.fieldstart.wonderroom" to BattleStateTracker.FieldCondition.WONDER_ROOM
    )

    private val FIELD_END_KEYS = mapOf(
        "cobblemon.battle.fieldend.trickroom" to BattleStateTracker.FieldCondition.TRICK_ROOM,
        "cobblemon.battle.fieldend.gravity" to BattleStateTracker.FieldCondition.GRAVITY,
        "cobblemon.battle.fieldend.magicroom" to BattleStateTracker.FieldCondition.MAGIC_ROOM,
        "cobblemon.battle.fieldend.wonderroom" to BattleStateTracker.FieldCondition.WONDER_ROOM
    )

    private val SIDE_START_KEYS = buildMap {
        put("cobblemon.battle.sidestart.ally.reflect", BattleStateTracker.SideCondition.REFLECT to true)
        put("cobblemon.battle.sidestart.ally.lightscreen", BattleStateTracker.SideCondition.LIGHT_SCREEN to true)
        put("cobblemon.battle.sidestart.ally.auroraveil", BattleStateTracker.SideCondition.AURORA_VEIL to true)
        put("cobblemon.battle.sidestart.ally.tailwind", BattleStateTracker.SideCondition.TAILWIND to true)
        put("cobblemon.battle.sidestart.ally.safeguard", BattleStateTracker.SideCondition.SAFEGUARD to true)
        put("cobblemon.battle.sidestart.ally.luckychant", BattleStateTracker.SideCondition.LUCKY_CHANT to true)
        put("cobblemon.battle.sidestart.ally.mist", BattleStateTracker.SideCondition.MIST to true)
        put("cobblemon.battle.sidestart.ally.stealthrock", BattleStateTracker.SideCondition.STEALTH_ROCK to true)
        put("cobblemon.battle.sidestart.ally.spikes", BattleStateTracker.SideCondition.SPIKES to true)
        put("cobblemon.battle.sidestart.ally.toxicspikes", BattleStateTracker.SideCondition.TOXIC_SPIKES to true)
        put("cobblemon.battle.sidestart.ally.stickyweb", BattleStateTracker.SideCondition.STICKY_WEB to true)
        put("cobblemon.battle.sidestart.opponent.reflect", BattleStateTracker.SideCondition.REFLECT to false)
        put("cobblemon.battle.sidestart.opponent.lightscreen", BattleStateTracker.SideCondition.LIGHT_SCREEN to false)
        put("cobblemon.battle.sidestart.opponent.auroraveil", BattleStateTracker.SideCondition.AURORA_VEIL to false)
        put("cobblemon.battle.sidestart.opponent.tailwind", BattleStateTracker.SideCondition.TAILWIND to false)
        put("cobblemon.battle.sidestart.opponent.safeguard", BattleStateTracker.SideCondition.SAFEGUARD to false)
        put("cobblemon.battle.sidestart.opponent.luckychant", BattleStateTracker.SideCondition.LUCKY_CHANT to false)
        put("cobblemon.battle.sidestart.opponent.mist", BattleStateTracker.SideCondition.MIST to false)
        put("cobblemon.battle.sidestart.opponent.stealthrock", BattleStateTracker.SideCondition.STEALTH_ROCK to false)
        put("cobblemon.battle.sidestart.opponent.spikes", BattleStateTracker.SideCondition.SPIKES to false)
        put("cobblemon.battle.sidestart.opponent.toxicspikes", BattleStateTracker.SideCondition.TOXIC_SPIKES to false)
        put("cobblemon.battle.sidestart.opponent.stickyweb", BattleStateTracker.SideCondition.STICKY_WEB to false)
    }

    private val SIDE_END_KEYS = buildMap {
        put("cobblemon.battle.sideend.ally.reflect", BattleStateTracker.SideCondition.REFLECT to true)
        put("cobblemon.battle.sideend.ally.lightscreen", BattleStateTracker.SideCondition.LIGHT_SCREEN to true)
        put("cobblemon.battle.sideend.ally.auroraveil", BattleStateTracker.SideCondition.AURORA_VEIL to true)
        put("cobblemon.battle.sideend.ally.tailwind", BattleStateTracker.SideCondition.TAILWIND to true)
        put("cobblemon.battle.sideend.ally.safeguard", BattleStateTracker.SideCondition.SAFEGUARD to true)
        put("cobblemon.battle.sideend.ally.luckychant", BattleStateTracker.SideCondition.LUCKY_CHANT to true)
        put("cobblemon.battle.sideend.ally.mist", BattleStateTracker.SideCondition.MIST to true)
        put("cobblemon.battle.sideend.ally.stealthrock", BattleStateTracker.SideCondition.STEALTH_ROCK to true)
        put("cobblemon.battle.sideend.ally.spikes", BattleStateTracker.SideCondition.SPIKES to true)
        put("cobblemon.battle.sideend.ally.toxicspikes", BattleStateTracker.SideCondition.TOXIC_SPIKES to true)
        put("cobblemon.battle.sideend.ally.stickyweb", BattleStateTracker.SideCondition.STICKY_WEB to true)
        put("cobblemon.battle.sideend.opponent.reflect", BattleStateTracker.SideCondition.REFLECT to false)
        put("cobblemon.battle.sideend.opponent.lightscreen", BattleStateTracker.SideCondition.LIGHT_SCREEN to false)
        put("cobblemon.battle.sideend.opponent.auroraveil", BattleStateTracker.SideCondition.AURORA_VEIL to false)
        put("cobblemon.battle.sideend.opponent.tailwind", BattleStateTracker.SideCondition.TAILWIND to false)
        put("cobblemon.battle.sideend.opponent.safeguard", BattleStateTracker.SideCondition.SAFEGUARD to false)
        put("cobblemon.battle.sideend.opponent.luckychant", BattleStateTracker.SideCondition.LUCKY_CHANT to false)
        put("cobblemon.battle.sideend.opponent.mist", BattleStateTracker.SideCondition.MIST to false)
        put("cobblemon.battle.sideend.opponent.stealthrock", BattleStateTracker.SideCondition.STEALTH_ROCK to false)
        put("cobblemon.battle.sideend.opponent.spikes", BattleStateTracker.SideCondition.SPIKES to false)
        put("cobblemon.battle.sideend.opponent.toxicspikes", BattleStateTracker.SideCondition.TOXIC_SPIKES to false)
        put("cobblemon.battle.sideend.opponent.stickyweb", BattleStateTracker.SideCondition.STICKY_WEB to false)
    }

    private const val TURN_KEY = "cobblemon.battle.turn"
    private const val FAINT_KEY = "cobblemon.battle.faint"
    private const val SWITCH_KEY = "cobblemon.battle.switch"
    private const val DRAG_KEY = "cobblemon.battle.drag"  // Forced switch (e.g., Roar, Whirlwind)
    private const val PERISH_SONG_FIELD_KEY = "cobblemon.battle.fieldactivate.perishsong"  // Applies to all Pokemon

    // Volatile status start keys
    private val VOLATILE_START_KEYS = mapOf(
        // Seeding/Draining
        "cobblemon.battle.start.leechseed" to BattleStateTracker.VolatileStatus.LEECH_SEED,

        // Mental/Behavioral effects
        "cobblemon.battle.start.confusion" to BattleStateTracker.VolatileStatus.CONFUSION,
        "cobblemon.battle.start.taunt" to BattleStateTracker.VolatileStatus.TAUNT,
        "cobblemon.battle.start.encore" to BattleStateTracker.VolatileStatus.ENCORE,
        "cobblemon.battle.start.disable" to BattleStateTracker.VolatileStatus.DISABLE,
        "cobblemon.battle.start.torment" to BattleStateTracker.VolatileStatus.TORMENT,
        "cobblemon.battle.start.attract" to BattleStateTracker.VolatileStatus.INFATUATION,

        // Countdown effects
        "cobblemon.battle.start.perish" to BattleStateTracker.VolatileStatus.PERISH_SONG,
        "cobblemon.battle.start.yawn" to BattleStateTracker.VolatileStatus.DROWSY,

        // Damage over time
        "cobblemon.battle.start.curse" to BattleStateTracker.VolatileStatus.CURSE,
        "cobblemon.battle.start.nightmare" to BattleStateTracker.VolatileStatus.NIGHTMARE,

        // Protection/Healing (positive)
        "cobblemon.battle.start.substitute" to BattleStateTracker.VolatileStatus.SUBSTITUTE,
        "cobblemon.battle.start.aquaring" to BattleStateTracker.VolatileStatus.AQUA_RING,
        "cobblemon.battle.start.ingrain" to BattleStateTracker.VolatileStatus.INGRAIN,
        "cobblemon.battle.start.focusenergy" to BattleStateTracker.VolatileStatus.FOCUS_ENERGY,
        "cobblemon.battle.start.magnetrise" to BattleStateTracker.VolatileStatus.MAGNET_RISE,

        // Prevention effects
        "cobblemon.battle.start.embargo" to BattleStateTracker.VolatileStatus.EMBARGO,
        "cobblemon.battle.start.healblock" to BattleStateTracker.VolatileStatus.HEAL_BLOCK
    )

    // Volatile status activate keys (trapping moves use "activate" instead of "start")
    private val VOLATILE_ACTIVATE_KEYS = mapOf(
        // Trapping moves (all map to BOUND)
        "cobblemon.battle.activate.bind" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.wrap" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.firespin" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.whirlpool" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.sandtomb" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.clamp" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.infestation" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.magmastorm" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.snaptrap" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.activate.thundercage" to BattleStateTracker.VolatileStatus.BOUND,

        // Movement restriction
        "cobblemon.battle.activate.trapped" to BattleStateTracker.VolatileStatus.TRAPPED
    )

    // Volatile status end keys
    private val VOLATILE_END_KEYS = mapOf(
        // Seeding/Draining
        "cobblemon.battle.end.leechseed" to BattleStateTracker.VolatileStatus.LEECH_SEED,

        // Mental/Behavioral effects
        "cobblemon.battle.end.confusion" to BattleStateTracker.VolatileStatus.CONFUSION,
        "cobblemon.battle.end.taunt" to BattleStateTracker.VolatileStatus.TAUNT,
        "cobblemon.battle.end.encore" to BattleStateTracker.VolatileStatus.ENCORE,
        "cobblemon.battle.end.disable" to BattleStateTracker.VolatileStatus.DISABLE,
        "cobblemon.battle.end.torment" to BattleStateTracker.VolatileStatus.TORMENT,
        "cobblemon.battle.end.attract" to BattleStateTracker.VolatileStatus.INFATUATION,

        // Protection/Healing
        "cobblemon.battle.end.substitute" to BattleStateTracker.VolatileStatus.SUBSTITUTE,
        "cobblemon.battle.end.magnetrise" to BattleStateTracker.VolatileStatus.MAGNET_RISE,

        // Prevention effects
        "cobblemon.battle.end.embargo" to BattleStateTracker.VolatileStatus.EMBARGO,
        "cobblemon.battle.end.healblock" to BattleStateTracker.VolatileStatus.HEAL_BLOCK,

        // Trapping moves (all clear BOUND)
        "cobblemon.battle.end.bind" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.end.wrap" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.end.firespin" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.end.whirlpool" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.end.sandtomb" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.end.clamp" to BattleStateTracker.VolatileStatus.BOUND,
        "cobblemon.battle.end.infestation" to BattleStateTracker.VolatileStatus.BOUND
    )

    fun processMessages(messages: List<Text>) {
        for (message in messages) {
            processComponent(message)
        }
    }

    private fun processComponent(text: Text) {
        val contents = text.content

        if (contents is TranslatableTextContent) {
            val key = contents.key
            val args = contents.args

            if (key.startsWith("cobblemon.battle.")) {
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessage: key='$key', args=${args.map {
                    when (it) {
                        is Text -> it.string
                        else -> it.toString()
                    }
                }}")
            }

            if (key == TURN_KEY) {
                extractTurn(args)
                return
            }

            // Track move usage with target for later use by swap moves and special move handling
            // "used_move_on" has args: [user, moveName, target]
            if (key == "cobblemon.battle.used_move_on" && args.size >= 3) {
                lastMoveUser = argToString(args[0])
                lastMoveName = argToString(args[1])
                lastMoveTarget = argToString(args[2])
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Move tracked - $lastMoveUser used $lastMoveName on $lastMoveTarget")

                // Handle Spectral Thief: steal positive stat boosts from target
                if (lastMoveName?.lowercase() == SPECTRAL_THIEF) {
                    BattleStateTracker.stealPositiveStats(lastMoveUser!!, lastMoveTarget!!)
                }
                // Don't return - let it continue to be processed by BattleLog
            }

            // Track move usage without target (self-targeting moves like Baton Pass)
            // "used_move" has args: [user, moveName]
            if (key == "cobblemon.battle.used_move" && args.size >= 2) {
                lastMoveUser = argToString(args[0])
                lastMoveName = argToString(args[1])
                lastMoveTarget = null
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Self-move tracked - $lastMoveUser used $lastMoveName")

                // Handle Baton Pass: mark the user so stats transfer on switch
                if (lastMoveName?.lowercase() == BATON_PASS) {
                    BattleStateTracker.markBatonPassUsed(lastMoveUser!!)
                }
                // Don't return - let it continue to be processed by BattleLog
            }

            // Stat boost messages: cobblemon.battle.boost.{slight|sharp|severe}
            // Args: [pokemonName, statName]
            BOOST_MAGNITUDE_KEYS[key]?.let { stages ->
                extractBoost(args, stages)
                return
            }

            // Stat unboost messages: cobblemon.battle.unboost.{slight|sharp|severe}
            // Args: [pokemonName, statName]
            UNBOOST_MAGNITUDE_KEYS[key]?.let { stages ->
                extractBoost(args, -stages)
                return
            }

            // Set boost: cobblemon.battle.setboost.bellydrum or cobblemon.battle.setboost.angerpoint
            // Args: [pokemonName] - sets Attack to +6
            if (key == "cobblemon.battle.setboost.bellydrum" || key == "cobblemon.battle.setboost.angerpoint") {
                extractSetBoost(args)
                return
            }

            // Clear all boosts: cobblemon.battle.clearallboost (Haze - clears all stats for all Pokemon)
            if (key == "cobblemon.battle.clearallboost") {
                BattleStateTracker.clearAllStatsForAll()
                return
            }

            // Clear boost for one Pokemon: cobblemon.battle.clearboost
            // Args: [pokemonName]
            if (key == "cobblemon.battle.clearboost") {
                extractClearBoost(args)
                return
            }

            // Invert boost: cobblemon.battle.invertboost (Topsy-Turvy)
            // Args: [pokemonName]
            if (key == "cobblemon.battle.invertboost") {
                extractInvertBoost(args)
                return
            }

            // Swap boost: cobblemon.battle.swapboost.heartswap or cobblemon.battle.swapboost.generic
            // Heart Swap swaps ALL stat changes
            // heartswap only has 1 arg (user), generic has 2 args (user, target)
            if (key == "cobblemon.battle.swapboost.heartswap" || key == "cobblemon.battle.swapboost.generic") {
                extractSwapBoostAllStats(args)
                return
            }

            // Power/Guard/Speed Swap: single arg, uses lastMoveTarget
            if (key == "cobblemon.battle.swapboost.powerswap") {
                extractSwapBoostSpecific(args, listOf(BattleStateTracker.BattleStat.ATTACK, BattleStateTracker.BattleStat.SPECIAL_ATTACK))
                return
            }
            if (key == "cobblemon.battle.swapboost.guardswap") {
                extractSwapBoostSpecific(args, listOf(BattleStateTracker.BattleStat.DEFENSE, BattleStateTracker.BattleStat.SPECIAL_DEFENSE))
                return
            }
            if (key == "cobblemon.battle.activate.speedswap") {
                extractSwapBoostSpecific(args, listOf(BattleStateTracker.BattleStat.SPEED))
                return
            }

            // Copy boost: cobblemon.battle.copyboost.generic (Psych Up)
            // Args: [copier, source]
            if (key == "cobblemon.battle.copyboost.generic") {
                extractCopyBoost(args)
                return
            }

            WEATHER_START_KEYS[key]?.let { weather ->
                BattleStateTracker.setWeather(weather)
                return
            }

            if (key in WEATHER_END_KEYS) {
                BattleStateTracker.clearWeather()
                return
            }

            TERRAIN_START_KEYS[key]?.let { terrain ->
                BattleStateTracker.setTerrain(terrain)
                return
            }

            if (key in TERRAIN_END_KEYS) {
                BattleStateTracker.clearTerrain()
                return
            }

            FIELD_START_KEYS[key]?.let { condition ->
                BattleStateTracker.setFieldCondition(condition)
                return
            }

            FIELD_END_KEYS[key]?.let { condition ->
                BattleStateTracker.clearFieldCondition(condition)
                return
            }

            SIDE_START_KEYS[key]?.let { (condition, isAlly) ->
                BattleStateTracker.setSideCondition(isAlly, condition)
                return
            }

            SIDE_END_KEYS[key]?.let { (condition, isAlly) ->
                BattleStateTracker.clearSideCondition(isAlly, condition)
                return
            }

            VOLATILE_START_KEYS[key]?.let { volatileStatus ->
                extractVolatileStatusStart(args, volatileStatus)
                return
            }

            VOLATILE_END_KEYS[key]?.let { volatileStatus ->
                extractVolatileStatusEnd(args, volatileStatus)
                return
            }

            VOLATILE_ACTIVATE_KEYS[key]?.let { volatileStatus ->
                extractVolatileStatusStart(args, volatileStatus)
                return
            }

            // Perish Song applies to ALL active Pokemon
            if (key == PERISH_SONG_FIELD_KEY) {
                BattleStateTracker.applyPerishSongToAll()
                return
            }

            // Faint/switch clears stats and volatile statuses
            if (key == FAINT_KEY) {
                clearPokemonState(args)
                return
            }
            if (key == SWITCH_KEY || key == DRAG_KEY) {
                clearPokemonState(args)
                return
            }
        }

        for (sibling in text.siblings) {
            processComponent(sibling)
        }
    }

    private fun extractTurn(args: Array<out Any>) {
        if (args.isEmpty()) return

        val turnStr = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            is Number -> arg0.toString()
            else -> arg0.toString()
        }

        val turn = turnStr.toIntOrNull()
        if (turn != null) {
            BattleStateTracker.setTurn(turn)
        }
    }

    private fun clearPokemonState(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Clearing stats and volatiles for $pokemonName (faint/switch)")
        BattleStateTracker.clearPokemonStatsByName(pokemonName)
        BattleStateTracker.clearPokemonVolatilesByName(pokemonName)
    }

    private fun argToString(arg: Any): String {
        return when (arg) {
            is Text -> arg.string
            is String -> arg
            is TranslatableTextContent -> {
                // If it's a raw TranslatableTextContent, create a Text and get string
                Text.translatable(arg.key, *arg.args).string
            }
            else -> arg.toString()
        }
    }

    // Args: [pokemonName, statName]. stages > 0 for boost, < 0 for drop.
    private fun extractBoost(args: Array<out Any>, stages: Int) {
        if (args.size < 2) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Boost args too short: ${args.size}, args=${args.map { "${it::class.simpleName}:$it" }}")
            return
        }

        val pokemonName = argToString(args[0])
        val statName = argToString(args[1])

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Boost parsing - pokemon='$pokemonName', stat='$statName', stages=$stages")

        val stat = STAT_NAME_MAPPING[statName.lowercase()] ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Unknown stat name: '$statName' (lowercase: '${statName.lowercase()}')")
            return
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: $pokemonName ${stat.abbr} ${if (stages > 0) "+" else ""}$stages")
        BattleStateTracker.applyStatChange(pokemonName, stat, stages)
    }

    // Belly Drum / Anger Point: sets Attack to +6
    private fun extractSetBoost(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: $pokemonName Attack set to +6 (Belly Drum/Anger Point)")
        BattleStateTracker.setStatStage(pokemonName, BattleStateTracker.BattleStat.ATTACK, 6)
    }

    private fun extractClearBoost(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Clearing all stats for $pokemonName")
        BattleStateTracker.clearPokemonStatsByName(pokemonName)
    }

    // Topsy-Turvy: invert all stat changes
    private fun extractInvertBoost(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Inverting stats for $pokemonName")
        BattleStateTracker.invertStats(pokemonName)
    }

    // Heart Swap: swap all stats. Single-arg variant uses lastMoveTarget.
    private fun extractSwapBoostAllStats(args: Array<out Any>) {
        if (args.isEmpty()) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: SwapBoostAllStats no args")
            return
        }

        val pokemon1 = argToString(args[0])
        val pokemon2: String

        if (args.size >= 2) {
            // Generic swap with both Pokemon specified
            pokemon2 = argToString(args[1])
        } else {
            // Single arg (heartswap) - use tracked target
            pokemon2 = lastMoveTarget ?: run {
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: SwapBoostAllStats no target tracked for $pokemon1")
                return
            }
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Swapping ALL stats between $pokemon1 and $pokemon2")
        BattleStateTracker.swapStats(pokemon1, pokemon2)
    }

    // Power/Guard/Speed Swap: swap specific stats. Single-arg variant uses lastMoveTarget.
    private fun extractSwapBoostSpecific(args: Array<out Any>, statsToSwap: List<BattleStateTracker.BattleStat>) {
        if (args.isEmpty()) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: SwapBoostSpecific no args")
            return
        }

        val pokemon1 = argToString(args[0])
        val pokemon2: String

        if (args.size >= 2) {
            // Two args provided
            pokemon2 = argToString(args[1])
        } else {
            // Single arg - use tracked target
            pokemon2 = lastMoveTarget ?: run {
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: SwapBoostSpecific no target tracked for $pokemon1")
                return
            }
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Swapping ${statsToSwap.map { it.abbr }} between $pokemon1 and $pokemon2")
        BattleStateTracker.swapSpecificStats(pokemon1, pokemon2, statsToSwap)
    }

    // Psych Up: copy all stat changes from source to copier
    private fun extractCopyBoost(args: Array<out Any>) {
        if (args.size < 2) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: CopyBoost needs 2 args, got ${args.size}")
            return
        }

        val copier = argToString(args[0])
        val source = argToString(args[1])

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: $copier copies stats from $source")
        BattleStateTracker.copyStats(source, copier)
    }

    private fun extractVolatileStatusStart(args: Array<out Any>, volatileStatus: BattleStateTracker.VolatileStatus) {
        if (args.isEmpty()) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: No args for volatile status start: ${volatileStatus.displayName}")
            return
        }

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Volatile start - $pokemonName gained ${volatileStatus.displayName}")
        BattleStateTracker.setVolatileStatus(pokemonName, volatileStatus)
    }

    private fun extractVolatileStatusEnd(args: Array<out Any>, volatileStatus: BattleStateTracker.VolatileStatus) {
        if (args.isEmpty()) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: No args for volatile status end: ${volatileStatus.displayName}")
            return
        }

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Volatile end - $pokemonName lost ${volatileStatus.displayName}")
        BattleStateTracker.clearVolatileStatus(pokemonName, volatileStatus)
    }
}

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

    // Ability announcement keys (for abilities that affect PP/mechanics)
    // Pressure: "X is exerting its Pressure!" - args: [pokemonName]
    private const val PRESSURE_KEY = "cobblemon.battle.ability.pressure"

    // ═══════════════════════════════════════════════════════════════════════════
    // Item Tracking Keys
    // ═══════════════════════════════════════════════════════════════════════════

    // Item reveal messages - format: [pokemon, item] or [target, item, revealer]
    private val ITEM_REVEAL_KEYS = setOf(
        "cobblemon.battle.item.airballoon",     // Pokemon floats with Air Balloon
        "cobblemon.battle.item.eat",            // Pokemon eats item
        "cobblemon.battle.item.harvest",        // Harvest regrows berry
        "cobblemon.battle.item.recycle",        // Recycle recovers item
        "cobblemon.battle.item.trick",          // Trick obtains item
        "cobblemon.battle.damage.item"          // Item damages holder (Life Orb)
    )

    // Item reveal with different arg order: [target, item, user]
    private const val FRISK_KEY = "cobblemon.battle.item.frisk"

    // Life Orb damage message - only contains Pokemon name, no item name
    private const val LIFE_ORB_KEY = "cobblemon.battle.damage.lifeorb"

    // Thief: [thief, item, victim]
    private const val THIEF_KEY = "cobblemon.battle.item.thief"

    // Bestow: [receiver, item, giver]
    private const val BESTOW_KEY = "cobblemon.battle.item.bestow"

    // Item consumed/removed messages - format: [pokemon, item]
    private val ITEM_CONSUMED_KEYS = setOf(
        "cobblemon.battle.enditem.eat",
        "cobblemon.battle.enditem.fling",
        "cobblemon.battle.enditem.gem",
        "cobblemon.battle.enditem.incinerate",
        "cobblemon.battle.enditem.airballoon",
        "cobblemon.battle.enditem.generic",
        "cobblemon.battle.enditem.mentalherb",
        "cobblemon.battle.enditem.powerherb",
        "cobblemon.battle.enditem.mirrorherb",
        "cobblemon.battle.enditem.whiteherb",
        "cobblemon.battle.enditem.cellbattery",
        "cobblemon.battle.enditem.ejectbutton",
        "cobblemon.battle.enditem.snowball",
        "cobblemon.battle.enditem.ejectpack",
        "cobblemon.battle.enditem.berryjuice",
        "cobblemon.battle.enditem.redcard"
    )

    // Item consumed messages with only 1 arg (Pokemon name) - item name in key
    // Format: [pokemon] - item name is derived from key
    private val ITEM_CONSUMED_SINGLE_ARG_KEYS = mapOf(
        "cobblemon.battle.enditem.focussash" to "Focus Sash",
        "cobblemon.battle.enditem.focusband" to "Focus Band"
    )

    // Berry damage reduction messages - format varies, extract item from message
    private val BERRY_DAMAGE_KEYS = setOf(
        "cobblemon.battle.enditem.occaberry",
        "cobblemon.battle.enditem.passhoberry",
        "cobblemon.battle.enditem.wacanberry",
        "cobblemon.battle.enditem.rindoberry",
        "cobblemon.battle.enditem.yacheberry",
        "cobblemon.battle.enditem.chopleberry",
        "cobblemon.battle.enditem.kebiaberry",
        "cobblemon.battle.enditem.shucaberry",
        "cobblemon.battle.enditem.cobaberry"
    )

    // Knock Off: [target, item, attacker]
    private const val KNOCKOFF_KEY = "cobblemon.battle.enditem.knockoff"

    // Steal and eat (Bug Bite/Pluck): [attacker, item, target]
    private const val STEALEAT_KEY = "cobblemon.battle.enditem.stealeat"

    // Corrosive Gas: [target, item, attacker]
    private const val CORROSIVEGAS_KEY = "cobblemon.battle.enditem.corrosivegas"

    // Healing items that reveal but don't consume - format: [pokemon, item]
    // These items heal the holder each turn but remain held
    private val HEALING_ITEM_KEYS = setOf(
        "cobblemon.battle.heal.leftovers",      // Leftovers: "restored a little HP using its Leftovers"
        "cobblemon.battle.heal.item"            // Black Sludge and similar: "restored HP using its [item]"
    )

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
    private const val FAINT_KEY = "cobblemon.battle.fainted"
    private const val SWITCH_KEY = "cobblemon.battle.switch"
    private const val DRAG_KEY = "cobblemon.battle.drag"  // Forced switch (e.g., Roar, Whirlwind)
    private const val PERISH_SONG_FIELD_KEY = "cobblemon.battle.fieldactivate.perishsong"  // Applies to all Pokemon
    private const val TRANSFORM_KEY = "cobblemon.battle.transform"  // Ditto Transform/Impostor

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

                // Track revealed move for tooltip display (pass target for Pressure PP check)
                BattleStateTracker.addRevealedMove(lastMoveUser!!, lastMoveName!!, lastMoveTarget)

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

                // Track revealed move for tooltip display (no target = no Pressure check)
                BattleStateTracker.addRevealedMove(lastMoveUser!!, lastMoveName!!, null)

                // Handle Baton Pass: mark the user so stats transfer on switch
                if (lastMoveName?.lowercase() == BATON_PASS) {
                    BattleStateTracker.markBatonPassUsed(lastMoveUser!!)
                }
                // Don't return - let it continue to be processed by BattleLog
            }

            // Pressure ability announcement - register Pokemon with Pressure
            // This causes moves used against them to cost 2 PP instead of 1
            if (key == PRESSURE_KEY && args.isNotEmpty()) {
                val pokemonName = argToString(args[0])
                BattleStateTracker.registerPressure(pokemonName)
                CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Pressure registered for $pokemonName")
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

            // ═══════════════════════════════════════════════════════════════════════════
            // Item Tracking
            // ═══════════════════════════════════════════════════════════════════════════

            // Standard item reveal: [pokemon, item]
            if (key in ITEM_REVEAL_KEYS) {
                extractItemReveal(args)
                return
            }

            // Life Orb damage message: [pokemon] - item name not included, we know it's Life Orb
            if (key == LIFE_ORB_KEY) {
                extractLifeOrbReveal(args)
                return
            }

            // Frisk reveals item: [target, item, frisker]
            if (key == FRISK_KEY) {
                extractFriskItem(args)
                return
            }

            // Thief steals item: [thief, item, victim]
            if (key == THIEF_KEY) {
                extractThiefItem(args)
                return
            }

            // Bestow gives item: [receiver, item, giver]
            if (key == BESTOW_KEY) {
                extractBestowItem(args)
                return
            }

            // Item consumed (general): [pokemon, item]
            if (key in ITEM_CONSUMED_KEYS) {
                extractItemConsumed(args)
                return
            }

            // Item consumed (single-arg): [pokemon] - item name derived from key
            ITEM_CONSUMED_SINGLE_ARG_KEYS[key]?.let { itemName ->
                extractItemConsumedSingleArg(args, itemName)
                return
            }

            // Berry damage reduction: [pokemon] - extract berry from key
            if (key in BERRY_DAMAGE_KEYS) {
                extractBerryFromKey(key, args)
                return
            }

            // Knock Off: [target, item, attacker]
            if (key == KNOCKOFF_KEY) {
                extractKnockOff(args)
                return
            }

            // Bug Bite / Pluck: [attacker, item, target]
            if (key == STEALEAT_KEY) {
                extractStealEat(args)
                return
            }

            // Corrosive Gas: [target, item, attacker]
            if (key == CORROSIVEGAS_KEY) {
                extractCorrosiveGas(args)
                return
            }

            // Healing items (Leftovers, Black Sludge): [pokemon, item]
            // These items heal each turn but remain held
            if (key in HEALING_ITEM_KEYS) {
                extractHealingItem(args)
                return
            }

            // Perish Song applies to ALL active Pokemon
            if (key == PERISH_SONG_FIELD_KEY) {
                BattleStateTracker.applyPerishSongToAll()
                return
            }

            // Transform (Ditto) - mark the Pokemon as transformed for form reversion on faint
            // Format: [transformer, target]
            if (key == TRANSFORM_KEY) {
                markPokemonTransformed(args)
                return
            }

            // Faint/switch clears stats and volatile statuses
            if (key == FAINT_KEY) {
                markPokemonFainted(args)
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

    /**
     * Handle Pokemon faint: mark as KO'd and clear stats/volatiles.
     * Marking as KO'd is critical for pokeball indicators since the Pokemon
     * gets removed from activePokemon immediately after fainting.
     */
    private fun markPokemonFainted(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Pokemon fainted - $pokemonName")

        // Mark as KO'd in BattleStateTracker (persists for pokeball indicators)
        BattleStateTracker.markAsKO(pokemonName)

        // Also notify TeamIndicatorUI directly to update its tracking
        TeamIndicatorUI.markPokemonAsKO(pokemonName)

        // Clear stats and volatiles
        BattleStateTracker.clearPokemonStatsByName(pokemonName)
        BattleStateTracker.clearPokemonVolatilesByName(pokemonName)
    }

    private fun clearPokemonState(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Clearing stats, volatiles, and transform for $pokemonName (switch)")
        BattleStateTracker.clearPokemonStatsByName(pokemonName)
        BattleStateTracker.clearPokemonVolatilesByName(pokemonName)

        // Clear transform status - Pokemon reverts to original form when switched out
        BattleStateTracker.clearTransformStatusByName(pokemonName)
        TeamIndicatorUI.clearTransformStatus(pokemonName)
    }

    /**
     * Handle Transform (Ditto) - mark the transforming Pokemon for later form reversion.
     * Args: [transformer, target] - transformer transformed into target
     */
    private fun markPokemonTransformed(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Pokemon transformed - $pokemonName")

        // Mark in BattleStateTracker for form reversion on faint
        BattleStateTracker.markAsTransformed(pokemonName)

        // Notify TeamIndicatorUI to track the transformation
        TeamIndicatorUI.markPokemonAsTransformed(pokemonName)
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Item Extraction Functions
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Standard item reveal: args = [pokemon, item]
     * Used for: Air Balloon, item.eat, Harvest, Recycle, Trick obtain, damage.item (Life Orb)
     */
    private fun extractItemReveal(args: Array<out Any>) {
        if (args.size < 2) return

        val pokemonName = argToString(args[0])
        val itemName = argToString(args[1])

        BattleStateTracker.setItem(pokemonName, itemName, BattleStateTracker.ItemStatus.HELD)
    }

    /**
     * Life Orb damage message: args = [pokemon]
     * The message "%1$s lost some of its HP!" only contains the Pokemon name,
     * but we know the item is Life Orb since this message is only for that item.
     */
    private fun extractLifeOrbReveal(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = argToString(args[0])
        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Life Orb revealed for $pokemonName")
        BattleStateTracker.setItem(pokemonName, "Life Orb", BattleStateTracker.ItemStatus.HELD)
    }

    /**
     * Frisk reveals item: args = [target, item, frisker]
     * Frisk ability reveals opponent's item
     */
    private fun extractFriskItem(args: Array<out Any>) {
        if (args.size < 2) return

        val targetPokemon = argToString(args[0])
        val itemName = argToString(args[1])

        BattleStateTracker.setItem(targetPokemon, itemName, BattleStateTracker.ItemStatus.HELD)
    }

    /**
     * Thief steals item: args = [thief, item, victim]
     */
    private fun extractThiefItem(args: Array<out Any>) {
        if (args.size < 3) return

        val thiefPokemon = argToString(args[0])
        val itemName = argToString(args[1])
        val victimPokemon = argToString(args[2])

        // Transfer item from victim to thief
        BattleStateTracker.transferItem(victimPokemon, thiefPokemon, itemName)
    }

    /**
     * Bestow gives item: args = [receiver, item, giver]
     */
    private fun extractBestowItem(args: Array<out Any>) {
        if (args.size < 3) return

        val receiverPokemon = argToString(args[0])
        val itemName = argToString(args[1])
        val giverPokemon = argToString(args[2])

        // Transfer item from giver to receiver
        BattleStateTracker.transferItem(giverPokemon, receiverPokemon, itemName)
    }

    /**
     * Item consumed: args = [pokemon, item]
     * Used for berries eaten, gems used, etc.
     */
    private fun extractItemConsumed(args: Array<out Any>) {
        if (args.size < 2) return

        val pokemonName = argToString(args[0])
        val itemName = argToString(args[1])

        BattleStateTracker.setItem(pokemonName, itemName, BattleStateTracker.ItemStatus.CONSUMED)
    }

    /**
     * Item consumed with single arg: args = [pokemon]
     * Used for Focus Sash, Focus Band where item name is in the translation key.
     */
    private fun extractItemConsumedSingleArg(args: Array<out Any>, itemName: String) {
        if (args.isEmpty()) return

        val pokemonName = argToString(args[0])
        BattleStateTracker.setItem(pokemonName, itemName, BattleStateTracker.ItemStatus.CONSUMED)
    }

    /**
     * Berry damage reduction: key contains berry name, args = [pokemon]
     * Extract berry name from key like "cobblemon.battle.enditem.occaberry"
     */
    private fun extractBerryFromKey(key: String, args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = argToString(args[0])

        // Extract berry name from key: "cobblemon.battle.enditem.occaberry" -> "Occa Berry"
        val berryId = key.substringAfterLast(".")
        val berryName = berryId
            .replace("berry", " Berry")
            .replaceFirstChar { it.uppercase() }

        BattleStateTracker.setItem(pokemonName, berryName, BattleStateTracker.ItemStatus.CONSUMED)
    }

    /**
     * Knock Off: args = [target, item, attacker]
     */
    private fun extractKnockOff(args: Array<out Any>) {
        if (args.size < 2) return

        val targetPokemon = argToString(args[0])
        val itemName = argToString(args[1])

        BattleStateTracker.setItem(targetPokemon, itemName, BattleStateTracker.ItemStatus.KNOCKED_OFF)
    }

    /**
     * Bug Bite / Pluck steal and eat: args = [attacker, item, target]
     */
    private fun extractStealEat(args: Array<out Any>) {
        if (args.size < 3) return

        // args = [attacker, item, target] - attacker eats the item immediately
        val itemName = argToString(args[1])
        val targetPokemon = argToString(args[2])

        // Mark target's item as stolen, attacker doesn't keep it (they eat it)
        BattleStateTracker.setItem(targetPokemon, itemName, BattleStateTracker.ItemStatus.STOLEN)
    }

    /**
     * Corrosive Gas: args = [target, item, attacker]
     * Destroys the target's held item
     */
    private fun extractCorrosiveGas(args: Array<out Any>) {
        if (args.size < 2) return

        val targetPokemon = argToString(args[0])
        val itemName = argToString(args[1])

        // Corrosive Gas destroys the item (similar to Knock Off but different message)
        BattleStateTracker.setItem(targetPokemon, itemName, BattleStateTracker.ItemStatus.CONSUMED)
    }

    /**
     * Healing items: args = [pokemon, item]
     * Leftovers and Black Sludge heal each turn and remain held.
     * Berries (Sitrus Berry, etc.) heal once and are consumed.
     */
    private fun extractHealingItem(args: Array<out Any>) {
        if (args.size < 2) return

        val pokemonName = argToString(args[0])
        val itemName = argToString(args[1])

        // Berries are consumed when they heal, other healing items (Leftovers, Black Sludge) remain held
        val status = if (itemName.lowercase().contains("berry")) {
            BattleStateTracker.ItemStatus.CONSUMED
        } else {
            BattleStateTracker.ItemStatus.HELD
        }

        BattleStateTracker.setItem(pokemonName, itemName, status)
    }
}

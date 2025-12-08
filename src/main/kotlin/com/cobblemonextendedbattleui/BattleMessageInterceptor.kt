package com.cobblemonextendedbattleui

import net.minecraft.text.Text
import net.minecraft.text.TranslatableTextContent

/**
 * Intercepts battle messages and updates BattleStateTracker.
 */
object BattleMessageInterceptor {

    private val BOOST_KEYS = mapOf(
        "cobblemon.battle.boost.slight" to 1,
        "cobblemon.battle.boost.sharp" to 2,
        "cobblemon.battle.boost.severe" to 3
    )

    private val UNBOOST_KEYS = mapOf(
        "cobblemon.battle.unboost.slight" to -1,
        "cobblemon.battle.unboost.sharp" to -2,
        "cobblemon.battle.unboost.severe" to -3
    )

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

            // Debug: Log all battle-related translation keys
            if (key.startsWith("cobblemon.battle.")) {
                CobblemonExtendedBattleUI.LOGGER.info("BattleMessage: key='$key', args=${args.map {
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

            BOOST_KEYS[key]?.let { stages ->
                extractStatChange(args, stages)
                return
            }

            UNBOOST_KEYS[key]?.let { stages ->
                extractStatChange(args, stages)
                return
            }

            if (key.startsWith("cobblemon.battle.boost.") && key.endsWith(".zeffect")) {
                val baseKey = key.removeSuffix(".zeffect")
                BOOST_KEYS[baseKey]?.let { stages ->
                    extractStatChange(args, stages)
                    return
                }
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

            // Handle faint - clear stats for the fainted Pokemon
            if (key == FAINT_KEY) {
                clearPokemonStats(args)
                return
            }

            // Handle switch/drag - clear stats for the Pokemon leaving battle
            if (key == SWITCH_KEY || key == DRAG_KEY) {
                clearPokemonStats(args)
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

    private fun extractStatChange(args: Array<out Any>, stages: Int) {
        if (args.size < 2) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Not enough args for stat change: ${args.size}")
            return
        }

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        val statName = when (val arg1 = args[1]) {
            is Text -> arg1.string
            is String -> arg1
            else -> arg1.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Stat change - $pokemonName $statName ${if (stages > 0) "+" else ""}$stages")
        BattleStateTracker.applyStatChange(pokemonName, statName, stages)
    }

    private fun clearPokemonStats(args: Array<out Any>) {
        if (args.isEmpty()) return

        val pokemonName = when (val arg0 = args[0]) {
            is Text -> arg0.string
            is String -> arg0
            else -> arg0.toString()
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleMessageInterceptor: Clearing stats for $pokemonName (faint/switch)")
        BattleStateTracker.clearPokemonStatsByName(pokemonName)
    }
}

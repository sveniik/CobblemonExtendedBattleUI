package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.Natures
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.api.pokemon.status.Statuses
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.api.types.tera.TeraType
import com.cobblemon.mod.common.api.types.tera.TeraTypes
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.drawScaledText
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Nature
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.joml.Quaternionf
import org.lwjgl.glfw.GLFW
import java.util.*
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

    // Opacity for minimized state (matches Cobblemon's BattleOverlay behavior)
    private const val MINIMISED_OPACITY = 0.5f
    private var isMinimised: Boolean = false

    /**
     * Applies the current opacity (minimized state) to a color's alpha channel.
     */
    private fun applyOpacity(color: Int): Int {
        if (!isMinimised) return color
        val a = ((color shr 24) and 0xFF)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val newA = (a * MINIMISED_OPACITY).toInt()
        return (newA shl 24) or (r shl 16) or (g shl 8) or b
    }

    // Track Pokemon as they're revealed in battle
    data class TrackedPokemon(
        val uuid: UUID,
        var hpPercent: Float,  // 0.0 to 1.0
        var status: Status?,
        var isKO: Boolean,
        // Display name (persists after switch-out)
        var displayName: String? = null,
        // For model rendering
        var speciesIdentifier: Identifier? = null,
        var aspects: Set<String> = emptySet(),
        // Original form tracking for Transform/Impostor (Ditto)
        var originalSpeciesIdentifier: Identifier? = null,
        var originalAspects: Set<String> = emptySet(),
        var isTransformed: Boolean = false,
        var form: FormData? = null,
        var teraType: TeraType? = null,
        val nature: Nature? = null
    )

    // Track Pokemon for both sides separately (for spectating and opponent tracking)
    private val trackedSide1Pokemon = ConcurrentHashMap<UUID, TrackedPokemon>()
    private val trackedSide2Pokemon = ConcurrentHashMap<UUID, TrackedPokemon>()

    // Persistent KO tracking - Pokemon removed from activePokemon after fainting
    // still need to show as KO'd in pokeball indicators
    private val knockedOutPokemon = ConcurrentHashMap.newKeySet<UUID>()

    // Pending transforms - queued when transform message arrives before Pokemon is tracked
    // (handles Impostor ability where transform happens immediately on switch-in)
    private val pendingTransforms = ConcurrentHashMap.newKeySet<UUID>()

    // Track which Pokemon were active last frame (for detecting disappeared/KO'd Pokemon)
    // Maps: isLeftSide -> Set of UUIDs that were in activePokemon
    private val previouslyActiveUuids = ConcurrentHashMap<Boolean, MutableSet<UUID>>()

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
     * Move information including PP (for player's Pokemon) or estimated (for opponent).
     */
    data class MoveInfo(
        val name: String,
        val currentPp: Int? = null,        // Exact PP for player's Pokemon
        val maxPp: Int? = null,            // Max PP for player's Pokemon
        val estimatedRemaining: Int? = null, // Estimated remaining PP (assumes PP Max)
        val estimatedMax: Int? = null,     // Estimated max PP (base * 8/5)
        val usageCount: Int? = null        // Times used (for unknown moves)
    )

    /**
     * Aggregated data for tooltip display.
     */
    data class TooltipData(
        val pokemonName: String,
        val pokemonId: Identifier?,
        val hpPercent: Float,
        val statusCondition: Status?,
        val isKO: Boolean,
        val moves: List<MoveInfo>,
        val item: BattleStateTracker.TrackedItem?,
        val statChanges: Map<BattleStateTracker.BattleStat, Int>,
        val volatileStatuses: Set<BattleStateTracker.VolatileStatusState>,
        val level: Int?,
        val speciesName: String?,
        val isPlayerPokemon: Boolean,
        val actualSpeed: Int? = null,
        val actualAttack: Int? = null,
        val actualSpecialAttack: Int? = null,
        val actualDefence: Int? = null,
        val actualSpecialDefence: Int? = null,
        val abilityName: String? = null,  // Known ability (player Pokemon or revealed)
        val possibleAbilities: List<String>? = null,  // Possible abilities if not yet revealed (opponent)
        val primaryType: ElementalType? = null,
        val secondaryType: ElementalType? = null,
        val teraType: TeraType? = null,
        val form: FormData ? = null,
        val nature: Nature ? = null
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
    private val TOOLTIP_DIM = color(100, 110, 120, 255)
    private val TOOLTIP_HP_HIGH = color(100, 220, 100, 255)
    private val TOOLTIP_HP_MED = color(220, 180, 50, 255)
    private val TOOLTIP_HP_LOW = color(220, 80, 80, 255)
    private val TOOLTIP_STAT_BOOST = color(100, 200, 100, 255)
    private val TOOLTIP_STAT_DROP = color(200, 100, 100, 255)
    private const val TOOLTIP_PADDING = 6
    private const val TOOLTIP_CORNER = 3
    private const val TOOLTIP_BASE_LINE_HEIGHT = 10  // Base line height before scaling
    private const val TOOLTIP_FONT_SCALE = 0.85f     // Base font scale multiplier
    private val TOOLTIP_SPEED = color(150, 180, 220, 255)
    private val TOOLTIP_DEFENSE = color(150, 220, 180, 255)
    private val TOOLTIP_SPECIAL_DEFENSE = color(180, 150, 220, 255)
    private val TOOLTIP_ATTACK = color(220, 150, 150, 255)
    private val TOOLTIP_SPECIAL_ATTACK = color(220, 180, 150, 255)
    private val TOOLTIP_PP = color(255, 210, 80, 255)
    private val TOOLTIP_PP_LOW = color(255, 100, 80, 255)
    private val TOOLTIP_ABILITY = color(200, 180, 255, 255)           // Purple for known ability
    private val TOOLTIP_ABILITY_POSSIBLE = color(160, 150, 180, 255)  // Dimmer purple for possible abilities

    // Ability ID -> Display Name lookup for compound words that can't be split programmatically
    private val ABILITY_DISPLAY_NAMES = mapOf(
        "earlybird" to "Early Bird",
        "flashfire" to "Flash Fire",
        "swiftswim" to "Swift Swim",
        "chlorophyll" to "Chlorophyll",
        "sandveil" to "Sand Veil",
        "sandrush" to "Sand Rush",
        "sandstream" to "Sand Stream",
        "sandforce" to "Sand Force",
        "slushrush" to "Slush Rush",
        "snowcloak" to "Snow Cloak",
        "snowwarning" to "Snow Warning",
        "icebody" to "Ice Body",
        "iceface" to "Ice Face",
        "icescales" to "Ice Scales",
        "dryskin" to "Dry Skin",
        "waterveil" to "Water Veil",
        "waterabsorb" to "Water Absorb",
        "waterbubble" to "Water Bubble",
        "raindish" to "Rain Dish",
        "drizzle" to "Drizzle",
        "drought" to "Drought",
        "solarpower" to "Solar Power",
        "moldbreaker" to "Mold Breaker",
        "toughclaws" to "Tough Claws",
        "strongjaw" to "Strong Jaw",
        "hugepower" to "Huge Power",
        "purepower" to "Pure Power",
        "hustle" to "Hustle",
        "guts" to "Guts",
        "sheerforce" to "Sheer Force",
        "ironfist" to "Iron Fist",
        "technician" to "Technician",
        "skilllink" to "Skill Link",
        "serenegrace" to "Serene Grace",
        "superluck" to "Super Luck",
        "sniper" to "Sniper",
        "infiltrator" to "Infiltrator",
        "scrappy" to "Scrappy",
        "noguard" to "No Guard",
        "compoundeyes" to "Compound Eyes",
        "keeneye" to "Keen Eye",
        "wonderguard" to "Wonder Guard",
        "multiscale" to "Multiscale",
        "solidrock" to "Solid Rock",
        "filter" to "Filter",
        "thickfat" to "Thick Fat",
        "furcoat" to "Fur Coat",
        "fluffy" to "Fluffy",
        "battlearmor" to "Battle Armor",
        "shellarmor" to "Shell Armor",
        "magicguard" to "Magic Guard",
        "magicbounce" to "Magic Bounce",
        "naturalcure" to "Natural Cure",
        "regenerator" to "Regenerator",
        "poisonheal" to "Poison Heal",
        "immunity" to "Immunity",
        "limber" to "Limber",
        "owntempo" to "Own Tempo",
        "innerfocus" to "Inner Focus",
        "steadfast" to "Steadfast",
        "contrary" to "Contrary",
        "defiant" to "Defiant",
        "competitive" to "Competitive",
        "speedboost" to "Speed Boost",
        "moxie" to "Moxie",
        "beastboost" to "Beast Boost",
        "intimidate" to "Intimidate",
        "pressure" to "Pressure",
        "unnerve" to "Unnerve",
        "unaware" to "Unaware",
        "oblivious" to "Oblivious",
        "trace" to "Trace",
        "imposter" to "Imposter",
        "prankster" to "Prankster",
        "protean" to "Protean",
        "libero" to "Libero",
        "levitate" to "Levitate",
        "sturdy" to "Sturdy",
        "anticipation" to "Anticipation",
        "forewarn" to "Forewarn",
        "frisk" to "Frisk",
        "pickup" to "Pickup",
        "harvest" to "Harvest",
        "runaway" to "Run Away",
        "quickfeet" to "Quick Feet",
        "unburden" to "Unburden",
        "shadowtag" to "Shadow Tag",
        "arenatrap" to "Arena Trap",
        "magnetpull" to "Magnet Pull",
        "stickyhold" to "Sticky Hold",
        "suctioncups" to "Suction Cups"
    )

    /**
     * Format an ability ID into a proper display name.
     * Uses lookup table for compound words, falls back to formatting for unknown abilities.
     */
    private fun formatAbilityName(abilityId: String): String {
        // Check lookup first
        ABILITY_DISPLAY_NAMES[abilityId.lowercase()]?.let { return it }

        // Fallback: handle underscore/camelCase separation
        return abilityId
            .replace("_", " ")
            .split(Regex("(?=[A-Z])|\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                when (word.lowercase()) {
                    "hp" -> "HP"
                    "pp" -> "PP"
                    else -> word.replaceFirstChar { it.uppercase() }
                }
            }
    }

    // ================== Stat Calculation Utilities ==================

    /**
     * Get the stat stage multiplier for a given stage (-6 to +6).
     * Uses the standard Pokemon formula: (2 + stage) / 2 for positive, 2 / (2 - stage) for negative.
     */
    private fun getStageMultiplier(stage: Int): Double {
        return if (stage >= 0) {
            (2.0 + stage) / 2.0
        } else {
            2.0 / (2.0 - stage)
        }
    }

    /**
     * Calculate a stat value using the standard Pokemon formula.
     * stat = floor((floor((2 * base + iv + floor(ev/4)) * level / 100) + 5) * nature)
     */
    private fun calculateStat(base: Int, level: Int, iv: Int, ev: Int, natureMod: Double): Int {
        val inner = ((2 * base + iv + ev / 4) * level / 100) + 5
        return (inner * natureMod).toInt()
    }

    // ================== Speed Modifier Utilities ==================

    /**
     * Normalize ability name for comparison (lowercase, no spaces/underscores).
     */
    private fun normalizeAbilityName(name: String?): String? =
        name?.lowercase()?.replace(" ", "")?.replace("_", "")

    /**
     * Weather-based speed-doubling abilities.
     */
    private val WEATHER_SPEED_ABILITIES = mapOf(
        "chlorophyll" to BattleStateTracker.Weather.SUN,
        "swiftswim" to BattleStateTracker.Weather.RAIN,
        "sandrush" to BattleStateTracker.Weather.SANDSTORM,
        "slushrush" to BattleStateTracker.Weather.SNOW
    )

    /**
     * Terrain-based speed abilities.
     */
    private val TERRAIN_SPEED_ABILITIES = mapOf(
        "surgesurfer" to BattleStateTracker.Terrain.ELECTRIC
    )

    /**
     * Get speed multiplier from ability given current battle conditions.
     * Returns 1.0 if ability doesn't affect speed or conditions aren't met.
     */
    private fun getAbilitySpeedMultiplier(
        abilityName: String?,
        weather: BattleStateTracker.Weather?,
        terrain: BattleStateTracker.Terrain?,
        hasStatus: Boolean,
        itemConsumed: Boolean
    ): Double {
        val normalizedAbility = normalizeAbilityName(abilityName) ?: return 1.0

        // Weather-based abilities (2x)
        WEATHER_SPEED_ABILITIES[normalizedAbility]?.let { requiredWeather ->
            // Handle Slush Rush which works in both Snow and Hail
            if (normalizedAbility == "slushrush") {
                if (weather == BattleStateTracker.Weather.SNOW || weather == BattleStateTracker.Weather.HAIL) {
                    return 2.0
                }
            } else if (weather == requiredWeather) {
                return 2.0
            }
        }

        // Terrain-based abilities (2x)
        TERRAIN_SPEED_ABILITIES[normalizedAbility]?.let { requiredTerrain ->
            if (terrain == requiredTerrain) return 2.0
        }

        // Quick Feet (1.5x when statused, also ignores paralysis speed drop)
        if (normalizedAbility == "quickfeet" && hasStatus) return 1.5

        // Unburden (2x after item consumed)
        if (normalizedAbility == "unburden" && itemConsumed) return 2.0

        return 1.0
    }

    /**
     * Get speed multiplier from status condition.
     * Quick Feet negates paralysis speed penalty.
     */
    private fun getStatusSpeedMultiplier(status: Status?, abilityName: String?): Double {
        if (status == Statuses.PARALYSIS) {
            // Quick Feet ignores paralysis speed penalty
            if (normalizeAbilityName(abilityName) == "quickfeet") return 1.0
            return 0.5
        }
        return 1.0
    }

    /**
     * Get speed multiplier from held item.
     */
    private fun getItemSpeedMultiplier(itemName: String?): Double {
        if (itemName == null) return 1.0
        val normalizedItem = itemName.lowercase().replace(" ", "").replace("_", "")
        return when (normalizedItem) {
            "choicescarf" -> 1.5
            "ironball" -> 0.5
            else -> 1.0
        }
    }

    /**
     * Check if species can have any speed-boosting ability that's currently active.
     * Returns the max multiplier if any ability could apply, 1.0 otherwise.
     */
    private fun getMaxPossibleAbilitySpeedMultiplier(
        pokemonId: Identifier,
        weather: BattleStateTracker.Weather?,
        terrain: BattleStateTracker.Terrain?,
        hasStatus: Boolean,
        itemConsumed: Boolean
    ): Double {
        val species = PokemonSpecies.getByIdentifier(pokemonId) ?: return 1.0
        var maxMultiplier = 1.0

        // Get all possible abilities for this species
        val possibleAbilities = species.abilities.mapNotNull { normalizeAbilityName(it.template.name) }

        for (ability in possibleAbilities) {
            val mult = getAbilitySpeedMultiplier(ability, weather, terrain, hasStatus, itemConsumed)
            if (mult > maxMultiplier) maxMultiplier = mult
        }

        return maxMultiplier
    }

    /**
     * Calculate effective speed for player's Pokemon with all modifiers.
     */
    private fun calculateEffectiveSpeed(
        baseSpeed: Int,
        speedStage: Int,
        abilityName: String?,
        status: Status?,
        itemName: String?,
        itemConsumed: Boolean
    ): Int {
        val weather = BattleStateTracker.weather?.type
        val terrain = BattleStateTracker.terrain?.type
        val hasStatus = status != null

        val stageMultiplier = getStageMultiplier(speedStage)
        val abilityMultiplier = getAbilitySpeedMultiplier(abilityName, weather, terrain, hasStatus, itemConsumed)
        val statusMultiplier = getStatusSpeedMultiplier(status, abilityName)
        val itemMultiplier = if (!itemConsumed) getItemSpeedMultiplier(itemName) else 1.0

        return (baseSpeed * stageMultiplier * abilityMultiplier * statusMultiplier * itemMultiplier).toInt()
    }

    /**
     * Result of opponent speed range calculation.
     */
    data class SpeedRangeResult(
        val minSpeed: Int,
        val maxSpeed: Int,
        val abilityNote: String? = null  // Note if ability could boost speed
    )
    data class RangeResult(
        val min: Int,
        val max: Int,
        val ability: String? = null  // Note if ability could boost speed
    )

    /**
     * Calculate speed range for opponent with all modifiers considered.
     * Accounts for possible abilities that could boost speed in current conditions.
     */
    private fun calculateOpponentSpeedRange(
        pokemonId: Identifier,
        level: Int,
        speedStage: Int,
        status: Status?,
        knownItem: BattleStateTracker.TrackedItem?,
        form: FormData?
    ): SpeedRangeResult? {
        val species = PokemonSpecies.getByIdentifier(pokemonId) ?: return null
        val baseSpeed = if (form != null) form.baseStats[Stats.SPEED] ?: return null
        else species.baseStats[Stats.SPEED] ?: return null

        val weather = BattleStateTracker.weather?.type
        val terrain = BattleStateTracker.terrain?.type
        val hasStatus = status != null
        val itemConsumed = knownItem?.status == BattleStateTracker.ItemStatus.CONSUMED

        // Calculate base stat range (0 IV/0 EV/- nature to 31 IV/252 EV/+ nature)
        val minBaseStat = calculateStat(baseSpeed, level, 0, 0, 0.9)
        val maxBaseStat = calculateStat(baseSpeed, level, 31, 252, 1.1)

        val stageMultiplier = getStageMultiplier(speedStage)
        val statusMultiplier = getStatusSpeedMultiplier(status, null)  // Conservative: assume no Quick Feet for min

        // For min speed: assume worst case (no ability boost, paralysis penalty if paralyzed)
        val minSpeed = (minBaseStat * stageMultiplier * statusMultiplier).toInt()

        // For max speed: check if any ability could boost speed
        val maxAbilityMultiplier =
            getMaxPossibleAbilitySpeedMultiplier(pokemonId, weather, terrain, hasStatus, itemConsumed)

        // For paralysis: Quick Feet could negate the penalty AND give 1.5x
        val maxStatusMultiplier = if (status == Statuses.PARALYSIS) {
            // Check if Quick Feet is possible
            val possibleAbilities = species.abilities.mapNotNull { normalizeAbilityName(it.template.name) }
            if ("quickfeet" in possibleAbilities) 1.0 else statusMultiplier
        } else {
            1.0
        }

        val maxSpeed =
            (maxBaseStat * stageMultiplier * maxAbilityMultiplier.coerceAtLeast(1.0) * maxStatusMultiplier).toInt()

        // Generate ability note if conditions apply
        val abilityNote = when {
            maxAbilityMultiplier > 1.0 -> {
                val activeConditions = mutableListOf<String>()
                val normalizedAbilities = species.abilities.mapNotNull { normalizeAbilityName(it.template.name) }
                if (weather == BattleStateTracker.Weather.SUN && "chlorophyll" in normalizedAbilities) {
                    activeConditions.add("Chlorophyll")
                }
                if (weather == BattleStateTracker.Weather.RAIN && "swiftswim" in normalizedAbilities) {
                    activeConditions.add("Swift Swim")
                }
                if (weather == BattleStateTracker.Weather.SANDSTORM && "sandrush" in normalizedAbilities) {
                    activeConditions.add("Sand Rush")
                }
                if ((weather == BattleStateTracker.Weather.SNOW || weather == BattleStateTracker.Weather.HAIL) && "slushrush" in normalizedAbilities) {
                    activeConditions.add("Slush Rush")
                }
                if (terrain == BattleStateTracker.Terrain.ELECTRIC && "surgesurfer" in normalizedAbilities) {
                    activeConditions.add("Surge Surfer")
                }
                if (activeConditions.isNotEmpty()) activeConditions.joinToString("/") + "?" else null
            }

            else -> null
        }

        return SpeedRangeResult(minSpeed, maxSpeed, abilityNote)
    }

    /**
     * Clear tracking when battle ends.
     */
    fun clear() {
        trackedSide1Pokemon.clear()
        trackedSide2Pokemon.clear()
        knockedOutPokemon.clear()
        pendingTransforms.clear()
        previouslyActiveUuids.clear()
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

    /**
     * Mark a Pokemon as transformed (Ditto via Transform/Impostor).
     * Saves the original species so it can be restored when the Pokemon faints.
     * If the Pokemon isn't tracked yet (Impostor triggers on switch-in), queues for later processing.
     */
    fun markPokemonAsTransformed(pokemonName: String) {
        val uuid = BattleStateTracker.getPokemonUuid(pokemonName) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("TeamIndicatorUI: Unknown Pokemon '$pokemonName' for transform tracking")
            return
        }

        // Find and update the tracked Pokemon
        val tracked = trackedSide1Pokemon[uuid] ?: trackedSide2Pokemon[uuid]
        if (tracked != null) {
            applyTransformToTracked(tracked, pokemonName)
        } else {
            // Pokemon not tracked yet - queue for when it gets added
            // This handles Impostor ability where transform message arrives before tracking
            pendingTransforms.add(uuid)
            CobblemonExtendedBattleUI.LOGGER.debug(
                "TeamIndicatorUI: Queued pending transform for $pokemonName (UUID: $uuid)"
            )
        }
    }

    /**
     * Apply transform status to a tracked Pokemon, saving its original form.
     */
    private fun applyTransformToTracked(tracked: TrackedPokemon, debugName: String) {
        // Only save if not already transformed (first transformation)
        if (!tracked.isTransformed) {
            tracked.originalSpeciesIdentifier = tracked.speciesIdentifier
            tracked.originalAspects = tracked.aspects
            tracked.isTransformed = true
            CobblemonExtendedBattleUI.LOGGER.debug(
                "TeamIndicatorUI: Saved original form for $debugName: ${tracked.originalSpeciesIdentifier}"
            )
        }
    }

    /**
     * Clear transform status for a Pokemon (on switch-out).
     * The Pokemon returns to its original form when it leaves the field.
     */
    fun clearTransformStatus(pokemonName: String) {
        val uuid = BattleStateTracker.getPokemonUuid(pokemonName) ?: return

        // Remove from pending if queued
        pendingTransforms.remove(uuid)

        // Clear transform status in tracked Pokemon
        val tracked = trackedSide1Pokemon[uuid] ?: trackedSide2Pokemon[uuid]
        if (tracked != null && tracked.isTransformed) {
            tracked.isTransformed = false
            // Don't clear originalSpeciesIdentifier - it's still the true original
            CobblemonExtendedBattleUI.LOGGER.debug(
                "TeamIndicatorUI: Cleared transform status for $pokemonName"
            )
        }
    }

    fun render(context: DrawContext) {
        val battle = CobblemonClient.battle ?: return

        // Track minimized state - render greyed out instead of hiding
        isMinimised = battle.minimised

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
                "TeamIndicatorUI: Spectating - battle.side1: $side1Names, battle.side2: $side2Names (side2 on LEFT)"
            )
        }

        // Cobblemon's BattleOverlay swaps sides based on player presence:
        // - If player is in side1: side1 is LEFT, side2 is RIGHT
        // - If player is in side2: side2 is LEFT, side1 is RIGHT
        // - If spectating: side2 is LEFT, side1 is RIGHT
        // We must match this positioning for team preview to align with the battle tiles.
        val leftSide = when {
            playerInSide1 -> battle.side1
            playerInSide2 -> battle.side2
            else -> battle.side2  // When spectating, side2 is on LEFT
        }
        val rightSide = if (leftSide == battle.side1) battle.side2 else battle.side1

        // Update tracked Pokemon for both sides from battle data
        updateTrackedPokemonForSide(leftSide, trackedSide1Pokemon, isLeftSide = true)
        updateTrackedPokemonForSide(rightSide, trackedSide2Pokemon, isLeftSide = false)

        // Count active Pokemon for positioning (determines how many tiles are shown)
        val leftActiveCount = leftSide.actors.sumOf { it.activePokemon.size }
        val rightActiveCount = rightSide.actors.sumOf { it.activePokemon.size }

        val leftY = calculateIndicatorY(leftActiveCount)
        val rightY = calculateIndicatorY(rightActiveCount)

        // Find the player's actor if they're in the battle
        val playerActor = battle.side1.actors.find { it.uuid == playerUUID }
            ?: battle.side2.actors.find { it.uuid == playerUUID }

        // Initialize PP tracking for all player's Pokemon (idempotent - only initializes once)
        playerActor?.pokemon?.forEach { pokemon ->
            BattleStateTracker.initializeMoves(
                pokemon.uuid,
                pokemon.moveSet.getMoves().map {
                    BattleStateTracker.TrackedMove(it.displayName.string, it.currentPp, it.maxPp)
                }
            )
        }

        // Determine if player is on the left or right side
        val playerOnLeft = playerActor != null && leftSide.actors.any { it.uuid == playerUUID }
        val playerOnRight = playerActor != null && rightSide.actors.any { it.uuid == playerUUID }

        // Render LEFT side - player's team if they're on left, otherwise tracked
        if (playerOnLeft) {
            // Player is on left - use battle actor's pokemon list for authoritative data
            val playerTeam = playerActor!!.pokemon
            renderBattleTeam(context, HORIZONTAL_INSET, leftY, playerTeam, isLeftSide = true)
        } else {
            // Left side is opponent or we're spectating - use tracked Pokemon from battle data
            val leftTeam = trackedSide1Pokemon.values.toList()
            if (leftTeam.isNotEmpty()) {
                renderTrackedTeam(context, HORIZONTAL_INSET, leftY, leftTeam, isLeftSide = true)
            }
        }

        // Render RIGHT side - player's team if they're on right, otherwise tracked
        if (playerOnRight) {
            // Player is on right - use battle actor's pokemon list for authoritative data
            val playerTeam = playerActor!!.pokemon
            val rightWidth = playerTeam.size * (MODEL_SIZE + MODEL_SPACING) - MODEL_SPACING
            renderBattleTeam(
                context,
                screenWidth - HORIZONTAL_INSET - rightWidth,
                rightY,
                playerTeam,
                isLeftSide = false
            )
        } else {
            // Right side is opponent or we're spectating - use tracked Pokemon from battle data
            val rightTeam = trackedSide2Pokemon.values.toList()
            if (rightTeam.isNotEmpty()) {
                val rightWidth = rightTeam.size * (MODEL_SIZE + MODEL_SPACING) - MODEL_SPACING
                renderTrackedTeam(
                    context,
                    screenWidth - HORIZONTAL_INSET - rightWidth,
                    rightY,
                    rightTeam,
                    isLeftSide = false
                )
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
                mouseY >= bounds.y && mouseY <= bounds.y + bounds.height
            ) {
                return true
            }
        }
        rightTeamPanelBounds?.let { bounds ->
            if (mouseX >= bounds.x && mouseX <= bounds.x + bounds.width &&
                mouseY >= bounds.y && mouseY <= bounds.y + bounds.height
            ) {
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
     * Also detects Pokemon that disappeared from activePokemon without a switch message,
     * indicating they were KO'd (e.g., by Perish Song, Memento, Explosion).
     */
    private fun updateTrackedPokemonForSide(
        side: ClientBattleSide,
        tracked: ConcurrentHashMap<UUID, TrackedPokemon>,
        isLeftSide: Boolean
    ) {
        val currentlyActiveUuids = mutableSetOf<UUID>()

        for (actor in side.actors) {
            for (activePokemon in actor.activePokemon) {
                val battlePokemon = activePokemon.battlePokemon ?: continue
                currentlyActiveUuids.add(battlePokemon.uuid)
                updateTrackedPokemonInMap(battlePokemon, tracked)
            }
        }

        // Check for Pokemon that were active last frame but aren't anymore
        val previousActive = previouslyActiveUuids[isLeftSide]
        if (previousActive != null) {
            // Key insight: if a Pokemon left AND a new Pokemon appeared on this side,
            // it was a switch (or faint + replacement). If a Pokemon left but no new
            // one appeared, it was a KO with no replacement available.
            //
            // We do NOT check switchMessageReceived here because of a race condition:
            // the battle state (activePokemon) can update before the switch message is processed.
            // The presence of a new Pokemon on the same side is sufficient evidence of a switch.
            val newPokemonAppearedOnThisSide = currentlyActiveUuids.any { it !in previousActive }

            for (uuid in previousActive) {
                if (uuid !in currentlyActiveUuids) {
                    // Pokemon left the active slot on this side
                    if (newPokemonAppearedOnThisSide) {
                        // A new Pokemon appeared on this side - the old one was replaced
                        // Don't auto-mark as KO; if it fainted, the faint message handles it
                        CobblemonExtendedBattleUI.LOGGER.debug(
                            "TeamIndicatorUI: Pokemon $uuid left active (replaced by new Pokemon on ${if (isLeftSide) "left" else "right"} side)"
                        )
                    } else if (!isPokemonKO(uuid)) {
                        // No new Pokemon appeared - this was a KO with no replacement
                        // (last Pokemon fainted, or self-KO move like Explosion/Memento)
                        val pokemon = tracked[uuid]
                        if (pokemon != null && !pokemon.isKO) {
                            CobblemonExtendedBattleUI.LOGGER.debug(
                                "TeamIndicatorUI: Pokemon $uuid disappeared with no replacement - marking as KO"
                            )
                            markPokemonAsKO(uuid)
                        }
                    }
                }
            }
        }

        // Update tracking for next frame
        previouslyActiveUuids[isLeftSide] = currentlyActiveUuids
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
     * Processes pending transforms for Impostor ability (transform before tracking).
     */
    private fun updateTrackedPokemonInMap(
        battlePokemon: ClientBattlePokemon,
        targetMap: ConcurrentHashMap<UUID, TrackedPokemon>
    ) {
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
        val displayName = battlePokemon.displayName.string
        val form = battlePokemon.species.getForm(aspects)
        val teraType = battlePokemon.properties.teraType?.let { TeraTypes.get(it) };

        // If HP is 0, add to persistent KO tracking
        // This catches KO status even if faint message hasn't arrived yet
        if (isKO) {
            knockedOutPokemon.add(uuid)
        }

        // Check if this Pokemon has a pending transform (Impostor triggered before tracking)
        val hasPendingTransform = pendingTransforms.remove(uuid)

        targetMap.compute(uuid) { _, existing ->
            if (existing != null) {
                // Update existing - also check persistent KO set
                existing.hpPercent = hpPercent
                existing.status = status
                existing.isKO = isKO || knockedOutPokemon.contains(uuid)
                // Update display name if we have one (persist the name)
                if (displayName.isNotEmpty()) existing.displayName = displayName
                // Always update the current species (for transformed Pokemon, this shows the transformed form)
                // The original form is tracked separately in originalSpeciesIdentifier
                existing.speciesIdentifier = speciesId ?: existing.speciesIdentifier
                existing.aspects = aspects.ifEmpty { existing.aspects }
                existing
            } else {
                // New Pokemon revealed
                // Check if transform was queued before we could track this Pokemon (Impostor ability)
                val isTransformed = hasPendingTransform
                // If pending transform, the current species is already transformed - original was Ditto
                val originalSpecies = if (isTransformed) DITTO_SPECIES_ID else speciesId
                val originalAspects = if (isTransformed) emptySet() else aspects

                if (isTransformed) {
                    CobblemonExtendedBattleUI.LOGGER.debug(
                        "TeamIndicatorUI: Processing pending transform for UUID $uuid - original species: ditto, current: $speciesName"
                    )
                }

                TrackedPokemon(
                    uuid = uuid,
                    hpPercent = hpPercent,
                    status = status,
                    isKO = isKO || knockedOutPokemon.contains(uuid),
                    displayName = displayName.ifEmpty { null },
                    speciesIdentifier = speciesId,
                    aspects = aspects,
                    originalSpeciesIdentifier = originalSpecies,
                    originalAspects = originalAspects,
                    isTransformed = isTransformed,
                    form = form,
                    teraType = teraType,
                    nature = battlePokemon.properties.nature?.let { Natures.getNature(it) }
                )
            }
        }
    }

    // Ditto species identifier for transform reversion
    private val DITTO_SPECIES_ID = Identifier.of("cobblemon", "ditto")

    /**
     * Draw a background panel behind the team's Pokemon models.
     */
    private fun drawTeamPanel(context: DrawContext, x: Int, y: Int, teamSize: Int) {
        if (teamSize <= 0) return

        val panelWidth = teamSize * MODEL_SIZE + (teamSize - 1) * MODEL_SPACING + PANEL_PADDING_H * 2
        val panelHeight = MODEL_SIZE + PANEL_PADDING_V * 2

        val panelX = x - PANEL_PADDING_H
        val panelY = y - PANEL_PADDING_V

        // Apply opacity for minimized state
        val bg = applyOpacity(PANEL_BG)
        val border = applyOpacity(PANEL_BORDER)

        // Draw main background (cross pattern for rounded corners)
        context.fill(panelX + PANEL_CORNER, panelY, panelX + panelWidth - PANEL_CORNER, panelY + panelHeight, bg)
        context.fill(panelX, panelY + PANEL_CORNER, panelX + panelWidth, panelY + panelHeight - PANEL_CORNER, bg)

        // Fill corners with graduated rounding (creates smooth curve)
        // Top-left corner
        context.fill(panelX + 2, panelY + 1, panelX + PANEL_CORNER, panelY + 2, bg)
        context.fill(panelX + 1, panelY + 2, panelX + PANEL_CORNER, panelY + PANEL_CORNER, bg)
        // Top-right corner
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + 1, panelX + panelWidth - 2, panelY + 2, bg)
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY + 2, panelX + panelWidth - 1, panelY + PANEL_CORNER, bg)
        // Bottom-left corner
        context.fill(panelX + 2, panelY + panelHeight - 2, panelX + PANEL_CORNER, panelY + panelHeight - 1, bg)
        context.fill(
            panelX + 1,
            panelY + panelHeight - PANEL_CORNER,
            panelX + PANEL_CORNER,
            panelY + panelHeight - 2,
            bg
        )
        // Bottom-right corner
        context.fill(
            panelX + panelWidth - PANEL_CORNER,
            panelY + panelHeight - 2,
            panelX + panelWidth - 2,
            panelY + panelHeight - 1,
            bg
        )
        context.fill(
            panelX + panelWidth - PANEL_CORNER,
            panelY + panelHeight - PANEL_CORNER,
            panelX + panelWidth - 1,
            panelY + panelHeight - 2,
            bg
        )

        // Draw border - top
        context.fill(panelX + PANEL_CORNER, panelY, panelX + panelWidth - PANEL_CORNER, panelY + 1, border)
        // Draw border - bottom
        context.fill(
            panelX + PANEL_CORNER,
            panelY + panelHeight - 1,
            panelX + panelWidth - PANEL_CORNER,
            panelY + panelHeight,
            border
        )
        // Draw border - left
        context.fill(panelX, panelY + PANEL_CORNER, panelX + 1, panelY + panelHeight - PANEL_CORNER, border)
        // Draw border - right
        context.fill(
            panelX + panelWidth - 1,
            panelY + PANEL_CORNER,
            panelX + panelWidth,
            panelY + panelHeight - PANEL_CORNER,
            border
        )

        // Draw rounded corner borders (curved edges)
        // Top-left
        context.fill(panelX + 2, panelY, panelX + PANEL_CORNER, panelY + 1, border)
        context.fill(panelX + 1, panelY + 1, panelX + 2, panelY + 2, border)
        context.fill(panelX, panelY + 2, panelX + 1, panelY + PANEL_CORNER, border)
        // Top-right
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY, panelX + panelWidth - 2, panelY + 1, border)
        context.fill(panelX + panelWidth - 2, panelY + 1, panelX + panelWidth - 1, panelY + 2, border)
        context.fill(panelX + panelWidth - 1, panelY + 2, panelX + panelWidth, panelY + PANEL_CORNER, border)
        // Bottom-left
        context.fill(panelX + 2, panelY + panelHeight - 1, panelX + PANEL_CORNER, panelY + panelHeight, border)
        context.fill(panelX + 1, panelY + panelHeight - 2, panelX + 2, panelY + panelHeight - 1, border)
        context.fill(panelX, panelY + panelHeight - PANEL_CORNER, panelX + 1, panelY + panelHeight - 2, border)
        // Bottom-right
        context.fill(
            panelX + panelWidth - PANEL_CORNER,
            panelY + panelHeight - 1,
            panelX + panelWidth - 2,
            panelY + panelHeight,
            border
        )
        context.fill(
            panelX + panelWidth - 2,
            panelY + panelHeight - 2,
            panelX + panelWidth - 1,
            panelY + panelHeight - 1,
            border
        )
        context.fill(
            panelX + panelWidth - 1,
            panelY + panelHeight - PANEL_CORNER,
            panelX + panelWidth,
            panelY + panelHeight - 2,
            border
        )
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

        // Apply opacity for minimized state
        val border = applyOpacity(PANEL_BORDER)

        val matrices = context.matrices
        matrices.push()
        // Push z-level forward to render on top of 3D models
        matrices.translate(0.0, 0.0, 200.0)

        // Redraw rounded corner borders to overlay any model bleed
        // Top-left
        context.fill(panelX + 2, panelY, panelX + PANEL_CORNER, panelY + 1, border)
        context.fill(panelX + 1, panelY + 1, panelX + 2, panelY + 2, border)
        context.fill(panelX, panelY + 2, panelX + 1, panelY + PANEL_CORNER, border)
        // Top-right
        context.fill(panelX + panelWidth - PANEL_CORNER, panelY, panelX + panelWidth - 2, panelY + 1, border)
        context.fill(panelX + panelWidth - 2, panelY + 1, panelX + panelWidth - 1, panelY + 2, border)
        context.fill(panelX + panelWidth - 1, panelY + 2, panelX + panelWidth, panelY + PANEL_CORNER, border)
        // Bottom-left
        context.fill(panelX + 2, panelY + panelHeight - 1, panelX + PANEL_CORNER, panelY + panelHeight, border)
        context.fill(panelX + 1, panelY + panelHeight - 2, panelX + 2, panelY + panelHeight - 1, border)
        context.fill(panelX, panelY + panelHeight - PANEL_CORNER, panelX + 1, panelY + panelHeight - 2, border)
        // Bottom-right
        context.fill(
            panelX + panelWidth - PANEL_CORNER,
            panelY + panelHeight - 1,
            panelX + panelWidth - 2,
            panelY + panelHeight,
            border
        )
        context.fill(
            panelX + panelWidth - 2,
            panelY + panelHeight - 2,
            panelX + panelWidth - 1,
            panelY + panelHeight - 1,
            border
        )
        context.fill(
            panelX + panelWidth - 1,
            panelY + panelHeight - PANEL_CORNER,
            panelX + panelWidth,
            panelY + panelHeight - 2,
            border
        )

        matrices.pop()
    }

    /**
     * Render a team using battle actor's pokemon list.
     * This uses authoritative battle data which works correctly on servers.
     * Also checks persistent KO tracking as a fallback for race conditions.
     */
    private fun renderBattleTeam(
        context: DrawContext,
        startX: Int,
        startY: Int,
        team: List<Pokemon>,
        isLeftSide: Boolean
    ) {
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
            pokeballBounds.add(
                PokeballBounds(
                    x,
                    startY,
                    MODEL_SIZE,
                    MODEL_SIZE,
                    pokemon.uuid,
                    isLeftSide,
                    isPlayerPokemon = true
                )
            )

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
    private fun renderTrackedTeam(
        context: DrawContext,
        startX: Int,
        startY: Int,
        team: List<TrackedPokemon>,
        isLeftSide: Boolean
    ) {
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

            // If Pokemon is KO'd and was transformed, revert to original form
            val displaySpecies: Identifier?
            val displayAspects: Set<String>
            if (isKO && pokemon.isTransformed && pokemon.originalSpeciesIdentifier != null) {
                displaySpecies = pokemon.originalSpeciesIdentifier
                displayAspects = pokemon.originalAspects
            } else {
                displaySpecies = pokemon.speciesIdentifier
                displayAspects = pokemon.aspects
            }

            drawPokemonModel(
                context = context,
                x = x,
                y = startY,
                renderablePokemon = null,
                speciesIdentifier = displaySpecies,
                aspects = displayAspects,
                uuid = pokemon.uuid,
                isKO = isKO,
                status = pokemon.status,
                isLeftSide = isLeftSide
            )

            // Store bounds for hover detection (opponent or spectated team)
            pokeballBounds.add(
                PokeballBounds(
                    x,
                    startY,
                    MODEL_SIZE,
                    MODEL_SIZE,
                    pokemon.uuid,
                    isLeftSide,
                    isPlayerPokemon = false
                )
            )

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

    private fun drawPokeball(
        context: DrawContext,
        x: Int,
        y: Int,
        topColor: Int,
        bottomColor: Int,
        bandColor: Int,
        centerColor: Int
    ) {
        val halfSize = BALL_SIZE / 2
        val centerSize = 4
        val centerOffset = (BALL_SIZE - centerSize) / 2

        // Apply opacity for minimized state
        val top = applyOpacity(topColor)
        val bottom = applyOpacity(bottomColor)
        val band = applyOpacity(bandColor)
        val center = applyOpacity(centerColor)

        // Top half (status/normal color)
        context.fill(x + 1, y, x + BALL_SIZE - 1, y + halfSize, top)
        context.fill(x, y + 1, x + BALL_SIZE, y + halfSize, top)

        // Bottom half (white/gray)
        context.fill(x + 1, y + halfSize, x + BALL_SIZE - 1, y + BALL_SIZE, bottom)
        context.fill(x, y + halfSize, x + BALL_SIZE, y + BALL_SIZE - 1, bottom)

        // Center band
        context.fill(x, y + halfSize - 1, x + BALL_SIZE, y + halfSize + 1, band)

        // Center button
        context.fill(
            x + centerOffset,
            y + centerOffset,
            x + centerOffset + centerSize,
            y + centerOffset + centerSize,
            center
        )
        // Button outline
        context.fill(x + centerOffset, y + centerOffset, x + centerOffset + centerSize, y + centerOffset + 1, band)
        context.fill(
            x + centerOffset,
            y + centerOffset + centerSize - 1,
            x + centerOffset + centerSize,
            y + centerOffset + centerSize,
            band
        )
        context.fill(x + centerOffset, y + centerOffset, x + centerOffset + 1, y + centerOffset + centerSize, band)
        context.fill(
            x + centerOffset + centerSize - 1,
            y + centerOffset,
            x + centerOffset + centerSize,
            y + centerOffset + centerSize,
            band
        )
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

    private fun getBattlePokemonByUuid(
        uuid: UUID,
        battle: com.cobblemon.mod.common.client.battle.ClientBattle
    ): Pokemon? {
        for (side in listOf(battle.side1, battle.side2)) {
            for (actor in side.actors) {
                actor.pokemon.find { it.uuid == uuid }?.let { return it }
            }
        }
        return null
    }

    /**
     * Get ClientBattlePokemon by UUID (used to access level and species from properties).
     */
    private fun getClientBattlePokemonByUuid(uuid: UUID): ClientBattlePokemon? {
        val battle = CobblemonClient.battle ?: return null
        for (side in listOf(battle.side1, battle.side2)) {
            for (actor in side.actors) {
                for (active in actor.activePokemon) {
                    active.battlePokemon?.let {
                        if (it.uuid == uuid) return it
                    }
                }
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

    private fun getTooltipData(
        uuid: UUID,
        trackedPokemon: TrackedPokemon?,
        battlePokemon: Pokemon?,
        isPlayerPokemon: Boolean
    ): TooltipData {
        val pokemonId = trackedPokemon?.speciesIdentifier
            ?: battlePokemon?.species?.resourceIdentifier
        val name = battlePokemon?.getDisplayName()?.string
            ?: getPokemonNameFromUuid(uuid)
            ?: trackedPokemon?.displayName
            ?: "Unknown"

        val hpPercent = trackedPokemon?.hpPercent
            ?: battlePokemon?.let {
                if (it.maxHealth > 0) it.currentHealth.toFloat() / it.maxHealth else 0f
            }
            ?: 0f

        // Get ClientBattlePokemon for level and species info
        val clientBattlePokemon = getClientBattlePokemonByUuid(uuid)

        // Get level: from ClientBattlePokemon properties, or from Pokemon object for player's own
        val level: Int? = clientBattlePokemon?.properties?.level
            ?: battlePokemon?.level

        // Get species name: from tracked data, ClientBattlePokemon, or Pokemon object
        val speciesName: String? = trackedPokemon?.speciesIdentifier?.path
            ?: clientBattlePokemon?.properties?.species
            ?: battlePokemon?.species?.name

        // For player's own Pokemon, show ALL moves and item directly from Pokemon data
        // For opponent/spectated Pokemon, only show revealed moves and tracked items
        val moves: List<MoveInfo>
        val item: BattleStateTracker.TrackedItem?
        val actualSpeed: Int?
        val actualDefence: Int?
        val actualSpecialDefence: Int?
        val actualAttack: Int?
        val actualSpecialAttack: Int?
        val abilityName: String?
        val possibleAbilities: List<String>?

        if (isPlayerPokemon && battlePokemon != null) {
            // Use tracked PP (which updates when moves are used) - initialized in render()
            val trackedMoves = BattleStateTracker.getTrackedMoves(uuid)
            moves = if (trackedMoves != null) {
                trackedMoves.map { MoveInfo(it.name, currentPp = it.currentPp, maxPp = it.maxPp) }
            } else {
                // Fallback to Pokemon data if tracking not initialized
                battlePokemon.moveSet.getMoves().map {
                    MoveInfo(it.displayName.string, currentPp = it.currentPp, maxPp = it.maxPp)
                }
            }
            // Player's Pokemon: show actual held item (if any)
            val heldItem = battlePokemon.heldItem()
            item = if (!heldItem.isEmpty) {
                // Check if we have tracked info about this item being consumed/knocked off
                val trackedItem = BattleStateTracker.getItem(uuid)
                if (trackedItem != null && trackedItem.status != BattleStateTracker.ItemStatus.HELD) {
                    trackedItem  // Use tracked status if item was consumed/knocked off
                } else {
                    BattleStateTracker.TrackedItem(
                        heldItem.name.string,
                        BattleStateTracker.ItemStatus.HELD,
                        BattleStateTracker.currentTurn
                    )
                }
            } else {
                // No item held - check if one was consumed/knocked off
                BattleStateTracker.getItem(uuid)
            }
            // Get actual speed stat and ability for player's Pokemon
            actualSpeed = battlePokemon.speed
            actualDefence = battlePokemon.defence
            actualSpecialDefence = battlePokemon.specialDefence
            actualAttack = battlePokemon.attack
            actualSpecialAttack = battlePokemon.specialAttack
            // Format the ability name properly (ability.name returns ID like "flashfire")
            val rawAbilityName = battlePokemon.ability.name
            val translatedAbility = Text.translatable("cobblemon.ability.$rawAbilityName").string
            abilityName = if (translatedAbility.startsWith("cobblemon.") || translatedAbility == rawAbilityName) {
                formatAbilityName(rawAbilityName)
            } else {
                translatedAbility
            }
            possibleAbilities = null  // Player knows their own ability
        } else {
            // Opponent/spectated: show revealed moves with estimated PP (assumes PP Max)
            moves = BattleStateTracker.getRevealedMoves(uuid).map { moveName ->
                val usageCount = BattleStateTracker.getMoveUsageCount(uuid, moveName)
                // Look up base PP from move template (try lowercase for registry lookup)
                val basePp = Moves.getByName(moveName.lowercase().replace(" ", ""))?.pp
                    ?: Moves.getByName(moveName)?.pp
                if (basePp != null) {
                    // Assume PP Max (base * 8/5) for competitive Pokemon
                    val estimatedMax = basePp * 8 / 5
                    val estimatedRemaining = (estimatedMax - usageCount).coerceAtLeast(0)
                    MoveInfo(moveName, estimatedRemaining = estimatedRemaining, estimatedMax = estimatedMax)
                } else {
                    // Unknown move - just show usage count
                    MoveInfo(moveName, usageCount = usageCount)
                }
            }
            item = BattleStateTracker.getItem(uuid)
            actualSpeed = null  // Can't know opponent's actual speed
            actualDefence = null
            actualSpecialDefence = null
            actualAttack = null
            actualSpecialAttack = null
            // Check if ability was revealed during battle
            val revealedAbility = BattleStateTracker.getRevealedAbility(uuid)
            if (revealedAbility != null) {
                abilityName = revealedAbility
                possibleAbilities = null  // Ability is known, no need for possibilities
            } else {
                // Ability not yet revealed - show possible abilities from species
                abilityName = null
                possibleAbilities = trackedPokemon?.form?.abilities?.mapNotNull { potentialAbility ->
                    // Try translation first, fall back to formatting the ID
                    val translated = Text.translatable(potentialAbility.template.displayName).string
                    val abilityId = potentialAbility.template.name

                    // If translation failed (returned key or looks like untranslated ID), format manually
                    if (translated.startsWith("cobblemon.") || translated == abilityId) {
                        formatAbilityName(abilityId)
                    } else {
                        translated
                    }
                }
            }
        }

        // Get types from species (name must be lowercase for registry lookup)
        val species = if (trackedPokemon?.speciesIdentifier != null) PokemonSpecies.getByIdentifier(trackedPokemon.speciesIdentifier!!)
        else if (battlePokemon != null) PokemonSpecies.getByIdentifier(battlePokemon.species.resourceIdentifier)
        else null

        val primaryType = if (battlePokemon != null) battlePokemon.form.primaryType
        else {
            if (trackedPokemon?.form != null) trackedPokemon.form?.primaryType
            else species?.primaryType
        }

        val secondaryType = if (battlePokemon != null) battlePokemon.form.secondaryType
        else {
            if (trackedPokemon?.form != null) trackedPokemon.form?.secondaryType
            else species?.secondaryType
        }

        val teraType = if (battlePokemon != null) battlePokemon.teraType
        else trackedPokemon?.teraType

        return TooltipData(
            pokemonName = name,
            pokemonId = pokemonId,
            hpPercent = hpPercent,
            statusCondition = trackedPokemon?.status ?: battlePokemon?.status?.status,
            isKO = trackedPokemon?.isKO ?: isPokemonKO(uuid),
            moves = moves,
            item = item,
            statChanges = BattleStateTracker.getStatChanges(uuid),
            volatileStatuses = BattleStateTracker.getVolatileStatuses(uuid),
            level = level,
            speciesName = speciesName,
            isPlayerPokemon = isPlayerPokemon,
            actualSpeed = actualSpeed,
            actualDefence = actualDefence,
            actualSpecialDefence = actualSpecialDefence,
            actualAttack = actualAttack,
            actualSpecialAttack = actualSpecialAttack,
            abilityName = abilityName,
            possibleAbilities = possibleAbilities,
            primaryType = primaryType,
            secondaryType = secondaryType,
            teraType = teraType,
            form = trackedPokemon?.form,
            nature = trackedPokemon?.nature
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

        // Build tooltip lines: each line is a list of (text, color) segments
        val lines = mutableListOf<List<Pair<String, Int>>>()

        val nameColor = data.primaryType?.let { UIUtils.getTypeColor(it) } ?: TOOLTIP_HEADER
        lines.add(listOf(data.pokemonName to nameColor))

        // Types
        if (data.primaryType != null) {
            val typeSegments = mutableListOf<Pair<String, Int>>()
            typeSegments.add(data.primaryType.displayName.string to UIUtils.getTypeColor(data.primaryType))
            if (data.secondaryType != null) {
                typeSegments.add(" / " to TOOLTIP_DIM)
                typeSegments.add(data.secondaryType.displayName.string to UIUtils.getTypeColor(data.secondaryType))
            }
            lines.add(typeSegments)
        }

        // Tera Type
        if (data.teraType != null) {
            val typeSegments = mutableListOf<Pair<String, Int>>()
            typeSegments.add("Tera Type: " to TOOLTIP_LABEL)
            val elementalType = ElementalTypes.get(data.teraType.showdownId());
            if (elementalType != null) {
                typeSegments.add(elementalType.displayName.string to UIUtils.getTypeColor(elementalType))
            } else {
                typeSegments.add(data.teraType.name to TOOLTIP_TEXT)
            }
            lines.add(typeSegments);
        } else {
            lines.add(listOf("Tera Type: Unknown" to TOOLTIP_LABEL))
        }
        // Ability
        if (data.abilityName != null) {
            // Known ability (player Pokemon or revealed opponent ability)
            lines.add(listOf("Ability: ${data.abilityName}" to TOOLTIP_ABILITY))
        } else if (!data.possibleAbilities.isNullOrEmpty()) {
            // Unknown ability - show possible abilities
            val abilitySegments = mutableListOf<Pair<String, Int>>()
            abilitySegments.add("Ability: " to TOOLTIP_LABEL)
            data.possibleAbilities.forEachIndexed { index, ability ->
                if (index > 0) {
                    abilitySegments.add(" / " to TOOLTIP_DIM)
                }
                abilitySegments.add(ability to TOOLTIP_ABILITY_POSSIBLE)
            }
            lines.add(abilitySegments)
        }

        // HP percentage
        val hpColor = when {
            data.isKO -> color(100, 100, 100)
            data.hpPercent > 0.5f -> TOOLTIP_HP_HIGH
            data.hpPercent > 0.25f -> TOOLTIP_HP_MED
            else -> TOOLTIP_HP_LOW
        }
        val hpText = if (data.isKO) "HP: KO'd" else "HP: ${(data.hpPercent * 100).toInt()}%"
        lines.add(listOf(hpText to hpColor))

        // Status condition
        data.statusCondition?.let { status ->
            val statusName = getStatusDisplayName(status)
            lines.add(listOf("Status: $statusName" to getStatusTextColor(status)))
        }

        // Moves (with PP - exact for player, estimated range for opponent)
        if (data.moves.isNotEmpty()) {
            lines.add(listOf("Moves:" to TOOLTIP_LABEL))
            for (move in data.moves.take(4)) {
                val moveSegments = mutableListOf<Pair<String, Int>>()

                // Look up move type for coloring (try lowercase registry name)
                val moveTemplate = Moves.getByName(move.name.lowercase().replace(" ", ""))
                    ?: Moves.getByName(move.name.lowercase())
                val moveColor = moveTemplate?.let { UIUtils.getTypeColor(it.elementalType) } ?: TOOLTIP_TEXT
                moveSegments.add("  ${move.name}" to moveColor)

                if (data.isPlayerPokemon) {
                    // Player's Pokemon: show exact remaining PP / max PP with color
                    if (move.currentPp != null && move.maxPp != null) {
                        val ppRatio = move.currentPp.toFloat() / move.maxPp.coerceAtLeast(1)
                        val ppColor = if (ppRatio <= 0.25f) TOOLTIP_PP_LOW else TOOLTIP_PP
                        moveSegments.add(" (" to TOOLTIP_DIM)
                        moveSegments.add("${move.currentPp}/${move.maxPp}" to ppColor)
                        moveSegments.add(")" to TOOLTIP_DIM)
                    }
                } else {
                    // Opponent: show estimated remaining/max PP
                    if (move.estimatedRemaining != null && move.estimatedMax != null) {
                        val ppRatio = move.estimatedRemaining.toFloat() / move.estimatedMax.coerceAtLeast(1)
                        val ppColor = if (ppRatio <= 0.25f) TOOLTIP_PP_LOW else TOOLTIP_PP
                        moveSegments.add(" (~" to TOOLTIP_DIM)
                        moveSegments.add("${move.estimatedRemaining}/${move.estimatedMax}" to ppColor)
                        moveSegments.add(")" to TOOLTIP_DIM)
                    } else if (move.usageCount != null) {
                        // Unknown move - show usage count
                        moveSegments.add(" (used ×${move.usageCount})" to TOOLTIP_DIM)
                    }
                }
                lines.add(moveSegments)
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
            lines.add(listOf(itemText to itemColor))
        }

        // Stat changes
        if (data.statChanges.isNotEmpty()) {
            val statSegments = mutableListOf<Pair<String, Int>>()
            statSegments.add("Stats: " to TOOLTIP_LABEL)
            val sortedStats = data.statChanges.entries.sortedBy { it.key.ordinal }
            sortedStats.forEachIndexed { index, (stat, value) ->
                if (index > 0) statSegments.add(" " to TOOLTIP_LABEL)
                val sign = if (value > 0) "+" else ""
                val statColor = if (value > 0) TOOLTIP_STAT_BOOST else TOOLTIP_STAT_DROP
                statSegments.add("${stat.abbr}$sign$value" to statColor)
            }
            lines.add(statSegments)
        }

        // Attack
        if (data.isPlayerPokemon && data.actualAttack != null) {
            val attackStage = data.statChanges[BattleStateTracker.BattleStat.ATTACK] ?: 0
            val effectiveAttack = calculateEffectiveStat(
                data.actualAttack,
                attackStage,
                BattleStateTracker.BattleStat.ATTACK,
                data.nature
            )
            val modifiers = mutableListOf<String>()
            if (attackStage != 0) modifiers.add(if (attackStage > 0) "+$attackStage" else "$attackStage")
            val modText = if (modifiers.isNotEmpty()) " (${modifiers.joinToString(", ")})" else ""
            lines.add(listOf("Attack: $effectiveAttack$modText" to TOOLTIP_ATTACK))
        }

        // Defense
        val defenceStage = data.statChanges[BattleStateTracker.BattleStat.DEFENSE] ?: 0

        if (data.isPlayerPokemon && data.actualDefence != null) {
            val itemName = data.item?.name
            val itemConsumed = data.item?.status == BattleStateTracker.ItemStatus.CONSUMED

            val effectiveDefence = calculateEffectiveStat(
                data.actualDefence,
                defenceStage,
                BattleStateTracker.BattleStat.DEFENSE,
                data.nature
            )

            // Build modifier notes (igual que Speed)
            val modifiers = mutableListOf<String>()
            if (defenceStage != 0) {
                modifiers.add(if (defenceStage > 0) "+$defenceStage" else "$defenceStage")
            }

            // Si en el futuro tienes habilidades/items que afecten defensa,
            // aquí es donde se añaden (ejemplo comentado)
            /*
            val abilityMult = getAbilityDefenseMultiplier(data.abilityName, ...)
            if (abilityMult != 1.0) modifiers.add(data.abilityName)

            if (itemName != null && getItemDefenseMultiplier(itemName) != 1.0 && !itemConsumed) {
                modifiers.add(itemName)
            }
            */

            val modText = if (modifiers.isNotEmpty())
                " (${modifiers.joinToString(", ")})"
            else ""

            lines.add(
                listOf(
                    "Defense: $effectiveDefence$modText" to TOOLTIP_DEFENSE
                )
            )
        }

        // Special Attack
        if (data.isPlayerPokemon && data.actualSpecialAttack != null) {
            val specialAttackStage = data.statChanges[BattleStateTracker.BattleStat.SPECIAL_ATTACK] ?: 0
            val effectiveSpecialAttack = calculateEffectiveStat(
                data.actualSpecialAttack,
                specialAttackStage,
                BattleStateTracker.BattleStat.SPECIAL_ATTACK,
                data.nature
            )
            val modifiers = mutableListOf<String>()
            if (specialAttackStage != 0) modifiers.add(if (specialAttackStage > 0) "+$specialAttackStage" else "$specialAttackStage")
            val modText = if (modifiers.isNotEmpty()) " (${modifiers.joinToString(", ")})" else ""
            lines.add(listOf("Special Attack: $effectiveSpecialAttack$modText" to TOOLTIP_SPECIAL_ATTACK))
        }



        // Special Defense
        val specialDefenceStage = data.statChanges[BattleStateTracker.BattleStat.SPECIAL_DEFENSE] ?: 0
        if (data.isPlayerPokemon && data.actualSpecialDefence != null) {
            val itemName = data.item?.name
            val itemConsumed = data.item?.status == BattleStateTracker.ItemStatus.CONSUMED

            val effectiveSpecialDefence = calculateEffectiveStat(
                data.actualSpecialDefence,
                specialDefenceStage,
                BattleStateTracker.BattleStat.SPECIAL_DEFENSE,
                data.nature
            )

            // Build modifier notes (igual que Speed)
            val modifiers = mutableListOf<String>()
            if (specialDefenceStage != 0) {
                modifiers.add(if (specialDefenceStage > 0) "+$specialDefenceStage" else "$specialDefenceStage")
            }

            // Si en el futuro tienes habilidades/items que afecten defensa especial,
            // aquí es donde se añaden (ejemplo comentado)
            /*
            val abilityMult = getAbilitySpecialDefenseMultiplier(data.abilityName, ...)
            if (abilityMult != 1.0) modifiers.add(data.abilityName)

            if (itemName != null && getItemSpecialDefenseMultiplier(itemName) != 1.0 && !itemConsumed) {
                modifiers.add(itemName)
            }
            */

            val modText = if (modifiers.isNotEmpty())
                " (${modifiers.joinToString(", ")})"
            else ""

            lines.add(
                listOf(
                    "Special Defense: $effectiveSpecialDefence$modText" to TOOLTIP_SPECIAL_DEFENSE
                )
            )
        }

        // Speed tier display with ability/status/item modifiers
        val speedStage = data.statChanges[BattleStateTracker.BattleStat.SPEED] ?: 0
        if (data.isPlayerPokemon && data.actualSpeed != null) {
            // Player's Pokemon: show effective speed with all modifiers
            val itemName = data.item?.name
            val itemConsumed = data.item?.status == BattleStateTracker.ItemStatus.CONSUMED
            val effectiveSpeed = calculateEffectiveSpeed(
                data.actualSpeed, speedStage, data.abilityName,
                data.statusCondition, itemName, itemConsumed
            )
            // Build modifier notes
            val modifiers = mutableListOf<String>()
            if (speedStage != 0) modifiers.add(if (speedStage > 0) "+$speedStage" else "$speedStage")
            val abilityMult = getAbilitySpeedMultiplier(
                data.abilityName, BattleStateTracker.weather?.type, BattleStateTracker.terrain?.type,
                data.statusCondition != null, itemConsumed
            )
            if (abilityMult != 1.0) modifiers.add("${data.abilityName}")
            if (data.statusCondition == Statuses.PARALYSIS && normalizeAbilityName(data.abilityName) != "quickfeet") {
                modifiers.add("Para")
            }
            if (itemName != null && getItemSpeedMultiplier(itemName) != 1.0 && !itemConsumed) {
                modifiers.add(itemName)
            }
            val modText = if (modifiers.isNotEmpty()) " (${modifiers.joinToString(", ")})" else ""
            lines.add(listOf("Speed: $effectiveSpeed$modText" to TOOLTIP_SPEED))
        } else if (data.pokemonId != null && data.level != null) {
            // Opponent: show min-max speed range with ability considerations
            val speedRange = calculateOpponentSpeedRange(
                data.pokemonId, data.level, speedStage, data.statusCondition, data.item, data.form
            )
            if (speedRange != null) {
                val modifiers = mutableListOf<String>()
                if (speedStage != 0) modifiers.add(if (speedStage > 0) "+$speedStage" else "$speedStage")
                speedRange.abilityNote?.let { modifiers.add(it) }
                if (data.statusCondition == Statuses.PARALYSIS) modifiers.add("Para?")
                val modText = if (modifiers.isNotEmpty()) " (${modifiers.joinToString(", ")})" else ""
                lines.add(listOf("Speed: ${speedRange.minSpeed}-${speedRange.maxSpeed}$modText" to TOOLTIP_SPEED))
            }
        }

        // Volatile statuses
        if (data.volatileStatuses.isNotEmpty()) {
            val volatileText = data.volatileStatuses.take(3).joinToString(", ") { it.type.displayName }
            val suffix = if (data.volatileStatuses.size > 3) "..." else ""
            lines.add(listOf("Effects: $volatileText$suffix" to TOOLTIP_LABEL))
        }

        // Calculate dimensions - sum up segment widths for each line
        val maxLineWidth = lines.maxOfOrNull { segments ->
            segments.sumOf { (text, _) -> (textRenderer.getWidth(text) * fontScale).toInt() }
        } ?: 80
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

        // Push z-level forward so tooltip renders on top of other UI elements
        val matrices = context.matrices
        matrices.push()
        matrices.translate(0.0, 0.0, 400.0)

        // Draw rounded background
        drawTooltipBackground(context, tooltipX, tooltipY, tooltipWidth, tooltipHeight)

        // Draw text lines with scaling (each line can have multiple colored segments)
        var lineY = tooltipY + TOOLTIP_PADDING
        for (segments in lines) {
            var segmentX = (tooltipX + TOOLTIP_PADDING).toFloat()
            for ((text, textColor) in segments) {
                drawScaledText(
                    context = context,
                    text = Text.literal(text),
                    x = segmentX,
                    y = lineY.toFloat(),
                    colour = applyOpacity(textColor),
                    scale = fontScale,
                    shadow = false
                )
                segmentX += textRenderer.getWidth(text) * fontScale
            }
            lineY += lineHeight
        }

        matrices.pop()
    }

    private fun calculateOpponentStatRange(
        level: Int,
        battleStat: BattleStateTracker.BattleStat,
        defenceStage: Int,
        form: FormData?,
        nature: Nature?
    ): RangeResult? {

        val baseStat = when (battleStat) {
            BattleStateTracker.BattleStat.DEFENSE ->
                form?.baseStats?.get(Stats.DEFENCE)
            BattleStateTracker.BattleStat.SPECIAL_DEFENSE ->
                form?.baseStats?.get(Stats.SPECIAL_DEFENCE)
            else -> null
        } ?: return null

        // Pokémon standard assumptions
        val minIV = 0
        val maxIV = 31
        val minEV = 0
        val maxEV = 252

        fun calculateStat(base: Int, iv: Int, ev: Int): Int {
            return (((2 * base + iv + (ev / 4)) * level) / 100) + 5
        }

        val minStat = calculateStat(baseStat, minIV, minEV)
        val maxStat = calculateStat(baseStat, maxIV, maxEV)

        val effectiveMin = calculateEffectiveStat(minStat, defenceStage, battleStat, nature  )
        val effectiveMax = calculateEffectiveStat(maxStat, defenceStage, battleStat, nature )



        return RangeResult(effectiveMin, effectiveMax)
    }

    private fun calculateEffectiveStat(
        actualStat: Int,
        stage: Int,
        stat: BattleStateTracker.BattleStat,
        nature: Nature?
    ): Int {

        val stageMultipliers = arrayOf(
            1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0
        )

        val stageMultiplier = if (stage >= 0) {
            stageMultipliers[stage.coerceAtMost(6)]
        } else {
            1.0 / stageMultipliers[(-stage).coerceAtMost(6)]
        }

        val natureMultiplier = getNatureMultiplier(nature, stat)

        return (actualStat * stageMultiplier * natureMultiplier).toInt()
    }


    private val BATTLESTAT_TO_NATURESTAT = mapOf(
        BattleStateTracker.BattleStat.ATTACK to Stats.ATTACK,
        BattleStateTracker.BattleStat.SPECIAL_ATTACK to Stats.SPECIAL_ATTACK,
        BattleStateTracker.BattleStat.DEFENSE to Stats.DEFENCE,
        BattleStateTracker.BattleStat.SPECIAL_DEFENSE to Stats.SPECIAL_DEFENCE,
        BattleStateTracker.BattleStat.SPEED to Stats.SPEED
    )


    private fun getNatureMultiplier(
        nature: Nature?,
        stat: BattleStateTracker.BattleStat
    ): Double {
        val natureStat = BATTLESTAT_TO_NATURESTAT[stat] ?: return 1.0
        if (nature == null) return 1.0

        return when {
            nature.increasedStat == natureStat -> 1.1
            nature.decreasedStat == natureStat -> 0.9
            else -> 1.0
        }
    }



    /**
     * Draw a rounded tooltip background with border.
     */
    private fun drawTooltipBackground(context: DrawContext, x: Int, y: Int, width: Int, height: Int) {
        val corner = TOOLTIP_CORNER

        // Apply opacity for minimized state
        val bg = applyOpacity(TOOLTIP_BG)
        val border = applyOpacity(TOOLTIP_BORDER)

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

        // Draw border - straight edges
        context.fill(x + corner, y, x + width - corner, y + 1, border)
        context.fill(x + corner, y + height - 1, x + width - corner, y + height, border)
        context.fill(x, y + corner, x + 1, y + height - corner, border)
        context.fill(x + width - 1, y + corner, x + width, y + height - corner, border)

        // Draw rounded corner borders
        // Top-left
        context.fill(x + 2, y, x + corner, y + 1, border)
        context.fill(x + 1, y + 1, x + 2, y + 2, border)
        context.fill(x, y + 2, x + 1, y + corner, border)
        // Top-right
        context.fill(x + width - corner, y, x + width - 2, y + 1, border)
        context.fill(x + width - 2, y + 1, x + width - 1, y + 2, border)
        context.fill(x + width - 1, y + 2, x + width, y + corner, border)
        // Bottom-left
        context.fill(x + 2, y + height - 1, x + corner, y + height, border)
        context.fill(x + 1, y + height - 2, x + 2, y + height - 1, border)
        context.fill(x, y + height - corner, x + 1, y + height - 2, border)
        // Bottom-right
        context.fill(x + width - corner, y + height - 1, x + width - 2, y + height, border)
        context.fill(x + width - 2, y + height - 2, x + width - 1, y + height - 1, border)
        context.fill(x + width - 1, y + height - corner, x + width, y + height - 2, border)
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // Tooltip Input Handling
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle font size keybinds ([ and ] keys).
     */
    private fun handleFontKeybinds(handle: Long) {
        val increaseKey =
            InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.increaseFontKey.boundKeyTranslationKey)
        val isIncreaseDown = isKeyOrButtonPressed(handle, increaseKey)
        if (isIncreaseDown && !wasIncreaseFontKeyPressed) {
            PanelConfig.adjustTooltipFontScale(PanelConfig.FONT_SCALE_STEP)
            PanelConfig.save()
        }
        wasIncreaseFontKeyPressed = isIncreaseDown

        val decreaseKey =
            InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.decreaseFontKey.boundKeyTranslationKey)
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

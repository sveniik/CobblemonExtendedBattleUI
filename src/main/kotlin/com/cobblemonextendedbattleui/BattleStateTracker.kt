package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.FormData
import net.minecraft.util.Identifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    // Track if player is spectating (not participating in the battle)
    var isSpectating: Boolean = false
        private set

    fun setSpectating(spectating: Boolean) {
        isSpectating = spectating
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Spectating mode = $spectating")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Dynamic Type Tracking
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tracks types that can change mid-battle via moves (Soak, Burn Up, Trick-or-Treat)
     * or form changes.
     *
     * @param primaryType Current primary type name (null = "???" lost type)
     * @param secondaryType Current secondary type name (null = no secondary OR lost)
     * @param hasLostPrimaryType True if primary was lost (show "???")
     * @param addedTypes Types added by moves (Trick-or-Treat adds Ghost, Forest's Curse adds Grass)
     * @param originalPrimaryType For reference/reversion
     * @param originalSecondaryType For reference/reversion
     */
    data class DynamicTypeState(
        val primaryType: String?,
        val secondaryType: String?,
        val hasLostPrimaryType: Boolean = false,
        val addedTypes: List<String> = emptyList(),
        val originalPrimaryType: String?,
        val originalSecondaryType: String?
    )

    // Maps UUID -> current dynamic type state
    private val dynamicTypes = ConcurrentHashMap<UUID, DynamicTypeState>()

    // ═══════════════════════════════════════════════════════════════════════════
    // Form Change Tracking
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tracks the current form of a Pokemon that can change mid-battle.
     * @param currentForm The form name (e.g., "Mega", "Zen", "Sunny", "School", "Ash-Greninja")
     * @param originalForm The base form name (null = default form)
     * @param isMega True if this is a Mega Evolution
     * @param isTemporary True if the form reverts automatically (weather forms, Zen Mode on HP)
     */
    data class FormState(
        val currentForm: String,
        val originalForm: String? = null,
        val isMega: Boolean = false,
        val isTemporary: Boolean = false
    )

    // Maps UUID -> current form state (null = default form)
    private val pokemonForms = ConcurrentHashMap<UUID, FormState>()

    // Maps UUID -> species identifier (for form lookup when only name is available)
    private val pokemonSpeciesIds = ConcurrentHashMap<UUID, Identifier>()

    /**
     * Initialize dynamic types when a Pokemon enters battle.
     * Sets the original types for reference and starts tracking.
     */
    fun initializeDynamicTypes(uuid: UUID, primaryType: String?, secondaryType: String?) {
        dynamicTypes[uuid] = DynamicTypeState(
            primaryType = primaryType,
            secondaryType = secondaryType,
            hasLostPrimaryType = false,
            addedTypes = emptyList(),
            originalPrimaryType = primaryType,
            originalSecondaryType = secondaryType
        )
        CobblemonExtendedBattleUI.LOGGER.debug(
            "BattleStateTracker: Initialized dynamic types for $uuid - primary=$primaryType, secondary=$secondaryType"
        )
    }

    /**
     * Set complete type replacement for a Pokemon (Soak, form changes).
     * Replaces all types with new values.
     * If both types are null, attempts to look up from species data as fallback.
     */
    fun setTypeReplacement(pokemonName: String, newPrimaryType: String?, newSecondaryType: String?, preferAlly: Boolean?) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for type replacement")
            return
        }

        // If both types are null, try to look up from species as fallback
        // This prevents creating states with unknown types
        var effectivePrimaryType = newPrimaryType
        var effectiveSecondaryType = newSecondaryType
        if (effectivePrimaryType == null && effectiveSecondaryType == null) {
            val speciesId = pokemonSpeciesIds[uuid]
            val species = speciesId?.let { PokemonSpecies.getByIdentifier(it) }
            if (species != null) {
                effectivePrimaryType = species.primaryType.name
                effectiveSecondaryType = species.secondaryType?.name
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "BattleStateTracker: Type replacement for $pokemonName - using species fallback: $effectivePrimaryType/$effectiveSecondaryType"
                )
            }
        }

        val existing = dynamicTypes[uuid]

        // If there's an existing state with hasLostPrimaryType=true (from Burn Up, etc.),
        // don't let a generic type change message overwrite it - the type loss should persist
        if (existing?.hasLostPrimaryType == true) {
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: Ignoring type replacement for $pokemonName - hasLostPrimaryType is already true"
            )
            return
        }

        dynamicTypes[uuid] = DynamicTypeState(
            primaryType = effectivePrimaryType,
            secondaryType = effectiveSecondaryType,
            hasLostPrimaryType = false,
            addedTypes = emptyList(),  // Type replacement clears added types
            originalPrimaryType = existing?.originalPrimaryType ?: effectivePrimaryType,
            originalSecondaryType = existing?.originalSecondaryType ?: effectiveSecondaryType
        )
        CobblemonExtendedBattleUI.LOGGER.debug(
            "BattleStateTracker: Type replacement for $pokemonName - primary=$effectivePrimaryType, secondary=$effectiveSecondaryType"
        )
    }

    /**
     * Mark a type as lost (Burn Up loses Fire type).
     * If the lost type is primary, sets hasLostPrimaryType to show "???".
     * If types aren't tracked yet, initializes from species data first.
     */
    fun loseType(pokemonName: String, typeName: String, preferAlly: Boolean?) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for type loss")
            return
        }

        // If types aren't tracked yet, initialize from species
        var existing = dynamicTypes[uuid]

        if (existing == null) {
            val speciesId = pokemonSpeciesIds[uuid]
            val species = speciesId?.let { PokemonSpecies.getByIdentifier(it) }

            if (species != null) {
                val primaryType = species.primaryType.name
                val secondaryType = species.secondaryType?.name
                existing = DynamicTypeState(
                    primaryType = primaryType,
                    secondaryType = secondaryType,
                    hasLostPrimaryType = false,
                    originalPrimaryType = primaryType,
                    originalSecondaryType = secondaryType
                )
                dynamicTypes[uuid] = existing
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "BattleStateTracker: Initialized types for $pokemonName: $primaryType/$secondaryType"
                )
            } else {
                // Species unknown - still create a minimal dynamic state to track the lost type
                // This handles cases where Burn Up message arrives before species is registered
                existing = DynamicTypeState(
                    primaryType = typeName,  // Assume the lost type was the primary (true for Burn Up/Fire, Double Shock/Electric)
                    secondaryType = null,
                    hasLostPrimaryType = false,
                    originalPrimaryType = typeName,
                    originalSecondaryType = null
                )
                dynamicTypes[uuid] = existing
            }
        }

        val typeNameLower = typeName.lowercase()
        val existingPrimaryLower = existing.primaryType?.lowercase()
        val existingSecondaryLower = existing.secondaryType?.lowercase()
        val isPrimaryLost = existingPrimaryLower == typeNameLower
        val isSecondaryLost = existingSecondaryLower == typeNameLower

        if (isPrimaryLost || isSecondaryLost) {
            val newState = existing.copy(
                primaryType = if (isPrimaryLost) null else existing.primaryType,
                secondaryType = if (isSecondaryLost) null else existing.secondaryType,
                hasLostPrimaryType = isPrimaryLost || existing.hasLostPrimaryType
            )
            dynamicTypes[uuid] = newState
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: $pokemonName lost type $typeName - hasLostPrimaryType=${newState.hasLostPrimaryType}"
            )
        } else if (existing.primaryType == null && existing.secondaryType == null && !existing.hasLostPrimaryType) {
            // Edge case: existing state has null types (from failed Transform type update)
            // Since we know this Pokemon just used a type-loss move, mark the primary type as lost
            // This ensures "???" is displayed even if we couldn't determine original types
            val newState = existing.copy(
                hasLostPrimaryType = true
            )
            dynamicTypes[uuid] = newState
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: $pokemonName has unknown types but used type-loss move - marking hasLostPrimaryType=true"
            )
        } else {
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: Type '$typeName' not found on $pokemonName (types: ${existing.primaryType}/${existing.secondaryType})"
            )
        }
    }

    /**
     * Add a type to a Pokemon (Trick-or-Treat adds Ghost, Forest's Curse adds Grass).
     * Added types are shown after the Pokemon's normal types.
     */
    fun addType(pokemonName: String, typeName: String, preferAlly: Boolean?) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for add type")
            return
        }

        val existing = dynamicTypes[uuid] ?: return
        val normalizedName = typeName.replaceFirstChar { it.uppercase() }

        // Don't add if already present in normal types or added types
        if (existing.primaryType?.equals(normalizedName, ignoreCase = true) == true ||
            existing.secondaryType?.equals(normalizedName, ignoreCase = true) == true ||
            existing.addedTypes.any { it.equals(normalizedName, ignoreCase = true) }) {
            return
        }

        dynamicTypes[uuid] = existing.copy(
            addedTypes = existing.addedTypes + normalizedName
        )
        CobblemonExtendedBattleUI.LOGGER.debug(
            "BattleStateTracker: $pokemonName gained type $normalizedName"
        )
    }

    /**
     * Get current dynamic type state for a Pokemon.
     * Returns null if no dynamic state exists (use form types instead).
     */
    fun getDynamicTypes(uuid: UUID): DynamicTypeState? = dynamicTypes[uuid]

    /**
     * Clear dynamic types for a Pokemon (on switch, NOT on faint - persist for info).
     */
    fun clearDynamicTypes(uuid: UUID) {
        if (dynamicTypes.remove(uuid) != null) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared dynamic types for $uuid")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Form Change CRUD Methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Set the current form for a Pokemon.
     * Called when form change messages are detected.
     */
    fun setCurrentForm(pokemonName: String, formName: String, isMega: Boolean = false, isTemporary: Boolean = false, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for form change")
            return
        }

        val existing = pokemonForms[uuid]
        pokemonForms[uuid] = FormState(
            currentForm = formName,
            originalForm = existing?.originalForm,  // Preserve original if already tracked
            isMega = isMega,
            isTemporary = isTemporary
        )
        CobblemonExtendedBattleUI.LOGGER.debug(
            "BattleStateTracker: $pokemonName form changed to '$formName' (mega=$isMega, temporary=$isTemporary)"
        )
    }

    /**
     * Clear form state (revert to default form).
     * Called when temporary forms end or Pokemon switches out.
     */
    fun clearCurrentForm(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        if (pokemonForms.remove(uuid) != null) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName reverted to base form")
        }
    }

    /**
     * Get current form state for a Pokemon.
     * Returns null if Pokemon is in its default form.
     */
    fun getCurrentForm(uuid: UUID): FormState? = pokemonForms[uuid]

    /**
     * Get current form by name.
     */
    fun getCurrentFormByName(pokemonName: String, preferAlly: Boolean? = null): FormState? {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return null
        return pokemonForms[uuid]
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Form Change Type Updates
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Convert form name from battle messages to Cobblemon aspect string.
     * Form names come from messages like "Mega Evolution" -> "mega",
     * "Zen Mode" -> "zen", "School Form" -> "school", etc.
     */
    fun formNameToAspect(formName: String): String {
        return formName
            .lowercase()
            .replace("-", "")
            .replace(" ", "")
            .replace("form", "")
            .replace("mode", "")
            .trim()
    }

    /**
     * Update types and return FormData when a form change occurs.
     * Called after setCurrentForm() to propagate type changes.
     *
     * @param pokemonName Name of the Pokemon
     * @param speciesId Identifier for species lookup
     * @param formName The form name from the battle message
     * @param preferAlly Hint for mirror match disambiguation
     * @return FormData if found, null otherwise
     */
    fun updateTypesForFormChange(
        pokemonName: String,
        speciesId: Identifier?,
        formName: String,
        preferAlly: Boolean? = null
    ): FormData? {
        if (speciesId == null) return null

        val species = PokemonSpecies.getByIdentifier(speciesId) ?: return null
        val aspect = formNameToAspect(formName)
        val form = species.getForm(setOf(aspect))

        // Update dynamic types with the new form's types
        val primaryType = form.primaryType?.name
        val secondaryType = form.secondaryType?.name

        setTypeReplacement(pokemonName, primaryType, secondaryType, preferAlly)

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Form change type update for $pokemonName to $formName - types: $primaryType/$secondaryType")

        return form
    }

    /**
     * Restore a Pokemon's original types (used when form reverts or Pokemon switches out).
     */
    fun restoreOriginalTypes(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        restoreOriginalTypes(uuid)
    }

    /**
     * Restore a Pokemon's original types by UUID.
     * Called when a Pokemon switches out - type changes from Burn Up, Soak, etc. are reset.
     */
    fun restoreOriginalTypes(uuid: UUID) {
        val state = dynamicTypes[uuid] ?: return

        dynamicTypes[uuid] = state.copy(
            primaryType = state.originalPrimaryType,
            secondaryType = state.originalSecondaryType,
            hasLostPrimaryType = false,
            addedTypes = emptyList()
        )
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Restored original types for UUID $uuid")
    }

    /**
     * Get the species identifier for a tracked Pokemon by UUID.
     */
    fun getSpeciesId(uuid: UUID): Identifier? = pokemonSpeciesIds[uuid]

    /**
     * Get the species identifier for a tracked Pokemon by name.
     * Used when we need to look up form data but only have the name.
     */
    fun getSpeciesIdByName(pokemonName: String, preferAlly: Boolean? = null): Identifier? {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return null
        return pokemonSpeciesIds[uuid]
    }

    /**
     * Register a species ID for a Pokemon.
     * Called when a Pokemon is tracked with known species.
     * Only registers if not already present (called every frame, so avoid redundant work).
     */
    fun registerSpeciesId(uuid: UUID, speciesId: Identifier) {
        if (!pokemonSpeciesIds.containsKey(uuid)) {
            pokemonSpeciesIds[uuid] = speciesId
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Registered species ID $speciesId for $uuid")
        }
    }

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
    // Move Tracking (for hover tooltips)
    // ═══════════════════════════════════════════════════════════════════════════

    // Maps UUID -> Set of revealed move names (persists entire battle)
    private val revealedMoves = ConcurrentHashMap<UUID, MutableSet<String>>()

    // Maps UUID -> (move name -> usage count) for estimating opponent PP
    private val moveUsageCounts = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Int>>()

    // UUIDs of Pokemon with Pressure ability (causes 2 PP to be consumed instead of 1)
    private val pressurePokemon = ConcurrentHashMap.newKeySet<UUID>()

    // ═══════════════════════════════════════════════════════════════════════════
    // Ability Tracking (for opponent Pokemon)
    // ═══════════════════════════════════════════════════════════════════════════

    // Maps UUID -> revealed ability name (persists entire battle)
    private val revealedAbilities = ConcurrentHashMap<UUID, String>()

    // ═══════════════════════════════════════════════════════════════════════════
    // PP Tracking (for player's Pokemon)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tracked move with PP information.
     * Uses AtomicInteger for thread-safe PP tracking.
     */
    class TrackedMove(val name: String, initialPp: Int, val maxPp: Int) {
        private val _currentPp = AtomicInteger(initialPp)
        val currentPp: Int get() = _currentPp.get()

        /**
         * Atomically decrement PP by the specified amount (minimum 0).
         * @param amount Amount to decrement (default 1, use 2 for Pressure)
         * Returns the new PP value.
         */
        fun decrementPp(amount: Int = 1): Int {
            return _currentPp.updateAndGet { current -> (current - amount).coerceAtLeast(0) }
        }
    }

    // Maps UUID -> List of tracked moves with PP
    private val trackedMoves = ConcurrentHashMap<UUID, List<TrackedMove>>()

    /**
     * Initialize PP tracking for a Pokemon's moves.
     * Only initializes if not already tracked (prevents overwriting during battle).
     * Uses putIfAbsent for thread-safe atomic initialization.
     */
    fun initializeMoves(uuid: UUID, moves: List<TrackedMove>) {
        if (moves.isNotEmpty() && trackedMoves.putIfAbsent(uuid, moves) == null) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Initialized PP tracking for $uuid with ${moves.size} moves")
        }
    }

    /**
     * Decrement PP for a move when used.
     * Called from addRevealedMove when a player's Pokemon uses a move.
     * @param targetName Optional target name - if target has Pressure, decrements by 2
     */
    fun decrementPP(uuid: UUID, moveName: String, targetName: String? = null) {
        val moves = trackedMoves[uuid] ?: return
        val move = moves.find { it.name.equals(moveName, ignoreCase = true) } ?: return

        // Check if target has Pressure ability (costs 2 PP instead of 1)
        val ppCost = if (targetName != null) {
            val targetUuid = resolvePokemonUuid(targetName, preferAlly = false)
            if (targetUuid != null && pressurePokemon.contains(targetUuid)) 2 else 1
        } else {
            1
        }

        val newPp = move.decrementPp(ppCost)
        if (ppCost > 1) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $moveName PP: $newPp/${move.maxPp} (Pressure: -$ppCost)")
        } else {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $moveName PP: $newPp/${move.maxPp}")
        }
    }

    /**
     * Register a Pokemon as having the Pressure ability.
     * Called when Pressure is announced at battle start or on switch-in.
     */
    fun registerPressure(pokemonName: String) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly = null) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Could not resolve UUID for Pressure Pokemon '$pokemonName'")
            return
        }
        if (pressurePokemon.add(uuid)) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Registered Pressure for $pokemonName ($uuid)")
        }
    }

    /**
     * Check if a Pokemon has Pressure ability.
     */
    fun hasPressure(uuid: UUID): Boolean = pressurePokemon.contains(uuid)

    /**
     * Get tracked moves with PP for a Pokemon.
     * Returns null if not tracked (opponent Pokemon).
     */
    fun getTrackedMoves(uuid: UUID): List<TrackedMove>? = trackedMoves[uuid]

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
        isSpectating = false
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
        pokemonItems.clear()
        knockedOutPokemon.clear()
        transformedPokemon.clear()
        revealedMoves.clear()
        moveUsageCounts.clear()
        trackedMoves.clear()
        pressurePokemon.clear()
        revealedAbilities.clear()
        dynamicTypes.clear()
        pokemonForms.clear()
        pokemonSpeciesIds.clear()
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
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Registered '$name' (${if (isAlly) "ally" else "opponent"}) with UUID $uuid")
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

        // Perish Song has an absolute countdown - don't reset startTurn if already applied
        // (Cobblemon sends "start.perish" messages each turn as the counter decrements)
        if (type == VolatileStatus.PERISH_SONG && statuses.any { it.type == VolatileStatus.PERISH_SONG }) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName already has Perish Song, keeping original startTurn")
            return
        }

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
            // Countdown display (e.g., Perish Song: 3, 2, 1, 0)
            // Perish Song starts at 3 at end of turn used, decrements each subsequent turn
            // turnsElapsed=1 means we're on the turn after it was used, counter should be 3
            // turnsElapsed=2 means counter should be 2, etc.
            val countdown = (duration - turnsElapsed).coerceIn(0, duration - 1)
            countdown.toString()
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
    // Item Tracking
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Status of a tracked item.
     */
    enum class ItemStatus(val displaySuffix: String?) {
        HELD(null),                    // Item revealed, still held
        KNOCKED_OFF("knocked off"),    // Item was knocked off by Knock Off
        STOLEN("stolen"),              // Item was stolen by Thief/Covet
        SWAPPED("swapped"),            // Item was swapped away by Trick/Switcheroo
        CONSUMED("used")               // Item was consumed (berry, Focus Sash, etc.)
    }

    /**
     * Tracked item data for a Pokemon.
     * Items PERSIST after faint/switch for competitive information purposes.
     */
    data class TrackedItem(
        val name: String,
        var status: ItemStatus,
        val revealTurn: Int,
        var removalTurn: Int? = null
    )

    // Store items by Pokemon UUID - persists after switch/faint, only cleared on battle end
    private val pokemonItems = ConcurrentHashMap<UUID, TrackedItem>()

    // Track knocked out Pokemon - persists after faint, only cleared on battle end
    // This ensures pokeball indicators can reliably show KO status even after
    // the Pokemon is removed from activePokemon
    private val knockedOutPokemon = ConcurrentHashMap.newKeySet<UUID>()

    // Track transformed Pokemon (Ditto via Transform/Impostor) - used to revert form on faint
    private val transformedPokemon = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * Set or update an item for a Pokemon.
     */
    fun setItem(pokemonName: String, itemName: String, status: ItemStatus, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for item tracking")
            return
        }

        val effectiveTurn = maxOf(1, currentTurn)
        val existingItem = pokemonItems[uuid]

        when (status) {
            ItemStatus.HELD -> {
                // Only add if not already known (first reveal)
                if (existingItem == null) {
                    pokemonItems[uuid] = TrackedItem(itemName, status, effectiveTurn)
                    CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName revealed item $itemName")
                }
            }
            ItemStatus.KNOCKED_OFF, ItemStatus.STOLEN, ItemStatus.SWAPPED, ItemStatus.CONSUMED -> {
                // Update existing or create new with removal status
                pokemonItems[uuid] = TrackedItem(
                    itemName,
                    status,
                    existingItem?.revealTurn ?: effectiveTurn,
                    removalTurn = effectiveTurn
                )
                CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName item $itemName ${status.displaySuffix}")
            }
        }
    }

    /**
     * Get the tracked item for a Pokemon.
     */
    fun getItem(uuid: UUID): TrackedItem? = pokemonItems[uuid]

    /**
     * Get the tracked item for a Pokemon by name.
     */
    fun getItemByName(pokemonName: String, preferAlly: Boolean? = null): TrackedItem? {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return null
        return pokemonItems[uuid]
    }

    /**
     * Transfer an item from one Pokemon to another (for Thief/Covet/Trick).
     */
    fun transferItem(fromPokemon: String, toPokemon: String, itemName: String, fromIsAlly: Boolean? = null, toIsAlly: Boolean? = null) {
        val fromUuid = resolvePokemonUuid(fromPokemon, fromIsAlly)
        val toUuid = resolvePokemonUuid(toPokemon, toIsAlly)
        val effectiveTurn = maxOf(1, currentTurn)

        // Mark original holder's item as stolen
        if (fromUuid != null) {
            val existingItem = pokemonItems[fromUuid]
            pokemonItems[fromUuid] = TrackedItem(
                itemName,
                ItemStatus.STOLEN,
                existingItem?.revealTurn ?: effectiveTurn,
                removalTurn = effectiveTurn
            )
        }

        // Give item to the thief
        if (toUuid != null) {
            pokemonItems[toUuid] = TrackedItem(itemName, ItemStatus.HELD, effectiveTurn)
        }

        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $toPokemon stole $itemName from $fromPokemon")
    }

    /**
     * Handle item obtained via Trick/Switcheroo.
     * Sets the new item as HELD, replacing any existing item tracking.
     * The old item's stat effects will no longer apply since we track the new item.
     */
    fun receiveItemViaTrick(pokemonName: String, newItemName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for Trick item swap")
            return
        }

        val effectiveTurn = maxOf(1, currentTurn)
        val existingItem = pokemonItems[uuid]

        // Log what happened (for debugging)
        if (existingItem != null && existingItem.status == ItemStatus.HELD) {
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: $pokemonName's ${existingItem.name} swapped for $newItemName via Trick"
            )
        } else {
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: $pokemonName obtained $newItemName via Trick"
            )
        }

        // Set the new item as held (overwrites any previous item)
        pokemonItems[uuid] = TrackedItem(newItemName, ItemStatus.HELD, effectiveTurn)
    }

    /**
     * Mark an item as swapped away (lost via Trick/Switcheroo without knowing the new item).
     * Used when we know an item was lost but don't have info about what was received.
     */
    fun markItemSwapped(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return

        val effectiveTurn = maxOf(1, currentTurn)
        val existingItem = pokemonItems[uuid] ?: return

        if (existingItem.status == ItemStatus.HELD) {
            pokemonItems[uuid] = TrackedItem(
                existingItem.name,
                ItemStatus.SWAPPED,
                existingItem.revealTurn,
                removalTurn = effectiveTurn
            )
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: $pokemonName's ${existingItem.name} marked as swapped away"
            )
        }
    }

    // Note: Items are NOT cleared on switch/faint - they persist for competitive info.
    // They are only cleared when the battle ends (in clear()).

    // ═══════════════════════════════════════════════════════════════════════════
    // Faint/KO Tracking
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Mark a Pokemon as knocked out by name. Used when faint messages are received.
     */
    fun markAsKO(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for KO tracking")
            return
        }
        markAsKO(uuid)
    }

    /**
     * Mark a Pokemon as knocked out by UUID. Used when HP reaches 0.
     */
    fun markAsKO(uuid: UUID) {
        knockedOutPokemon.add(uuid)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Marked UUID $uuid as KO'd")
    }

    /**
     * Check if a Pokemon is knocked out.
     */
    fun isKO(uuid: UUID): Boolean = knockedOutPokemon.contains(uuid)

    /**
     * Get the UUID for a Pokemon by name. Public method for TeamIndicatorUI to use.
     */
    fun getPokemonUuid(pokemonName: String, preferAlly: Boolean? = null): UUID? {
        return resolvePokemonUuid(pokemonName, preferAlly)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Transform Tracking (Ditto)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Mark a Pokemon as transformed (via Transform move or Impostor ability).
     * Used by TeamIndicatorUI to know when to revert form display on faint.
     */
    fun markAsTransformed(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for transform tracking")
            return
        }
        markAsTransformed(uuid)
    }

    /**
     * Mark a Pokemon as transformed by UUID.
     */
    fun markAsTransformed(uuid: UUID) {
        transformedPokemon.add(uuid)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Marked UUID $uuid as transformed")
    }

    /**
     * Check if a Pokemon is currently transformed.
     */
    fun isTransformed(uuid: UUID): Boolean = transformedPokemon.contains(uuid)

    /**
     * Clear transform status for a Pokemon (on switch or faint).
     */
    fun clearTransformStatus(uuid: UUID) {
        if (transformedPokemon.remove(uuid)) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared transform status for UUID $uuid")
        }
    }

    /**
     * Clear transform status for a Pokemon by name.
     */
    fun clearTransformStatusByName(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return
        clearTransformStatus(uuid)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Move Tracking API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Track a revealed move for a Pokemon.
     * Called when a Pokemon uses a move in battle.
     * Also decrements PP for player's Pokemon if PP tracking is initialized.
     * Tracks usage count for estimating opponent PP.
     */
    fun addRevealedMove(pokemonName: String, moveName: String, targetName: String? = null, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for move tracking")
            return
        }
        val moves = revealedMoves.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }
        if (moves.add(moveName)) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName revealed move '$moveName'")
        }

        // Track usage count for estimating opponent PP
        val usageCounts = moveUsageCounts.computeIfAbsent(uuid) { ConcurrentHashMap() }
        usageCounts.compute(moveName) { _, count -> (count ?: 0) + 1 }

        // Decrement PP for player's Pokemon (if PP tracking is initialized)
        // Pass target name for Pressure check (costs 2 PP if target has Pressure)
        decrementPP(uuid, moveName, targetName)
    }

    /**
     * Get all revealed moves for a Pokemon.
     */
    fun getRevealedMoves(uuid: UUID): Set<String> = revealedMoves[uuid]?.toSet() ?: emptySet()

    /**
     * Get the usage count for a specific move.
     */
    fun getMoveUsageCount(uuid: UUID, moveName: String): Int {
        return moveUsageCounts[uuid]?.get(moveName) ?: 0
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ability Tracking API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Set the revealed ability for a Pokemon.
     * Called when an ability activates in battle (e.g., Intimidate, Flash Fire, etc.).
     */
    fun setRevealedAbility(pokemonName: String, abilityName: String, preferAlly: Boolean? = null) {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Unknown Pokemon '$pokemonName' for ability tracking")
            return
        }
        setRevealedAbility(uuid, abilityName)
        CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: $pokemonName ability revealed: $abilityName")
    }

    /**
     * Set the revealed ability for a Pokemon by UUID.
     */
    fun setRevealedAbility(uuid: UUID, abilityName: String) {
        // Normalize ability name (capitalize each word)
        val normalizedName = abilityName.split(" ", "_", "-")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        revealedAbilities[uuid] = normalizedName
    }

    /**
     * Get the revealed ability for a Pokemon, or null if not yet revealed.
     */
    fun getRevealedAbility(uuid: UUID): String? = revealedAbilities[uuid]

    /**
     * Get the revealed ability for a Pokemon by name.
     */
    fun getRevealedAbilityByName(pokemonName: String, preferAlly: Boolean? = null): String? {
        val uuid = resolvePokemonUuid(pokemonName, preferAlly) ?: return null
        return revealedAbilities[uuid]
    }

    /**
     * Clear the revealed ability for a Pokemon (e.g., when Transform reverts).
     */
    fun clearRevealedAbility(uuid: UUID) {
        if (revealedAbilities.remove(uuid) != null) {
            CobblemonExtendedBattleUI.LOGGER.debug("BattleStateTracker: Cleared revealed ability for UUID $uuid")
        }
    }

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
     * 2. "opposing" or "the opposing" prefix (indicates opponent's Pokemon)
     * 3. preferAlly hint
     * 4. First registered (fallback)
     */
    private fun resolvePokemonUuid(pokemonName: String, preferAlly: Boolean? = null): UUID? {
        var lookupName = pokemonName.lowercase()
        var ownerDeterminedSide: Boolean? = null  // true = ally, false = opponent

        // Check for "opposing" or "the opposing" prefix (indicates opponent's Pokemon)
        // These prefixes are used by Cobblemon in battle messages to identify opponent Pokemon
        val opposingPrefixes = listOf("the opposing ", "opposing ")
        for (prefix in opposingPrefixes) {
            if (lookupName.startsWith(prefix)) {
                lookupName = lookupName.removePrefix(prefix)
                ownerDeterminedSide = false  // This is definitely the opponent's Pokemon
                CobblemonExtendedBattleUI.LOGGER.debug(
                    "BattleStateTracker: Detected opponent prefix in '$pokemonName', using name '$lookupName'"
                )
                break
            }
        }

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

        val uuidList = nameToUuids[lookupName] ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug(
                "BattleStateTracker: Could not find Pokemon '$lookupName' (original: '$pokemonName') in registry"
            )
            return null
        }

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

package com.cobblemonextendedbattleui

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks HP changes and injects them into the battle log.
 * Called from BattleHealthChangeHandlerMixin when HP changes are detected.
 *
 * Also tracks last known HP percentage per Pokemon (by PNX identifier) to handle
 * rapid successive HP changes (e.g., multi-hit moves) where Cobblemon's stored
 * HP value may not have updated between packets.
 */
object DamageTracker {

    /**
     * Tracks last known HP percentage (0-100) per Pokemon, keyed by PNX identifier.
     * This ensures multi-hit moves calculate damage correctly by using our tracked
     * value instead of potentially stale Cobblemon values.
     */
    private val lastKnownHpPercent = ConcurrentHashMap<String, Float>()

    /**
     * Get the last known HP percentage for a Pokemon.
     * @param pnx The Pokemon's PNX identifier from the battle
     * @return The last known HP percentage (0-100), or null if not tracked yet
     */
    fun getLastKnownHpPercent(pnx: String): Float? = lastKnownHpPercent[pnx]

    /**
     * Update the tracked HP percentage for a Pokemon.
     * @param pnx The Pokemon's PNX identifier from the battle
     * @param percent The new HP percentage (0-100)
     */
    fun updateHpPercent(pnx: String, percent: Float) {
        lastKnownHpPercent[pnx] = percent
    }

    fun recordDamage(targetName: String, damagePercent: Float) {
        BattleLog.addHpChangeEntry("  → ${formatPercent(damagePercent)}% to $targetName")
    }

    fun recordHealing(targetName: String, healPercent: Float) {
        BattleLog.addHpChangeEntry("  → +${formatPercent(healPercent)}% to $targetName", isHealing = true)
    }

    /**
     * Format percentage for display - shows one decimal place to avoid rounding errors
     * that make multi-hit damage not add up correctly.
     * Strips trailing ".0" for clean display of whole numbers.
     */
    private fun formatPercent(percent: Float): String {
        val rounded = kotlin.math.round(percent * 10) / 10  // Round to 1 decimal
        return if (rounded == rounded.toLong().toFloat()) {
            // Whole number - display without decimal
            rounded.toLong().toString()
        } else {
            // Has decimal - show one decimal place
            String.format("%.1f", rounded)
        }
    }

    fun clear() {
        lastKnownHpPercent.clear()
    }
}

package com.cobblemonextendedbattleui

/**
 * Tracks HP changes and injects them into the battle log.
 * Called from BattleHealthChangeHandlerMixin when HP changes are detected.
 */
object DamageTracker {

    fun recordDamage(targetName: String, damagePercent: Float) {
        BattleLog.addHpChangeEntry("  → ${damagePercent.toInt()}% to $targetName")
    }

    fun recordHealing(targetName: String, healPercent: Float) {
        BattleLog.addHpChangeEntry("  → +${healPercent.toInt()}% to $targetName", isHealing = true)
    }

    fun clear() {
        // Called on battle end for consistency
    }
}

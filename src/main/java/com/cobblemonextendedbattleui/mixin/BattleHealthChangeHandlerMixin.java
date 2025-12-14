package com.cobblemonextendedbattleui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.net.battle.BattleHealthChangeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import com.cobblemonextendedbattleui.DamageTracker;
import net.minecraft.client.MinecraftClient;

/**
 * Intercepts HP changes to track damage/healing percentages for the battle log.
 *
 * Uses DamageTracker to maintain our own HP tracking per Pokemon (by PNX identifier).
 * This fixes issues with multi-hit moves where Cobblemon's stored HP value may not
 * update between rapid successive packets, causing incorrect damage calculations
 * (e.g., each hit showing damage from original HP instead of progressively reduced HP).
 */
@Mixin(value = BattleHealthChangeHandler.class, remap = false)
public class BattleHealthChangeHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void onHandlePre(BattleHealthChangePacket packet, MinecraftClient client, CallbackInfo ci) {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) return;

        try {
            String pnx = packet.getPnx();
            var result = battle.getPokemonFromPNX(pnx);
            var activePokemon = result.getSecond();
            ClientBattlePokemon pokemon = activePokemon.getBattlePokemon();

            if (pokemon != null) {
                float maxHp = pokemon.getMaxHp();
                float newHpValue = packet.getNewHealth();
                boolean isFlat = pokemon.isHpFlat();

                // Convert new HP value to percentage (0-100)
                float newPercent;
                if (isFlat && maxHp > 0) {
                    newPercent = (newHpValue / maxHp) * 100f;
                } else {
                    // Non-flat values are already 0.0-1.0 percentages
                    newPercent = newHpValue * 100f;
                }

                // Get old HP percentage - prefer our tracked value for accuracy,
                // fall back to Cobblemon's value only on first encounter
                Float trackedOldPercent = DamageTracker.INSTANCE.getLastKnownHpPercent(pnx);
                float oldPercent;

                if (trackedOldPercent != null) {
                    // Use our tracked value (handles multi-hit moves correctly)
                    oldPercent = trackedOldPercent;
                } else {
                    // First time seeing this Pokemon - use Cobblemon's current value
                    float oldHpValue = pokemon.getHpValue();
                    if (isFlat && maxHp > 0) {
                        oldPercent = (oldHpValue / maxHp) * 100f;
                    } else {
                        oldPercent = oldHpValue * 100f;
                    }
                }

                // Update our tracking with the new percentage BEFORE calculating damage
                // This ensures subsequent packets in a multi-hit move use the correct base
                DamageTracker.INSTANCE.updateHpPercent(pnx, newPercent);

                float hpChange = oldPercent - newPercent;
                String pokemonName = pokemon.getDisplayName().getString();

                if (hpChange > 0.5f) {
                    DamageTracker.INSTANCE.recordDamage(pokemonName, hpChange);
                } else if (hpChange < -0.5f) {
                    DamageTracker.INSTANCE.recordHealing(pokemonName, -hpChange);
                }
            }
        } catch (Exception e) {
            // Silent fail to avoid breaking gameplay
        }
    }
}

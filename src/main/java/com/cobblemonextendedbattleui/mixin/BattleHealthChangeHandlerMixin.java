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

import java.util.UUID;

/**
 * Intercepts HP changes to track damage/healing percentages for the battle log.
 *
 * Uses DamageTracker to maintain HP tracking per Pokemon by UUID. HP baselines are
 * pre-populated when Pokemon switch in (via BattleSwitchHandlerMixin), ensuring we
 * always have an accurate baseline for damage calculations.
 *
 * This approach handles:
 * - Multi-hit moves: Each hit uses the correct previous HP value
 * - Switch-ins: HP is captured from the switch packet before any changes
 * - Slot reuse: UUID tracking prevents confusion when different Pokemon occupy the same slot
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
                UUID uuid = pokemon.getUuid();
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

                // Get old HP percentage from our tracking (should be pre-populated by switch handler)
                Float trackedOldPercent = DamageTracker.INSTANCE.getLastKnownHpPercent(uuid);

                if (trackedOldPercent == null) {
                    // Edge case: HP change arrived before switch handler ran, or for initial battle Pokemon.
                    // Use the packet's new value as our baseline for future changes.
                    // This should rarely happen now that we have switch-in pre-population.
                    DamageTracker.INSTANCE.updateHpPercent(uuid, newPercent);
                    return;
                }

                // We have a tracked baseline - calculate the HP change
                float oldPercent = trackedOldPercent;

                // Update our tracking with the new percentage BEFORE calculating damage
                // This ensures subsequent packets in a multi-hit move use the correct base
                DamageTracker.INSTANCE.updateHpPercent(uuid, newPercent);

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

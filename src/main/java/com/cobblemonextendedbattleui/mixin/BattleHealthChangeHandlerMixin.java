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
 */
@Mixin(value = BattleHealthChangeHandler.class, remap = false)
public class BattleHealthChangeHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void onHandlePre(BattleHealthChangePacket packet, MinecraftClient client, CallbackInfo ci) {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) return;

        try {
            var result = battle.getPokemonFromPNX(packet.getPnx());
            var activePokemon = result.getSecond();
            ClientBattlePokemon pokemon = activePokemon.getBattlePokemon();

            if (pokemon != null) {
                float oldHpValue = pokemon.getHpValue();
                float maxHp = pokemon.getMaxHp();
                float newHpValue = packet.getNewHealth();
                boolean isFlat = pokemon.isHpFlat();

                float oldPercent;
                float newPercent;

                if (isFlat && maxHp > 0) {
                    oldPercent = (oldHpValue / maxHp) * 100f;
                    newPercent = (newHpValue / maxHp) * 100f;
                } else {
                    oldPercent = oldHpValue * 100f;
                    newPercent = newHpValue * 100f;
                }

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

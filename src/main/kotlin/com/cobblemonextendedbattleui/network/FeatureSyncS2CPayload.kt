package com.cobblemonextendedbattleui.network

import com.cobblemonextendedbattleui.CobblemonExtendedBattleUI
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * Server → client: feature toggle values the server owner chose to sync.
 * Only keys present in [entries] override the client's local config (server-forced).
 */
data class FeatureSyncS2CPayload(
    val schemaVersion: Int,
    val entries: List<Pair<String, Boolean>>
) : CustomPayload {

    override fun getId(): CustomPayload.Id<FeatureSyncS2CPayload> = ID

    companion object {
        val ID = CustomPayload.Id<FeatureSyncS2CPayload>(
            Identifier.of(CobblemonExtendedBattleUI.MOD_ID, "feature_sync")
        )

        const val SCHEMA_VERSION: Int = 1
        private const val MAX_FEATURE_SYNC_ENTRIES: Int = 64

        val CODEC: PacketCodec<RegistryByteBuf, FeatureSyncS2CPayload> = CustomPayload.codecOf(
            { payload, buf ->
                buf.writeVarInt(payload.schemaVersion)
                buf.writeVarInt(payload.entries.size)
                for ((key, value) in payload.entries) {
                    buf.writeString(key)
                    buf.writeBoolean(value)
                }
            },
            { buf ->
                val version = buf.readVarInt()
                val n = buf.readVarInt().coerceIn(0, MAX_FEATURE_SYNC_ENTRIES)
                val list = ArrayList<Pair<String, Boolean>>(n)
                repeat(n) {
                    list.add(buf.readString() to buf.readBoolean())
                }
                FeatureSyncS2CPayload(version, list)
            }
        )
    }
}

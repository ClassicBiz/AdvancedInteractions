package com.luminacity.advancedinteractions.network;

import com.luminacity.advancedinteractions.AdvancedInteractions;
import com.luminacity.advancedinteractions.network.c2s.C2S_HologramClick;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestPlaceholderRefresh;
import com.luminacity.advancedinteractions.network.s2c.S2C_ForceResync;
import com.luminacity.advancedinteractions.network.s2c.S2C_PlaceholderValues;
import com.luminacity.advancedinteractions.network.s2c.S2C_PlayerPlaceholders;
import com.luminacity.advancedinteractions.network.s2c.S2C_SyncHolograms;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    /**
     * Bump this whenever packet schemas change.
     */
    private static final String PROTO = "11"; // 11: confirmations, input fields
    public static SimpleChannel CHANNEL;

    public static void register() {
        CHANNEL = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(AdvancedInteractions.MODID, "main"))
                .networkProtocolVersion(() -> PROTO)
                .clientAcceptedVersions(PROTO::equals)
                .serverAcceptedVersions(PROTO::equals)
                .simpleChannel();

        int id = 0;
        CHANNEL.registerMessage(id++, S2C_SyncHolograms.class, S2C_SyncHolograms::encode, S2C_SyncHolograms::decode, S2C_SyncHolograms::handle);
        CHANNEL.registerMessage(id++, S2C_PlaceholderValues.class, S2C_PlaceholderValues::encode, S2C_PlaceholderValues::decode, S2C_PlaceholderValues::handle);
        CHANNEL.registerMessage(id++, S2C_PlayerPlaceholders.class, S2C_PlayerPlaceholders::encode, S2C_PlayerPlaceholders::decode, S2C_PlayerPlaceholders::handle);
        CHANNEL.registerMessage(id++, S2C_ForceResync.class, S2C_ForceResync::encode, S2C_ForceResync::decode, S2C_ForceResync::handle);

        CHANNEL.registerMessage(id++, C2S_HologramClick.class, C2S_HologramClick::encode, C2S_HologramClick::decode, C2S_HologramClick::handle);
        CHANNEL.registerMessage(id++, C2S_RequestSync.class, C2S_RequestSync::encode, C2S_RequestSync::decode, C2S_RequestSync::handle);
        CHANNEL.registerMessage(id++, C2S_RequestPlaceholderRefresh.class, C2S_RequestPlaceholderRefresh::encode, C2S_RequestPlaceholderRefresh::decode, C2S_RequestPlaceholderRefresh::handle);

        // In-game editor
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.editor.C2S_EditorRequest.class,
                com.luminacity.advancedinteractions.network.editor.C2S_EditorRequest::encode,
                com.luminacity.advancedinteractions.network.editor.C2S_EditorRequest::decode,
                com.luminacity.advancedinteractions.network.editor.C2S_EditorRequest::handle);
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.editor.C2S_EditorSave.class,
                com.luminacity.advancedinteractions.network.editor.C2S_EditorSave::encode,
                com.luminacity.advancedinteractions.network.editor.C2S_EditorSave::decode,
                com.luminacity.advancedinteractions.network.editor.C2S_EditorSave::handle);
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.editor.S2C_EditorData.class,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorData::encode,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorData::decode,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorData::handle);
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.editor.S2C_EditorList.class,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorList::encode,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorList::decode,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorList::handle);
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.editor.S2C_EditorReply.class,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorReply::encode,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorReply::decode,
                com.luminacity.advancedinteractions.network.editor.S2C_EditorReply::handle);

        // Confirmations and input fields
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.ConfirmPackets.Ask.class,
                com.luminacity.advancedinteractions.network.ConfirmPackets.Ask::encode,
                com.luminacity.advancedinteractions.network.ConfirmPackets.Ask::decode,
                com.luminacity.advancedinteractions.network.ConfirmPackets.Ask::handle);
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.ConfirmPackets.Answer.class,
                com.luminacity.advancedinteractions.network.ConfirmPackets.Answer::encode,
                com.luminacity.advancedinteractions.network.ConfirmPackets.Answer::decode,
                com.luminacity.advancedinteractions.network.ConfirmPackets.Answer::handle);
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.c2s.C2S_InputSet.class,
                com.luminacity.advancedinteractions.network.c2s.C2S_InputSet::encode,
                com.luminacity.advancedinteractions.network.c2s.C2S_InputSet::decode,
                com.luminacity.advancedinteractions.network.c2s.C2S_InputSet::handle);

        // Server images
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.editor.ImagePackets.Request.class,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Request::encode,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Request::decode,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Request::handle);
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.editor.ImagePackets.Data.class,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Data::encode,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Data::decode,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Data::handle);
        CHANNEL.registerMessage(id++, com.luminacity.advancedinteractions.network.editor.ImagePackets.Invalidate.class,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Invalidate::encode,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Invalidate::decode,
                com.luminacity.advancedinteractions.network.editor.ImagePackets.Invalidate::handle);
    }

    public static void sendTo(ServerPlayer p, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), msg);
    }

    public static void broadcastToDimension(ServerLevel level, Object msg) {
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> level.dimension()), msg);
    }
}

package com.luminacity.advancedinteractions.network.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/** Shared helpers for the editor packets: compressed NBT and chunking limits. */
public final class EditorNet {
    private EditorNet() {}

    /** Client-to-server payloads are capped at 32 KiB by vanilla; stay well under per chunk. */
    public static final int CHUNK_BYTES = 30_000;
    /** Largest hologram (compressed) the server accepts from an editor. */
    public static final int MAX_SAVE_BYTES = 4 * 1024 * 1024;
    /** Largest hologram (uncompressed NBT) read back, guarding against zip bombs. */
    public static final long MAX_NBT_BYTES = 32L * 1024 * 1024;

    public static byte[] compress(CompoundTag tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not compress hologram", e);
        }
    }

    public static CompoundTag decompress(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            return NbtIo.read(in, new NbtAccounter(MAX_NBT_BYTES));
        }
    }
}

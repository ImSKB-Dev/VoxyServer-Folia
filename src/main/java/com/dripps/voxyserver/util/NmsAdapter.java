package com.dripps.voxyserver.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;

public class NmsAdapter {
    public static LevelChunk getHandle(Chunk chunk) {
        if (chunk instanceof CraftChunk craftChunk && craftChunk.getHandle(ChunkStatus.FULL) instanceof LevelChunk levelChunk) {
            return levelChunk;
        }
        return null;
    }

    public static ServerLevel getHandle(World world) {
        if (world instanceof CraftWorld craftWorld) {
            return craftWorld.getHandle();
        }
        return null;
    }
}

package com.dripps.voxyserver.server;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.util.NmsAdapter;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkVoxelizer implements Listener {
    private static final long RETRY_INTERVAL_TICKS = 2L;

    private final ServerLodEngine engine;
    private final LodStreamingService streamingService;
    private final boolean generateOnChunkLoad;
    private final boolean ingestOnChunkUnload;
    private final ConcurrentHashMap<PendingChunk, Long> pendingChunkRetries = new ConcurrentHashMap<>();
    private volatile long currentTick;

    private record PendingChunk(String dimension, int chunkX, int chunkZ) {}

    public ChunkVoxelizer(ServerLodEngine engine, LodStreamingService streamingService, VoxyServerConfig config) {
        this.engine = engine;
        this.streamingService = streamingService;
        this.generateOnChunkLoad = config.generateOnChunkLoad;
        this.ingestOnChunkUnload = !config.dirtyTrackingEnabled;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!generateOnChunkLoad) return;
        Chunk chunk = event.getChunk();
        World world = event.getWorld();
        ServerLevel level = NmsAdapter.getHandle(world);
        LevelChunk levelChunk = NmsAdapter.getHandle(chunk);
        if (level == null || levelChunk == null) return;

        String dimension = world.getKey().toString();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        if (streamingService != null) {
            streamingService.onChunkLoadStateChanged(dimension, chunkX, chunkZ, true);
        }
        if (ingestChunk(level, levelChunk, true)) {
            pendingChunkRetries.remove(new PendingChunk(dimension, chunkX, chunkZ));
            return;
        }

        scheduleRetry(dimension, chunkX, chunkZ);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        World world = event.getWorld();
        ServerLevel level = NmsAdapter.getHandle(world);
        LevelChunk levelChunk = NmsAdapter.getHandle(chunk);

        String dimension = world.getKey().toString();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        if (streamingService != null) {
            streamingService.onChunkLoadStateChanged(dimension, chunkX, chunkZ, false);
        }
        pendingChunkRetries.remove(new PendingChunk(dimension, chunkX, chunkZ));
        if (ingestOnChunkUnload && level != null && levelChunk != null) {
            ingestChunk(level, levelChunk, false);
        }
    }

    public boolean revoxelizeChunk(ServerLevel level, LevelChunk chunk) {
        return ingestChunk(level, chunk, false);
    }

    public boolean ingestChunk(ServerLevel level, LevelChunk chunk, boolean markPendingResend) {
        WorldEngine world = engine.getOrCreate(level);
        if (world == null) return false;

        String dimension = level.dimension().identifier().toString();
        List<Integer> pendingSectionYs = markPendingResend ? markPendingChunkSections(dimension, chunk) : List.of();

        engine.markChunkPossiblyPresent(level, chunk);

        boolean enqueued = engine.getIngestService().enqueueIngest(world, chunk);
        if (!enqueued && !pendingSectionYs.isEmpty()) {
            clearPendingChunkSections(dimension, chunk, pendingSectionYs);
        }
        return enqueued;
    }

    private List<Integer> markPendingChunkSections(String dimension, LevelChunk chunk) {
        List<Integer> pendingSectionYs = new ArrayList<>();
        int chunkSectionY = chunk.getMinSectionY() - 1;
        int lastWorldSecY = Integer.MIN_VALUE;
        for (var ignored : chunk.getSections()) {
            chunkSectionY++;
            int worldSecY = chunkSectionY >> 1;
            if (worldSecY == lastWorldSecY) {
                continue;
            }

            lastWorldSecY = worldSecY;
            pendingSectionYs.add(worldSecY);
            if (streamingService != null) {
                streamingService.markChunkPendingInitialLoad(dimension, chunk.getPos().x(), worldSecY, chunk.getPos().z());
            }
        }
        return pendingSectionYs;
    }

    private void clearPendingChunkSections(String dimension, LevelChunk chunk, List<Integer> pendingSectionYs) {
        for (int worldSecY : pendingSectionYs) {
            if (streamingService != null) {
                streamingService.clearChunkPendingDirty(dimension, chunk.getPos().x(), worldSecY, chunk.getPos().z());
            }
        }
    }

    private void scheduleRetry(String dimension, int chunkX, int chunkZ) {
        pendingChunkRetries.put(
                new PendingChunk(dimension, chunkX, chunkZ),
                currentTick + RETRY_INTERVAL_TICKS
        );
    }

    public void tick() {
        currentTick++;
        if (pendingChunkRetries.isEmpty()) {
            return;
        }

        for (Map.Entry<PendingChunk, Long> entry : pendingChunkRetries.entrySet()) {
            if (entry.getValue() > currentTick) {
                continue;
            }

            PendingChunk pendingChunk = entry.getKey();
            pendingChunkRetries.remove(pendingChunk, entry.getValue());
        }
    }
}

package com.dripps.voxyserver.server;

import com.dripps.voxyserver.util.NmsAdapter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DirtyTracker implements Listener {
    public static volatile DirtyTracker INSTANCE;

    private final Plugin plugin;
    private final ConcurrentHashMap<DirtySection, Boolean> dirtySections = new ConcurrentHashMap<>();
    private final ChunkVoxelizer voxelizer;
    private final LodStreamingService streamingService;
    private final int flushInterval;
    private int tickCounter = 0;

    private record DirtySection(String dimension, int chunkX, int sectionY, int chunkZ) {}
    private record ChunkPosDim(String dimension, int chunkX, int chunkZ) {}

    public DirtyTracker(Plugin plugin, ChunkVoxelizer voxelizer, LodStreamingService streamingService, int flushInterval) {
        this.plugin = plugin;
        this.voxelizer = voxelizer;
        this.streamingService = streamingService;
        this.flushInterval = flushInterval;
    }

    public void markDirty(World world, int chunkX, int blockY, int chunkZ) {
        String dim = world.getKey().toString();
        int sectionY = blockY >> 5;
        DirtySection dirtySection = new DirtySection(dim, chunkX, sectionY, chunkZ);
        dirtySections.put(dirtySection, Boolean.TRUE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        markDirty(b.getWorld(), b.getX() >> 4, b.getY(), b.getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block b = event.getBlock();
        markDirty(b.getWorld(), b.getX() >> 4, b.getY(), b.getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            markDirty(b.getWorld(), b.getX() >> 4, b.getY(), b.getZ() >> 4);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            markDirty(b.getWorld(), b.getX() >> 4, b.getY(), b.getZ() >> 4);
        }
    }

    public void tick() {
        if (++tickCounter < flushInterval) {
            return;
        }
        tickCounter = 0;

        if (dirtySections.isEmpty()) {
            return;
        }

        Set<DirtySection> toProcess = ConcurrentHashMap.newKeySet();
        var iter = dirtySections.keySet().iterator();
        while (iter.hasNext()) {
            toProcess.add(iter.next());
            iter.remove();
        }

        Map<ChunkPosDim, Set<Integer>> sectionsByChunk = new HashMap<>();
        for (DirtySection ds : toProcess) {
            ChunkPosDim cpd = new ChunkPosDim(ds.dimension, ds.chunkX, ds.chunkZ);
            sectionsByChunk.computeIfAbsent(cpd, ignored -> new HashSet<>()).add(ds.sectionY);
        }

        for (var entry : sectionsByChunk.entrySet()) {
            ChunkPosDim chunkPos = entry.getKey();
            Set<Integer> sectionYs = entry.getValue();

            World world = Bukkit.getWorld(org.bukkit.NamespacedKey.fromString(chunkPos.dimension));
            if (world == null) continue;

            Bukkit.getRegionScheduler().run(plugin, world, chunkPos.chunkX, chunkPos.chunkZ, task -> {
                ServerLevel level = NmsAdapter.getHandle(world);
                if (level == null) return;

                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.chunkX, chunkPos.chunkZ);
                if (chunk == null) return;

                for (int sectionY : sectionYs) {
                    streamingService.markChunkPendingInitialLoad(chunkPos.dimension, chunkPos.chunkX, sectionY, chunkPos.chunkZ);
                }

                if (!voxelizer.revoxelizeChunk(level, chunk)) {
                    for (int sectionY : sectionYs) {
                        streamingService.clearChunkPendingDirty(chunkPos.dimension, chunkPos.chunkX, sectionY, chunkPos.chunkZ);
                    }
                }
            });
        }
    }
}

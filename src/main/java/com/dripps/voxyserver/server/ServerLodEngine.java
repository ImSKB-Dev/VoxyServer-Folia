package com.dripps.voxyserver.server;

import com.dripps.voxyserver.util.NmsAdapter;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ServerLodEngine extends VoxyInstance {
    @FunctionalInterface
    public interface DirtySectionListener {
        void onSectionDirty(String dimension, long sectionKey);
    }

    private final Path basePath;
    private final SectionSerializationStorage.Config storageConfig;
    private final ConcurrentHashMap<WorldIdentifier, String> dimensionsByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorldIdentifier, WorldEngine> activeEngineCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorldIdentifier, StoredSectionPresenceIndex> presenceIndexes = new ConcurrentHashMap<>();
    private final ExecutorService presenceIndexExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoxyServer Presence Index");
        t.setDaemon(true);
        return t;
    });
    private final Logger logger;
    private volatile DirtySectionListener dirtySectionListener;

    public ServerLodEngine(Path worldFolder, Logger logger) {
        super();
        this.basePath = worldFolder.resolve("voxyserver");
        this.logger = logger;
        this.storageConfig = StorageConfigUtil.createDefaultSerializer();
        this.updateDedicatedThreads();
        logger.info("Server LOD Engine started, storage at " + this.basePath);
    }

    public void updateDedicatedThreadsCount(int threads) {
        this.setNumThreads(threads);
    }

    public void setDirtySectionListener(DirtySectionListener dirtySectionListener) {
        this.dirtySectionListener = dirtySectionListener;
    }

    public static WorldIdentifier getWorldIdentifier(World world) {
        if (world == null) {
            return null;
        }
        String dimStr = world.getKey().toString();
        var key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimStr));
        var dim = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(dimStr));
        return new WorldIdentifier(key, world.getSeed(), dim);
    }

    public static WorldIdentifier getWorldIdentifier(ServerLevel level) {
        if (level == null) {
            return null;
        }
        String dimStr = level.dimension().identifier().toString();
        var key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimStr));
        var dim = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(dimStr));
        return new WorldIdentifier(key, level.getSeed(), dim);
    }

    public WorldEngine getNullable(ServerLevel level) {
        WorldIdentifier worldId = getWorldIdentifier(level);
        if (worldId == null) {
            return null;
        }
        return activeEngineCache.get(worldId);
    }

    public WorldEngine getNullable(World world) {
        WorldIdentifier worldId = getWorldIdentifier(world);
        if (worldId == null) {
            return null;
        }
        return activeEngineCache.get(worldId);
    }

    public WorldEngine getOrCreate(World world) {
        ServerLevel level = NmsAdapter.getHandle(world);
        if (level != null) {
            return getOrCreate(level);
        }
        WorldIdentifier id = getWorldIdentifier(world);
        return getOrCreate(id, world.getKey().toString());
    }

    public WorldEngine getOrCreate(ServerLevel level) {
        WorldIdentifier worldId = getWorldIdentifier(level);
        if (worldId == null) {
            return null;
        }
        return this.getOrCreate(worldId, level.dimension().identifier().toString());
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier, String dimension) {
        if (identifier == null || !this.isRunning()) {
            return null;
        }
        WorldEngine cached = activeEngineCache.get(identifier);
        if (cached != null) {
            return cached;
        }

        this.dimensionsByWorld.put(identifier, dimension);
        WorldEngine world;
        try {
            world = super.getOrCreate(identifier);
        } catch (Exception e) {
            logger.severe("Could not get or create world for " + identifier + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
        if (world == null) {
            return null;
        }
        this.activeEngineCache.put(identifier, world);
        this.attachDirtyCallback(identifier, world);
        this.ensurePresenceIndex(identifier, world);
        return world;
    }

    @Override
    public WorldEngine getOrCreate(WorldIdentifier identifier) {
        if (!this.isRunning()) {
            return null;
        }
        WorldEngine cached = activeEngineCache.get(identifier);
        if (cached != null) {
            return cached;
        }

        WorldEngine world;
        try {
            world = super.getOrCreate(identifier);
        } catch (Exception e) {
            logger.severe("Could not get or create world for " + identifier + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
        if (world == null) {
            return null;
        }
        this.activeEngineCache.put(identifier, world);
        this.attachDirtyCallback(identifier, world);
        this.ensurePresenceIndex(identifier, world);
        return world;
    }

    public boolean mayHaveStoredSection(WorldIdentifier identifier, WorldEngine world, long sectionKey) {
        if (identifier == null || world == null || WorldEngine.getLevel(sectionKey) != 0) {
            return true;
        }
        return this.ensurePresenceIndex(identifier, world).mayContain(sectionKey);
    }

    public void markChunkPossiblyPresent(ServerLevel level, LevelChunk chunk) {
        WorldIdentifier identifier = getWorldIdentifier(level);
        if (identifier == null) {
            return;
        }

        StoredSectionPresenceIndex index = this.presenceIndexes.get(identifier);
        if (index == null) {
            return;
        }

        int worldSecX = chunk.getPos().x() >> 1;
        int worldSecZ = chunk.getPos().z() >> 1;
        int chunkSectionY = chunk.getMinSectionY() - 1;
        int lastWorldSecY = Integer.MIN_VALUE;
        for (var ignored : chunk.getSections()) {
            chunkSectionY++;
            int worldSecY = chunkSectionY >> 1;
            if (worldSecY == lastWorldSecY) {
                continue;
            }
            lastWorldSecY = worldSecY;
            index.add(WorldEngine.getWorldSectionId(0, worldSecX, worldSecY, worldSecZ));
        }
    }

    public void invalidatePresenceIndex(ServerLevel level) {
        WorldIdentifier identifier = getWorldIdentifier(level);
        if (identifier == null) {
            return;
        }

        WorldEngine world = this.getOrCreate(level);
        if (world == null) {
            return;
        }

        StoredSectionPresenceIndex index = new StoredSectionPresenceIndex();
        this.presenceIndexes.put(identifier, index);
        this.schedulePresenceIndexBuild(identifier, world, index);
    }

    @Override
    public void shutdown() {
        this.activeEngineCache.clear();
        this.presenceIndexExecutor.shutdownNow();
        try {
            this.presenceIndexExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        super.shutdown();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.storageConfig.build(ctx);
    }

    private void attachDirtyCallback(WorldIdentifier identifier, WorldEngine world) {
        if (world == null) {
            return;
        }

        String dimension = this.dimensionsByWorld.get(identifier);
        DirtySectionListener listener = this.dirtySectionListener;
        if (dimension == null || listener == null) {
            return;
        }

        world.setDirtyCallback((section, updateFlags, neighborMsk) -> {
            if (section.lvl != 0) {
                return;
            }
            listener.onSectionDirty(dimension, section.key);
        });
    }

    private StoredSectionPresenceIndex ensurePresenceIndex(WorldIdentifier identifier, WorldEngine world) {
        StoredSectionPresenceIndex index = this.presenceIndexes.computeIfAbsent(identifier, ignored -> new StoredSectionPresenceIndex());
        if (!index.isReady()) {
            this.schedulePresenceIndexBuild(identifier, world, index);
        }
        return index;
    }

    private void schedulePresenceIndexBuild(WorldIdentifier identifier, WorldEngine world, StoredSectionPresenceIndex index) {
        if (world == null || !index.tryScheduleBuild()) {
            return;
        }

        var filter = index.createBuildFilter();
        world.acquireRef();
        try {
            this.presenceIndexExecutor.execute(() -> {
                try {
                    world.storage.iteratePositions(0, key -> index.addTo(filter, key));
                    index.completeBuild(filter);
                } catch (Exception e) {
                    logger.warning("Failed to build presence index for " + identifier.getLongHash() + ": " + e.getMessage());
                    index.failBuild();
                } finally {
                    world.releaseRef();
                }
            });
        } catch (RejectedExecutionException e) {
            world.releaseRef();
            index.failBuild();
        }
    }
}

package com.dripps.voxyserver;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.server.ChunkVoxelizer;
import com.dripps.voxyserver.server.DirtyTracker;
import com.dripps.voxyserver.server.LodStreamingService;
import com.dripps.voxyserver.server.ServerLodEngine;
import com.dripps.voxyserver.server.VoxyServerCommands;
import com.dripps.voxyserver.server.WorldImportCoordinator;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class VoxyServerPlugin extends JavaPlugin {
    private static VoxyServerPlugin instance;
    private VoxyServerConfig config;
    private ServerLodEngine lodEngine;
    private ChunkVoxelizer chunkVoxelizer;
    private LodStreamingService streamingService;
    private WorldImportCoordinator importCoordinator;
    private DirtyTracker dirtyTracker;

    public static VoxyServerPlugin getInstance() {
        return instance;
    }

    public VoxyServerConfig getVoxyConfig() {
        return config;
    }

    @Override
    public void onEnable() {
        instance = this;
        Path dataFolder = getDataFolder().toPath();
        config = VoxyServerConfig.load(dataFolder, getLogger());

        getLogger().info("Initializing VoxyServer Folia Plugin...");

        lodEngine = new ServerLodEngine(dataFolder, getLogger());
        lodEngine.updateDedicatedThreadsCount(config.workerThreads);

        streamingService = new LodStreamingService(this, lodEngine, config);
        streamingService.register();

        chunkVoxelizer = new ChunkVoxelizer(lodEngine, streamingService, config);
        Bukkit.getPluginManager().registerEvents(chunkVoxelizer, this);

        importCoordinator = new WorldImportCoordinator(lodEngine, streamingService);

        if (config.dirtyTrackingEnabled) {
            dirtyTracker = new DirtyTracker(this, chunkVoxelizer, streamingService, config.dirtyTrackingInterval);
            DirtyTracker.INSTANCE = dirtyTracker;
            Bukkit.getPluginManager().registerEvents(dirtyTracker, this);

            Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> {
                if (dirtyTracker != null) {
                    dirtyTracker.tick();
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        long tickIntervalMs = Math.max(50L, config.tickInterval * 50L);
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> {
            if (streamingService != null) {
                streamingService.tick();
            }
            if (chunkVoxelizer != null) {
                chunkVoxelizer.tick();
            }
        }, tickIntervalMs, tickIntervalMs, TimeUnit.MILLISECONDS);

        VoxyServerCommands commandHandler = new VoxyServerCommands(() -> importCoordinator);
        var cmd = getCommand("voxyserver");
        if (cmd != null) {
            cmd.setExecutor(commandHandler);
            cmd.setTabCompleter(commandHandler);
        }

        getLogger().info("VoxyServer engine successfully started.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down VoxyServer engine...");
        DirtyTracker.INSTANCE = null;
        dirtyTracker = null;

        if (importCoordinator != null) {
            importCoordinator.shutdown();
            importCoordinator = null;
        }
        if (streamingService != null) {
            streamingService.shutdown();
            streamingService = null;
        }
        if (lodEngine != null) {
            lodEngine.shutdown();
            lodEngine = null;
        }
        chunkVoxelizer = null;
        instance = null;
        getLogger().info("VoxyServer stopped cleanly.");
    }
}

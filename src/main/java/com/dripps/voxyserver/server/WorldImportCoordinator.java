package com.dripps.voxyserver.server;

import com.dripps.voxyserver.util.NmsAdapter;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class WorldImportCoordinator {
    private final Object lock = new Object();
    private final ServerLodEngine engine;
    private final LodStreamingService streamingService;
    private final Deque<ImportRequest> queue = new ArrayDeque<>();
    private ActiveImport activeImport;
    private long activeRunId;
    private long nextRunId = 1L;

    public WorldImportCoordinator(ServerLodEngine engine, LodStreamingService streamingService) {
        this.engine = engine;
        this.streamingService = streamingService;
    }

    public boolean isImportRunning() {
        synchronized (this.lock) {
            return this.activeImport != null || !this.queue.isEmpty();
        }
    }

    public boolean startAll(CommandSender source) {
        int queuedCount = 0;
        for (World world : Bukkit.getWorlds()) {
            if (this.startDimension(source, world)) {
                queuedCount++;
            }
        }
        return queuedCount > 0;
    }

    public boolean startCurrent(CommandSender source) {
        if (!(source instanceof org.bukkit.entity.Player player)) {
            sendFailure(source, "current can only be used by a player");
            return false;
        }
        return this.startDimension(source, player.getWorld());
    }

    public boolean startDimension(CommandSender source, String dimensionName) {
        World world = Bukkit.getWorld(org.bukkit.NamespacedKey.fromString(dimensionName));
        if (world == null) {
            for (World w : Bukkit.getWorlds()) {
                if (w.getName().equalsIgnoreCase(dimensionName) || w.getKey().toString().equalsIgnoreCase(dimensionName)) {
                    world = w;
                    break;
                }
            }
        }
        if (world == null) {
            sendFailure(source, "dimension " + dimensionName + " not found");
            return false;
        }
        return this.startDimension(source, world);
    }

    public boolean startDimension(CommandSender source, World world) {
        Path regionPath = getRegionPath(world);
        if (!Files.isDirectory(regionPath)) {
            sendFailure(source, "no region folder found for " + world.getKey());
            return false;
        }

        long runId;
        synchronized (this.lock) {
            if (this.activeImport != null) {
                sendFailure(source, "an import is already running");
                return false;
            }
            runId = this.nextRunId++;
            this.activeRunId = runId;
            this.queue.clear();
            this.queue.addLast(new ImportRequest(world, regionPath, source));
        }

        sendSuccess(source, "queued import for " + world.getKey());
        this.startNext(runId);
        return true;
    }

    public boolean cancel(CommandSender source) {
        ActiveImport active;
        int removed;
        synchronized (this.lock) {
            active = this.activeImport;
            removed = this.queue.size();
            this.queue.clear();
            this.activeRunId = 0L;
        }

        if (active == null) {
            if (removed == 0) {
                sendFailure(source, "no import is running");
                return false;
            }
            sendSuccess(source, "cleared queued imports");
            return true;
        }

        active.cancelled = true;
        java.util.concurrent.CompletableFuture.runAsync(active.importer::shutdown);
        sendSuccess(source, "cancelled import for " + active.dimensionId);
        return true;
    }

    public void shutdown() {
        ActiveImport active;
        synchronized (this.lock) {
            this.queue.clear();
            this.activeRunId = 0L;
            active = this.activeImport;
        }
        if (active != null) {
            active.cancelled = true;
            java.util.concurrent.CompletableFuture.runAsync(active.importer::shutdown);
        }
    }

    private void startNext(long runId) {
        ImportRequest request;
        synchronized (this.lock) {
            if (runId != this.activeRunId || this.activeImport != null) {
                return;
            }
            request = this.queue.pollFirst();
            if (request == null) {
                this.activeRunId = 0L;
                return;
            }
        }

        WorldIdentifier worldId = ServerLodEngine.getWorldIdentifier(request.world);
        if (worldId == null) {
            sendFailure(request.source, "could not make up voxy world for " + request.dimensionId);
            this.onImportFinished(runId, null, request, false, 0);
            return;
        }

        var worldEngine = this.engine.getOrCreate(worldId, request.world.getKey().toString());
        if (worldEngine == null) {
            sendFailure(request.source, "could not create voxy world for " + request.dimensionId);
            this.onImportFinished(runId, null, request, false, 0);
            return;
        }

        worldEngine.markActive();
        try {
            worldEngine.acquireRef();
        } catch (IllegalStateException e) {
            sendFailure(request.source, "voxy world for " + request.dimensionId + " is not active");
            this.onImportFinished(runId, null, request, false, 0);
            return;
        }

        WorldImporter importer = new WorldImporter(
                worldEngine,
                NmsAdapter.getHandle(request.world),
                this.engine.getServiceManager(),
                this.engine.savingServiceRateLimiter
        );
        importer.importRegionDirectoryAsync(request.regionPath.toFile());

        ActiveImport active = new ActiveImport(request, importer);
        synchronized (this.lock) {
            if (runId != this.activeRunId) {
                importer.shutdown();
                try {
                    worldEngine.releaseRef();
                } catch (Exception ignored) {}
                return;
            }
            this.activeImport = active;
        }

        sendSuccess(request.source, "starting import for " + request.dimensionId);

        importer.runImport(
                (finished, outOf) -> {
                    active.processedChunks.set(finished);
                    active.estimatedChunks.set(outOf);

                    long now = System.currentTimeMillis();
                    long elapsed = now - active.lastUpdateMs;
                    if (elapsed < 1000L) {
                        return;
                    }
                    int delta = finished - active.lastSnapshotChunks;
                    double cps = delta / (elapsed / 1000.0);
                    active.cps = cps;
                    active.lastSnapshotChunks = finished;
                    active.lastUpdateMs = now;

                    int total = Math.max(finished, outOf);
                    int remaining = total - finished;
                    double cumulativeCps = finished / Math.max(1.0, (now - active.startedAtMs) / 1000.0);
                    long etaMs = cumulativeCps > 0 ? (long) (remaining / cumulativeCps * 1000) : -1L;
                    String cpsStr = String.format("%.1f", cps);
                    String etaStr = etaMs >= 0 ? " ETA " + formatDuration(etaMs) : "";

                    String msg = "importing "
                            + active.dimensionId
                            + " "
                            + finished
                            + "/"
                            + total
                            + " chunks at "
                            + cpsStr
                            + " c/s"
                            + etaStr;
                    sendSuccess(request.source, msg);
                },
                total -> this.onImportFinished(runId, active, request, true, total)
        );
    }

    private void onImportFinished(long runId, ActiveImport active, ImportRequest request, boolean completed, int totalChunks) {
        boolean cancelled = active != null && active.cancelled;

        try {
            WorldIdentifier id = ServerLodEngine.getWorldIdentifier(request.world);
            if (id != null) {
                var worldEngine = this.engine.getNullable(request.world);
                if (worldEngine != null) {
                    worldEngine.releaseRef();
                }
            }
        } catch (Exception ignored) {}

        synchronized (this.lock) {
            if (this.activeImport != null && Objects.equals(this.activeImport, active)) {
                this.activeImport = null;
            }
        }

        ServerLevel level = NmsAdapter.getHandle(request.world);

        if (completed && !cancelled) {
            if (level != null) {
                this.engine.invalidatePresenceIndex(level);
                this.streamingService.clearDimensionForReadyPlayers(level);
            }
            String msg = "finished import for "
                    + request.dimensionId
                    + " with "
                    + totalChunks
                    + " chunks";
            sendSuccess(request.source, msg);
            this.startNext(runId);
            return;
        }

        if (cancelled) {
            String msg = "import cancelled for " + request.dimensionId;
            sendSuccess(request.source, msg);
        } else {
            String msg = "import ended early for " + request.dimensionId;
            sendFailure(request.source, msg);
        }

        synchronized (this.lock) {
            this.queue.clear();
            if (this.activeRunId == runId) {
                this.activeRunId = 0L;
            }
        }
    }

    private static Path getRegionPath(World world) {
        return world.getWorldFolder().toPath().resolve("region");
    }

    private static void sendSuccess(CommandSender source, String message) {
        source.sendMessage("§a[VoxyServer] " + message);
    }

    private static void sendFailure(CommandSender source, String message) {
        source.sendMessage("§c[VoxyServer] " + message);
    }

    private static String formatDuration(long elapsedMs) {
        long seconds = elapsedMs / 1000L;
        long minutes = seconds / 60L;
        long remSeconds = seconds % 60L;
        if (minutes > 0) {
            return minutes + "m " + remSeconds + "s";
        }
        return Math.max(1L, seconds) + "s";
    }

    private static final class ImportRequest {
        private final World world;
        private final Path regionPath;
        private final CommandSender source;
        private final String dimensionId;

        private ImportRequest(World world, Path regionPath, CommandSender source) {
            this.world = world;
            this.regionPath = regionPath;
            this.source = source;
            this.dimensionId = world.getKey().toString().toLowerCase(Locale.ROOT);
        }
    }

    private static final class ActiveImport {
        private final WorldImporter importer;
        private final String dimensionId;
        private final long startedAtMs;
        private final AtomicInteger processedChunks = new AtomicInteger();
        private final AtomicInteger estimatedChunks = new AtomicInteger();
        private volatile long lastUpdateMs;
        private volatile int lastSnapshotChunks;
        private volatile double cps;
        private volatile boolean cancelled;

        private ActiveImport(ImportRequest request, WorldImporter importer) {
            this.importer = importer;
            this.dimensionId = request.dimensionId;
            this.startedAtMs = System.currentTimeMillis();
            this.lastUpdateMs = this.startedAtMs;
        }
    }
}

package com.dripps.voxyserver.server;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.network.LODBulkPayload;
import com.dripps.voxyserver.network.LODClearPayload;
import com.dripps.voxyserver.network.LODPreferencesPayload;
import com.dripps.voxyserver.network.LODReadyPayload;
import com.dripps.voxyserver.network.LODSectionPayload;
import com.dripps.voxyserver.network.LODServerSettingsPayload;
import com.dripps.voxyserver.network.PacketBuffer;
import com.dripps.voxyserver.network.PreSerializedLodPayload;
import com.dripps.voxyserver.util.NmsAdapter;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LodStreamingService implements ServerLodEngine.DirtySectionListener, Listener, PluginMessageListener {
    private static final long IDLE_RESCAN_INTERVAL_TICKS = 20L;
    private static final long INITIAL_LOAD_MIN_WAIT_TICKS = 10L;
    private static final int MAX_DIRTY_SECTIONS_PER_DRAIN = 512;

    private final Plugin plugin;
    private final ServerLodEngine engine;
    private final VoxyServerConfig config;
    private final ConcurrentHashMap<UUID, PlayerLodTracker> playerTrackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> pendingDirtySections = new ConcurrentHashMap<>();
    private final LongOpenHashSet queuedDirtySections = new LongOpenHashSet();
    private final ConcurrentHashMap<Long, Integer> sectionVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> initialLoadSections = new ConcurrentHashMap<>();
    private final LongOpenHashSet loadedChunks = new LongOpenHashSet();
    private final ConcurrentHashMap<String, Integer> dimensionOrdinals = new ConcurrentHashMap<>();

    private final List<PlayerSnapshot> pendingSnapshots = new ArrayList<>();
    private final AtomicBoolean streamWorkerScheduled = new AtomicBoolean();
    private final ExecutorService streamExecutor = createStreamExecutor();

    private volatile long currentTick;

    private record PlayerSnapshot(UUID uuid, String dimension, int centerSecX, int centerSecZ,
                                  int radiusSections, int minSecY, int maxSecY, int maxSections) {}

    public LodStreamingService(Plugin plugin, ServerLodEngine engine, VoxyServerConfig config) {
        this.plugin = plugin;
        this.engine = engine;
        this.config = config;
        engine.setDirtySectionListener(this);
    }

    public void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, LODSectionPayload.CHANNEL);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, LODBulkPayload.CHANNEL);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, PreSerializedLodPayload.CHANNEL);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, LODClearPayload.CHANNEL);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, LODServerSettingsPayload.CHANNEL);

        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, LODReadyPayload.CHANNEL, this);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, LODPreferencesPayload.CHANNEL, this);

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (LODReadyPayload.CHANNEL.equals(channel)) {
            onPlayerReady(player);
        } else if (LODPreferencesPayload.CHANNEL.equals(channel)) {
            PacketBuffer buf = new PacketBuffer(message);
            try {
                LODPreferencesPayload payload = LODPreferencesPayload.read(buf);
                onPlayerPreferences(player, payload);
            } finally {
                buf.release();
            }
        }
    }

    private void onPlayerReady(Player player) {
        PlayerLodTracker tracker = playerTrackers.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerLodTracker());
        tracker.setReady(true);
        tracker.updatePosition(player);

        LODServerSettingsPayload settings = new LODServerSettingsPayload(
                config.lodStreamRadius,
                config.maxSectionsPerTickPerPlayer
        );
        sendPluginMessage(player, LODServerSettingsPayload.CHANNEL, settings.encode());
    }

    private void onPlayerPreferences(Player player, LODPreferencesPayload preferences) {
        PlayerLodTracker tracker = playerTrackers.get(player.getUniqueId());
        if (tracker == null) {
            tracker = new PlayerLodTracker();
            playerTrackers.put(player.getUniqueId(), tracker);
        }
        tracker.setLodEnabled(preferences.enabled());
        tracker.setPreferredRadius(preferences.lodStreamRadius());
        tracker.setPreferredMaxSections(preferences.maxSectionsPerTick());
        tracker.reset();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerTrackers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PlayerLodTracker tracker = playerTrackers.get(player.getUniqueId());
        if (tracker != null) {
            tracker.reset();
            LODClearPayload clear = new LODClearPayload(player.getWorld().getKey().toString());
            sendPluginMessage(player, LODClearPayload.CHANNEL, clear.encode());
        }
    }

    @Override
    public void onSectionDirty(String dimension, long sectionKey) {
        int dimOrd = getDimensionOrdinal(dimension);
        long compositeKey = composeSectionKey(dimOrd, sectionKey);
        pendingDirtySections.put(compositeKey, currentTick + config.dirtyTrackingInterval);
        synchronized (queuedDirtySections) {
            queuedDirtySections.add(compositeKey);
        }
        scheduleStreamWorker();
    }

    public void markChunkPendingInitialLoad(String dimension, int chunkX, int worldSecY, int chunkZ) {
        int dimOrd = getDimensionOrdinal(dimension);
        long sectionKey = WorldEngine.getWorldSectionId(0, chunkX >> 1, worldSecY, chunkZ >> 1);
        long compositeKey = composeSectionKey(dimOrd, sectionKey);
        initialLoadSections.put(compositeKey, currentTick + INITIAL_LOAD_MIN_WAIT_TICKS);
        pendingDirtySections.put(compositeKey, currentTick + config.dirtyTrackingInterval);
        synchronized (queuedDirtySections) {
            queuedDirtySections.add(compositeKey);
        }
    }

    public void clearChunkPendingDirty(String dimension, int chunkX, int worldSecY, int chunkZ) {
        int dimOrd = getDimensionOrdinal(dimension);
        long sectionKey = WorldEngine.getWorldSectionId(0, chunkX >> 1, worldSecY, chunkZ >> 1);
        long compositeKey = composeSectionKey(dimOrd, sectionKey);
        initialLoadSections.remove(compositeKey);
        pendingDirtySections.remove(compositeKey);
    }

    public void onChunkLoadStateChanged(String dimension, int chunkX, int chunkZ, boolean loaded) {
        int dimOrd = getDimensionOrdinal(dimension);
        long chunkKey = composeChunkKey(dimOrd, chunkX, chunkZ);
        synchronized (loadedChunks) {
            if (loaded) {
                loadedChunks.add(chunkKey);
            } else {
                loadedChunks.remove(chunkKey);
            }
        }
    }

    public void tick() {
        currentTick++;
        expirePendingDirtySections();

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(plugin, task -> {
                PlayerLodTracker tracker = playerTrackers.get(player.getUniqueId());
                if (tracker == null || !tracker.isReady() || !tracker.isLodEnabled()) {
                    return;
                }

                World world = player.getWorld();
                ServerLevel level = NmsAdapter.getHandle(world);
                if (level == null) return;

                int chunkX = player.getLocation().getBlockX() >> 4;
                int chunkZ = player.getLocation().getBlockZ() >> 4;

                int radiusChunks = tracker.getEffectiveRadius(config.lodStreamRadius);
                int radiusSections = Math.max(1, radiusChunks >> 1);

                int minSecY = level.getMinSectionY() >> 1;
                int maxSecY = (level.getMaxSectionY() + 1) >> 1;

                int centerSecX = chunkX >> 1;
                int centerSecZ = chunkZ >> 1;

                int maxSections = tracker.getEffectiveMaxSections(config.maxSectionsPerTickPerPlayer);

                PlayerSnapshot snapshot = new PlayerSnapshot(
                        player.getUniqueId(),
                        world.getKey().toString(),
                        centerSecX, centerSecZ,
                        radiusSections, minSecY, maxSecY, maxSections
                );

                synchronized (pendingSnapshots) {
                    pendingSnapshots.add(snapshot);
                }
                scheduleStreamWorker();
            }, null);
        }
    }

    private void expirePendingDirtySections() {
        if (pendingDirtySections.isEmpty()) return;

        for (var entry : pendingDirtySections.entrySet()) {
            if (entry.getValue() > currentTick) continue;

            if (pendingDirtySections.remove(entry.getKey(), entry.getValue())) {
                initialLoadSections.remove(entry.getKey());
                sectionVersions.compute(entry.getKey(), (k, v) -> (v == null || v == Integer.MAX_VALUE) ? 1 : v + 1);
            }
        }
    }

    private void scheduleStreamWorker() {
        if (streamWorkerScheduled.compareAndSet(false, true)) {
            try {
                streamExecutor.execute(this::runStreamWorker);
            } catch (RejectedExecutionException e) {
                streamWorkerScheduled.set(false);
            }
        }
    }

    private void runStreamWorker() {
        try {
            while (true) {
                boolean didWork = drainQueuedDirtySections(MAX_DIRTY_SECTIONS_PER_DRAIN) > 0;

                List<PlayerSnapshot> snapshots;
                synchronized (pendingSnapshots) {
                    snapshots = new ArrayList<>(pendingSnapshots);
                    pendingSnapshots.clear();
                }

                if (!snapshots.isEmpty()) {
                    didWork = true;
                    processSnapshots(snapshots);
                }

                boolean hasMoreSnapshots;
                synchronized (pendingSnapshots) {
                    hasMoreSnapshots = !pendingSnapshots.isEmpty();
                }

                if (!didWork && queuedDirtySections.isEmpty() && !hasMoreSnapshots) {
                    return;
                }
            }
        } finally {
            streamWorkerScheduled.set(false);
            boolean hasMoreSnapshots;
            synchronized (pendingSnapshots) {
                hasMoreSnapshots = !pendingSnapshots.isEmpty();
            }
            if (!queuedDirtySections.isEmpty() || hasMoreSnapshots) {
                scheduleStreamWorker();
            }
        }
    }

    private int drainQueuedDirtySections(int maxSections) {
        int drained = 0;
        synchronized (queuedDirtySections) {
            LongIterator iter = queuedDirtySections.iterator();
            while (iter.hasNext() && drained < maxSections) {
                long compositeKey = iter.nextLong();
                iter.remove();
                processDirtySection(compositeKey);
                drained++;
            }
        }
        return drained;
    }

    private void processDirtySection(long compositeKey) {
        int dimOrd = extractSectionDimOrdinal(compositeKey);
        long sectionKey = extractSectionKey(compositeKey);
        String dimension = getDimensionFromOrdinal(dimOrd);
        if (dimension == null) return;

        World world = Bukkit.getWorld(org.bukkit.NamespacedKey.fromString(dimension));
        if (world == null) return;

        ServerLevel level = NmsAdapter.getHandle(world);
        if (level == null) return;

        WorldIdentifier worldId = ServerLodEngine.getWorldIdentifier(level);
        WorldEngine engineWorld = engine.getOrCreate(worldId, dimension);
        if (engineWorld == null) return;

        int version = getSectionVersion(dimOrd, sectionKey);

        for (Map.Entry<UUID, PlayerLodTracker> entry : playerTrackers.entrySet()) {
            PlayerLodTracker tracker = entry.getValue();
            if (!tracker.isReady() || !tracker.isLodEnabled()) continue;

            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.getWorld().getKey().toString().equals(dimension)) continue;

            if (!tracker.hasSent(sectionKey, version)) {
                tracker.invalidate(sectionKey);
            }
        }
    }

    private void processSnapshots(List<PlayerSnapshot> snapshots) {
        for (PlayerSnapshot snapshot : snapshots) {
            PlayerLodTracker tracker = playerTrackers.get(snapshot.uuid());
            if (tracker == null || !tracker.isReady() || !tracker.isLodEnabled()) continue;

            World world = Bukkit.getWorld(org.bukkit.NamespacedKey.fromString(snapshot.dimension()));
            if (world == null) continue;

            ServerLevel level = NmsAdapter.getHandle(world);
            if (level == null) continue;

            WorldIdentifier worldId = ServerLodEngine.getWorldIdentifier(level);
            WorldEngine engineWorld = engine.getOrCreate(worldId, snapshot.dimension());
            if (engineWorld == null) continue;

            int dimOrd = getDimensionOrdinal(snapshot.dimension());

            if (!tracker.prepareScan(
                    snapshot.centerSecX(), snapshot.centerSecZ(),
                    snapshot.radiusSections(), snapshot.minSecY(), snapshot.maxSecY(),
                    currentTick, IDLE_RESCAN_INTERVAL_TICKS)) {
                continue;
            }

            List<PreSerializedLodPayload> pendingPayloads = new ArrayList<>();
            int maxSections = snapshot.maxSections();

            while (pendingPayloads.size() < maxSections) {
                long sectionKey = tracker.nextSectionKeyToScan(currentTick, IDLE_RESCAN_INTERVAL_TICKS);
                if (sectionKey == PlayerLodTracker.NO_SECTION_KEY) break;

                int version = getSectionVersion(dimOrd, sectionKey);
                if (tracker.hasSent(sectionKey, version)) continue;

                if (!engine.mayHaveStoredSection(worldId, engineWorld, sectionKey)) continue;

                WorldSection section = engineWorld.acquireIfExists(sectionKey);
                if (section == null) continue;

                try {
                    LODSectionPayload sectionPayload = createSectionPayload(snapshot.dimension(), sectionKey, section);
                    if (sectionPayload != null) {
                        PreSerializedLodPayload preSerialized = new PreSerializedLodPayload(sectionPayload.encode());
                        pendingPayloads.add(preSerialized);
                        tracker.markSent(sectionKey, version);
                    }
                } finally {
                    section.release();
                }
            }

            if (!pendingPayloads.isEmpty()) {
                Player player = Bukkit.getPlayer(snapshot.uuid());
                if (player != null) {
                    LODBulkPayload bulk = new LODBulkPayload(pendingPayloads);
                    sendPluginMessage(player, LODBulkPayload.CHANNEL, bulk.encode());
                }
            }
        }
    }

    private LODSectionPayload createSectionPayload(String dimension, long sectionKey, WorldSection section) {
        long[] rawData = section.copyData();
        if (rawData == null) return null;

        Long2IntOpenHashMap lutMap = new Long2IntOpenHashMap();
        lutMap.defaultReturnValue(-1);

        short[] indices = new short[rawData.length];
        int lutSize = 0;

        for (int i = 0; i < rawData.length; i++) {
            long state = rawData[i];
            int idx = lutMap.get(state);
            if (idx == -1) {
                idx = lutSize++;
                lutMap.put(state, idx);
            }
            indices[i] = (short) idx;
        }

        int[] lutBlockStates = new int[lutSize];
        int[] lutBiomes = new int[lutSize];
        byte[] lutLight = new byte[lutSize];

        for (var entry : lutMap.long2IntEntrySet()) {
            long state = entry.getLongKey();
            int idx = entry.getIntValue();

            int blockId = (int) (state & 0xFFFFFFFFL);
            int biomeId = (int) ((state >>> 32) & 0xFFFFL);
            byte light = (byte) ((state >>> 48) & 0xFFL);

            lutBlockStates[idx] = blockId;
            lutBiomes[idx] = biomeId;
            lutLight[idx] = light;
        }

        return new LODSectionPayload(
                dimension,
                sectionKey,
                lutBlockStates,
                lutBiomes,
                lutLight,
                indices
        );
    }

    private void sendPluginMessage(Player player, String channel, byte[] data) {
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, channel, data);
            }
        }, null);
    }

    private int getSectionVersion(int dimOrd, long sectionKey) {
        return sectionVersions.getOrDefault(composeSectionKey(dimOrd, sectionKey), 0);
    }

    private int getDimensionOrdinal(String dimension) {
        return dimensionOrdinals.computeIfAbsent(dimension, d -> dimensionOrdinals.size());
    }

    private String getDimensionFromOrdinal(int dimOrd) {
        for (Map.Entry<String, Integer> entry : dimensionOrdinals.entrySet()) {
            if (entry.getValue() == dimOrd) return entry.getKey();
        }
        return null;
    }

    private static long composeSectionKey(int dimOrd, long sectionKey) {
        return (((long) dimOrd) << 56) ^ sectionKey;
    }

    private static int extractSectionDimOrdinal(long compositeKey) {
        return (int) ((compositeKey >>> 56) & 0xFFL);
    }

    private static long extractSectionKey(long compositeKey) {
        return compositeKey & 0x00FFFFFFFFFFFFFFL;
    }

    private static long composeChunkKey(int dimOrd, int chunkX, int chunkZ) {
        return (((long) dimOrd) << 56) | (((long) chunkX & 0xFFFFFFL) << 28) | ((long) chunkZ & 0xFFFFFFL);
    }

    public void clearDimensionForReadyPlayers(ServerLevel level) {
        String dimension = level.dimension().identifier().toString();
        for (Map.Entry<UUID, PlayerLodTracker> entry : playerTrackers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getWorld().getKey().toString().equals(dimension)) {
                entry.getValue().reset();
                sendPluginMessage(player, LODClearPayload.CHANNEL, new LODClearPayload(dimension).encode());
            }
        }
    }

    public void shutdown() {
        streamExecutor.shutdownNow();
        try {
            streamExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService createStreamExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VoxyServer Streaming Worker");
            t.setDaemon(true);
            return t;
        });
    }
}

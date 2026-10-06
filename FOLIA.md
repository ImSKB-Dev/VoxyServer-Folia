# Folia Architecture & Concurrency Model

`VoxyServer-Folia` is built specifically for **Folia 26.1.2** and **Java 25**. Folia replaces single-threaded server execution with multi-threaded regional tick loops.

This document details how VoxyServer-Folia maintains high performance and thread safety in a multi-region environment.

---

## 1. Scheduler Usage & Threading Boundaries

### A. Region Thread Operations (`RegionScheduler`)
- **Event Handling**: Block break, place, and explosion events (`BlockBreakEvent`, `BlockPlaceEvent`, `EntityExplodeEvent`, `BlockExplodeEvent`) are dispatched on the region thread owning the affected chunk.
- **Chunk Load/Unload Hooks**: `ChunkLoadEvent` and `ChunkUnloadEvent` run on the region thread for that chunk position.
- **Rule**: Region threads **MUST NEVER** perform heavy voxelization, disk reads/writes, or wait on locks. All chunk section data is captured or queued for async background processing.

### B. Entity Thread Operations (`EntityScheduler`)
- **Plugin Messaging**: Outgoing plugin messages (`voxyserver:lod_bulk`, `voxyserver:lod_clear`, etc.) to players are dispatched using `Player#getScheduler()`.
- **Player State Updates**: Disconnects (`PlayerQuitEvent`) and dimension switches (`PlayerChangedWorldEvent`) invalidate player streaming sessions safely.

### C. Async Workers (`AsyncScheduler` & Dedicated Worker Pools)
- **Voxelization & Ingest**: Chunk conversion and mipping (`VoxelIngestService`) run on background worker threads.
- **Spiral LOD Scanning**: `LodStreamingService` takes atomic snapshots of online players' coordinates and computes candidate LOD sections in background threads without touching world objects.
- **Presence Index Building**: `StoredSectionPresenceIndex` populates bloom filter data asynchronously.
- **World MCA Importer**: MCA region file parsing runs entirely on async threads, reporting progress back to command execution contexts.

---

## 2. Race Condition & Memory Safety Countermeasures

1. **Section Palette & Buffer Off-Heap Safety**
   - Voxy's off-heap memory allocations utilize LWJGL `MemoryUtil` and atomic reference counters (`acquire()` / `release()`).
   - Sections are safely copied or referenced with reference counting (`WorldSection#acquireIfExists(...)` / `release()`).

2. **Dirty Tracking Coalescing**
   - Rapid block changes within the same chunk section are coalesced in `ConcurrentHashMap` dirty sets.
   - Re-voxelization is debounced using configured intervals (`dirtyTrackingInterval`), preventing repetitive chunk re-processing.

3. **Player Disconnects & World Unloads**
   - When a player leaves or switches dimensions, `PlayerLodTracker#reset()` and `playerTrackers.remove(uuid)` invalidate pending streaming queues immediately.
   - If a world is unmapped, background workers release locks and skip unmapped region lookups.

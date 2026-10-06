# VoxyServer-Folia Porting Notes

This document details the architectural analysis of the original Fabric server mod [poosmacker/voxyserver](https://github.com/poosmacker/voxyserver), reference code from [illustris/voxy-server](https://github.com/illustris/voxy-server) and [MCRcortex/voxy](https://github.com/MCRcortex/voxy), and the strategy for porting it to a standalone **Folia / Paper 26.1.2** plugin running on **Java 25**.

---

## 1. Original Architecture Overview

The original VoxyServer project is a Fabric server-side mod designed to voxelize Minecraft world chunks into Voxy's Level of Detail (LOD) format, persist them per-world, and stream them progressively to client mods using custom netty network payloads.

### Core Modules & Responsibilities

1. **`Voxyserver` (Lifecycle & Hooks)**
   - Initialized via Fabric's `ModInitializer`.
   - Listens to server lifecycle events (`SERVER_STARTED`, `SERVER_STOPPING`), dimension changes (`AFTER_PLAYER_CHANGE_WORLD`), and server ticks (`END_SERVER_TICK`).

2. **`VoxyServerConfig` (Configuration)**
   - Managed JSON configuration stored in `config/voxyserver.json`.
   - Contains parameters for stream radius, sections per tick, sections per packet, tick intervals, worker thread count, and dirty tracking toggles.

3. **`VoxyServerNetworking` & Payloads**
   - Registered via Fabric API `PayloadTypeRegistry`.
   - S2C Payloads: `lod_section`, `lod_bulk`, `pre_serialized_lod`, `lod_clear`, `lod_server_settings`.
   - C2S Payloads: `lod_ready`, `lod_preferences`.

4. **`ServerLodEngine` & `StoredSectionPresenceIndex`**
   - Wraps `me.cortex.voxy.commonImpl.VoxyInstance`.
   - Manages per-world `WorldEngine` instances stored at `<world_folder>/voxyserver/`.
   - Uses a thread-safe bloom/presence index (`StoredSectionPresenceIndex`) to check if LOD sections exist prior to disk reads.

5. **`ChunkVoxelizer`**
   - Captures chunk block states, biomes, and light levels upon chunk load/change.
   - Converts chunk section data to `VoxelizedSection` using Voxy's `WorldConversionFactory`.
   - Performs off-thread mipping (`WorldVoxilizedSectionMipper`) and inserts updates into `WorldEngine`.

6. **`LodStreamingService` & `PlayerLodTracker`**
   - Tracks online players who have sent `lod_ready`.
   - Computes required LOD sections in a spiral radius outward from the player's position.
   - Rate-limits packets and pre-serializes payload bytes off-thread.

7. **`DirtyTracker` & `LevelChunkMixin`**
   - Mixin into `net.minecraft.world.level.chunk.LevelChunk` intercepts block state / light updates.
   - Batches affected section keys and queues them for re-voxelization and client update pushing.

8. **`WorldImportCoordinator` & Region Importer**
   - Reads existing `.mca` region files directly using Voxy's `WorldImporter`.
   - Voxelizes region file NBT chunks asynchronously without needing players to manually load chunks in-game.

---

## 2. Dependencies & Migration Matrix

| Component | Original Dependency | Paper / Folia Replacement Strategy |
| :--- | :--- | :--- |
| **Plugin Entry Point** | `ModInitializer` | `JavaPlugin` (`VoxyServerPlugin`) |
| **Plugin Configuration** | `config/voxyserver.json` | `plugins/VoxyServer/config.json` via Bukkit DataFolder |
| **Lifecycle Hooks** | `ServerLifecycleEvents` | `JavaPlugin#onEnable()` & `JavaPlugin#onDisable()` |
| **Tick Events** | `ServerTickEvents.END_SERVER_TICK` | `FoliaSchedulerAdapter` / `AsyncScheduler` / `RegionScheduler` |
| **Command Registration** | Fabric `CommandRegistrationCallback` | Bukkit Command API / Brigadier (`/voxyserver`) |
| **Chunk Change Detection** | `LevelChunkMixin` | Bukkit `BlockBreakEvent`, `BlockPlaceEvent`, `EntityExplodeEvent`, `BlockExplodeEvent` + Coalescing Queue |
| **Networking Registration**| Fabric `PayloadTypeRegistry` | Paper Plugin Messaging Channels (`voxyserver:lod_*`) |
| **Player Events** | Fabric `AFTER_PLAYER_CHANGE_WORLD` | Paper `PlayerJoinEvent`, `PlayerQuitEvent`, `PlayerChangedWorldEvent` |
| **World Storage** | Vanilla `LevelResource.ROOT` | Bukkit `World#getWorldFolder()` (`<world>/voxyserver/`) |
| **Voxy Core Engine** | `me.cortex.voxy:common` | Preserved via `libs/voxy.jar` + LWJGL dependencies |

---

## 3. Folia Threading & Concurrency Adaptation Strategy

Folia replaces the monolithic single-threaded tick loop with multithreaded regional tick loops. As a result, code cannot assume global single-threaded execution.

### Execution Boundaries

1. **Region Threads (`RegionScheduler`)**
   - Used for accessing Bukkit/Paper world state, block data, light data, or spawning entity events in a specific region.
   - **Constraint**: Region threads MUST NOT be blocked by heavy LOD generation, disk IO, or Voxy database access.
   - **Action**: Minimal data snapshots are captured from region threads and passed to background workers.

2. **Entity Threads (`EntityScheduler`)**
   - Used for player-specific operations (sending network packets, inspecting player location).

3. **Async Workers (`AsyncScheduler` / Controlled ThreadPool)**
   - Performs chunk voxelization, mipping, compression, disk persistence, spiral LOD candidate searching, and packet pre-serialization.
   - Results are safely queued and dispatched back to players using Folia-safe schedulers.

4. **Coalescing & Thread Safety**
   - Thread-safe concurrent maps (`ConcurrentHashMap`) and atomic primitives are used for state tracking.
   - Dirty chunk events are coalesced so multiple block edits in the same chunk only trigger a single re-voxelization job.

---

## 4. Networking Protocol Compatibility

The Voxy client expects exact byte-level network payload formats. All payload definitions (`LODSectionPayload`, `LODBulkPayload`, `PreSerializedLodPayload`, `LODClearPayload`, `LODServerSettingsPayload`, `LODReadyPayload`, `LODPreferencesPayload`) are preserved byte-for-byte. Communication uses Paper's custom plugin messaging channels under the `voxyserver` namespace.

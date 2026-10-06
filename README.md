# VoxyServer-Folia

A **standalone Folia / Paper 26.1.2 plugin** (compiled for **Java 25**) that voxelizes server world chunks into Level of Detail (LOD) format using [Voxy](https://github.com/MCRcortex/voxy) and streams them to connected players.

Players with the **Voxy** client mod installed will automatically receive distant terrain LODs from the server without needing client-side world scanning orFabric/Forge server loaders.

---

## Requirements

- **Server**: Folia 26.1.2 (or Paper 26.1.2+)
- **Java**: Java 25
- **Client**: Minecraft 26.1.2 with [Voxy](https://github.com/MCRcortex/voxy) + Sodium

> **Note**: The server does **NOT** require Fabric Loader, Fabric API, or any Fabric mods.

---

## Features

- **Progressive Spiral LOD Streaming**: Streams distant chunk sections outward from player position.
- **Folia Multithreading**: Fully safe for Folia's RegionScheduler, EntityScheduler, and AsyncScheduler.
- **Dirty Tracking**: Automatically re-voxelizes terrain when blocks break, are placed, or explode.
- **MCA Region Importer**: Backfills existing explored worlds directly from `.mca` region files via command.
- **Per-Player Rate Limiting & Preferences**: Respects client streaming preferences (`lod_preferences`).

---

## Installation

1. Download or build `VoxyServer-1.1.5-folia.jar`.
2. Drop the `.jar` into your server's `plugins/` folder.
3. Start the server using **Java 25**.

---

## Configuration

On first run, the configuration file is created at `plugins/VoxyServer/config.json`.

```json
{
  "lodStreamRadius": 256,
  "maxSectionsPerTickPerPlayer": 100,
  "sectionsPerPacket": 50,
  "generateOnChunkLoad": true,
  "tickInterval": 5,
  "workerThreads": 3,
  "dirtyTrackingEnabled": true,
  "dirtyTrackingInterval": 40,
  "debugTrackingEnabled": false,
  "debugTrackingInterval": 200
}
```

| Option | Default | Description |
| :--- | :--- | :--- |
| `lodStreamRadius` | `256` | Maximum chunk radius for streaming LODs around each player. |
| `maxSectionsPerTickPerPlayer` | `100` | Maximum LOD sections sent per player per streaming tick cycle. |
| `sectionsPerPacket` | `50` | Maximum LOD sections bundled into a single network packet. |
| `tickInterval` | `5` | Ticks between streaming background cycles. |
| `workerThreads` | `3` | Worker thread pool count for Voxy voxelization. |
| `generateOnChunkLoad` | `true` | Voxelizes chunks as they load on the server. |
| `dirtyTrackingEnabled` | `true` | Tracks block updates and re-streams updated LODs. |
| `dirtyTrackingInterval` | `40` | Ticks between dirty section flushes (40 ticks = 2s). |

---

## Commands

Requires permission `voxyserver.admin` (default: OP).

- `/voxyserver import existing all`
  Imports all loaded dimensions with a `region` folder.
- `/voxyserver import existing current`
  Imports the executing player's current dimension.
- `/voxyserver import existing dimension <dim>`
  Imports a specific dimension (e.g. `minecraft:overworld`).
- `/voxyserver import existing status`
  Shows current import progress.
- `/voxyserver import existing cancel`
  Cancels the active import job.

---

## Storage

LOD data is stored per-world at `<world_folder>/voxyserver/`. Deleting this folder resets LOD storage without affecting vanilla world terrain.

---

## License

GNU General Public License v3.0 (GPLv3)

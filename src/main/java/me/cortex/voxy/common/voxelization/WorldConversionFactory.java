package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

public class WorldConversionFactory {
    private static final boolean LITHIUM_INSTALLED;
    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    private static final VarHandle CONTAINER_DATA_HANDLE;
    private static final VarHandle DATA_PALETTE_HANDLE;
    private static final VarHandle DATA_STORAGE_HANDLE;

    static {
        boolean lithium = false;
        try {
            lithium = FabricLoader.getInstance().isModLoaded("lithium");
        } catch (Throwable ignored) {}
        LITHIUM_INSTALLED = lithium;

        VarHandle containerData = null;
        VarHandle dataPalette = null;
        VarHandle dataStorage = null;

        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup());
            Field dataField = PalettedContainer.class.getDeclaredField("data");
            dataField.setAccessible(true);
            containerData = lookup.unreflectVarHandle(dataField);

            Class<?> dataClass = dataField.getType();
            MethodHandles.Lookup dataLookup = MethodHandles.privateLookupIn(dataClass, MethodHandles.lookup());

            try {
                Field paletteField = dataClass.getDeclaredField("palette");
                paletteField.setAccessible(true);
                dataPalette = dataLookup.unreflectVarHandle(paletteField);
            } catch (Throwable ignored) {}

            try {
                Field storageField = dataClass.getDeclaredField("storage");
                storageField.setAccessible(true);
                dataStorage = dataLookup.unreflectVarHandle(storageField);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        CONTAINER_DATA_HANDLE = containerData;
        DATA_PALETTE_HANDLE = dataPalette;
        DATA_STORAGE_HANDLE = dataStorage;
    }

    private static Object getContainerData(PalettedContainer<?> container) {
        if (CONTAINER_DATA_HANDLE != null) {
            try {
                return CONTAINER_DATA_HANDLE.get(container);
            } catch (Throwable ignored) {}
        }
        try {
            Field field = PalettedContainer.class.getDeclaredField("data");
            field.setAccessible(true);
            return field.get(container);
        } catch (Throwable e) {
            throw new RuntimeException("Could not access PalettedContainer.data", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Palette<BlockState> getDataPalette(Object dataObj) {
        if (DATA_PALETTE_HANDLE != null) {
            try {
                return (Palette<BlockState>) DATA_PALETTE_HANDLE.get(dataObj);
            } catch (Throwable ignored) {}
        }
        try {
            Field field = dataObj.getClass().getDeclaredField("palette");
            field.setAccessible(true);
            return (Palette<BlockState>) field.get(dataObj);
        } catch (Throwable e) {
            try {
                Method method = dataObj.getClass().getMethod("palette");
                return (Palette<BlockState>) method.invoke(dataObj);
            } catch (Throwable ex) {
                throw new RuntimeException("Could not access PalettedContainer.Data.palette", ex);
            }
        }
    }

    private static BitStorage getDataStorage(Object dataObj) {
        if (DATA_STORAGE_HANDLE != null) {
            try {
                return (BitStorage) DATA_STORAGE_HANDLE.get(dataObj);
            } catch (Throwable ignored) {}
        }
        try {
            Field field = dataObj.getClass().getDeclaredField("storage");
            field.setAccessible(true);
            return (BitStorage) field.get(dataObj);
        } catch (Throwable e) {
            try {
                Method method = dataObj.getClass().getMethod("storage");
                return (BitStorage) method.invoke(dataObj);
            } catch (Throwable ex) {
                throw new RuntimeException("Could not access PalettedContainer.Data.storage", ex);
            }
        }
    }

    private static boolean setupLithiumLocalPallet(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        if (vp.getClass().getName().equals("net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette")) {
            for (int i = 0; i < vp.getSize(); ++i) {
                BlockState state = null;
                int blockId = -1;
                try {
                    state = vp.valueFor(i);
                } catch (Exception ignored) {}
                if (state != null && (blockId = blockCache.getOrDefault((Object) state, -1)) == -1) {
                    blockId = mapper.getIdForBlockState(state);
                    blockCache.put(state, blockId);
                }
                pc[i] = blockId;
            }
            return true;
        }
        return false;
    }

    private static int setupLocalPalette(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        int c = vp.getSize();
        if (vp instanceof LinearPalette) {
            for (int i = 0; i < vp.getSize(); ++i) {
                BlockState state = vp.valueFor(i);
                int blockId = -1;
                if (state != null && (blockId = blockCache.getOrDefault((Object) state, -1)) == -1) {
                    blockId = mapper.getIdForBlockState(state);
                    blockCache.put(state, blockId);
                }
                pc[i] = blockId;
            }
        } else if (vp instanceof HashMapPalette) {
            for (int i = 0; i < vp.getSize(); ++i) {
                BlockState state = null;
                int blockId = -1;
                try {
                    state = vp.valueFor(i);
                } catch (Exception ignored) {}
                if (state != null && (blockId = blockCache.getOrDefault((Object) state, -1)) == -1) {
                    blockId = mapper.getIdForBlockState(state);
                    blockCache.put(state, blockId);
                }
                pc[i] = blockId;
            }
        } else if (vp instanceof SingleValuePalette) {
            int blockId = -1;
            BlockState state = vp.valueFor(0);
            if (state != null && (blockId = blockCache.getOrDefault((Object) state, -1)) == -1) {
                blockId = mapper.getIdForBlockState(state);
                blockCache.put(state, blockId);
            }
            pc[0] = blockId;
        } else if (!LITHIUM_INSTALLED || !WorldConversionFactory.setupLithiumLocalPallet(vp, blockCache, mapper, pc)) {
            throw new IllegalStateException("Unknown palette type: " + vp);
        }
        return c;
    }

    public static VoxelizedSection convert(VoxelizedSection section, Mapper stateMapper, PalettedContainer<BlockState> blockContainer, PalettedContainerRO<Holder<Biome>> biomeContainer, ILightingSupplier lightSupplier) {
        return WorldConversionFactory.convert(section, stateMapper, blockContainer, biomeContainer, lightSupplier, false, 0L);
    }

    @SuppressWarnings("unchecked")
    public static VoxelizedSection convert(VoxelizedSection section, Mapper stateMapper, PalettedContainer<BlockState> blockContainer, PalettedContainerRO<Holder<Biome>> biomeContainer, ILightingSupplier lightSupplier, boolean shouldZoom, long zoomSeed) {
        Cache cache = THREAD_LOCAL.get();
        Reference2IntOpenHashMap<BlockState> blockCache = cache.getLocalMapping(stateMapper);
        int[] biomes = cache.biomeCache;
        long[] data = section.section;
        long[] zoomCells = cache.zoomCellCache;

        Object dataObj = getContainerData(blockContainer);
        Palette<BlockState> vp = getDataPalette(dataObj);
        int[] pc = cache.getPaletteCache(vp.getSize());
        GlobalPalette<BlockState> bps = null;
        int pcc = 0;
        Palette<BlockState> palette = vp;
        if (palette instanceof GlobalPalette) {
            bps = (GlobalPalette<BlockState>) palette;
            pcc = bps.getSize();
        } else {
            pcc = WorldConversionFactory.setupLocalPalette(vp, blockCache, stateMapper, pc);
            pcc = Math.max(0, pcc - 1);
        }
        int i = 0;
        int inital = -1;
        for (int y = 0; y < 4; ++y) {
            for (int z = 0; z < 4; ++z) {
                for (int x = 0; x < 4; ++x) {
                    int bid = stateMapper.getIdForBiome(biomeContainer.get(x, y, z));
                    biomes[i++] = bid;
                    if (inital == -1) {
                        inital = bid;
                    }
                    shouldZoom &= inital == bid;
                }
            }
        }
        if (shouldZoom) {
            WorldConversionFactory.computeZoomCells(biomes, zoomSeed, zoomCells);
        }
        int nonZeroCnt = 0;
        BitStorage yStorage = getDataStorage(dataObj);
        if (yStorage instanceof SimpleBitStorage bStor) {
            long[] bDat = bStor.getRaw();
            int iterPerLong = 64 / bStor.getBits() - 1;
            int MSK = (1 << bStor.getBits()) - 1;
            int eBits = bStor.getBits();
            long sample = 0L;
            int c = 0;
            int dec = 0;
            for (int i2 = 0; i2 <= 4095; ++i2) {
                if (dec-- == 0) {
                    sample = bDat[c++];
                    dec = iterPerLong;
                }
                int bId = bps == null ? pc[Math.min((int) (sample & (long) MSK), pcc)] : stateMapper.getIdForBlockState(bps.valueFor((int) (sample & (long) MSK)));
                sample >>>= eBits;
                byte light = lightSupplier.supply(i2 & 0xF, i2 >> 8 & 0xF, i2 >> 4 & 0xF);
                nonZeroCnt += bId != 0 ? 1 : 0;
                data[i2] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i2, 3276)]);
            }
        } else {
            if (!(yStorage instanceof ZeroBitStorage)) {
                throw new IllegalStateException("Unsupported BitStorage: " + yStorage.getClass().getName());
            }
            int bId = pc[0];
            if (bId == 0) {
                for (i = 0; i <= 4095; ++i) {
                    data[i] = Mapper.airWithLight(lightSupplier.supply(i & 0xF, i >> 8 & 0xF, i >> 4 & 0xF));
                }
            } else {
                nonZeroCnt = 4096;
                for (i = 0; i <= 4095; ++i) {
                    byte light = lightSupplier.supply(i & 0xF, i >> 8 & 0xF, i >> 4 & 0xF);
                    data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i, 3276)]);
                }
            }
        }
        section.lvl0NonAirCount = nonZeroCnt;
        return section;
    }

    private static void computeZoomCells(int[] biomes, long zoomSeed, long[] zoomInfo) {
        for (int cy = 0; cy < 4; ++cy) {
            for (int cz = 0; cz < 4; ++cz) {
                for (int cx = 0; cx < 4; ++cx) {
                }
            }
        }
    }

    @Deprecated(forRemoval = true)
    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        WorldVoxilizedSectionMipper.mipSection(section, mapper);
    }

    private static final class Cache {
        private final int[] biomeCache = new int[64];
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();
        private int[] paletteCache = new int[1024];
        private final long[] zoomCellCache = new long[125];

        private Cache() {
        }

        private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, a_ -> new Reference2IntOpenHashMap<>());
        }

        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }
}

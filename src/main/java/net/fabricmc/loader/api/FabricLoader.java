package net.fabricmc.loader.api;

import net.fabricmc.api.EnvType;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public interface FabricLoader {
    static FabricLoader getInstance() {
        return Impl.INSTANCE;
    }

    boolean isModLoaded(String id);
    Optional<ModContainer> getModContainer(String id);
    EnvType getEnvironmentType();
    Path getConfigDir();
    Path getGameDir();

    class Impl implements FabricLoader {
        private static final FabricLoader INSTANCE = new Impl();
        private static final ModContainer DUMMY_CONTAINER = new ModContainer() {};

        @Override
        public boolean isModLoaded(String id) {
            return false;
        }

        @Override
        public Optional<ModContainer> getModContainer(String id) {
            if ("voxy".equalsIgnoreCase(id) || "voxyserver".equalsIgnoreCase(id)) {
                return Optional.of(DUMMY_CONTAINER);
            }
            return Optional.empty();
        }

        @Override
        public EnvType getEnvironmentType() {
            return EnvType.SERVER;
        }

        @Override
        public Path getConfigDir() {
            return Paths.get("plugins", "VoxyServer");
        }

        @Override
        public Path getGameDir() {
            return Paths.get(".");
        }
    }
}

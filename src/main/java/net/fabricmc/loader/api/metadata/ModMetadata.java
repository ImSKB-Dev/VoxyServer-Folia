package net.fabricmc.loader.api.metadata;

import java.util.Optional;

public interface ModMetadata {
    default Optional<ModVersion> getVersion() {
        return Optional.of(() -> "1.1.5");
    }

    default CustomValue getCustomValue(String key) {
        return () -> "1.1.5-folia";
    }

    interface ModVersion {
        String getFriendlyString();
    }

    interface CustomValue {
        String getAsString();
    }
}

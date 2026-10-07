package net.fabricmc.loader.api.metadata;

import net.fabricmc.loader.api.Version;

public interface ModMetadata {
    default Version getVersion() {
        return () -> "1.1.5";
    }

    default CustomValue getCustomValue(String key) {
        return () -> "1.1.5-folia";
    }

    interface CustomValue {
        String getAsString();
    }
}

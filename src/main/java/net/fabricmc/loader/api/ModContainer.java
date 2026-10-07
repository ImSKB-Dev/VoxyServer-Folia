package net.fabricmc.loader.api;

import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public interface ModContainer {
    default ModMetadata getMetadata() {
        return new ModMetadata() {};
    }

    default List<Path> getRootPaths() {
        return List.of(Paths.get("."));
    }
}

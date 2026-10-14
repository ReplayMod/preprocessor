package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingVisitor
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.extension

internal fun readMappings(path: Path, visitor: MappingVisitor) {
    if (path.extension == "jar") {
        FileSystems.newFileSystem(path).use { fileSystem ->
            fileSystem.getPath("mappings", "mappings.tiny").bufferedReader().use { reader ->
                return MappingReader.read(reader, visitor)
            }
        }
    } else {
        return MappingReader.read(path, visitor)
    }
}

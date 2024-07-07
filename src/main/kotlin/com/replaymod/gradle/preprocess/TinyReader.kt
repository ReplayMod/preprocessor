package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.tree.MappingTree
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingsReader
import java.io.IOException

internal class TinyReader(private val m: MappingTree, from: String, to: String) : MappingsReader() {
    private val fromId = m.getNamespaceId(from)
    private val toId = m.getNamespaceId(to)

    override fun read(mappings: MappingSet): MappingSet {
        for (cls in m.classes) {
            val clsMapping = mappings.getOrCreateClassMapping(cls.getName(fromId))
            clsMapping.deobfuscatedName = cls.getName(toId)

            for (field in cls.fields) {
                clsMapping.getOrCreateFieldMapping(field.getName(fromId), field.getDesc(fromId)).deobfuscatedName = field.getName(toId)
            }

            for (method in cls.methods) {
                clsMapping.getOrCreateMethodMapping(method.getName(fromId), method.getDesc(fromId)).deobfuscatedName = method.getName(toId)
            }
        }
        return mappings
    }

    @Throws(IOException::class)
    override fun close() {

    }
}

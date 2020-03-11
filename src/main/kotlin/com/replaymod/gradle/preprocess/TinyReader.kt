package com.replaymod.gradle.preprocess

import net.fabricmc.mapping.tree.TinyTree
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingsReader
import java.io.IOException

class TinyReader(private val m: TinyTree, private val from: String, private val to: String) : MappingsReader() {
    override fun read(mappings: MappingSet): MappingSet {
        for (cls in m.classes) {
            val clsMapping = mappings.getOrCreateClassMapping(cls.getName(from))
            clsMapping.deobfuscatedName = cls.getName(to)

            for (field in cls.fields) {
                clsMapping.getOrCreateFieldMapping(field.getName(from), field.getDescriptor(from)).deobfuscatedName = field.getName(to)
            }

            for (method in cls.methods) {
                clsMapping.getOrCreateMethodMapping(method.getName(from), method.getDescriptor(from)).deobfuscatedName = method.getName(to)
            }
        }
        return mappings
    }

    @Throws(IOException::class)
    override fun close() {

    }
}

package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingDstNsReorder
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Turns two files containing at least `official` and `named` namespaces, into a single mappings file which contains
 * `official`, `named`, and `mojang` namespaces.
 * The `official` names should be the same across all files.
 * The output `named` names are the `named` names from [namedMappings].
 * The output `mojang` names are the `named` names from [mojangMappings].
 */
internal abstract class MergeNamedAndMojangMappingsTask : DefaultTask() {
    @get:InputFile
    abstract val namedMappings: RegularFileProperty

    @get:InputFile
    abstract val mojangMappings: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun merge() {
        val mappingTree = MemoryMappingTree()
        mappingTree.visitNamespaces("official", listOf("named", "mojang"))
        mappingTree.visitEnd()

        readMappings(namedMappings.get().asFile.toPath(),
            mappingTree.withDstNs("named").withSrcNs("official"))
        readMappings(mojangMappings.get().asFile.toPath(),
            mappingTree.withNsRename("named" to "mojang").withDstNs("named").withSrcNs("official"))

        output.get().asFile.bufferedWriter().use { writer ->
            mappingTree.accept(MappingNsCompleter(Tiny2FileWriter(writer, false), null))
        }
    }

    private fun MappingVisitor.withSrcNs(srcNs: String) = MappingSourceNsSwitch(this, srcNs)
    private fun MappingVisitor.withDstNs(vararg newDstNs: String) = MappingDstNsReorder(this, newDstNs.asList())
    private fun MappingVisitor.withNsRename(vararg mapping: Pair<String, String>) = MappingNsRenamer(this, mapOf(*mapping))
}

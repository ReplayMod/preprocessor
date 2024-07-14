package com.replaymod.gradle.preprocess

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import java.io.File

abstract class CleanupUnnecessaryMappingsTask : DefaultTask() {
    @get:Input
    val task: Property<PreprocessTask> = project.objects.property()

    @InputFile
    val mappingFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        val task = task.get()
        val mappingFile = mappingFile.get().asFile
        val mappings = ExtraMapping.read(mappingFile.toPath())
        // Class entries can only be removed after all their method/field entries have been removed, so sort them to
        // be processed last.
        val mappingEntries = mappings.entries.sortedBy { it is ExtraMapping.ClassEntry }

        val removedLines = mutableSetOf<Int>()
        for (mappingEntry in mappingEntries) {
            val tmpMappingsFile = temporaryDir.resolve("reduced-mappings.txt")
            writeReducedMappings(mappingFile, tmpMappingsFile, removedLines + mappingEntry.line)

            val tmpInOuts = task.entries.mapIndexed { i, inOut ->
                inOut.copy(generated = temporaryDir.resolve("output-$i"))
            }
            tmpInOuts.forEach { it.generated.deleteRecursively() }

            task.preprocess(tmpMappingsFile, tmpInOuts)

            var changed = false
            for ((i, inOut) in task.entries.withIndex()) {
                val orgOutRoot = inOut.generated
                val newOutRoot = tmpInOuts[i].generated
                for (orgFile in orgOutRoot.walk()) {
                    if (orgFile.isDirectory) continue
                    val newFile = newOutRoot.resolve(orgFile.relativeTo(orgOutRoot))
                    if (orgFile.readText() != newFile.readText()) {
                        println("Removing $mappingEntry results in changes to at least: $orgFile")
                        changed = true
                        break
                    }
                }
                if (changed) break
            }
            if (!changed) {
                println("Removing $mappingEntry does not appear to change the output.")
                removedLines.add(mappingEntry.line)
            }
        }

        if (removedLines.isNotEmpty()) {
            writeReducedMappings(mappingFile, mappingFile, removedLines)
        }
    }

    private fun writeReducedMappings(originalFile: File, reducedFile: File, removedLines: Set<Int>) {
        val lines = originalFile.readLines()
            .filterIndexed { index, _ -> (index + 1) !in removedLines }
        reducedFile.writeText(lines.joinToString("") { "$it\n" })
    }
}

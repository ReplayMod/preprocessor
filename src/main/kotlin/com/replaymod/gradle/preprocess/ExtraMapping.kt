package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.logging.Logger
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.bufferedReader

data class ExtraMapping(val entries: List<Entry>) {
    sealed interface Entry {
        val line: Int
    }

    data class ClassEntry(
        override val line: Int,
        val srcName: String,
        val dstName: String,
    ) : Entry

    data class FieldEntry(
        override val line: Int,
        val srcClsName: String,
        val dstClsName: String?,
        val srcName: String,
        val dstName: String,
    ) : Entry

    data class MethodEntry(
        override val line: Int,
        val srcClsName: String,
        val dstClsName: String?,
        val srcName: String,
        val dstName: String,
        val srcDesc: String?,
        val dstDesc: String?,
    ) : Entry


    fun resolve(
        logger: Logger,
        srcTree: MappingTree,
        dstTree: MappingTree,
        namedNamespace: String,
        sharedNamespace: String,
    ): Pair<MemoryMappingTree, MemoryMappingTree> {
        val srcNamedNsId = srcTree.getNamespaceId(namedNamespace)
        val srcSharedNsId = srcTree.getNamespaceId(sharedNamespace)
        val dstSharedNsId = srcTree.getNamespaceId(sharedNamespace)
        val dstNamedNsId = srcTree.getNamespaceId(namedNamespace)

        val internalEntries = entries.map { entry ->
            /** Turns `a.b.C.D` into `a/b/C$D`. */
            fun String.toInternal(): String {
                val parts = split('.')
                return buildString {
                    var state = 0 // 0: First, 1: Top level class, 2: Inner classes
                    for (part in parts) {
                        when (state) {
                            0 -> state = 1
                            1 -> {
                                append('/')
                                if (part[0].isUpperCase() || part.startsWith("class_")) {
                                    state = 2
                                }
                            }
                            2 -> append('$')
                        }
                        append(part)
                    }
                }
            }
            when (entry) {
                is ClassEntry -> entry.copy(srcName = entry.srcName.toInternal(), dstName = entry.dstName.toInternal())
                is FieldEntry -> entry.copy(srcClsName = entry.srcClsName.toInternal(), dstClsName = entry.dstClsName?.toInternal())
                is MethodEntry -> entry.copy(srcClsName = entry.srcClsName.toInternal(), dstClsName = entry.dstClsName?.toInternal())
            }
        }

        val extraClsMappings = internalEntries.filterIsInstance<ClassEntry>().associate { it.srcName to it.dstName }

        val clsTree = MemoryMappingTree(true).apply { visitNamespaces("source", listOf("destination")) }
        val clsSrcNsId = clsTree.getNamespaceId("source")
        val clsDstNsId = clsTree.getNamespaceId("destination")
        for (srcCls in srcTree.classes) {
            val srcName = srcCls.getName(srcNamedNsId)
            val dstName = dstTree.getClass(srcCls.getName(srcSharedNsId), dstSharedNsId)?.getName(dstNamedNsId) ?: continue
            clsTree.visitClass(srcName)
            clsTree.visitDstName(MappedElementKind.CLASS, clsDstNsId, dstName)
        }
        for ((srcName, dstName) in extraClsMappings) {
            clsTree.visitClass(srcName)
            clsTree.visitDstName(MappedElementKind.CLASS, clsDstNsId, dstName)
        }

        val completedEntries = internalEntries.flatMap { entry ->
            fun findDstCls(entry: Entry, srcName: String): String? {
                val extraClsMapping = extraClsMappings[srcName]
                if (extraClsMapping != null) {
                    return extraClsMapping
                }
                val srcCls = srcTree.getClass(srcName, srcNamedNsId)
                if (srcCls == null) {
                    logger.error("Failed to find mapping for source class `$srcName` of $entry. Make sure you did " +
                        "not typo, and if the class really just isn't mapped, then provide a manual mapping for it.")
                    return null
                }
                val sharedName = srcCls.getName(srcSharedNsId)
                val dstCls = dstTree.getClass(sharedName, dstSharedNsId)
                if (dstCls == null) {
                    logger.error("Failed to find mapping for destination class `$sharedName` of $entry. Make sure you did " +
                        "not typo, and if the class really just isn't mapped, then provide a manual mapping for it.")
                    return null
                }
                return dstCls.getName(dstNamedNsId)!!
            }
            when (entry) {
                is ClassEntry -> listOf(entry)
                is FieldEntry -> {
                    val dstClsName = entry.dstClsName ?: findDstCls(entry, entry.srcClsName) ?: return@flatMap emptyList()
                    listOf(entry.copy(dstClsName = dstClsName))
                }
                is MethodEntry -> {
                    val srcClsName = entry.srcClsName
                    val dstClsName = entry.dstClsName ?: findDstCls(entry, srcClsName) ?: return@flatMap emptyList()

                    val srcCls = srcTree.getClass(srcClsName, srcNamedNsId)
                    val dstCls = dstTree.getClass(dstClsName, dstNamedNsId)

                    val srcMappedDesc = entry.dstDesc?.let { clsTree.mapDesc(it, clsDstNsId, clsSrcNsId) }
                    val dstMappedDesc = entry.srcDesc?.let { clsTree.mapDesc(it, clsSrcNsId, clsDstNsId) }

                    if (entry.srcDesc != null || entry.dstDesc != null) {
                        return@flatMap listOf(entry.copy(
                            dstClsName = dstClsName,
                            srcDesc = entry.srcDesc ?: srcMappedDesc!!,
                            dstDesc = entry.dstDesc ?: dstMappedDesc!!,
                        ))
                    }

                    if (srcCls == null && dstCls == null) {
                        logger.error("Neither source nor target class of $entry appear to be mapped (or did you typo?). " +
                            "As such, you must explicitly specify the method signature of at least one of them.")
                        return@flatMap emptyList()
                    }

                    val srcMethods = srcCls?.methods.orEmpty().filter { it.getName(srcNamedNsId) == entry.srcName }.toMutableList()
                    val dstMethods = dstCls?.methods.orEmpty().filter { it.getName(dstNamedNsId) == entry.dstName }.toMutableList()

                    if (srcMethods.isEmpty() && dstMethods.isEmpty()) {
                        logger.error("Neither source nor target method of $entry appear to be mapped (or did you typo?). " +
                            "As such, you must explicitly specify the method signature of at least one of them.")
                        return@flatMap emptyList()
                    }

                    if (srcMethods.size == dstMethods.size) {
                        val result = mutableListOf<Entry>()

                        // First pair up exact matches
                        srcMethods.removeIf { srcMethod ->
                            val srcDesc = srcMethod.getDesc(srcNamedNsId)!!
                            val dstDesc = clsTree.mapDesc(srcDesc, clsSrcNsId, clsDstNsId)
                            val dstMethod = dstMethods.find { it.getDesc(dstNamedNsId) == dstDesc }
                            if (dstMethod != null) {
                                dstMethods.remove(dstMethod)
                                result.add(entry.copy(dstClsName = dstClsName, srcDesc = srcDesc, dstDesc = dstDesc))
                                true
                            } else {
                                false
                            }
                        }

                        // then all remaining ones
                        result.addAll(srcMethods.zip(dstMethods) { srcMethod, dstMethod ->
                            entry.copy(
                                dstClsName = dstClsName,
                                srcDesc = srcMethod.getDesc(srcNamedNsId),
                                dstDesc = dstMethod.getDesc(dstNamedNsId),
                            )
                        })

                        return@flatMap result
                    }

                    val allSrcDescs = mutableSetOf<String>()
                    srcMethods.forEach { allSrcDescs.add(it.getDesc(srcNamedNsId)!!) }
                    dstMethods.forEach { allSrcDescs.add(clsTree.mapDesc(it.getDesc(dstNamedNsId)!!, clsDstNsId, clsSrcNsId)) }

                    allSrcDescs.map { desc ->
                        entry.copy(
                            dstClsName = dstClsName,
                            srcDesc = desc,
                            dstDesc = clsTree.mapDesc(desc, clsSrcNsId, clsDstNsId),
                        )
                    }
                }
            }
        }

        val forwards = entriesToMappingTree(srcTree, namedNamespace, completedEntries)
        val backwards = entriesToMappingTree(dstTree, namedNamespace, completedEntries.map { entry ->
            when (entry) {
                is ClassEntry -> entry.copy(srcName = entry.dstName, dstName = entry.srcName)
                is FieldEntry -> entry.copy(
                    srcClsName = entry.dstClsName!!,
                    dstClsName = entry.srcClsName,
                    srcName = entry.dstName,
                    dstName = entry.srcName,
                )
                is MethodEntry -> entry.copy(
                    srcClsName = entry.dstClsName!!,
                    dstClsName = entry.srcClsName,
                    srcName = entry.dstName,
                    dstName = entry.srcName,
                    srcDesc = entry.dstDesc,
                    dstDesc = entry.srcDesc,
                )
            }
        })
        return Pair(forwards, backwards)
    }

    private fun entriesToMappingTree(
        srcTree: MappingTree,
        namedNamespace: String,
        completedEntries: List<Entry>,
    ): MemoryMappingTree {
        val srcNamedNsId = srcTree.getNamespaceId(namedNamespace)

        val entriesBySrcClass = completedEntries.groupBy { entry ->
            when (entry) {
                is ClassEntry -> entry.srcName
                is FieldEntry -> entry.srcClsName
                is MethodEntry -> entry.srcClsName
            }
        }

        val result = MemoryMappingTree().apply { visitNamespaces("source", listOf("destination")) }

        for ((srcClsName, entries) in entriesBySrcClass) {
            result.visitClass(srcClsName)
            val dstClsName = entries.firstNotNullOfOrNull { (it as? ClassEntry)?.dstName } ?: srcClsName
            result.visitDstName(MappedElementKind.CLASS, 0, dstClsName)

            val srcCls = srcTree.getClass(srcClsName, srcNamedNsId)

            for (entry in entries) {
                when (entry) {
                    is ClassEntry -> {}
                    is FieldEntry -> {
                        val srcField = srcCls?.getField(entry.srcName, null, srcNamedNsId)
                        result.visitField(entry.srcName, srcField?.getDesc(srcNamedNsId))
                        result.visitDstName(MappedElementKind.FIELD, 0, entry.dstName)
                    }
                    is MethodEntry -> {
                        result.visitMethod(entry.srcName, entry.srcDesc)
                        result.visitDstName(MappedElementKind.METHOD, 0, entry.dstName)
                    }
                }
            }
        }

        return result
    }

    companion object {
        @Throws(IOException::class)
        fun read(path: Path): ExtraMapping = path.bufferedReader().use { read(it) }

        @Throws(IOException::class)
        fun read(reader: BufferedReader): ExtraMapping {
            val entries = mutableListOf<Entry>()
            var lineNumber = 0
            for (line in reader.lineSequence()) {
                lineNumber++

                val trimmedLine = line.trim()
                if (trimmedLine.startsWith("#") || trimmedLine.isEmpty()) {
                    continue
                }

                val parts = trimmedLine.split("\\s+".toRegex())
                val srcClsName = parts[0]
                if (parts.size == 2) {
                    val dstClsName = parts[1]
                    // Common mistake to make when copying fully qualified class names from imports
                    if (dstClsName.endsWith(";")) {
                        throw IllegalArgumentException("Line $lineNumber: Unexpected semi-colon")
                    }
                    entries.add(ClassEntry(lineNumber, srcClsName, dstClsName))
                } else if (parts.size == 3 || parts.size == 4) {
                    var srcName = parts[1]
                    var dstClsName: String?
                    var dstName: String
                    if (parts.size == 4) {
                        dstClsName = parts[2]
                        dstName = parts[3]
                    } else {
                        dstClsName = null
                        dstName = parts[2]
                    }
                    if ("(" in srcName) {
                        val srcDesc = ("(" + srcName.substringAfter("("))
                            .takeUnless { it == "()" }
                        val dstDesc = ("(" + dstName.substringAfter("("))
                            .takeUnless { it == "()" }
                        srcName = srcName.substringBefore("(")
                        dstName = dstName.substringBefore("(")
                        entries.add(MethodEntry(lineNumber, srcClsName, dstClsName, srcName, dstName, srcDesc, dstDesc))
                    } else {
                        entries.add(FieldEntry(lineNumber, srcClsName, dstClsName, srcName, dstName))
                    }
                } else {
                    throw IllegalArgumentException("Line $lineNumber: Expected 2-4 whitespace-separated identifiers, got ${parts.size}")
                }
            }
            return ExtraMapping(entries)
        }
    }
}

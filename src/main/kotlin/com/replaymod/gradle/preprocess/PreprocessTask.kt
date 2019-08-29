package com.replaymod.gradle.preprocess

import com.replaymod.gradle.remap.Transformer
import com.replaymod.gradle.remap.legacy.LegacyMapping
import com.replaymod.gradle.remap.legacy.LegacyMappingSetModelFactory
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.InnerClassMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import java.io.File
import java.io.Serializable
import java.util.regex.Pattern

data class Keywords(
        val `if`: String,
        val ifdef: String,
        val `else`: String,
        val endif: String,
        val eval: String
) : Serializable

open class PreprocessTask : DefaultTask() {
    companion object {
        @JvmStatic
        val DEFAULT_KEYWORDS = Keywords(
                `if` = "//#if ",
                ifdef = "//#ifdef ",
                `else` = "//#else",
                endif = "//#endif",
                eval = "//$$"
        )
        @JvmStatic
        val CFG_KEYWORDS = Keywords(
                `if` = "##if ",
                ifdef = "##ifdef ",
                `else` = "##else",
                endif = "##endif",
                eval = "#$$"
        )
    }

    @OutputDirectory
    var generated: File? = null

    @InputDirectory
    @SkipWhenEmpty
    var source: File? = null

    @InputFile
    @Optional
    var sourceMappings: File? = null

    @InputFile
    @Optional
    var destinationMappings: File? = null

    @InputFile
    @Optional
    var mapping: File? = null

    @Input
    var reverseMapping: Boolean = false

    @InputFiles
    @Optional
    var classpath: FileCollection? = null

    @Input
    var vars: MutableMap<String, Int> = mutableMapOf()

    @Input
    var keywords: MutableMap<String, Keywords> = mutableMapOf(
            ".java" to DEFAULT_KEYWORDS,
            ".gradle" to DEFAULT_KEYWORDS,
            ".json" to DEFAULT_KEYWORDS,
            ".mcmeta" to DEFAULT_KEYWORDS,
            ".cfg" to CFG_KEYWORDS
    )

    fun source(file: Any) {
        source = project.file(file)
    }

    fun generated(file: Any) {
        generated = project.file(file)
    }

    fun inplace(file: Any) {
        source(file)
        generated(file)
    }

    fun compileTask(task: AbstractCompile) {
        dependsOn(task)
        classpath = task.classpath + project.files(task.destinationDir)
    }

    fun `var`(name: String, value: Int) {
        vars[name] = value
    }

    fun `var`(map: Map<String, Int>) {
        vars.putAll(map)
    }

    fun keywords(extension: String, map: Keywords) {
        keywords[extension] = map
    }

    private fun File.readMappings(): MappingSet {
        val ext = name.substring(name.lastIndexOf(".") + 1)
        val format = MappingFormats.REGISTRY.values().find { it.standardFileExtension.orElse(null) == ext }
                ?: throw UnsupportedOperationException("Cannot find mapping format for $this")
        return format.read(toPath())
    }

    // SRG doesn't track class names, so we need to inject our manual mappings which do contain them
    // However, our manual mappings also contain certain manual mappings in MCP names which need to be
    // applied before transitioning to SRG names (i.e. not where class mappings need to be applied).
    // As such, we need to split our class mappings off from the rest of the manual mappings.
    private fun MappingSet.splitOffClassMappings(): MappingSet {
        val clsMap = MappingSet.create()
        for (cls in topLevelClassMappings) {
            val clsOnly = clsMap.getOrCreateTopLevelClassMapping(cls.obfuscatedName)
            clsOnly.deobfuscatedName = cls.deobfuscatedName
            cls.deobfuscatedName = cls.obfuscatedName
            for (inner in cls.innerClassMappings) {
                inner.splitOffInnerClassMappings(clsOnly.getOrCreateInnerClassMapping(inner.obfuscatedName))
            }
        }
        return clsMap
    }

    private fun InnerClassMapping.splitOffInnerClassMappings(to: InnerClassMapping) {
        to.deobfuscatedName = deobfuscatedName
        deobfuscatedName = obfuscatedName
        for (inner in innerClassMappings) {
            inner.splitOffInnerClassMappings(to.getOrCreateInnerClassMapping(inner.obfuscatedName))
        }
    }

    // Like a.merge(b) except that mappings in b which do not exist in a at all will nevertheless be preserved.
    private fun MappingSet.mergeBoth(b: MappingSet, into: MappingSet = MappingSet.create()): MappingSet {
        topLevelClassMappings.forEach { aClass ->
            val bClass = b.getTopLevelClassMapping(aClass.deobfuscatedName).orElse(null)
            if (bClass != null) {
                val merged = into.getOrCreateTopLevelClassMapping(aClass.obfuscatedName)
                merged.deobfuscatedName = bClass.deobfuscatedName
                aClass.mergeBoth(bClass, merged)
            } else {
                aClass.copy(into)
            }
        }
        b.topLevelClassMappings.forEach {
            if (!topLevelClassMappings.any { c -> c.deobfuscatedName == it.obfuscatedName }) {
                it.copy(into)
            }
        }
        return into
    }

    private fun <T : ClassMapping<T, *>> ClassMapping<T, *>.mergeBoth(b: ClassMapping<T, *>, merged: ClassMapping<T, *>) {
        fieldMappings.forEach {
            val bField = b.getFieldMapping(it.deobfuscatedName).orElse(null)
            if (bField != null) {
                it.merge(bField, merged)
            } else {
                it.copy(merged)
            }
        }
        b.fieldMappings.forEach {
            if (!fieldMappings.any { c -> c.deobfuscatedSignature == it.signature }) {
                it.copy(merged)
            }
        }
        methodMappings.forEach {
            val bMethod = b.getMethodMapping(it.deobfuscatedSignature).orElse(null)
            if (bMethod != null) {
                it.merge(bMethod, merged)
            } else {
                it.copy(merged)
            }
        }
        b.methodMappings.forEach {
            if (!methodMappings.any { c -> c.deobfuscatedSignature == it.signature }) {
                it.copy(merged)
            }
        }
        innerClassMappings.forEach { aClass ->
            val bClass = b.getInnerClassMapping(aClass.deobfuscatedName).orElse(null)
            if (bClass != null) {
                val mergedInner = merged.getOrCreateInnerClassMapping(obfuscatedName)
                mergedInner.deobfuscatedName = bClass.deobfuscatedName
                aClass.merge(bClass, mergedInner)
            } else {
                aClass.copy(merged)
            }
        }
        b.innerClassMappings.forEach {
            if (!innerClassMappings.any { c -> c.deobfuscatedName == it.obfuscatedName }) {
                it.copy(merged)
            }
        }
    }

    // Like a.merge(b) except that mappings not in b will not be in the result (even if they're in a)
    private fun MappingSet.join(b: MappingSet, into: MappingSet = MappingSet.create()): MappingSet {
        topLevelClassMappings.forEach { classA ->
            b.getTopLevelClassMapping(classA.deobfuscatedName).ifPresent { classB ->
                classA.join(classB, into)
            }
        }
        return into
    }

    private fun TopLevelClassMapping.join(b: TopLevelClassMapping, into: MappingSet) {
        val merged = into.getOrCreateTopLevelClassMapping(obfuscatedName)
        merged.deobfuscatedName = b.deobfuscatedName
        fieldMappings.forEach { fieldA ->
            b.getFieldMapping(fieldA.deobfuscatedSignature).ifPresent { fieldB ->
                fieldA.merge(fieldB, merged)
            }
        }
        methodMappings.forEach { methodA ->
            b.getMethodMapping(methodA.deobfuscatedSignature).ifPresent { methodB ->
                methodA.merge(methodB, merged)
            }
        }
        innerClassMappings.forEach { classA ->
            b.getInnerClassMapping(classA.deobfuscatedName).ifPresent { classB ->
                classA.join(classB, merged)
            }
        }
    }

    private fun InnerClassMapping.join(b: InnerClassMapping, into: ClassMapping<*, *>) {
        val merged = into.getOrCreateInnerClassMapping(obfuscatedName)
        merged.deobfuscatedName = b.deobfuscatedName
        fieldMappings.forEach { fieldA ->
            b.getFieldMapping(fieldA.deobfuscatedSignature).ifPresent { fieldB ->
                fieldA.merge(fieldB, merged)
            }
        }
        methodMappings.forEach { methodA ->
            b.getMethodMapping(methodA.deobfuscatedSignature).ifPresent { methodB ->
                methodA.merge(methodB, merged)
            }
        }
        innerClassMappings.forEach { classA ->
            b.getInnerClassMapping(classA.deobfuscatedName).ifPresent { classB ->
                classA.join(classB, merged)
            }
        }
    }

    @TaskAction
    fun preprocess() {
        val source = source!!
        val inPath = source.toPath()
        val outPath = generated!!.toPath()
        val inPlace = inPath.toAbsolutePath() == outPath.toAbsolutePath()
        var mappedSources: Map<String, String>? = null

        val mapping = mapping
        val classpath = classpath
        if (mapping != null && classpath != null) {
            val mappings = if (sourceMappings?.exists() == true && destinationMappings?.exists() == true) {
                val legacyMap = LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
                val clsMap = legacyMap.splitOffClassMappings()
                val srcMap = sourceMappings!!.readMappings()
                val dstMap = destinationMappings!!.readMappings()
                legacyMap.mergeBoth(
                        // The inner clsMap is to make the join work, the outer one for custom classes (which are not part of
                        // dstMap and would otherwise be filtered by the join)
                        srcMap.mergeBoth(clsMap).join(dstMap.reverse()).mergeBoth(clsMap),
                        MappingSet.create(LegacyMappingSetModelFactory()))
            } else {
                LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
            }
            val javaTransformer = Transformer(mappings)
            javaTransformer.classpath = classpath.files.filter { it.exists() }.map { it.absolutePath }.toTypedArray()
            val sources = mutableMapOf<String, String>()
            project.fileTree(source).forEach { file ->
                if (file.name.endsWith(".java")) {
                    val relPath = inPath.relativize(file.toPath())
                    sources[relPath.toString()] = file.readText()
                }
            }
            mappedSources = javaTransformer.remap(sources)
        }

        val commentPreprocessor = CommentPreprocessor(vars)
        project.fileTree(source).forEach { file ->
            val relPath = inPath.relativize(file.toPath())
            val outFile = outPath.resolve(relPath).toFile()
            val kws = keywords.entries.find { (ext, _) -> file.name.endsWith(ext) }
            if (kws != null) {
                val javaTransform = { lines: List<String> ->
                    mappedSources?.get(relPath.toString())?.lines() ?: lines
                }
                commentPreprocessor.convertFile(kws.value, file, outFile, javaTransform)
            } else if (!inPlace) {
                project.copy {
                    from(file)
                    into(outFile.parentFile)
                }
            }
        }
    }
}

class CommentPreprocessor(private val vars: Map<String, Int>) {
    companion object {
        private val EXPR_PATTERN = Pattern.compile("(.+)(<=|>=|<|>)(.+)")
        private val OR_PATTERN = Pattern.quote("||").toPattern()
        private val AND_PATTERN = Pattern.quote("&&").toPattern()
    }

    private fun String.evalVar() = toIntOrNull() ?: vars[this] ?: throw NoSuchElementException(this)

    private fun String.evalExpr(): Boolean {
        split(OR_PATTERN).let { parts ->
            if (parts.size > 1) {
                return parts.any { it.trim().evalExpr() }
            }
        }
        split(AND_PATTERN).let { parts ->
            if (parts.size > 1) {
                return parts.all { it.trim().evalExpr() }
            }
        }

        val matcher = EXPR_PATTERN.matcher(this)
        if (matcher.matches()) {
            val lhs = matcher.group(1).trim().evalVar()
            val rhs = matcher.group(3).trim().evalVar()
            return when (matcher.group(2)) {
                ">=" -> lhs >= rhs
                "<=" -> lhs <= rhs
                ">" -> lhs > rhs
                "<" -> lhs < rhs
                else -> throw IllegalArgumentException("Invalid expression: $this")
            }
        } else {
            throw IllegalArgumentException("Invalid expression: $this")
        }
    }

    private val String.indentation: Int
        get() = takeWhile { it == ' ' }.length

    private fun convertSource(kws: Keywords, lines: List<String>, remapped: List<String>, fileName: String): List<String> {
        val ifStack = mutableListOf<Boolean>()
        val indentStack = mutableListOf<Int>()
        var active = true
        var n = 0
        return lines.zip(remapped).map { (originalLine, line) ->
            n++
            val trimmed = line.trim()
            if (trimmed.startsWith(kws.`if`)) {
                val result = trimmed.substring(kws.`if`.length).trim().evalExpr()
                ifStack.push(result)
                indentStack.push(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.`else`)) {
                if (ifStack.isEmpty()) {
                    throw ParserException("Unexpected else in line $n of $fileName")
                }
                ifStack.push(!ifStack.pop())
                indentStack.pop()
                indentStack.push(line.indentation)
                active = ifStack.all { it }
                line
            } else if (trimmed.startsWith(kws.ifdef)) {
                val result = vars.containsKey(trimmed.substring(kws.ifdef.length))
                ifStack.push(result)
                indentStack.push(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.endif)) {
                if (ifStack.isEmpty()) {
                    throw ParserException("Unexpected endif in line $n of $fileName")
                }
                ifStack.pop()
                indentStack.pop()
                active = ifStack.all { it }
                line
            } else {
                if (active) {
                    if (trimmed.startsWith(kws.eval)) {
                        line.replaceFirst((Pattern.quote(kws.eval) + " ?").toRegex(), "").let {
                            if (it.trim().isEmpty()) {
                                ""
                            } else {
                                it
                            }
                        }
                    } else {
                        line
                    }
                } else {
                    val currIndent = indentStack.peek()!!
                    if (trimmed.isEmpty()) {
                        " ".repeat(currIndent) + kws.eval
                    } else if (!trimmed.startsWith(kws.eval) && currIndent <= line.indentation) {
                        // Line has been disabled, so we want to use its non-remapped content instead.
                        // For one, the remapped content would be useless anyway since it's commented out
                        // and, more importantly, if we do not preserve it, we might permanently loose it as the
                        // remap process is only guaranteed to work on code which compiles and since we're
                        // just about to comment it out, it probably doesn't compile.
                        " ".repeat(currIndent) + kws.eval + " " + originalLine.substring(currIndent)
                    } else {
                        line
                    }
                }
            }
        }.also {
            if (ifStack.isNotEmpty()) {
                throw ParserException("Missing endif in $fileName")
            }
        }
    }

    fun convertFile(kws: Keywords, inFile: File, outFile: File, remap: ((List<String>) -> List<String>)? = null) {
        val string = inFile.readText()
        var lines = string.lines()
        val remapped = remap?.invoke(lines) ?: lines
        try {
            lines = convertSource(kws, lines, remapped, inFile.path)
        } catch (e: Throwable) {
            if (e is ParserException) {
                throw e
            }
            throw RuntimeException("Failed to convert file $inFile", e)
        }
        outFile.parentFile.mkdirs()
        outFile.writeText(lines.joinToString("\n"))
    }

    class ParserException(str: String): RuntimeException(str)
}

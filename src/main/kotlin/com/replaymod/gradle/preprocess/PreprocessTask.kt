package com.replaymod.gradle.preprocess

import com.replaymod.gradle.remap.Transformer
import com.replaymod.gradle.remap.legacy.LegacyMapping
import com.replaymod.gradle.remap.legacy.LegacyMappingSetModelFactory
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.slf4j.LoggerFactory
import java.io.File
import java.io.Serializable
import java.nio.file.Files
import java.util.regex.Pattern

data class Keywords(
        val disableRemap: String,
        val enableRemap: String,
        val `if`: String,
        val ifdef: String,
        val elseif: String,
        val `else`: String,
        val endif: String,
        val eval: String
) : Serializable

open class PreprocessTask : DefaultTask() {
    companion object {
        @JvmStatic
        val DEFAULT_KEYWORDS = Keywords(
                disableRemap = "//#disable-remap",
                enableRemap = "//#enable-remap",
                `if` = "//#if ",
                ifdef = "//#ifdef ",
                elseif = "//#elseif",
                `else` = "//#else",
                endif = "//#endif",
                eval = "//$$"
        )
        @JvmStatic
        val CFG_KEYWORDS = Keywords(
                disableRemap = "##disable-remap",
                enableRemap = "##enable-remap",
                `if` = "##if ",
                ifdef = "##ifdef ",
                elseif = "##elseif",
                `else` = "##else",
                endif = "##endif",
                eval = "#$$"
        )

        private val LOGGER = LoggerFactory.getLogger(PreprocessTask::class.java)
    }

    @OutputDirectory
    var generated: File? = null

    @Internal
    var source: FileCollection? = null

    @InputFiles
    @SkipWhenEmpty
    fun getSourceFileTrees(): List<ConfigurableFileTree>? {
        return source?.map { project.fileTree(it) }
    }

    @InputDirectory
    @Optional
    var overwrites: File? = null

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

    @InputFiles
    @Optional
    var remappedClasspath: FileCollection? = null

    @Input
    val vars = project.objects.mapProperty<String, Int>()

    @Input
    val keywords = project.objects.mapProperty<String, Keywords>()

    @Input
    @Optional
    val patternAnnotation = project.objects.property<String>()

    fun source(file: Any) {
        source = project.files(file)
    }

    fun generated(file: Any) {
        generated = project.file(file)
    }

    fun compileTask(task: AbstractCompile) {
        dependsOn(task)
        classpath = (classpath ?: project.files()) + task.classpath + project.files(task.destinationDir)
    }

    @TaskAction
    fun preprocess() {
        val source = source!!
        val outPath = generated!!.toPath()
        val overwritesPath = overwrites?.toPath()
        var mappedSources: Map<String, Pair<String, List<Pair<Int, String>>>>? = null

        val mapping = mapping
        val classpath = classpath
        if (classpath != null && (mapping != null || sourceMappings != null && destinationMappings != null)) {
            val mappings = if (mapping != null) {
                if (sourceMappings != null && destinationMappings != null) {
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
            } else {
                val srcMap = sourceMappings!!.readMappings()
                val dstMap = destinationMappings!!.readMappings()
                srcMap.join(dstMap.reverse())
            }
            MappingFormats.SRG.write(mappings, project.buildDir.resolve(name).resolve("mapping.srg").toPath().also {
                Files.createDirectories(it.parent)
            })
            val javaTransformer = Transformer(mappings)
            javaTransformer.patternAnnotation = patternAnnotation.orNull
            LOGGER.debug("Remap Classpath:")
            javaTransformer.classpath = classpath.files.mapNotNull {
                if (it.exists()) {
                    it.absolutePath.also(LOGGER::debug)
                } else {
                    LOGGER.debug("$it (file does not exist)")
                    null
                }
            }.toTypedArray()
            LOGGER.debug("Remapped Classpath:")
            javaTransformer.remappedClasspath = remappedClasspath?.files?.mapNotNull {
                if (it.exists()) {
                    it.absolutePath.also(LOGGER::debug)
                } else {
                    LOGGER.debug("$it (file does not exist)")
                    null
                }
            }?.toTypedArray()
            val sources = mutableMapOf<String, String>()
            val processedSources = mutableMapOf<String, String>()
            source.flatMap { base -> project.fileTree(base).map { Pair(base, it) } }.forEach { (base, file) ->
                if (file.name.endsWith(".java") || file.name.endsWith(".kt")) {
                    val relPath = base.toPath().relativize(file.toPath())
                    val text = file.readText()
                    sources[relPath.toString()] = text
                    val lines = text.lines()
                    val kws = keywords.get().entries.find { (ext, _) -> file.name.endsWith(ext) }
                    if (kws != null) {
                        processedSources[relPath.toString()] = CommentPreprocessor(vars.get()).convertSource(
                                kws.value,
                                lines,
                                lines.map { Pair(it, emptyList()) },
                                file.toString()
                        ).joinToString("\n")
                    }
                }
            }
            overwritesPath?.let { base -> project.fileTree(base).map { Pair(base, it) } }?.forEach { (base, file) ->
                if (file.name.endsWith(".java") || file.name.endsWith(".kt")) {
                    val relPath = base.relativize(file.toPath())
                    processedSources[relPath.toString()] = file.readText()
                }
            }
            mappedSources = javaTransformer.remap(sources, processedSources)
        }

        project.delete(outPath)

        val commentPreprocessor = CommentPreprocessor(vars.get())
        source.flatMap { base -> project.fileTree(base).map { Pair(base, it) } }.forEach { (base, file) ->
            val relPath = base.toPath().relativize(file.toPath())
            val outFile = outPath.resolve(relPath).toFile()
            if (overwritesPath != null && Files.exists(overwritesPath.resolve(relPath))) {
                return@forEach
            }
            val kws = keywords.get().entries.find { (ext, _) -> file.name.endsWith(ext) }
            if (kws != null) {
                val javaTransform = { lines: List<String> ->
                    mappedSources?.get(relPath.toString())?.let { (source, errors) ->
                        val errorsByLine = mutableMapOf<Int, MutableList<String>>()
                        for ((line, error) in errors) {
                            errorsByLine.getOrPut(line, ::mutableListOf).add(error)
                        }
                        source.lines().mapIndexed { index: Int, line: String -> Pair(line, errorsByLine[index] ?: emptyList<String>()) }
                    } ?: lines.map { Pair(it, emptyList()) }
                }
                commentPreprocessor.convertFile(kws.value, file, outFile, javaTransform)
            } else {
                project.copy {
                    from(file)
                    into(outFile.parentFile)
                }
            }
        }

        if (commentPreprocessor.fail) {
            throw GradleException("Failed to remap sources. See errors above for details.")
        }
    }
}

class CommentPreprocessor(private val vars: Map<String, Int>) {
    companion object {
        private val EXPR_PATTERN = Pattern.compile("(.+)(==|!=|<=|>=|<|>)(.+)")
        private val OR_PATTERN = Pattern.quote("||").toPattern()
        private val AND_PATTERN = Pattern.quote("&&").toPattern()
    }

    var fail = false

    private fun String.evalVarOrNull() = toIntOrNull() ?: vars[this]
    private fun String.evalVar() = evalVarOrNull() ?: throw NoSuchElementException(this)

    internal fun String.evalExpr(): Boolean {
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

        val result = evalVarOrNull()
        if (result != null) {
            return result != 0
        }

        val matcher = EXPR_PATTERN.matcher(this)
        if (matcher.matches()) {
            val lhs = matcher.group(1).trim().evalVar()
            val rhs = matcher.group(3).trim().evalVar()
            return when (matcher.group(2)) {
                "==" -> lhs == rhs
                "!=" -> lhs != rhs
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

    fun convertSource(kws: Keywords, lines: List<String>, remapped: List<Pair<String, List<String>>>, fileName: String): List<String> {
        val stack = mutableListOf<IfStackEntry>()
        val indentStack = mutableListOf<Int>()
        var active = true
        var remapActive = true
        var n = 0

        return lines.zip(remapped).map { (originalLine, lineMapped) ->
            val (line, errors) = lineMapped
            var ignoreErrors = false
            n++
            val trimmed = line.trim()
            val mapped = if (trimmed.startsWith(kws.`if`)) {
                val result = trimmed.substring(kws.`if`.length).trim().evalExpr()
                stack.push(IfStackEntry(result, elseFound = false, trueFound = result))
                indentStack.push(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.elseif)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected elseif in line $n of $fileName")
                }
                if (stack.last().elseFound) {
                    throw ParserException("Unexpected elseif after else in line $n of $fileName")
                }

                indentStack.pop()
                indentStack.push(line.indentation)

                active = if (stack.last().trueFound) {
                    val last = stack.pop()
                    stack.push(last.copy(currentValue = false))
                    false
                } else {
                    val result = trimmed.substring(kws.elseif.length).trim().evalExpr()
                    stack.pop()
                    stack.push(IfStackEntry(result, elseFound = false, trueFound = result))
                    stack.all { it.currentValue }
                }
                line
            } else if (trimmed.startsWith(kws.`else`)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected else in line $n of $fileName")
                }
                val entry = stack.pop()
                stack.push(IfStackEntry(!entry.trueFound, elseFound = true, trueFound = entry.trueFound))
                indentStack.pop()
                indentStack.push(line.indentation)
                active = stack.all { it.currentValue }
                line
            } else if (trimmed.startsWith(kws.ifdef)) {
                val result = vars.containsKey(trimmed.substring(kws.ifdef.length))
                stack.push(IfStackEntry(result, elseFound = false, trueFound = result))
                indentStack.push(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.endif)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected endif in line $n of $fileName")
                }
                stack.pop()
                indentStack.pop()
                active = stack.all { it.currentValue }
                line
            } else if (trimmed.startsWith(kws.disableRemap)) {
                if (!remapActive) {
                    throw ParserException("Remapping already disabled in line $n of $fileName")
                }
                remapActive = false
                line
            } else if (trimmed.startsWith(kws.enableRemap)) {
                if (remapActive) {
                    throw ParserException("Remapping not disabled in line $n of $fileName")
                }
                remapActive = true
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
                    } else if (remapActive) {
                        line
                    } else {
                        ignoreErrors = true
                        originalLine
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
                        ignoreErrors = true
                        " ".repeat(currIndent) + kws.eval + " " + originalLine.substring(currIndent)
                    } else {
                        line
                    }
                }
            }
            if (errors.isNotEmpty() && !ignoreErrors) {
                fail = true
                for (message in errors) {
                    System.err.println("$fileName:$n: $message")
                }
            }
            mapped
        }.also {
            if (stack.isNotEmpty()) {
                throw ParserException("Missing endif in $fileName")
            }
        }
    }

    fun convertFile(kws: Keywords, inFile: File, outFile: File, remap: ((List<String>) -> List<Pair<String, List<String>>>)? = null) {
        val string = inFile.readText()
        var lines = string.lines()
        val remapped = remap?.invoke(lines) ?: lines.map { Pair(it, emptyList()) }
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

    data class IfStackEntry(
        var currentValue: Boolean,
        var elseFound: Boolean = false,
        var trueFound: Boolean = false
    )

    class ParserException(str: String): RuntimeException(str)
}

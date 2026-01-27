package com.replaymod.gradle.preprocess

import com.replaymod.gradle.remap.Transformer
import com.replaymod.gradle.remap.legacy.LegacyMapping
import com.replaymod.gradle.remap.legacy.LegacyMappingSetModelFactory
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.io.Serializable
import java.lang.ref.SoftReference
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.io.path.bufferedReader
import kotlin.io.path.extension

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

@CacheableTask
open class PreprocessTask @Inject constructor(
    private val objects: ObjectFactory,
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {
    companion object {
        @JvmStatic
        val DEFAULT_KEYWORDS = Keywords(
                disableRemap = "//#disable-remap",
                enableRemap = "//#enable-remap",
                `if` = "//#if",
                ifdef = "//#ifdef",
                elseif = "//#elseif",
                `else` = "//#else",
                endif = "//#endif",
                eval = "//$$"
        )
        @JvmStatic
        val CFG_KEYWORDS = Keywords(
                disableRemap = "##disable-remap",
                enableRemap = "##enable-remap",
                `if` = "##if",
                ifdef = "##ifdef",
                elseif = "##elseif",
                `else` = "##else",
                endif = "##endif",
                eval = "#$$"
        )
    }

    data class InOut(
        val source: FileCollection,
        val generated: File,
        val overwrites: File?,
    )

    @Internal
    var entries: MutableList<InOut> = mutableListOf()

    @InputFiles
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getSourceFileTrees(): List<ConfigurableFileTree> {
        return entries.flatMap { it.source }.map { objects.fileTree().from(it) }
    }

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getOverwritesFileTrees(): List<ConfigurableFileTree> {
        return entries.mapNotNull { it.overwrites }.map { objects.fileTree().from(it) }
    }

    @OutputDirectories
    fun getGeneratedDirectories(): List<File> {
        return entries.map { it.generated }
    }

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var sourceMappings: File? = null

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var destinationMappings: File? = null

    // Note: Requires that source and destination mappings files to be in `tiny` format.
    @Input
    @Optional // required if source or destination mappings have more than two namespaces (optional for backwards compat)
    val intermediateMappingsName = objects.property<String>()

    @Input
    val strictExtraMappings = objects.property<Boolean>().convention(false)

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var mapping: File? = null

    @Input
    var reverseMapping: Boolean = false

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val jdkHome = objects.directoryProperty()

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val remappedjdkHome = objects.directoryProperty()

    @InputFiles
    @Optional
    @CompileClasspath
    var classpath: FileCollection? = null

    @InputFiles
    @Optional
    @CompileClasspath
    var remappedClasspath: FileCollection? = null

    @Input
    val vars = objects.mapProperty<String, Int>()

    @Input
    val keywords = objects.mapProperty<String, Keywords>()

    @Input
    @Optional
    val patternAnnotation = objects.property<String>()

    @Input
    @Optional
    val manageImports = objects.property<Boolean>()

    @Classpath
    val compiler = objects.fileCollection()

    fun entry(source: FileCollection, generated: File, overwrites: File) {
        entries.add(InOut(source, generated, overwrites))
    }

    @TaskAction
    fun preprocess() {
        preprocess(mapping, entries)
    }

    fun preprocess(mappingIn: File?, entriesIn: List<InOut>) {
        val workQueue = if (compiler.isEmpty) {
            workerExecutor.noIsolation()
        } else {
            // See comment on `executeIsolated` below
            workerExecutor.noIsolation()
            //workerExecutor.classLoaderIsolation {
            //    classpath.from(compiler)
            //}
        }

        workQueue.submit(PreprocessAction::class) {
            compiler.set(this@PreprocessTask.compiler)
            entries.set(entriesIn.map { entry ->
                objects.newInstance(PreprocessParameters.InOut::class).apply {
                    source.set(entry.source)
                    generated.set(entry.generated)
                    overwrites.set(entry.overwrites)
                }
            })
            sourceMappings.set(this@PreprocessTask.sourceMappings)
            destinationMappings.set(this@PreprocessTask.destinationMappings)
            intermediateMappingsName.set(this@PreprocessTask.intermediateMappingsName)
            strictExtraMappings.set(this@PreprocessTask.strictExtraMappings)
            mapping.set(mappingIn)
            reverseMapping.set(this@PreprocessTask.reverseMapping)
            jdkHome.set(this@PreprocessTask.jdkHome)
            remappedjdkHome.set(this@PreprocessTask.remappedjdkHome)
            classpath.set(this@PreprocessTask.classpath)
            remappedClasspath.set(this@PreprocessTask.remappedClasspath)
            vars.set(this@PreprocessTask.vars)
            keywords.set(this@PreprocessTask.keywords)
            patternAnnotation.set(this@PreprocessTask.patternAnnotation)
            manageImports.set(this@PreprocessTask.manageImports)
        }

        workQueue.await()
    }
}

internal interface PreprocessParameters : WorkParameters {
    val compiler: Property<FileCollection>

    interface InOut {
        val source: Property<FileCollection>
        val generated: Property<File>
        val overwrites: Property<File> // optional
    }
    val entries: ListProperty<InOut>
    val sourceMappings: Property<File> // optional
    val destinationMappings: Property<File> // optional
    val intermediateMappingsName: Property<String> // optional depending on other properties
    val strictExtraMappings: Property<Boolean>
    val mapping: Property<File> // optional
    val reverseMapping: Property<Boolean>
    val jdkHome: DirectoryProperty // optional
    val remappedjdkHome: DirectoryProperty // optional
    val classpath: Property<FileCollection> // optional
    val remappedClasspath: Property<FileCollection> // optional
    val vars: MapProperty<String, Int>
    val keywords: MapProperty<String, Keywords>
    val patternAnnotation: Property<String> // optional
    val manageImports: Property<Boolean> // optional
}

private val LOGGER = Logging.getLogger(PreprocessTask::class.java)

internal abstract class PreprocessAction : WorkAction<PreprocessParameters> {
    override fun execute() {
        val compiler = parameters.compiler.get()
        if (compiler.isEmpty) {
            PreprocessActionImpl().accept(parameters)
        } else {
            executeIsolated(compiler)
        }
    }

    // Work around for https://github.com/gradle/gradle/issues/34442
    // Additionally, it seems that Gradle's classpath isolated work queue doesn't re-use class loaders even when the
    // classpath is unchanged, which is really bad for performance (like 7x slowdown) in our case, since we're loading
    // the whole Kotlin compiler (and it may internally cache various stuff as well).
    // So instead we'll completely disable Gradle's isolation and do our own caching.
    private fun executeIsolated(compilerClasspath: FileCollection) {
        val fullClasspath =
            compilerClasspath.files.map { it.toURI().toURL() } + listOf(
                PreprocessActionImpl::class.java,
                Transformer::class.java,
            ).map { it.protectionDomain.codeSource.location }

        LOGGER.debug("Remap IsolatedClassLoader classpath:")
        fullClasspath.forEach { LOGGER.debug(" - {}", it) }

        val cacheKey = fullClasspath.map { it.toString() } // URL has bad `equals`; its `toString` is good enough for us
        val classLoader = synchronized(cache) {
            cache.values.removeIf { it.get() == null }
            cache[cacheKey]?.get() ?: IsolatedClassLoader(
                fullClasspath.toTypedArray(),
                javaClass.classLoader,
                exclusions = listOf(
                    "org.gradle.",
                    "net.fabricmc.mappingio.",
                    "org.cadixdev.lorenz.",
                    "org.cadixdev.bombe.",
                    "org.objectweb.asm.",
                    PreprocessParameters::class.java.name,
                    Keywords::class.java.name,
                ),
            ).also { cache[cacheKey] = SoftReference(it) }
        }

        val implClass = classLoader.loadClass(PreprocessActionImpl::class.java.name)
        val implInstance = implClass.constructors.first().apply { isAccessible = true }.newInstance()

        @Suppress("UNCHECKED_CAST")
        (implInstance as Consumer<PreprocessParameters>).accept(parameters)
    }

    companion object {
        private val cache = mutableMapOf<List<String>, SoftReference<IsolatedClassLoader>>()
    }
}

private class PreprocessActionImpl : Consumer<PreprocessParameters> {
    override fun accept(params: PreprocessParameters) {
        val logger = LOGGER
        val entries = params.entries.get().map { PreprocessTask.InOut(it.source.get(), it.generated.get(), it.overwrites.orNull) }
        val sourceMappings = params.sourceMappings.orNull
        val destinationMappings = params.destinationMappings.orNull
        val intermediateMappingsName = params.intermediateMappingsName
        val strictExtraMappings = params.strictExtraMappings
        val mapping = params.mapping.orNull
        val reverseMapping = params.reverseMapping.get()
        val jdkHome = params.jdkHome
        val remappedjdkHome = params.remappedjdkHome
        val classpath = params.classpath.orNull
        val remappedClasspath = params.remappedClasspath.orNull
        val vars = params.vars
        val keywords = params.keywords
        val patternAnnotation = params.patternAnnotation
        val manageImports = params.manageImports

        data class Entry(val relPath: String, val inBase: Path, val outBase: Path, val overwritesBase: Path?)
        val sourceFiles: List<Entry> = entries.flatMap { inOut ->
            val outBasePath = inOut.generated.toPath()
            val overwritesBasePath = inOut.overwrites?.toPath()
            inOut.source.flatMap { inBase ->
                val inBasePath = inBase.toPath()
                inBase.walk().filter { it.isFile }.map { file ->
                    val relPath = inBasePath.relativize(file.toPath())
                    Entry(relPath.toString(), inBasePath, outBasePath, overwritesBasePath)
                }
            }
        }

        var mappedSources: Map<String, Pair<String, List<Pair<Int, String>>>>? = null

        val sourceMappingsFile = sourceMappings
        val destinationMappingsFile = destinationMappings
        val mappings = if (intermediateMappingsName.isPresent && classpath != null && sourceMappingsFile != null && destinationMappingsFile != null) {
            val sharedMappingsNamespace = intermediateMappingsName.get()
            val srcTree = MemoryMappingTree().also { readMappings(sourceMappingsFile.toPath(), it) }
            val dstTree = MemoryMappingTree().also { readMappings(destinationMappingsFile.toPath(), it) }
            if (strictExtraMappings.get()) {
                if (sharedMappingsNamespace == "srg") {
                    inferSharedClassMappings(srcTree, dstTree, sharedMappingsNamespace)
                }
                srcTree.setIndexByDstNames(true)
                dstTree.setIndexByDstNames(true)
                val extTree = mapping?.let { file ->
                    try {
                        val ast = ExtraMapping.read(file.toPath())
                        if (!reverseMapping) {
                            ast.resolve(logger, srcTree, dstTree, "named", sharedMappingsNamespace).first
                        } else {
                            ast.resolve(logger, dstTree, srcTree, "named", sharedMappingsNamespace).second
                        }
                    } catch (e: Exception) {
                        throw GradleException("Failed to parse $file: ${e.message}", e)
                    }
                } ?: MemoryMappingTree().apply {
                    visitNamespaces("source", listOf("destination"))
                    visitEnd()
                }
                val mrgTree = mergeMappings(srcTree, dstTree, extTree, sharedMappingsNamespace)
                TinyReader(mrgTree, "source", "destination").read()
            } else {
                val sourceMappings = TinyReader(srcTree, "named", sharedMappingsNamespace).read()
                val destinationMappings = TinyReader(dstTree, "named", sharedMappingsNamespace).read()
                if (mapping != null) {
                    val legacyMap = LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
                    val clsMap = legacyMap.splitOffClassMappings()
                    val srcMap = sourceMappings
                    val dstMap = destinationMappings
                    legacyMap.mergeBoth(
                        // The inner clsMap is to make the join work, the outer one for custom classes (which are not part of
                        // dstMap and would otherwise be filtered by the join)
                        srcMap.mergeBoth(clsMap).join(dstMap.reverse()).mergeBoth(clsMap),
                        MappingSet.create(LegacyMappingSetModelFactory()))
                } else {
                    val srcMap = sourceMappings!!
                    val dstMap = destinationMappings!!
                    srcMap.join(dstMap.reverse())
                }
            }
        } else if (!intermediateMappingsName.isPresent && classpath != null && (mapping != null || sourceMappings != null && destinationMappings != null)) {
            if (mapping != null) {
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
        } else {
            null
        }
        if (mappings != null) {
            classpath!!
            val javaTransformer = Transformer(mappings)
            javaTransformer.verboseCompilerMessages = logger.isInfoEnabled
            javaTransformer.patternAnnotation = patternAnnotation.orNull
            javaTransformer.manageImports = manageImports.getOrElse(false)
            javaTransformer.jdkHome = jdkHome.orNull?.asFile
            javaTransformer.remappedJdkHome = remappedjdkHome.orNull?.asFile
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
            sourceFiles.forEach { (relPath, inBase, _, _) ->
                if (relPath.endsWith(".java") || relPath.endsWith(".kt")) {
                    val text = String(Files.readAllBytes(inBase.resolve(relPath)))
                    sources[relPath] = text
                    val lines = text.lines()
                    val kws = keywords.get().entries.find { (ext, _) -> relPath.endsWith(ext) }
                    if (kws != null) {
                        processedSources[relPath] = CommentPreprocessor(vars.get()).convertSource(
                                kws.value,
                                lines,
                                lines.map { Pair(it, emptyList()) },
                                relPath
                        ).joinToString("\n")
                    }
                }
            }
            val overwritesFiles = entries
                .mapNotNull { it.overwrites }
                .flatMap { base -> base.walk().filter { it.isFile }.map { Pair(base.toPath(), it) } }
            overwritesFiles.forEach { (base, file) ->
                if (file.name.endsWith(".java") || file.name.endsWith(".kt")) {
                    val relPath = base.relativize(file.toPath())
                    processedSources[relPath.toString()] = file.readText()
                }
            }
            mappedSources = javaTransformer.remap(sources, processedSources)
        }

        entries.forEach { it.generated.deleteRecursively() }

        val commentPreprocessor = CommentPreprocessor(vars.get())
        sourceFiles.forEach { (relPath, inBase, outBase, overwritesPath) ->
            val file = inBase.resolve(relPath).toFile()
            val outFile = outBase.resolve(relPath).toFile()
            if (overwritesPath != null && Files.exists(overwritesPath.resolve(relPath))) {
                return@forEach
            }
            val kws = keywords.get().entries.find { (ext, _) -> file.name.endsWith(ext) }
            if (kws != null) {
                val javaTransform = { lines: List<String> ->
                    mappedSources?.get(relPath)?.let { (source, errors) ->
                        val errorsByLine = mutableMapOf<Int, MutableList<String>>()
                        for ((line, error) in errors) {
                            errorsByLine.getOrPut(line, ::mutableListOf).add(error)
                        }
                        source.lines().mapIndexed { index: Int, line: String -> Pair(line, errorsByLine[index] ?: emptyList<String>()) }
                    } ?: lines.map { Pair(it, emptyList()) }
                }
                commentPreprocessor.convertFile(kws.value, file, outFile, javaTransform)
            } else {
                outFile.parentFile.mkdirs()
                file.copyTo(outFile)
            }
        }

        if (commentPreprocessor.fail) {
            throw GradleException("Failed to remap sources. See errors above for details.")
        }
    }

    private fun readMappings(path: Path, visitor: MappingVisitor) {
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

    /**
     * Tries to infer shared classes based on shared members.
     *
     * Forge uses intermediate mappings ("SRG", same as the original file format they came in) which do not contain
     * intermediate names for classes, only methods and fields. As such, one would have to manually declare mappings
     * for all classes one cares about.
     * It does however still track methods and fields even when the class name changes, so we can make use of those
     * to infer a good deal of class mappings automatically.
     *
     * This method infers these mappings, and updates the input trees to use them.
     */
    private fun inferSharedClassMappings(
        srcTree: MemoryMappingTree,
        dstTree: MemoryMappingTree,
        sharedNamespace: String,
    ) {
        val srcNsId = srcTree.getNamespaceId(sharedNamespace)
        val dstNsId = dstTree.getNamespaceId(sharedNamespace)

        val done = mutableSetOf<String>()

        // Check for classes which didn't change their name (presumably)
        for (srcCls in srcTree.classes) {
            val srcName = srcCls.getName(srcNsId)!!
            if (dstTree.getClass(srcName, dstNsId) != null) {
                done.add(srcName)
            }
        }

        // This isn't entirely straightforward though because inherited methods (and their synthetic overload methods)
        // have the same names as super methods, so we can't just assume a match on the first shared method.
        // Instead, we'll do multiple rounds and in each one we only pair those classes that unambiguously match.
        var nextSharedId = 0
        do {
            val doneBeforeRound = done.size

            val srcMemberToClass = mutableMapOf<String, MutableList<String>>()
            for (cls in srcTree.classes) {
                val clsName = cls.getName(srcNsId)!!
                if (clsName in done) {
                    continue
                }
                for (field in cls.fields) {
                    val name = field.getName(srcNsId)!!
                    if (!name.startsWith("field_")) continue
                    srcMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
                for (method in cls.methods) {
                    val name = method.getName(srcNsId)!!
                    if (!name.startsWith("func_")) continue
                    srcMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
            }
            val dstMemberToClass = mutableMapOf<String, MutableList<String>>()
            for (cls in dstTree.classes) {
                val clsName = cls.getName(dstNsId)!!
                if (clsName in done) {
                    continue
                }
                for (field in cls.fields) {
                    val name = field.getName(dstNsId)!!
                    if (!name.startsWith("field_")) continue
                    dstMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
                for (method in cls.methods) {
                    val name = method.getName(dstNsId)!!
                    if (!name.startsWith("func_")) continue
                    dstMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
            }

            val srcMappings = tryInferMapping(srcTree, srcNsId, dstMemberToClass, done)
            val dstMappings = tryInferMapping(dstTree, dstNsId, srcMemberToClass, done)

            for ((srcName, dstNames) in srcMappings) {
                if (dstNames.isEmpty()) {
                    continue
                }
                if (dstNames.size > 1) {
                    // println("Multiple dst classes for $srcName: $dstNames")
                    continue
                }
                val dstName = dstNames.single()

                val revSrcNames = dstMappings.getValue(dstName)
                assert(revSrcNames.isNotEmpty())
                if (revSrcNames.size > 1) {
                    // println("Multiple src classes for $dstName: $revSrcNames")
                    continue
                }
                val revSrcName = revSrcNames.single()
                if (revSrcName != srcName) {
                    // println("Conflicting mappings $srcName -> $dstName -> $revSrcName")
                    continue
                }

                val srcCls = srcTree.getClass(srcName, srcNsId)!!
                val dstCls = dstTree.getClass(dstName, dstNsId)!!

                val sharedName = "class_${nextSharedId++}"
                // println("Discovered mapping $srcName -> $dstName, assigning $sharedName")
                srcCls.setDstName(sharedName, srcNsId)
                dstCls.setDstName(sharedName, dstNsId)
                done.add(sharedName)
            }
        } while (done.size > doneBeforeRound)
    }

    private fun tryInferMapping(
        srcTree: MappingTree,
        srcNsId: Int,
        dstMemberToClass: Map<String, List<String>>,
        done: Set<String>,
    ): Map<String, Collection<String>> {
        val results = mutableMapOf<String, Collection<String>>()
        for (srcCls in srcTree.classes) {
            val srcName = srcCls.getName(srcNsId)!!
            if (srcName in done) {
                continue
            }

            val candidates = mutableMapOf<String, Int>()

            for (srcField in srcCls.fields) {
                for (dstCls in dstMemberToClass[srcField.getName(srcNsId)!!] ?: emptyList()) {
                    candidates.compute(dstCls) { _, n -> (n ?: 0) + 1 }
                }
            }
            for (srcMethod in srcCls.methods) {
                for (dstCls in dstMemberToClass[srcMethod.getName(srcNsId)!!] ?: emptyList()) {
                    candidates.compute(dstCls) { _, n -> (n ?: 0) + 1 }
                }
            }

            if (candidates.isEmpty()) {
                results[srcName] = emptyList()
                continue
            }

            val (bestName, bestCount) = candidates.maxBy { it.value }
            if (candidates.all { (name, count) -> name === bestName || count < bestCount }) {
                results[srcName] = listOf(bestName)
            } else {
                results[srcName] = candidates.keys
            }
        }
        return results
    }

    private fun mergeMappings(
        srcTree: MappingTree,
        dstTree: MappingTree,
        extTree: MemoryMappingTree,
        sharedNamespace: String,
    ): MappingTree {
        val srcNamedNsId = srcTree.getNamespaceId("named")
        val srcSharedNsId = srcTree.getNamespaceId(sharedNamespace)
        val dstSharedNsId = dstTree.getNamespaceId(sharedNamespace)
        val dstNamedNsId = dstTree.getNamespaceId("named")
        val extSrcNsId = extTree.getNamespaceId("source")
        val extDstNsId = extTree.getNamespaceId("destination")

        val tmpTree = MemoryMappingTree()
        tmpTree.visitNamespaces(dstTree.srcNamespace, dstTree.dstNamespaces)
        val mrgTree = MemoryMappingTree()
        mrgTree.visitNamespaces("source", listOf("destination"))

        fun injectExtraMembers(extCls: MappingTree.ClassMapping) {
            for (extField in extCls.fields) {
                val srcName = extField.getName(extSrcNsId)
                val srcDesc = extField.getDesc(extSrcNsId)
                if (srcDesc == null) {
                    LOGGER.error("Owner ${extCls.getName(extSrcNsId)} of field $srcName does not appear to have any mappings. " +
                        "As such, you must provide the full signature of this method manually " +
                        "(if it does not change across versions, providing it for either version is sufficient).")
                    continue
                }
                mrgTree.visitField(srcName, srcDesc)
                mrgTree.visitDstName(MappedElementKind.FIELD, 0, extField.getName(extDstNsId))
            }
            for (extMethod in extCls.methods) {
                val srcName = extMethod.getName(extSrcNsId)
                val srcDesc = extMethod.getDesc(extSrcNsId)
                if (srcDesc == null) {
                    LOGGER.error("Owner ${extCls.getName(extSrcNsId)} of method $srcName does not appear to have any mappings. " +
                        "As such, you must provide the full signature of this method manually " +
                        "(if it does not change across versions, providing it for either version is sufficient).")
                    continue
                }
                mrgTree.visitMethod(srcName, srcDesc)
                mrgTree.visitDstName(MappedElementKind.METHOD, 0, extMethod.getName(extDstNsId))
            }
        }

        for (srcCls in srcTree.classes) {
            val extCls = extTree.removeClass(srcCls.getName(srcNamedNsId))
            val dstCls = if (extCls != null) {
                val dstName = extCls.getName(extDstNsId)
                dstTree.getClass(dstName, dstNamedNsId) ?: run {
                    tmpTree.visitClass(dstName)
                    tmpTree.visitDstName(MappedElementKind.CLASS, dstNamedNsId, dstName)
                    tmpTree.getClass(dstName)!!
                }
            } else {
                dstTree.getClass(srcCls.getName(srcSharedNsId), dstSharedNsId) ?: continue
            }
            mrgTree.visitClass(srcCls.getName(srcNamedNsId))
            mrgTree.visitDstName(MappedElementKind.CLASS, 0, dstCls.getName(dstNamedNsId))
            for (srcField in srcCls.fields) {
                val extField = extCls?.getField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId), extSrcNsId)
                if (extField != null) {
                    extCls.removeField(extField.srcName, extField.srcDesc)
                    mrgTree.visitField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId))
                    mrgTree.visitDstName(MappedElementKind.FIELD, 0, extField.getName(extDstNsId))
                    continue
                }
                val dstField = dstCls.getField(srcField.getName(srcSharedNsId), srcField.getDesc(srcSharedNsId), dstSharedNsId)
                    ?: dstCls.getField(srcField.getName(srcSharedNsId), null, dstSharedNsId)
                    ?: continue
                mrgTree.visitField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId))
                mrgTree.visitDstName(MappedElementKind.FIELD, 0, dstField.getName(dstNamedNsId))
            }
            for (srcMethod in srcCls.methods) {
                val extMethod = extCls?.getMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId), extSrcNsId)
                if (extMethod != null) {
                    extCls.removeMethod(extMethod.srcName, extMethod.srcDesc)
                    mrgTree.visitMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId))
                    mrgTree.visitDstName(MappedElementKind.METHOD, 0, extMethod.getName(extDstNsId))
                    continue
                }
                val dstMethod = dstCls.getMethod(srcMethod.getName(srcSharedNsId), srcMethod.getDesc(srcSharedNsId), dstSharedNsId)
                    ?: dstCls.getMethod(srcMethod.getName(srcSharedNsId), null, dstSharedNsId)
                    ?: continue
                mrgTree.visitMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId))
                mrgTree.visitDstName(MappedElementKind.METHOD, 0, dstMethod.getName(dstNamedNsId))
            }
            if (extCls != null) {
                injectExtraMembers(extCls)
            }
        }
        for (extCls in extTree.classes) {
            mrgTree.visitClass(extCls.getName(extSrcNsId))
            mrgTree.visitDstName(MappedElementKind.CLASS, 0, extCls.getName(extDstNsId))
            injectExtraMembers(extCls)
        }
        mrgTree.visitEnd()
        return mrgTree
    }
}

class CommentPreprocessor(private val vars: Map<String, Int>) {
    companion object {
        private val EXPR_PATTERN = Pattern.compile("(.+)(==|!=|<=|>=|<|>)(.+)")
        private val OR_PATTERN = Pattern.quote("||").toPattern()
        private val AND_PATTERN = Pattern.quote("&&").toPattern()
    }

    var fail = false

    private fun String.evalVarOrNull(): Int? {
        vars[this]?.let { return it }

        if ("." in this) {
            val (majorS, minorS, patchS) = this.split(".") + listOf("0")
            val major = majorS.toIntOrNull() ?: return null
            val minor = minorS.toIntOrNull() ?: return null
            val patch = patchS.toIntOrNull() ?: return null
            return major * 1_00_00 + minor * 1_00 + patch
        }

        return replace("_", "").toIntOrNull()
    }
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

        if (startsWith("!")) {
            return !substring(1).evalExpr()
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
                else -> throw InvalidExpressionException(this)
            }
        } else {
            throw InvalidExpressionException(this)
        }
    }

    private val String.indentation: String
        get() = takeWhile { it == ' ' || it == '\t' }

    fun convertSource(kws: Keywords, lines: List<String>, remapped: List<Pair<String, List<String>>>, fileName: String): List<String> {
        val stack = mutableListOf<IfStackEntry>()
        val indentStack = mutableListOf<String>()
        var active = true
        var remapActive = true
        var n = 0

        fun evalCondition(condition: String): Boolean {
            if (!condition.startsWith(" "))
                throw ParserException("Expected space before condition in line $n of $fileName")
            try {
                return condition.trim().evalExpr()
            } catch (e: InvalidExpressionException) {
                throw ParserException("Invalid expression \"${e.message}\" in line $n of $fileName")
            }
        }

        return lines.zip(remapped).map { (originalLine, lineMapped) ->
            val (line, errors) = lineMapped
            var ignoreErrors = false
            n++
            val trimmed = line.trim()
            val mapped = if (trimmed.startsWith(kws.`if`)) {
                val result = evalCondition(trimmed.substring(kws.`if`.length))
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
                    val result = evalCondition(trimmed.substring(kws.elseif.length))
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
                    val currIndent = indentStack.last()
                    if (trimmed.isEmpty()) {
                        currIndent + kws.eval
                    } else if (!trimmed.startsWith(kws.eval) && currIndent.length <= line.indentation.length) {
                        // Line has been disabled, so we want to use its non-remapped content instead.
                        // For one, the remapped content would be useless anyway since it's commented out
                        // and, more importantly, if we do not preserve it, we might permanently loose it as the
                        // remap process is only guaranteed to work on code which compiles and since we're
                        // just about to comment it out, it probably doesn't compile.
                        ignoreErrors = true
                        currIndent + kws.eval + " " + originalLine.substring(currIndent.length)
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

    class InvalidExpressionException(expr: String) : RuntimeException(expr)

    class ParserException(str: String) : RuntimeException(str)
}

private fun <E> MutableList<E>.push(e: E) = add(e)
private fun <E> MutableList<E>.pop() = removeLast()

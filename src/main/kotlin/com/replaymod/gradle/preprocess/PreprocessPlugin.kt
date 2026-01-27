package com.replaymod.gradle.preprocess

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingSpecBuilderImpl
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.JavaCompile

import org.gradle.kotlin.dsl.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.name
import kotlin.io.path.toPath

class PreprocessPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val parent = project.parent
        if (parent == null) {
            project.apply<RootPreprocessPlugin>()
            return
        }

        project.evaluationDependsOn(parent.path)
        val rootExtension = parent.extensions.getByType<RootPreprocessExtension>()
        val graph = rootExtension.rootNode ?: throw IllegalStateException("Preprocess graph was not configured.")
        val projectNode = graph.findNode(project.name) ?: throw IllegalStateException("Prepocess graph does not contain ${project.name}.")

        val coreProjectFile = project.file("../mainProject")
        val coreProject = coreProjectFile.readText().trim()
        if (coreProject !in parent.childProjects) throw IllegalStateException("Configured main project `$coreProject` does not exist.")
        if (graph.findNode(coreProject) == null) throw IllegalStateException("Preprocess graph does not contain main project `$coreProject`.")
        val mcVersion = projectNode.mcVersion
        project.extra["mcVersion"] = mcVersion
        val ext = project.extensions.create("preprocess", PreprocessExtension::class, project.objects, mcVersion)

        val kotlin = project.plugins.hasPlugin("kotlin")
        val remapKotlinCompilerClasspath = setupKotlinCompilerClasspath(project)

        project.the<SourceSetContainer>().configureEach {
            val compileClasspath = if (name == "main") "compileClasspath" else name + "CompileClasspath"
            project.configurations.consumable("preprocess-outgoing-$compileClasspath") {
                extendsFrom(project.configurations[compileClasspath])
            }
        }

        if (coreProject == project.name) {
            project.the<SourceSetContainer>().configureEach {
                java.setSrcDirs(listOf(parent.file("src/$name/java")))
                resources.setSrcDirs(listOf(parent.file("src/$name/resources")))
                if (kotlin) {
                    withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.setSrcDirs(listOf(
                            parent.file("src/$name/kotlin"),
                            parent.file("src/$name/java")
                    ))
                }
            }
        } else {
            val inheritedLink = projectNode.links.find { it.first.findNode(coreProject) != null }
            val (inheritedNode, extraMappings) = inheritedLink ?: graph.findParent(projectNode)!!
            val (mappingFile, mappingFileInverted) = extraMappings
            val reverseMappings = (inheritedLink != null) != mappingFileInverted
            val inherited = parent.evaluationDependsOn(inheritedNode.project)

            project.the<SourceSetContainer>().configureEach {
                val inheritedSourceSet = inherited.the<SourceSetContainer>()[name]
                val cName = if (name == "main") "" else name.uppercaseFirstChar()
                val overwritesKotlin = project.file("src/$name/kotlin").also { it.mkdirs() }
                val overwritesJava = project.file("src/$name/java").also { it.mkdirs() }
                val overwriteResources = project.file("src/$name/resources").also { it.mkdirs() }
                val preprocessedRoot = project.layout.buildDirectory.dir("preprocessed/$name")
                val generatedKotlin = preprocessedRoot.dir("kotlin")
                val generatedJava = preprocessedRoot.dir("java")
                val generatedResources = preprocessedRoot.dir("resources")

                val compileClasspath = if (name == "main") "compileClasspath" else name + "CompileClasspath"
                val incomingCompileClasspath = project.configurations.dependencyScope("preprocess-incoming-$compileClasspath")
                val incomingCompileClasspathResolver = project.configurations.resolvable("${incomingCompileClasspath.name}-resolver") {
                    extendsFrom(incomingCompileClasspath.get())
                }
                project.dependencies {
                    incomingCompileClasspath(project(inherited.path, "preprocess-outgoing-$compileClasspath"))
                }

                val preprocessCode = project.tasks.register<PreprocessTask>("preprocess${cName}Code") {
                    inherited.tasks.findByPath("preprocess${cName}Code")?.let { dependsOn(it) }
                    entry(
                        source = inherited.files(inheritedSourceSet.java.srcDirs),
                        overwrites = overwritesJava,
                        generated = generatedJava.get().asFile,
                    )
                    if (kotlin) {
                        entry(
                            source = inherited.files(inheritedSourceSet.withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.srcDirs.filter {
                                it.endsWith("kotlin")
                            }),
                            overwrites = overwritesKotlin,
                            generated = generatedKotlin.get().asFile,
                        )
                    }
                    jdkHome.set((inherited.tasks["compileJava"] as JavaCompile).javaCompiler.map { it.metadata.installationPath })
                    remappedjdkHome.set((project.tasks["compileJava"] as JavaCompile).javaCompiler.map { it.metadata.installationPath })
                    classpath = project.files(incomingCompileClasspathResolver)
                    remappedClasspath = project.files(project.configurations[compileClasspath])
                    mapping = mappingFile
                    reverseMapping = reverseMappings
                    vars.convention(ext.vars)
                    keywords.convention(ext.keywords)
                    patternAnnotation.convention(ext.patternAnnotation)
                    manageImports.convention(ext.manageImports)
                    compiler.from(remapKotlinCompilerClasspath)
                }
                val sourceJavaTask = project.tasks.findByName("source${name.uppercaseFirstChar()}Java")
                (sourceJavaTask ?: project.tasks["compile${cName}Java"]).dependsOn(preprocessCode)
                java.setSrcDirs(listOf(overwritesJava, preprocessCode.map { generatedJava }))

                if (kotlin) {
                    val kotlinConsumerTask = project.tasks.findByName("source${name.uppercaseFirstChar()}Kotlin")
                            ?: project.tasks["compile${cName}Kotlin"]
                    kotlinConsumerTask.dependsOn(preprocessCode)
                    withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.setSrcDirs(
                            listOf(
                                overwritesKotlin,
                                preprocessCode.map { generatedKotlin },
                                overwritesJava,
                                preprocessCode.map { generatedJava },
                            ))
                }

                val preprocessResources = project.tasks.register<PreprocessTask>("preprocess${cName}Resources") {
                    inherited.tasks.findByPath("preprocess${cName}Resources")?.let { dependsOn(it) }
                    entry(
                        source = inherited.files(inheritedSourceSet.resources.srcDirs),
                        overwrites = overwriteResources,
                        generated = generatedResources.get().asFile,
                    )
                    vars.convention(ext.vars)
                    keywords.convention(ext.keywords)
                    patternAnnotation.convention(ext.patternAnnotation)
                    manageImports.convention(ext.manageImports)
                }
                project.tasks["process${cName}Resources"].dependsOn(preprocessResources)
                resources.setSrcDirs(listOf(overwriteResources, preprocessResources.map { generatedResources }))

                if (mappingFile != null && name == "main") {
                    project.tasks.register<CleanupUnnecessaryMappingsTask>("cleanupUnnecessaryMappings") {
                        this.task.set(preprocessCode)
                        this.mappingFile.set(mappingFile)
                    }
                }
            }

            project.afterEvaluate {
                if ("genSrgs" in project.tasks.names || "createMcpToSrg" in project.tasks.names) {
                    logger.warn("ForgeGradle compatibility in Preprocessor is deprecated." +
                        "Consider switching to architectury-loom (or essential-loom for FG2).")
                    if (rootExtension.strictExtraMappings.getOrElse(false)) {
                        throw UnsupportedOperationException("Strict mappings are only supported with Loom.")
                    }
                } else {
                    val projectSrgMappings = project.tinyMappingsWithSrg
                    val inheritedSrgMappings = inherited.tinyMappingsWithSrg
                    val projectTinyMappings = project.tinyMappings
                    val inheritedTinyMappings = inherited.tinyMappings
                    val generatedMappingsFile = project.layout.buildDirectory.get().asFile.resolve("generatedIdentityMappings.tiny")
                    val generatedMappingsTask = tasks.register("generateIdentityMappingsFromMinecraftJars", GenerateIdentityMappingsFromMinecraftJars::class) {
                        minecraftJars.from(project.extensions.getByType<LoomGradleExtensionAPI>().namedMinecraftJars)
                        output.set(generatedMappingsFile)
                    }
                    tasks.withType<PreprocessTask>().configureEach {
                        if (projectTinyMappings == null && inheritedTinyMappings == null) {
                            // Between two unobfuscated versions
                            dependsOn(generatedMappingsTask)
                            sourceMappings = generatedMappingsFile
                            destinationMappings = generatedMappingsFile
                            intermediateMappingsName.set("named")
                        } else if (projectTinyMappings == null) {
                            // We have source mappings, but target is unobfuscated
                            sourceMappings = inheritedTinyMappings
                            destinationMappings = inherited.mojangMappings
                            intermediateMappingsName.set("official")
                        } else if (inheritedTinyMappings == null) {
                            // We have target mappings, but source is unobfuscated
                            sourceMappings = project.mojangMappings
                            destinationMappings = projectTinyMappings
                            intermediateMappingsName.set("official")
                        } else if ((inheritedSrgMappings != null) == (projectSrgMappings != null)) {
                            sourceMappings = inheritedSrgMappings ?: inheritedTinyMappings
                            destinationMappings = projectSrgMappings ?: projectTinyMappings
                            intermediateMappingsName.set(if (projectSrgMappings != null) "srg" else "intermediary")
                        } else if (inheritedNode.mcVersion == projectNode.mcVersion) {
                            sourceMappings = inheritedTinyMappings
                            destinationMappings = projectTinyMappings
                            intermediateMappingsName.set("official")
                        } else {
                            throw IllegalStateException("Failed to find mappings from $inherited to $project.")
                        }
                        strictExtraMappings.convention(rootExtension.strictExtraMappings.orElse(false))
                    }

                    if (!rootExtension.strictExtraMappings.isPresent) {
                        logger.warn("Legacy extra mappings are deprecated. " +
                            "Please consider enabling strict extra mappings via " +
                            "`preprocess.strictExtraMappings.set(true)` in your root project. " +
                            "You may suppress this message by explicitly setting it to `false`.")
                    }
                    return@afterEvaluate
                }
                val prepareTaskName = "prepareMappingsForPreprocessor"
                val prepareSourceTaskName = "prepareSourceMappingsForPreprocessor"
                val prepareDestTaskName = "prepareDestMappingsForPreprocessor"
                val projectIntermediaryMappings = project.intermediaryMappings
                val inheritedIntermediaryMappings = inherited.intermediaryMappings
                val projectNotchMappings = project.notchMappings
                val inheritedNotchMappings = inherited.notchMappings
                val sourceSrg = project.layout.buildDirectory.get().asFile.resolve(prepareTaskName).resolve("source.srg")
                val destinationSrg = project.layout.buildDirectory.get().asFile.resolve(prepareTaskName).resolve("destination.srg")
                val (prepareSourceTask, prepareDestTask) = if (inheritedIntermediaryMappings.type == projectIntermediaryMappings.type) {
                    Pair(
                        bakeNamedToIntermediaryMappings(prepareSourceTaskName, inheritedIntermediaryMappings, sourceSrg),
                        bakeNamedToIntermediaryMappings(prepareDestTaskName, projectIntermediaryMappings, destinationSrg),
                    )
                } else if (inheritedNotchMappings != null && projectNotchMappings != null
                        && inheritedNode.mcVersion == projectNode.mcVersion) {
                    Pair(
                        bakeNamedToOfficialMappings(prepareSourceTaskName, inheritedNotchMappings, inheritedIntermediaryMappings, sourceSrg),
                        bakeNamedToOfficialMappings(prepareDestTaskName, projectNotchMappings, projectIntermediaryMappings, destinationSrg),
                    )
                } else {
                    throw IllegalStateException("Failed to find mappings from $inherited to $project.")
                }
                tasks.withType<PreprocessTask>().configureEach {
                    sourceMappings = sourceSrg
                    destinationMappings = destinationSrg
                    dependsOn(prepareSourceTask)
                    dependsOn(prepareDestTask)
                }
            }

            project.tasks.register<Copy>("setCoreVersion") {
                outputs.upToDateWhen { false }

                from(project.file("src"))
                from(project.layout.buildDirectory.dir("preprocessed"))
                into(project.parent!!.layout.projectDirectory.dir("src"))

                project.the<SourceSetContainer>().all {
                    val cName = if (name == "main") "" else name.uppercaseFirstChar()

                    dependsOn(project.tasks.named("preprocess${cName}Code"))
                    dependsOn(project.tasks.named("preprocess${cName}Resources"))
                }

                doFirst {
                    // "Overwrites" for the core project are just stored in the main sources
                    // which will get overwritten soon, so we need to preserve those.
                    // Specifically, assume we've got projects A, B and C with C being the current
                    // core project and A being the soon to be one:
                    // If there is an overwrite in B, we need to preserve A's source version in A's overwrites.
                    // If there is an overwrite in C, we need to preserve B's version in B's overwrites and get
                    // rid of C's overwrite since it will now be stored in the main sources.
                    fun preserveOverwrites(project: Project, toBePreserved: List<Path>?) {
                        val overwrites = project.file("src").toPath()
                        val overwritten = overwrites.toFile()
                                .walk()
                                .filter { it.isFile }
                                .map { overwrites.relativize(it.toPath()) }
                                .toList()

                        // For the soon-to-be-core project, we must not yet delete the overwrites
                        // as they have yet to be copied into the main sources.
                        if (toBePreserved != null) {
                            val source = if (project.name == coreProject) {
                                project.parent!!.file( "src").toPath()
                            } else {
                                project.layout.buildDirectory.dir("preprocessed").get().asFile.toPath()
                            }
                            project.delete(overwrites)
                            toBePreserved.forEach { name ->
                                project.copy {
                                    from(source.resolve(name))
                                    into(overwrites.resolve(name).parent)
                                }
                            }
                        }

                        if (project.name != coreProject) {
                            val node = graph.findNode(project.name)!!
                            val nextLink = node.links.find { it.first.findNode(coreProject) != null }
                            val (nextNode, _) = nextLink ?: graph.findParent(node)!!
                            val nextProject = parent.project(nextNode.project)
                            preserveOverwrites(nextProject, overwritten)
                        }
                    }
                    preserveOverwrites(project, null)
                }

                doLast {
                    // Once our own overwrites have been copied into the main sources, we should remove them.
                    val overwrites = project.file("src")
                    project.delete(overwrites)
                    project.mkdir(overwrites)
                }

                doLast {
                    coreProjectFile.writeText(project.name)
                }
            }
        }
    }

    private fun setupKotlinCompilerClasspath(project: Project): Configuration {
        val remapKotlinCompiler by project.configurations.creating
        val remapKotlinCompilerClasspath by project.configurations.creating {
            extendsFrom(remapKotlinCompiler)
        }

        var appliedKotlinGradlePluginVersion: String? = null
        project.pluginManager.withPlugin("kotlin") {
            try {
                // Hack to find the version of the applied Kotlin Gradle Plugin so we can by default use the same
                // version with remap
                appliedKotlinGradlePluginVersion = project.plugins.getPlugin("kotlin")
                    .javaClass.protectionDomain.codeSource.location.toURI().toPath()
                    .parent.parent.name
            } catch (e: Exception) {
                project.logger.error("Failed to determine version of applied Kotlin plugin, falling back to $KOTLIN_COMPILER_VERSION.")
            }
        }
        project.afterEvaluate {
            val version = appliedKotlinGradlePluginVersion ?: KOTLIN_COMPILER_VERSION
            project.dependencies.add(remapKotlinCompiler.name, "$KOTLIN_COMPILER_EMBEDDABLE:$version")
        }

        return remapKotlinCompilerClasspath
    }
}

internal class MappingsFile(
    @Input
    val type: String,
    @Input
    val format: String,
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    val file: File,
)
private fun Mappings.toFile() = MappingsFile(type, format, file)

@CacheableTask
internal abstract class BakeNamedToIntermediaryMappings : DefaultTask() {
    @get:Nested
    abstract val mappings: Property<MappingsFile>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun prepare() {
        val mappings = mappings.get()
        val mapping = if (mappings.format == "tiny") {
            val tiny = MemoryMappingTree().also { MappingReader.read(mappings.file.toPath(), it) }
            TinyReader(tiny, "named", if (mappings.type == "searge") "srg" else "intermediary").read()
        } else {
            readMappings(mappings.format, mappings.file.toPath())
        }
        MappingFormats.SRG.write(mapping, output.get().asFile.toPath())
    }
}

@CacheableTask
internal abstract class BakeNamedToOfficialMappings : DefaultTask() {
    @get:Nested
    abstract val mappings: Property<MappingsFile>

    @get:Nested
    @get:Optional
    abstract val namedToIntermediaryMappings: Property<MappingsFile>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun prepare() {
        val mappings = mappings.get()
        val mapping = if (mappings.format == "tiny") {
            val tiny = MemoryMappingTree().also { MappingReader.read(mappings.file.toPath(), it) }
            TinyReader(tiny, "named", "official").read()
        } else {
            val iMappings = namedToIntermediaryMappings.get()
            val iMapSet = readMappings(iMappings.format, iMappings.file.toPath())
            val oMapSet = readMappings(mappings.format, mappings.file.toPath())
            oMapSet.join(iMapSet.reverse()).reverse()
        }
        MappingFormats.SRG.write(mapping, output.get().asFile.toPath())
    }
}

private fun Project.bakeNamedToIntermediaryMappings(name: String, namedToIntermediaryMappings: Mappings, destination: File): TaskProvider<BakeNamedToIntermediaryMappings> {
    val task = tasks.register(name, BakeNamedToIntermediaryMappings::class)
    task.configure {
        dependsOn(namedToIntermediaryMappings.tasks)
        mappings.set(namedToIntermediaryMappings.toFile())
        output.set(destination)
    }
    return task
}

private fun Project.bakeNamedToOfficialMappings(name: String, mappings: Mappings, namedToIntermediaryMappings: Mappings, destination: File): TaskProvider<BakeNamedToOfficialMappings> {
    val task = tasks.register(name, BakeNamedToOfficialMappings::class)
    task.configure {
        dependsOn(mappings.tasks, namedToIntermediaryMappings.tasks)
        this.mappings.set(mappings.toFile())
        this.namedToIntermediaryMappings.set(namedToIntermediaryMappings.toFile())
        output.set(destination)
    }
    return task
}

private fun readMappings(format: String, path: Path): MappingSet {
    // special handling for TSRG2
    return if (format == "tsrg2") {
        // remove lines starting with "tsrg2" (header) and \t\t (method parameter / static indicator)
        val modifiedTSRG2 = path.toFile().readLines(StandardCharsets.UTF_8).stream().filter {
            !it.startsWith("tsrg2") && !it.startsWith("\t\t")
        }.collect(Collectors.joining("\n"))

        val inputStream = ByteArrayInputStream(modifiedTSRG2.toByteArray(StandardCharsets.UTF_8))

        // Proceed as the file would be normal TSRG:
        // This is fine because the the output of createMcpToSrg specifically only contains two names: `left` and `right`.
        // The other TSRG2 features, static indicators and parameter names, are irrelevant to our purpose and thus can be safely omitted as well.
        MappingFormats.byId("tsrg").createReader(inputStream).read()
    } else {
        MappingFormats.byId(format).read(path)
    }
}

private val Project.intermediaryMappings: Mappings
    get() {
        project.tasks.findByName("genSrgs")?.let { // FG2
            return Mappings("searge", it.property("mcpToSrg") as File, "srg", listOf(it))
        }
        project.tasks.findByName("createMcpToSrg")?.let { // FG3-5
            val output = it.property("output")
            // FG3+4 returns a File, FG5 a RegularFileProperty
            return if (output is File) {
                Mappings("searge", output, "tsrg", listOf(it))
            } else {
                Mappings("searge", (output as RegularFileProperty).get().asFile, "tsrg2", listOf(it))
            }
        }
        tinyMappingsWithSrg?.let { return Mappings("searge", it, "tiny", emptyList()) }
        tinyMappings?.let { Mappings("yarn", it, "tiny", emptyList()) }
        throw UnsupportedOperationException("Mapping between ForgeGradle and unobfuscated versions is not supported.")
    }

data class Mappings(val type: String, val file: File, val format: String, val tasks: List<Task>)

private val Project.notchMappings: Mappings?
    get() {
        project.tasks.findByName("genSrgs")?.let { // FG2
            return null // Unsupported
        }
        project.tasks.findByName("extractSrg")?.let { // FG3-5
            val output = it.property("output")
            // FG3+4 returns a File, FG5 a RegularFileProperty
            return if (output is File) {
                Mappings("notch", output, "tsrg", listOf(it))
            } else {
                Mappings("notch", (output as RegularFileProperty).get().asFile, "tsrg2", listOf(it))
            }
        }
        tinyMappings?.let { Mappings("notch", it, "tiny", emptyList()) }
        throw UnsupportedOperationException("Mapping between ForgeGradle and unobfuscated versions is not supported.")
    }

private val Project.mappingsProvider: Any?
    get() {
        val extension = extensions.findByName("loom") ?: extensions.findByName("minecraft")
            ?: throw UnsupportedLoom("Expected `loom` or `minecraft` extension")
        if (!extension.javaClass.name.contains("LoomGradleExtension")) {
            throw UnsupportedLoom("Unexpected extension class name: ${extension.javaClass.name}")
        }

        // Fabric Loom 1.13
        try {
            if (extension.javaClass.getMethod("disableObfuscation").invoke(extension) == true) {
                return null
            }
        } catch (_: NoSuchMethodException) {}

        listOf(
            "mappingConfiguration", // Fabric Loom 1.1+
            "mappingsProvider", // Fabric Loom pre 1.1
        ).forEach { pro ->
            extension.maybeGetGroovyProperty(pro)?.also { return it }
        }
        throw UnsupportedLoom("Failed to find mappings provider")
    }

private val Project.tinyMappings: File?
    get() {
        val mappingsProvider = mappingsProvider ?: return null
        mappingsProvider.maybeGetGroovyProperty("MAPPINGS_TINY")?.let { return it as File } // loom 0.2.5
        mappingsProvider.maybeGetGroovyProperty("tinyMappings")?.also {
            when (it) {
                is File -> return it // loom 0.2.6
                is Path -> return it.toFile() // loom 0.10.17
            }
        }
        throw UnsupportedLoom("Failed to find tiny mappings file")
    }

private val Project.tinyMappingsWithSrg: File?
    get() {
        mappingsProvider?.maybeGetGroovyProperty("tinyMappingsWithSrg")?.let { // architectury
            val file = (it as Path).toFile()
            if (file.exists()) {
                return file
            }
        }
        return null
    }

private val Project.mojangMappings: File?
    get() {
        val factory = LayeredMappingsFactory(LayeredMappingSpecBuilderImpl.buildOfficialMojangMappings())
        return factory.resolve(this).toFile()
    }

private class UnsupportedLoom(msg: String) : GradleException("Loom version not supported by preprocess plugin: $msg")

private fun Provider<Directory>.dir(path: String): Provider<Directory> =
    map { it.dir(path) }

private fun String.uppercaseFirstChar() = replaceFirstChar { it.uppercaseChar() }

private fun Any.maybeGetGroovyProperty(name: String) = withGroovyBuilder { metaClass }.hasProperty(this, name)?.getProperty(this)

private const val KOTLIN_COMPILER_EMBEDDABLE = "org.jetbrains.kotlin:kotlin-compiler-embeddable"
private const val KOTLIN_COMPILER_VERSION = "2.2.0"

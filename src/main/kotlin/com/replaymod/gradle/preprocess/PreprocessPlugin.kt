package com.replaymod.gradle.preprocess

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.api.mappings.layered.MappingContext
import net.fabricmc.loom.api.mappings.layered.MappingLayer
import net.fabricmc.loom.api.mappings.layered.spec.MappingsSpec
import net.fabricmc.mappingio.MappingVisitor
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.file.Directory
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.JavaCompile

import org.gradle.kotlin.dsl.*
import java.io.File
import java.nio.file.Path

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
        val adjacentNodes = buildList {
            graph.findParent(projectNode)?.first?.let { add(it) }
            projectNode.links.forEach { add(it.first) }
        }

        if (project.isObfuscated && !projectNode.isObfuscated) {
            throw IllegalStateException("Project appears to be obfuscated but configured preprocess node has `mappings` set to `null`")
        } else if (!project.isObfuscated && projectNode.isObfuscated) {
            throw IllegalStateException("Project appears to not be obfuscated but configured preprocess node has `mappings` set to a non-`null` value")
        }

        val coreProjectFile = project.file("../mainProject")
        val coreProject = coreProjectFile.readText().trim()
        if (coreProject !in parent.childProjects) throw IllegalStateException("Configured main project `$coreProject` does not exist.")
        if (graph.findNode(coreProject) == null) throw IllegalStateException("Preprocess graph does not contain main project `$coreProject`.")
        val mcVersion = projectNode.mcVersion
        project.extra["mcVersion"] = mcVersion
        val ext = project.extensions.create("preprocess", PreprocessExtension::class, project.objects, mcVersion)

        val kotlin = project.plugins.hasPlugin("kotlin")
        val remapClasspathConfiguration = setupRemapClasspath(project)

        project.the<SourceSetContainer>().configureEach {
            val compileClasspath = if (name == "main") "compileClasspath" else name + "CompileClasspath"
            project.configurations.consumable("preprocess-outgoing-$compileClasspath") {
                extendsFrom(project.configurations[compileClasspath])
            }
        }

        val projectMojangMappings = project.configurations.dependencyScope("preprocess-mojangMappings")
        if (projectNode.isObfuscated && adjacentNodes.any { !it.isObfuscated }) {
            project.dependencies {
                projectMojangMappings(project.extensions.getByType<LoomGradleExtensionAPI>().layered {
                    officialMojangMappings()
                    // Workaround for a Loom bug where all layered mappings with the same spec (which does not include
                    // the Minecraft version!) will have the same hash, and so the same maven coordinates / file system
                    // location, and consequently overwrite each other, making it impossible for us to get hold of the
                    // correct mappings.
                    // This bypasses that by modifying the hash of our layered mappings to be different per Minecraft
                    // version by adding a dummy layer, the hash of which is the Minecraft version.
                    class NoOpLayer : MappingLayer {
                        override fun visit(visitor: MappingVisitor) {}
                    }
                    addLayer(object : MappingsSpec<NoOpLayer> {
                        override fun createLayer(ctx: MappingContext): NoOpLayer = NoOpLayer()
                        override fun hashCode(): Int = projectNode.mcVersion
                    })
                })
            }
            project.configurations.consumable("preprocess-outgoing-mojangMappings") {
                extendsFrom(projectMojangMappings.get())
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

            fun incoming(name: String): NamedDomainObjectProvider<ResolvableConfiguration> {
                val dependencyScope = project.configurations.dependencyScope("preprocess-incoming-$name")
                project.dependencies {
                    dependencyScope(project(inherited.path, "preprocess-outgoing-$name"))
                }
                return project.configurations.resolvable("${dependencyScope.name}-resolver") {
                    extendsFrom(dependencyScope.get())
                }
            }

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
                val incomingCompileClasspathResolver = incoming(compileClasspath)

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
                    remapClasspath.from(remapClasspathConfiguration)
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

            val inheritedMojangMappings = incoming("mojangMappings")

            project.afterEvaluate {
                if ("genSrgs" in project.tasks.names || "createMcpToSrg" in project.tasks.names) {
                    throw UnsupportedOperationException("ForgeGradle is no longer supported. " +
                        "Use architectury-loom (or essential-loom for FG2) instead.")
                } else {
                    val projectSrgMappings = project.tinyMappingsWithSrg
                    val inheritedSrgMappings = inherited.tinyMappingsWithSrg
                    val projectTinyMappings = project.tinyMappings
                    val inheritedTinyMappings = inherited.tinyMappings
                    val generateSourceMappingsTask = tasks.register("generateIdentityMappingsFromSourceMinecraftJars", GenerateIdentityMappingsFromMinecraftJars::class) {
                        minecraftJars.from(inherited.extensions.getByType<LoomGradleExtensionAPI>().namedMinecraftJars)
                        output.set(project.layout.buildDirectory.get().asFile.resolve("generatedSourceIdentityMappings.tiny"))
                    }
                    val generateDestinationMappingsTask = tasks.register("generateIdentityMappingsFromDestinationMinecraftJars", GenerateIdentityMappingsFromMinecraftJars::class) {
                        minecraftJars.from(project.extensions.getByType<LoomGradleExtensionAPI>().namedMinecraftJars)
                        output.set(project.layout.buildDirectory.get().asFile.resolve("generatedDestinationIdentityMappings.tiny"))
                    }
                    val mergeSourceMappingsTask = tasks.register("mergeSourceNamedAndMojangMappings", MergeNamedAndMojangMappingsTask::class) {
                        namedMappings.set { inheritedTinyMappings!! }
                        mojangMappings.fileProvider(inheritedMojangMappings.flatMap { it.elements }.map { it.single().asFile })
                        output.set(project.layout.buildDirectory.get().asFile.resolve("mergedSourceNamedAndMojangMappings.tiny"))
                    }
                    val mergeDestinationMappingsTask = tasks.register("mergeDestinationNamedAndMojangMappings", MergeNamedAndMojangMappingsTask::class) {
                        namedMappings.set { projectTinyMappings!! }
                        mojangMappings.fileProvider(projectMojangMappings.flatMap { it.elements }.map { it.single().asFile })
                        output.set(project.layout.buildDirectory.get().asFile.resolve("mergedDestinationNamedAndMojangMappings.tiny"))
                    }
                    tasks.withType<PreprocessTask>().configureEach {
                        if (projectTinyMappings == null && inheritedTinyMappings == null) {
                            // Between two unobfuscated versions
                            dependsOn(generateSourceMappingsTask, generateDestinationMappingsTask)
                            sourceMappings = generateSourceMappingsTask.get().output.get().asFile
                            destinationMappings = generateDestinationMappingsTask.get().output.get().asFile
                            intermediateMappingsName.set("mojang")
                        } else if (projectTinyMappings == null) {
                            // We have source mappings, but target is unobfuscated
                            dependsOn(mergeSourceMappingsTask, generateDestinationMappingsTask)
                            sourceMappings = mergeSourceMappingsTask.get().output.get().asFile
                            destinationMappings = generateDestinationMappingsTask.get().output.get().asFile
                            intermediateMappingsName.set("mojang")
                        } else if (inheritedTinyMappings == null) {
                            // We have target mappings, but source is unobfuscated
                            dependsOn(generateSourceMappingsTask, mergeDestinationMappingsTask)
                            sourceMappings = generateSourceMappingsTask.get().output.get().asFile
                            destinationMappings = mergeDestinationMappingsTask.get().output.get().asFile
                            intermediateMappingsName.set("mojang")
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
                    }

                    @Suppress("DEPRECATION")
                    if (rootExtension.strictExtraMappings.isPresent && !rootExtension.strictExtraMappings.get()) {
                        throw UnsupportedOperationException("Non-strict extra mappings are no longer supported.")
                    }
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

    private fun setupRemapClasspath(project: Project): NamedDomainObjectProvider<ResolvableConfiguration> {
        val remapDependencyScope = project.configurations.dependencyScope("preprocess-remap")
        project.dependencies {
            // Note: Version needs to be kept in-sync with the one in `build.gradle.kts`
            remapDependencyScope("com.github.replaymod:remap:b9800c7c2403c3d1e9887d4df1c159e2b98b7322")
        }
        return project.configurations.resolvable("preprocess-remap-resolver") {
            extendsFrom(remapDependencyScope.get())
        }
    }
}

private val Project.mappingsProvider: Any?
    get() {
        val extension = extensions.findByName("loom") ?: extensions.findByName("minecraft")
            ?: throw UnsupportedLoom("Expected `loom` or `minecraft` extension")
        if (!extension.javaClass.name.contains("LoomGradleExtension")) {
            throw UnsupportedLoom("Unexpected extension class name: ${extension.javaClass.name}")
        }

        // Fabric Loom 1.13
        if (!isObfuscated) return null

        listOf(
            "mappingConfiguration", // Fabric Loom 1.1+
            "mappingsProvider", // Fabric Loom pre 1.1
        ).forEach { pro ->
            extension.maybeGetGroovyProperty(pro)?.also { return it }
        }
        throw UnsupportedLoom("Failed to find mappings provider")
    }

private val Project.isObfuscated: Boolean
    get() {
        val extension = extensions.findByName("loom") ?: return true
        return try {
            extension.javaClass.getMethod("disableObfuscation").invoke(extension) == false
        } catch (_: NoSuchMethodException) {
            true
        }
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

private class UnsupportedLoom(msg: String) : GradleException("Loom version not supported by preprocess plugin: $msg")

private fun Provider<Directory>.dir(path: String): Provider<Directory> =
    map { it.dir(path) }

private fun String.uppercaseFirstChar() = replaceFirstChar { it.uppercaseChar() }

private fun Any.maybeGetGroovyProperty(name: String) = withGroovyBuilder { metaClass }.hasProperty(this, name)?.getProperty(this)

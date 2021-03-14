package com.replaymod.gradle.preprocess

import net.fabricmc.mapping.tree.TinyMappingFactory
import org.cadixdev.lorenz.io.MappingFormats
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile

import org.gradle.kotlin.dsl.*
import java.io.File
import java.nio.file.Path

class PreprocessPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val parent = project.parent
        if (parent == null) {
            project.extensions.create("preprocess", RootPreprocessExtension::class)
            return
        }

        project.evaluationDependsOn(parent.path)
        val rootExtension = parent.extensions.getByType<RootPreprocessExtension>()
        val graph = rootExtension.rootNode ?: throw IllegalStateException("Preprocess graph was not configured.")
        val projectNode = graph.findNode(project.name) ?: throw IllegalStateException("Prepocess graph does not contain ${project.name}.")

        val coreProjectFile = project.file("../mainProject")
        val coreProject = coreProjectFile.readText().trim()
        val mcVersion = projectNode.mcVersion
        project.extra["mcVersion"] = mcVersion
        val ext = project.extensions.create("preprocess", PreprocessExtension::class, project.objects, mcVersion)

        val kotlin = project.plugins.hasPlugin("kotlin")

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
            val reverseMappings = inheritedLink != null
            val (inheritedNode, mappingFile) = inheritedLink ?: graph.findParent(projectNode)!!
            val inherited = parent.evaluationDependsOn(inheritedNode.project)

            project.the<SourceSetContainer>().configureEach {
                val inheritedSourceSet = inherited.the<SourceSetContainer>()[name]
                val cName = if (name == "main") "" else name.capitalize()
                val overwritesKotlin = project.file("src/$name/kotlin").also { it.mkdirs() }
                val overwritesJava = project.file("src/$name/java").also { it.mkdirs() }
                val overwriteResources = project.file("src/$name/resources").also { it.mkdirs() }
                val preprocessedKotlin = File(project.buildDir, "preprocessed/$name/kotlin")
                val preprocessedJava = File(project.buildDir, "preprocessed/$name/java")
                val preprocessedResources = File(project.buildDir, "preprocessed/$name/resources")

                val preprocessJava = project.tasks.register<PreprocessTask>("preprocess${cName}Java") {
                    inherited.tasks.findByPath("preprocess${cName}Java")?.let { dependsOn(it) }
                    source = inherited.files(inheritedSourceSet.java.srcDirs)
                    overwrites = overwritesJava
                    generated = preprocessedJava
                    compileTask(inherited.tasks["compile${cName}Java"] as AbstractCompile)
                    if (kotlin) {
                        compileTask(inherited.tasks["compile${cName}Kotlin"] as AbstractCompile)
                    }
                    mapping = mappingFile
                    reverseMapping = reverseMappings
                    vars.convention(ext.vars)
                    keywords.convention(ext.keywords)
                    patternAnnotation.convention(ext.patternAnnotation)
                }
                val sourceJavaTask = project.tasks.findByName("source${name.capitalize()}Java")
                (sourceJavaTask ?: project.tasks["compile${cName}Java"]).dependsOn(preprocessJava)
                java.setSrcDirs(listOf(overwritesJava, preprocessedJava))

                if (kotlin) {
                    val preprocessKotlin = project.tasks.register<PreprocessTask>("preprocess${cName}Kotlin") {
                        inherited.tasks.findByPath("preprocess${cName}Kotlin")?.let { dependsOn(it) }
                        source = inherited.files(inheritedSourceSet.withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.srcDirs.filter { it.endsWith("kotlin") })
                        overwrites = overwritesKotlin
                        generated = preprocessedKotlin
                        compileTask(inherited.tasks["compile${cName}Kotlin"] as AbstractCompile)
                        mapping = mappingFile
                        reverseMapping = reverseMappings
                        vars.convention(ext.vars)
                        keywords.convention(ext.keywords)
                        patternAnnotation.convention(ext.patternAnnotation)
                    }
                    val kotlinConsumerTask = project.tasks.findByName("source${name.capitalize()}Kotlin")
                            ?: project.tasks["compile${cName}Kotlin"]
                    kotlinConsumerTask.dependsOn(preprocessKotlin)
                    kotlinConsumerTask.dependsOn(preprocessJava)
                    withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.setSrcDirs(
                            listOf(overwritesKotlin, preprocessedKotlin, overwritesJava, preprocessedJava))
                }

                val preprocessResources = project.tasks.register<PreprocessTask>("preprocess${cName}Resources") {
                    inherited.tasks.findByPath("preprocess${cName}Resources")?.let { dependsOn(it) }
                    source = inherited.files(inheritedSourceSet.resources.srcDirs)
                    overwrites = overwriteResources
                    generated = preprocessedResources
                    vars.convention(ext.vars)
                    keywords.convention(ext.keywords)
                    patternAnnotation.convention(ext.patternAnnotation)
                }
                project.tasks["process${cName}Resources"].dependsOn(preprocessResources)
                resources.setSrcDirs(listOf(overwriteResources, preprocessedResources))
            }

            project.afterEvaluate {
                val prepareTaskName = "prepareMappingsForPreprocessor"
                val projectIntermediaryMappings = project.intermediaryMappings
                val inheritedIntermediaryMappings = inherited.intermediaryMappings
                val projectNotchMappings = project.notchMappings
                val inheritedNotchMappings = inherited.notchMappings
                val sourceSrg = project.buildDir.resolve(prepareTaskName).resolve("source.srg")
                val destinationSrg = project.buildDir.resolve(prepareTaskName).resolve("destination.srg")
                val prepareTask = if (inheritedIntermediaryMappings != null && projectIntermediaryMappings != null
                        && inheritedIntermediaryMappings.type == projectIntermediaryMappings.type) {
                    tasks.register(prepareTaskName) {
                        bakeNamedToIntermediaryMappings(inheritedIntermediaryMappings, sourceSrg)
                        bakeNamedToIntermediaryMappings(projectIntermediaryMappings, destinationSrg)
                    }
                } else if (inheritedNotchMappings != null && projectNotchMappings != null
                        && inheritedNode.mcVersion == projectNode.mcVersion) {
                    tasks.register(prepareTaskName) {
                        bakeNamedToOfficialMappings(inheritedNotchMappings, inheritedIntermediaryMappings, sourceSrg)
                        bakeNamedToOfficialMappings(projectNotchMappings, projectIntermediaryMappings, destinationSrg)
                    }
                } else {
                    throw IllegalStateException("Failed to find mappings from $inherited to $project.")
                }
                tasks.withType<PreprocessTask>().configureEach {
                    sourceMappings = sourceSrg
                    destinationMappings = destinationSrg
                    dependsOn(prepareTask)
                }
            }

            project.tasks.register<Copy>("setCoreVersion") {
                outputs.upToDateWhen { false }

                from(project.file("src"))
                from(File(project.buildDir, "preprocessed"))
                into(File(parent.projectDir, "src"))

                project.the<SourceSetContainer>().all {
                    val cName = if (name == "main") "" else name.capitalize()

                    if (kotlin) {
                        dependsOn(project.tasks.named("preprocess${cName}Kotlin"))
                    }
                    dependsOn(project.tasks.named("preprocess${cName}Java"))
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
                                project.buildDir.toPath().resolve("preprocessed")
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
}

private fun Task.bakeNamedToIntermediaryMappings(mappings: Mappings, destination: File) {
    mappings.tasks.forEach { this.dependsOn(it) }
    inputs.file(mappings.file)
    outputs.file(destination)
    doLast {
        val mapping = if (mappings.format == "tiny") {
            val tiny = mappings.file.inputStream().use { TinyMappingFactory.loadWithDetection(it.bufferedReader()) }
            TinyReader(tiny, "named", "intermediary").read()
        } else {
            MappingFormats.byId(mappings.format).read(mappings.file.toPath())
        }
        MappingFormats.SRG.write(mapping, destination.toPath())
    }
}

private fun Task.bakeNamedToOfficialMappings(mappings: Mappings, namedToIntermediaryMappings: Mappings?, destination: File) {
    mappings.tasks.forEach { this.dependsOn(it) }
    namedToIntermediaryMappings?.tasks?.forEach { dependsOn(it) }
    inputs.file(mappings.file)
    namedToIntermediaryMappings?.let { inputs.file(it.file) }
    outputs.file(destination)
    doLast {
        val mapping = if (mappings.format == "tiny") {
            val tiny = mappings.file.inputStream().use { TinyMappingFactory.loadWithDetection(it.bufferedReader()) }
            TinyReader(tiny, "named", "official").read()
        } else {
            val iMappings = namedToIntermediaryMappings!!
            val iMapSet = MappingFormats.byId(iMappings.format).read(iMappings.file.toPath())
            val oMapSet = MappingFormats.byId(mappings.format).read(mappings.file.toPath())
            oMapSet.join(iMapSet.reverse()).reverse()
        }
        MappingFormats.SRG.write(mapping, destination.toPath())
    }
}

private val Project.intermediaryMappings: Mappings?
    get() {
        project.tasks.findByName("genSrgs")?.let { // FG2
            return Mappings("searge", it.property("mcpToSrg") as File, "srg", listOf(it))
        }
        project.tasks.findByName("createMcpToSrg")?.let { // FG3
            return Mappings("searge", it.property("output") as File, "tsrg", listOf(it))
        }
        tinyMappings?.let { return Mappings("yarn", it, "tiny", emptyList()) }
        return null
    }

data class Mappings(val type: String, val file: File, val format: String, val tasks: List<Task>)

private val Project.notchMappings: Mappings?
    get() {
        project.tasks.findByName("extractSrg")?.let { // FG3
            return Mappings("notch", it.property("output") as File, "tsrg", listOf(it))
        }
        tinyMappings?.let { return Mappings("notch", it, "tiny", emptyList()) }
        return null
    }

private val Project.tinyMappings: File?
    get() {
        val extension = extensions.findByName("minecraft") ?: return null
        if (!extension.javaClass.name.contains("LoomGradleExtension")) return null
        val mappingsProvider = extension.withGroovyBuilder { getProperty("mappingsProvider") }
        mappingsProvider.maybeGetGroovyProperty("MAPPINGS_TINY")?.let { return it as File } // loom 0.2.5
        mappingsProvider.maybeGetGroovyProperty("tinyMappings")?.let { return it as File } // loom 0.2.6
        throw GradleException("loom version not supported by preprocess plugin")
    }

private fun Any.maybeGetGroovyProperty(name: String) = withGroovyBuilder { metaClass }.hasProperty(this, name)?.getProperty(this)

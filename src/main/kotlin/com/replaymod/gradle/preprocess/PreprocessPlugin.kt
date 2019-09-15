package com.replaymod.gradle.preprocess

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile

import org.gradle.kotlin.dsl.*
import java.io.File
import java.util.*

class PreprocessPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val kotlin = project.plugins.hasPlugin("kotlin")

        val coreVersionFile = project.file("../mainVersion")
        val mappingFiles = findMappingFiles(project)

        val parent = project.parent!!
        val coreVersion = coreVersionFile.readText().toInt()
        val mcVersion = project.getMcVersion()
        project.extra["mcVersion"] = mcVersion
        if (coreVersion == mcVersion) {
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
            val core = project.byVersion(coreVersion)
            val mappingFile: File?
            val inherited: Project?
            if (coreVersion < mcVersion) {
                // Upgrading older core to newer version
                // Grab the next mapping at or below our version
                // e.g. if we're on 1.13.2, that'll be 11302 which maps 1.13.2 to 1.12.2
                val entry = mappingFiles.floorEntry(mcVersion)
                if (entry == null || entry.key <= coreVersion) {
                    inherited = core
                    mappingFile = null
                } else {
                    mappingFile = entry.value
                    // Inherit from the version directly below the mapping we"re using
                    val inheritedVersion = mappingFiles.lowerKey(entry.key)
                    inherited = inheritedVersion?.let { project.byVersion(it) } ?: core
                }
            } else {
                // Dowgrading newer core to older versions
                // Grab the next mapping on our way to the newer core version (i.e. the one right above our version)
                val entry = mappingFiles.higherEntry(mcVersion)
                if (entry == null || entry.key > coreVersion) {
                    inherited = core
                    mappingFile = null
                } else {
                    mappingFile = entry.value
                    // Inherit from the version which the mapping belongs to
                    // e.g. if we're on 1.12.2 then the mapping maps 1.13.2 to 1.12.2 and will be labeled 11302
                    inherited = project.byVersion(entry.key)
                }
            }

            project.the<SourceSetContainer>().configureEach {
                val inheritedSourceSet = inherited.the<SourceSetContainer>()[name]
                val cName = if (name == "main") "" else name.capitalize()
                val preprocessedKotlin = File(project.buildDir, "preprocessed/$name/kotlin")
                val preprocessedJava = File(project.buildDir, "preprocessed/$name/java")
                val preprocessedResources = File(project.buildDir, "preprocessed/$name/resources")

                if (kotlin) {
                    val preprocessKotlin = project.tasks.register<PreprocessTask>("preprocess${cName}Kotlin") {
                        source = inherited.file(inheritedSourceSet.withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.srcDirs.first())
                        generated = preprocessedKotlin
                        compileTask(inherited.tasks["compile${cName}Kotlin"] as AbstractCompile)
                        mapping = mappingFile
                        reverseMapping = coreVersion < mcVersion
                        vars = mutableMapOf("MC" to mcVersion)
                    }
                    val sourceKotlinTask = project.tasks.findByName("source${name.capitalize()}Kotlin")
                    (sourceKotlinTask ?: project.tasks["compile${cName}Kotlin"]).dependsOn(preprocessKotlin)
                    withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.setSrcDirs(listOf(preprocessKotlin, preprocessedJava))
                }

                val preprocessJava = project.tasks.register<PreprocessTask>("preprocess${cName}Java") {
                    source = inherited.file(inheritedSourceSet.java.srcDirs.first())
                    generated = preprocessedJava
                    compileTask(inherited.tasks["compile${cName}Java"] as AbstractCompile)
                    if (kotlin) {
                        compileTask(inherited.tasks["compile${cName}Kotlin"] as AbstractCompile)
                    }
                    mapping = mappingFile
                    reverseMapping = coreVersion < mcVersion
                    vars = mutableMapOf("MC" to mcVersion)
                }
                val sourceJavaTask = project.tasks.findByName("source${name.capitalize()}Java")
                (sourceJavaTask ?: project.tasks["compile${cName}Java"]).dependsOn(preprocessJava)
                java.setSrcDirs(listOf(preprocessedJava))

                val preprocessResources = project.tasks.register<PreprocessTask>("preprocess${cName}Resources") {
                    source = inherited.file(inheritedSourceSet.resources.srcDirs.first())
                    generated = preprocessedResources
                    vars = mutableMapOf("MC" to mcVersion)
                }
                project.tasks["process${cName}Resources"].dependsOn(preprocessResources)
                resources.setSrcDirs(listOf(preprocessedResources))
            }

            project.afterEvaluate {
                val projectIntermediaryMappings = project.intermediaryMappings
                val inheritedIntermediaryMappings = inherited.intermediaryMappings
                if (inheritedIntermediaryMappings != null && projectIntermediaryMappings != null) {
                    tasks.withType<PreprocessTask>().configureEach {
                        sourceMappings = inheritedIntermediaryMappings.first
                        destinationMappings = projectIntermediaryMappings.first
                        (inheritedIntermediaryMappings.second + projectIntermediaryMappings.second).forEach { dependsOn(it) }
                    }
                }
            }

            project.tasks.register<Copy>("setCoreVersion") {
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

                doLast {
                    coreVersionFile.writeText(mcVersion.toString())
                }
            }
        }
    }
}

private fun Project.getMcVersion(): Int = (name.split(".") + listOf("")).let { (major, minor, patch) ->
    "$major${minor.padStart(2, '0')}${patch.padStart(2, '0')}".toInt()
}

private fun Project.byVersion(version: Int): Project {
    val name = "${version/10000}.${version/100 % 100}${if (version%100 == 0) "" else ".${version%100}"}"
    return project.parent!!.evaluationDependsOn(name)
}

private fun findMappingFiles(project: Project): NavigableMap<Int, File> =
        project.file("../").listFiles()!!.mapNotNull {
            val mappingFile = File(it, "mapping.txt")
            if (mappingFile.exists()) {
                val (major, minor, patch) = it.name.split(".") + listOf(null)
                val version = "$major${minor?.padStart(2, '0')}${(patch ?: "").padStart(2, '0')}"
                Pair(version.toInt(), mappingFile)
            } else {
                null
            }
        }.toMap(TreeMap())


private val Project.intermediaryMappings: Pair<File, List<Task>>?
    get() {
        project.tasks.findByName("genSrgs")?.let { // FG2
            return Pair(it.property("mcpToSrg") as File, listOf(it))
        }
        project.tasks.findByName("createMcpToSrg")?.let { // FG3
            return Pair(it.property("output") as File, listOf(it))
        }
        return null
    }

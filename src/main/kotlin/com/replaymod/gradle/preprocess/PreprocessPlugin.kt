package com.replaymod.gradle.preprocess

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile

import org.gradle.kotlin.dsl.*
import java.io.File
import java.util.*

class PreprocessPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val originalSrc = "../../src/main/java"
        val originalRes = "../../src/main/resources"
        val preprocessedSrc = "build/preprocessed/src"
        val preprocessedRes = "build/preprocessed/res"
        val mappingFiles = findMappingFiles(project)

        if (project.name == "core") {
            project.configure<SourceSetContainer> {
                named("main") {
                    java.setSrcDirs(listOf(originalSrc))
                    resources.setSrcDirs(listOf(originalRes))
                }
            }
        } else {
            val mcVersion = project.extra["mcVersion"] as Int
            val core = project.parent!!.evaluationDependsOn("core")
            val coreVersion = core.extra["mcVersion"] as Int
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

            val preprocessJava = project.tasks.create<PreprocessTask>("preprocessJava") {
                source = project.file(inherited.the<SourceSetContainer>().getByName("main").java.srcDirs.first())
                generated = project.file(preprocessedSrc)
                compileTask(inherited.tasks.getByName("compileJava") as AbstractCompile)
                project.afterEvaluate {
                    val projectIntermediaryMappings = project.intermediaryMappings
                    val inheritedIntermediaryMappings = inherited.intermediaryMappings
                    if (inheritedIntermediaryMappings != null && projectIntermediaryMappings != null) {
                        sourceMappings = inheritedIntermediaryMappings.first
                        destinationMappings = projectIntermediaryMappings.first
                        (inheritedIntermediaryMappings.second + projectIntermediaryMappings.second).forEach { dependsOn(it) }
                    }
                }
                mapping = mappingFile
                reverseMapping = coreVersion < mcVersion
                vars = mutableMapOf("MC" to mcVersion)
            }

            val preprocessResources = project.tasks.create<PreprocessTask>("preprocessResources") {
                source = project.file(originalRes)
                generated = project.file(preprocessedRes)
                vars = mutableMapOf("MC" to mcVersion)
            }

            val sourceMainJava = project.tasks.findByName("sourceMainJava")
            (sourceMainJava ?: project.tasks.getByName("compileJava")).dependsOn(preprocessJava)
            project.tasks.getByName("processResources").dependsOn(preprocessResources)

            project.configure<SourceSetContainer> {
                named("main") {
                    java.setSrcDirs(listOf(preprocessedSrc))
                    resources.setSrcDirs(listOf(preprocessedRes))
                }
            }

            val setCoreVersionJava = project.tasks.create<PreprocessTask>("setCoreVersionJava") {
                dependsOn(preprocessJava)
                source = project.file(preprocessedSrc)
                generated = project.file(originalSrc)
                vars = mutableMapOf("MC" to mcVersion, "DEV_ENV" to 1)
            }

            val setCoreVersionResources = project.tasks.create<PreprocessTask>("setCoreVersionResources") {
                inplace(originalRes)
                vars = mutableMapOf("MC" to mcVersion, "DEV_ENV" to 1)
            }

            project.tasks.create("setCoreVersion") {
                dependsOn(setCoreVersionJava)
                dependsOn(setCoreVersionResources)

                doLast {
                    project.file("../core/mcVersion").writeText(mcVersion.toString())
                }
            }
        }
    }
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

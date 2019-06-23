/* Copyright (C) 2019 Jonas Herzig <me@johni0702.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.replaymod.gradle.preprocess

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

class PreprocessPlugin implements Plugin<Project> {
    void apply(Project project) {
        def originalSrc = '../../src/main/java'
        def originalRes = '../../src/main/resources'
        def preprocessedSrc = 'build/preprocessed/src'
        def preprocessedRes = 'build/preprocessed/res'
        def mappingFiles = findMappingFiles(project)

        if (project.name == 'core') {
            project.sourceSets {
                main.java.srcDirs = [originalSrc]
                main.resources.srcDirs = [originalRes]
            }
        } else {
            def mcVersion = project.mcVersion as int
            def core = project.parent.evaluationDependsOn('core')
            def coreVersion = core.mcVersion as int
            def mappingFile
            def inherited
            if (coreVersion < mcVersion) {
                // Upgrading older core to newer version
                // Grab the next mapping at or below our version
                // e.g. if we're on 1.13.2, that'll be 11302 which maps 1.13.2 to 1.12.2
                def entry = mappingFiles.floorEntry(mcVersion)
                if (entry == null || entry.key <= coreVersion) {
                    inherited = core
                    mappingFile = null
                } else {
                    mappingFile = entry.value
                    // Inherit from the version directly below the mapping we're using
                    def inheritedVersion = mappingFiles.lowerKey(entry.key)
                    if (inheritedVersion == null) {
                        inherited = core
                    } else {
                        inherited = projectForVersion(project, inheritedVersion)
                    }
                }
            } else {
                // Dowgrading newer core to older versions
                // Grab the next mapping on our way to the newer core version (i.e. the one right above our version)
                def entry = mappingFiles.higherEntry(mcVersion)
                if (entry == null || entry.key > coreVersion) {
                    inherited = core
                    mappingFile = null
                } else {
                    mappingFile = entry.value
                    // Inherit from the version which the mapping belongs to
                    // e.g. if we're on 1.12.2 then the mapping maps 1.13.2 to 1.12.2 and will be labeled 11302
                    inherited = projectForVersion(project, entry.key)
                }
            }

            def preprocessJava = project.tasks.create('preprocessJava', PreprocessTask) {
                source inherited.sourceSets.main.java.srcDirs[0]
                generated preprocessedSrc
                compileTask inherited.tasks.compileJava
                project.afterEvaluate {
                    def projectIntermediaryMappings = getIntermediaryMappings(project)
                    def inheritedIntermediaryMappings = getIntermediaryMappings(inherited)
                    if (inheritedIntermediaryMappings != null && projectIntermediaryMappings != null) {
                        sourceMappings = inheritedIntermediaryMappings.first
                        destinationMappings = projectIntermediaryMappings.first
                        (inheritedIntermediaryMappings.second + projectIntermediaryMappings.second).each { dependsOn it }
                    }
                }
                mapping = mappingFile
                reverseMapping = coreVersion < mcVersion
                var MC: mcVersion
            }

            def preprocessResources = project.tasks.create('preprocessResources', PreprocessTask) {
                source originalRes
                generated preprocessedRes
                var MC: mcVersion
            }

            def sourceMainJava = project.tasks.findByName('sourceMainJava')
            (sourceMainJava ?: project.tasks.compileJava).dependsOn preprocessJava
            project.tasks.processResources.dependsOn preprocessResources

            project.sourceSets {
                main.java.srcDirs = [preprocessedSrc]
                main.resources.srcDirs = [preprocessedRes]
            }

            def setCoreVersionJava = project.tasks.create('setCoreVersionJava', PreprocessTask) {
                dependsOn preprocessJava
                source preprocessedSrc
                generated originalSrc
                var DEV_ENV: 1
                var MC: mcVersion
            }

            def setCoreVersionResources = project.tasks.create('setCoreVersionResources', PreprocessTask) {
                inplace originalRes
                var DEV_ENV: 1
                var MC: mcVersion
            }

            project.tasks.create('setCoreVersion') {
                dependsOn setCoreVersionJava
                dependsOn setCoreVersionResources

                doLast {
                    project.file('../core/mcVersion').write(mcVersion.toString())
                }
            }
        }
    }

    static projectForVersion(Project project, int version) {
        def name = "${(int)(version/10000)}.${(int)(version/100)%100}" + (version%100==0 ? '' : ".${version%100}")
        println(name)
        project.parent.evaluationDependsOn(name)
    }

    static NavigableMap<Integer, File> findMappingFiles(Project project) {
        def mappings = new TreeMap<Integer, File>()
        project.file('../').listFiles().each {
            def mappingFile = new File(it, "mapping.txt")
            if (mappingFile.exists()) {
                def (major, minor, patch) = it.name.tokenize('.')
                def version = "${major}${minor.padLeft(2, '0')}${(patch ?: '').padLeft(2, '0')}" as int
                mappings.put(version, mappingFile)
            }
        }
        mappings
    }

    static Tuple2<File, List<Task>> getIntermediaryMappings(Project project) {
        def genSrgsTask = project.tasks.findByName('genSrgs') // FG2
        def createMcpToSrgTask = project.tasks.findByName('createMcpToSrg') // FG3
        if (genSrgsTask != null) {
            return new Tuple2(genSrgsTask.mcpToSrg, [genSrgsTask])
        } else if (createMcpToSrgTask != null) {
            return new Tuple2(createMcpToSrgTask.output, [createMcpToSrgTask])
        } else {
            return null
        }
    }
}

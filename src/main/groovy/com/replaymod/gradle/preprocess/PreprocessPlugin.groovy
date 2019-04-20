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

class PreprocessPlugin implements Plugin<Project> {
    void apply(Project project) {
        def originalSrc = '../../src/main/java'
        def originalRes = '../../src/main/resources'
        def preprocessedSrc = 'build/preprocessed/src'
        def preprocessedRes = 'build/preprocessed/res'

        if (project.name == 'core') {
            project.sourceSets {
                main.java.srcDirs = [originalSrc]
                main.resources.srcDirs = [originalRes]
            }
        } else {
            def preprocessJava = project.tasks.create('preprocessJava', PreprocessTask) {
                source originalSrc
                generated preprocessedSrc
                var MC: project.mcVersion
            }

            def preprocessResources = project.tasks.create('preprocessResources', PreprocessTask) {
                source originalRes
                generated preprocessedRes
                var MC: project.mcVersion
            }

            project.tasks.compileJava.dependsOn preprocessJava
            project.tasks.processResources.dependsOn preprocessResources

            project.sourceSets {
                main.java.srcDir preprocessedSrc
                main.resources.srcDir preprocessedRes
            }

            def setCoreVersionJava = project.tasks.create('setCoreVersionJava', PreprocessTask) {
                inplace originalSrc
                var DEV_ENV: 1
                var MC: project.mcVersion
            }

            def setCoreVersionResources = project.tasks.create('setCoreVersionResources', PreprocessTask) {
                inplace originalRes
                var DEV_ENV: 1
                var MC: project.mcVersion
            }

            project.tasks.create('setCoreVersion') {
                dependsOn setCoreVersionJava
                dependsOn setCoreVersionResources

                doLast {
                    project.file('../core/mcVersion').write(project.mcVersion.toString())
                }
            }
        }
    }
}

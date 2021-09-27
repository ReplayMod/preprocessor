package com.replaymod.gradle.preprocess

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

class RootPreprocessPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("preprocess", RootPreprocessExtension::class)
    }
}

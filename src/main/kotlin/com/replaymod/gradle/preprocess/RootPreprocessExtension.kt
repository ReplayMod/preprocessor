package com.replaymod.gradle.preprocess

import java.io.File

open class RootPreprocessExtension : ProjectGraphNodeDSL {
    var rootNode: ProjectGraphNode? = null

    override fun addNode(project: String, mcVersion: Int, mappings: String, extraMappings: File?, invertMappings: Boolean): ProjectGraphNode {
        check(rootNode == null) { "Only one root node may be set." }
        check(extraMappings == null) { "Cannot add extra mappings to root node." }
        return ProjectGraphNode(project, mcVersion, mappings).also { rootNode = it }
    }
}

interface ProjectGraphNodeDSL {
    operator fun String.invoke(mcVersion: Int, mappings: String, extraMappings: File? = null, configure: ProjectGraphNodeDSL.() -> Unit = {}) {
        addNode(this, mcVersion, mappings, extraMappings).configure()
    }

    fun addNode(project: String, mcVersion: Int, mappings: String, extraMappings: File? = null, invertMappings: Boolean = false): ProjectGraphNodeDSL
}

open class ProjectGraphNode(
        val project: String,
        val mcVersion: Int,
        val mappings: String,
        val links: MutableList<Pair<ProjectGraphNode, Pair<File?, Boolean>>> = mutableListOf()
) : ProjectGraphNodeDSL {
    override fun addNode(project: String, mcVersion: Int, mappings: String, extraMappings: File?, invertMappings: Boolean): ProjectGraphNodeDSL =
            ProjectGraphNode(project, mcVersion, mappings).also { links.add(Pair(it, Pair(extraMappings, invertMappings))) }

    fun findNode(project: String): ProjectGraphNode? = if (project == this.project) {
        this
    } else {
        links.map { it.first.findNode(project) }.find { it != null }
    }

    fun findParent(node: ProjectGraphNode): Pair<ProjectGraphNode, Pair<File?, Boolean>>? = if (node == this) {
        null
    } else {
        links.map { (child, extraMappings) ->
            if (child == node) {
                Pair(this, extraMappings)
            } else {
                child.findParent(node)
            }
        }.find { it != null }
    }
}

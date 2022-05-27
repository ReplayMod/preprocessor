package com.replaymod.gradle.preprocess

import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property

open class PreprocessExtension(objects: ObjectFactory, val mcVersion: Int) {
    val vars = objects.mapProperty<String, Int>().convention(mutableMapOf(
            "MC" to mcVersion
    ))
    val keywords = objects.mapProperty<String, Keywords>().convention(mutableMapOf(
            ".java" to PreprocessTask.DEFAULT_KEYWORDS,
            ".kt" to PreprocessTask.DEFAULT_KEYWORDS,
            ".gradle" to PreprocessTask.DEFAULT_KEYWORDS,
            ".json" to PreprocessTask.DEFAULT_KEYWORDS,
            ".mcmeta" to PreprocessTask.DEFAULT_KEYWORDS,
            ".cfg" to PreprocessTask.CFG_KEYWORDS
    ))
    val patternAnnotation = objects.property<String>()
    val manageImports = objects.property<Boolean>()
}
package com.replaymod.gradle.preprocess

import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.model.*
import java.io.File

fun File.readMappings(): MappingSet {
    val ext = name.substring(name.lastIndexOf(".") + 1)
    val format = MappingFormats.REGISTRY.values().find { it.standardFileExtension.orElse(null) == ext }
            ?: throw UnsupportedOperationException("Cannot find mapping format for $this")
    return format.read(toPath())
}

// SRG doesn't track class names, so we need to inject our manual mappings which do contain them
// However, our manual mappings also contain certain manual mappings in MCP names which need to be
// applied before transitioning to SRG names (i.e. not where class mappings need to be applied).
// As such, we need to split our class mappings off from the rest of the manual mappings.
fun MappingSet.splitOffClassMappings(): MappingSet {
    val clsMap = MappingSet.create()
    for (cls in topLevelClassMappings) {
        val clsOnly = clsMap.getOrCreateTopLevelClassMapping(cls.obfuscatedName)
        clsOnly.deobfuscatedName = cls.deobfuscatedName
        cls.deobfuscatedName = cls.obfuscatedName
        for (inner in cls.innerClassMappings) {
            inner.splitOffInnerClassMappings(clsOnly.getOrCreateInnerClassMapping(inner.obfuscatedName))
        }
    }
    return clsMap
}

fun InnerClassMapping.splitOffInnerClassMappings(to: InnerClassMapping) {
    to.deobfuscatedName = deobfuscatedName
    deobfuscatedName = obfuscatedName
    for (inner in innerClassMappings) {
        inner.splitOffInnerClassMappings(to.getOrCreateInnerClassMapping(inner.obfuscatedName))
    }
}

// Like a.merge(b) except that mappings in b which do not exist in a at all will nevertheless be preserved.
fun MappingSet.mergeBoth(b: MappingSet, into: MappingSet = MappingSet.create()): MappingSet {
    topLevelClassMappings.forEach { aClass ->
        val bClass = b.getTopLevelClassMapping(aClass.deobfuscatedName).orElse(null)
        if (bClass != null) {
            val merged = into.getOrCreateTopLevelClassMapping(aClass.obfuscatedName)
            merged.deobfuscatedName = bClass.deobfuscatedName
            aClass.mergeBoth(bClass, merged)
        } else {
            aClass.copy(into)
        }
    }
    b.topLevelClassMappings.forEach {
        if (!topLevelClassMappings.any { c -> c.deobfuscatedName == it.obfuscatedName }) {
            it.copy(into)
        }
    }
    return into
}

fun <T : ClassMapping<T, *>> ClassMapping<T, *>.mergeBoth(b: ClassMapping<T, *>, merged: ClassMapping<T, *>) {
    fieldMappings.forEach {
        val bField = b.getFieldMapping(it.deobfuscatedName).orElse(null)
        if (bField != null) {
            it.join(bField, merged)
        } else {
            it.copy(merged)
        }
    }
    b.fieldMappings.forEach {
        if (!fieldMappings.any { c -> c.deobfuscatedSignature == it.signature }) {
            it.copy(merged)
        }
    }
    methodMappings.forEach {
        val bMethod = b.getMethodMapping(it.deobfuscatedSignature).orElse(null)
        if (bMethod != null) {
            it.join(bMethod, merged)
        } else {
            it.copy(merged)
        }
    }
    b.methodMappings.forEach {
        if (!methodMappings.any { c -> c.deobfuscatedSignature == it.signature }) {
            it.copy(merged)
        }
    }
    innerClassMappings.forEach { aClass ->
        val bClass = b.getInnerClassMapping(aClass.deobfuscatedName).orElse(null)
        if (bClass != null) {
            val mergedInner = merged.getOrCreateInnerClassMapping(aClass.obfuscatedName)
            mergedInner.deobfuscatedName = bClass.deobfuscatedName
            aClass.mergeBoth(bClass, mergedInner)
        } else {
            aClass.copy(merged)
        }
    }
    b.innerClassMappings.forEach {
        if (!innerClassMappings.any { c -> c.deobfuscatedName == it.obfuscatedName }) {
            it.copy(merged)
        }
    }
}

// Like a.merge(b) except that mappings not in b will not be in the result (even if they're in a)
// Also ignores types for field/method joining (so fields/methods with matching intermediate names will still be mapped
// even when their types have changed)
fun MappingSet.join(b: MappingSet, into: MappingSet = MappingSet.create()): MappingSet {
    topLevelClassMappings.forEach { classA ->
        b.getTopLevelClassMapping(classA.deobfuscatedName).ifPresent { classB ->
            classA.join(classB, into)
        }
    }
    return into
}

fun TopLevelClassMapping.join(b: TopLevelClassMapping, into: MappingSet) {
    val merged = into.getOrCreateTopLevelClassMapping(obfuscatedName)
    merged.deobfuscatedName = b.deobfuscatedName
    fieldMappings.forEach { fieldA ->
        val fieldB = b.getFieldMapping(fieldA.deobfuscatedSignature).orElse(null)
            ?: b.getFieldMapping(fieldA.deobfuscatedName).orElse(null)
            ?: return@forEach
        fieldA.join(fieldB, merged)
    }
    methodMappings.forEach { methodA ->
        val methodB = b.getMethodMapping(methodA.deobfuscatedSignature).orElse(null)
            ?: b.methodMappings.find { methodA.deobfuscatedName == it.obfuscatedName }
            ?: return@forEach
        methodA.join(methodB, merged)
    }
    innerClassMappings.forEach { classA ->
        b.getInnerClassMapping(classA.deobfuscatedName).ifPresent { classB ->
            classA.join(classB, merged)
        }
    }
}

fun InnerClassMapping.join(b: InnerClassMapping, into: ClassMapping<*, *>) {
    val merged = into.getOrCreateInnerClassMapping(obfuscatedName)
    merged.deobfuscatedName = b.deobfuscatedName
    fieldMappings.forEach { fieldA ->
        val fieldB = b.getFieldMapping(fieldA.deobfuscatedSignature).orElse(null)
            ?: b.getFieldMapping(fieldA.deobfuscatedName).orElse(null)
            ?: return@forEach
        fieldA.join(fieldB, merged)
    }
    methodMappings.forEach { methodA ->
        val methodB = b.getMethodMapping(methodA.deobfuscatedSignature).orElse(null)
            ?: b.methodMappings.find { methodA.deobfuscatedName == it.obfuscatedName }
            ?: return@forEach
        methodA.join(methodB, merged)
    }
    innerClassMappings.forEach { classA ->
        b.getInnerClassMapping(classA.deobfuscatedName).ifPresent { classB ->
            classA.join(classB, merged)
        }
    }
}

// Like FieldMapping.merge but not horribly slow in newer lorenz versions
fun FieldMapping.join(with: FieldMapping, parent: ClassMapping<*, *>): FieldMapping =
        parent.createFieldMapping(signature)
                .setDeobfuscatedName(with.deobfuscatedName)

// Like FieldMapping.merge but not horribly slow in newer lorenz versions
fun MethodMapping.join(with: MethodMapping, parent: ClassMapping<*, *>): MethodMapping =
        parent.createMethodMapping(signature)
                .setDeobfuscatedName(with.deobfuscatedName)
                .also { merged ->
                    parameterMappings.forEach { paramA ->
                        val paramB = with.getOrCreateParameterMapping(paramA.index)
                        paramA.merge(paramB, merged)
                    }
                }

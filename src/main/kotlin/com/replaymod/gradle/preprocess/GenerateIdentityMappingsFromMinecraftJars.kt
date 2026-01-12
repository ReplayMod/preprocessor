package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.jar.JarInputStream

internal abstract class GenerateIdentityMappingsFromMinecraftJars : DefaultTask() {
    @get:InputFiles
    abstract val minecraftJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun generate() {
        val mappingTree = MemoryMappingTree()
        mappingTree.visitNamespaces("named", emptyList())

        val classVisitor = object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                mappingTree.visitClass(name)
                super.visit(version, access, name, signature, superName, interfaces)
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                mappingTree.visitField(name, descriptor)
                return super.visitField(access, name, descriptor, signature, value)
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                mappingTree.visitMethod(name, descriptor)
                return super.visitMethod(access, name, descriptor, signature, exceptions)
            }

            override fun visitEnd() {
                super.visitEnd()

                mappingTree.visitEnd()
            }
        }

        for (minecraftJar in minecraftJars.files) {
            minecraftJar.inputStream().use { rawIn ->
                JarInputStream(rawIn).use { jarIn ->
                    while (true) {
                        val entry = jarIn.nextEntry ?: break
                        if (entry.name.endsWith(".class")) {
                            val classReader = ClassReader(jarIn)
                            classReader.accept(classVisitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                        }
                    }
                }
            }
        }

        output.get().asFile.bufferedWriter().use { writer ->
            mappingTree.accept(Tiny2FileWriter(writer, false))
        }
    }
}

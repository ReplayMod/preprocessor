package com.replaymod.gradle.preprocess

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

import java.nio.charset.StandardCharsets

class Transformer {
    private static final ARTIFACT = 'com.github.replaymod:remap:5fba27c'
    private static final MAIN_CLASS = 'com.replaymod.gradle.remap.Transformer'

    File mapping
    boolean reverseMapping = false
    List<File> classpath = new ArrayList<>()

    private Project project
    private Configuration configuration

    Transformer(Project project) {
        this.project = project

        def dependency = project.dependencies.create(ARTIFACT)
        configuration = project.configurations.detachedConfiguration dependency
    }

    Map<String, String> run(Map<String, String> sources) {
        def stdout = new ByteArrayOutputStream()
        def input = classpath.collect { it.absolutePath + '\n' }.join() +
                sources.collect { "${it.key}\n${it.value.count('\n') + 1}\n${it.value}\n" }.join()
        def stdin = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))

        project.javaexec {
            classpath configuration

            def lombokFiles = this.classpath.findAll { it.name.matches(/^lombok(-[0-9\.]+)?\.jar$/) }
            if (!lombokFiles.isEmpty()) {
                def lombokFile = lombokFiles.get(0).canonicalPath
                jvmArgs "-javaagent:$lombokFile=ECJ"
                classpath lombokFile
            }

            main MAIN_CLASS
            args mapping?.absolutePath ?: "", reverseMapping, this.classpath.size()
            standardOutput = stdout
            standardInput = stdin
        }.rethrowFailure().assertNormalExitValue()

        def lines = new String(stdout.toByteArray(), StandardCharsets.UTF_8).readLines()
        def result = new HashMap()
        def name = null
        def i = null
        def text = ''
        for (line in lines) {
            if (name == null) {
                name = line
            } else if (i == null) {
                i = line.toInteger()
            } else {
                text += line + '\n'
                i--
                if (i == 0) {
                    result.put(name, text)
                    name = null
                    text = ''
                    i = null
                }
            }
        }
        result
    }
}

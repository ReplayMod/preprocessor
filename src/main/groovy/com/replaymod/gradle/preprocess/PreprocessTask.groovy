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

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class PreprocessTask extends DefaultTask {
    @OutputDirectory
    File generated

    @InputDirectory
    File source

    @Input
    Map<String, Integer> vars = new HashMap<>()

    static def DEFAULT_KEYWORDS = [if: '//#if ', ifdef: '//#ifdef ', else: '//#else', endif: '//#endif', eval: '//$$']
    static def CFG_KEYWORDS = [if: '##if ', ifdef: '##ifdef ', else: '##else', endif: '##endif', eval: '#$$']

    @Input
    Map<String, Map<String, String>> keywords = [
            '.java': DEFAULT_KEYWORDS,
            '.gradle': DEFAULT_KEYWORDS,
            '.json': DEFAULT_KEYWORDS,
            '.mcmeta': DEFAULT_KEYWORDS,
            '.cfg': CFG_KEYWORDS
    ]

    void source(Object file) {
        source = project.file(file)
    }

    void generated(Object file) {
        generated = project.file(file)
    }

    void inplace(Object file) {
        source = generated = project.file(file)
    }

    void var(String name, int value) {
        vars.put(name, value)
    }

    void var(Map<String, Integer> map) {
        vars.putAll(map)
    }

    void keywords(String extension, Map<String, String> map) {
        keywords.put(extension, map)
    }

    static Map<String, String> defaultKeywords() {
        DEFAULT_KEYWORDS
    }

    @TaskAction
    void preprocess() {
        def inPath = source.toPath()
        def outPath = generated.toPath()
        def inPlace = inPath.toAbsolutePath() == outPath.toAbsolutePath()
        project.fileTree(source).forEach { file ->
            def outFile = outPath.resolve(inPath.relativize(file.toPath())).toFile()
            def kws = keywords.find { ext, _ -> file.name.endsWith(ext) }
            if (kws) {
                convertFile(kws.value, vars, file, outFile)
            } else if (!inPlace) {
                project.copy {
                    from file
                    into outFile.parentFile
                }
            }
        }
    }

    static def evalVar(vars, String var) {
        if (var.number) {
            return var as int
        } else {
            return vars[var]
        }
    }

    static def EXPR_PATTERN = Pattern.compile(/(.+)(<=|>=|<|>)(.+)/)

    static def evalExpr(vars, String expr) {
        expr = expr.trim()

        def parts = expr.split(/\|\|/)
        if (parts.length > 1) {
            return parts.any { evalExpr(vars, it) }
        }
        parts = expr.split(/&&/)
        if (parts.length > 1) {
            return !parts.any { !evalExpr(vars, it) }
        }

        def matcher = EXPR_PATTERN.matcher(expr)
        if (matcher.matches()) {
            def lhs = evalVar(vars, matcher.group(1))
            def rhs = evalVar(vars, matcher.group(3))
            switch (matcher.group(2)) {
                case '>=': return lhs >= rhs
                case '<=': return lhs <= rhs
                case '>': return lhs > rhs
                case '<': return lhs < rhs
            }
        }
    }

    static def getIndent(String str) {
        return str.takeWhile {it == ' '}.length()
    }

    class ParserException extends RuntimeException {
        ParserException(String str) {
            super(str)
        }
    }

    static def convertSource(Map<String, String> kws, Map<String, Integer> vars, List<String> lines, String fileName) {
        def ifStack = []
        List<Integer> indentStack = []
        def active = true
        def n = 0
        lines = lines.collect { line ->
            n++
            def trimmed = line.trim()
            if (trimmed.startsWith(kws.if)) {
                def result = evalExpr(vars, trimmed.substring(kws.if.length()))
                ifStack.push(result)
                if (result != null) {
                    indentStack.push(getIndent(line))
                    active &= result
                }
            } else if (trimmed.startsWith(kws.else)) {
                if (ifStack.isEmpty()) {
                    throw new ParserException("Unexpected else in line $n of $fileName")
                }
                def head = ifStack.pop()
                if (head != null) {
                    head = !head
                    indentStack.pop()
                    indentStack.push(getIndent(line))
                }
                ifStack.push(head)
                active = true;
                ifStack.each {
                    if (it != null) {
                        active &= it
                    }
                }
            } else if (trimmed.startsWith(kws.ifdef)) {
                def result = vars.containsKey(trimmed.substring(kws.ifdef.length()))
                ifStack.push(result)
                indentStack.push(getIndent(line))
                active &= result
            } else if (trimmed.startsWith(kws.endif)) {
                if (ifStack.isEmpty()) {
                    throw new ParserException("Unexpected endif in line $n of $fileName")
                }
                def head = ifStack.pop()
                if (head != null) {
                    indentStack.pop()
                    active = true;
                    ifStack.each {
                        if (it != null) {
                            active &= it
                        }
                    }
                }
            } else {
                if (active) {
                    if (trimmed.startsWith(kws.eval)) {
                        line = line.replaceFirst(Pattern.quote(kws.eval) + ' ?', '')
                        if (line.trim().isEmpty()) {
                            line = ''
                        }
                    }
                } else {
                    def currIndent = indentStack.last()
                    if (trimmed.isEmpty()) {
                        line = ' ' * currIndent + kws.eval
                    } else if (!trimmed.startsWith(kws.eval)) {
                        def actualIndent = getIndent(line)
                        if (currIndent <= actualIndent) {
                            line = ' ' * currIndent + kws.eval + ' ' + line.substring(currIndent)
                        }
                    }
                }
            }
            line
        }
        if (!ifStack.isEmpty()) {
            throw new ParserException("Missing endif in $fileName")
        }
        return lines
    }

    static def convertFile(Map<String, String> kws, Map<String, Integer> vars, File inFile, File outFile) {
        def string = new String(inFile.readBytes(), StandardCharsets.UTF_8)
        def lines = string.readLines()
        try {
            lines = convertSource(kws, vars, lines, inFile.path)
        } catch (e) {
            if (e instanceof ParserException) {
                throw e
            }
            throw new RuntimeException('Failed to convert file ' + inFile, e)
        }
        outFile.parentFile.mkdirs()
        if (string.endsWith('\n')) {
            outFile.write(lines.collect { it + '\n' }.join(''))
        } else {
            outFile.write(lines.join('\n'))
        }
    }
}

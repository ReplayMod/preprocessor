package com.replaymod.gradle.preprocess

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class PreprocessorTests : FunSpec({
    val vars = mapOf(
            "zero" to 0,
            "one" to 1,
            "two" to 2,
            "t" to 1,
            "f" to 0
    )
    with(CommentPreprocessor(vars)) {
        context("evalExpr") {
            test("c-style truthiness of variables") {
                "zero".evalExpr().shouldBeFalse()
                "one".evalExpr().shouldBeTrue()
                "two".evalExpr().shouldBeTrue()
                "t".evalExpr().shouldBeTrue()
                "f".evalExpr().shouldBeFalse()
            }
            test("negation") {
                "!zero".evalExpr().shouldBeTrue()
                "!one".evalExpr().shouldBeFalse()
                "!two".evalExpr().shouldBeFalse()
                "!t".evalExpr().shouldBeFalse()
                "!f".evalExpr().shouldBeTrue()
            }
            test("a == b") {
                "one == 0".evalExpr().shouldBeFalse()
                "one == 1".evalExpr().shouldBeTrue()
                "1 == zero".evalExpr().shouldBeFalse()
                "1 == one".evalExpr().shouldBeTrue()
            }
            test("a != b") {
                "one != 0".evalExpr().shouldBeTrue()
                "one != 1".evalExpr().shouldBeFalse()
                "1 != zero".evalExpr().shouldBeTrue()
                "1 != one".evalExpr().shouldBeFalse()
            }
            test("a > b") {
                "one > 0".evalExpr().shouldBeTrue()
                "one > 1".evalExpr().shouldBeFalse()
                "one > 2".evalExpr().shouldBeFalse()
                "1 > zero".evalExpr().shouldBeTrue()
                "1 > one".evalExpr().shouldBeFalse()
                "1 > two".evalExpr().shouldBeFalse()
            }
            test("a >= b") {
                "one >= 0".evalExpr().shouldBeTrue()
                "one >= 1".evalExpr().shouldBeTrue()
                "one >= 2".evalExpr().shouldBeFalse()
                "1 >= zero".evalExpr().shouldBeTrue()
                "1 >= one".evalExpr().shouldBeTrue()
                "1 >= two".evalExpr().shouldBeFalse()
            }
            test("a < b") {
                "one < 0".evalExpr().shouldBeFalse()
                "one < 1".evalExpr().shouldBeFalse()
                "one < 2".evalExpr().shouldBeTrue()
                "1 < zero".evalExpr().shouldBeFalse()
                "1 < one".evalExpr().shouldBeFalse()
                "1 < two".evalExpr().shouldBeTrue()
            }
            test("a <= b") {
                "one <= 0".evalExpr().shouldBeFalse()
                "one <= 1".evalExpr().shouldBeTrue()
                "one <= 2".evalExpr().shouldBeTrue()
                "1 <= zero".evalExpr().shouldBeFalse()
                "1 <= one".evalExpr().shouldBeTrue()
                "1 <= two".evalExpr().shouldBeTrue()
            }
            test("a && b") {
                "t && t".evalExpr().shouldBeTrue()
                "t && f".evalExpr().shouldBeFalse()
                "f && t".evalExpr().shouldBeFalse()
                "f && f".evalExpr().shouldBeFalse()
            }
            test("a || b") {
                "t || t".evalExpr().shouldBeTrue()
                "t || f".evalExpr().shouldBeTrue()
                "f || t".evalExpr().shouldBeTrue()
                "f || f".evalExpr().shouldBeFalse()
            }
            test("|| and && should nest") {
                "t && t || t".evalExpr().shouldBeTrue()
                "t && f || t".evalExpr().shouldBeTrue()
                "t && f || t && f".evalExpr().shouldBeFalse()
                "t || f && t || f".evalExpr().shouldBeTrue()
                "f || f && t || f".evalExpr().shouldBeFalse()
            }
            test("should allow underscore in numbers") {
                "1_19_02 == 11902".evalExpr().shouldBeTrue()
            }
            test("should desugar dot-separated version literals") {
                "1.19.02 == 11902".evalExpr().shouldBeTrue()
                "1.19.2 == 11902".evalExpr().shouldBeTrue()
                "1.19 == 11900".evalExpr().shouldBeTrue()
                "1.8.9 == 10809".evalExpr().shouldBeTrue()
                "1.8 == 10800".evalExpr().shouldBeTrue()
                "1.7.10 == 10710".evalExpr().shouldBeTrue()
            }
            test("unknown variables should throw") {
                shouldThrow<NoSuchElementException> { "invalid == 0".evalExpr() }
            }
        }
        context("convertSource") {
            fun String.convert() = convertSource(
                    PreprocessTask.DEFAULT_KEYWORDS,
                    lines(),
                    lines().map { it to emptyList() },
                    "test.java"
            ).joinToString("\n")

            test("throws on unexpected endif") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#endif".convert() }
            }
            test("throws on unexpected else") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#else".convert() }
            }
            test("throws on unexpected elseif") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#elseif".convert() }
            }
            test("throws on elseif after else") {
                shouldThrow<CommentPreprocessor.ParserException> { """
                    //#if t
                    //#else
                    //#elseif t
                    //#endif
                """.convert() }
            }
            test("throws on missing endif") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#if t".convert() }
                shouldThrow<CommentPreprocessor.ParserException> { "//#if t\n//#if t\n//#endif".convert() }
            }
            test("throws on missing space") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#ift\n//#endif".convert() }
                shouldThrow<CommentPreprocessor.ParserException> { "//#if f\n//#elseift\n//#endif".convert() }
            }
            test("throws on empty if condition") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#if\n//#endif".convert() }
                shouldThrow<CommentPreprocessor.ParserException> { "//#if f\n//#elseif\n//#endif".convert() }
            }
            test("if t .. endif") {
                """
                    //#if t
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#endif
                """)
            }
            test("if f .. endif") {
                """
                    //#if f
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#endif
                """)
            }
            test("if t .. else .. endif") {
                """
                    //#if t
                    code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#else
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#else
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#else
                    //$$ code
                    //#endif
                """)
            }
            test("if f .. else .. endif") {
                """
                    //#if f
                    //$$ code
                    //#else
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#else
                    code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#else
                    code
                    //#endif
                """)
            }
            test("if t .. elseif f .. endif") {
                """
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#elseif f
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    code
                    //#elseif f
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """)
            }
            test("if f .. elseif t .. endif") {
                """
                    //#if f
                    code
                    //#elseif t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """)
                """
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#elseif t
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """)
                """
                    //#if f
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """)
            }
            test("if t .. elseif t .. endif") {
                """
                    //#if t
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif t
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif t
                    //$$ code
                    //#endif
                """)
            }
            test("if .. elseif .. else .. endif") {
                """
                    //#if f
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#else
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif t
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """)
                """
                    //#if f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#else
                    code
                    //#endif
                """)
            }
            test("multiple elseifs") {
                """
                    //#if f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#elseif t
                    code
                    //#elseif f
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """)
            }
            test("nested if") {
                """
                    //#if f
                        //#if f
                        code
                        //#else
                        //$$ code
                        //#endif
                    //#else
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#else
                        //#if f
                        //$$ code
                        //#else
                        code
                        //#endif
                    //#endif
                """)
            }
            test("nested elseifs") {
                """
                    //#if f
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#elseif t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#elseif t
                    code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#elseif t
                        //#if f
                        code
                        //#else
                        code
                        //#endif
                    //#elseif f
                    code
                    //#else
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                        //#if f
                        //$$ code
                        //#else
                        code
                        //#endif
                    //#elseif f
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#elseif f
                        //#if f
                        code
                        //#else
                        code
                        //#endif
                    //#elseif f
                    code
                    //#else
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif f
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#elseif f
                    //$$ code
                    //#else
                    code
                    //#endif
                """)
                """
                    //#if t
                    //#elseif t
                        //#if t
                        code
                        //#else
                        //#endif
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    //#elseif t
                        //#if t
                        //$$ code
                        //#else
                        //#endif
                    //#endif
                """)
                """
                    //#if t
                    //#elseif t
                        //#if t
                        //#endif
                        code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    //#elseif t
                        //#if t
                        //#endif
                    //$$     code
                    //#endif
                """)
            }
            test("uses mapped source for unaffected lines") {
                convertSource(
                        PreprocessTask.DEFAULT_KEYWORDS,
                        listOf("//#if t", "original", "//#endif"),
                        listOf("//#if t", "mapped", "//#endif").map { it to emptyList() },
                        "test.java"
                ).shouldBe(listOf("//#if t", "mapped", "//#endif"))
            }
            test("uses original source for newly commented lines") {
                convertSource(
                        PreprocessTask.DEFAULT_KEYWORDS,
                        listOf("//#if f", "original", "//#endif"),
                        listOf("//#if f", "mapped", "//#endif").map { it to emptyList() },
                        "test.java"
                ).shouldBe(listOf("//#if f", "//$$ original", "//#endif"))
            }
            test("fails when there are errors in unaffected lines") {
                with (CommentPreprocessor(vars)) {
                    convertSource(
                            PreprocessTask.DEFAULT_KEYWORDS,
                            listOf("//#if t", "original", "//#endif"),
                            listOf(
                                    "//#if t" to emptyList(),
                                    "mapped" to listOf("err1", "err2"),
                                    "//#endif" to emptyList()
                            ),
                            "test.java"
                    )
                    fail.shouldBeTrue()
                }
            }
            test("ignores errors in commented lines") {
                with (CommentPreprocessor(vars)) {
                    convertSource(
                            PreprocessTask.DEFAULT_KEYWORDS,
                            listOf("//#if f", "original", "//#endif"),
                            listOf(
                                    "//#if f" to emptyList(),
                                    "mapped" to listOf("err1", "err2"),
                                    "//#endif" to emptyList()
                            ),
                            "test.java"
                    )
                    fail.shouldBeFalse()
                }
            }
        }
    }
})

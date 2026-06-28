package com.candelahq.candela.actions

import com.candelahq.candela.CandelaPlatformTestCase
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.psi.PsiDocumentManager

/**
 * Platform tests for [extractCodeContext].
 *
 * Uses the IntelliJ test fixture to configure a real editor with PSI-backed
 * files, then invokes [extractCodeContext] via a synthesised [AnActionEvent].
 */
class CodeContextPlatformTest : CandelaPlatformTestCase() {
    /**
     * Build an [AnActionEvent] whose [DataContext] exposes the fixture's
     * editor, PSI file, virtual file, and project — exactly what
     * [extractCodeContext] expects.
     */
    private fun buildActionEvent(): AnActionEvent {
        val editor = myFixture.editor
        val psiFile = myFixture.file
        val virtualFile = psiFile.virtualFile

        val dataContext =
            DataContext { dataId ->
                when (dataId) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.EDITOR.name -> editor
                    CommonDataKeys.PSI_FILE.name -> psiFile
                    CommonDataKeys.VIRTUAL_FILE.name -> virtualFile
                    else -> null
                }
            }

        return AnActionEvent.createEvent(
            dataContext,
            Presentation(),
            "TestPlace",
            ActionUiKind.NONE,
            null,
        )
    }

    fun testExtractFileName() {
        myFixture.configureByText(
            "MyService.kt",
            "package com.example\n\nclass MyService\n",
        )
        val ctx = extractCodeContext(buildActionEvent())
        assertNotNull(ctx)
        assertEquals("MyService.kt", ctx!!.fileName)
    }

    fun testExtractLanguage() {
        myFixture.configureByText(
            "MyService.kt",
            "class MyService\n",
        )
        val ctx = extractCodeContext(buildActionEvent())
        assertNotNull(ctx)
        // IntelliJ Kotlin plugin reports the language id; fall back to extension
        assertTrue(
            "Expected language to be 'kotlin' or 'Kotlin', got: ${ctx!!.language}",
            ctx.language.equals("kotlin", ignoreCase = true),
        )
    }

    fun testExtractImports() {
        val code =
            """
            package com.example

            import java.util.List
            import kotlin.collections.Map

            class MyService
            """.trimIndent()

        myFixture.configureByText("MyService.kt", code)
        val ctx = extractCodeContext(buildActionEvent())
        assertNotNull(ctx)
        assertTrue(
            "Expected imports to contain 'import java.util.List', got: ${ctx!!.imports}",
            ctx.imports.contains("import java.util.List"),
        )
        assertTrue(
            "Expected imports to contain 'import kotlin.collections.Map'",
            ctx.imports.contains("import kotlin.collections.Map"),
        )
        assertTrue(
            "Expected imports to contain 'package com.example'",
            ctx.imports.contains("package com.example"),
        )
    }

    fun testExtractEnclosingClassWithSelection() {
        val code =
            """
            package com.example

            class MyService {
                fun process() {
                    val x = <selection>42</selection>
                }
            }
            """.trimIndent()

        myFixture.configureByText("MyService.kt", code)
        // Commit PSI so the tree is in sync
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val ctx = extractCodeContext(buildActionEvent())
        assertNotNull(ctx)
        // The selection marker above should create a selection in the fixture
        assertTrue("Selection should be present", myFixture.editor.selectionModel.hasSelection())
        // Enclosing class/function detection depends on PSI + selection
        assertEquals("MyService", ctx!!.enclosingClass)
    }

    fun testNoSelectionReturnsNullLineRange() {
        myFixture.configureByText("Simple.kt", "val x = 1\n")
        // No selection — just a caret
        myFixture.editor.selectionModel.removeSelection()

        val ctx = extractCodeContext(buildActionEvent())
        assertNotNull(ctx)
        assertNull("lineRange should be null without selection", ctx!!.lineRange)
    }

    fun testJavaFileExtraction() {
        val code =
            """
            package com.example;

            import java.util.ArrayList;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("hello");
                }
            }
            """.trimIndent()

        myFixture.configureByText("Main.java", code)
        val ctx = extractCodeContext(buildActionEvent())
        assertNotNull(ctx)
        assertEquals("Main.java", ctx!!.fileName)
        assertTrue(
            "Expected imports to contain 'import java.util.ArrayList'",
            ctx.imports.contains("import java.util.ArrayList"),
        )
    }
}

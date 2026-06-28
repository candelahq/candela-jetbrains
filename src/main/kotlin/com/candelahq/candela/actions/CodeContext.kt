package com.candelahq.candela.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Contextual information about the code surrounding the user's selection.
 *
 * Extracted from the editor at action time and included in the chat message
 * to give the LLM better understanding of the code's environment.
 */
data class CodeContext(
    /** File basename (e.g. "FooService.kt") */
    val fileName: String,
    /** Path relative to project root (e.g. "src/main/kotlin/com/example/FooService.kt") */
    val filePath: String,
    /** Language identifier (e.g. "kotlin", "java", "python") */
    val language: String,
    /** Import/package declarations from the file header */
    val imports: String,
    /** Name of the enclosing class/object, if any */
    val enclosingClass: String?,
    /** Name of the enclosing function/method, if any */
    val enclosingFunction: String?,
    /** 1-indexed start and end line numbers of the selection */
    val lineRange: Pair<Int, Int>?,
)

/**
 * Extract code context from the current editor state.
 *
 * Uses a language-agnostic approach:
 * - Import detection via text pattern matching (works for Java, Kotlin, Python, Go, etc.)
 * - Enclosing element detection via PSI [PsiNamedElement] tree walk
 *
 * Returns `null` if the required editor/file data is unavailable.
 */
fun extractCodeContext(e: AnActionEvent): CodeContext? {
    val project = e.project ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val psiFile = e.getData(CommonDataKeys.PSI_FILE)
    val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null

    val basePath = project.basePath ?: ""
    val relativePath = virtualFile.path.removePrefix(basePath).removePrefix("/")
    val lang = psiFile?.language?.id?.lowercase() ?: virtualFile.extension ?: ""
    val fileName = psiFile?.name ?: virtualFile.name

    // Extract import/package declarations from the file header (language-agnostic).
    // Use immutableCharSequence to avoid copying the entire document, and limit to
    // the first 100 lines where imports/packages are typically located.
    val importLines =
        editor.document.immutableCharSequence
            .lineSequence()
            .take(100)
            .filter { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("import ") ||
                    trimmed.startsWith("package ") ||
                    trimmed.startsWith("from ") ||
                    trimmed.startsWith("using ") ||
                    trimmed.startsWith("#include ")
            }.joinToString("\n")

    // Walk PSI tree to find enclosing named elements (class, function, etc.)
    var enclosingClass: String? = null
    var enclosingFunction: String? = null
    if (psiFile != null && editor.selectionModel.hasSelection()) {
        // Ensure PSI tree is in sync with the editor document
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val element = psiFile.findElementAt(editor.selectionModel.selectionStart)
        if (element != null) {
            // Walk up PSI tree collecting named parents
            val namedParents =
                PsiTreeUtil
                    .collectParents(element, PsiNamedElement::class.java, false) { it == psiFile }

            for (parent in namedParents) {
                val name = parent.name ?: continue
                val typeName =
                    parent.node
                        ?.elementType
                        ?.toString()
                        ?.lowercase() ?: ""
                when {
                    typeName.contains("class") || typeName.contains("object") ->
                        if (enclosingClass == null) enclosingClass = name
                    typeName.contains("fun") || typeName.contains("method") ->
                        if (enclosingFunction == null) enclosingFunction = name
                }
            }
        }
    }

    // Line range of selection
    val lineRange =
        if (editor.selectionModel.hasSelection()) {
            val startOffset = editor.selectionModel.selectionStart
            val endOffset = editor.selectionModel.selectionEnd
            // When selecting whole lines, selectionEnd is at the start of the
            // next line. Decrement by 1 to avoid including that extra line.
            val adjustedEnd = if (endOffset > startOffset) endOffset - 1 else endOffset
            Pair(
                editor.document.getLineNumber(startOffset) + 1,
                editor.document.getLineNumber(adjustedEnd) + 1,
            )
        } else {
            null
        }

    return CodeContext(
        fileName = fileName,
        filePath = relativePath,
        language = lang,
        imports = importLines,
        enclosingClass = enclosingClass,
        enclosingFunction = enclosingFunction,
        lineRange = lineRange,
    )
}

/**
 * Format a [CodeContext] as a human-readable header for inclusion in chat messages.
 *
 * Example output:
 * ```
 * File: src/main/kotlin/com/example/FooService.kt (kotlin)
 * Class: FooService | Function: processItems | Lines: 42-58
 *
 * Imports:
 * import kotlinx.coroutines.*
 * import com.example.models.Item
 * ```
 */
fun formatContextHeader(ctx: CodeContext): String {
    val parts = mutableListOf<String>()
    parts.add("File: `${ctx.filePath}` (${ctx.language})")

    val locationParts = mutableListOf<String>()
    ctx.enclosingClass?.let { locationParts.add("Class: $it") }
    ctx.enclosingFunction?.let { locationParts.add("Function: $it") }
    ctx.lineRange?.let { (start, end) -> locationParts.add("Lines: $start-$end") }
    if (locationParts.isNotEmpty()) {
        parts.add(locationParts.joinToString(" | "))
    }

    if (ctx.imports.isNotBlank()) {
        parts.add("\nImports:\n${ctx.imports}")
    }

    return parts.joinToString("\n")
}

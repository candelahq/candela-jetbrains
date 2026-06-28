package com.candelahq.candela

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/**
 * Project-level service that provides a [CoroutineScope] tied to the project lifecycle.
 *
 * IntelliJ automatically injects a [CoroutineScope] via the constructor and cancels it
 * when the project is closed. All plugin coroutines should use this scope (or a child of it)
 * instead of creating unmanaged [CoroutineScope] instances.
 *
 * Usage:
 * ```kotlin
 * val scope = project.service<CandelaCoroutineService>().scope
 * scope.launch { ... }
 * ```
 */
@Service(Service.Level.PROJECT)
class CandelaCoroutineService(
    val scope: CoroutineScope,
)

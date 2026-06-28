package com.candelahq.candela

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the coroutine scope lifecycle pattern used throughout the plugin.
 *
 * The plugin uses a parent-child scope hierarchy:
 *   CandelaCoroutineService (project scope)
 *     └── StatusBarWidget scope (child SupervisorJob)
 *     └── ChatPanel scope (child SupervisorJob)
 *
 * These tests verify that:
 * 1. Child scopes inherit the parent Job relationship
 * 2. Cancelling the parent cascades to children
 * 3. SupervisorJob prevents sibling cancellation on failure
 */
class CoroutineScopeLifecycleTest {
    @Test
    fun `child scope cancels when parent scope is cancelled`() =
        runTest {
            val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val childScope =
                parentScope.let { parent ->
                    CoroutineScope(
                        parent.coroutineContext +
                            SupervisorJob(parent.coroutineContext[Job]) +
                            Dispatchers.Default,
                    )
                }

            assertTrue(parentScope.isActive, "Parent should be active")
            assertTrue(childScope.isActive, "Child should be active")

            parentScope.cancel("Project closing")

            // Give cancellation time to propagate
            delay(50)

            assertFalse(parentScope.isActive, "Parent should be cancelled")
            assertFalse(childScope.isActive, "Child should be cancelled when parent is cancelled")
        }

    @Test
    fun `child scope can be cancelled independently of parent`() =
        runTest {
            val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val childScope =
                parentScope.let { parent ->
                    CoroutineScope(
                        parent.coroutineContext +
                            SupervisorJob(parent.coroutineContext[Job]) +
                            Dispatchers.Default,
                    )
                }

            childScope.cancel("Widget disposed")

            delay(50)

            assertTrue(parentScope.isActive, "Parent should still be active")
            assertFalse(childScope.isActive, "Child should be cancelled")
        }

    @Test
    fun `SupervisorJob prevents sibling cancellation on failure`() =
        runTest {
            // CoroutineExceptionHandler is required to consume the uncaught exception
            // from the failing child — without it, the exception would propagate.
            val handler =
                CoroutineExceptionHandler { _, _ ->
                    // swallow — child failures are expected in this test
                }
            val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)

            var sibling1Failed = false
            var sibling2Completed = false

            val job1 =
                parentScope.launch {
                    sibling1Failed = true
                    throw RuntimeException("child failure")
                }

            val job2 =
                parentScope.launch {
                    delay(100)
                    sibling2Completed = true
                }

            // Wait for both to finish
            job1.join()
            job2.join()

            assertTrue(sibling1Failed, "Job1 should have run and failed")
            assertTrue(sibling2Completed, "Job2 should complete independently despite sibling failure")
            assertTrue(parentScope.isActive, "Parent scope should still be active")
        }

    @Test
    fun `coroutines launched in child scope are cancelled with scope`() =
        runTest {
            val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val childScope =
                parentScope.let { parent ->
                    CoroutineScope(
                        parent.coroutineContext +
                            SupervisorJob(parent.coroutineContext[Job]) +
                            Dispatchers.Default,
                    )
                }

            var coroutineWasCancelled = false
            val job =
                childScope.launch {
                    try {
                        delay(Long.MAX_VALUE)
                    } finally {
                        coroutineWasCancelled = true
                    }
                }

            delay(50) // let coroutine start
            childScope.cancel("dispose")
            job.join()

            assertTrue(coroutineWasCancelled, "Coroutine should be cancelled when scope is cancelled")
        }
}

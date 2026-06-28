package com.candelahq.candela

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
            val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            var sibling1Cancelled = false
            var sibling2Completed = false

            val job1 =
                parentScope.launch {
                    try {
                        delay(Long.MAX_VALUE) // wait forever
                    } finally {
                        sibling1Cancelled = true
                    }
                }

            val job2 =
                parentScope.launch {
                    delay(50)
                    sibling2Completed = true
                }

            // Cancel job1 — should NOT affect job2
            job1.cancel()
            job2.join()

            assertTrue(sibling1Cancelled, "Job1 should have been cancelled")
            assertTrue(sibling2Completed, "Job2 should complete independently")
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

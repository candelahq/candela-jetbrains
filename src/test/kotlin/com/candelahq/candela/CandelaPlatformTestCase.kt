package com.candelahq.candela

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base class for Candela platform tests.
 *
 * Extends [BasePlatformTestCase] to get a full IntelliJ Platform test fixture
 * including a project, application, PSI infrastructure, and an in-memory editor.
 *
 * Subclasses use JUnit 3 conventions: test methods named `testXxx()`,
 * setup via `setUp()` / `tearDown()`, and no `@Test` annotations.
 */
abstract class CandelaPlatformTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData"
}

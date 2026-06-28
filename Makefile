# Candela JetBrains — Development Targets
#
# Usage:
#   make test             — Run all unit tests (fast, no IDE sandbox)
#   make platform-test    — Run platform tests (boots full IDE sandbox, ~60s)
#   make lint             — Run ktlint + detekt
#   make build            — Build the plugin
#   make check            — Full pre-merge check (lint + unit tests + build)

.PHONY: test platform-test lint build check

# ── Unit Tests (JUnit 5, no IDE sandbox) ──────────────────────────
test:
	./gradlew test \
		--tests "com.candelahq.candela.BackoffTest" \
		--tests "com.candelahq.candela.BuildCodeFenceTest" \
		--tests "com.candelahq.candela.CodeContextTest" \
		--tests "com.candelahq.candela.CoroutineScopeLifecycleTest" \
		--tests "com.candelahq.candela.StatusBar*" \
		--tests "com.candelahq.candela.Stream*" \
		--tests "com.candelahq.candela.actions.ActionsTest" \
		--tests "com.candelahq.candela.chat.*Test" \
		--tests "com.candelahq.candela.client.*"

# ── Platform Tests (JUnit 3, full IDE sandbox) ────────────────────
# NOTE: These boot a full IntelliJ IDE instance. They run in ~60s via
# Gradle CLI, or ~5s per test in the IntelliJ IDE test runner.
# They are NOT run in CI due to slow tearDown on GitHub Actions.
platform-test:
	./gradlew test \
		--tests "com.candelahq.candela.settings.CandleSettingsPlatformTest" \
		--tests "com.candelahq.candela.actions.CodeContextPlatformTest" \
		--tests "com.candelahq.candela.actions.ActionUpdatePlatformTest"

# ── Lint ──────────────────────────────────────────────────────────
lint:
	./gradlew ktlintCheck detekt

# ── Build ─────────────────────────────────────────────────────────
build:
	./gradlew buildPlugin

# ── Full Check (mirrors CI pipeline) ─────────────────────────────
check: lint test build
	@echo ""
	@echo "✅ All CI-equivalent checks passed."
	@echo "💡 Remember to run 'make platform-test' before merging."
	@echo "   (Platform tests require IDE sandbox — not run in CI)"

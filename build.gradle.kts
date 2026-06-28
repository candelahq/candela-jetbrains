import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.6.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "com.candelahq"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }

    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    // Coroutines are bundled by IntelliJ 2025.1+ — compileOnly avoids classloader clashes
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("junit:junit:4.13.2") // Required by IntelliJ platform test framework
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.6.0")
}

detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}

intellijPlatform {
    pluginConfiguration {
        id = "com.candelahq.candela"
        name = "Candela - LLM Cost Tracker"
        version = project.version.toString()
        description =
            """
            Real-time LLM cost tracking, budget warnings, and observability for Candela.

            <ul>
              <li><b>Status bar widget</b> — live token counts and spend</li>
              <li><b>Budget warnings</b> — notifications when approaching daily limits</li>
              <li><b>Grant display</b> — active bonus grants with expiry countdowns</li>
              <li><b>Dashboard launcher</b> — open the Candela web dashboard</li>
            </ul>

            Works with any JetBrains IDE: IntelliJ IDEA, WebStorm, PyCharm, GoLand, etc.
            Requires a local <a href="https://github.com/candelahq/candela">Candela</a> instance.
            """.trimIndent()
        vendor {
            name = "Candela HQ"
            url = "https://candelahq.com"
            email = "austin@apache.org"
        }
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
        changeNotes =
            """
            <h3>0.1.0</h3>
            <ul>
              <li>Initial release</li>
              <li>Status bar cost tracker with auto-refresh</li>
              <li>Budget warnings and grant display</li>
              <li>Dashboard launcher action</li>
            </ul>
            """.trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    publishing {
        // Token from JetBrains Marketplace
        token = providers.environmentVariable("JETBRAINS_TOKEN")
    }
}

tasks {
    test {
        useJUnitPlatform()

        // Platform tests boot a full IDE sandbox — these args are required for
        // headless CI (GitHub Actions) and prevent hangs on display access.
        jvmArgs(
            "-Xmx2g",
            "-Xms512m",
            "-Djava.awt.headless=true",
            "-Didea.test.cyclic.buffer.size=1048576",
        )

        // Fail fast: 10 min per test class (platform tests boot IDE sandbox)
        systemProperty("idea.test.timeout.minutes", "5")
    }
}

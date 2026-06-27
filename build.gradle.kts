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
        intellijIdeaCommunity("2024.3")
        pluginVerifier()
    }

    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("junit:junit:4.13.2") // Required by IntelliJ platform test framework
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
            sinceBuild = "243"
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
    }
}

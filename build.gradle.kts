plugins {
    base
    id("com.android.application") version "8.7.3" apply false
    id("com.android.test") version "8.7.3" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
}

allprojects {
    group = "com.pocketagent"
    version = "0.1.0-phase0"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure(io.gitlab.arturbosch.detekt.extensions.DetektExtension::class.java) {
            buildUponDefaultConfig = true
            allRules = false
            autoCorrect = false
            ignoreFailures = true
            baseline = rootProject.file("config/detekt/baseline.xml")
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        }
        tasks.withType(io.gitlab.arturbosch.detekt.Detekt::class.java).configureEach {
            jvmTarget = "17"
            reports.xml.required.set(true)
            reports.html.required.set(true)
            reports.sarif.required.set(true)
        }
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        extensions.configure(org.jlleitschuh.gradle.ktlint.KtlintExtension::class.java) {
            version.set("1.3.1")
            ignoreFailures.set(true)
            verbose.set(true)
        }
    }
}

import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

data class SourceBoundaryRule(
    val description: String,
    val roots: List<String>,
    val forbiddenPatterns: List<String>,
    val allowSuffixes: Set<String> = emptySet(),
    val excludeFileNames: Set<String> = emptySet(),
)

fun Project.sourceBoundaryOffenders(rule: SourceBoundaryRule): List<String> {
    return rule.roots
        .asSequence()
        .map(::file)
        .filter { it.exists() }
        .flatMap { root ->
            fileTree(root) {
                include("**/*.kt")
            }.files.asSequence()
        }
        .filter { file -> file.name !in rule.excludeFileNames }
        .filter { file ->
            val source = file.readText()
            val hasForbiddenPattern = rule.forbiddenPatterns.any(source::contains)
            val allowedPath = rule.allowSuffixes.any { suffix ->
                file.invariantSeparatorsPath.endsWith(suffix)
            }
            hasForbiddenPattern && !allowedPath
        }
        .map { it.invariantSeparatorsPath }
        .sorted()
        .toList()
}

fun String.isTruthyFlag(): Boolean = trim().lowercase() in setOf("1", "true", "yes")

val nativeBuildEnabled = providers.gradleProperty("pocketgpt.enableNativeBuild")
    .orElse("true")
    .map { it.isTruthyFlag() }
    .get()
val nativeHexagonEnabled = providers.gradleProperty("pocketgpt.enableHexagonBuild")
    .orElse("false")
    .map { it.isTruthyFlag() }
    .get()
val nativeArmI8mmEnabled = providers.gradleProperty("pocketgpt.enableArmI8mm")
    .orElse("false")
    .map { it.isTruthyFlag() }
    .get()
val nativeAbiFilters = providers.gradleProperty("pocketgpt.nativeAbiFilters")
    .orElse("arm64-v8a")
    .map { raw ->
        raw
            .split(",")
            .map { abi -> abi.trim() }
            .filter { abi -> abi.isNotEmpty() }
    }
    .get()
val nativeTargets = if (nativeAbiFilters.size == 1 && nativeAbiFilters.single() == "arm64-v8a") {
    listOf(
        "pocket_llama",
        "pocket_llama_v8",
        "pocket_llama_v8_2",
        "pocket_llama_v8_2_dotprod",
        "pocket_llama_v8_2_i8mm",
        "pocket_llama_v8_2_dotprod_i8mm",
    )
} else {
    listOf("pocket_llama")
}
val modelManifestUrl = providers.gradleProperty("pocketgpt.modelManifestUrl")
    .orElse("")
    .get()
    .replace("\"", "\\\"")
val hfFixtureBaseUrl = providers.gradleProperty("pocketgpt.hfFixtureBaseUrl")
    .orElse("")
    .get()
    .replace("\"", "\\\"")

android {
    namespace = "com.pocketagent.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketagent.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "NATIVE_RUNTIME_LIBRARY_PACKAGED", nativeBuildEnabled.toString())
        buildConfigField("String", "MODEL_MANIFEST_URL", "\"$modelManifestUrl\"")
        buildConfigField("String", "HF_FIXTURE_BASE_URL", "\"$hfFixtureBaseUrl\"")
    }

    if (nativeBuildEnabled) {
        val cmakePath = file("src/main/cpp/CMakeLists.txt")
        if (!cmakePath.exists()) {
            throw GradleException(
                "Native build enabled but CMake file is missing at ${cmakePath.path}. " +
                    "Add native sources or disable -Ppocketgpt.enableNativeBuild.",
            )
        }
        if (nativeAbiFilters.isEmpty()) {
            throw GradleException(
                "Native build enabled but no ABIs were configured. " +
                    "Set -Ppocketgpt.nativeAbiFilters (for example: arm64-v8a or x86_64).",
            )
        }

        defaultConfig {
            ndk {
                abiFilters += nativeAbiFilters
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DPOCKETGPT_ENABLE_HEXAGON=${if (nativeHexagonEnabled) "ON" else "OFF"}",
                        "-DPOCKETGPT_FORCE_ARM_I8MM=${if (nativeArmI8mmEnabled) "ON" else "OFF"}",
                    )
                    cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                    targets += nativeTargets
                }
            }
        }

        externalNativeBuild {
            cmake {
                path = cmakePath
            }
        }
    }

    signingConfigs {
        // Reuse the well-known Android debug keystore so the benchmark variant is installable
        // on developer/CI devices without provisioning a custom signing key. The benchmark
        // variant is NEVER published; it is the perf-measurement APK only.
        create("benchmark") {
            val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Performance-measurement variant: production-grade ART (no DEBUGGABLE flag, no Compose
        // debug-source instrumentation, AOT eligible) but without minification, so traces and
        // crash reports retain readable symbols. Use this — never `debug` — for perf-baseline,
        // gfxinfo measurement, and any merge/release jank gate. See
        // `docs/architecture/android-performance-contract.md`.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isDebuggable = false
            isProfileable = true
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("benchmark")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

composeCompiler {
    stabilityConfigurationFile = layout.projectDirectory.file("compose-stability.conf")
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
}

dependencies {
    implementation(project(":packages:core-domain"))
    implementation(project(":packages:inference-adapters"))
    implementation(project(":packages:tool-runtime"))
    implementation(project(":packages:memory"))
    implementation(project(":packages:native-bridge"))
    implementation(project(":packages:app-runtime"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.bihe0832.android:lib-sherpa-onnx:6.25.21")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyAndroidArchitecture by tasks.registering {
    group = "verification"
    description = "Fails the build when Android architecture boundary rules regress."

    doLast {
        val rules = listOf(
            SourceBoundaryRule(
                description = "UI package must not reference AppRuntimeDependencies",
                roots = listOf("src/main/kotlin/com/pocketagent/android/ui"),
                forbiddenPatterns = listOf("AppRuntimeDependencies"),
            ),
            SourceBoundaryRule(
                description = "Only sanctioned gateways may reference AppRuntimeDependencies",
                roots = listOf("src/main/kotlin/com/pocketagent/android"),
                forbiddenPatterns = listOf("AppRuntimeDependencies"),
                allowSuffixes = setOf(
                    "com/pocketagent/android/AppRuntimeDependencies.kt",
                    "com/pocketagent/android/PocketAgentApplication.kt",
                    "com/pocketagent/android/runtime/AppForegroundRuntimeServices.kt",
                    "com/pocketagent/android/runtime/ProvisioningGateway.kt",
                    "com/pocketagent/android/runtime/ProvisioningRuntimeBindings.kt",
                    "com/pocketagent/android/runtime/modelmanager/ModelDownloadCancelReceiver.kt",
                ),
            ),
            SourceBoundaryRule(
                description = "Runtime package must not depend on UI packages",
                roots = listOf("src/main/kotlin/com/pocketagent/android/runtime"),
                forbiddenPatterns = listOf("com.pocketagent.android.ui"),
            ),
            SourceBoundaryRule(
                description = "Model download stack must not depend on compile-time model catalog",
                roots = listOf("src/main/kotlin/com/pocketagent/android/runtime/modelmanager"),
                forbiddenPatterns = listOf("import com.pocketagent.inference.ModelCatalog"),
            ),
            SourceBoundaryRule(
                description = "ui/state must not host persistence implementation details",
                roots = listOf("src/main/kotlin/com/pocketagent/android/ui/state"),
                forbiddenPatterns = listOf(
                    "SQLiteOpenHelper",
                    "PersistedChatStateCodec",
                    "AndroidSessionPersistence",
                    "SQLiteChatSessionRepository",
                    "interface SessionPersistence",
                    "sealed interface SessionStateLoadResult",
                    "StoredChatState",
                ),
            ),
            SourceBoundaryRule(
                description = "UI packages must not import nativebridge types directly",
                roots = listOf("src/main/kotlin/com/pocketagent/android/ui"),
                forbiddenPatterns = listOf("import com.pocketagent.nativebridge."),
            ),
            SourceBoundaryRule(
                description = "Android layer must use ChatRuntimeService directly",
                roots = listOf(
                    "src/main/kotlin/com/pocketagent/android",
                    "src/test/kotlin/com/pocketagent/android",
                    "src/androidTest/kotlin/com/pocketagent/android",
                ),
                forbiddenPatterns = listOf(
                    "import com.pocketagent.android.runtime.RuntimeGateway",
                    ": RuntimeGateway",
                    "typealias RuntimeGateway",
                ),
                excludeFileNames = setOf("ArchitectureBoundaryTest.kt"),
            ),
            SourceBoundaryRule(
                description = "Android layer must prepare streams via ChatRuntimeService.prepareChatStream",
                roots = listOf(
                    "src/main/kotlin/com/pocketagent/android",
                    "src/test/kotlin/com/pocketagent/android",
                ),
                forbiddenPatterns = listOf("buildStreamChatRequest("),
                excludeFileNames = setOf("ArchitectureBoundaryTest.kt"),
            ),
            SourceBoundaryRule(
                description = "Deprecated StreamUserMessageRequest must stay isolated to the legacy runtime adapter only",
                roots = listOf(
                    "src/main/kotlin/com/pocketagent/android",
                    "src/test/kotlin/com/pocketagent/android",
                    "src/androidTest/kotlin/com/pocketagent/android",
                ),
                forbiddenPatterns = listOf("StreamUserMessageRequest"),
                allowSuffixes = setOf("com/pocketagent/android/HotSwappableRuntimeFacade.kt"),
                excludeFileNames = setOf("ArchitectureBoundaryTest.kt"),
            ),
        )

        val failures = rules.mapNotNull { rule ->
            val offenders = sourceBoundaryOffenders(rule)
            if (offenders.isEmpty()) {
                null
            } else {
                "${rule.description}. Found: ${offenders.joinToString()}"
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException(failures.joinToString(separator = "\n\n"))
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyAndroidArchitecture)
}

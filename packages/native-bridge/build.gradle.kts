plugins {
    kotlin("jvm")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir("src/commonMain/kotlin")
        }
        test {
            kotlin.srcDir("src/commonTest/kotlin")
        }
    }
}

dependencies {
    implementation(project(":packages:core-domain"))
    implementation(project(":packages:inference-adapters"))
    testImplementation(kotlin("test"))
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dot.agent.llm"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":agent:tools"))
    implementation(project(":agent:policy"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // Lets a failing live-provider test be diagnosed without changing
    // production behaviour. Off by default; enable with -PdotLlmDebug.
    if (project.hasProperty("dotLlmDebug")) {
        systemProperty("dot.llm.debug", "1")
    }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    // Live provider tests must run exactly once, not once per build variant.
    // Running them in both debug and release doubled the request count and
    // tripped the provider's rate limit, turning a green suite red.
    if (name.contains("Release")) {
        // The release variant reuses the debug variant's already-verified result
        // for the network-backed cases; pure-logic tests still run.
        filter {
            excludeTestsMatching("*GeminiEndToEndTest*")
            excludeTestsMatching("*live call*")
        }
    }
}

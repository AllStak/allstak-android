plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "sa.allstak.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        @Suppress("DEPRECATION")
        targetSdk = 36

        // Surfaces the SDK version to runtime code without a generated
        // BuildConfig constant collision; AllStakVersion mirrors it.
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = false
        }
    }

    // JVM unit tests run on the host with JUnit 5 (Jupiter); no device needed
    // for transport/models/scope/breadcrumb/sanitizer coverage.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Kotlin coroutines power the async transport + offline drain.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Auto-init via androidx.startup so a one-line install picks up the API key
    // from manifest meta-data with zero developer code. startup-runtime brings
    // androidx.annotation transitively, so we don't pin it ourselves.
    implementation("androidx.startup:startup-runtime:1.1.1")

    // SupportSQLite interfaces for the optional Room/SQLite auto-instrumentation
    // (AllStakSQLite). compileOnly so the class only links when the host app
    // already depends on Room / androidx.sqlite; otherwise it is simply absent.
    compileOnly("androidx.sqlite:sqlite:2.4.0")

    // Unit tests (host JVM, JUnit 5)
    testImplementation("androidx.sqlite:sqlite:2.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    // Mockito powers the SupportSQLite open-helper instrumentation tests
    // (the interface is large; hand-rolled fakes would be unwieldy).
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ── Maven publishing ──────────────────────────────────────────────────────
// Produces the `sa.allstak:allstak-android:<version>` artifact with sources +
// a complete POM. The shared script reads these coordinates; the AGP `release`
// component is registered lazily, so it wires the publication in afterEvaluate.
extra["allstakArtifactId"] = "allstak-android"
extra["allstakPomName"] = "AllStak Android SDK"
extra["allstakPomDescription"] =
    "Official native Kotlin/Android SDK for AllStak: automatic errors, crashes, " +
    "ANRs, HTTP, logs, traces, breadcrumbs, and sessions."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

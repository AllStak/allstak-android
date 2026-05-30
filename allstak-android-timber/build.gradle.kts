plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "sa.allstak.android.timber"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        @Suppress("DEPRECATION")
        targetSdk = 36
        consumerProguardFiles("consumer-rules.pro")
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
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":allstak-android"))

    // The logging facade is supplied by the host app; guarded so the module
    // degrades gracefully when it is absent.
    compileOnly("com.jakewharton.timber:timber:5.0.1")

    testImplementation("com.jakewharton.timber:timber:5.0.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ── Maven publishing ──────────────────────────────────────────────────────
extra["allstakArtifactId"] = "allstak-android-timber"
extra["allstakPomName"] = "AllStak Android Timber"
extra["allstakPomDescription"] =
    "Timber integration for the AllStak Android SDK: ships logs as AllStak log " +
    "events and promotes throwable-carrying errors to captured exceptions."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

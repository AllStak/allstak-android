plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "sa.allstak.android.okhttp"
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

    // OkHttp is provided by the host app; we never force a version on them.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ── Maven publishing ──────────────────────────────────────────────────────
extra["allstakArtifactId"] = "allstak-android-okhttp"
extra["allstakPomName"] = "AllStak Android OkHttp"
extra["allstakPomDescription"] =
    "OkHttp integration for the AllStak Android SDK: per-call HTTP records, " +
    "client spans, breadcrumbs, and trace-propagation headers."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

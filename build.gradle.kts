// Root build script. Plugins are declared here with `apply false` so module
// build scripts can apply them without re-declaring versions.
plugins {
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // `maven-publish` and `signing` are core Gradle plugins; modules apply them
    // directly (they cannot be declared here with `apply false`).

    // Sonatype Central Portal publishing. The aggregation variant collects the
    // `release` MavenPublication of every module into a single bundle and
    // uploads it to https://central.sonatype.com via the Portal API. Applied
    // (not `apply false`) on the root so the `nmcpAggregation` extension and the
    // `publishAggregationToCentralPortal` task are configured here.
    id("com.gradleup.nmcp.aggregation") version "1.5.0"
}

// Shared coordinates for every published module.
extra["allstakVersion"] = "0.2.0"
extra["allstakGroup"] = "sa.allstak"

// Shared POM / SCM metadata reused by each module's publication.
extra["allstakScmUrl"] = "https://github.com/AllStak/allstak-android"
extra["allstakProjectUrl"] = "https://allstak.sa"

allprojects {
    group = "sa.allstak"
    version = "0.2.0"
}

// ── Sonatype Central Portal aggregation ────────────────────────────────────
// Bundles all three modules' publications and uploads them as one deployment to
// the Central Portal. Credentials come from MAVEN_CENTRAL_USERNAME /
// MAVEN_CENTRAL_PASSWORD env vars (the CI secret names), falling back to the
// mavenCentralUsername / mavenCentralPassword Gradle properties for local use.
// The publish task is `publishAggregationToCentralPortal`.
nmcpAggregation {
    centralPortal {
        username.set(
            providers.environmentVariable("MAVEN_CENTRAL_USERNAME")
                .orElse(providers.gradleProperty("mavenCentralUsername")),
        )
        password.set(
            providers.environmentVariable("MAVEN_CENTRAL_PASSWORD")
                .orElse(providers.gradleProperty("mavenCentralPassword")),
        )
        // Central validates the bundle, then the deployment is released
        // automatically once validation passes.
        publishingType.set("AUTOMATIC")
    }
}

dependencies {
    nmcpAggregation(project(":allstak-android"))
    nmcpAggregation(project(":allstak-android-okhttp"))
    nmcpAggregation(project(":allstak-android-timber"))
}

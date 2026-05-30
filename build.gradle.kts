// Root build script. Plugins are declared here with `apply false` so module
// build scripts can apply them without re-declaring versions.
plugins {
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // `maven-publish` and `signing` are core Gradle plugins; modules apply them
    // directly (they cannot be declared here with `apply false`).
}

// Shared coordinates for every published module.
extra["allstakVersion"] = "0.1.0"
extra["allstakGroup"] = "sa.allstak"

// Shared POM / SCM metadata reused by each module's publication.
extra["allstakScmUrl"] = "https://github.com/AllStak/allstak-android"
extra["allstakProjectUrl"] = "https://allstak.sa"

allprojects {
    group = "sa.allstak"
    version = "0.1.0"
}

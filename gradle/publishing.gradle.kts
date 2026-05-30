// Shared Maven-publishing convention for every AllStak Android module.
//
// Applied via `apply(from = rootProject.file("gradle/publishing.gradle.kts"))`
// after the module sets these extra properties:
//   allstakArtifactId, allstakPomName, allstakPomDescription
//
// Wires a `release` Maven publication (AAR + sources jar + complete POM) and a
// publish target, plus optional signing. The AGP `release` software component
// is created lazily, so the publication is configured inside `afterEvaluate`.
//
// Repositories:
//   - `GitHubPackages`  — configured when ALLSTAK_GH_USER + ALLSTAK_GH_TOKEN
//     (or gpr.user / gpr.token Gradle properties) are present.
//   - `mavenLocal()` is always available via `publishToMavenLocal`.
//
// Signing:
//   - Active only when a key is supplied (in-memory ASCII-armored key via
//     SIGNING_KEY/SIGNING_PASSWORD env, or the gnupg keyring via Gradle props),
//     so local/dev builds publish unsigned while release CI signs.

val artifactId = providers.provider { project.extra["allstakArtifactId"] as String }.get()
val pomName = providers.provider { project.extra["allstakPomName"] as String }.get()
val pomDescription = providers.provider { project.extra["allstakPomDescription"] as String }.get()

val scmUrl = (rootProject.extra["allstakScmUrl"] as String)
val projectUrl = (rootProject.extra["allstakProjectUrl"] as String)

fun envOrProp(envName: String, propName: String): String? =
    System.getenv(envName) ?: (project.findProperty(propName) as String?)

afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = project.group.toString()
                this@create.artifactId = artifactId
                version = project.version.toString()

                pom {
                    name.set(pomName)
                    description.set(pomDescription)
                    url.set(projectUrl)

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("allstak")
                            name.set("AllStak")
                            email.set("sdk@allstak.sa")
                            organization.set("AllStak")
                            organizationUrl.set(projectUrl)
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/AllStak/allstak-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/AllStak/allstak-android.git")
                        url.set(scmUrl)
                        tag.set("HEAD")
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("$scmUrl/issues")
                    }
                }
            }
        }

        repositories {
            val ghUser = envOrProp("ALLSTAK_GH_USER", "gpr.user")
            val ghToken = envOrProp("ALLSTAK_GH_TOKEN", "gpr.token")
            if (ghUser != null && ghToken != null) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/AllStak/allstak-android")
                    credentials {
                        username = ghUser
                        password = ghToken
                    }
                }
            }
        }
    }

    // Optional signing — only when a key is present, so dev/local publishes
    // (e.g. publishToMavenLocal) work unsigned and release CI signs.
    val signingKey = System.getenv("SIGNING_KEY") ?: (project.findProperty("signingKey") as String?)
    val signingPassword = System.getenv("SIGNING_PASSWORD") ?: (project.findProperty("signingPassword") as String?)
    if (signingKey != null) {
        extensions.configure<SigningExtension>("signing") {
            useInMemoryPgpKeys(signingKey, signingPassword)
            val publishing = extensions.getByType(PublishingExtension::class.java)
            sign(publishing.publications["release"])
        }
    } else if (project.hasProperty("signing.keyId")) {
        extensions.configure<SigningExtension>("signing") {
            val publishing = extensions.getByType(PublishingExtension::class.java)
            sign(publishing.publications["release"])
        }
    }
}

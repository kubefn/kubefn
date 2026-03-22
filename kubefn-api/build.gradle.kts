// kubefn-api: The function author contract. TINY, stable, zero heavy deps.
// Only SLF4J API is exposed to function authors.
//
// Published to Maven so function authors can depend on it:
//   implementation("io.kubefn:kubefn-api:0.2.0")

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.slf4j.api)
    testImplementation(libs.bundles.testing)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "io.kubefn"
            artifactId = "kubefn-api"
            version = project.version.toString()

            from(components["java"])

            pom {
                name.set("KubeFn API")
                description.set("Function author API for KubeFn — the Live Application Fabric")
                url.set("https://kubefn.com")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("spranab")
                        name.set("Pranab Sarkar")
                        email.set("mail@pranab.co.in")
                    }
                }
                scm {
                    url.set("https://github.com/spranab/KubeFn")
                }
            }
        }
    }

    repositories {
        // Publish to GitHub Packages
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/spranab/KubeFn")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "spranab"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

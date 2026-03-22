// kubefn-api: The function author contract. TINY, stable, zero heavy deps.
//
// Published to Maven Central:
//   implementation("com.kubefn:kubefn-api:0.3.1")

plugins {
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.central.publishing)
}

dependencies {
    api(libs.slf4j.api)
    testImplementation(libs.bundles.testing)
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        addBooleanOption("html5", true)
    }
}

// Sonatype Central Portal publishing
centralPortal {
    username = System.getenv("SONATYPE_USERNAME") ?: ""
    password = System.getenv("SONATYPE_PASSWORD") ?: ""

    pom {
        name.set("KubeFn API")
        description.set("Function author API for KubeFn — the Live Application Fabric for Memory-Continuous Architecture")
        url.set("https://kubefn.com")
        inceptionYear.set("2026")

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
                email.set("developer@pranab.co.in")
                url.set("https://pranab.co.in")
            }
        }

        scm {
            url.set("https://github.com/kubefn/kubefn")
            connection.set("scm:git:git://github.com/kubefn/kubefn.git")
            developerConnection.set("scm:git:ssh://github.com/kubefn/kubefn.git")
        }
    }
}

// Also publish to GitHub Packages
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/kubefn/kubefn")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "kubefn"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

signing {
    val signingKey = System.getenv("GPG_SIGNING_KEY")
    val signingPassword = System.getenv("GPG_SIGNING_PASSWORD")
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword ?: "")
    }
    sign(publishing.publications)
}

tasks.withType<Sign> {
    onlyIf { gradle.taskGraph.hasTask("publish") || gradle.taskGraph.hasTask("publishToCentralPortal") }
}

// kubefn-api: The function author contract. TINY, stable, zero heavy deps.
// Only SLF4J API is exposed to function authors.

plugins {
    `java-library`
}

dependencies {
    api(libs.slf4j.api)

    testImplementation(libs.bundles.testing)
}

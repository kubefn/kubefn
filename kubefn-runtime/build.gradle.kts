// kubefn-runtime: The living organism. Netty server, classloading,
// HeapExchange, FnGraph engine, lifecycle management.

plugins {
    alias(libs.plugins.shadow)
    application
}

application {
    mainClass.set("com.kubefn.runtime.KubeFnMain")
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "-XX:+UseZGC",
        "-XX:+DisableAttachMechanism"
    )
}

dependencies {
    implementation(project(":kubefn-api"))
    implementation(project(":kubefn-contracts"))
    implementation(project(":kubefn-shared"))

    // Runtime internals — NOT exposed to functions
    implementation(libs.netty.all)
    implementation(libs.bundles.jackson)
    implementation(libs.caffeine)
    implementation(libs.bundles.logging)
    implementation(libs.micrometer.core)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.logging)
    implementation(libs.resilience4j.circuitbreaker)

    // JVM language runtimes — available to polyglot functions via parent classloader
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
    implementation("org.scala-lang:scala-library:2.13.16")
    implementation("org.apache.groovy:groovy:4.0.27")

    // Jackson modules for polyglot serialization
    implementation("com.fasterxml.jackson.module:jackson-module-scala_2.13:2.18.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")

    testImplementation(libs.bundles.testing)
}

tasks.shadowJar {
    archiveBaseName.set("kubefn-runtime")
    archiveClassifier.set("all")
    mergeServiceFiles()
}

// Example function group demonstrating HeapExchange and FnGraph.
// This module produces a thin JAR with only function classes.

dependencies {
    compileOnly(project(":kubefn-api"))

    testImplementation(libs.bundles.testing)
}

// Produce a thin JAR — no deps bundled, just function classes
tasks.jar {
    archiveBaseName.set("hello-function")
}

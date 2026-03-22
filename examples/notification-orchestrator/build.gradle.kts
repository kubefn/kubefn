// Notification orchestrator: 5-function multi-channel notification pipeline
// Demonstrates template resolution, personalization, and channel routing via HeapExchange

dependencies {
    compileOnly(project(":kubefn-api"))
    testImplementation(libs.bundles.testing)
}

tasks.jar {
    archiveBaseName.set("notification-orchestrator")
}

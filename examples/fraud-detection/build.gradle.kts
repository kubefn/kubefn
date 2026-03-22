// Fraud detection pipeline: 6-function real-time fraud analysis engine
// Demonstrates multi-signal scoring with HeapExchange composition

dependencies {
    compileOnly(project(":kubefn-api"))
    testImplementation(libs.bundles.testing)
}

tasks.jar {
    archiveBaseName.set("fraud-detection")
}

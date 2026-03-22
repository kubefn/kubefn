// Payment processing pipeline: 6-function payment orchestration
// Demonstrates AML screening, FX conversion, and ledger entry via HeapExchange

dependencies {
    compileOnly(project(":kubefn-api"))
    testImplementation(libs.bundles.testing)
}

tasks.jar {
    archiveBaseName.set("payment-processing")
}

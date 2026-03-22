// Real-time pricing engine: 5-function dynamic pricing pipeline
// Demonstrates demand-aware, competitor-aware pricing with HeapExchange

dependencies {
    compileOnly(project(":kubefn-api"))
    testImplementation(libs.bundles.testing)
}

tasks.jar {
    archiveBaseName.set("realtime-pricing")
}

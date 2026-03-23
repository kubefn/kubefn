// Pattern 3: Consumer with Fallback — gracefully handles missing heap data
// Reference implementation for resilient functions that degrade instead of crashing

dependencies {
    compileOnly(project(":kubefn-api"))
    compileOnly(project(":kubefn-contracts"))
}

tasks.jar {
    archiveBaseName.set("pattern-consumer-with-fallback")
}

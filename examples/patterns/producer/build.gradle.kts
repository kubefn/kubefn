// Pattern 1: Producer — publishes typed objects to HeapExchange
// Reference implementation for any function that CREATES data for siblings

dependencies {
    compileOnly(project(":kubefn-api"))
    compileOnly(project(":kubefn-contracts"))
}

tasks.jar {
    archiveBaseName.set("pattern-producer")
}

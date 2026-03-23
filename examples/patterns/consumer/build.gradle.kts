// Pattern 2: Consumer — reads typed objects from HeapExchange
// Reference implementation for any function that READS data published by siblings

dependencies {
    compileOnly(project(":kubefn-api"))
    compileOnly(project(":kubefn-contracts"))
}

tasks.jar {
    archiveBaseName.set("pattern-consumer")
}

// Pattern 5: Contract-First Stub — code against contracts before implementations exist
// Reference implementation for parallel development with typed contracts

dependencies {
    compileOnly(project(":kubefn-api"))
    compileOnly(project(":kubefn-contracts"))
}

tasks.jar {
    archiveBaseName.set("pattern-contract-first-stub")
}

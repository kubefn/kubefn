// Pattern 4: Pipeline Orchestrator — calls sibling functions and assembles results
// Reference implementation for the canonical "multi-step checkout" pattern

dependencies {
    compileOnly(project(":kubefn-api"))
    compileOnly(project(":kubefn-contracts"))
}

tasks.jar {
    archiveBaseName.set("pattern-pipeline-orchestrator")
}

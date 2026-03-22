// AI inference pipeline: 6-function RAG pipeline with retrieval and reranking
// Demonstrates full retrieval-augmented generation flow via HeapExchange

dependencies {
    compileOnly(project(":kubefn-api"))
    testImplementation(libs.bundles.testing)
}

tasks.jar {
    archiveBaseName.set("ai-inference-pipeline")
}

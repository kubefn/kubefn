dependencies {
    compileOnly(project(":kubefn-api"))
    testImplementation(libs.bundles.testing)
}
tasks.jar { archiveBaseName.set("event-processing") }

// kubefn-sdk: Local dev server for function authors to test without K8s.

dependencies {
    implementation(project(":kubefn-api"))
    implementation(project(":kubefn-runtime"))

    testImplementation(libs.bundles.testing)
}

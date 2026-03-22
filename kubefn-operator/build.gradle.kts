// kubefn-operator: K8s CRDs + reconcilers for KubeFnGroup and KubeFnFunction.

plugins {
    application
}

application {
    mainClass.set("com.kubefn.operator.OperatorMain")
}

dependencies {
    implementation(project(":kubefn-api"))

    implementation(libs.fabric8.client)
    implementation(libs.josdk.framework)
    implementation(libs.bundles.logging)

    annotationProcessor(libs.fabric8.crd.generator)

    testImplementation(libs.bundles.testing)
}

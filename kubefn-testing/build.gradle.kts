plugins {
    `java-library`
}

dependencies {
    api(project(":kubefn-api"))

    testImplementation(libs.junit.jupiter)
}

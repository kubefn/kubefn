plugins { java }
dependencies {
    compileOnly(project(":kubefn-api"))
    compileOnly(project(":kubefn-contracts"))
    testImplementation(project(":kubefn-api"))
    testImplementation(project(":kubefn-contracts"))
    testImplementation(project(":kubefn-testing"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
tasks.test { useJUnitPlatform() }

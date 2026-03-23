// kubefn-shared: Common utilities available to all functions.
//
// Published to Maven Central alongside kubefn-api and kubefn-contracts.
// Functions depend on it compileOnly — the runtime provides it.
//
// This is Layer 3 in the KubeFn classpath model:
//   Layer 1: kubefn-api (interfaces)
//   Layer 2: kubefn-contracts (shared types)
//   Layer 3: kubefn-shared (utilities)
//   Layer 4: your-function.jar (your code only)

plugins {
    `java-library`
}

dependencies {
    api(project(":kubefn-api"))
    api(project(":kubefn-contracts"))
}

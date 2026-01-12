// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.build") version "0.6.2"
    id("com.hedera.pbj.pbj-compiler") version "0.12.10" apply false
}

javaModules {
    // This "intermediate parent project" should be removed
    module("platform-sdk") { artifact = "swirlds-platform" }

    // The Hedera API module
    directory("hapi") { group = "com.hedera.hashgraph" }

    // The Hedera platform modules
    directory("platform-sdk") {
        group = "com.hedera.hashgraph"
        module("swirlds") // not actually a Module as it has no module-info.java
        module("swirlds-benchmarks") // not actually a Module as it has no module-info.java
    }

    // The Hedera services modules
    directory("hedera-node") {
        group = "com.hedera.hashgraph"

        // Configure 'artifact' for projects where folder does not correspond to artifact name
        module("hapi-fees") { artifact = "app-hapi-fees" }
        module("hapi-utils") { artifact = "app-hapi-utils" }
        module("hedera-addressbook-service") { artifact = "app-service-addressbook" }
        module("hedera-addressbook-service-impl") { artifact = "app-service-addressbook-impl" }
        module("hedera-app") { artifact = "app" }
        module("hedera-app-spi") { artifact = "app-spi" }
        module("hedera-config") { artifact = "config" }
        module("hedera-consensus-service") { artifact = "app-service-consensus" }
        module("hedera-consensus-service-impl") { artifact = "app-service-consensus-impl" }
        module("hedera-file-service") { artifact = "app-service-file" }
        module("hedera-file-service-impl") { artifact = "app-service-file-impl" }
        module("hedera-network-admin-service") { artifact = "app-service-network-admin" }
        module("hedera-network-admin-service-impl") { artifact = "app-service-network-admin-impl" }
        module("hedera-schedule-service") { artifact = "app-service-schedule" }
        module("hedera-schedule-service-impl") { artifact = "app-service-schedule-impl" }
        module("hedera-smart-contract-service") { artifact = "app-service-contract" }
        module("hedera-smart-contract-service-impl") { artifact = "app-service-contract-impl" }
        module("hedera-token-service") { artifact = "app-service-token" }
        module("hedera-token-service-impl") { artifact = "app-service-token-impl" }
        module("hedera-util-service") { artifact = "app-service-util" }
        module("hedera-util-service-impl") { artifact = "app-service-util-impl" }
        module("hedera-roster-service") { artifact = "app-service-roster" }
        module("hedera-roster-service-impl") { artifact = "app-service-roster-impl" }
        module("hedera-entity-id-service") { artifact = "app-service-entity-id" }
        module("hedera-entity-id-service-impl") { artifact = "app-service-entity-id-impl" }
    }

    // Platform-base demo applications
    directory("example-apps") { group = "com.hedera.hashgraph" }

    module("hedera-state-validator") { group = "com.hedera.hashgraph" }

    @Suppress("UnstableApiUsage")
    gradle.lifecycle.beforeProject {
        plugins.withId("org.hiero.gradle.base.jpms-modules") {
            configure<org.gradlex.javamodule.moduleinfo.ExtraJavaModuleInfoPluginExtension> {
                // besu now requires the consensys' fork of the tuweni libraries
                // (new Coordinates, same Module Names)
                module("io.consensys.tuweni:tuweni-units", "tuweni.units")
                module("io.consensys.tuweni:tuweni-bytes", "tuweni.bytes")
                // new transitive dependency to Automatic-Module
                module("io.vertx:vertx-core", "io.vertx.core")
                // due to https://github.com/hyperledger/besu-native/issues/274 merge all
                // besu-native
                // Jars required. They are integrated in 'org.hyperledger.besu.nativelib.secp256k1'
                // as that is the only module we require directly.
                module(
                    "org.hyperledger.besu:secp256k1",
                    "org.hyperledger.besu.nativelib.secp256k1",
                ) {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                    mergeJar("org.hyperledger.besu:gnark")
                    mergeJar("org.hyperledger.besu:secp256r1")
                    mergeJar("org.hyperledger.besu:arithmetic")
                }
            }
            configure<
                org.gradlex.jvm.dependency.conflict.resolution.JvmDependencyConflictsExtension
            > {
                // Because all natives are summarized in the 'secp256k1' Jar,
                // all other native dependencies need to be removed
                patch {
                    module("org.hyperledger.besu.internal:algorithms") {
                        removeDependency("org.hyperledger.besu:secp256r1")
                    }
                    module("org.hyperledger.besu:evm") {
                        removeDependency("org.hyperledger.besu:arithmetic")
                        removeDependency("org.hyperledger.besu:gnark")
                        addRuntimeOnlyDependency("org.hyperledger.besu:secp256k1")
                    }
                }
            }
        }
    }
}

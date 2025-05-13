// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.build") version "0.4.0" }

javaModules {
    // This "intermediate parent project" should be removed
    module("platform-sdk") { artifact = "swirlds-platform" }

    // The Hedera API module
    directory("hapi") { group = "com.hedera.hashgraph" }

    // The Hedera platform modules
    directory("platform-sdk") {
        group = "com.swirlds"
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
    }

    // Platform-base demo applications
    directory("example-apps") { group = "com.swirlds" }

    // Platform demo applications
    directory("platform-sdk/platform-apps/demos") { group = "com.swirlds" }

    // Platform test applications
    directory("platform-sdk/platform-apps/tests") { group = "com.swirlds" }
}

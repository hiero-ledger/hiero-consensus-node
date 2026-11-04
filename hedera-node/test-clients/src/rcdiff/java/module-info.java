// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.test.clients.rcdiff {
    opens com.hedera.services.rcdiff to
            info.picocli;

    requires com.hedera.node.test.clients;
    requires info.picocli;
    requires static com.github.spotbugs.annotations;
}

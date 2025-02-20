// SPDX-License-Identifier: Apache-2.0
/**
 * Provides fixtures for testing the token service.
 */
module com.hedera.node.app.service.token.test.fixtures {
    exports com.hedera.node.app.service.token.fixtures;

    requires transitive com.hedera.node.app.service.token;
    requires com.hedera.node.hapi;
    requires static transitive com.github.spotbugs.annotations;
}

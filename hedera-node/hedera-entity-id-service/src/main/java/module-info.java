// SPDX-License-Identifier: Apache-2.0
import com.hedera.node.app.service.entityid.EntityIdService;

/**
 * Provides the classes necessary to manage the Hedera Entity ID Generation Service.
 */
module com.hedera.node.app.service.entityid {
    exports com.hedera.node.app.service.entityid;

    uses EntityIdService;

    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires static transitive com.github.spotbugs.annotations;
}

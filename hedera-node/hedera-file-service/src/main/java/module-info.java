// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.file {
    exports com.hedera.node.app.service.file;

    uses com.hedera.node.app.service.file.FileService;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires com.hedera.node.app.hapi.utils;
    requires com.swirlds.state.api;
    requires static transitive com.github.spotbugs.annotations;
}

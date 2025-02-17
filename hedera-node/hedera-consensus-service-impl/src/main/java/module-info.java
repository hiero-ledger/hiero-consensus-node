/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;

/**
 * Module that provides the implementation of the Hedera Consensus Service.
 */
module com.hedera.node.app.service.consensus.impl {
    requires transitive com.hedera.node.app.service.consensus;
    requires transitive com.hedera.node.app.service.token;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires transitive dagger;
    requires transitive java.compiler; // javax.annotation.processing.Generated
    requires transitive javax.inject;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.node.app.service.token.impl;
    requires com.hedera.node.config;
    requires com.swirlds.config.api;
    requires com.google.common;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;

    provides com.hedera.node.app.service.consensus.ConsensusService with
            ConsensusServiceImpl;

    exports com.hedera.node.app.service.consensus.impl to
            com.hedera.node.app,
            com.hedera.node.test.clients;
    exports com.hedera.node.app.service.consensus.impl.handlers;
    exports com.hedera.node.app.service.consensus.impl.handlers.customfee;
    exports com.hedera.node.app.service.consensus.impl.records;
    exports com.hedera.node.app.service.consensus.impl.schemas;
    exports com.hedera.node.app.service.consensus.impl.validators;
}

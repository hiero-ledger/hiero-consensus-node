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

/**
 * Provides the classes necessary to manage Hedera Token Service.
 */
module com.hedera.node.app.service.token {
    exports com.hedera.node.app.service.token;
    exports com.hedera.node.app.service.token.api;
    exports com.hedera.node.app.service.token.records;

    uses com.hedera.node.app.service.token.TokenService;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.state.api;
    requires transitive org.apache.logging.log4j;
    requires com.hedera.node.app.hapi.utils;
    requires com.swirlds.common;
    requires com.github.spotbugs.annotations;
    requires com.google.common;
}

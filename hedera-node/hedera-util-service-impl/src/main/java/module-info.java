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

import com.hedera.node.app.service.util.impl.UtilServiceImpl;

module com.hedera.node.app.service.util.impl {
    requires transitive com.hedera.node.app.service.util;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive dagger;
    requires transitive java.compiler; // javax.annotation.processing.Generated
    requires transitive javax.inject;
    requires com.hedera.node.config;
    requires com.hedera.node.hapi;
    requires com.swirlds.config.api;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;

    provides com.hedera.node.app.service.util.UtilService with
            UtilServiceImpl;

    exports com.hedera.node.app.service.util.impl to
            com.hedera.node.app,
            com.hedera.node.test.clients;
    exports com.hedera.node.app.service.util.impl.handlers;
    exports com.hedera.node.app.service.util.impl.components;
    exports com.hedera.node.app.service.util.impl.records;
}

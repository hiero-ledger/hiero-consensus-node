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
 * A map that implements the FastCopyable interface.
 */
open module com.swirlds.virtualmap {
    exports com.swirlds.virtualmap;
    exports com.swirlds.virtualmap.datasource;
    exports com.swirlds.virtualmap.constructable;
    // Currently, exported only for tests.
    exports com.swirlds.virtualmap.internal.merkle;
    exports com.swirlds.virtualmap.config;
    exports com.swirlds.virtualmap.serialize;

    // Testing-only exports
    exports com.swirlds.virtualmap.internal to
            com.swirlds.virtualmap.test.fixtures;
    exports com.swirlds.virtualmap.internal.cache to
            com.swirlds.virtualmap.test.fixtures,
            com.swirlds.platform.core.test.fixtures;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires com.swirlds.base;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires java.management; // Test dependency
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}

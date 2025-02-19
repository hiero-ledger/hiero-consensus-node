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

open module com.swirlds.common.test.fixtures {
    exports com.swirlds.common.test.fixtures;
    exports com.swirlds.common.test.fixtures.benchmark;
    exports com.swirlds.common.test.fixtures.crypto;
    exports com.swirlds.common.test.fixtures.dummy;
    exports com.swirlds.common.test.fixtures.io;
    exports com.swirlds.common.test.fixtures.junit.tags;
    exports com.swirlds.common.test.fixtures.map;
    exports com.swirlds.common.test.fixtures.merkle.util;
    exports com.swirlds.common.test.fixtures.threading;
    exports com.swirlds.common.test.fixtures.set;
    exports com.swirlds.common.test.fixtures.stream;
    exports com.swirlds.common.test.fixtures.fcqueue;
    exports com.swirlds.common.test.fixtures.platform;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.platform.core;
    requires com.swirlds.config.extensions.test.fixtures;
    requires com.swirlds.logging;
    requires com.swirlds.virtualmap;
    requires lazysodium.java;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j;
    requires org.junit.jupiter.api;
    requires org.mockito;
    requires static transitive com.github.spotbugs.annotations;
}

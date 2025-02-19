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
open module com.swirlds.merkle.test.fixtures {
    exports com.swirlds.merkle.test.fixtures;
    exports com.swirlds.merkle.test.fixtures.map.lifecycle;
    exports com.swirlds.merkle.test.fixtures.map.pta;
    exports com.swirlds.merkle.test.fixtures.map.util;

    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.merkle;
    requires transitive com.fasterxml.jackson.annotation;
    requires transitive com.fasterxml.jackson.core;
    requires transitive com.fasterxml.jackson.databind;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.fchashmap;
    requires com.swirlds.fcqueue;
    requires com.swirlds.logging;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j;
}

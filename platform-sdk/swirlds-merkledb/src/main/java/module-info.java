/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
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
 * A disk-based VirtualDataSource implementation; complete module documentation to be assembled over time as the full
 * implementation is transplanted here.
 */
open module com.swirlds.merkledb {
    exports com.swirlds.merkledb;
    exports com.swirlds.merkledb.collections;
    exports com.swirlds.merkledb.config;
    exports com.swirlds.merkledb.files;
    exports com.swirlds.merkledb.files.hashmap;
    exports com.swirlds.merkledb.utilities;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.virtualmap;
    requires com.swirlds.base;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires java.management;
    requires jdk.management;
    requires jdk.unsupported;
    requires org.apache.logging.log4j;
    requires org.eclipse.collections.api;
    requires org.eclipse.collections.impl;
    requires static transitive com.github.spotbugs.annotations;
}

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

module com.swirlds.config.extensions {
    exports com.swirlds.config.extensions;
    exports com.swirlds.config.extensions.export;
    exports com.swirlds.config.extensions.reflection;
    exports com.swirlds.config.extensions.sources;
    exports com.swirlds.config.extensions.validators;

    requires transitive com.swirlds.config.api;
    requires com.swirlds.base;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}

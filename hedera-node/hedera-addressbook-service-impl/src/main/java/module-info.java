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

import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;

module com.hedera.node.app.service.addressbook.impl {
    requires transitive com.hedera.node.app.service.addressbook;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires transitive dagger;
    requires transitive javax.inject;
    requires transitive org.apache.logging.log4j;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.node.app.service.token;
    requires com.swirlds.common;
    requires com.swirlds.config.api;
    requires com.swirlds.platform.core;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires static transitive java.compiler;
    requires static com.github.spotbugs.annotations;

    provides com.hedera.node.app.service.addressbook.AddressBookService with
            AddressBookServiceImpl;

    exports com.hedera.node.app.service.addressbook.impl;
    exports com.hedera.node.app.service.addressbook.impl.handlers;
    exports com.hedera.node.app.service.addressbook.impl.records;
    exports com.hedera.node.app.service.addressbook.impl.validators;
    exports com.hedera.node.app.service.addressbook.impl.schemas;
}

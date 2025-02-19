/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.addressbook.impl.test.schemas;

import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.FILES_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.endpointFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NodeAddress;
import com.hedera.hapi.node.base.NodeAddressBook;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.ids.AppEntityIdFactory;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.addressbook.impl.test.handlers.AddressBookTestBase;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class V053AddressBookSchemaTest extends AddressBookTestBase {
    private static final Key NODE0_ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .build();
    private static final Key NODE1_ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
            .build();

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    private MigrationContext migrationContext;

    @Mock
    private NetworkInfo networkInfo;

    @TempDir
    java.nio.file.Path tempDir;

    @LoggingSubject
    private V053AddressBookSchema subject;

    private final Map<AccountID, Account> accounts = new HashMap<>();
    private final MapWritableKVState<AccountID, Account> writableAccounts =
            new MapWritableKVState<>(ACCOUNTS_KEY, accounts);

    private final Map<EntityNumber, Node> nodes = new HashMap<>();
    private final MapWritableKVState<EntityNumber, Node> writableNodes = new MapWritableKVState<>(NODES_KEY, nodes);

    private final Map<FileID, File> files = new HashMap<>();
    private final MapWritableKVState<FileID, File> writableFiles = new MapWritableKVState<>(FILES_KEY, files);

    private MapWritableStates writableStates = null;

    @BeforeEach
    void setUp() {
        subject = new V053AddressBookSchema();
    }

    @Test
    void registersExpectedSchema() {
        final var statesToCreate = subject.statesToCreate();
        assertThat(statesToCreate).hasSize(1);
        final var iter =
                statesToCreate.stream().map(StateDefinition::stateKey).sorted().iterator();
        assertEquals(NODES_KEY, iter.next());
    }

    @Test
    void parsesExpectedAdminKeys() {
        final Map<Long, Key> expectedKeys = Map.of(
                0L, NODE0_ADMIN_KEY,
                1L, NODE1_ADMIN_KEY);
        final var actualKeys = V053AddressBookSchema.parseEd25519NodeAdminKeys(nodeAdminKeysJson());
        assertEquals(expectedKeys, actualKeys);
    }

    @Test
    void migrateAsExpected() {
        setupMigrationContext();

        assertThatCode(() -> subject.migrate(migrationContext)).doesNotThrowAnyException();
        assertThat(logCaptor.infoLogs()).contains("Started migrating nodes from address book");
        assertThat(logCaptor.infoLogs()).contains("AccountStore is not found, can be ignored.");
        assertThat(logCaptor.infoLogs()).contains("FileStore is not found, can be ignored.");
        assertThat(logCaptor.infoLogs()).contains("Migrated 3 nodes from address book");
    }

    @Test
    void migrateAsExpected2() {
        setupMigrationContext2();

        assertThatCode(() -> subject.migrate(migrationContext)).doesNotThrowAnyException();
        assertThat(logCaptor.infoLogs()).contains("Started migrating nodes from address book");
        assertThat(logCaptor.infoLogs()).contains("FileStore is not found, can be ignored.");
        assertThat(logCaptor.infoLogs()).contains("Migrated 3 nodes from address book");
        assertEquals(
                Node.newBuilder()
                        .nodeId(1)
                        .accountId(payerId)
                        .description("node2")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.245", 22), endpointFor("127.0.0.1", 123)))
                        .gossipCaCertificate(Bytes.wrap(gossipCaCertificate))
                        .weight(0)
                        .adminKey(NODE1_ADMIN_KEY)
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(1).build()));
        assertEquals(
                Node.newBuilder()
                        .nodeId(2)
                        .accountId(accountId)
                        .description("node3")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.240", 23), endpointFor("127.0.0.2", 123)))
                        .gossipCaCertificate(Bytes.wrap(grpcCertificateHash))
                        .weight(1)
                        .adminKey(anotherKey)
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(2).build()));
        assertEquals(
                Node.newBuilder()
                        .nodeId(3)
                        .accountId(accountId)
                        .description("node4")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.243", 45), endpointFor("127.0.0.3", 124)))
                        .gossipCaCertificate(Bytes.wrap(grpcCertificateHash))
                        .weight(10)
                        .adminKey(anotherKey)
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(3).build()));
    }

    @Test
    void migrateAsExpected3() {
        setupMigrationContext3();

        assertThatCode(() -> subject.migrate(migrationContext)).doesNotThrowAnyException();
        assertThat(logCaptor.infoLogs()).contains("Started migrating nodes from address book");
        assertThat(logCaptor.infoLogs()).contains("Migrated 3 nodes from address book");
        assertEquals(
                Node.newBuilder()
                        .nodeId(1)
                        .accountId(payerId)
                        .description("node2")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.245", 22), endpointFor("127.0.0.1", 123)))
                        .gossipCaCertificate(Bytes.wrap(gossipCaCertificate))
                        .weight(0)
                        .adminKey(anotherKey)
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(1).build()));
        assertEquals(
                Node.newBuilder()
                        .nodeId(2)
                        .accountId(accountId)
                        .description("node3")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.240", 23), endpointFor("127.0.0.2", 123)))
                        .gossipCaCertificate(Bytes.wrap(grpcCertificateHash))
                        .weight(1)
                        .adminKey(anotherKey)
                        .grpcCertificateHash(Bytes.fromHex("ebdaba19283dadbabedab1"))
                        .serviceEndpoint(List.of(endpointFor("127.1.0.1", 1234), endpointFor("127.1.0.2", 1234)))
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(2).build()));
        assertEquals(
                Node.newBuilder()
                        .nodeId(3)
                        .accountId(accountId)
                        .description("node4")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.243", 45), endpointFor("127.0.0.3", 124)))
                        .gossipCaCertificate(Bytes.wrap(grpcCertificateHash))
                        .weight(10)
                        .adminKey(anotherKey)
                        .grpcCertificateHash(Bytes.fromHex("ebdaba19283dadbabedab2"))
                        .serviceEndpoint(
                                List.of(endpointFor("domain.test1.com", 1234), endpointFor("domain.test2.com", 5678)))
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(3).build()));
    }

    @Test
    void migrateAsExpected4() {
        setupMigrationContext4();

        assertThatCode(() -> subject.migrate(migrationContext)).doesNotThrowAnyException();
        assertThat(logCaptor.infoLogs()).contains("Started migrating nodes from address book");
        assertThat(logCaptor.warnLogs()).hasSize(1);

        assertThat(logCaptor.warnLogs()).matches(logs -> logs.getLast()
                .contains("Can not parse file 102 com.hedera.pbj.runtime.ParseException: "));
        assertThat(logCaptor.infoLogs()).contains("Migrated 3 nodes from address book");
        assertEquals(
                Node.newBuilder()
                        .nodeId(1)
                        .accountId(payerId)
                        .description("node2")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.245", 22), endpointFor("127.0.0.1", 123)))
                        .gossipCaCertificate(Bytes.wrap(gossipCaCertificate))
                        .weight(0)
                        .adminKey(anotherKey)
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(1).build()));
        assertEquals(
                Node.newBuilder()
                        .nodeId(2)
                        .accountId(accountId)
                        .description("node3")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.240", 23), endpointFor("127.0.0.2", 123)))
                        .gossipCaCertificate(Bytes.wrap(grpcCertificateHash))
                        .weight(1)
                        .adminKey(anotherKey)
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(2).build()));
        assertEquals(
                Node.newBuilder()
                        .nodeId(3)
                        .accountId(accountId)
                        .description("node4")
                        .gossipEndpoint(List.of(endpointFor("23.45.34.243", 45), endpointFor("127.0.0.3", 124)))
                        .gossipCaCertificate(Bytes.wrap(grpcCertificateHash))
                        .weight(10)
                        .adminKey(anotherKey)
                        .build(),
                writableNodes.get(EntityNumber.newBuilder().number(3).build()));
    }

    @Test
    void failedNullNetworkinfo() {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        given(migrationContext.appConfig()).willReturn(config);
        given(migrationContext.genesisNetworkInfo()).willReturn(null);
        assertThatCode(() -> subject.migrate(migrationContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Genesis network info is not found");
    }

    private void setupMigrationContext() {
        writableStates = MapWritableStates.builder().state(writableNodes).build();
        given(migrationContext.isGenesis()).willReturn(true);
        given(migrationContext.newStates()).willReturn(writableStates);

        final var nodeInfo1 = new NodeInfoImpl(
                1,
                payerId,
                0,
                List.of(endpointFor("23.45.34.245", 22), endpointFor("127.0.0.1", 123)),
                Bytes.wrap(gossipCaCertificate));
        final var nodeInfo2 = new NodeInfoImpl(
                2,
                accountId,
                1,
                List.of(endpointFor("23.45.34.240", 23), endpointFor("127.0.0.2", 123)),
                Bytes.wrap(grpcCertificateHash));
        final var nodeInfo3 = new NodeInfoImpl(
                3,
                accountId,
                10,
                List.of(endpointFor("23.45.34.243", 45), endpointFor("127.0.0.3", 124)),
                Bytes.wrap(grpcCertificateHash));
        given(networkInfo.addressBook()).willReturn(List.of(nodeInfo1, nodeInfo2, nodeInfo3));
        given(migrationContext.genesisNetworkInfo()).willReturn(networkInfo);
        final var config = HederaTestConfigBuilder.create()
                .withValue("bootstrap.genesisPublicKey", defaultAdminKeyBytes)
                .getOrCreateConfig();
        given(migrationContext.appConfig()).willReturn(config);
    }

    private void setupMigrationContext2() {
        setupMigrationContext();
        accounts.put(
                idFactory.newAccountId(55), Account.newBuilder().key(anotherKey).build());
        writableStates = MapWritableStates.builder()
                .state(writableAccounts)
                .state(writableNodes)
                .build();
        given(migrationContext.newStates()).willReturn(writableStates);

        final var adminKeysLoc = tempDir.resolve("node-admin-keys.json");
        try {
            Files.writeString(adminKeysLoc, nodeAdminKeysJson());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final var config = HederaTestConfigBuilder.create()
                .withValue("bootstrap.genesisPublicKey", defaultAdminKeyBytes)
                .withValue(
                        "bootstrap.nodeAdminKeys.path",
                        adminKeysLoc.toAbsolutePath().toString())
                .withValue("accounts.addressBookAdmin", "55")
                .getOrCreateConfig();
        given(migrationContext.appConfig()).willReturn(config);
        final var entityIdFactory = new AppEntityIdFactory(config);
        given(migrationContext.entityIdFactory()).willReturn(entityIdFactory);
    }

    private void setupMigrationContext3() {
        setupMigrationContext2();
        final var nodeDetails = new ArrayList<NodeAddress>();
        nodeDetails.addAll(List.of(
                NodeAddress.newBuilder()
                        .nodeId(2)
                        .nodeCertHash(Bytes.wrap("ebdaba19283dadbabedab1"))
                        .serviceEndpoint(List.of(endpointFor("127.1.0.1", 1234), endpointFor("127.1.0.2", 1234)))
                        .build(),
                NodeAddress.newBuilder()
                        .nodeId(3)
                        .nodeCertHash(Bytes.wrap("ebdaba19283dadbabedab2"))
                        .serviceEndpoint(
                                List.of(endpointFor("domain.test1.com", 1234), endpointFor("domain.test2.com", 5678)))
                        .build()));
        final Bytes fileContent = NodeAddressBook.PROTOBUF.toBytes(
                NodeAddressBook.newBuilder().nodeAddress(nodeDetails).build());
        files.put(
                idFactory.newFileId(102),
                File.newBuilder().contents(fileContent).build());
        writableStates = MapWritableStates.builder()
                .state(writableAccounts)
                .state(writableNodes)
                .state(writableFiles)
                .build();
        given(migrationContext.newStates()).willReturn(writableStates);

        final var config = HederaTestConfigBuilder.create()
                .withValue("bootstrap.genesisPublicKey", defaultAdminKeyBytes)
                .withValue("accounts.addressBookAdmin", "55")
                .withValue("files.nodeDetails", "102")
                .getOrCreateConfig();
        given(migrationContext.appConfig()).willReturn(config);
        final var entityIdFactory = new AppEntityIdFactory(config);
        given(migrationContext.entityIdFactory()).willReturn(entityIdFactory);
    }

    private void setupMigrationContext4() {
        setupMigrationContext2();

        files.put(
                idFactory.newFileId(102),
                File.newBuilder().contents(Bytes.wrap("NotGoodNodeDetailFile")).build());
        writableStates = MapWritableStates.builder()
                .state(writableAccounts)
                .state(writableNodes)
                .state(writableFiles)
                .build();
        given(migrationContext.newStates()).willReturn(writableStates);

        final var config = HederaTestConfigBuilder.create()
                .withValue("bootstrap.genesisPublicKey", defaultAdminKeyBytes)
                .withValue("accounts.addressBookAdmin", "55")
                .withValue("files.nodeDetails", "102")
                .getOrCreateConfig();
        given(migrationContext.appConfig()).willReturn(config);
        final var entityIdFactory = new AppEntityIdFactory(config);
        given(migrationContext.entityIdFactory()).willReturn(entityIdFactory);
    }

    private String nodeAdminKeysJson() {
        return """
                {
                  "0": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                }""";
    }
}

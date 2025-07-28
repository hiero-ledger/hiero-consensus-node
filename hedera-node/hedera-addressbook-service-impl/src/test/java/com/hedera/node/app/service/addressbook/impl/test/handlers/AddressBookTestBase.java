// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.asBytes;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Key.Builder;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.ids.ReadableEntityIdStore;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Random;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.hiero.consensus.model.roster.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AddressBookTestBase {
    private static final String A_NAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B_NAME = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C_NAME = "cccccccccccccccccccccccccccccccc";
    private static final Function<String, Builder> KEY_BUILDER =
            value -> Key.newBuilder().ed25519(Bytes.wrap(value.getBytes()));
    private static final Key A_THRESHOLD_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    KEY_BUILDER.apply(C_NAME).build())
                            .build()))
            .build();
    private static final Key A_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_THRESHOLD_KEY)))
            .build();
    private static final Key B_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_COMPLEX_KEY)))
            .build();
    public static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();
    protected static final long SHARD =
            DEFAULT_CONFIG.getConfigData(HederaConfig.class).shard();
    protected static final long REALM =
            DEFAULT_CONFIG.getConfigData(HederaConfig.class).realm();
    protected EntityIdFactory idFactory = new FakeEntityIdFactoryImpl(SHARD, REALM);

    protected final Key key = A_COMPLEX_KEY;
    protected final Key anotherKey = B_COMPLEX_KEY;

    protected final Bytes defaultAdminKeyBytes =
            Bytes.wrap("0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92");

    final Key invalidKey = Key.newBuilder()
            .ecdsaSecp256k1((Bytes.fromHex("0000000000000000000000000000000000000000")))
            .build();
    protected final AccountID accountId = idFactory.newAccountId(3);

    protected final AccountID payerId = idFactory.newAccountId(2);
    protected final byte[] grpcCertificateHash = "grpcCertificateHash".getBytes();
    protected final byte[] gossipCaCertificate = "gossipCaCertificate".getBytes();
    protected final long WELL_KNOWN_NODE_ID = 1L;
    protected final EntityNumber nodeId =
            EntityNumber.newBuilder().number(WELL_KNOWN_NODE_ID).build();
    protected final EntityNumber nodeId2 = EntityNumber.newBuilder().number(3L).build();
    protected final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();

    protected static final Key aPrimitiveKey = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    protected static final ProtoBytes edKeyAlias = new ProtoBytes(Bytes.wrap(asBytes(Key.PROTOBUF, aPrimitiveKey)));
    protected final AccountID alias = idFactory.newAccountIdWithAlias(edKeyAlias.value());

    protected final ServiceEndpoint endpoint1 = V053AddressBookSchema.endpointFor("127.0.0.1", 1234);

    protected final ServiceEndpoint endpoint2 = V053AddressBookSchema.endpointFor("127.0.0.2", 2345);

    protected final ServiceEndpoint endpoint3 = V053AddressBookSchema.endpointFor("test.domain.com", 3456);

    protected final ServiceEndpoint endpoint4 = V053AddressBookSchema.endpointFor("test.domain.com", 2345)
            .copyBuilder()
            .ipAddressV4(endpoint1.ipAddressV4())
            .build();

    protected final ServiceEndpoint endpoint5 = new ServiceEndpoint(Bytes.EMPTY, 2345, null);

    protected final ServiceEndpoint endpoint6 = new ServiceEndpoint(Bytes.EMPTY, 0, null);
    protected final ServiceEndpoint endpoint7 = new ServiceEndpoint(null, 123, null);

    protected final ServiceEndpoint endpoint8 = new ServiceEndpoint(Bytes.wrap("345.0.0.1"), 1234, null);
    protected final ServiceEndpoint endpoint9 = new ServiceEndpoint(Bytes.wrap("1.0.0.0"), 1234, null);

    private final byte[] invalidIPBytes = {49, 46, 48, 46, 48, 46, 48};
    protected final ServiceEndpoint endpoint10 = new ServiceEndpoint(Bytes.wrap(invalidIPBytes), 1234, null);

    protected Node node;

    @Mock
    protected ReadableStates readableStates;

    @Mock
    protected WritableStates writableStates;

    protected ReadableEntityIdStore readableEntityCounters;
    protected WritableEntityIdStore writableEntityCounters;

    protected MapReadableKVState<EntityNumber, Node> readableNodeState;
    protected MapWritableKVState<EntityNumber, Node> writableNodeState;

    protected ReadableNodeStore readableStore;
    protected WritableNodeStore writableStore;

    @BeforeEach
    void commonSetUp() {
        givenValidNode();
        refreshStoresWithCurrentNodeInReadable();
    }

    protected void refreshStoresWithCurrentNodeInReadable() {
        givenEntityCountersWithOneNodeInWritable();
        readableNodeState = readableNodeState();
        writableNodeState = emptyWritableNodeState();
        given(readableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(readableNodeState);
        given(writableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(writableNodeState);
        readableStore = new ReadableNodeStoreImpl(readableStates, readableEntityCounters);
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
    }

    protected void givenEntityCounters() {
        given(writableStates.getSingleton(ENTITY_ID_STATE_KEY))
                .willReturn(new FunctionWritableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_ID_STATE_KEY,
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}));
        given(writableStates.getSingleton(ENTITY_COUNTS_KEY))
                .willReturn(new FunctionWritableSingletonState<>(
                        EntityIdService.NAME, ENTITY_COUNTS_KEY, () -> EntityCounts.DEFAULT, c -> {}));
        given(readableStates.getSingleton(ENTITY_ID_STATE_KEY))
                .willReturn(new FunctionReadableSingletonState<>(
                        EntityIdService.NAME, ENTITY_ID_STATE_KEY, () -> EntityNumber.newBuilder()
                                .build()));
        given(readableStates.getSingleton(ENTITY_COUNTS_KEY))
                .willReturn(new FunctionReadableSingletonState<>(
                        EntityIdService.NAME, ENTITY_COUNTS_KEY, () -> EntityCounts.DEFAULT));
        readableEntityCounters = new ReadableEntityIdStoreImpl(readableStates);
        writableEntityCounters = new WritableEntityIdStore(writableStates);
    }

    protected void givenEntityCountersWithOneNodeInWritable() {
        given(writableStates.getSingleton(ENTITY_ID_STATE_KEY))
                .willReturn(new FunctionWritableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_ID_STATE_KEY,
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}));
        given(writableStates.getSingleton(ENTITY_COUNTS_KEY))
                .willReturn(new FunctionWritableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_KEY,
                        () -> EntityCounts.newBuilder().numNodes(1).build(),
                        c -> {}));
        given(readableStates.getSingleton(ENTITY_ID_STATE_KEY))
                .willReturn(new FunctionReadableSingletonState<>(
                        EntityIdService.NAME, ENTITY_ID_STATE_KEY, () -> EntityNumber.newBuilder()
                                .build()));
        given(readableStates.getSingleton(ENTITY_COUNTS_KEY))
                .willReturn(new FunctionReadableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_KEY,
                        () -> EntityCounts.newBuilder().numNodes(1).build()));
        readableEntityCounters = new ReadableEntityIdStoreImpl(readableStates);
        writableEntityCounters = new WritableEntityIdStore(writableStates);
    }

    protected void refreshStoresWithCurrentNodeInBothReadableAndWritable() {
        givenEntityCountersWithOneNodeInWritable();
        readableNodeState = readableNodeState();
        writableNodeState = writableNodeStateWithOneKey();
        given(readableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(readableNodeState);
        given(writableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(writableNodeState);
        readableStore = new ReadableNodeStoreImpl(readableStates, readableEntityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
    }

    protected void refreshStoresWithCurrentNodeInWritable() {
        givenEntityCountersWithOneNodeInWritable();
        writableEntityCounters.incrementEntityTypeCount(EntityType.NODE);
        writableNodeState = writableNodeStateWithOneKey();
        given(writableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(writableNodeState);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
    }

    protected void refreshStoresWithMoreNodeInWritable() {
        givenEntityCounters();
        writableNodeState = writableNodeStateWithMoreKeys();
        given(writableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(writableNodeState);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
    }

    @NonNull
    protected MapWritableKVState<EntityNumber, Node> emptyWritableNodeState() {
        return MapWritableKVState.<EntityNumber, Node>builder(AddressBookService.NAME, NODES_KEY)
                .build();
    }

    @NonNull
    protected MapWritableKVState<EntityNumber, Node> writableNodeStateWithOneKey() {
        return MapWritableKVState.<EntityNumber, Node>builder(AddressBookService.NAME, NODES_KEY)
                .value(nodeId, node)
                .build();
    }

    @NonNull
    protected MapWritableKVState<EntityNumber, Node> writableNodeStateWithMoreKeys() {
        return MapWritableKVState.<EntityNumber, Node>builder(AddressBookService.NAME, NODES_KEY)
                .value(nodeId, node)
                .value(nodeId2, mock(Node.class))
                .build();
    }

    @NonNull
    protected MapReadableKVState<EntityNumber, Node> readableNodeState() {
        return MapReadableKVState.<EntityNumber, Node>builder(AddressBookService.NAME, NODES_KEY)
                .value(nodeId, node)
                .build();
    }

    @NonNull
    protected MapReadableKVState.Builder<EntityNumber, Node> emptyReadableNodeStateBuilder() {
        return MapReadableKVState.builder(AddressBookService.NAME, NODES_KEY);
    }

    protected void givenValidNode() {
        givenValidNode(false);
    }

    protected void givenValidNode(boolean deleted) {
        node = new Node(
                nodeId.number(),
                accountId,
                "description",
                null,
                null,
                Bytes.wrap(gossipCaCertificate),
                Bytes.wrap(grpcCertificateHash),
                0,
                deleted,
                key,
                false,
                null);
    }

    protected void givenValidNodeWithAdminKey(Key adminKey) {
        node = new Node(
                nodeId.number(),
                accountId,
                "description",
                null,
                null,
                Bytes.wrap(gossipCaCertificate),
                Bytes.wrap(grpcCertificateHash),
                0,
                false,
                adminKey,
                false,
                null);
    }

    protected Node createNode() {
        return new Node.Builder()
                .nodeId(nodeId.number())
                .accountId(accountId)
                .description("description")
                .gossipEndpoint((List<ServiceEndpoint>) null)
                .serviceEndpoint((List<ServiceEndpoint>) null)
                .gossipCaCertificate(Bytes.wrap(gossipCaCertificate))
                .grpcCertificateHash(Bytes.wrap(grpcCertificateHash))
                .weight(0)
                .adminKey(key)
                .build();
    }

    protected Key mockPayerLookup(Key key, AccountID contextPayerId, ReadableAccountStore accountStore) {
        final var account = mock(Account.class);
        given(account.key()).willReturn(key);
        given(accountStore.getAccountById(contextPayerId)).willReturn(account);
        return key;
    }

    public static List<X509Certificate> generateX509Certificates(final int n) {
        final var randomAddressBook = RandomAddressBookBuilder.create(new Random())
                .withSize(n)
                .withRealKeysEnabled(true)
                .build();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(randomAddressBook.iterator(), 0), false)
                .map(Address::getSigCert)
                .collect(Collectors.toList());
    }
}

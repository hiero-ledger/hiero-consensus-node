// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.asBytes;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_LABEL;
import static com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema.ACCOUNT_NODE_REL_STATE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema.ACCOUNT_NODE_REL_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0700EntityIdSchema.NODE_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0700EntityIdSchema.NODE_ID_STATE_LABEL;
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
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.service.addressbook.ReadableAccountNodeRelStore;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.ReadableAccountNodeRelStoreImpl;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.addressbook.impl.WritableAccountNodeRelStore;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
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
import java.util.function.Function;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
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

    protected final long WELL_KNOWN_NODE_ID = 1L;
    protected final long WELL_KNOWN_ACCOUNT_ID = 3L;

    protected final AccountID accountId = idFactory.newAccountId(WELL_KNOWN_ACCOUNT_ID);

    protected final AccountID payerId = idFactory.newAccountId(2);
    protected final byte[] grpcCertificateHash = "grpcCertificateHash".getBytes();
    protected final byte[] gossipCaCertificate = "gossipCaCertificate".getBytes();

    protected final EntityNumber nodeId =
            EntityNumber.newBuilder().number(WELL_KNOWN_NODE_ID).build();
    protected final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();

    protected static final Key aPrimitiveKey = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    protected static final Key bPrimitiveKey = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789098765432101"))
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
    protected Account account;

    @Mock
    protected ReadableStates readableStates;

    @Mock
    protected WritableStates writableStates;

    @Mock
    protected ExpiryValidator expiryValidator;

    protected ReadableEntityIdStore readableEntityCounters;
    protected WritableEntityIdStoreImpl writableEntityCounters;

    protected MapReadableKVState<EntityNumber, Node> readableNodeState;
    protected MapWritableKVState<EntityNumber, Node> writableNodeState;

    protected ReadableNodeStore readableStore;
    protected WritableNodeStore writableStore;

    protected MapReadableKVState<AccountID, NodeId> readableAccountNodeRelState;
    protected MapWritableKVState<AccountID, NodeId> writableAccountNodeRelState;
    protected ReadableAccountNodeRelStore readableAccountNodeRelStore;
    protected WritableAccountNodeRelStore writableAccountNodeRelStore;

    @BeforeEach
    void commonSetUp() {
        givenValidNode();
        rebuildState(1);
    }

    protected void givenEntityCounters(int num) {
        given(writableStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID,
                        ENTITY_ID_STATE_LABEL,
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}));
        given(writableStates.getSingleton(NODE_ID_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        NODE_ID_STATE_ID,
                        NODE_ID_STATE_LABEL,
                        () -> NodeId.newBuilder().id(num).build(),
                        c -> {}));
        given(writableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().numNodes(num).build(),
                        c -> {}));
        given(readableStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                                .build()));
        given(readableStates.getSingleton(NODE_ID_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        NODE_ID_STATE_ID,
                        NODE_ID_STATE_LABEL,
                        () -> NodeId.newBuilder().id(num).build()));
        given(readableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().numNodes(num).build()));
        readableEntityCounters = new ReadableEntityIdStoreImpl(readableStates);
        writableEntityCounters = new WritableEntityIdStoreImpl(writableStates);
    }

    /**
     * Creates node and account-node relationship states for testing.
     * <p>
     * This method initializes both readable and writable states for nodes and their
     * account relationships, populating them with the specified number of nodes.
     * </p>
     *
     * @param nodeCount The number of nodes to include in the states.
     *                  If 1 or greater, the first node will be the predefined
     *                  WELL_KNOWN_NODE associated with WELL_KNOWN_ACCOUNT.
     */
    protected void rebuildState(int nodeCount) {
        givenEntityCounters(nodeCount);
        readableNodeState = readableNodeStateBuilder(nodeCount).build();
        writableNodeState = writableNodeStateBuilder(nodeCount).build();
        readableAccountNodeRelState = readableAccNodeRelState(nodeCount);
        writableAccountNodeRelState = writableAccNodeRelState(nodeCount);

        given(readableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(readableNodeState);
        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(writableNodeState);
        given(readableStates.<AccountID, NodeId>get(ACCOUNT_NODE_REL_STATE_ID)).willReturn(readableAccountNodeRelState);
        given(writableStates.<AccountID, NodeId>get(ACCOUNT_NODE_REL_STATE_ID)).willReturn(writableAccountNodeRelState);

        readableStore = new ReadableNodeStoreImpl(readableStates, readableEntityCounters);
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
        readableAccountNodeRelStore = new ReadableAccountNodeRelStoreImpl(readableStates);
        writableAccountNodeRelStore = new WritableAccountNodeRelStore(writableStates);
    }

    @NonNull
    protected MapReadableKVState.Builder<EntityNumber, Node> readableNodeStateBuilder(int nodeCount) {
        final var builder = MapReadableKVState.<EntityNumber, Node>builder(NODES_STATE_ID, NODES_STATE_LABEL);
        if (nodeCount >= 1) {
            // add current node
            builder.value(nodeId, node);
            // fill the rest nodes
            for (int i = 1; i < nodeCount; i++) {
                builder.value(
                        EntityNumber.newBuilder().number(i + WELL_KNOWN_NODE_ID).build(), mock(Node.class));
            }
        }
        return builder;
    }

    @NonNull
    protected MapWritableKVState.Builder<EntityNumber, Node> writableNodeStateBuilder(int nodeCount) {
        final var builder = MapWritableKVState.<EntityNumber, Node>builder(NODES_STATE_ID, NODES_STATE_LABEL);
        if (nodeCount >= 1) {
            // add current node
            builder.value(nodeId, node);
            // fill the rest nodes
            for (int i = 1; i < nodeCount; i++) {
                builder.value(
                        EntityNumber.newBuilder().number(i + WELL_KNOWN_NODE_ID).build(), mock(Node.class));
            }
        }
        return builder;
    }

    @NonNull
    protected MapReadableKVState<AccountID, NodeId> readableAccNodeRelState(int nodeCount) {
        final var builder =
                MapReadableKVState.<AccountID, NodeId>builder(ACCOUNT_NODE_REL_STATE_ID, ACCOUNT_NODE_REL_STATE_LABEL);
        if (nodeCount >= 1) {
            // add current node if the node is not deleted
            if (node != null) {
                builder.value(
                        node.accountId(), NodeId.newBuilder().id(node.nodeId()).build());
            }
            // fill the rest nodes
            for (int i = 1; i < nodeCount; i++) {
                builder.value(
                        AccountID.newBuilder()
                                .accountNum(i + WELL_KNOWN_ACCOUNT_ID)
                                .build(),
                        NodeId.newBuilder().id(i + WELL_KNOWN_NODE_ID).build());
            }
        }
        return builder.build();
    }

    @NonNull
    protected MapWritableKVState<AccountID, NodeId> writableAccNodeRelState(int nodeCount) {
        final var builder =
                MapWritableKVState.<AccountID, NodeId>builder(ACCOUNT_NODE_REL_STATE_ID, ACCOUNT_NODE_REL_STATE_LABEL);
        if (nodeCount >= 1) {
            // add current node if the node is not deleted
            if (node != null) {
                builder.value(
                        node.accountId(), NodeId.newBuilder().id(node.nodeId()).build());
            }
            // fill the rest nodes
            for (int i = 1; i < nodeCount; i++) {
                builder.value(
                        AccountID.newBuilder()
                                .accountNum(i + WELL_KNOWN_ACCOUNT_ID)
                                .build(),
                        NodeId.newBuilder().id(i + WELL_KNOWN_NODE_ID).build());
            }
        }
        return builder.build();
    }

    protected void givenValidNode() {
        this.givenValidNode(false);
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

    protected void mockAccountLookup(Key key, AccountID contextPayerId, ReadableAccountStore accountStore) {
        final var account = mock(Account.class);
        given(account.key()).willReturn(key);
        given(accountStore.getAccountById(contextPayerId)).willReturn(account);
    }

    protected void mockAccountKeyOrThrow(Key key, AccountID contextPayerId, ReadableAccountStore accountStore) {
        final var account = mock(Account.class);
        given(account.keyOrThrow()).willReturn(key);
        given(accountStore.getAccountById(contextPayerId)).willReturn(account);
    }

    public static List<X509Certificate> generateX509Certificates(final int n) {
        final var roster = RandomRosterBuilder.create(new Random())
                .withRealKeysEnabled(true)
                .withSize(n)
                .build();

        return roster.rosterEntries().stream()
                .map(RosterUtils::fetchGossipCaCertificate)
                .toList();
    }
}

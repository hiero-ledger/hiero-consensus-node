// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_NODES_CREATED;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.checkDABEnabled;
import static com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator.validateX509Certificate;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.SYSTEM_TXN_CREATION_ENTITY_NUM;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableAccountNodeRelStore;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.records.NodeCreateStreamBuilder;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.service.entityid.NodeIdGenerator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#NODE_CREATE}.
 * This is a privileged(Needs signatures from 2-50 )
 */
@Singleton
public class NodeCreateHandler implements TransactionHandler {
    private final AddressBookValidator addressBookValidator;

    /**
     * Constructs a {@link NodeCreateHandler} with the given {@link AddressBookValidator}.
     *
     * @param addressBookValidator the validator for the crypto create transaction
     */
    @Inject
    public NodeCreateHandler(@NonNull final AddressBookValidator addressBookValidator) {
        this.addressBookValidator =
                requireNonNull(addressBookValidator, "The supplied argument 'addressBookValidator' must not be null");
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.nodeCreateOrThrow();
        addressBookValidator.validateAccountId(op.accountId());
        validateFalsePreCheck(op.gossipEndpoint().isEmpty(), INVALID_GOSSIP_ENDPOINT);
        validateFalsePreCheck(op.serviceEndpoint().isEmpty(), INVALID_SERVICE_ENDPOINT);
        validateFalsePreCheck(
                op.gossipCaCertificate().length() == 0
                        || op.gossipCaCertificate().equals(Bytes.EMPTY),
                INVALID_GOSSIP_CA_CERTIFICATE);
        validateX509Certificate(op.gossipCaCertificate());
        final var adminKey = op.adminKey();
        addressBookValidator.validateAdminKey(adminKey);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().nodeCreateOrThrow();
        context.requireKeyOrThrow(op.adminKeyOrThrow(), INVALID_ADMIN_KEY);
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) {
        requireNonNull(handleContext);
        final var op = handleContext.body().nodeCreateOrThrow();
        final var nodeConfig = handleContext.configuration().getConfigData(NodesConfig.class);
        final var storeFactory = handleContext.storeFactory();
        final var nodeStore = storeFactory.writableStore(WritableNodeStore.class);
        final var accountNodeRelStore = storeFactory.writableStore(WritableAccountNodeRelStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);
        final var registeredNodeStore = storeFactory.readableStore(ReadableRegisteredNodeStore.class);
        final var accountId = op.accountIdOrElse(AccountID.DEFAULT);
        final var maybeSystemTxnDispatchEntityNum =
                handleContext.dispatchMetadata().getMetadata(SYSTEM_TXN_CREATION_ENTITY_NUM, Long.class);
        final var maybeNodeIsInStateForSystemTxn =
                isNodeInStateForSystemTxn(handleContext.dispatchMetadata(), handleContext.nodeIdGenerator(), nodeStore);
        validateTrue(
                maybeNodeIsInStateForSystemTxn || (nodeStore.sizeOfState() < nodeConfig.maxNumber()),
                MAX_NODES_CREATED);
        addressBookValidator.validateAccount(
                accountId, accountStore, accountNodeRelStore, handleContext.expiryValidator());
        addressBookValidator.validateDescription(op.description(), nodeConfig);
        addressBookValidator.validateGossipEndpoint(op.gossipEndpoint(), nodeConfig);
        addressBookValidator.validateServiceEndpoint(op.serviceEndpoint(), nodeConfig);
        if (op.hasGrpcProxyEndpoint()) {
            validateTrue(nodeConfig.webProxyEndpointsEnabled(), GRPC_WEB_PROXY_NOT_SUPPORTED);
            addressBookValidator.validateFqdnEndpoint(op.grpcProxyEndpoint(), nodeConfig);
        }
        handleContext.attributeValidator().validateKey(op.adminKeyOrThrow(), INVALID_ADMIN_KEY);

        validateTrue(op.associatedRegisteredNode().size() <= 20, INVALID_NODE_ID);
        for (final var registeredNodeId : op.associatedRegisteredNode()) {
            validateTrue(registeredNodeId >= 0, INVALID_NODE_ID);
            validateTrue(registeredNodeStore.get(registeredNodeId) != null, INVALID_NODE_ID);
        }

        final var nodeBuilder = new Node.Builder()
                .accountId(op.accountId())
                .description(op.description())
                .gossipEndpoint(op.gossipEndpoint())
                .serviceEndpoint(op.serviceEndpoint())
                .gossipCaCertificate(op.gossipCaCertificate())
                .grpcCertificateHash(op.grpcCertificateHash())
                .declineReward(op.declineReward())
                .associatedRegisteredNode(op.associatedRegisteredNode())
                .adminKey(op.adminKey());
        if (op.hasGrpcProxyEndpoint()) {
            nodeBuilder.grpcProxyEndpoint(op.grpcProxyEndpoint());
        }

        long nextNodeId;
        Node node;

        // If a system-dispatched transplant transaction for nodes in override network (non-prod environments)
        // attempts to create a node that already exists in the state (even if marked as deleted),
        // neither the highest node ID nor the entity count should be incremented.
        if (maybeNodeIsInStateForSystemTxn) {
            // Assign node id using the one provided by the system dispatch metadata
            nextNodeId = maybeSystemTxnDispatchEntityNum.get();
            node = nodeBuilder.nodeId(nextNodeId).build();
            nodeStore.put(node);
        } else {
            // Assign node id using a dedicated generator to avoid reuse
            nextNodeId = handleContext.nodeIdGenerator().newNodeId();
            node = nodeBuilder.nodeId(nextNodeId).build();
            nodeStore.putAndIncrementCount(node);
        }

        accountNodeRelStore.put(op.accountIdOrThrow(), node.nodeId());

        final var recordBuilder = handleContext.savepointStack().getBaseBuilder(NodeCreateStreamBuilder.class);

        recordBuilder.nodeID(node.nodeId());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        checkDABEnabled(feeContext);
        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        calculator.resetUsage();
        // The price of node create should be increased based on number of signatures.
        // The first signature is free and is accounted in the base price, so we only need to add
        // the price of the rest of the signatures.
        calculator.addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1));
        return calculator.calculate();
    }

    /**
     * Determines if a system-dispatched node creation transaction targets a node ID
     * that already exists in the current state.
     *
     * <p>If the dispatch metadata provides a node ID (as in system transactions), this method checks
     * if that node ID is already present in the node store. If not, it uses the next node ID from the generator.
     *
     * @param metadata the dispatch metadata containing optional system transaction node ID
     * @param nodeIdGenerator the generator for new node IDs
     * @param nodeStore the store containing current node state
     * @return {@code true} if the node ID (from metadata or generator) already exists in the state; {@code false} otherwise
     */
    private boolean isNodeInStateForSystemTxn(
            final HandleContext.DispatchMetadata metadata,
            final NodeIdGenerator nodeIdGenerator,
            final ReadableNodeStore nodeStore) {
        final var systemTxnCreationNum = metadata.getMetadataIfPresent(SYSTEM_TXN_CREATION_ENTITY_NUM, Long.class);
        final var nextNodeId = systemTxnCreationNum != null ? systemTxnCreationNum : nodeIdGenerator.peekAtNewNodeId();
        return nodeStore.get(nextNodeId) != null;
    }
}

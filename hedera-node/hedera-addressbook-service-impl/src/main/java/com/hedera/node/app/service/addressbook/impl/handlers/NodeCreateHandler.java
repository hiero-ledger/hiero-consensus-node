// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_NODES_CREATED;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.checkDABEnabled;
import static com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator.validateX509Certificate;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.records.NodeCreateStreamBuilder;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
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
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);
        final var accountId = op.accountIdOrElse(AccountID.DEFAULT);

        validateFalse(nodeStore.sizeOfState() >= nodeConfig.maxNumber(), MAX_NODES_CREATED);
        validateTrue(accountStore.contains(accountId), INVALID_NODE_ACCOUNT_ID);
        addressBookValidator.validateDescription(op.description(), nodeConfig);
        addressBookValidator.validateGossipEndpoint(op.gossipEndpoint(), nodeConfig);
        addressBookValidator.validateServiceEndpoint(op.serviceEndpoint(), nodeConfig);
        if (op.hasGrpcProxyEndpoint()) {
            validateTrue(nodeConfig.webProxyEndpointsEnabled(), GRPC_WEB_PROXY_NOT_SUPPORTED);
            addressBookValidator.validateFqdnEndpoint(op.grpcProxyEndpoint(), nodeConfig);
        }
        handleContext.attributeValidator().validateKey(op.adminKeyOrThrow(), INVALID_ADMIN_KEY);

        final var nodeBuilder = new Node.Builder()
                .accountId(op.accountId())
                .description(op.description())
                .gossipEndpoint(op.gossipEndpoint())
                .serviceEndpoint(op.serviceEndpoint())
                .gossipCaCertificate(op.gossipCaCertificate())
                .grpcCertificateHash(op.grpcCertificateHash())
                .declineReward(op.declineReward())
                .adminKey(op.adminKey());
        if (op.hasGrpcProxyEndpoint()) {
            nodeBuilder.grpcProxyEndpoint(op.grpcProxyEndpoint());
        }

        // Since nodes won't be removed from state, we can set the nodeId to the next available id
        // in the state based on the size of the state.
        final var node = nodeBuilder.nodeId(nodeStore.sizeOfState()).build();

        nodeStore.putAndIncrementCount(node);

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
}

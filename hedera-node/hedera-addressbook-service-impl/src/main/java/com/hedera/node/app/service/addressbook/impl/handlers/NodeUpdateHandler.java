// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GRPC_CERTIFICATE_HASH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UPDATE_NODE_ACCOUNT_NOT_ALLOWED;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.checkDABEnabled;
import static com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator.validateX509Certificate;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
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
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#NODE_UPDATE}.
 */
@Singleton
public class NodeUpdateHandler implements TransactionHandler {
    private final AddressBookValidator addressBookValidator;

    @Inject
    public NodeUpdateHandler(@NonNull final AddressBookValidator addressBookValidator) {
        this.addressBookValidator =
                requireNonNull(addressBookValidator, "The supplied argument 'addressBookValidator' must not be null");
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.nodeUpdateOrThrow();
        validateFalsePreCheck(op.nodeId() < 0, INVALID_NODE_ID);
        if (op.hasGossipCaCertificate()) {
            validateFalsePreCheck(op.gossipCaCertificate().equals(Bytes.EMPTY), INVALID_GOSSIP_CA_CERTIFICATE);
            validateX509Certificate(op.gossipCaCertificate());
        }
        if (op.hasAdminKey()) {
            final var adminKey = op.adminKey();
            addressBookValidator.validateAdminKey(adminKey);
        }
        if (op.hasGrpcCertificateHash()) {
            validateFalsePreCheck(op.grpcCertificateHash().equals(Bytes.EMPTY), INVALID_GRPC_CERTIFICATE_HASH);
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().nodeUpdateOrThrow();
        final var nodeStore = context.createStore(ReadableNodeStore.class);
        final var config = context.configuration().getConfigData(NodesConfig.class);

        final var existingNode = nodeStore.get(op.nodeId());
        validateFalsePreCheck(existingNode == null, INVALID_NODE_ID);
        validateFalsePreCheck(existingNode.deleted(), INVALID_NODE_ID);

        context.requireKeyOrThrow(existingNode.adminKey(), INVALID_ADMIN_KEY);
        if (op.hasAdminKey()) {
            context.requireKeyOrThrow(op.adminKeyOrThrow(), INVALID_ADMIN_KEY);
        }
        if (config.updateAccountIdAllowed()) {
            if (op.hasAccountId()) {
                addressBookValidator.validateAccountId(op.accountIdOrThrow());
            }
        } else {
            validateFalsePreCheck(op.hasAccountId(), UPDATE_NODE_ACCOUNT_NOT_ALLOWED);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) {
        requireNonNull(handleContext);
        final var op = handleContext.body().nodeUpdateOrThrow();

        final var configuration = handleContext.configuration();
        final var nodeConfig = configuration.getConfigData(NodesConfig.class);
        final var storeFactory = handleContext.storeFactory();
        final var nodeStore = storeFactory.writableStore(WritableNodeStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);

        final var existingNode = nodeStore.get(op.nodeId());
        validateFalse(existingNode == null, INVALID_NODE_ID);
        if (op.hasAccountId()) {
            final var accountId = op.accountIdOrThrow();
            validateTrue(accountStore.contains(accountId), INVALID_NODE_ACCOUNT_ID);
        }
        if (op.hasDescription()) addressBookValidator.validateDescription(op.description(), nodeConfig);
        if (!op.gossipEndpoint().isEmpty()) {
            addressBookValidator.validateGossipEndpoint(op.gossipEndpoint(), nodeConfig);
        }
        if (!op.serviceEndpoint().isEmpty()) {
            addressBookValidator.validateServiceEndpoint(op.serviceEndpoint(), nodeConfig);
        }

        boolean proxyIsSentinelValue = false;
        if (op.hasGrpcProxyEndpoint()) {
            validateTrue(nodeConfig.webProxyEndpointsEnabled(), GRPC_WEB_PROXY_NOT_SUPPORTED);

            // Check for a sentinel value, which indicates that the gRPC proxy endpoint should be unset
            if (Objects.equals(op.grpcProxyEndpointOrThrow(), ServiceEndpoint.DEFAULT)) {
                proxyIsSentinelValue = true;
            } else {
                addressBookValidator.validateFqdnEndpoint(op.grpcProxyEndpoint(), nodeConfig);
            }
        }

        final var nodeBuilder = updateNode(op, existingNode, proxyIsSentinelValue);
        nodeStore.put(nodeBuilder.build());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        checkDABEnabled(feeContext);
        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        calculator.resetUsage();
        // The price of node update should be increased based on number of signatures.
        // The first signature is free and is accounted in the base price, so we only need to add
        // the price of the rest of the signatures.
        calculator.addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1));
        return calculator.calculate();
    }

    private Node.Builder updateNode(
            @NonNull final NodeUpdateTransactionBody op, @NonNull final Node node, final boolean unsetWebProxy) {
        requireNonNull(op);
        requireNonNull(node);

        final var nodeBuilder = node.copyBuilder();
        if (op.hasAccountId()) {
            nodeBuilder.accountId(op.accountId());
        }
        if (op.hasDescription()) {
            nodeBuilder.description(op.description());
        }
        if (!op.gossipEndpoint().isEmpty()) {
            nodeBuilder.gossipEndpoint(op.gossipEndpoint());
        }
        if (!op.serviceEndpoint().isEmpty()) {
            nodeBuilder.serviceEndpoint(op.serviceEndpoint());
        }
        if (op.hasGossipCaCertificate()) {
            nodeBuilder.gossipCaCertificate(op.gossipCaCertificate());
        }
        if (op.hasGrpcCertificateHash()) {
            nodeBuilder.grpcCertificateHash(op.grpcCertificateHash());
        }
        if (op.hasAdminKey()) {
            nodeBuilder.adminKey(op.adminKey());
        }
        if (op.hasDeclineReward()) {
            nodeBuilder.declineReward(op.declineReward());
        }
        if (op.hasGrpcProxyEndpoint()) {
            nodeBuilder.grpcProxyEndpoint(unsetWebProxy ? null : op.grpcProxyEndpoint());
        }
        return nodeBuilder;
    }
}

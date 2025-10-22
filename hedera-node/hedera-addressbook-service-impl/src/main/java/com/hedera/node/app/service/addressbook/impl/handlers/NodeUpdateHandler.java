// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GRPC_CERTIFICATE_HASH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UPDATE_NODE_ACCOUNT_NOT_ALLOWED;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.checkDABEnabled;
import static com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator.validateX509Certificate;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableAccountNodeRelStore;
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
import java.util.Arrays;
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

        if (op.hasAccountId()) {
            validateTruePreCheck(config.updateAccountIdAllowed(), UPDATE_NODE_ACCOUNT_NOT_ALLOWED);
            final var newAccountId = op.accountIdOrThrow();
            addressBookValidator.validateAccountId(newAccountId);
            context.requireKeyOrThrow(newAccountId, INVALID_SIGNATURE);
            // On updating only the account ID, require the existing account or the admin key signature
            if (onlyUpdatesAccountID(op)) {
                handleAccountIdOnlyUpdate(context, existingNode);
                return;
            }
        }

        context.requireKeyOrThrow(existingNode.adminKey(), INVALID_ADMIN_KEY);
        if (op.hasAdminKey()) {
            context.requireKeyOrThrow(op.adminKeyOrThrow(), INVALID_ADMIN_KEY);
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
        final var accountNodeRelStore = storeFactory.writableStore(WritableAccountNodeRelStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);

        final var existingNode = nodeStore.get(op.nodeId());
        validateFalse(existingNode == null, INVALID_NODE_ID);
        if (op.hasAccountId()) {
            final var accountId = op.accountIdOrThrow();
            validateTrue(accountStore.contains(accountId), INVALID_NODE_ACCOUNT_ID);
            if (!accountId.equals(existingNode.accountId())) {
                // update account node relation
                accountNodeRelStore.remove(existingNode.accountId());
                accountNodeRelStore.put(accountId, existingNode.nodeId());
            }
        }
        if (op.hasDescription()) {
            addressBookValidator.validateDescription(op.description(), nodeConfig);
        }
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

    private void handleAccountIdOnlyUpdate(PreHandleContext context, Node existingNode) throws PreCheckException {
        if (existingNode.hasAccountId()) {
            // Allow signature from either admin key or existing account key
            Key requiredKey = oneOf(existingNode.adminKey(), context.getAccountKey(existingNode.accountIdOrThrow()));
            context.requireKeyOrThrow(requiredKey, INVALID_SIGNATURE);
        } else {
            // No account ID exists, so only admin key signature is acceptable
            context.requireKeyOrThrow(existingNode.adminKey(), INVALID_ADMIN_KEY);
        }
    }

    private boolean onlyUpdatesAccountID(@NonNull final NodeUpdateTransactionBody op) {
        return op.hasAccountId()
                && !op.hasDescription()
                && !op.hasAdminKey()
                && op.gossipEndpoint().isEmpty()
                && op.serviceEndpoint().isEmpty()
                && !op.hasGossipCaCertificate()
                && !op.hasGrpcCertificateHash()
                && !op.hasDeclineReward()
                && !op.hasGrpcProxyEndpoint();
    }

    /**
     * Creates a threshold key with threshold 1 with the given keys.
     * @param acceptedKeys all keys that can individually authorize an operation
     * @return threshold key with threshold 1
     */
    private static Key oneOf(@NonNull final Key... acceptedKeys) {
        return Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .keys(new KeyList(Arrays.asList(acceptedKeys)))
                        .threshold(1)
                        .build())
                .build();
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_HAS_IN_FLIGHT_MESSAGES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_UNAUTHORIZED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.service.clpr.ReadableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_DEREGISTER_CONNECTOR} transactions.
 *
 * <p>Removes a Connector from the CLPR Service and returns any locked stake
 * to the explicitly specified stake_recipient. Requires the connector's admin_key to sign.
 */
@Singleton
public class ClprDeregisterConnectorHandler extends AbstractClprHandler {

    private final EntityIdFactory entityIdFactory;

    @Inject
    public ClprDeregisterConnectorHandler(@NonNull final EntityIdFactory entityIdFactory) {
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprDeregisterConnectorOrThrow();
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.connectorId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasStakeRecipient(), INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprDeregisterConnectorOrThrow();
        final var connectorStore = context.createStore(ReadableConnectorStore.class);
        final var key = new ClprConnectorKey(op.channelId(), op.connectorId());
        final var connector = connectorStore.getConnector(key);
        validateTruePreCheck(connector != null, CLPR_CONNECTOR_NOT_FOUND);
        context.requireKeyOrThrow(connector.adminKeyOrElse(null), CLPR_CONNECTOR_UNAUTHORIZED);
        context.requireKeyOrThrow(op.stakeRecipientOrThrow(), CLPR_CONNECTOR_UNAUTHORIZED);
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprDeregisterConnectorOrThrow();
        final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
        final var key = new ClprConnectorKey(op.channelId(), op.connectorId());
        final var storeFactory = context.storeFactory();
        final var connectorStore = storeFactory.writableStore(WritableConnectorStore.class);
        final var connector = connectorStore.getConnector(key);
        validateTrue(connector != null, CLPR_CONNECTOR_NOT_FOUND);
        validateTrue(connector.inFlightMessageCount() == 0, CLPR_CONNECTOR_HAS_IN_FLIGHT_MESSAGES);

        final var lockedStake = connector.lockedStake();
        if (lockedStake > 0) {
            final var stakingAccountId = entityIdFactory.newAccountId(clprConfig.stakingAccount());
            storeFactory
                    .serviceApi(TokenServiceApi.class)
                    .transferFromTo(stakingAccountId, op.stakeRecipientOrThrow(), lockedStake);
        }

        connectorStore.remove(key);
    }
}

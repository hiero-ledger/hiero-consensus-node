// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator.ReplyValidation;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import com.hedera.node.app.spi.workflows.ClprDispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.app.workflows.query.QueryContextImpl;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 * Verifies inbound sync bundles locally before the node pays to submit them for consensus.
 * All proof execution and validation use the same read-only state view and cannot charge fees.
 */
@Singleton
public class ClprBundlePrevalidator {
    private static final ClprDispatchMetadata CLPR_DISPATCH_METADATA =
            new ClprDispatchMetadata(CLPR_SERVICE_ACCOUNT_ID, CLPR_EVM_ADDRESS_BYTES);

    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final ClprBundleValidator bundleValidator;
    private final ClprVerifierFactory verifierFactory;
    private final Supplier<ContractHandlers> contractHandlers;
    private final RecordCache recordCache;
    private final ExchangeRateManager exchangeRateManager;
    private final FeeManager feeManager;
    private final Provider<ClprChannelLifecycle> channelLifecycle;

    @Inject
    public ClprBundlePrevalidator(
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final ClprBundleValidator bundleValidator,
            @NonNull final ClprVerifierFactory verifierFactory,
            @NonNull final Supplier<ContractHandlers> contractHandlers,
            @NonNull final RecordCache recordCache,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final FeeManager feeManager,
            @NonNull final Provider<ClprChannelLifecycle> channelLifecycle) {
        this.stateAccessor = requireNonNull(stateAccessor);
        this.bundleValidator = requireNonNull(bundleValidator);
        this.verifierFactory = requireNonNull(verifierFactory);
        this.contractHandlers = requireNonNull(contractHandlers);
        this.recordCache = requireNonNull(recordCache);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.feeManager = requireNonNull(feeManager);
        this.channelLifecycle = requireNonNull(channelLifecycle);
    }

    /**
     * Rejects an inadmissible channel, invalid proof, or bundle that would fail node-level handling.
     * Consensus must repeat these checks because channel state can advance after this call.
     */
    public void validate(@NonNull final ClprSyncPayload payload, @NonNull final Configuration configuration)
            throws PreCheckException {
        requireNonNull(payload);
        requireNonNull(configuration);
        final var config = configuration.getConfigData(ClprConfig.class);
        validateTruePreCheck(config.enabled(), CLPR_NOT_ENABLED);
        validateTruePreCheck(payload.channelId().length() == 32, INVALID_TRANSACTION_BODY);
        try (final var stateWrapper = stateAccessor.get()) {
            final var state = requireNonNull(stateWrapper.get());
            final var stores = new ReadableStoreFactoryImpl(state);
            final var channel = stores.readableStore(ReadableChannelStore.class).getChannel(payload.channelId());
            validateTruePreCheck(channel != null, CLPR_CHANNEL_NOT_FOUND);
            validateTruePreCheck(
                    channel.status() != ClprChannelStatus.CLOSED && channel.status() != ClprChannelStatus.PENDING,
                    CLPR_INVALID_CHANNEL_STATUS);
            final var ledgerConfig =
                    stores.readableStore(ReadableLedgerConfigurationStore.class).getConfiguration();
            bundleValidator.validatePayloadSize(payload.bundlePayload(), ledgerConfig.throttlesOrThrow());
            final var verifier = verifierFactory.getVerifier(channel.verifierContractOrThrow());
            final var content = verifier.verifyBundleLocally(
                    payload.bundlePayload(),
                    channel.trustAnchor(),
                    channel.channelContext(),
                    callData ->
                            callVerifier(state, stores, channel, configuration, config.verifierGasLimit(), callData));
            bundleValidator.validateTrustAnchor(content);
            // Pure acks may be rejected as NoProgress. Still retain the peer's proven view of our
            // manifest so the sync runtime does not keep resending an already observed manifest.
            if (content.metadata() != null) {
                channelLifecycle
                        .get()
                        .recordPeerObservedManifestVersion(
                                channel.channelId(), content.metadataOrThrow().endpointManifestVersion());
            }
            final var validated = bundleValidator.validate(channel, content, ledgerConfig);
            final var replies = bundleValidator.validateReplies(
                    channel, validated, stores.readableStore(ReadableMessageQueueStore.class));
            validateTruePreCheck(
                    !(replies instanceof ReplyValidation.OrderingViolated), CLPR_BUNDLE_VERIFICATION_FAILED);
        } catch (final HandleException e) {
            throw new PreCheckException(e.getStatus());
        }
    }

    private Bytes callVerifier(
            @NonNull final State state,
            @NonNull final ReadableStoreFactory stores,
            @NonNull final ClprChannel channel,
            @NonNull final Configuration configuration,
            final long gasLimit,
            @NonNull final Bytes callData) {
        final var query = Query.newBuilder()
                .contractCallLocal(ContractCallLocalQuery.newBuilder()
                        .contractID(channel.verifierContractOrThrow())
                        .gas(gasLimit)
                        .functionParameters(callData))
                .build();
        final var context =
                new QueryContextImpl(
                        state,
                        stores,
                        query,
                        configuration,
                        recordCache,
                        exchangeRateManager,
                        feeManager,
                        CLPR_SERVICE_ACCOUNT_ID) {
                    @Override
                    public ClprDispatchMetadata clprDispatchMetadata() {
                        return CLPR_DISPATCH_METADATA;
                    }
                };
        // Execute the read-only query directly, without the paid query/submission workflow. Native
        // CLPR gas and calldata rules are enforced by the query's static transaction factory.
        final var response = contractHandlers
                .get()
                .contractCallLocalHandler()
                .findResponse(
                        context,
                        ResponseHeader.newBuilder()
                                .nodeTransactionPrecheckCode(SUCCESS)
                                .build())
                .contractCallLocalOrThrow();
        if (response.headerOrThrow().nodeTransactionPrecheckCode() != SUCCESS || !response.hasFunctionResult()) {
            throw new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED);
        }
        return response.functionResultOrThrow().contractCallResult();
    }
}

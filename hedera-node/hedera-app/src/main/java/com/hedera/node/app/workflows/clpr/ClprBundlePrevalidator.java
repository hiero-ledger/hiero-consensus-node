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
 * Verifies inbound sync bundles locally before the node pays to submit them for consensus. A bundle that consensus
 * would reject is dropped here, so the node pays neither a {@code ClprSubmitBundle} transaction fee nor the endpoint
 * misbehavior penalty for it.
 *
 * <p>{@link #validate} applies the admission checks of {@code ClprSubmitBundleHandler}, verifies the proof, and runs
 * the shared {@link ClprBundleValidator} checks. The proof is verified by calling the channel's verifier contract
 * through the {@code ContractCallLocal} handler directly, bypassing the paid query workflow, with the native CLPR
 * sender and limits used by the verifier dispatch at consensus.
 *
 * <p>Every read, including the EVM call, uses the node's current working state, and nothing is written to it or
 * charged. Consensus repeats every check against the handling state, which may have advanced since, so passing here
 * does not guarantee acceptance.
 *
 * <p>The verifier runs in a static EVM frame here, but as a non-static child dispatch at consensus. A verifier whose
 * {@code verifyBundle} changes state, for example by writing storage or emitting events, therefore always fails here,
 * even when consensus would accept its result.
 */
@Singleton
public class ClprBundlePrevalidator {
    /** Native CLPR sender identity for local verifier calls; matches the verifier dispatch at consensus. */
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

    /**
     * @param stateAccessor supplies the working state that channels, queues, and verifier contracts are read from
     * @param bundleValidator the node-level bundle checks shared with consensus handling
     * @param verifierFactory resolves the verifier for a channel's verifier contract
     * @param contractHandlers supplies the {@code ContractCallLocal} handler that executes the verifier
     * @param recordCache needed to build the local query context
     * @param exchangeRateManager needed to build the local query context
     * @param feeManager needed to build the local query context
     * @param channelLifecycle records the peer's proven view of this ledger's endpoint-manifest version; resolved
     *     lazily
     */
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
     * Rejects an inadmissible channel, an invalid proof, or a bundle that would fail node-level handling.
     * Consensus must repeat these checks because channel state can advance after this call.
     *
     * <p>The checks run in this order, and the first failure is reported: CLPR is enabled; the channel id is 32 bytes;
     * the channel exists and is neither {@code CLOSED} nor {@code PENDING}; the raw payload fits
     * {@code max_sync_bytes}; the proof verifies; the successor trust-anchor invariant holds;
     * {@link ClprBundleValidator#validate} passes; and {@link ClprBundleValidator#validateReplies} passes without an
     * ordering violation.
     *
     * <p>As at consensus, once the proof and the trust-anchor invariant pass, the peer's proven view of this ledger's
     * endpoint-manifest version is recorded, even if a later check rejects the bundle. Idle peers send pure
     * acknowledgements that are rejected with {@code CLPR_NO_PROGRESS}; without this, the sync runtime would keep
     * resending a manifest the peer already has.
     *
     * @param payload the inbound sync payload
     * @param configuration the node configuration
     * @throws PreCheckException with the response code consensus handling reports for the same failure, including
     *     {@code CLPR_NO_PROGRESS} for a bundle that is valid but changes nothing. A reply ordering violation, which
     *     consensus handles by pausing the channel, is reported as {@code CLPR_BUNDLE_VERIFICATION_FAILED}. Other
     *     unexpected failures propagate as runtime exceptions, which callers must treat as a rejection.
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

    /**
     * Executes ABI-encoded call data against the channel's verifier contract as a local {@code ContractCallLocal}
     * query on {@code state}.
     *
     * <p>The query context carries the native CLPR dispatch metadata, so, as at consensus, the EVM uses the CLPR
     * service account as the sender, treats the gas as prepaid, checks intrinsic gas against the call data, allows a
     * gas limit above the public query maximum, and applies the CLPR system contract input limits. The handler is
     * invoked directly, so the call is neither throttled nor paid for.
     *
     * @param state the state to execute against
     * @param stores read-only stores over {@code state}
     * @param channel the channel whose verifier contract is called
     * @param configuration the node configuration
     * @param gasLimit the gas available to the verifier, {@code clpr.verifierGasLimit}
     * @param callData the ABI-encoded {@code verifyBundle} call
     * @return the raw EVM return bytes
     * @throws HandleException with {@code CLPR_BUNDLE_VERIFICATION_FAILED} if the call fails or reverts, or returns no
     *     result
     */
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

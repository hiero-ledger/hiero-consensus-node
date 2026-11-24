// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_NUM;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.clpr.ClprCreateChannelTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.hapi.utils.ClprChannelId;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.ClprGasAccounting;
import com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiersRegistry;
import com.hedera.node.app.service.contract.api.SmartContractServiceApi;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link com.hedera.hapi.node.base.HederaFunctionality#CLPR_CREATE_CHANNEL} transactions.
 *
 * <p>Verifies the peer configuration and binds both ledger identities and verifiers to the channel ID.
 */
@Singleton
public final class ClprCreateChannelHandler extends AbstractClprHandler {
    private static final Logger log = LoggerFactory.getLogger(ClprCreateChannelHandler.class);
    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);

    /**
     * Contract numbers of built-in system contracts that are valid verifier targets but have
     * no on-ledger account record:
     * <ul>
     *   <li>{@code 0x16e} (366) — CLPR system contract precompile.</li>
     *   <li>{@code 0x16f} (367) — Besu QBFT verifier system contract.</li>
     *   <li>{@code 0x171} (369) — Ethereum (sync-committee) verifier system contract.</li>
     *   <li>{@code 0x173} (371) — Canton (non-EVM, native verifier).</li>
     * </ul>
     * Mirrors {@code BesuQBFTVerifierSystemContract.BESU_QBFT_VERIFIER_EVM_ADDRESS} and
     * {@code EthereumVerifierSystemContract.ETHEREUM_VERIFIER_EVM_ADDRESS} in the
     * smart-contract-service-impl module for the verifier addresses.
     *
     * <p>The Sei (CometBFT) verifier system contract at {@code 0x170} is not supported
     * ({@code SeiVerifierSystemContract}), so it is not a built-in verifier: like any other address without a
     * smart-contract account, it is rejected as a verifier with {@code CLPR_INVALID_VERIFIER_CONTRACT}.
     */
    private static final long BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NUM = 0x16fL;

    /** EVM-address form of {@link #BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NUM}. */
    private static final Bytes BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS =
            Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x6f});

    /** Ethereum (sync-committee) verifier system contract; mirrors {@code ETHEREUM_VERIFIER_EVM_ADDRESS} (0x171). */
    private static final long ETHEREUM_VERIFIER_SYSTEM_CONTRACT_NUM = 0x171L;

    /** EVM-address form of {@link #ETHEREUM_VERIFIER_SYSTEM_CONTRACT_NUM}. */
    private static final Bytes ETHEREUM_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS =
            Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x71});

    /** Canton verifier reserved contract num (0x173); non-EVM, dispatched natively by the {@link VerifiersRegistry}. */
    private static final long CANTON_VERIFIER_SYSTEM_CONTRACT_NUM = VerifiersRegistry.CANTON_CONTRACT_NUM;

    /** EVM-address form of {@link #CANTON_VERIFIER_SYSTEM_CONTRACT_NUM}. */
    private static final Bytes CANTON_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS = VerifiersRegistry.CANTON_EVM_ADDRESS;

    private record BuiltInVerifier(String displayName, Bytes fingerprint) {}

    // Identity names are permanent protocol labels, separate from the readable display names.
    // Their hashes identify verifier types and do not change with native implementation updates.
    private static final BuiltInVerifier HIERO_TSS_VERIFIER =
            new BuiltInVerifier("Hiero TSS (0x16e)", builtInFingerprint("hiero-tss"));
    private static final BuiltInVerifier BESU_QBFT_VERIFIER =
            new BuiltInVerifier("Besu QBFT (0x16f)", builtInFingerprint("besu-qbft"));
    private static final BuiltInVerifier ETHEREUM_VERIFIER =
            new BuiltInVerifier("Ethereum sync committee (0x171)", builtInFingerprint("ethereum-sync-committee"));
    private static final BuiltInVerifier CANTON_VERIFIER =
            new BuiltInVerifier("Canton (0x173)", builtInFingerprint("canton"));

    private static final Map<Long, BuiltInVerifier> BUILT_IN_VERIFIERS_BY_NUM = Map.of(
            CLPR_SERVICE_ACCOUNT_NUM, HIERO_TSS_VERIFIER,
            BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NUM, BESU_QBFT_VERIFIER,
            ETHEREUM_VERIFIER_SYSTEM_CONTRACT_NUM, ETHEREUM_VERIFIER,
            CANTON_VERIFIER_SYSTEM_CONTRACT_NUM, CANTON_VERIFIER);

    private static final Map<Bytes, BuiltInVerifier> BUILT_IN_VERIFIERS_BY_ADDRESS = Map.of(
            CLPR_EVM_ADDRESS_BYTES, HIERO_TSS_VERIFIER,
            BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS, BESU_QBFT_VERIFIER,
            ETHEREUM_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS, ETHEREUM_VERIFIER,
            CANTON_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS, CANTON_VERIFIER);

    private final ClprVerifierFactory verifierFactory;
    private final ClprChannelLifecycle channelLifecycle;

    @Inject
    public ClprCreateChannelHandler(
            @NonNull final ClprVerifierFactory verifierFactory, @NonNull final ClprChannelLifecycle channelLifecycle) {
        this.verifierFactory = requireNonNull(verifierFactory);
        this.channelLifecycle = requireNonNull(channelLifecycle);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        final var op = context.body().clprCreateChannelOrThrow();
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.salt().length() == 32, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasVerifierContract(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.configProofBytes().length() > 0, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // Permissionless — no additional key requirements beyond payer
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        try {
            createChannel(context);
        } catch (final HandleException failure) {
            throw ClprGasAccounting.preservingChargesOnRollback(context, failure);
        } catch (final RuntimeException failure) {
            throw ClprGasAccounting.preservingChargesOnRollback(context, failure);
        }
    }

    private void createChannel(@NonNull final HandleContext context) {
        final var op = context.body().clprCreateChannelOrThrow();
        final var channelStore = context.storeFactory().writableStore(WritableChannelStore.class);
        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);

        if (channelStore.getChannel(op.channelId()) != null) {
            log.warn("[ClprCreateChannel] channel already active channelId={}", shortHex(op.channelId()));
            throw new HandleException(CLPR_CHANNEL_ALREADY_EXISTS);
        }

        // Use the same 20-byte verifier address with either HAPI ContractID representation.
        final var verifierId = op.verifierContractOrThrow();
        final Bytes verifierAddress;
        if (isBuiltInVerifier(verifierId)) {
            verifierAddress = verifierId.hasEvmAddress()
                    ? verifierId.evmAddressOrThrow()
                    : Bytes.wrap(asEvmAddress(verifierId.contractNumOrThrow()));
        } else {
            final var account = accountStore.getContractById(verifierId);
            validateTrue(account != null && account.smartContract(), CLPR_INVALID_VERIFIER_CONTRACT);
            final var alias = extractEvmAddress(account.alias());
            verifierAddress = alias != null
                    ? alias
                    : Bytes.wrap(asEvmAddress(account.accountIdOrThrow().accountNumOrThrow()));
        }
        log.debug(
                "[ClprCreateChannel] verifier contract validation PASS channelId={} verifierContract={} builtIn={}",
                shortHex(op.channelId()),
                op.verifierContractOrThrow(),
                isBuiltInVerifier(op.verifierContractOrThrow()));

        // Call verifier.verifyConfig(config_proof_bytes) to get peer configuration. The trust
        // anchor used to authenticate the proof comes from the proven config's
        // initial_trust_anchor field (populated by the source ledger), so no separate registrant
        // claim is needed; the verifier rejects a proof whose inner config has an empty anchor.
        final var verifier = verifierFactory.getVerifier(op.verifierContractOrThrow());
        log.debug(
                "[ClprCreateChannel] verifyConfig START channelId={} verifierContract={} configProofBytes={} "
                        + "configProofHash={}",
                shortHex(op.channelId()),
                op.verifierContractOrThrow(),
                op.configProofBytes().length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(op.configProofBytes())));
        // Thread endpoint_manifest_proof_bytes into the manifest-aware verifyConfig ABI
        // (spec §4.8). No freshness check is applied at createChannel time.
        final var verifiedConfig = verifyPeerConfig(
                verifier,
                op.verifierContractOrThrow(),
                op.channelId(),
                op.configProofBytes(),
                op.endpointManifestProofBytes(),
                context);
        final var peerConfig = verifiedConfig.config();
        final var localConfig = context.storeFactory()
                .readableStore(ReadableLedgerConfigurationStore.class)
                .getConfiguration();
        validateTrue(!localConfig.chainId().isEmpty() && !peerConfig.chainId().isEmpty(), CLPR_VERIFIER_CONFIG_FAILED);
        final var expectedId = ClprChannelId.derive(
                localConfig.chainId(),
                CLPR_EVM_ADDRESS_BYTES,
                verifierAddress,
                peerConfig.chainId(),
                peerConfig.serviceAddress(),
                op.peerVerifier(),
                op.salt());
        validateTrue(expectedId.equals(op.channelId()), INVALID_TRANSACTION_BODY);
        final var peerManifest = verifiedConfig.manifest();
        // The authoritative peer-endpoint source is the ClprEndpointManifest, mirroring
        // ClprChannelManager.resolveDialTargets (the runtime consumer) so producer and consumer
        // stay symmetric. The endpoints live solely in the ClprEndpointManifest (config.endpoints is
        // deprecated and left empty per spec §4.8).
        final List<ClprEndpoint> peerEndpoints = peerManifest.endpoints();
        log.debug(
                "[ClprCreateChannel] verifyConfig PASS channelId={} verifierContract={} chainId={} "
                        + "serviceAddress={} endpoints={} throttlesPresent={} initialTrustAnchorBytes={} "
                        + "initialTrustAnchorHash={} initialTrustAnchorId={}",
                shortHex(op.channelId()),
                op.verifierContractOrThrow(),
                peerConfig.chainId(),
                shortHex(peerConfig.serviceAddress()),
                peerEndpoints.size(),
                peerConfig.throttles() != null,
                peerConfig.initialTrustAnchor().length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(peerConfig.initialTrustAnchor())),
                shortHex(peerConfig.initialTrustAnchorId()));

        // Reject configs missing peerThrottles. peerThrottles is read by
        // ClprChannelManager.syncTick via peerThrottlesOrThrow() — a null value would
        // NPE the background sync thread (untraceable), instead of failing the transaction
        // cleanly. This is an upstream config error and should surface here, not at runtime.
        if (peerConfig.throttles() == null) {
            log.warn(
                    "[ClprCreateChannel] verified config rejected: missing peerThrottles channelId={} "
                            + "chainId={} serviceAddress={} configProofHash={} initialTrustAnchorHash={}",
                    shortHex(op.channelId()),
                    peerConfig.chainId(),
                    shortHex(peerConfig.serviceAddress()),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(op.configProofBytes())),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(peerConfig.initialTrustAnchor())));
        }
        validateTrue(peerConfig.throttles() != null, CLPR_VERIFIER_CONFIG_FAILED);
        // A manifest-backed channel may legitimately start empty: ClprEndpointManifest permits an
        // empty endpoint list at version >= 1, and genesis creates exactly that — later
        // manifest-update bundles populate it. So an empty peer-endpoint list is not rejected here.

        // channel_context = abi.encodePacked(bytes32 channelId, bytes serviceAddress)
        final var channelContextBytes = buildChannelContext(peerConfig, op);
        final Bytes channelContext = Bytes.wrap(channelContextBytes);

        // Record an informational verifier fingerprint: a fixed identity hash for built-ins,
        // or keccak256 of deployed verifier bytecode. Keep readable names in diagnostics as well.
        final var verifierFingerprint = computeVerifierFingerprint(context, op.verifierContractOrThrow());
        final var builtInVerifier = resolveBuiltInVerifier(op.verifierContractOrThrow());
        log.debug(
                "[ClprCreateChannel] verifier fingerprint channelId={} verifierName={} verifierContract={} fingerprint={}",
                shortHex(op.channelId()),
                builtInVerifier != null ? builtInVerifier.displayName() : "Deployed contract",
                op.verifierContractOrThrow(),
                shortHex(verifierFingerprint));

        // Apply local count and byte limits before storing any peer-controlled manifest data.
        final var localThrottles = localConfig.throttlesOrElse(ClprThrottles.DEFAULT);
        final int rawPeerLimit = localThrottles.maxPeerEndpoints();
        final ClprEndpointManifest storedManifest;
        try {
            storedManifest = ClprPeerManifestPolicy.boundedManifest(peerManifest, rawPeerLimit);
        } catch (final IllegalArgumentException e) {
            throw new HandleException(CLPR_VERIFIER_CONFIG_FAILED);
        }
        final List<ClprEndpoint> truncatedPeerEndpoints = storedManifest.endpoints();
        log.debug(
                "[ClprCreateChannel] endpoint policy channelId={} peerEndpoints={} localMaxPeerEndpoints={} "
                        + "storedEndpoints={}",
                shortHex(op.channelId()),
                peerEndpoints.size(),
                rawPeerLimit,
                truncatedPeerEndpoints.size());

        // Reject peer configs with a semantically invalid timestamp
        validateTimestamp(peerConfig.timestamp(), CLPR_VERIFIER_CONFIG_FAILED);

        // Seed Channel.trust_anchor / trust_anchor_id from the verified config's
        // initial_trust_anchor / initial_trust_anchor_id. For Hiero TSS this is the peer
        // ledger_id (populated by the source ledger on its config). createChannel is the
        // only path other than verifyBundle that writes these.
        final var channelBuilder = ClprChannel.newBuilder()
                .channelId(op.channelId())
                .chainId(peerConfig.chainId())
                .serviceAddress(peerConfig.serviceAddress())
                .verifierContract(op.verifierContractOrThrow())
                .verifierFingerprint(verifierFingerprint)
                .status(ClprChannelStatus.ACTIVE)
                .peerConfigTimestamp(peerConfig.timestamp())
                .peerThrottles(peerConfig.throttles())
                .nextMessageId(1L)
                .ackedMessageId(0L)
                .sentRunningHash(ZERO_HASH)
                .receivedMessageId(0L)
                .receivedRunningHash(ZERO_HASH)
                .lastConfigTimestamp(toTimestamp(context.consensusNow()))
                .salt(op.salt())
                .trustAnchor(peerConfig.initialTrustAnchor())
                .trustAnchorId(peerConfig.initialTrustAnchorId())
                .channelContext(channelContext);
        // Attach the peer endpoint manifest: it is the runtime dial-target source (spec §4.7,
        // ClprChannelManager.resolveDialTargets).
        //
        // Store the manifest carrying the LOCALLY TRUNCATED endpoint list (this ledger's
        // max_peer_endpoints policy), not the peer's full list — the stored manifest IS what the
        // orchestrator dials, so it must honor the same bound applied to the seeded cache below.
        channelBuilder.endpointManifest(storedManifest).endpointManifestVersion(storedManifest.version());
        final var channel = channelBuilder.build();
        ClprFeeCalculator.settleCreateChannelFee(
                context, 0L, Math.toIntExact(ClprChannel.PROTOBUF.measureRecord(channel)));
        channelStore.put(channel);
        log.debug(
                "[ClprCreateChannel] channel ACTIVE channelId={} peerChainId={} endpointsStored={} "
                        + " trustAnchorBytes={} trustAnchorId={} endpointManifestVersion={} "
                        + "endpointManifestEntries={}",
                shortHex(op.channelId()),
                peerConfig.chainId(),
                truncatedPeerEndpoints.size(),
                peerConfig.initialTrustAnchor().length(),
                shortHex(peerConfig.initialTrustAnchorId()),
                peerManifest.version(),
                truncatedPeerEndpoints.size());
        ClprGasAccounting.refundUnusedGas(context);
        // Notify the runtime sync orchestrator. If the surrounding transaction
        // rolls back, the orchestrator will self-correct on its next tick by
        // detecting the missing state record.
        channelLifecycle.onChannelActivated(op.channelId());
        // Seed the orchestrator's peer endpoint cache from the (truncated) peer
        // ledger configuration so the first sync tick has endpoints to contact.
        // Use the same truncated list as the on-chain peer_signing_keys roster
        // so the orchestrator can't try to call out to a peer endpoint whose
        // signing key we don't recognize.
        channelLifecycle.seedPeerEndpoints(op.channelId(), truncatedPeerEndpoints);
    }

    private static byte[] buildChannelContext(
            final ClprLedgerConfiguration peerConfig, final ClprCreateChannelTransactionBody op) {
        final byte[] serviceAddressBytes = peerConfig.serviceAddress().toByteArray();
        final byte[] connIdRaw = op.channelId().toByteArray();
        final byte[] connId32 = new byte[32];
        System.arraycopy(connIdRaw, 0, connId32, 0, Math.min(connIdRaw.length, 32));
        final byte[] channelContextBytes = new byte[32 + serviceAddressBytes.length];
        System.arraycopy(connId32, 0, channelContextBytes, 0, 32);
        System.arraycopy(serviceAddressBytes, 0, channelContextBytes, 32, serviceAddressBytes.length);
        return channelContextBytes;
    }

    /**
     * Returns {@code true} when {@code verifierContract} resolves to one of the built-in
     * CLPR system contracts. Accepts either the {@code contractNum} or {@code evmAddress}
     * representation.
     */
    private static boolean isBuiltInVerifier(@NonNull final ContractID verifierContract) {
        return resolveBuiltInVerifier(verifierContract) != null;
    }

    @Nullable
    private static BuiltInVerifier resolveBuiltInVerifier(@NonNull final ContractID verifierContract) {
        if (verifierContract.hasContractNum()) {
            return BUILT_IN_VERIFIERS_BY_NUM.get(verifierContract.contractNumOrElse(0L));
        }
        if (verifierContract.hasEvmAddress()) {
            return BUILT_IN_VERIFIERS_BY_ADDRESS.get(verifierContract.evmAddressOrElse(Bytes.EMPTY));
        }
        return null;
    }

    /** Fixed keccak256 identity derived from an explicit UTF-8 namespace and permanent verifier name. */
    @NonNull
    private static Bytes builtInFingerprint(@NonNull final String name) {
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(("hiero.clpr.builtin-verifier/" + name).getBytes(UTF_8)));
    }

    @NonNull
    private VerifiedConfig verifyPeerConfig(
            @NonNull final ClprVerifier verifier,
            @NonNull final ContractID verifierContract,
            @NonNull final Bytes channelId,
            @NonNull final Bytes configProofBytes,
            @NonNull final Bytes endpointManifestProofBytes,
            @NonNull final HandleContext context) {
        try {
            return verifier.verifyConfig(configProofBytes, channelId, endpointManifestProofBytes, context);
        } catch (final HandleException e) {
            log.warn(
                    "[ClprCreateChannel] verifyConfig FAILED channelId={} verifierContract={} status={} "
                            + "configProofBytes={} configProofHash={} configProofPrefix={}",
                    shortHex(channelId),
                    verifierContract,
                    e.getStatus(),
                    configProofBytes.length(),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(configProofBytes)),
                    shortHex(configProofBytes));
            throw e;
        }
    }

    /**
     * Returns a fixed identity hash for built-in verifiers, or keccak256 of a deployed verifier's bytecode.
     * Returns {@link #ZERO_HASH} when a custom verifier's bytecode is unavailable.
     */
    @NonNull
    private Bytes computeVerifierFingerprint(
            @NonNull final HandleContext context, @NonNull final ContractID verifierContractId) {
        final var builtInVerifier = resolveBuiltInVerifier(verifierContractId);
        if (builtInVerifier != null) {
            return builtInVerifier.fingerprint();
        }
        final var contractApi = context.storeFactory().serviceApi(SmartContractServiceApi.class);
        final var bytecode = contractApi.getContractBytecode(verifierContractId);
        if (bytecode == null || bytecode.length() == 0) {
            return ZERO_HASH;
        }
        return MiscCryptoUtils.keccak256DigestOf(bytecode);
    }

    private static String shortHex(@NonNull final Bytes bytes) {
        final var hex = bytes.toHex();
        if (hex.length() <= 64) {
            return hex;
        }
        return hex.substring(0, 64) + "...";
    }
}

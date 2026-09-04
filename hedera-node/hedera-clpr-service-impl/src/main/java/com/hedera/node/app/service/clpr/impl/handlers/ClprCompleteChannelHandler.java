// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_COMMITMENT_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_NUM;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.WritableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingCommitmentStore;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.service.contract.api.SmartContractServiceApi;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.SignatureType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link com.hedera.hapi.node.base.HederaFunctionality#CLPR_COMPLETE_CHANNEL} transactions.
 *
 * <p>This is the reveal phase of the commit-reveal channel registration scheme.
 * It validates the ownership commitment preimage, verifies the signature, validates the
 * verifier contract and peer config, then creates the Channel in ACTIVE state.
 */
@Singleton
public final class ClprCompleteChannelHandler extends AbstractClprHandler {
    private static final Logger log = LoggerFactory.getLogger(ClprCompleteChannelHandler.class);
    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);

    /**
     * Contract numbers of built-in system contracts that are valid verifier targets but have
     * no on-ledger account record:
     * <ul>
     *   <li>{@code 0x16e} (366) — CLPR system contract precompile.</li>
     *   <li>{@code 0x16f} (367) — Besu QBFT verifier system contract.</li>
     *   <li>{@code 0x170} (368) — Sei (CometBFT) verifier system contract.</li>
     *   <li>{@code 0x171} (369) — Ethereum (sync-committee) verifier system contract.</li>
     * </ul>
     * Mirrors {@code BesuQBFTVerifierSystemContract.BESU_QBFT_VERIFIER_EVM_ADDRESS},
     * {@code SeiVerifierSystemContract.SEI_VERIFIER_EVM_ADDRESS} and
     * {@code EthereumVerifierSystemContract.ETHEREUM_VERIFIER_EVM_ADDRESS} in the
     * smart-contract-service-impl module for the verifier addresses.
     */
    private static final long BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NUM = 0x16fL;

    /** EVM-address form of {@link #BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NUM}. */
    private static final Bytes BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS =
            Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x6f});

    /** Sei (CometBFT) verifier system contract; mirrors {@code SEI_VERIFIER_EVM_ADDRESS} (0x170). */
    private static final long SEI_VERIFIER_SYSTEM_CONTRACT_NUM = 0x170L;

    /** EVM-address form of {@link #SEI_VERIFIER_SYSTEM_CONTRACT_NUM}. */
    private static final Bytes SEI_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS =
            Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x70});

    /** Ethereum (sync-committee) verifier system contract; mirrors {@code ETHEREUM_VERIFIER_EVM_ADDRESS} (0x171). */
    private static final long ETHEREUM_VERIFIER_SYSTEM_CONTRACT_NUM = 0x171L;

    /** EVM-address form of {@link #ETHEREUM_VERIFIER_SYSTEM_CONTRACT_NUM}. */
    private static final Bytes ETHEREUM_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS =
            Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x71});

    private static final Set<Long> BUILT_IN_VERIFIER_NUMS = Set.of(
            CLPR_SERVICE_ACCOUNT_NUM,
            BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NUM,
            SEI_VERIFIER_SYSTEM_CONTRACT_NUM,
            ETHEREUM_VERIFIER_SYSTEM_CONTRACT_NUM);

    private static final Set<Bytes> BUILT_IN_VERIFIER_EVM_ADDRESSES = Set.of(
            CLPR_EVM_ADDRESS_BYTES,
            BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS,
            SEI_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS,
            ETHEREUM_VERIFIER_SYSTEM_CONTRACT_EVM_ADDRESS);

    private final ClprVerifierFactory verifierFactory;
    private final ClprChannelLifecycle channelLifecycle;

    @Inject
    public ClprCompleteChannelHandler(
            @NonNull final ClprVerifierFactory verifierFactory, @NonNull final ClprChannelLifecycle channelLifecycle) {
        this.verifierFactory = requireNonNull(verifierFactory);
        this.channelLifecycle = requireNonNull(channelLifecycle);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        final var op = context.body().clprCompleteChannelOrThrow();
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.signature().length() == SIGNATURE_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasVerifierContract(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.configProofBytes().length() > 0, INVALID_TRANSACTION_BODY);

        final var expectedKeyLength =
                switch (op.signatureScheme()) {
                    case ECDSA_SECP256K1 -> ECDSA_UNCOMPRESSED_KEY_LENGTH;
                    case ED25519 -> ED25519_KEY_LENGTH;
                    default -> throw new PreCheckException(INVALID_TRANSACTION_BODY);
                };
        validateTruePreCheck(op.publicKey().length() == expectedKeyLength, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // Permissionless — no additional key requirements beyond payer
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprCompleteChannelOrThrow();
        log.debug(
                "[ClprCompleteChannel] ENTER channelId={} verifierContract={} signatureScheme={} "
                        + "publicKeyBytes={} signatureBytes={} configProofBytes={} configProofHash={} configProofPrefix={}",
                shortHex(op.channelId()),
                op.verifierContractOrThrow(),
                op.signatureScheme(),
                op.publicKey().length(),
                op.signature().length(),
                op.configProofBytes().length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(op.configProofBytes())),
                shortHex(op.configProofBytes()));

        final var commitmentStore = context.storeFactory().writableStore(WritablePendingCommitmentStore.class);
        final var channelStore = context.storeFactory().writableStore(WritableChannelStore.class);
        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);

        // 1. Compute the expected commitment and verify it exists
        final var expectedCommitment = computeCommitment(op.channelId(), op.publicKey());
        if (!commitmentStore.contains(expectedCommitment)) {
            log.warn(
                    "[ClprCompleteChannel] commitment lookup FAILED channelId={} expectedCommitment={}",
                    shortHex(op.channelId()),
                    shortHex(expectedCommitment));
            throw new HandleException(CLPR_COMMITMENT_MISMATCH);
        }
        log.debug(
                "[ClprCompleteChannel] commitment lookup PASS channelId={} expectedCommitment={}",
                shortHex(op.channelId()),
                shortHex(expectedCommitment));

        // 2. Verify channel_id isn't already active
        if (channelStore.getChannel(op.channelId()) != null) {
            log.warn("[ClprCompleteChannel] channel already active channelId={}", shortHex(op.channelId()));
            throw new HandleException(CLPR_CHANNEL_ALREADY_EXISTS);
        }

        // 3. Verify signature over keccak256(channel_id)
        // FUTURE: We may be able to move this to pre-handle to avoid the crypto work on the handle thread
        final var messageHash = MiscCryptoUtils.keccak256DigestOf(op.channelId().toByteArray());
        final var signatureType =
                switch (op.signatureScheme()) {
                    case ECDSA_SECP256K1 -> SignatureType.ECDSA_SECP256K1;
                    case ED25519 -> SignatureType.ED25519;
                    default -> throw new HandleException(INVALID_TRANSACTION_BODY);
                };
        log.debug(
                "[ClprCompleteChannel] signature verification START channelId={} signatureScheme={} "
                        + "messageHash={} publicKeyBytes={} signatureBytes={}",
                shortHex(op.channelId()),
                op.signatureScheme(),
                shortHex(Bytes.wrap(messageHash)),
                op.publicKey().length(),
                op.signature().length());
        final var isValid = CryptographyProvider.getInstance()
                .verifySync(
                        messageHash,
                        op.signature().toByteArray(),
                        op.publicKey().toByteArray(),
                        signatureType);
        if (!isValid) {
            log.warn(
                    "[ClprCompleteChannel] signature verification FAILED channelId={} signatureScheme={} "
                            + "messageHash={}",
                    shortHex(op.channelId()),
                    op.signatureScheme(),
                    shortHex(Bytes.wrap(messageHash)));
            throw new HandleException(CLPR_INVALID_SIGNATURE);
        }
        log.debug(
                "[ClprCompleteChannel] signature verification PASS channelId={} signatureScheme={} messageHash={}",
                shortHex(op.channelId()),
                op.signatureScheme(),
                shortHex(Bytes.wrap(messageHash)));

        // 4. Verify verifier contract exists and is a smart contract.
        //    Built-in system contracts (0x16e, 0x16f) are valid verifiers but have no on-ledger
        //    account record, so skip the account-store lookup for those. See ClprVerifierFactory
        //    (TODO CLPR-5.3) for the planned built-in verifier registry that will subsume this.
        if (!isBuiltInVerifier(op.verifierContractOrThrow())) {
            final var verifierAccount = accountStore.getContractById(op.verifierContractOrThrow());
            if (verifierAccount == null || !verifierAccount.smartContract()) {
                log.warn(
                        "[ClprCompleteChannel] verifier contract validation FAILED channelId={} "
                                + "verifierContract={} accountFound={} smartContract={}",
                        shortHex(op.channelId()),
                        op.verifierContractOrThrow(),
                        verifierAccount != null,
                        verifierAccount != null && verifierAccount.smartContract());
                throw new HandleException(CLPR_INVALID_VERIFIER_CONTRACT);
            }
        }
        log.debug(
                "[ClprCompleteChannel] verifier contract validation PASS channelId={} verifierContract={} builtIn={}",
                shortHex(op.channelId()),
                op.verifierContractOrThrow(),
                isBuiltInVerifier(op.verifierContractOrThrow()));

        // 5. Call verifier.verifyConfig(config_proof_bytes) to get peer configuration. The trust
        // anchor used to authenticate the proof comes from the proven config's
        // initial_trust_anchor field (populated by the source ledger), so no separate registrant
        // claim is needed; the verifier rejects a proof whose inner config has an empty anchor.
        final var verifier = verifierFactory.getVerifier(op.verifierContractOrThrow());
        log.debug(
                "[ClprCompleteChannel] verifyConfig START channelId={} verifierContract={} configProofBytes={} "
                        + "configProofHash={}",
                shortHex(op.channelId()),
                op.verifierContractOrThrow(),
                op.configProofBytes().length(),
                shortHex(MiscCryptoUtils.keccak256DigestOf(op.configProofBytes())));
        // Thread endpoint_manifest_proof_bytes into the manifest-aware verifyConfig ABI
        // (spec §4.8). EvmClprVerifier flag-gates on clpr.endpointManifestEnabled: when the
        // flag is off, it dispatches the legacy 1-arg ABI and ignores this field (returning
        // an empty manifest). No freshness check is applied at completeChannel time.
        final var verifiedConfig = verifyPeerConfig(
                verifier,
                op.verifierContractOrThrow(),
                op.channelId(),
                op.configProofBytes(),
                op.endpointManifestProofBytes(),
                context);
        final var peerConfig = verifiedConfig.config();
        final var peerManifest = verifiedConfig.manifest();
        // Select the authoritative peer-endpoint source per the manifest feature flag, mirroring
        // ClprChannelManager.resolveDialTargets (the runtime consumer) so producer and consumer
        // stay symmetric. When clpr.endpointManifestEnabled=true the endpoints live solely in the
        // ClprEndpointManifest (config.endpoints is deprecated and left empty per spec §4.8); when
        // off, they come from ClprLedgerConfiguration.endpoints and no manifest is attached below.
        final boolean manifestEnabled =
                context.configuration().getConfigData(ClprConfig.class).endpointManifestEnabled();
        final List<ClprEndpoint> peerEndpoints = manifestEnabled ? peerManifest.endpoints() : peerConfig.endpoints();
        log.debug(
                "[ClprCompleteChannel] verifyConfig PASS channelId={} verifierContract={} chainId={} "
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
                    "[ClprCompleteChannel] verified config rejected: missing peerThrottles channelId={} "
                            + "chainId={} serviceAddress={} configProofHash={} initialTrustAnchorHash={}",
                    shortHex(op.channelId()),
                    peerConfig.chainId(),
                    shortHex(peerConfig.serviceAddress()),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(op.configProofBytes())),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(peerConfig.initialTrustAnchor())));
        }
        validateTrue(peerConfig.throttles() != null, CLPR_VERIFIER_CONFIG_FAILED);
        // Only the legacy config-seeded path requires a non-empty endpoint list (an empty
        // config leaves the orchestrator with no peer to call, making the channel inert).
        // A manifest-enabled channel may legitimately start empty: ClprEndpointManifest
        // permits an empty endpoint list at version >= 1, and genesis creates exactly that —
        // later manifest-update bundles populate it. So only reject an empty list flag-off.
        if (!manifestEnabled && peerEndpoints.isEmpty()) {
            log.warn(
                    "[ClprCompleteChannel] verified config rejected: no peer endpoints channelId={} "
                            + "chainId={} serviceAddress={} configProofHash={} initialTrustAnchorHash={}",
                    shortHex(op.channelId()),
                    peerConfig.chainId(),
                    shortHex(peerConfig.serviceAddress()),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(op.configProofBytes())),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(peerConfig.initialTrustAnchor())));
        }
        validateTrue(manifestEnabled || !peerEndpoints.isEmpty(), CLPR_VERIFIER_CONFIG_FAILED);

        // channel_context = abi.encodePacked(bytes32 channelId, bytes serviceAddress)
        final var channelContextBytes = buildChannelContext(peerConfig, op);
        final Bytes channelContext = Bytes.wrap(channelContextBytes);

        // 6. Compute verifier_fingerprint: keccak256 of verifier contract bytecode at registration time.
        // Informational — lets observers detect if the verifier contract was swapped after registration.
        // Built-in verifiers have no on-ledger bytecode, so they get ZERO_HASH.
        final var verifierFingerprint = computeVerifierFingerprint(context, op.verifierContractOrThrow());
        log.debug(
                "[ClprCompleteChannel] verifier fingerprint channelId={} verifierContract={} fingerprint={}",
                shortHex(op.channelId()),
                op.verifierContractOrThrow(),
                shortHex(verifierFingerprint));

        // 7. Truncate the peer endpoint list to this ledger's max_peer_endpoints (spec §3.10.5).
        //    The receiving ledger imposes its own bound on how many peer endpoints it stores on
        //    chain per Channel; entries beyond the limit are silently discarded in declared
        //    order. Zero means no limit. Read from the LOCAL ledger config — the peer's
        //    declaration of its own list doesn't override our storage policy.
        final var localConfigStore = context.storeFactory().readableStore(ReadableLedgerConfigurationStore.class);
        final var localThrottles = localConfigStore.getConfiguration().throttlesOrElse(ClprThrottles.DEFAULT);
        final int rawPeerLimit = localThrottles.maxPeerEndpoints();
        final List<ClprEndpoint> truncatedPeerEndpoints = rawPeerLimit > 0 && peerEndpoints.size() > rawPeerLimit
                ? peerEndpoints.subList(0, rawPeerLimit)
                : peerEndpoints;
        log.debug(
                "[ClprCompleteChannel] endpoint policy channelId={} peerEndpoints={} localMaxPeerEndpoints={} "
                        + "storedEndpoints={}",
                shortHex(op.channelId()),
                peerEndpoints.size(),
                rawPeerLimit,
                truncatedPeerEndpoints.size());

        // 8. Reject peer configs with a semantically invalid timestamp
        validateTimestamp(peerConfig.timestamp(), CLPR_VERIFIER_CONFIG_FAILED);

        // 9. Create Channel in ACTIVE state. The ownership_commitment is stored on the
        // channel record so that CloseChannel can remove it from the pending-commitments
        // store when the channel is closed, freeing the commitment for potential reuse.
        // Seed Channel.trust_anchor / trust_anchor_id from the verified config's
        // initial_trust_anchor / initial_trust_anchor_id. For Hiero TSS this is the peer
        // ledger_id (populated by the source ledger on its config). completeChannel is the
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
                .ownershipCommitment(expectedCommitment)
                .trustAnchor(peerConfig.initialTrustAnchor())
                .trustAnchorId(peerConfig.initialTrustAnchorId())
                .channelContext(channelContext);
        // Attach the peer endpoint manifest only when the feature flag is on: it is the runtime
        // dial-target source (spec §4.7, ClprChannelManager.resolveDialTargets). Flag-off
        // channels dial from the config-seeded peer-endpoint cache, so persisting the
        // verifier's synthesized bring-up manifest would store state nothing ever reads; leave
        // endpoint_manifest unset (hasEndpointManifest()==false) instead.
        //
        // Store the manifest carrying the LOCALLY TRUNCATED endpoint list (this ledger's
        // max_peer_endpoints policy), not the peer's full list — the stored manifest IS what the
        // orchestrator dials, so it must honor the same bound applied to the seeded cache below.
        if (manifestEnabled) {
            final var storedManifest =
                    peerManifest.copyBuilder().endpoints(truncatedPeerEndpoints).build();
            channelBuilder.endpointManifest(storedManifest).endpointManifestVersion(storedManifest.version());
        }
        final var channel = channelBuilder.build();
        channelStore.put(channel);
        log.debug(
                "[ClprCompleteChannel] channel ACTIVE channelId={} peerChainId={} endpointsStored={} "
                        + " trustAnchorBytes={} trustAnchorId={} manifestEnabled={} endpointManifestVersion={} "
                        + "endpointManifestEntries={}",
                shortHex(op.channelId()),
                peerConfig.chainId(),
                truncatedPeerEndpoints.size(),
                peerConfig.initialTrustAnchor().length(),
                shortHex(peerConfig.initialTrustAnchorId()),
                manifestEnabled,
                manifestEnabled ? peerManifest.version() : 0L,
                manifestEnabled ? truncatedPeerEndpoints.size() : 0);
        // Notify the runtime sync orchestrator. If the surrounding transaction
        // rolls back, the orchestrator will self-correct on its next tick by
        // detecting the missing state record.
        channelLifecycle.onChannelActivated(op.channelId());
        // Seed the orchestrator's peer endpoint cache from the (truncated) peer
        // ledger configuration so the first sync tick has endpoints to contact
        // without waiting for discovery. Use the same truncated list as the on-chain
        // peer_signing_keys roster so the orchestrator can't try to call out to a
        // peer endpoint whose signing key we don't recognize.
        channelLifecycle.seedPeerEndpoints(op.channelId(), truncatedPeerEndpoints);
    }

    private static byte[] buildChannelContext(
            final ClprLedgerConfiguration peerConfig, final ClprCompleteChannelTransactionBody op) {
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
        if (verifierContract.hasContractNum()) {
            return BUILT_IN_VERIFIER_NUMS.contains(verifierContract.contractNumOrElse(0L));
        }
        if (verifierContract.hasEvmAddress()) {
            return BUILT_IN_VERIFIER_EVM_ADDRESSES.contains(verifierContract.evmAddressOrElse(Bytes.EMPTY));
        }
        return false;
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
                    "[ClprCompleteChannel] verifyConfig FAILED channelId={} verifierContract={} status={} "
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
     * Returns keccak256 of the verifier contract's deployed bytecode, or {@link #ZERO_HASH}
     * if the bytecode is unavailable (e.g. for built-in verifiers).
     */
    @NonNull
    private Bytes computeVerifierFingerprint(
            @NonNull final HandleContext context, @NonNull final ContractID verifierContractId) {
        final var contractApi = context.storeFactory().serviceApi(SmartContractServiceApi.class);
        final var bytecode = contractApi.getContractBytecode(verifierContractId);
        if (bytecode == null || bytecode.length() == 0) {
            return ZERO_HASH;
        }
        return MiscCryptoUtils.keccak256DigestOf(bytecode);
    }

    /**
     * Computes the ownership commitment: keccak256(channel_id || public_key).
     */
    @NonNull
    private Bytes computeCommitment(@NonNull final Bytes channelId, @NonNull final Bytes publicKey) {
        final var payload = new byte[(int) (channelId.length() + publicKey.length())];
        channelId.getBytes(0, payload, 0, (int) channelId.length());
        publicKey.getBytes(0, payload, (int) channelId.length(), (int) publicKey.length());
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(payload));
    }

    private static String shortHex(@NonNull final Bytes bytes) {
        final var hex = bytes.toHex();
        if (hex.length() <= 64) {
            return hex;
        }
        return hex.substring(0, 64) + "...";
    }
}

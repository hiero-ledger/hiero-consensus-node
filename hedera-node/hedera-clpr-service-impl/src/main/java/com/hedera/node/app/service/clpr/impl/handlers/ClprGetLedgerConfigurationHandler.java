// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.clpr.ClprGetLedgerConfigurationResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.spi.workflows.FreeQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handler for {@link HederaFunctionality#CLPR_GET_LEDGER_CONFIGURATION} queries.
 *
 * <p>Returns the current CLPR ledger configuration. No special authorization is required.
 */
@Singleton
public class ClprGetLedgerConfigurationHandler extends FreeQueryHandler {

    private static final Logger log = LogManager.getLogger(ClprGetLedgerConfigurationHandler.class);

    private final ClprStateProofManager stateProofManager;
    private final TssVerifier tssVerifier;
    private final ConfigProvider configProvider;

    @Inject
    public ClprGetLedgerConfigurationHandler(
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final TssVerifier tssVerifier,
            @NonNull final ConfigProvider configProvider) {
        this.stateProofManager = requireNonNull(stateProofManager);
        this.tssVerifier = requireNonNull(tssVerifier);
        this.configProvider = requireNonNull(configProvider);
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.clprGetLedgerConfigurationOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = ClprGetLedgerConfigurationResponse.newBuilder().header(header);
        return Response.newBuilder().clprGetLedgerConfiguration(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        if (!context.configuration().getConfigData(ClprConfig.class).enabled()) {
            throw new PreCheckException(CLPR_NOT_ENABLED);
        }
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);

        final var response = ClprGetLedgerConfigurationResponse.newBuilder().header(header);
        if (header.nodeTransactionPrecheckCode() == OK) {
            final var configStore = context.createStore(ReadableLedgerConfigurationStore.class);
            response.configuration(configStore.getConfiguration());
            // Attach a StateProof for the ClprLedgerConfiguration singleton so peers
            // can use this response as config_proof_bytes when registering a Channel
            // backed by a verifier contract that accepts a StateProof (e.g. one that
            // delegates to the CLPR system contract precompile's verifyConfig operation).
            // Empty when no signed block snapshot is available yet — clients should retry.
            final var proofBytes = stateProofManager.buildConfigStateProof();
            // Ledger id is sourced from ReadableHistoryStore (via the snapshot), not from
            // LedgerConfig — TSS verification requires the genesis-rooted id, not the config knob.
            final var ledgerId = stateProofManager.latestLedgerId();
            response.configurationStateProof(assertValidOrEmpty(proofBytes, ledgerId));
        }
        return Response.newBuilder().clprGetLedgerConfiguration(response).build();
    }

    /**
     * TEMP (bring-up sanity check): re-parses the freshly built configuration state proof,
     * recomputes the block root hash, and verifies the TSS signature carried in the proof's
     * {@code signedBlockProof} against that root hash (under this ledger's {@code ledger.id}).
     * If anything fails — structural parse, root hash computation, or TSS verification — we
     * refuse to return the bytes (return {@link Bytes#EMPTY}) and log a warning, so peers see
     * an explicit "no proof available" rather than silently-broken bytes that would later fail
     * their {@code verifyConfig} call with a confusing error.
     *
     * <p>Remove once the proof builders are exercised by enough downstream tests that we trust
     * them implicitly.
     */
    private Bytes assertValidOrEmpty(final Bytes proofBytes, final Bytes ledgerId) {
        if (proofBytes == null || proofBytes.length() == 0) {
            return Bytes.EMPTY;
        }
        if (!configProvider.getConfiguration().getConfigData(ClprConfig.class).verifyProofsAtSender()) {
            return proofBytes;
        }
        try {
            final var stateProof = StateProof.PROTOBUF.parse(proofBytes.toReadableSequentialData());
            // Throws IllegalStateException if any path is structurally invalid (bad sibling
            // counts, mismatched parent pointers, etc).
            final byte[] rootHash = StateProofVerifier.computeBlockRootHash(stateProof);

            if (!stateProof.hasSignedBlockProof()) {
                log.warn("ClprGetLedgerConfiguration: proof has no signedBlockProof; refusing");
                return Bytes.EMPTY;
            }
            final var signature = stateProof.signedBlockProof().blockSignature();
            if (signature == null || signature.length() == 0) {
                log.warn("ClprGetLedgerConfiguration: proof carries no block signature; refusing");
                return Bytes.EMPTY;
            }
            if (!tssVerifier.verifyTss(ledgerId, signature, Bytes.wrap(rootHash))) {
                log.warn("ClprGetLedgerConfiguration: TSS verification failed for ledgerId {}; refusing", ledgerId);
                return Bytes.EMPTY;
            }
            if (log.isDebugEnabled()) {
                log.debug(
                        "ClprGetLedgerConfiguration: built and verified configuration_state_proof "
                                + "({} bytes, computed rootHash length {}, TSS ok)",
                        proofBytes.length(),
                        rootHash.length);
            }
            return proofBytes;
        } catch (final Exception e) {
            log.warn(
                    "ClprGetLedgerConfiguration: refusing to return an invalid configuration_state_proof "
                            + "({} bytes): {}",
                    proofBytes.length(),
                    e.getMessage());
            return Bytes.EMPTY;
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.clpr.ClprGetEndpointManifestResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.ReadableEndpointManifestStore;
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
 * Handler for {@link HederaFunctionality#CLPR_GET_ENDPOINT_MANIFEST} queries (spec §6.5).
 *
 * <p>Returns the current CLPR endpoint manifest and a serialized {@link StateProof} attesting
 * the singleton against the latest signed block root. Public read — no admin authorization
 * required. Enables:
 * <ul>
 *   <li>Channel registrants to build the {@code endpoint_manifest_proof_bytes} needed by
 *       {@code ClprCompleteChannel} (spec PR #332).</li>
 *   <li>Any party to construct manifest-recovery {@code ClprSubmitBundle} calls (spec PR #336).</li>
 * </ul>
 */
@Singleton
public class ClprGetEndpointManifestHandler extends FreeQueryHandler {

    private static final Logger log = LogManager.getLogger(ClprGetEndpointManifestHandler.class);

    private final ClprStateProofManager stateProofManager;
    private final TssVerifier tssVerifier;
    private final ConfigProvider configProvider;

    @Inject
    public ClprGetEndpointManifestHandler(
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
        return query.clprGetEndpointManifestOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = ClprGetEndpointManifestResponse.newBuilder().header(header);
        return Response.newBuilder().clprGetEndpointManifest(response).build();
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

        final var response = ClprGetEndpointManifestResponse.newBuilder().header(header);
        if (header.nodeTransactionPrecheckCode() == OK) {
            final var manifestStore = context.createStore(ReadableEndpointManifestStore.class);
            response.manifest(manifestStore.get());
            final var proofBytes = stateProofManager.buildManifestStateProof();
            final var ledgerId = stateProofManager.latestLedgerId();
            response.manifestStateProof(assertValidOrEmpty(proofBytes, ledgerId));
        }
        return Response.newBuilder().clprGetEndpointManifest(response).build();
    }

    /**
     * Bring-up sanity check: re-parses the freshly built manifest state proof, recomputes the
     * block root hash, and verifies the TSS signature carried in the proof's
     * {@code signedBlockProof} against that root hash (under this ledger's {@code ledger.id}).
     * If anything fails — structural parse, root hash computation, or TSS verification — we
     * refuse to return the bytes ({@link Bytes#EMPTY}) and log a warning so peers see an
     * explicit "no proof available" rather than silently-broken bytes that would later fail
     * their {@code verifyConfig}/manifest-proof consumer with a confusing error.
     *
     * <p>Same defensive pattern as {@link ClprGetLedgerConfigurationHandler}; consider
     * factoring both into a shared helper once the pattern proves stable.
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
            final byte[] rootHash = StateProofVerifier.computeBlockRootHash(stateProof);
            if (!stateProof.hasSignedBlockProof()) {
                log.warn("ClprGetEndpointManifest: proof has no signedBlockProof; refusing");
                return Bytes.EMPTY;
            }
            final var signature = stateProof.signedBlockProof().blockSignature();
            if (signature == null || signature.length() == 0) {
                log.warn("ClprGetEndpointManifest: proof carries no block signature; refusing");
                return Bytes.EMPTY;
            }
            if (!tssVerifier.verifyTss(ledgerId, signature, Bytes.wrap(rootHash))) {
                log.warn("ClprGetEndpointManifest: TSS verification failed for ledgerId {}; refusing", ledgerId);
                return Bytes.EMPTY;
            }
            if (log.isDebugEnabled()) {
                log.debug(
                        "ClprGetEndpointManifest: built and verified manifest_state_proof "
                                + "({} bytes, computed rootHash length {}, TSS ok)",
                        proofBytes.length(),
                        rootHash.length);
            }
            return proofBytes;
        } catch (final Exception e) {
            log.warn(
                    "ClprGetEndpointManifest: refusing to return an invalid manifest_state_proof " + "({} bytes): {}",
                    proofBytes.length(),
                    e.getMessage());
            return Bytes.EMPTY;
        }
    }
}

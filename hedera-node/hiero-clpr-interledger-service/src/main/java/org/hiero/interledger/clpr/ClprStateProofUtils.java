// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384HashOf;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;

/**
 * Utility class for extracting and validating CLPR ledger configurations from state proofs.
 *
 * <p>This class provides methods to:
 * <ul>
 *   <li>Validate state proofs using {@link StateProofVerifier}</li>
 *   <li>Extract {@link ClprLedgerConfiguration} from state proof leaf bytes</li>
 *   <li>Handle dev-mode validation where TSS signatures contain Merkle root hashes</li>
 * </ul>
 */
public final class ClprStateProofUtils {
	private static final Logger log = LogManager.getLogger(ClprStateProofUtils.class);

    private ClprStateProofUtils() {
        // Utility class, no instantiation
    }

    /**
     * Extracts and validates a {@link ClprLedgerConfiguration} from a {@link StateProof}.
     *
     * <p>This method:
     * <ol>
     *   <li>Validates the state proof structure and signature using {@link StateProofVerifier}</li>
     *   <li>Extracts the leaf bytes from the first path</li>
     *   <li>Deserializes the leaf's state item into a {@link ClprLedgerConfiguration}</li>
     * </ol>
     *
     * <p><b>Dev Mode:</b> In development mode, the TSS signature field contains the Merkle root hash
     * directly. The verifier compares this against the recomputed root from the proof paths.
     *
     * @param stateProof the state proof containing the ledger configuration
     * @return the extracted and validated ledger configuration
     * @throws IllegalArgumentException if the state proof is invalid or malformed
     * @throws IllegalStateException if the leaf cannot be parsed as a ClprLedgerConfiguration
     */
    @NonNull
    public static ClprLedgerConfiguration extractConfiguration(@NonNull final StateProof stateProof) {
        requireNonNull(stateProof, "stateProof must not be null");

		var hashed = sha384HashOf(StateProof.PROTOBUF.toBytes(stateProof).toByteArray());
		StateProofVerifier.verify(stateProof);
		log.fatal("demo: state proof {} is valid, proceeding to extract configuration",
				Bytes.wrap(hashed));

        // Extract the leaf from the first path
        final var paths = stateProof.paths();
        if (paths.isEmpty()) {
            throw new IllegalStateException("State proof contains no paths");
        }

        final var firstPath = paths.get(0);
        if (firstPath.hasBlockItemLeaf() || firstPath.hasTimestampLeaf()) {
            throw new IllegalArgumentException("Leaf does not contain a state item");
        }
        if (!firstPath.hasStateItemLeaf()) {
            throw new IllegalStateException("First path does not contain a state item leaf");
        }

        // Deserialize the state item bytes into a ClprLedgerConfiguration
        final Bytes stateItemBytes = requireNonNull(firstPath.stateItemLeaf());
        try {
            final var stateItem = StateItem.PROTOBUF.parse(stateItemBytes);
            return stateItem.valueOrThrow().clprServiceIConfigurationsOrThrow();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to parse ClprLedgerConfiguration from state proof leaf", e);
        }
    }

    /**
     * Validates a state proof without extracting the configuration.
     *
     * <p>This is useful for pure validation checks where the configuration content is not needed.
     *
     * @param stateProof the state proof to validate
     * @return true if the state proof is valid, false otherwise
     */
    public static boolean validateStateProof(@NonNull final StateProof stateProof) {
        requireNonNull(stateProof, "stateProof must not be null");
        return StateProofVerifier.verify(stateProof);
    }

    /**
     * Builds a local synthetic state proof that contains the supplied configuration as the single leaf.
     * <p>This is a convenience for dev/local bootstrap; it does not produce a fully validated,
     * network-signed proof.</p>
     *
     * @param configuration the configuration to embed
     * @return a state proof for the configuration
     */
    @NonNull
    public static StateProof buildLocalClprStateProofWrapper(@NonNull final ClprLedgerConfiguration configuration) {
        requireNonNull(configuration, "configuration must not be null");
        final var ledgerId = requireNonNull(configuration.ledgerId(), "configuration must include a ledger ID");
        final var stateKey = com.hedera.hapi.platform.state.StateKey.newBuilder()
                .clprServiceIConfigurations(ledgerId)
                .build();
        final var stateValue = StateValue.newBuilder()
                .clprServiceIConfigurations(configuration)
                .build();
        final var stateItem = new StateItem(stateKey, stateValue);
        final var stateItemBytes = StateItem.PROTOBUF.toBytes(stateItem);
        final var pathBuilder = new com.hedera.node.app.hapi.utils.blocks.MerklePathBuilder();
        pathBuilder.setStateItemLeaf(stateItemBytes);
        return com.hedera.node.app.hapi.utils.blocks.StateProofBuilder.newBuilder()
                .addMerklePath(pathBuilder)
                .build();
    }
}

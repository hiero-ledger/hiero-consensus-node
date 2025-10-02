// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * This is a specific validator for consensus round related tests. It allows defining custom validations related to
 * {@link ConsensusRound}
 *
 * Each custom validation should be initialized in the constructor and added to the set of validations.
 */
public class ConsensusRoundValidator {

    private static final Set<ConsensusRoundComparisonValidation> validationsForDifferentNodes =
            Set.of(RoundInternalEqualityValidation.INSTANCE);
    private static final Set<ConsensusRoundConsistencyValidation> validationsForSameNode = Set.of(
            RoundInternalConsistencyValidation.INSTANCE,
            RoundTimestampCheckerValidation.INSTANCE,
            RoundAncientThresholdIncreasesValidation.INSTANCE);

    private ConsensusRoundValidator() {}

    /**
     * Validates the given {@link ConsensusRound} objects coming from separate nodes
     *
     * @param rounds1 the first list of rounds to use for validation from one node
     * @param rounds2 the second list of rounds to use for validation from another node
     */
    public static void validate(
            @NonNull final List<ConsensusRound> rounds1, @NonNull final List<ConsensusRound> rounds2) {
        assertThat(rounds1)
                .withFailMessage(String.format(
                        "The number of consensus rounds is not the same."
                                + "first argument has %d rounds, second has %d rounds",
                        rounds1.size(), rounds2.size()))
                .hasSameSizeAs(rounds2);

        for (final ConsensusRoundComparisonValidation validation : validationsForDifferentNodes) {
            for (int i = 0, n = rounds1.size(); i < n; i++) {
                validation.validate(rounds1.get(i), rounds2.get(i));
            }
        }
    }

    /**
     * Validates the given {@link ConsensusRound} object coming from separate nodes
     *
     * @param round1 the first round to use for validation from one node
     * @param round2 the second round to use for validation from another node
     */
    public static void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound round2) {
        validate(List.of(round1), List.of(round2));
    }

    /**
     * Validates the given {@link ConsensusRound} objects coming from a single node
     *
     * @param rounds the list of rounds to use for validation from one node
     */
    public static void validate(@NonNull final List<ConsensusRound> rounds) {
        for (final ConsensusRoundConsistencyValidation validation : validationsForSameNode) {
            validation.validate(rounds);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
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

    private final Set<ConsensusRoundValidation> validationsForDifferentNodes = new HashSet<>();
    private final Set<ConsensusRoundValidation> validationsForSameNode = new HashSet<>();

    /**
     * Creates a new instance of the validator with all available validations for {@link ConsensusRound}.
     */
    public ConsensusRoundValidator() {
        validationsForDifferentNodes.add(new RoundTimestampCheckerValidation());
        validationsForDifferentNodes.add(new RoundInternalEqualityValidation());
        validationsForSameNode.add(new RoundAncientThresholdIncreasesValidation());
    }

    /**
     * Validates the given {@link ConsensusRound} objects coming from separate nodes
     *
     * @param rounds1 the first list of rounds to use for validation from one node
     * @param rounds2 the second list of rounds to use for validation from another node
     */
    public void validate(@NonNull final List<ConsensusRound> rounds1, @NonNull final List<ConsensusRound> rounds2) {
        assertThat(rounds1)
                .withFailMessage(String.format(
                        "The number of consensus rounds is not the same."
                                + "first argument has %d rounds, second has %d rounds",
                        rounds1.size(), rounds2.size()))
                .hasSameSizeAs(rounds2);

        for (final ConsensusRoundValidation validation : validationsForDifferentNodes) {
            for (int i = 0; i < rounds1.size(); i++) {
                validation.validate(rounds1.get(i), rounds2.get(i));
            }
        }

        for (final ConsensusRoundValidation validation : validationsForSameNode) {
            if (rounds1.size() > 1) {
                for (int i = 0; i < rounds1.size() - 1; i++) {
                    validation.validate(rounds1.get(i), rounds1.get(i + 1));
                    validation.validate(rounds2.get(i), rounds2.get(i + 1));
                }
            }
        }
    }
}

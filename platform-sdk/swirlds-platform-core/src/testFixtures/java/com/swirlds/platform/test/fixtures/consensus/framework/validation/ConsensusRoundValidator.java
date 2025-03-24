// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * This is a specific validator for consensus round related tests. It allows defining custom validations related to
 * {@link ConsensusRound}
 *
 * Each custom validation should be initialized in the constructor and added to the set of validations.
 */
public class ConsensusRoundValidator {

    private final Set<ConsensusRoundValidation> validations;

    /**
     * Enum that defines whether a given validation needs consensus rounds coming from the same node
     * or from different nodes
     */
    enum ConsensusRoundsNodeOrigin {
        SAME,
        DIFFERENT
    }

    /**
     * Creates a new instance of the validator with all available validations for {@link ConsensusRound}.
     */
    public ConsensusRoundValidator() {
        validations = new HashSet<>();
        validations.add(new RoundTimestampCheckerValidation());
        validations.add(new RoundInternalEqualityValidation());
        validations.add(new RoundAncientThresholdIncreasesValidation());
    }

    /**
     * Validates the given {@link ConsensusRound} objects coming from separate nodes
     *
     * @param rounds1 the first list of rounds to use for validation from one node
     * @param rounds2 the second list of rounds to use for validation from another node
     */
    public void validate(@NonNull final List<ConsensusRound> rounds1, @NonNull final List<ConsensusRound> rounds2) {
        final Set<ConsensusRoundValidation> validationsForDifferentNodes = validations.stream()
                .filter(v -> v.getNodeOrigin() == ConsensusRoundsNodeOrigin.DIFFERENT)
                .collect(Collectors.toSet());
        final Set<ConsensusRoundValidation> validationsForSameNode = validations.stream()
                .filter(v -> v.getNodeOrigin() == ConsensusRoundsNodeOrigin.SAME)
                .collect(Collectors.toSet());

        for (final ConsensusRoundValidation validation : validationsForDifferentNodes) {
            for (int i = 0; i < rounds1.size(); i++) {
                validation.validate(rounds1.get(i), rounds2.get(i));
            }
        }

        for (final ConsensusRoundValidation validation : validationsForSameNode) {
            for (int i = 0; i < rounds1.size() - 1; i++) {
                validation.validate(rounds1.get(i), rounds1.get(i + 1));
            }
            for (int i = 0; i < rounds2.size() - 1; i++) {
                validation.validate(rounds2.get(i), rounds2.get(i + 1));
            }
        }
    }
}

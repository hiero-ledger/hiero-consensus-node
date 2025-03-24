// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * This is a specific validator for consensus round related tests. It allows defining custom validations related to
 * {@link ConsensusRound}
 *
 * Each custom validation should be initialized in the constructor and added to the list of validations.
 */
public class ConsensusRoundValidator {

    private final List<ConsensusRoundValidation> consensusRoundValidations;

    /**
     * Creates a new instance of the validator with all available validations for {@link ConsensusRound}.
     */
    public ConsensusRoundValidator() {
        this.consensusRoundValidations = new ArrayList<>();
        consensusRoundValidations.add(new RoundTimestampCheckerValidation());
        consensusRoundValidations.add(new RoundInternalEqualityValidation());
        consensusRoundValidations.add(new RoundAncientThresholdIncreasesValidation());
    }

    /**
     * Validates the given {@link ConsensusRound} objects coming from separate nodes
     *
     * @param round1 the round from one node
     * @param round2 the round from another node
     */
    public void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound round2) {
        for (final ConsensusRoundValidation validation : consensusRoundValidations) {
            validation.validate(round1, round2);
        }
    }
}

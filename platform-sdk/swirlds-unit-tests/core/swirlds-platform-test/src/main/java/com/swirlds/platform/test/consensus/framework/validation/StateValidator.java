// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import static com.swirlds.platform.test.consensus.framework.validation.StateValidator.StateValidationType.SIG_SET_HAS_SIGNATURE;
import static com.swirlds.platform.test.consensus.framework.validation.StateValidator.StateValidationType.STATE_CREATION_TIMESTAMPS;
import static com.swirlds.platform.test.consensus.framework.validation.StateValidator.StateValidationType.STATE_IS_COMPLETE;
import static com.swirlds.platform.test.consensus.framework.validation.StateValidator.StateValidationType.STATE_IS_VERIFIABLE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class StateValidator {

    private final Map<StateValidationType, StateValidation> consensusStateValidationsMap =
            new EnumMap<>(StateValidationType.class);

    private final Map<StateValidationType, SigSetValidation> consensusSigSetValidationsMap =
            new EnumMap<>(StateValidationType.class);

    public static @NonNull StateValidator newInstance() {
        return new StateValidator();
    }

    public @NonNull StateValidator standard() {
        consensusStateValidationsMap.putAll(Map.of(
                STATE_IS_COMPLETE, SignedStateValidation::isComplete,
                STATE_IS_VERIFIABLE, SignedStateValidation::isVerifiable,
                STATE_CREATION_TIMESTAMPS, SignedStateValidation::isWithCorrectCreationTimestamp));

        consensusSigSetValidationsMap.put(SIG_SET_HAS_SIGNATURE, SignatureSetValidation::hasSignature);
        return this;
    }

    public @NonNull List<StateValidation> getConsensusStateValidationsList() {
        return consensusStateValidationsMap.values().stream().toList();
    }

    public @NonNull List<SigSetValidation> getConsensusSigSetValidationsList() {
        return consensusSigSetValidationsMap.values().stream().toList();
    }

    public enum StateValidationType {
        STATE_IS_COMPLETE,
        STATE_IS_VERIFIABLE,
        STATE_CREATION_TIMESTAMPS,
        SIG_SET_HAS_SIGNATURE
    }
}

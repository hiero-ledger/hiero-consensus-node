// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.validators.merkledb.ValidateLeafIndexHalfDiskHashMap;
import com.hedera.statevalidation.validators.servicesstate.AccountValidator;
import com.swirlds.platform.state.MerkleNodeState;
import java.io.IOException;
import java.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistryException;

/**
 * Orchestrates validation execution with optimized traversals.
 * Groups validators to minimize state traversals while maintaining simplicity.
 */
public class ValidationEngine {

    private static final Logger log = LogManager.getLogger(ValidationEngine.class);

    // Can be a validation context in the future
    private final MerkleNodeState merkleNodeState;

    public ValidationEngine() throws ConstructableRegistryException, IOException {
        this.merkleNodeState =
                StateResolver.initState().reservedSignedState().get().getState();
    }

    public void execute(String[] requestedTags) throws Exception {
        Set<String> tagSet = Set.of(requestedTags);

        // Execute independent validators first (no traversal optimization needed)
        executeIndependentValidators(tagSet);

        // Execute traversal-based validators with optimization
        executeTraversalValidators(tagSet);
    }

    private void executeIndependentValidators(Set<String> tags) throws Exception {
        log.info("Executing independent validators...");

        // Only one for PoC simplicity...
        List<Validator> independentValidators = Arrays.asList(new ValidateLeafIndexHalfDiskHashMap());

        for (Validator validator : independentValidators) {
            if (tags.contains(validator.getTag())) {
                log.info("Running validator: {}", validator.getTag());
                validator.validate(merkleNodeState);
            }
        }
    }

    private void executeTraversalValidators(Set<String> tags) throws Exception {
        log.info("Executing traversal validators with optimization...");

        // Only one for PoC simplicity...
        // There would be a couple of validators, which can share path range traversal
        List<Validator> independentValidators = Arrays.asList(new AccountValidator());

        for (Validator validator : independentValidators) {
            if (tags.contains(validator.getTag())) {
                log.info("Running validator: {}", validator.getTag());
                validator.validate(merkleNodeState);
            }
        }
    }
}

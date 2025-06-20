// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions.matchers;

import static com.hedera.services.bdd.spec.assertions.matchers.MatcherUtils.getMismatchedItems;
import static com.hedera.services.bdd.spec.assertions.matchers.MatcherUtils.withEqualFields;
import static com.hedera.services.bdd.spec.assertions.matchers.MatcherUtils.within64Units;

import com.hedera.services.stream.proto.ContractAction;
import com.hedera.services.stream.proto.ContractActions;
import com.hedera.services.stream.proto.ContractBytecode;
import com.hedera.services.stream.proto.ContractStateChange;
import com.hedera.services.stream.proto.ContractStateChanges;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import com.hederahashgraph.api.proto.java.Timestamp;
import java.util.Map;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.shaded.org.hamcrest.Description;
import org.testcontainers.shaded.org.hamcrest.Matcher;
import org.testcontainers.shaded.org.hamcrest.TypeSafeDiagnosingMatcher;
import org.testcontainers.shaded.org.hamcrest.core.IsEqual;

/**
 * Used in assertions to compare {@link TransactionSidecarRecord} objects.
 * E.g., to check for the presence of a specific action, state change, or bytecode.
 *
 * @author vyanev
 */
public final class TransactionSidecarRecordMatcher extends TypeSafeDiagnosingMatcher<TransactionSidecarRecord> {

    private static final Logger log = LogManager.getLogger(TransactionSidecarRecordMatcher.class);

    /**
     * The expected consensus timestamp
     */
    private final Timestamp consensusTimestamp;

    /**
     * The expected actions
     */
    private final ContractActions actions;

    /**
     * The expected state changes
     */
    private final ContractStateChanges stateChanges;

    /**
     * The expected bytecode
     */
    private final ContractBytecode bytecode;

    /**
     * Matcher for the consensus timestamp
     */
    private final Function<Timestamp, Matcher<Timestamp>> timestampMatcher = IsEqual::equalTo;

    /**
     * Matcher for the actions
     */
    private final Function<ContractAction, Matcher<ContractAction>> actionMatcher = action -> {
        final Class<?> stopClass = action.getClass().getSuperclass();
        return withEqualFields(action, stopClass)
                .withCustomMatchersForFields(Map.of(
                        "gas", within64Units(action.getGas()),
                        "gasUsed", within64Units(action.getGasUsed())));
    };

    /**
     * Matcher for the state changes
     */
    private final Function<ContractStateChange, Matcher<ContractStateChange>> stateChangeMatcher = IsEqual::equalTo;

    /**
     * Matcher for the bytecode
     */
    private final Function<ContractBytecode, Matcher<ContractBytecode>> bytecodeMatcher = IsEqual::equalTo;

    private TransactionSidecarRecordMatcher(
            final Timestamp consensusTimestamp,
            final ContractActions contractActions,
            final ContractStateChanges contractStateChanges,
            final ContractBytecode bytecode) {
        super(TransactionSidecarRecord.class);
        this.consensusTimestamp = consensusTimestamp;
        this.actions = contractActions;
        this.stateChanges = contractStateChanges;
        this.bytecode = bytecode;
    }

    /**
     * @return {@code true} if there are expected actions
     */
    public boolean hasActions() {
        return actions != null;
    }

    /**
     * @return {@code true} if there are expected state changes
     */
    public boolean hasStateChanges() {
        return stateChanges != null;
    }

    /**
     * @return a {@link TransactionSidecarRecord} with the expected fields
     */
    public TransactionSidecarRecord toSidecarRecord() {
        final TransactionSidecarRecord.Builder builder = TransactionSidecarRecord.newBuilder();
        if (consensusTimestamp != null) {
            builder.setConsensusTimestamp(consensusTimestamp);
        }
        if (actions != null) {
            builder.setActions(actions);
        }
        if (stateChanges != null) {
            builder.setStateChanges(stateChanges);
        }
        if (bytecode != null) {
            builder.setBytecode(bytecode);
        }
        return builder.build();
    }

    /**
     * Describe the expected values for the {@link TransactionSidecarRecord}
     * @param description {@link Description}
     */
    @Override
    public void describeTo(final Description description) {
        description.appendValue(this.toSidecarRecord());
    }

    /**
     * Checks if the {@link TransactionSidecarRecord} matches the expected values.
     * @param sidecarRecord the {@link TransactionSidecarRecord} to check
     * @param mismatch {@link Description} of the mismatch
     * @return {@code true} if the record's properties match the expected values
     */
    @Override
    public boolean matchesSafely(final TransactionSidecarRecord sidecarRecord, final Description mismatch) {
        return matchesConsensusTimestampOf(sidecarRecord, mismatch)
                && matchesActionsOf(sidecarRecord, mismatch)
                && matchesStateChangesOf(sidecarRecord, mismatch)
                && matchesBytecodeOf(sidecarRecord, mismatch);
    }

    /**
     * @param sidecarRecord the actual {@link TransactionSidecarRecord}
     * @return {@code true} if the consensus timestamp of the given
     * {@link TransactionSidecarRecord} matches the expected value
     */
    public boolean matchesConsensusTimestampOf(final TransactionSidecarRecord sidecarRecord) {
        final Matcher<Timestamp> matcher = timestampMatcher.apply(consensusTimestamp);
        return consensusTimestamp != null && matcher.matches(sidecarRecord.getConsensusTimestamp());
    }

    private boolean matchesConsensusTimestampOf(
            final TransactionSidecarRecord sidecarRecord, final Description mismatch) {
        if (!matchesConsensusTimestampOf(sidecarRecord)) {
            describeMismatch(
                    """
                    ***Mismatch in Consensus Timestamp:***
                    Expected: %s
                    Actual: %s
                    """
                            .formatted(consensusTimestamp, sidecarRecord.getConsensusTimestamp()),
                    mismatch);
            return false;
        }
        return true;
    }

    private boolean matchesActionsOf(final TransactionSidecarRecord sidecarRecord, final Description mismatch) {
        if (actions == null) {
            return true;
        }

        final var expectedItems = actions.getContractActionsList();
        final var actualItems = sidecarRecord.getActions().getContractActionsList();
        final var mismatchedActions = getMismatchedItems(expectedItems, actualItems, actionMatcher);

        if (!mismatchedActions.isEmpty()) {
            describeMismatch(
                    """
                    ***Mismatch in Actions:***
                    The following actions were expected but not found in the actual actions:
                    %s
                    Actual actions:
                    %s
                    """
                            .formatted(
                                    mismatchedActions,
                                    sidecarRecord.getActions().getContractActionsList()),
                    mismatch);
            return false;
        }
        return true;
    }

    private boolean matchesStateChangesOf(final TransactionSidecarRecord sidecarRecord, final Description mismatch) {
        if (stateChanges == null) {
            return true;
        }

        final var expectedItems = stateChanges.getContractStateChangesList();
        final var actualItems = sidecarRecord.getStateChanges().getContractStateChangesList();
        final var mismatchedStateChanges = getMismatchedItems(expectedItems, actualItems, stateChangeMatcher);

        if (!mismatchedStateChanges.isEmpty()) {
            describeMismatch(
                    """
                    ***Mismatch in State Changes:***
                    The following state changes were expected but not found in the actual state changes:
                    %s
                    Actual state changes:
                    %s
                    """
                            .formatted(
                                    mismatchedStateChanges,
                                    sidecarRecord.getStateChanges().getContractStateChangesList()),
                    mismatch);
            return false;
        }
        return true;
    }

    private boolean matchesBytecodeOf(final TransactionSidecarRecord sidecarRecord, final Description mismatch) {
        final Matcher<ContractBytecode> matcher = bytecodeMatcher.apply(bytecode);
        if (bytecode != null && !matcher.matches(sidecarRecord.getBytecode())) {
            describeMismatch(
                    """
                    ***Mismatch in Bytecode:***
                    Expected: %s
                    Actual: %s
                    """
                            .formatted(bytecode, sidecarRecord.getBytecode()),
                    mismatch);
            return false;
        }
        return true;
    }

    /**
     * @return {@link Builder} for the {@link TransactionSidecarRecordMatcher}
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder for the {@link TransactionSidecarRecordMatcher}
     */
    public static class Builder {

        private Timestamp consensusTimestamp;
        private ContractActions actions;
        private ContractStateChanges stateChanges;
        private ContractBytecode bytecode;

        /**
         * @param consensusTimestamp the expected consensus timestamp
         * @return {@code this}
         */
        public Builder setConsensusTimestamp(final Timestamp consensusTimestamp) {
            this.consensusTimestamp = consensusTimestamp;
            return this;
        }

        /**
         * @param actions the expected actions
         * @return {@code this}
         */
        public Builder setActions(final ContractActions actions) {
            this.actions = actions;
            return this;
        }

        /**
         * @param stateChanges the expected state changes
         * @return {@code this}
         */
        public Builder setStateChanges(final ContractStateChanges stateChanges) {
            this.stateChanges = stateChanges;
            return this;
        }

        /**
         * @param bytecode the expected bytecode
         * @return {@code this}
         */
        public Builder setBytecode(final ContractBytecode bytecode) {
            this.bytecode = bytecode;
            return this;
        }

        /**
         * @return {@link TransactionSidecarRecordMatcher} with the expected fields
         */
        public TransactionSidecarRecordMatcher build() {
            return new TransactionSidecarRecordMatcher(consensusTimestamp, actions, stateChanges, bytecode);
        }
    }

    private static void describeMismatch(final String message, final Description mismatch) {
        log.warn(message);
        mismatch.appendText(message);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.ContractActionType;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ValidContractIdsAssertion implements RecordStreamAssertion {

    private final long shard;
    private final long realm;

    public ValidContractIdsAssertion(HapiSpec spec) {
        this.shard = spec.shard();
        this.realm = spec.realm();
    }

    @Override
    public boolean isApplicableToSidecar(TransactionSidecarRecord sidecar) {
        return true;
    }

    @Override
    public boolean testSidecar(TransactionSidecarRecord sidecar) throws AssertionError {
        switch (sidecar.getSidecarRecordsCase()) {
            case STATE_CHANGES -> validateStateChangeIds(sidecar);
            case ACTIONS -> validateActionIds(sidecar);
            case BYTECODE -> validateBytecodeIds(sidecar);
            case SIDECARRECORDS_NOT_SET -> {
                // No-op
            }
        }
        // This validator never officially passes until the end of the test (i.e., it
        // should run on every sidecar record)
        return false;
    }

    private void validateStateChangeIds(@NonNull final TransactionSidecarRecord sidecar) {
        final var stateChanges = sidecar.getStateChanges().getContractStateChangesList();
        for (final var change : stateChanges) {
            if (change.hasContractId()) {
                assertValid(change.getContractId(), "stateChange#contractId", sidecar, this::isValidId);
            }
        }
    }

    private void validateActionIds(@NonNull final TransactionSidecarRecord sidecar) {
        final var actions = sidecar.getActions().getContractActionsList();
        for (final var action : actions) {
            if (action.hasCallingAccount()) {
                assertValid(action.getCallingAccount(), "action#callingAccount", sidecar);
            } else if (action.hasCallingContract()) {
                assertValid(action.getCallingContract(), "action#callingContract", sidecar, this::isValidId);
            }

            if (action.hasRecipientAccount()) {
                assertValid(action.getRecipientAccount(), "action#recipientAccount", sidecar);
            } else if (action.hasRecipientContract()) {
                assertValid(action.getRecipientContract(), "action#recipientContract", sidecar, this::isValidRecipient);
            }

            if (action.getCallType() != ContractActionType.CREATE || action.hasOutput()) {
                final var recipientIsSet =
                        (action.hasRecipientAccount() || action.hasRecipientContract() || action.hasTargetedAddress());
                assertTrue(recipientIsSet, "action is missing recipient (account, contract, or targetedAddress)");
            }

            final var resultIsSet = (action.hasOutput() || action.hasError() || action.hasRevertReason());
            assertTrue(resultIsSet, "action is missing result (output, error, or revertReason) - " + action);
        }
    }

    private void validateBytecodeIds(@NonNull final TransactionSidecarRecord sidecar) {
        final var bytecode = sidecar.getBytecode();
        assertValid(bytecode.getContractId(), "bytecode#contractId", sidecar, this::isValidOrFailedBytecodeCreationId);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }

    interface IdValidator {
        boolean isValid(long shard, long realm, long num);
    }

    private void assertValid(
            @NonNull final ContractID id,
            @NonNull final String label,
            @NonNull final TransactionSidecarRecord sidecar,
            @NonNull final IdValidator validator) {
        assertValid(id.getShardNum(), id.getRealmNum(), id.getContractNum(), "Contract", label, sidecar, validator);
    }

    private void assertValid(
            @NonNull final AccountID id, @NonNull final String label, @NonNull final TransactionSidecarRecord sidecar) {
        assertValid(id.getShardNum(), id.getRealmNum(), id.getAccountNum(), "Account", label, sidecar, this::isValidId);
    }

    private void assertValid(
            final long shardNum,
            final long realmNum,
            final long entityNum,
            @NonNull final String type,
            @NonNull final String label,
            @NonNull final TransactionSidecarRecord sidecar,
            @NonNull final IdValidator validator) {
        if (!validator.isValid(shardNum, realmNum, entityNum)) {
            throw new AssertionError(type + " id (from "
                    + label + " field) "
                    + String.format("%d.%d.%d", shardNum, realmNum, entityNum)
                    + " is not valid in sidecar record " + sidecar);
        }
    }

    private boolean isValidId(long shard, long realm, long num) {
        return shard == this.shard && realm == this.realm && num >= 1 && num < Integer.MAX_VALUE;
    }

    private boolean isValidRecipient(long shard, long realm, long num) {
        return shard == this.shard && realm == this.realm && num >= 0 && num < Integer.MAX_VALUE;
    }

    private boolean isValidOrFailedBytecodeCreationId(long shard, long realm, long num) {
        if (shard == 0 && realm == 0 && num == 0) {
            return true;
        }
        return shard == this.shard && realm == this.realm && num >= 0 && num < Integer.MAX_VALUE;
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.ContractActionType;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link RecordStreamAssertion} that validates contract and account IDs in transaction sidecar records.
 *
 * <p>When constructed with {@code specTxnIds}, this assertion is <b>scoped</b> to only validate sidecars
 * belonging to the specified transactions (matched by consensus timestamp). This prevents cross-test
 * interference on shared {@code @HapiTest} networks where sidecars from other concurrent tests would
 * otherwise be validated and could cause false failures.
 *
 * <p>When constructed without {@code specTxnIds} (or with an empty array), the assertion validates
 * <b>all</b> sidecars on the record stream (the original behavior).
 */
public class ValidContractIdsAssertion implements RecordStreamAssertion {

    private final long shard;
    private final long realm;
    private final HapiSpec spec;
    private final String[] specTxnIds;
    private final Set<Timestamp> trackedTimestamps = ConcurrentHashMap.newKeySet();

    /**
     * Snapshot of txnIds already in the registry at construction time. These are inherited from
     * {@link com.hedera.services.bdd.junit.support.TestLifecycle} shared states and belong to
     * previous specs — not to the current test. They must be ignored during record item matching
     * to prevent cross-test interference on shared {@code @HapiTest} networks.
     */
    private final Map<String, TransactionID> staleTxnIds;

    public ValidContractIdsAssertion(@NonNull final HapiSpec spec) {
        this(spec, new String[0]);
    }

    public ValidContractIdsAssertion(@NonNull final HapiSpec spec, @NonNull final String... specTxnIds) {
        this.spec = spec;
        this.shard = spec.shard();
        this.realm = spec.realm();
        this.specTxnIds = specTxnIds;
        // Snapshot any txnIds already present in the registry — these were inherited from
        // shared states and belong to previous specs, not to the current test
        final var stale = new HashMap<String, TransactionID>();
        for (final var txnName : specTxnIds) {
            spec.registry().getMaybeTxnId(txnName).ifPresent(id -> stale.put(txnName, id));
        }
        this.staleTxnIds = Map.copyOf(stale);
    }

    private boolean isScoped() {
        return specTxnIds.length > 0;
    }

    @Override
    public boolean isApplicableTo(@NonNull final RecordStreamItem item) {
        if (!isScoped()) {
            return false;
        }
        final var observedId = item.getRecord().getTransactionID();
        for (final var txnName : specTxnIds) {
            final var maybeTxnId = spec.registry().getMaybeTxnId(txnName);
            if (maybeTxnId.isPresent()) {
                final var txnId = maybeTxnId.get();
                // Skip stale txnIds inherited from shared states — they belong to previous
                // specs and would cause us to track timestamps from the wrong transactions
                final var staleId = staleTxnIds.get(txnName);
                if (staleId != null && staleId.equals(txnId)) {
                    continue;
                }
                if (BaseIdScreenedAssertion.baseFieldsMatch(txnId, observedId)) {
                    trackedTimestamps.add(item.getRecord().getConsensusTimestamp());
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean test(@NonNull final RecordStreamItem item) throws AssertionError {
        // Items are only used to track consensus timestamps for sidecar filtering;
        // the actual validation happens in testSidecar().
        return false;
    }

    @Override
    public boolean isApplicableToSidecar(@NonNull final TransactionSidecarRecord sidecar) {
        if (!isScoped()) {
            return true;
        }
        return trackedTimestamps.contains(sidecar.getConsensusTimestamp());
    }

    @Override
    public boolean testSidecar(@NonNull final TransactionSidecarRecord sidecar) throws AssertionError {
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

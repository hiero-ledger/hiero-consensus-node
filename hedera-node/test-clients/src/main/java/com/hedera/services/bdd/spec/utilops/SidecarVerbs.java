// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilStateChange.stateChangesToGrpc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.getInitcode;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.assertions.StateChange;
import com.hedera.services.bdd.spec.assertions.matchers.TransactionSidecarRecordMatcher;
import com.hedera.services.bdd.spec.utilops.streams.SidecarValidationOp;
import com.hedera.services.bdd.spec.verification.traceability.ExpectedSidecar;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import com.hedera.services.stream.proto.ContractAction;
import com.hedera.services.stream.proto.ContractActions;
import com.hedera.services.stream.proto.ContractBytecode;
import com.hedera.services.stream.proto.ContractStateChanges;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides utilities to verify sidecar records.
 *
 * @author vyanev
 */
// assertions should not be used in production code
@SuppressWarnings("java:S5960")
public class SidecarVerbs {
    private static final String RUNTIME_CODE = "runtimeBytecode";

    /**
     * A watcher to automatically target if the current HapiSpec has no watcher set.
     */
    public static AtomicReference<SidecarWatcher> GLOBAL_WATCHER = new AtomicReference<>();

    private interface MatcherSpec {
        void customize(TransactionSidecarRecordMatcher.Builder builder, HapiSpec spec, TransactionRecord originRecord);
    }

    /**
     * Creates a {@link HapiSpec}-scoped sidecar watcher that will receive
     * any {@link ExpectedSidecar}s created by the factories that follow
     * in this class.
     *
     * @return A {@link CustomSpecAssert} that will watch for sidecar files.
     */
    public static SidecarValidationOp sidecarValidation() {
        return new SidecarValidationOp();
    }

    /**
     * Expect a sidecar file to be generated for the given transaction name with the given contract state changes,
     * for the default {@link SidecarWatcher}.
     *
     * @param txnName The name of the transaction to expect the sidecar for.
     * @param stateChanges The contract state changes to expect in the sidecar.
     * @return A {@link CustomSpecAssert} that will expect the sidecar file to be generated.
     */
    public static CustomSpecAssert expectContractStateChangesSidecarFor(
            @NonNull final String txnName, @NonNull final List<StateChange> stateChanges) {
        return expectContractStateChangesSidecarFor(txnName, 0, stateChanges);
    }

    /**
     * Expect a sidecar file to be generated for the given transaction name with the given contract state changes,
     * for the default {@link SidecarWatcher}.
     *
     * @param txnName The name of the transaction to expect the sidecar for.
     * @param childIndex The index of the child transaction to expect the sidecar for.
     * @param stateChanges The contract state changes to expect in the sidecar.
     * @return A {@link CustomSpecAssert} that will expect the sidecar file to be generated.
     */
    public static CustomSpecAssert expectContractStateChangesSidecarFor(
            @NonNull final String txnName, final int childIndex, @NonNull final List<StateChange> stateChanges) {
        requireNonNull(txnName);
        requireNonNull(stateChanges);
        return expectSidecarRecord(
                txnName,
                childIndex,
                (builder, spec, originRecord) -> builder.setStateChanges(ContractStateChanges.newBuilder()
                        .addAllContractStateChanges(stateChangesToGrpc(stateChanges, spec))
                        .build()));
    }

    /**
     * Expect a sidecar file to be generated for the given transaction name with the given contract actions,
     * for the default {@link SidecarWatcher}.
     *
     * @param txnName The name of the transaction to expect the sidecar for.
     * @param actions The contract actions to expect in the sidecar.
     * @return a {@link CustomSpecAssert} that will expect the sidecar file to be generated.
     */
    public static CustomSpecAssert expectContractActionSidecarFor(
            @NonNull final String txnName, @NonNull final List<ContractAction> actions) {
        requireNonNull(txnName);
        requireNonNull(actions);
        return expectSidecarRecord(
                txnName,
                (builder, spec, originRecord) -> builder.setActions(ContractActions.newBuilder()
                        .addAllContractActions(actions)
                        .build()));
    }

    /**
     * Expect a sidecar file to be generated with a sidecar record for the given transaction name with the
     * given contract bytecode, for the in-scope {@link SidecarWatcher}.
     *
     * @param txnName The name of the transaction to expect the sidecar for.
     * @param contractName The name of the contract to expect the bytecode for.
     * @param binFileName The name of the bin file to get the init-code from.
     * @param constructorArgs The constructor arguments to use for the init-code.
     * @return {@link CustomSpecAssert} expecting the sidecar file to be generated.
     */
    public static CustomSpecAssert expectContractBytecodeSidecarFor(
            @NonNull final String txnName,
            @NonNull final String contractName,
            @NonNull final String binFileName,
            @NonNull final Object... constructorArgs) {
        requireNonNull(txnName);
        requireNonNull(contractName);
        requireNonNull(binFileName);
        requireNonNull(constructorArgs);
        return expectSidecarRecord(txnName, (builder, spec, originRecord) -> {
            final var contractBytecode = getContractBytecode(contractName).saveResultTo(RUNTIME_CODE);
            allRunFor(spec, contractBytecode);
            builder.setBytecode(ContractBytecode.newBuilder()
                    .setContractId(originRecord.getContractCreateResult().getContractID())
                    .setInitcode(getInitcode(binFileName, constructorArgs))
                    .setRuntimeBytecode(ByteString.copyFrom(spec.registry().getBytes(RUNTIME_CODE)))
                    .build());
        });
    }

    /**
     * Expect a sidecar file to be generated with a record (for the given transaction)
     * that contains the init-code from the given bin file, for the in-scope {@link SidecarWatcher}.
     *
     * @param txnName The name of the transaction to expect the sidecar for.
     * @param binFileName The name of the bin file to get the init-code from.
     * @param constructorArgs The constructor arguments to use for the init-code.
     * @return {@link CustomSpecAssert} expecting the sidecar file to be generated.
     */
    public static CustomSpecAssert expectFailedContractBytecodeSidecarFor(
            @NonNull final String txnName,
            @NonNull final String binFileName,
            @NonNull final Object... constructorArgs) {
        requireNonNull(txnName);
        requireNonNull(binFileName);
        requireNonNull(constructorArgs);
        return expectSidecarRecord(txnName, (builder, spec, originRecord) -> {
            builder.setBytecode(ContractBytecode.newBuilder()
                    .setInitcode(getInitcode(binFileName, constructorArgs))
                    .build());
        });
    }

    /**
     * Expect a sidecar file to be generated with a record (for the given transaction)
     * that contains the runtime bytecode of the given contract.
     *
     * @param txnName The name of the transaction to expect the sidecar for.
     * @param contractName The name of the contract to expect the bytecode for.
     * @return {@link CustomSpecAssert} expecting the sidecar file to be generated.
     */
    public static CustomSpecAssert expectContractBytecodeSansInitcodeFor(
            @NonNull final String txnName, @NonNull final String contractName) {
        return expectContractBytecodeSansInitcodeFor(txnName, 0, contractName);
    }

    /**
     * Expect a sidecar file to be generated with a record (for the given transaction's child)
     * that contains the runtime bytecode of the given contract.
     *
     * @param txnName The name of the transaction to expect the sidecar for.
     * @param contractName The name of the contract to expect the bytecode for.
     * @return {@link CustomSpecAssert} expecting the sidecar file to be generated.
     */
    public static CustomSpecAssert expectContractBytecodeSansInitcodeFor(
            @NonNull final String txnName, final int childIndex, @NonNull final String contractName) {
        requireNonNull(txnName);
        requireNonNull(contractName);
        return expectSidecarRecord(txnName, childIndex, (builder, spec, originRecord) -> {
            final var contractBytecode = getContractBytecode(contractName).saveResultTo(RUNTIME_CODE);
            allRunFor(spec, contractBytecode);
            builder.setBytecode(ContractBytecode.newBuilder()
                    .setContractId(originRecord.getContractCreateResult().getContractID())
                    .setRuntimeBytecode(ByteString.copyFrom(spec.registry().getBytes(RUNTIME_CODE)))
                    .build());
        });
    }

    /**
     * Expect a sidecar file to be generated with a record for the given transaction
     * that contains the given contract ID, init-code, and runtime code.
     *
     * @param txnName the transaction name
     * @param childIndex the child index
     * @param contractID The contract ID.
     * @param initCode The init bytecode of the contract.
     * @param runtimeCode The runtime bytecode of the contract.
     * @return {@link CustomSpecAssert} expecting the sidecar file to be generated.
     */
    public static CustomSpecAssert expectExplicitContractBytecode(
            @NonNull final String txnName,
            final int childIndex,
            @NonNull final ContractID contractID,
            @NonNull final ByteString initCode,
            @NonNull final ByteString runtimeCode) {
        return expectSidecarRecord(
                txnName,
                childIndex,
                (builder, spec, originRecord) -> builder.setBytecode(ContractBytecode.newBuilder()
                        .setContractId(contractID)
                        .setInitcode(initCode)
                        .setRuntimeBytecode(runtimeCode)
                        .build()));
    }

    private static CustomSpecAssert expectSidecarRecord(
            @NonNull final String txnName, @NonNull final MatcherSpec matcherSpec) {
        return expectSidecarRecord(txnName, 0, matcherSpec);
    }

    private static CustomSpecAssert expectSidecarRecord(
            @NonNull final String txnName, final int childIndex, @NonNull final MatcherSpec matcherSpec) {
        return withOpContext((spec, opLog) -> {
            final var txnRecord = getTxnRecord(txnName);
            allRunFor(spec, txnRecord);
            final var builder = TransactionSidecarRecordMatcher.newBuilder()
                    .setConsensusTimestamp(
                            offsetNanos(txnRecord.getResponseRecord().getConsensusTimestamp(), childIndex));
            matcherSpec.customize(builder, spec, txnRecord.getResponseRecord());
            final var watcher = Optional.ofNullable(spec.getSidecarWatcher())
                    .or(() -> Optional.ofNullable(GLOBAL_WATCHER.get()))
                    .orElseThrow();
            if (watcher == null) {
                throw new IllegalStateException("Sidecar expectation added without a watcher");
            }
            watcher.addExpectedSidecar(new ExpectedSidecar(spec.getName(), builder.build()));
        });
    }

    private static Timestamp offsetNanos(@NonNull final Timestamp timestamp, final int nanos) {
        return Timestamp.newBuilder()
                .setSeconds(timestamp.getSeconds())
                .setNanos(timestamp.getNanos() + nanos)
                .build();
    }
}

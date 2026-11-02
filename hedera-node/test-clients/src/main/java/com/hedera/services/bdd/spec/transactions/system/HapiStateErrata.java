// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.system;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.StateErrataTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Submits a {@code StateErrata} transaction, which corrects network state by applying state changes.
 *
 * <p>State changes are given as PBJ types (or as raw serialized bytes, to test malformed input), optionally
 * computed from the spec at submission time.
 */
public class HapiStateErrata extends HapiTxnOp<HapiStateErrata> {
    private Function<HapiSpec, List<Bytes>> serializedChangesFn = spec -> List.of();

    public HapiStateErrata() {
        // No state changes by default
    }

    /**
     * Sets the state changes of this errata.
     *
     * @param stateChanges the state changes
     * @return this
     */
    public HapiStateErrata withStateChanges(@NonNull final StateChange... stateChanges) {
        final var serialized =
                Arrays.stream(stateChanges).map(StateChange.PROTOBUF::toBytes).toList();
        this.serializedChangesFn = spec -> serialized;
        return this;
    }

    /**
     * Sets the state changes of this errata, computed from the spec at submission time.
     *
     * @param stateChangesFn the function computing the state changes
     * @return this
     */
    public HapiStateErrata withStateChanges(@NonNull final Function<HapiSpec, List<StateChange>> stateChangesFn) {
        requireNonNull(stateChangesFn);
        this.serializedChangesFn = spec -> stateChangesFn.apply(spec).stream()
                .map(StateChange.PROTOBUF::toBytes)
                .toList();
        return this;
    }

    /**
     * Sets the serialized state changes of this errata verbatim, which may be malformed.
     *
     * @param serializedChanges the serialized state changes
     * @return this
     */
    public HapiStateErrata withSerializedStateChanges(@NonNull final Bytes... serializedChanges) {
        final var serialized = List.of(serializedChanges);
        this.serializedChangesFn = spec -> serialized;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.StateErrata;
    }

    @Override
    protected HapiStateErrata self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(@NonNull final HapiSpec spec) throws Throwable {
        final var serialized = serializedChangesFn.apply(spec);
        final StateErrataTransactionBody opBody = spec.txns()
                .<StateErrataTransactionBody, StateErrataTransactionBody.Builder>body(
                        StateErrataTransactionBody.class,
                        b -> serialized.forEach(
                                change -> b.addStateChanges(ByteString.copyFrom(change.toByteArray()))));
        return b -> b.setStateErrata(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }
}

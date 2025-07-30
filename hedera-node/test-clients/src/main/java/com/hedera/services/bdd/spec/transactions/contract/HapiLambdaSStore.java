// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.LambdaSStore;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.CreatedHookId;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.LambdaMappingEntries;
import com.hederahashgraph.api.proto.java.LambdaSStoreTransactionBody;
import com.hederahashgraph.api.proto.java.LambdaStorageSlot;
import com.hederahashgraph.api.proto.java.LambdaStorageUpdate;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class HapiLambdaSStore extends HapiTxnOp<HapiLambdaSStore> {
    private List<LambdaStorageSlot> slots = new ArrayList<>();
    private List<LambdaMappingEntries> entries = new ArrayList<>();

    @NonNull
    private final HookEntityId.EntityIdOneOfType ownerType;

    @NonNull
    private final String ownerName;

    private final long hookId;

    public static HapiLambdaSStore accountLambdaSStore(@NonNull final String account, final long index) {
        return new HapiLambdaSStore(HookEntityId.EntityIdOneOfType.ACCOUNT_ID, account, index);
    }

    public HapiLambdaSStore putSlot(Bytes key, Bytes value) {
        return slots(key, value);
    }

    public HapiLambdaSStore removeSlot(Bytes key) {
        return slots(key, Bytes.EMPTY);
    }

    public HapiLambdaSStore putMappingEntry(Bytes mappingSlot, Bytes key, Bytes value) {
        return entries(mappingSlot, List.of(key, value));
    }

    public HapiLambdaSStore removeMappingEntry(Bytes mappingSlot, Bytes key) {
        return entries(mappingSlot, List.of(key, Bytes.EMPTY));
    }

    private HapiLambdaSStore(
            @NonNull final HookEntityId.EntityIdOneOfType entityType,
            @NonNull final String ownerName,
            final long hookId) {
        this.ownerType = requireNonNull(entityType);
        this.ownerName = requireNonNull(ownerName);
        this.hookId = hookId;
    }

    @Override
    public HederaFunctionality type() {
        return LambdaSStore;
    }

    @Override
    protected HapiLambdaSStore self() {
        return this;
    }

    @Override
    protected long feeFor(@NonNull final HapiSpec spec, @NonNull final Transaction txn, final int numPayerKeys)
            throws Throwable {
        return 1L;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        final var op = spec.txns()
                .<LambdaSStoreTransactionBody, LambdaSStoreTransactionBody.Builder>body(
                        LambdaSStoreTransactionBody.class, b -> {
                            final var idBuilder = CreatedHookId.newBuilder().setHookId(hookId);
                            switch (ownerType) {
                                case ACCOUNT_ID ->
                                    idBuilder.setEntityId(com.hederahashgraph.api.proto.java.HookEntityId.newBuilder()
                                            .setAccountId(asId(ownerName, spec)));
                                default -> throw new IllegalArgumentException("Unsupported owner type: " + ownerType);
                            }
                            b.setHookId(idBuilder);
                            slots.forEach(slot -> b.addStorageUpdates(
                                    LambdaStorageUpdate.newBuilder().setStorageSlot(slot)));
                            entries.forEach(entries -> b.addStorageUpdates(
                                    LambdaStorageUpdate.newBuilder().setMappingEntries(entries)));
                        });
        return b -> b.setLambdaSstore(op);
    }

    private HapiLambdaSStore slots(@NonNull final Bytes... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Slots must be key-value pairs");
        }
        for (int i = 0; i < kv.length; i += 2) {
            slots.add(LambdaStorageSlot.newBuilder()
                    .setKey(fromPbj(kv[i]))
                    .setValue(fromPbj(kv[i + 1]))
                    .build());
        }
        return this;
    }

    private HapiLambdaSStore entries(@NonNull final Bytes mappingSlot, @NonNull final List<Bytes> kv) {
        if (kv.size() % 2 != 0) {
            throw new IllegalArgumentException("Mapping entries must be key-value pairs");
        }
        final var builder = LambdaMappingEntries.newBuilder().setMappingSlot(fromPbj(mappingSlot));
        for (int i = 0, n = kv.size(); i < n; i += 2) {
            builder.addEntries(LambdaStorageSlot.newBuilder()
                    .setKey(fromPbj(kv.get(i)))
                    .setValue(fromPbj(kv.get(i + 1)))
                    .build());
        }
        return this;
    }
}

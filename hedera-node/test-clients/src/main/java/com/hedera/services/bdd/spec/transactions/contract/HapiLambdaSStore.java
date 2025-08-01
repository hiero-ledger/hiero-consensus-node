// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.LambdaSStore;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.hooks.LambdaMappingEntries;
import com.hedera.hapi.node.hooks.LambdaMappingEntry;
import com.hedera.hapi.node.hooks.LambdaStorageSlot;
import com.hedera.hapi.node.hooks.LambdaStorageUpdate;
import com.hedera.hapi.node.hooks.legacy.LambdaSStoreTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.HookId;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class HapiLambdaSStore extends HapiTxnOp<HapiLambdaSStore> {
    private List<LambdaStorageUpdate> updates = new ArrayList<>();

    @NonNull
    private final HookEntityId.EntityIdOneOfType ownerType;

    @NonNull
    private final String ownerName;

    private final long hookId;

    private boolean omitEntityId = false;

    public HapiLambdaSStore omittingEntityId() {
        this.omitEntityId = true;
        return this;
    }

    public HapiLambdaSStore putSlot(Bytes key, Bytes value) {
        return slots(key, value);
    }

    public HapiLambdaSStore removeSlot(Bytes key) {
        return slots(key, Bytes.EMPTY);
    }

    public HapiLambdaSStore putMappingEntry(@NonNull final Bytes mappingSlot, @NonNull final LambdaMappingEntry entry) {
        return switch (entry.entryKey().kind()) {
            case UNSET -> throw new IllegalArgumentException("Mapping entry must have a key or preimage");
            case KEY -> putMappingEntryWithKey(mappingSlot, entry.keyOrThrow(), entry.value());
            case PREIMAGE -> putMappingEntryWithPreimage(mappingSlot, entry.preimageOrThrow(), entry.value());
        };
    }

    public HapiLambdaSStore putMappingEntryWithKey(
            @NonNull final Bytes mappingSlot, @NonNull final Bytes key, @NonNull final Bytes value) {
        return entries(mappingSlot, List.of(MappingKey.key(key)), List.of(value));
    }

    public HapiLambdaSStore putMappingEntryWithPreimage(
            @NonNull final Bytes mappingSlot, @NonNull final Bytes preimage, @NonNull final Bytes value) {
        return entries(mappingSlot, List.of(MappingKey.preimage(preimage)), List.of(value));
    }

    public HapiLambdaSStore removeMappingEntry(@NonNull final Bytes mappingSlot, @NonNull final Bytes key) {
        return entries(mappingSlot, List.of(MappingKey.key(key)), List.of(Bytes.EMPTY));
    }

    public HapiLambdaSStore removeMappingEntryWithPreimage(
            @NonNull final Bytes mappingSlot, @NonNull final Bytes preimage) {
        return entries(mappingSlot, List.of(MappingKey.preimage(preimage)), List.of(Bytes.EMPTY));
    }

    public HapiLambdaSStore(
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
        return ONE_HBAR;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        final var op = spec.txns()
                .<LambdaSStoreTransactionBody, LambdaSStoreTransactionBody.Builder>body(
                        LambdaSStoreTransactionBody.class, b -> {
                            final var idBuilder = HookId.newBuilder().setHookId(hookId);
                            if (!omitEntityId) {
                                switch (ownerType) {
                                    case ACCOUNT_ID ->
                                        idBuilder.setEntityId(
                                                com.hederahashgraph.api.proto.java.HookEntityId.newBuilder()
                                                        .setAccountId(asId(ownerName, spec)));
                                    default ->
                                        throw new IllegalArgumentException("Unsupported owner type: " + ownerType);
                                }
                            }
                            b.setHookId(idBuilder)
                                    .addAllStorageUpdates(updates.stream()
                                            .map(update -> pbjToProto(
                                                    update,
                                                    LambdaStorageUpdate.class,
                                                    com.hedera.hapi.node.hooks.legacy.LambdaStorageUpdate.class))
                                            .toList());
                        });
        return b -> b.setLambdaSstore(op);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final Function<HapiSpec, Key> ownerSigner =
                switch (ownerType) {
                    case ACCOUNT_ID ->
                        spec -> {
                            final var ownerKey = spec.registry().getKey(ownerName);
                            final var payerKey = spec.registry().getKey(effectivePayer(spec));
                            if (ownerKey.equals(payerKey)) {
                                return Key.getDefaultInstance();
                            } else {
                                return ownerKey;
                            }
                        };
                    default -> throw new IllegalArgumentException("Unsupported owner type: " + ownerType);
                };
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), ownerSigner);
    }

    private HapiLambdaSStore slots(@NonNull final Bytes... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Slots must be key-value pairs");
        }
        for (int i = 0; i < kv.length; i += 2) {
            updates.add(LambdaStorageUpdate.newBuilder()
                    .storageSlot(LambdaStorageSlot.newBuilder().key(kv[i]).value(kv[i + 1]))
                    .build());
        }
        return this;
    }

    private record MappingKey(@Nullable Bytes key, @Nullable Bytes preimage) {
        public static MappingKey key(@NonNull final Bytes key) {
            return new MappingKey(requireNonNull(key), null);
        }

        public static MappingKey preimage(@NonNull final Bytes preimage) {
            return new MappingKey(null, requireNonNull(preimage));
        }

        public LambdaMappingEntry.EntryKeyOneOfType type() {
            if (key != null) {
                return LambdaMappingEntry.EntryKeyOneOfType.KEY;
            } else {
                return LambdaMappingEntry.EntryKeyOneOfType.PREIMAGE;
            }
        }
    }

    private HapiLambdaSStore entries(
            @NonNull final Bytes mappingSlot, @NonNull final List<MappingKey> keys, @NonNull final List<Bytes> values) {
        final var builder = LambdaMappingEntries.newBuilder().mappingSlot(mappingSlot);
        final List<LambdaMappingEntry> entries = new ArrayList<>();
        for (int i = 0, n = keys.size(); i < n; i++) {
            final var entryBuilder = LambdaMappingEntry.newBuilder().value(values.get(i));
            final var key = keys.get(i);
            switch (key.type()) {
                case KEY -> entryBuilder.key(requireNonNull(key.key()));
                case PREIMAGE -> entryBuilder.preimage(requireNonNull(key.preimage()));
                default -> throw new IllegalArgumentException("Unsupported mapping key type - " + key.type());
            }
            entries.add(entryBuilder.build());
        }
        builder.entries(entries);
        updates.add(LambdaStorageUpdate.newBuilder().mappingEntries(builder).build());
        return this;
    }
}

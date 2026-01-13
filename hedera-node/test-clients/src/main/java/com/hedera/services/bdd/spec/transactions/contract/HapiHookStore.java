// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asContractId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HookStore;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.hooks.EvmHookMappingEntries;
import com.hedera.hapi.node.hooks.EvmHookMappingEntry;
import com.hedera.hapi.node.hooks.EvmHookStorageSlot;
import com.hedera.hapi.node.hooks.EvmHookStorageUpdate;
import com.hedera.hapi.node.hooks.legacy.HookStoreTransactionBody;
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

public class HapiHookStore extends HapiTxnOp<HapiHookStore> {
    private List<EvmHookStorageUpdate> updates = new ArrayList<>();

    @NonNull
    private final HookEntityId.EntityIdOneOfType ownerType;

    @NonNull
    private final String ownerName;

    private final long hookId;

    private boolean omitEntityId = false;

    public HapiHookStore omittingEntityId() {
        this.omitEntityId = true;
        return this;
    }

    public HapiHookStore putSlot(Bytes key, Bytes value) {
        return slots(key, value);
    }

    public HapiHookStore removeSlot(Bytes key) {
        return slots(key, Bytes.EMPTY);
    }

    public HapiHookStore putMappingEntry(@NonNull final Bytes mappingSlot, @NonNull final EvmHookMappingEntry entry) {
        return switch (entry.entryKey().kind()) {
            case UNSET -> throw new IllegalArgumentException("Mapping entry must have a key or preimage");
            case KEY -> putMappingEntryWithKey(mappingSlot, entry.keyOrThrow(), entry.value());
            case PREIMAGE -> putMappingEntryWithPreimage(mappingSlot, entry.preimageOrThrow(), entry.value());
        };
    }

    public HapiHookStore putMappingEntryWithKey(
            @NonNull final Bytes mappingSlot, @NonNull final Bytes key, @NonNull final Bytes value) {
        return entries(mappingSlot, List.of(MappingKey.key(key)), List.of(value));
    }

    public HapiHookStore putMappingEntryWithPreimage(
            @NonNull final Bytes mappingSlot, @NonNull final Bytes preimage, @NonNull final Bytes value) {
        return entries(mappingSlot, List.of(MappingKey.preimage(preimage)), List.of(value));
    }

    public HapiHookStore removeMappingEntry(@NonNull final Bytes mappingSlot, @NonNull final Bytes key) {
        return entries(mappingSlot, List.of(MappingKey.key(key)), List.of(Bytes.EMPTY));
    }

    public HapiHookStore removeMappingEntryWithPreimage(
            @NonNull final Bytes mappingSlot, @NonNull final Bytes preimage) {
        return entries(mappingSlot, List.of(MappingKey.preimage(preimage)), List.of(Bytes.EMPTY));
    }

    public HapiHookStore(
            @NonNull final HookEntityId.EntityIdOneOfType entityType,
            @NonNull final String ownerName,
            final long hookId) {
        this.ownerType = requireNonNull(entityType);
        this.ownerName = requireNonNull(ownerName);
        this.hookId = hookId;
    }

    @Override
    public HederaFunctionality type() {
        return HookStore;
    }

    @Override
    protected HapiHookStore self() {
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
                .<HookStoreTransactionBody, HookStoreTransactionBody.Builder>body(HookStoreTransactionBody.class, b -> {
                    final var idBuilder = HookId.newBuilder().setHookId(hookId);
                    if (!omitEntityId) {
                        switch (ownerType) {
                            case ACCOUNT_ID ->
                                idBuilder.setEntityId(com.hederahashgraph.api.proto.java.HookEntityId.newBuilder()
                                        .setAccountId(asId(ownerName, spec)));
                            case CONTRACT_ID ->
                                idBuilder.setEntityId(com.hederahashgraph.api.proto.java.HookEntityId.newBuilder()
                                        .setContractId(asContractId(ownerName, spec)));
                            default -> throw new IllegalArgumentException("Unsupported owner type: " + ownerType);
                        }
                    }
                    b.setHookId(idBuilder)
                            .addAllStorageUpdates(updates.stream()
                                    .map(update -> pbjToProto(
                                            update,
                                            EvmHookStorageUpdate.class,
                                            com.hedera.hapi.node.hooks.legacy.EvmHookStorageUpdate.class))
                                    .toList());
                });
        return b -> b.setHookStore(op);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final Function<HapiSpec, Key> ownerSigner =
                switch (ownerType) {
                    case ACCOUNT_ID, CONTRACT_ID ->
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

    private HapiHookStore slots(@NonNull final Bytes... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Slots must be key-value pairs");
        }
        for (int i = 0; i < kv.length; i += 2) {
            updates.add(EvmHookStorageUpdate.newBuilder()
                    .storageSlot(EvmHookStorageSlot.newBuilder().key(kv[i]).value(kv[i + 1]))
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

        public EvmHookMappingEntry.EntryKeyOneOfType type() {
            if (key != null) {
                return EvmHookMappingEntry.EntryKeyOneOfType.KEY;
            } else {
                return EvmHookMappingEntry.EntryKeyOneOfType.PREIMAGE;
            }
        }
    }

    private HapiHookStore entries(
            @NonNull final Bytes mappingSlot, @NonNull final List<MappingKey> keys, @NonNull final List<Bytes> values) {
        final var builder = EvmHookMappingEntries.newBuilder().mappingSlot(mappingSlot);
        final List<EvmHookMappingEntry> entries = new ArrayList<>();
        for (int i = 0, n = keys.size(); i < n; i++) {
            final var entryBuilder = EvmHookMappingEntry.newBuilder().value(values.get(i));
            final var key = keys.get(i);
            switch (key.type()) {
                case KEY -> entryBuilder.key(requireNonNull(key.key()));
                case PREIMAGE -> entryBuilder.preimage(requireNonNull(key.preimage()));
                default -> throw new IllegalArgumentException("Unsupported mapping key type - " + key.type());
            }
            entries.add(entryBuilder.build());
        }
        builder.entries(entries);
        updates.add(EvmHookStorageUpdate.newBuilder().mappingEntries(builder).build());
        return this;
    }
}

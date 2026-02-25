// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.node;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.netOf;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.RegisteredNodeCreateTransactionBody;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiRegisteredNodeCreate extends HapiTxnOp<HapiRegisteredNodeCreate> {
    private static final Logger LOG = LogManager.getLogger(HapiRegisteredNodeCreate.class);

    private final String name;
    private Optional<String> description = Optional.empty();
    private List<RegisteredServiceEndpoint> endpoints = List.of(defaultEndpoint());

    private Optional<String> adminKeyName = Optional.empty();
    private Optional<KeyShape> adminKeyShape = Optional.empty();

    @Nullable
    private Key adminKey;

    @Nullable
    private LongConsumer registeredNodeIdObserver;

    private boolean advertiseCreation = false;

    public HapiRegisteredNodeCreate(@NonNull final String name) {
        this.name = requireNonNull(name);
    }

    @Override
    protected Key lookupKey(final HapiSpec spec, final String name) {
        return spec.registry().getKey(name);
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.RegisteredNodeCreate;
    }

    public HapiRegisteredNodeCreate adminKey(@NonNull final String keyName) {
        this.adminKeyName = Optional.of(requireNonNull(keyName));
        return this;
    }

    public HapiRegisteredNodeCreate description(@NonNull final String description) {
        this.description = Optional.of(requireNonNull(description));
        return this;
    }

    public HapiRegisteredNodeCreate serviceEndpoints(@NonNull final List<RegisteredServiceEndpoint> endpoints) {
        this.endpoints = requireNonNull(endpoints);
        return this;
    }

    public HapiRegisteredNodeCreate advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    public HapiRegisteredNodeCreate exposingCreatedIdTo(@NonNull final LongConsumer observer) {
        this.registeredNodeIdObserver = requireNonNull(observer);
        return this;
    }

    private void genKeysFor(final HapiSpec spec) {
        adminKey = adminKey == null ? netOf(spec, adminKeyName, adminKeyShape) : adminKey;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(@NonNull final HapiSpec spec) throws Throwable {
        genKeysFor(spec);
        final var opBody = RegisteredNodeCreateTransactionBody.newBuilder()
                .setAdminKey(adminKey)
                .clearServiceEndpoint()
                .addAllServiceEndpoint(endpoints);
        description.ifPresent(opBody::setDescription);
        return b -> b.setRegisteredNodeCreate(opBody.build());
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), ignore -> adminKey);
    }

    @Override
    protected void updateStateOf(@NonNull final HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }
        final var newId = lastReceipt.getRegisteredNodeId();
        if (verboseLoggingOn) {
            LOG.info("Created registered node {} with ID {}.", name, newId);
        }
        if (advertiseCreation) {
            final String banner = "\n\n"
                    + bannerWith(
                            "Created registered node '%s' with id '%d'.".formatted(description.orElse(name), newId));
            LOG.info(banner);
        }
        if (registeredNodeIdObserver != null) {
            registeredNodeIdObserver.accept(newId);
        }
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper();
        Optional.ofNullable(lastReceipt).ifPresent(receipt -> helper.add("created", receipt.getRegisteredNodeId()));
        return helper;
    }

    private static RegisteredServiceEndpoint defaultEndpoint() {
        return RegisteredServiceEndpoint.newBuilder()
                .setIpAddress(ByteString.copyFrom(new byte[] {127, 0, 0, 1}))
                .setPort(8080)
                .setRequiresTls(false)
                .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .setEndpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                        .build())
                .build();
    }

    @Override
    protected HapiRegisteredNodeCreate self() {
        return this;
    }

    @Override
    protected long feeFor(@NonNull final HapiSpec spec, @NonNull final Transaction txn, final int numPayerKeys)
            throws Throwable {
        return spec.fees()
                .forActivityBasedOp(HederaFunctionality.RegisteredNodeCreate, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();
        accumulator.addVpt(Math.max(0, svo.getTotalSigCount() - 1));
        return AdapterUtils.feeDataFrom(accumulator);
    }
}

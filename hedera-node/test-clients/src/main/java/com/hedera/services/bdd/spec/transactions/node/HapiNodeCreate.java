// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.node;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.endpointFor;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.netOf;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NodeCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiNodeCreate extends HapiTxnOp<HapiNodeCreate> {
    private static final Logger LOG = LogManager.getLogger(HapiNodeCreate.class);

    private boolean advertiseCreation = false;
    private boolean useAvailableSubProcessPorts = false;
    private final String nodeName;
    private Optional<AccountID> accountId = Optional.empty();
    private Optional<Long> accountNum = Optional.empty();
    private Optional<String> description = Optional.empty();
    private List<ServiceEndpoint> gossipEndpoints =
            Arrays.asList(endpointFor("192.168.1.200", 123), endpointFor("192.168.1.201", 123));
    private List<ServiceEndpoint> grpcEndpoints = List.of(
            ServiceEndpoint.newBuilder().setDomainName("test.com").setPort(123).build());
    // (FUTURE) Since the introduction of a flag to explicitly enable the web proxy endpoint functionality, a non-empty
    // default here causes some tests to fail with GRPC_WEB_PROXY_NOT_SUPPORTED. Once we can enable
    // nodes.webProxyEndpointsEnabled permanently, we can restore the non-null default.
    private Optional<ServiceEndpoint> grpcWebProxyEndpoint = Optional.empty();
    private Optional<byte[]> gossipCaCertificate = Optional.empty();
    private Optional<byte[]> grpcCertificateHash = Optional.empty();
    private Optional<String> adminKeyName = Optional.empty();
    private Optional<KeyShape> adminKeyShape = Optional.empty();
    private Optional<Boolean> declineReward = Optional.empty();

    @Nullable
    private LongConsumer nodeIdObserver;

    @Nullable
    private Key adminKey;

    public HapiNodeCreate(@NonNull final String nodeName) {
        this.nodeName = nodeName;
    }

    @Override
    protected Key lookupKey(final HapiSpec spec, final String name) {
        return name.equals(nodeName) ? adminKey : spec.registry().getKey(name);
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.NodeCreate;
    }

    public HapiNodeCreate advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    public HapiNodeCreate exposingCreatedIdTo(@NonNull final LongConsumer nodeIdObserver) {
        this.nodeIdObserver = requireNonNull(nodeIdObserver);
        return this;
    }

    public HapiNodeCreate accountId(final AccountID accountID) {
        this.accountId = Optional.of(accountID);
        return this;
    }

    public HapiNodeCreate accountNum(final long accountNum) {
        this.accountNum = Optional.of(accountNum);
        return this;
    }

    public HapiNodeCreate description(final String description) {
        this.description = Optional.of(description);
        return this;
    }

    public HapiNodeCreate withAvailableSubProcessPorts() {
        useAvailableSubProcessPorts = true;
        return this;
    }

    public HapiNodeCreate gossipEndpoint(final List<ServiceEndpoint> gossipEndpoint) {
        this.gossipEndpoints = gossipEndpoint;
        return this;
    }

    public HapiNodeCreate serviceEndpoint(final List<ServiceEndpoint> serviceEndpoint) {
        this.grpcEndpoints = serviceEndpoint;
        return this;
    }

    public HapiNodeCreate grpcWebProxyEndpoint(final ServiceEndpoint grpcWebProxyEndpoint) {
        this.grpcWebProxyEndpoint = Optional.ofNullable(grpcWebProxyEndpoint);
        return this;
    }

    public HapiNodeCreate withNoWebProxyEndpoint() {
        return this.grpcWebProxyEndpoint(null);
    }

    public HapiNodeCreate gossipCaCertificate(@NonNull final Bytes cert) {
        return gossipCaCertificate(cert.toByteArray());
    }

    public HapiNodeCreate gossipCaCertificate(final byte[] gossipCaCertificate) {
        this.gossipCaCertificate = Optional.of(gossipCaCertificate);
        return this;
    }

    public HapiNodeCreate grpcCertificateHash(final byte[] grpcCertificateHash) {
        this.grpcCertificateHash = Optional.of(grpcCertificateHash);
        return this;
    }

    public HapiNodeCreate declineReward(final boolean decline) {
        this.declineReward = Optional.of(decline);
        return this;
    }

    public HapiNodeCreate adminKey(final String name) {
        adminKeyName = Optional.of(name);
        return this;
    }

    public HapiNodeCreate adminKey(final Key key) {
        adminKey = key;
        return this;
    }

    private void genKeysFor(final HapiSpec spec) {
        adminKey = adminKey == null ? netOf(spec, adminKeyName, adminKeyShape) : adminKey;
    }

    @Override
    protected HapiNodeCreate self() {
        return this;
    }

    @Override
    protected long feeFor(@NonNull final HapiSpec spec, @NonNull final Transaction txn, final int numPayerKeys)
            throws Throwable {
        return spec.fees().forActivityBasedOp(HederaFunctionality.NodeCreate, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();
        accumulator.addVpt(Math.max(0, svo.getTotalSigCount() - 1));
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(@NonNull final HapiSpec spec) throws Throwable {
        genKeysFor(spec);
        if (useAvailableSubProcessPorts) {
            if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
                throw new IllegalStateException("Target is not a SubProcessNetwork");
            }
            gossipEndpoints = subProcessNetwork.gossipEndpointsForNextNodeId();
            grpcEndpoints = List.of(subProcessNetwork.grpcEndpointForNextNodeId());
        }
        final NodeCreateTransactionBody opBody = spec.txns()
                .<NodeCreateTransactionBody, NodeCreateTransactionBody.Builder>body(
                        NodeCreateTransactionBody.class, builder -> {
                            accountId.ifPresent(builder::setAccountId);
                            accountNum.ifPresent(accountNum ->
                                    builder.setAccountId(asAccount(spec.shard(), spec.realm(), accountNum)));
                            description.ifPresent(builder::setDescription);
                            builder.setAdminKey(adminKey);
                            builder.clearGossipEndpoint().addAllGossipEndpoint(gossipEndpoints);
                            builder.clearServiceEndpoint().addAllServiceEndpoint(grpcEndpoints);
                            grpcWebProxyEndpoint.ifPresent(builder::setGrpcProxyEndpoint);
                            gossipCaCertificate.ifPresent(s -> builder.setGossipCaCertificate(ByteString.copyFrom(s)));
                            grpcCertificateHash.ifPresent(s -> builder.setGrpcCertificateHash(ByteString.copyFrom(s)));
                            declineReward.ifPresent(builder::setDeclineReward);
                        });
        return b -> b.setNodeCreate(opBody);
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
        final var newId = lastReceipt.getNodeId();
        spec.registry()
                .saveNodeId(
                        nodeName,
                        fromPbj(EntityNumber.newBuilder().number(newId).build()));

        if (verboseLoggingOn) {
            LOG.info("Created node {} with ID {}.", nodeName, lastReceipt.getNodeId());
        }

        if (advertiseCreation) {
            final String banner = "\n\n"
                    + bannerWith(String.format(
                            "Created node '%s' with id '%d'.", description.orElse(nodeName), lastReceipt.getNodeId()));
            LOG.info(banner);
        }
        if (nodeIdObserver != null) {
            nodeIdObserver.accept(newId);
        }
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper();
        Optional.ofNullable(lastReceipt).ifPresent(receipt -> helper.add("created", receipt.getNodeId()));
        return helper;
    }

    @Nullable
    public Key getAdminKey() {
        return adminKey;
    }
}

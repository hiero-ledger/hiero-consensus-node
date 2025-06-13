package com.hedera.node.app.fees;


import static com.hedera.hapi.node.transaction.TransactionBody.DataOneOfType.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.util.HapiUtils.countOfCryptographicKeys;
import static com.hedera.hapi.util.HapiUtils.functionOf;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.common.EntityCreate;
import com.hedera.node.app.hapi.fees.apis.common.FeesHelper;
import com.hedera.node.app.hapi.fees.apis.common.YesOrNo;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;


public class SimpleFeesCalculatorImpl implements FeeCalculator {
    private final SigUsage sigUsage;
    private final TransactionBody txBody;
    private final com.hederahashgraph.api.proto.java.ExchangeRate currentRate;
    private final int numVerifications;

    public SimpleFeesCalculatorImpl(
            @NonNull TransactionBody txBody,
            @NonNull Key payerKey,
            final int numVerifications,
            final int signatureMapSize,
            @NonNull final ExchangeRate currentRate
    ) {
        requireNonNull(txBody);
        this.numVerifications = numVerifications;
        sigUsage = new SigUsage(numVerifications, signatureMapSize, countOfCryptographicKeys(payerKey));
        this.txBody = txBody;
        this.currentRate = fromPbj(currentRate);
    }
    @Override
    public @NonNull FeeCalculator withResourceUsagePercent(double percent) {
        return this;
    }

    @Override
    public @NonNull FeeCalculator addBytesPerTransaction(long bytes) {
        return this;
    }

    @Override
    public @NonNull FeeCalculator addStorageBytesSeconds(long seconds) {
        return this;
    }

    @Override
    public @NonNull FeeCalculator addNetworkRamByteSeconds(long amount) {
        return this;
    }

    @Override
    public @NonNull FeeCalculator addRamByteSeconds(long amount) {
        return this;
    }

    @Override
    public @NonNull FeeCalculator addVerificationsPerTransaction(long amount) {
        return this;
    }

    @NonNull
    @Override
    public Fees legacyCalculate(@NonNull Function<SigValueObj, com.hederahashgraph.api.proto.java.FeeData> callback) {
        System.out.println("SimpleFeesCalculatorImpl.legacyCalculate() called");
        final var sigValueObject = new SigValueObj(sigUsage.numSigs(), sigUsage.numPayerKeys(), sigUsage.sigsSize());
//        System.out.println("sig value object is " + sigValueObject);
        final var matrix = callback.apply(sigValueObject);
//        System.out.println("matrix is " + matrix);

        if(this.txBody.data().kind() == CONSENSUS_CREATE_TOPIC) {
            System.out.println("consensus create topic");
            EntityCreate entity = FeesHelper.makeEntity(HederaFunctionality.CONSENSUS_CREATE_TOPIC, "Create a topic", 0, true);
            Map<String, Object> params = new HashMap<>();
            params.put("numSignatures", numVerifications);
            params.put("numKeys", 0);
            params.put("hasCustomFee", YesOrNo.NO);
            FeeResult simpleFee = entity.computeFee(params);
            //TODO: I'm pretty sure these calculations are wrong
            final var node_tinycents = this.tinyCentsToTinyBar(this.usdToTinycents(simpleFee.fee*0.10));
            final var network_tinycents = this.tinyCentsToTinyBar(this.usdToTinycents(simpleFee.fee*0.45));
            final var service_tinycents = this.tinyCentsToTinyBar(this.usdToTinycents(simpleFee.fee*0.45));
            return new Fees(node_tinycents,network_tinycents,service_tinycents, 0, simpleFee.details);
        }
        return new Fees(0,0,0, 0, new HashMap<>());
    }

    private long tinyCentsToTinyBar(long tcents) {
        return tcents* this.currentRate.getHbarEquiv()/this.currentRate.getCentEquiv() * 100;
    }

    private long usdToTinycents(double v) {
        return Math.round(v * 100_000_000L);
    }

    @Override
    public @NonNull Fees calculate() {
        return new Fees(0,0,0);
    }

    @Override
    public long getCongestionMultiplier() {
        return 0;
    }

    @Override
    public @NonNull FeeCalculator resetUsage() {
        return this;
    }
}

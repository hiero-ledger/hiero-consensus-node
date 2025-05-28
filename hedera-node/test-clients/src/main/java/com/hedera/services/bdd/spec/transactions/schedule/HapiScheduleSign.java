// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.schedule;

import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.FULL_PREFIXES;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asScheduleId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleCreate.correspondingScheduledTxnId;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleSign;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.infrastructure.RegistryNotFound;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ScheduleInfo;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class HapiScheduleSign extends HapiTxnOp<HapiScheduleSign> {
    private final String schedule;
    private List<String> signatories = Collections.emptyList();

    @Nullable
    private List<Key> explicitSigners = null;

    private boolean ignoreMissing = false;
    private boolean saveScheduledTxnId = false;

    public HapiScheduleSign(String schedule) {
        this.schedule = schedule;
        sigMapPrefixes(TrieSigMapGenerator.withNature(FULL_PREFIXES));
    }

    public HapiScheduleSign alsoSigningWith(String... keys) {
        signatories = List.of(keys);
        return this;
    }

    public HapiScheduleSign alsoSigningWithExplicit(final List<Key> keys) {
        explicitSigners = keys;
        return this;
    }

    public HapiScheduleSign ignoreIfMissing() {
        ignoreMissing = true;
        return this;
    }

    public HapiScheduleSign savingScheduledTxnId() {
        saveScheduledTxnId = true;
        return this;
    }

    @Override
    protected HapiScheduleSign self() {
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ScheduleSign;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        ScheduleSignTransactionBody opBody = spec.txns()
                .<ScheduleSignTransactionBody, ScheduleSignTransactionBody.Builder>body(
                        ScheduleSignTransactionBody.class, b -> {
                            ScheduleID id;
                            try {
                                id = asScheduleId(schedule, spec);
                                b.setScheduleID(id);
                            } catch (RegistryNotFound e) {
                                if (!ignoreMissing) {
                                    throw e;
                                }
                            }
                        });
        return b -> b.setScheduleSign(opBody);
    }

    @Override
    protected void updateStateOf(HapiSpec spec) throws Throwable {
        if (actualStatus != SUCCESS) {
            return;
        }
        if (saveScheduledTxnId) {
            spec.registry().saveTxnId(correspondingScheduledTxnId(schedule), lastReceipt.getScheduledTransactionID());
        }
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        try {
            final ScheduleInfo info = ScheduleFeeUtils.lookupInfo(spec, schedule, true);
            FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> scheduleOpsUsage.scheduleSignUsage(
                    _txn, suFrom(svo), info.getExpirationTime().getSeconds());
            return spec.fees().forActivityBasedOp(HederaFunctionality.ScheduleSign, metricsCalc, txn, numPayerKeys);
        } catch (Throwable ignore) {
            return HapiSuite.ONE_HBAR;
        }
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final var signers = new ArrayList<Function<HapiSpec, Key>>();
        signers.add(spec -> spec.registry().getKey(effectivePayer(spec)));
        if (explicitSigners != null) {
            explicitSigners.forEach(key -> signers.add(spec -> key));
        } else {
            for (String added : signatories) {
                signers.add(spec -> spec.registry().getKey(added));
            }
        }
        return signers;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("schedule", schedule).add("signers", signatories);
    }
}

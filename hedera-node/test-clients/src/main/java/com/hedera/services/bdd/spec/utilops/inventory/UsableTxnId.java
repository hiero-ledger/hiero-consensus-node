// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UsableTxnId extends UtilOp {
    static final Logger log = LogManager.getLogger(UsableTxnId.class);

    private boolean useScheduledInappropriately = false;
    private Optional<String> payerId = Optional.empty();
    private Optional<String> asScheduled = Optional.empty();
    private Optional<Long> customValidStart = Optional.empty();
    private Optional<Timestamp> validStart = Optional.empty();
    private final String name;

    public UsableTxnId(String name) {
        this.name = name;
    }

    public UsableTxnId payerId(String id) {
        payerId = Optional.of(id);
        return this;
    }

    public UsableTxnId validStart(Timestamp timestamp) {
        validStart = Optional.of(timestamp);
        return this;
    }

    public UsableTxnId settingScheduledInappropriately() {
        useScheduledInappropriately = true;
        return this;
    }

    public UsableTxnId asScheduled(String scheduleCreateName) {
        asScheduled = Optional.of(scheduleCreateName);
        return this;
    }

    public UsableTxnId modifyValidStart(long validStart) {
        customValidStart = Optional.of(validStart);
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) {
        final var txnId = spec.txns().nextTxnId();
        final var txnValidStartSeconds = txnId.getTransactionValidStart().getSeconds();
        var txnIdBuilder = txnId.toBuilder();

        // If the txn is scheduled, we need to set the scheduled flag to true
        if (asScheduled.isPresent()) {
            final var scheduleCreateId = spec.registry().getTxnId(asScheduled.get());
            txnIdBuilder = scheduleCreateId.toBuilder();
            txnIdBuilder.setScheduled(true);
        }

        // Modify the valid start time if needed
        if (customValidStart.isPresent()) {
            txnIdBuilder.setTransactionValidStart(Timestamp.newBuilder()
                    .setSeconds(txnValidStartSeconds + customValidStart.get())
                    .build());
        }

        final var finalTxnId = txnIdBuilder;
        payerId.ifPresent(name -> finalTxnId.setAccountID(TxnUtils.asId(name, spec)));
        validStart.ifPresent(finalTxnId::setTransactionValidStart);
        if (useScheduledInappropriately) {
            txnIdBuilder.setScheduled(true);
        }
        spec.registry().saveTxnId(name, txnIdBuilder.build());
        return false;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        payerId.ifPresent(id -> super.toStringHelper().add("id", id));
        return super.toStringHelper().add("name", name);
    }
}

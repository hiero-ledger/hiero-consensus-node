// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UsableTxnId extends UtilOp {
    static final Logger log = LogManager.getLogger(UsableTxnId.class);

    private boolean useScheduledInappropriately = false;
    private Optional<String> payerId = Optional.empty();
    private final String name;

    public UsableTxnId(String name) {
        this.name = name;
    }

    public UsableTxnId payerId(String id) {
        payerId = Optional.of(id);
        return this;
    }

    public UsableTxnId settingScheduledInappropriately() {
        useScheduledInappropriately = true;
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) {
        final var txnId = spec.txns().nextTxnId().toBuilder();
        payerId.ifPresent(name -> txnId.setAccountID(TxnUtils.asId(name, spec)));
        if (useScheduledInappropriately) {
            txnId.setScheduled(true);
        }
        spec.registry().saveTxnId(name, txnId.build());
        return false;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        payerId.ifPresent(id -> super.toStringHelper().add("id", id));
        return super.toStringHelper().add("name", name);
    }
}

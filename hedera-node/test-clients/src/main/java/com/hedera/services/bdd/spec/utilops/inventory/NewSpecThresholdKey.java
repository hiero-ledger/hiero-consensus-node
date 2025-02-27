// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NewSpecThresholdKey extends UtilOp {
    static final Logger log = LogManager.getLogger(NewSpecThresholdKey.class);

    private final String name;
    private final int nRequired;
    private final List<String> keys;

    public NewSpecThresholdKey(String name, int nRequired, List<String> keys) {
        this.name = name;
        this.nRequired = nRequired;
        this.keys = keys;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        List<Key> childKeys = keys.stream().map(spec.registry()::getKey).toList();
        final var keyList = KeyList.newBuilder().addAllKeys(childKeys);
        final var thresholdKey =
                ThresholdKey.newBuilder().setThreshold(nRequired).setKeys(keyList);
        Key newThreshold = Key.newBuilder().setThresholdKey(thresholdKey).build();
        spec.registry().saveKey(name, newThreshold);

        SigControl[] childControls =
                childKeys.stream().map(spec.keys()::controlFor).toArray(SigControl[]::new);
        spec.keys().setControl(newThreshold, SigControl.listSigs(childControls));

        return false;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("name", name);
    }
}

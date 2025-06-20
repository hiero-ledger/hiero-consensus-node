// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import static java.util.stream.Collectors.toList;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NewSpecKeyList extends UtilOp {
    static final Logger log = LogManager.getLogger(NewSpecKeyList.class);

    private final String name;
    private final List<String> keys;

    public NewSpecKeyList(String name, List<String> keys) {
        this.name = name;
        this.keys = keys;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        List<Key> childKeys = keys.stream().map(spec.registry()::getKey).collect(toList());
        Key newList = Key.newBuilder()
                .setKeyList(KeyList.newBuilder().addAllKeys(childKeys))
                .build();
        spec.registry().saveKey(name, newList);

        SigControl[] childControls =
                childKeys.stream().map(spec.keys()::controlFor).toArray(SigControl[]::new);
        spec.keys().setControl(newList, SigControl.listSigs(childControls));

        return false;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("name", name);
    }
}

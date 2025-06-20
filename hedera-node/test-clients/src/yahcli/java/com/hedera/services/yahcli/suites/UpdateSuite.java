// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoUpdate;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class UpdateSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(UpdateSuite.class);

    private final Map<String, String> specConfig;
    private final String memo;
    private final List<Key> keys;
    private final boolean schedule;
    private final String targetAccount;

    public UpdateSuite(
            final Map<String, String> specConfig,
            final String memo,
            final List<Key> keys,
            final String targetAccount,
            final boolean schedule) {
        this.memo = memo;
        this.specConfig = specConfig;
        this.keys = keys;
        this.targetAccount = targetAccount;
        this.schedule = schedule;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doUpdate());
    }

    final Stream<DynamicTest> doUpdate() {
        Key newList = Key.newBuilder()
                .setKeyList(KeyList.newBuilder().addAllKeys(keys))
                .build();
        HapiTxnOp<?> update = new HapiCryptoUpdate(HapiSuite.DEFAULT_SHARD_REALM + targetAccount)
                .signedBy(HapiSuite.DEFAULT_PAYER)
                .protoKey(newList)
                .blankMemo()
                .entityMemo(memo);

        // flag that transferred as parameter to schedule a key change or to execute right away
        if (schedule) {
            update = scheduleCreate("update", update).logged();
        }

        return HapiSpec.customHapiSpec("DoUpdate")
                .withProperties(specConfig)
                .given()
                .when()
                .then(update);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

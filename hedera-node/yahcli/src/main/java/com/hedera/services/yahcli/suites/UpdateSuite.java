// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoUpdate;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import com.hederahashgraph.api.proto.java.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class UpdateSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(UpdateSuite.class);

    private final ConfigManager configManager;
    private final String memo;
    private final List<Key> keys;
    private final boolean schedule;
    private final String targetAccount;
    private final AtomicReference<ScheduleID> scheduleId = new AtomicReference<>();

    public UpdateSuite(
            final ConfigManager configManager,
            final String memo,
            final List<Key> keys,
            final String targetAccount,
            final boolean schedule) {
        this.memo = memo;
        this.configManager = configManager;
        this.keys = keys;
        this.targetAccount = targetAccount;
        this.schedule = schedule;
    }

    public AtomicReference<ScheduleID> getScheduleId() {
        return scheduleId;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doUpdate());
    }

    final Stream<DynamicTest> doUpdate() {
        Key newList = Key.newBuilder()
                .setKeyList(KeyList.newBuilder().addAllKeys(keys))
                .build();
        HapiTxnOp<?> update = new HapiCryptoUpdate(targetAccount)
                .signedBy(HapiSuite.DEFAULT_PAYER)
                .protoKey(newList)
                .blankMemo()
                .entityMemo(memo);

        // flag that transferred as parameter to schedule a key change or to execute right away
        if (schedule) {
            update = scheduleCreate("update", update)
                    .exposingCreatedIdTo(scheduleId::set)
                    .logged();
        }

        final var spec = new HapiSpec(
                "DoUpdate", new MapPropertySource(configManager.asSpecConfig()), new SpecOperation[] {update});
        return HapiSpecUtils.targeted(spec, configManager);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

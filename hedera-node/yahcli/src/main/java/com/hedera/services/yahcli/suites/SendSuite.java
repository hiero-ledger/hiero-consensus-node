// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import com.hederahashgraph.api.proto.java.ScheduleID;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class SendSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(SendSuite.class);

    private final ConfigManager configManager;
    private final String memo;
    private final String beneficiary;

    @Nullable
    private final String denomination;

    private final boolean schedule;
    private final boolean batch;
    private final long unitsToSend;
    private final AtomicReference<ScheduleID> scheduleId = new AtomicReference<>();

    public SendSuite(
            final ConfigManager configManager,
            final String beneficiary,
            final long unitsToSend,
            final String memo,
            @Nullable final String denomination,
            final boolean schedule,
            final boolean batch) {
        this.memo = memo;
        this.configManager = configManager;
        this.beneficiary = beneficiary;
        this.unitsToSend = unitsToSend;
        this.denomination = denomination;
        this.schedule = schedule;
        this.batch = batch;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doSend());
    }

    public AtomicReference<ScheduleID> getScheduleId() {
        return scheduleId;
    }

    final Stream<DynamicTest> doSend() {
        HapiTxnOp<?> transfer;
        if (denomination == null) {
            transfer = TxnVerbs.cryptoTransfer(
                            HapiCryptoTransfer.tinyBarsFromTo(HapiSuite.DEFAULT_PAYER, beneficiary, unitsToSend))
                    .memo(memo)
                    .signedBy(HapiSuite.DEFAULT_PAYER);
        } else {
            final var fqDenomination = Utils.extractEntity(
                    configManager.shard().getShardNum(), configManager.realm().getRealmNum(), denomination);
            transfer = TxnVerbs.cryptoTransfer(TokenMovement.moving(unitsToSend, fqDenomination)
                            .between(HapiSuite.DEFAULT_PAYER, beneficiary))
                    .memo(memo)
                    .signedBy(HapiSuite.DEFAULT_PAYER);
        }

        // flag that transferred as parameter to schedule a transaction or to execute right away
        if (schedule) {
            transfer = TxnVerbs.scheduleCreate("original", transfer)
                    .exposingCreatedIdTo(scheduleId::set)
                    .logged();
        }
        if (batch) {
            transfer = atomicBatch(transfer.batchKey(DEFAULT_PAYER));
        }

        final var spec = new HapiSpec(
                "DoSend", new MapPropertySource(configManager.asSpecConfig()), new SpecOperation[] {transfer});
        return HapiSpecUtils.targeted(spec, configManager);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

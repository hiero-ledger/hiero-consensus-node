// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ConsensusScenario.NOVEL_TOPIC_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ConsensusScenario.PERSISTENT_TOPIC_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.yahcli.commands.ivy.scenarios.ConsensusScenario;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.YahcliKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class IvyConsensusScenarioSuite extends AbstractIvySuite {
    private static final Logger log = LogManager.getLogger(IvyConsensusScenarioSuite.class);

    private final boolean novel;

    public IvyConsensusScenarioSuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys,
            final boolean novel) {
        super(specConfig, scenariosConfig, nodeAccounts, persistUpdatedScenarios, yahcliKeys);
        this.novel = novel;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(consensusScenario());
    }

    final Stream<DynamicTest> consensusScenario() {
        final var scenarios = getOrCreateScenarios();
        if (scenarios.getConsensus() == null) {
            scenarios.setConsensus(new ConsensusScenario());
        }
        final var consensus = requireNonNull(scenariosConfig.getScenarios().getConsensus());
        final AtomicLong currentSequenceNumber = new AtomicLong(0L);
        return HapiSpec.customHapiSpec("ConsensusScenario")
                .withProperties(specConfig)
                .given(
                        ensureScenarioPayer(),
                        ensureEd25519Topic(
                                PERSISTENT_TOPIC_NAME,
                                consensus.getPersistent(),
                                consensus::setPersistent,
                                currentSequenceNumber::set))
                .when(flattened(
                        submitMessageTo(PERSISTENT_TOPIC_NAME)
                                .payingWith(SCENARIO_PAYER_NAME)
                                .setNodeFrom(nodeAccounts.get())
                                .message("The particular is pounded till it is man."),
                        novelTopicIfDesired()))
                .then(getTopicInfo(PERSISTENT_TOPIC_NAME)
                        .payingWith(SCENARIO_PAYER_NAME)
                        .setNodeFrom(nodeAccounts.get())
                        .hasSeqNo(() -> currentSequenceNumber.get() + 1L)
                        .logged());
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    private HapiSpecOperation[] novelTopicIfDesired() {
        if (!novel) {
            return new HapiSpecOperation[] {};
        }
        final var complex = KeyShape.threshOf(1, KeyShape.listOf(2), KeyShape.threshOf(1, 3));
        return new HapiSpecOperation[] {
            newKeyNamed("novelTopicAdmin").shape(complex),
            createTopic(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .adminKeyName("novelTopicAdmin")
                    .submitKeyShape(KeyShape.SIMPLE)
                    .logged(),
            submitMessageTo(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .signedBy(SCENARIO_PAYER_NAME)
                    .hasKnownStatus(INVALID_SIGNATURE)
                    .logged(),
            updateTopic(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .signedBy(SCENARIO_PAYER_NAME, "novelTopicAdmin")
                    .submitKey(EMPTY_KEY)
                    .logged(),
            submitMessageTo(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .signedBy(SCENARIO_PAYER_NAME)
                    .logged(),
            deleteTopic(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .logged(),
        };
    }
}

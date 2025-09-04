// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.YahcliKeys;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class IvyStakingScenarioSuite extends AbstractIvySuite {
    private static final Logger log = LogManager.getLogger(IvyStakingScenarioSuite.class);

    public IvyStakingScenarioSuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys) {
        super(specConfig, scenariosConfig, nodeAccounts, persistUpdatedScenarios, yahcliKeys);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(stakingScenario());
    }

    final Stream<DynamicTest> stakingScenario() {
        final AtomicReference<NodeAddressBook> addressBook = new AtomicReference<>();
        return HapiSpec.customHapiSpec("StakingScenario")
                .withProperties(specConfig)
                .given(ensureScenarioPayer())
                .when(getFileContents(NODE_DETAILS)
                        .payingWith(SCENARIO_PAYER_NAME)
                        .alertingPost(response -> {
                            try {
                                addressBook.set(NodeAddressBook.parseFrom(response.getFileGetContents()
                                        .getFileContents()
                                        .getContents()));
                            } catch (InvalidProtocolBufferException e) {
                                throw new RuntimeException(e);
                            }
                        }))
                .then(sourcing(() -> blockingOrder(addressBook.get().getNodeAddressList().stream()
                        .map(nodeAddress -> cryptoUpdate(SCENARIO_PAYER_NAME)
                                .payingWith(SCENARIO_PAYER_NAME)
                                .setNodeFrom(nodeAccounts.get())
                                .newStakedNodeId(nodeAddress.getNodeId()))
                        .toArray(SpecOperation[]::new))));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

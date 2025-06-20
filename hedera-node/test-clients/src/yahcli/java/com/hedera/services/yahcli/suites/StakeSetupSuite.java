// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class StakeSetupSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(StakeSetupSuite.class);

    private final long stakePerNode;
    private final long stakingRewardRate;
    private final long rewardAccountBalance;
    private final ConfigManager configManager;
    private final Map<String, String> specConfig;

    private final Map<String, Long> accountsToStakedNodes = new HashMap<>();

    public Map<String, Long> getAccountsToStakedNodes() {
        return accountsToStakedNodes;
    }

    public StakeSetupSuite(
            final long stakePerNode,
            final long stakingRewardRate,
            final long rewardAccountBalance,
            @NonNull final ConfigManager configManager) {
        this.stakePerNode = stakePerNode;
        this.stakingRewardRate = stakingRewardRate;
        this.rewardAccountBalance = rewardAccountBalance;
        this.configManager = configManager;
        this.specConfig = configManager.asSpecConfig();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(startStakingAndExportCreatedStakers());
    }

    final Stream<DynamicTest> startStakingAndExportCreatedStakers() {
        final var spec = new HapiSpec(
                "StartStakingAndExportCreatedStakers", new MapPropertySource(specConfig), new SpecOperation[] {
                    overriding("staking.perHbarRewardRate", String.valueOf(stakingRewardRate)),
                    TxnVerbs.cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(
                            HapiSuite.DEFAULT_PAYER, HapiSuite.STAKING_REWARD, rewardAccountBalance)),
                    UtilVerbs.inParallel(configManager.nodeIdsInTargetNet().stream()
                            .map(nodeId -> TxnVerbs.cryptoCreate("stakerFor" + nodeId)
                                    .stakedNodeId(nodeId)
                                    .balance(stakePerNode)
                                    .key(HapiSuite.DEFAULT_PAYER)
                                    .exposingCreatedIdTo(id -> accountsToStakedNodes.put(asEntityString(id), nodeId)))
                            .toArray(HapiSpecOperation[]::new))
                });
        return HapiSpecUtils.targeted(spec, configManager);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

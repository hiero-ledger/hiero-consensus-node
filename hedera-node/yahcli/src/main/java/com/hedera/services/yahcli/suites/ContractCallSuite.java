// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Submits a single ContractCall against a known contract id with raw EVM call data.
 */
public class ContractCallSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(ContractCallSuite.class);

    private final ConfigManager configManager;
    private final String contractId;
    private final byte[] callData;
    private final long gas;
    private final long valueSent;

    private HapiContractCall op;

    public ContractCallSuite(
            final ConfigManager configManager,
            final String contractId,
            final byte[] callData,
            final long gas,
            final long valueSent) {
        this.configManager = configManager;
        this.contractId = contractId;
        this.callData = callData;
        this.gas = gas;
        this.valueSent = valueSent;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(call());
    }

    final Stream<DynamicTest> call() {
        op = TxnVerbs.contractCall(contractId)
                .withExplicitRawParams(callData)
                .gas(gas)
                .sending(valueSent);
        final var spec = new HapiSpec(
                "YahcliContractCall", new MapPropertySource(configManager.asSpecConfig()), new SpecOperation[] {op});
        return HapiSpecUtils.targeted(spec, configManager);
    }

    public HapiContractCall getOp() {
        return op;
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

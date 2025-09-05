// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.readBytesUnchecked;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContractString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asFileString;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.includingDeduction;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocalWithFunctionAbi;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ContractScenario.DEFAULT_CONTRACT_INITCODE;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ContractScenario.DEFAULT_LUCKY_NUMBER;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ContractScenario.NOVEL_CONTRACT_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ContractScenario.PERSISTENT_CONTRACT_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.yahcli.commands.ivy.scenarios.ContractScenario;
import com.hedera.services.yahcli.commands.ivy.scenarios.PersistentContract;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.YahcliKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class IvyContractScenarioSuite extends AbstractIvySuite {
    private static final Logger log = LogManager.getLogger(IvyContractScenarioSuite.class);

    private final boolean novel;
    private final String scenariosLoc;

    public IvyContractScenarioSuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys,
            final boolean novel,
            @NonNull final String scenariosLoc) {
        super(specConfig, scenariosConfig, nodeAccounts, persistUpdatedScenarios, yahcliKeys);
        this.novel = novel;
        this.scenariosLoc = requireNonNull(scenariosLoc);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(contractScenario());
    }

    final Stream<DynamicTest> contractScenario() {
        final var scenarios = getOrCreateScenarios();
        if (scenarios.getContract() == null) {
            scenarios.setContract(new ContractScenario());
        }
        final var contract = requireNonNull(scenariosConfig.getScenarios().getContract());
        if (contract.getPersistent() == null) {
            final var persistentContract = new PersistentContract();
            persistentContract.setSource(scenariosLoc + File.separator + DEFAULT_CONTRACT_INITCODE);
            contract.setPersistent(persistentContract);
        }
        final var initcodeLoc = contract.getPersistent().getSource();
        final var path = Paths.get(initcodeLoc);
        if (!path.toFile().exists()) {
            try (final var in =
                    Thread.currentThread().getContextClassLoader().getResourceAsStream(DEFAULT_CONTRACT_INITCODE)) {
                requireNonNull(in);
                Files.copy(in, path);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Cannot copy classpath resource '" + DEFAULT_CONTRACT_INITCODE + "' to '" + initcodeLoc + "'",
                        e);
            }
        }
        final var persistent = contract.getPersistent();
        if (persistent.getLuckyNo() == null) {
            persistent.setLuckyNo(DEFAULT_LUCKY_NUMBER);
        }
        final AtomicReference<String> persistentIdLiteral = new AtomicReference<>();
        return HapiSpec.customHapiSpec("ContractScenario")
                .withProperties(specConfig)
                .given(
                        ensureScenarioPayer(),
                        persistent.getBytecode() == null
                                ? blockingOrder(
                                        ensureEd25519File(
                                                "initcode", null, readBytesUnchecked(path), persistent::setBytecode),
                                        ensureEd25519Contract(
                                                PERSISTENT_CONTRACT_NAME, null, "initcode", persistent::setNum),
                                        contractCallWithFunctionAbi(
                                                        PERSISTENT_CONTRACT_NAME,
                                                        BELIEVE_IN_ABI,
                                                        Long.valueOf(persistent.getLuckyNo()))
                                                .setNodeFrom(nodeAccounts.get()))
                                : noOp())
                .when(flattened(
                        doingContextual(spec -> persistentIdLiteral.set(
                                asContractString(spec.contractIdFactory().apply(persistent.getNum())))),
                        sourcing(() -> contractCallLocalWithFunctionAbi(persistentIdLiteral.get(), PICK_ABI)
                                .setNodeFrom(nodeAccounts.get())
                                .has(resultWith().resultThruAbi(PICK_ABI, isLiteralResult(new Object[] {
                                    Long.valueOf(persistent.getLuckyNo())
                                })))),
                        sourcing(() -> contractCall(persistentIdLiteral.get())
                                .payingWith(SCENARIO_PAYER_NAME)
                                .setNodeFrom(nodeAccounts.get())
                                .logged()
                                .sending(1L)),
                        sourcing(() -> getContractInfo(persistentIdLiteral.get())
                                .has(ContractInfoAsserts.contractWith().balance(1))
                                .logged()),
                        sourcing(() -> contractCallWithFunctionAbi(
                                        persistentIdLiteral.get(), DONATE_ABI, BigInteger.valueOf(800L), "Hey, Ma!")
                                .payingWith(SCENARIO_PAYER_NAME)
                                .logged()
                                .gas(1_000_000L)
                                .setNodeFrom(nodeAccounts.get())
                                .via("donation")),
                        sourcing(() -> getContractInfo(persistentIdLiteral.get())
                                .has(ContractInfoAsserts.contractWith().balance(0))
                                .logged()),
                        getTxnRecord("donation")
                                .payingWith(SCENARIO_PAYER_NAME)
                                .setNodeFrom(nodeAccounts.get())
                                .logged()
                                .hasPriority(recordWith()
                                        .transfers(includingDeduction(contract.getPersistent()::getNum, 1)))))
                .then(novelContractIfDesired(contract));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    private HapiSpecOperation[] novelContractIfDesired(@NonNull final ContractScenario contract) {
        if (!novel) {
            return new HapiSpecOperation[] {};
        }

        final var complex = KeyShape.listOf(KeyShape.threshOf(2, 3), KeyShape.threshOf(1, 3));
        return new HapiSpecOperation[] {
            newKeyNamed("firstNovelKey").shape(complex),
            newKeyNamed("secondNovelKey"),
            sourcingContextual(spec -> contractCreate(NOVEL_CONTRACT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .adminKey("firstNovelKey")
                    .balance(1)
                    .bytecode(() -> asFileString(
                            spec.fileIdFactory().apply(contract.getPersistent().getBytecode())))),
            contractUpdate(NOVEL_CONTRACT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .newKey("secondNovelKey"),
            contractDelete(NOVEL_CONTRACT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .transferAccount(SCENARIO_PAYER_NAME)
        };
    }

    private static final String PICK_ABI =
            """
              {
                "inputs": [],
                "name": "pick",
                "outputs": [
                  {
                    "internalType": "uint32",
                    "name": "",
                    "type": "uint32"
                  }
                ],
                "stateMutability": "view",
                "type": "function"
              }
            """;
    private static final String BELIEVE_IN_ABI =
            """
              {
               "inputs": [
                 {
                   "internalType": "uint32",
                   "name": "no",
                   "type": "uint32"
                 }
               ],
               "name": "believeIn",
               "outputs": [],
               "stateMutability": "nonpayable",
               "type": "function"
              }
            """;
    private static final String DONATE_ABI =
            """
              {
                "inputs": [
                  {
                    "internalType": "uint160",
                    "name": "toNum",
                    "type": "uint160"
                  },
                  {
                    "internalType": "string",
                    "name": "saying",
                    "type": "string"
                  }
                ],
                "name": "donate",
                "outputs": [],
                "stateMutability": "payable",
                "type": "function"
              }
            """;
}

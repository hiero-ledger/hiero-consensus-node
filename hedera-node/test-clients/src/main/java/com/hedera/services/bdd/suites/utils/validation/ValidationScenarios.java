// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.validation;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.includingDeduction;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromPem;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FinalOutcome.SUITE_PASSED;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.JutilPropsToSvcCfgBytes.LEGACY_THROTTLES_FIRST_ORDER;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.StandardSerdes.SYS_FILE_SERDES;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.CONSENSUS;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.CONTRACT;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.CRYPTO;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.FILE;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.STAKE_TO_EVERYBODY;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.SYSTEM_KEYS;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.SYS_FILES_DOWN;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.SYS_FILES_UP;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.TRANSFERS_ONLY;
import static com.hedera.services.bdd.suites.utils.validation.ValidationScenarios.Scenario.VERSIONS;
import static com.hedera.services.bdd.suites.utils.validation.domain.ConsensusScenario.NOVEL_TOPIC_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.ConsensusScenario.PERSISTENT_TOPIC_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.ContractScenario.DEFAULT_BYTECODE_RESOURCE;
import static com.hedera.services.bdd.suites.utils.validation.domain.ContractScenario.DEFAULT_CONTRACT_RESOURCE;
import static com.hedera.services.bdd.suites.utils.validation.domain.ContractScenario.DEFAULT_LUCKY_NUMBER;
import static com.hedera.services.bdd.suites.utils.validation.domain.ContractScenario.NOVEL_CONTRACT_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.ContractScenario.PERSISTENT_CONTRACT_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.CryptoScenario.NOVEL_ACCOUNT_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.CryptoScenario.RECEIVER_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.CryptoScenario.SENDER_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.FileScenario.DEFAULT_CONTENTS_RESOURCE;
import static com.hedera.services.bdd.suites.utils.validation.domain.FileScenario.NOVEL_FILE_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.FileScenario.PERSISTENT_FILE_NAME;
import static com.hedera.services.bdd.suites.utils.validation.domain.Network.SCENARIO_PAYER_NAME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static java.nio.file.Files.readString;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.SysFileSerde;
import com.hedera.services.bdd.suites.utils.validation.domain.ConsensusScenario;
import com.hedera.services.bdd.suites.utils.validation.domain.ContractScenario;
import com.hedera.services.bdd.suites.utils.validation.domain.CryptoScenario;
import com.hedera.services.bdd.suites.utils.validation.domain.FileScenario;
import com.hedera.services.bdd.suites.utils.validation.domain.Network;
import com.hedera.services.bdd.suites.utils.validation.domain.Node;
import com.hedera.services.bdd.suites.utils.validation.domain.PersistentContract;
import com.hedera.services.bdd.suites.utils.validation.domain.PersistentFile;
import com.hedera.services.bdd.suites.utils.validation.domain.Scenarios;
import com.hedera.services.bdd.suites.utils.validation.domain.StakingScenario;
import com.hedera.services.bdd.suites.utils.validation.domain.SysFilesDownScenario;
import com.hedera.services.bdd.suites.utils.validation.domain.SysFilesUpScenario;
import com.hedera.services.bdd.suites.utils.validation.domain.UpdateAction;
import com.hedera.services.bdd.suites.utils.validation.domain.ValidationConfig;
import com.hedera.services.bdd.suites.utils.validation.domain.VersionInfoScenario;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import com.hederahashgraph.api.proto.java.TopicID;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

public class ValidationScenarios extends HapiSuite {

    private static final Logger log = LogManager.getLogger(ValidationScenarios.class);
    public static final long FEE_TO_OFFER_IN_HBAR = 100;
    public static final long TINYBARS_PER_HBAR = 100_000_000L;
    private static final String DEFAULT_CONFIG_LOC = "config.yml";
    private static final String PATTERN = "%d";
    private static final String DEFAULT_PAYER_KEY = "default.payer.key";
    private static final String TRANSFER_TXN = "transferTxn";
    private static final String FEES_USE_FIXED_OFFER = "fees.useFixedOffer";
    private static final String DEFAULT_NODE = "default.node";
    private static final String DEFAULT_PAYER1 = "default.payer";
    private static final String NODES = "nodes";
    private static final String FILES = "files/";
    private static final String NOVEL_TOPIC_ADMIN = "novelTopicAdmin";
    public static final String FEES_FIXED_OFFER = "fees.fixedOffer";

    enum Scenario {
        CRYPTO,
        FILE,
        CONTRACT,
        CONSENSUS,
        SYSTEM_KEYS,
        TRANSFERS_ONLY,
        VERSIONS,
        SYS_FILES_UP,
        SYS_FILES_DOWN,
        STAKE_TO_EVERYBODY,
    }

    private static Scenarios scenarios;
    private static ValidationConfig validationConfig;
    private static ScenarioParams params = new ScenarioParams();
    private static List<String> nodeAccounts = new ArrayList<>();
    private static int nextAccount = 0;
    private static AtomicLong startingBalance = new AtomicLong(-1L);
    private static AtomicLong endingBalance = new AtomicLong(-1L);
    private static AtomicBoolean errorsOccurred = new AtomicBoolean(false);
    private static AtomicReference<String> novelAccountUsed = new AtomicReference<>(null);
    private static AtomicReference<String> novelFileUsed = new AtomicReference<>(null);
    private static AtomicReference<String> novelContractUsed = new AtomicReference<>(null);
    private static AtomicReference<String> novelTopicUsed = new AtomicReference<>(null);

    public static void main(String... args) {
        parse(args);
        readConfig();

        assertValidParams();
        log.info("Using nodes {}", nodes());
        FinalOutcome outcome = new ValidationScenarios().runSuiteSync();

        printNovelUsage();
        printBalanceChange();
        if (!skipScenarioPayer()) {
            persistUpdatedConfig();
        }

        System.exit((outcome == SUITE_PASSED && !errorsOccurred.get()) ? 0 : 1);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        var specs = Stream.of(
                        ofNullable(recordPayerBalance(startingBalance::set)),
                        ofNullable(skipScenarioPayer() ? null : ensureScenarioPayer()),
                        ofNullable(params.getScenarios().contains(CRYPTO) ? cryptoScenario() : null),
                        ofNullable(params.getScenarios().contains(VERSIONS) ? versionsScenario() : null),
                        ofNullable(params.getScenarios().contains(FILE) ? fileScenario() : null),
                        ofNullable(params.getScenarios().contains(CONTRACT) ? contractScenario() : null),
                        ofNullable(params.getScenarios().contains(CONSENSUS) ? consensusScenario() : null),
                        ofNullable(params.getScenarios().contains(STAKE_TO_EVERYBODY) ? stakingScenario() : null),
                        ofNullable(params.getScenarios().contains(SYSTEM_KEYS) ? getSystemKeys() : null),
                        ofNullable(params.getScenarios().contains(TRANSFERS_ONLY) ? doJustTransfers() : null),
                        ofNullable(params.getScenarios().contains(SYS_FILES_DOWN) ? sysFilesDown() : null),
                        ofNullable(params.getScenarios().contains(SYS_FILES_UP) ? sysFilesUp() : null),
                        ofNullable(params.getScenarios().isEmpty() ? null : recordPayerBalance(endingBalance::set)))
                .flatMap(Optional::stream)
                .toList();
        System.out.println(specs.stream()
                .flatMap(Function.identity())
                .map(test -> ((HapiSpec) test.getExecutable()).getName())
                .toList());
        return specs;
    }

    private static boolean skipScenarioPayer() {
        EnumSet<Scenario> needScenarioPayer = EnumSet.of(CRYPTO, FILE, CONTRACT, VERSIONS, CONSENSUS, TRANSFERS_ONLY);
        return needScenarioPayer.stream().noneMatch(params.getScenarios()::contains);
    }

    private static Stream<DynamicTest> doJustTransfers() {
        try {
            int numNodes = targetNetwork().getNodes().size();
            return customHapiSpec("DoJustTransfers")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(keyFromPem(() -> pemForAccount(targetNetwork().getScenarioPayer()))
                            .name(SCENARIO_PAYER_NAME)
                            .linkedTo(
                                    () -> String.format(PATTERN, targetNetwork().getScenarioPayer())))
                    .when()
                    .then(IntStream.range(0, numNodes)
                            .mapToObj(i -> cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1L))
                                    .hasAnyStatusAtAll()
                                    .payingWith(SCENARIO_PAYER_NAME)
                                    .setNode(String.format(
                                            PATTERN,
                                            targetNetwork().getNodes().get(i).getAccount()))
                                    .via(TRANSFER_TXN + i))
                            .toArray(HapiSpecOperation[]::new));
        } catch (Exception e) {
            log.warn("Unable to initialize transfers scenario, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static Stream<DynamicTest> sysFilesUp() {
        ensureScenarios();
        if (scenarios.getSysFilesUp() == null) {
            scenarios.setSysFilesUp(new SysFilesUpScenario());
        }
        var sys = scenarios.getSysFilesUp();
        long[] payers =
                sys.getUpdates().stream().mapToLong(UpdateAction::getPayer).toArray();

        try {
            return customHapiSpec("SysFilesUp")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(LongStream.of(payers)
                            .mapToObj(payer -> keyFromPem(() -> pemForAccount(payer))
                                    .name(String.format("payer%d", payer))
                                    .passphrase(passphraseFor(payer))
                                    .linkedTo(() -> String.format(PATTERN, payer)))
                            .toArray(HapiSpecOperation[]::new))
                    .when()
                    .then(sys.getUpdates().stream()
                            .map(action -> updateLargeFile(
                                    String.format("payer%d", action.getPayer()),
                                    String.format(PATTERN, action.getNum()),
                                    appropriateContents(action.getNum()),
                                    true,
                                    OptionalLong.of(10_000_000_000L)))
                            .toArray(HapiSpecOperation[]::new));
        } catch (Exception e) {
            log.warn("Unable to initialize system file update scenario, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static String passphraseFor(long accountNum) {
        return Optional.ofNullable(System.getenv(passphraseEnvVarFor(accountNum)))
                .orElse(params.getRawPassphrase());
    }

    private static String passphraseEnvVarFor(long accountNum) {
        return String.format(
                "%s_ACCOUNT%d_PASSPHRASE", params.getTargetNetwork().toUpperCase(), accountNum);
    }

    private static Stream<DynamicTest> sysFilesDown() {
        ensureScenarios();
        if (scenarios.getSysFilesDown() == null) {
            scenarios.setSysFilesDown(new SysFilesDownScenario());
        }
        var sys = scenarios.getSysFilesDown();
        final long[] targets =
                sys.getNumsToFetch().stream().mapToLong(Integer::longValue).toArray();

        try {
            return customHapiSpec("SysFilesDown")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(keyFromPem(() -> pemForAccount(targetNetwork().getScenarioPayer()))
                            .name(SCENARIO_PAYER_NAME)
                            .linkedTo(
                                    () -> String.format(PATTERN, targetNetwork().getScenarioPayer())))
                    .when()
                    .then(Arrays.stream(targets)
                            .mapToObj(fileNum -> appropriateQuery(sys, fileNum))
                            .toArray(HapiSpecOperation[]::new));
        } catch (Exception e) {
            log.warn("Unable to initialize system file scenarios, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static ByteString appropriateContents(long fileNum) {
        SysFileSerde<String> serde = SYS_FILE_SERDES.get(fileNum);
        String name = serde.preferredFileName();
        String loc = FILES + params.getTargetNetwork() + "-" + name;
        try {
            var stylized = Files.readString(Paths.get(loc));
            return ByteString.copyFrom(serde.toRawFile(stylized, null));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read update file @ '" + loc + "'!", e);
        }
    }

    private static HapiSpecOperation appropriateQuery(SysFilesDownScenario sys, long fileNum) {
        String fid = String.format(PATTERN, fileNum);
        SysFileSerde<String> serde = SYS_FILE_SERDES.get(fileNum);
        String fqn = params.getTargetNetwork() + "-" + serde.preferredFileName();
        String loc = FILES + fqn;
        UnaryOperator<byte[]> preCompare =
                (fileNum == 121 || fileNum == 122) ? ValidationScenarios::asOrdered : bytes -> bytes;

        if (SysFilesDownScenario.COMPARE_EVAL_MODE.equals(sys.getEvalMode())) {
            String actualLoc = "files/actual-" + fqn;
            try {
                byte[] expected = serde.toRawFile(readString(Paths.get(loc)), null);
                return getFileContents(fid)
                        .payingWith(SCENARIO_PAYER_NAME)
                        .saveReadableTo(serde::fromRawFile, actualLoc)
                        .hasContents(spec -> expected)
                        .afterBytesTransform(preCompare);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read comparison file @ '" + loc + "'!", e);
            }
        } else if (SysFilesDownScenario.SNAPSHOT_EVAL_MODE.equals(sys.getEvalMode())) {
            return getFileContents(fid).payingWith(SCENARIO_PAYER_NAME).saveReadableTo(serde::fromRawFile, loc);
        } else {
            throw new IllegalArgumentException("No such sys files eval mode '" + sys.getEvalMode() + "'!");
        }
    }

    private static byte[] asOrdered(byte[] svcCfgList) {
        try {
            var pre = ServicesConfigurationList.parseFrom(svcCfgList);
            var post = ServicesConfigurationList.newBuilder();
            Map<String, String> lookup =
                    pre.getNameValueList().stream().collect(toMap(Setting::getName, Setting::getValue));
            pre.getNameValueList().stream()
                    .map(Setting::getName)
                    .sorted(LEGACY_THROTTLES_FIRST_ORDER)
                    .forEach(prop ->
                            post.addNameValue(Setting.newBuilder().setName(prop).setValue(lookup.get(prop))));
            return post.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Not a services configuration list!", e);
        }
    }

    private static Stream<DynamicTest> getSystemKeys() {
        final long[] accounts = {2, 50, 55, 56, 57, 58};
        final long[] files = {101, 102, 111, 112, 121, 122};
        try {
            return customHapiSpec("GetSystemKeys")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given()
                    .when()
                    .then(flattened(
                            Arrays.stream(accounts)
                                    .mapToObj(num -> getAccountInfo(String.format(PATTERN, num))
                                            .setNodeFrom(ValidationScenarios::nextNode)
                                            .logged())
                                    .toArray(n -> new HapiSpecOperation[n]),
                            Arrays.stream(files)
                                    .mapToObj(num -> getFileInfo(String.format(PATTERN, num))
                                            .setNodeFrom(ValidationScenarios::nextNode)
                                            .logged())
                                    .toArray(n -> new HapiSpecOperation[n])));
        } catch (Exception e) {
            log.warn("Unable to initialize fetch for system keys, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static Stream<DynamicTest> recordPayerBalance(LongConsumer learner) {
        try {
            return customHapiSpec("RecordPayerBalance")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given()
                    .when()
                    .then(withOpContext((spec, opLog) -> {
                        var lookup = getAccountBalance(
                                () -> idLiteral(targetNetwork().getBootstrap()));
                        allRunFor(spec, lookup);
                        learner.accept(lookup.getResponse()
                                .getCryptogetAccountBalance()
                                .getBalance());
                    }));
        } catch (Exception e) {
            log.warn("Unable to record initial payer balance, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static Stream<DynamicTest> ensureScenarioPayer() {
        try {
            ensureScenarios();
            long minStartingBalance = targetNetwork().getEnsureScenarioPayerHbars() * TINYBARS_PER_HBAR;
            return customHapiSpec("EnsureScenarioPayer")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given()
                    .when()
                    .then(ensureValidatedAccountExistence(
                            SCENARIO_PAYER_NAME,
                            minStartingBalance,
                            pemForAccount(payerOrNegativeOne(targetNetwork()).getAsLong()),
                            payerOrNegativeOne(targetNetwork()),
                            targetNetwork()::setScenarioPayer));
        } catch (Exception e) {
            log.warn("Unable to ensure scenario payer, failing!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static Stream<DynamicTest> versionsScenario() {
        try {
            ensureScenarios();
            if (scenarios.getVersions() == null) {
                scenarios.setVersions(new VersionInfoScenario());
            }
            return customHapiSpec("VersionsScenario")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(keyFromPem(() -> pemForAccount(targetNetwork().getScenarioPayer()))
                            .name(SCENARIO_PAYER_NAME)
                            .linkedTo(
                                    () -> String.format(PATTERN, targetNetwork().getScenarioPayer())))
                    .when()
                    .then(getVersionInfo().payingWith(SCENARIO_PAYER_NAME).setNodeFrom(ValidationScenarios::nextNode));
        } catch (Exception e) {
            log.warn("Unable to initialize versions scenario, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static Stream<DynamicTest> cryptoScenario() {
        try {
            ensureScenarios();
            if (scenarios.getCrypto() == null) {
                scenarios.setCrypto(new CryptoScenario());
            }
            var crypto = scenarios.getCrypto();
            var transferFee = new AtomicLong(0);

            long expectedDelta = params.isNovelContent() ? 2L : 1L;
            return customHapiSpec("CryptoScenario")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(
                            keyFromPem(() -> pemForAccount(targetNetwork().getScenarioPayer()))
                                    .name(SCENARIO_PAYER_NAME)
                                    .linkedTo(() -> String.format(
                                            PATTERN, targetNetwork().getScenarioPayer())),
                            ensureValidatedAccountExistence(
                                    SENDER_NAME,
                                    2L,
                                    pemForAccount(senderOrNegativeOne(crypto).getAsLong()),
                                    senderOrNegativeOne(crypto),
                                    crypto::setSender),
                            ensureValidatedAccountExistence(
                                    RECEIVER_NAME,
                                    0L,
                                    pemForAccount(receiverOrNegativeOne(crypto).getAsLong()),
                                    receiverOrNegativeOne(crypto),
                                    crypto::setReceiver),
                            balanceSnapshot("receiverBefore", RECEIVER_NAME))
                    .when(flattened(
                            cryptoTransfer(tinyBarsFromTo(SENDER_NAME, RECEIVER_NAME, 1L))
                                    .payingWith(SCENARIO_PAYER_NAME)
                                    .setNodeFrom(ValidationScenarios::nextNode)
                                    .via(TRANSFER_TXN),
                            withOpContext((spec, opLog) -> {
                                var lookup = getTxnRecord(TRANSFER_TXN)
                                        .payingWith(SCENARIO_PAYER_NAME)
                                        .setNodeFrom(ValidationScenarios::nextNode)
                                        .logged();
                                allRunFor(spec, lookup);
                                var record = lookup.getResponseRecord();
                                transferFee.set(record.getTransactionFee());
                            }),
                            novelAccountIfDesired(transferFee)))
                    .then(getAccountBalance(RECEIVER_NAME)
                            .setNodeFrom(ValidationScenarios::nextNode)
                            .hasTinyBars(changeFromSnapshot("receiverBefore", expectedDelta)));
        } catch (Exception e) {
            log.warn("Unable to initialize crypto scenario, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static HapiSpecOperation[] novelAccountIfDesired(AtomicLong transferFee) {
        if (!params.isNovelContent()) {
            return new HapiSpecOperation[0];
        }

        KeyShape complex = KeyShape.threshOf(1, KeyShape.listOf(2), KeyShape.threshOf(1, 3));
        return new HapiSpecOperation[] {
            newKeyNamed("novelAccountFirstKey").shape(complex),
            newKeyNamed("novelAccountSecondKey"),
            cryptoCreate(NOVEL_ACCOUNT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .balance(ignore -> 2 * transferFee.get())
                    .key("novelAccountFirstKey"),
            cryptoUpdate(NOVEL_ACCOUNT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .key("novelAccountSecondKey"),
            cryptoTransfer(tinyBarsFromTo(SENDER_NAME, RECEIVER_NAME, 1L))
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .payingWith(NOVEL_ACCOUNT_NAME),
            cryptoDelete(NOVEL_ACCOUNT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .transfer(DEFAULT_PAYER),
            withOpContext((spec, opLog) -> novelAccountUsed.set(
                    HapiPropertySource.asAccountString(spec.registry().getAccountID(NOVEL_ACCOUNT_NAME))))
        };
    }

    private static LongSupplier payerOrNegativeOne(Network network) {
        return () -> ofNullable(network.getScenarioPayer()).orElse(-1L);
    }

    private static LongSupplier senderOrNegativeOne(CryptoScenario crypto) {
        return () -> ofNullable(crypto.getSender()).orElse(-1L);
    }

    private static LongSupplier receiverOrNegativeOne(CryptoScenario crypto) {
        return () -> ofNullable(crypto.getReceiver()).orElse(-1L);
    }

    private static HapiSpecOperation ensureValidatedAccountExistence(
            String name, long minBalance, String pemLoc, LongSupplier num, LongConsumer update) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            long accountNo = num.getAsLong();
            if (accountNo > 0) {
                var check = getAccountInfo(idLiteral(num.getAsLong())).setNodeFrom(ValidationScenarios::nextNode);
                allRunFor(spec, check);

                var info = check.getResponse().getCryptoGetInfo().getAccountInfo();
                final var key = Ed25519Utils.readKeyFrom(pemLoc, KeyFactory.PEM_PASSPHRASE);
                var expectedKey = Key.newBuilder()
                        .setEd25519(ByteString.copyFrom(key.getAbyte()))
                        .build();
                Assertions.assertEquals(
                        expectedKey,
                        info.getKey(),
                        String.format("Account 0.0.%d had a different key than expected!", accountNo));
                spec.registry().saveKey(name, expectedKey);
                spec.registry().saveAccountId(name, accountId(accountNo));
                spec.keys().incorporate(name, key);

                if (info.getBalance() < minBalance) {
                    var transfer = cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, name, (minBalance - info.getBalance())))
                            .setNodeFrom(ValidationScenarios::nextNode);
                    allRunFor(spec, transfer);
                }
            } else {
                var create = TxnVerbs.cryptoCreate(name)
                        .setNodeFrom(ValidationScenarios::nextNode)
                        .balance(minBalance);
                allRunFor(spec, create);
                var createdNo = create.numOfCreatedAccount();
                var newLoc = pemLoc.replace("account-1", String.format("account%d", createdNo));
                spec.keys().exportEd25519Key(newLoc, name);
                update.accept(createdNo);
            }
        });
    }

    private static Stream<DynamicTest> fileScenario() {
        try {
            ensureScenarios();
            if (scenarios.getFile() == null) {
                var fs = new FileScenario();
                fs.setPersistent(new PersistentFile());
                scenarios.setFile(fs);
            }
            var file = scenarios.getFile();

            return customHapiSpec("FileScenario")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(
                            keyFromPem(() -> pemForAccount(targetNetwork().getScenarioPayer()))
                                    .name(SCENARIO_PAYER_NAME)
                                    .linkedTo(() -> String.format(
                                            PATTERN, targetNetwork().getScenarioPayer())),
                            ensureValidatedFileExistence(
                                    PERSISTENT_FILE_NAME,
                                    file.getPersistent().getContents(),
                                    pemForFile(persistentOrNegativeOne(file).getAsLong()),
                                    persistentOrNegativeOne(file),
                                    num -> file.getPersistent().setNum(num),
                                    loc -> file.getPersistent().setContents(loc)))
                    .when()
                    .then(novelFileIfDesired());
        } catch (Exception e) {
            log.warn("Unable to initialize file scenario, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static HapiSpecOperation[] novelFileIfDesired() {
        if (!params.isNovelContent()) {
            return new HapiSpecOperation[0];
        }

        KeyShape firstComplex = KeyShape.listOf(KeyShape.threshOf(2, 3), KeyShape.threshOf(1, 3));
        KeyShape secondComplex = KeyShape.listOf(3);
        SigControl normalDelete = secondComplex.signedWith(KeyShape.sigs(ON, ON, ON));
        SigControl revocation = secondComplex.signedWith(KeyShape.sigs(ON, OFF, OFF));
        return new HapiSpecOperation[] {
            newKeyNamed("novelFileFirstKey").shape(firstComplex),
            newKeyNamed("novelFileSecondKey").shape(secondComplex),
            fileCreate(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .key("novelFileFirstKey")
                    .contents("abcdefghijklm"),
            fileAppend(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .content("nopqrstuvwxyz"),
            getFileContents(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .hasContents(ignore -> "abcdefghijklmnopqrstuvwxyz".getBytes()),
            fileUpdate(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .wacl("novelFileSecondKey"),
            fileDelete(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .sigControl(ControlForKey.forKey(
                            NOVEL_FILE_NAME, params.isRevocationService() ? revocation : normalDelete)),
            withOpContext((spec, opLog) -> novelFileUsed.set(
                    HapiPropertySource.asFileString(spec.registry().getFileId(NOVEL_FILE_NAME))))
        };
    }

    private static LongSupplier persistentOrNegativeOne(FileScenario file) {
        return () -> ofNullable(file.getPersistent())
                .flatMap(s -> ofNullable(s.getNum()))
                .orElse(-1L);
    }

    private static HapiSpecOperation ensureValidatedFileExistence(
            final String name,
            final String contentsLoc,
            final String pemLoc,
            final LongSupplier num,
            final LongConsumer numUpdate,
            final Consumer<String> contentsUpdate) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            long fileNo = num.getAsLong();
            if (fileNo > 0) {
                var expectedContents = Files.readAllBytes(Paths.get(pathTo(contentsLoc)));
                var literal = idLiteral(num.getAsLong());
                var infoCheck = getFileInfo(literal).setNodeFrom(ValidationScenarios::nextNode);
                allRunFor(spec, infoCheck);

                final var info = infoCheck.getResponse().getFileGetInfo().getFileInfo();
                final var key = Ed25519Utils.readKeyFrom(pemLoc, KeyFactory.PEM_PASSPHRASE);
                var expectedKey = Key.newBuilder()
                        .setKeyList(KeyList.newBuilder()
                                .addKeys(Key.newBuilder().setEd25519(ByteString.copyFrom(key.getAbyte()))))
                        .build();
                Assertions.assertEquals(
                        expectedKey.getKeyList(),
                        info.getKeys(),
                        String.format("File 0.0.%d had a different key than expected!", fileNo));

                final var contentsCheck = getFileContents(literal)
                        .setNodeFrom(ValidationScenarios::nextNode)
                        .hasContents(ignore -> expectedContents);
                allRunFor(spec, contentsCheck);

                spec.registry().saveKey(name, expectedKey);
                spec.registry().saveFileId(name, fileId(fileNo));
                spec.keys().incorporateEd25519SimpleWacl(name, key);
            } else {
                var contents = (contentsLoc != null)
                        ? Files.readAllBytes(Paths.get(pathTo(contentsLoc)))
                        : ValidationScenarios.class
                                .getClassLoader()
                                .getResourceAsStream(DEFAULT_CONTENTS_RESOURCE)
                                .readAllBytes();
                var filesDir = new File(FILES);
                if (!filesDir.exists()) {
                    filesDir.mkdir();
                }

                var fileName = DEFAULT_CONTENTS_RESOURCE.substring(DEFAULT_CONTENTS_RESOURCE.lastIndexOf("/") + 1);
                var fout = Files.newBufferedWriter(Paths.get(String.format("files/%s", fileName)));
                fout.write(new String(contents));
                fout.close();
                contentsUpdate.accept(fileName);

                var create = fileCreate(name)
                        .setNodeFrom(ValidationScenarios::nextNode)
                        .waclShape(KeyShape.listOf(1))
                        .contents(contents);
                allRunFor(spec, create);
                var createdNo = create.numOfCreatedFile();
                var newLoc = pemLoc.replace("file-1", String.format("file%d", createdNo));
                spec.keys().exportFirstEd25519FromKeyList(newLoc, name);
                numUpdate.accept(createdNo);
            }
        });
    }

    private static Stream<DynamicTest> contractScenario() {
        try {
            ensureScenarios();
            if (scenarios.getContract() == null) {
                var cs = new ContractScenario();
                cs.setPersistent(new PersistentContract());
                scenarios.setContract(cs);
            }
            var contract = scenarios.getContract();

            Object[] donationArgs = new Object[] {800L, "Hey, Ma!"};

            return customHapiSpec("ContractScenario")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(
                            keyFromPem(() -> pemForAccount(targetNetwork().getScenarioPayer()))
                                    .name(SCENARIO_PAYER_NAME)
                                    .linkedTo(() -> String.format(
                                            PATTERN, targetNetwork().getScenarioPayer())),
                            ensureValidatedContractExistence(
                                    PERSISTENT_CONTRACT_NAME,
                                    contract.getPersistent().getLuckyNo(),
                                    contract.getPersistent().getSource(),
                                    pemForContract(persistentContractOrNegativeOne(contract)
                                            .getAsLong()),
                                    persistentContractOrNegativeOne(contract),
                                    num -> contract.getPersistent().setNum(num),
                                    bytecodeNum -> contract.getPersistent().setBytecode(bytecodeNum),
                                    luckyNo -> contract.getPersistent().setLuckyNo(luckyNo),
                                    loc -> contract.getPersistent().setSource(loc)))
                    .when(flattened(
                            contractCall(PERSISTENT_CONTRACT_NAME)
                                    .payingWith(SCENARIO_PAYER_NAME)
                                    .setNodeFrom(ValidationScenarios::nextNode)
                                    .sending(1L),
                            contractCall(PERSISTENT_CONTRACT_NAME, "donate", donationArgs)
                                    .payingWith(SCENARIO_PAYER_NAME)
                                    .setNodeFrom(ValidationScenarios::nextNode)
                                    .via("donation"),
                            getTxnRecord("donation")
                                    .payingWith(SCENARIO_PAYER_NAME)
                                    .setNodeFrom(ValidationScenarios::nextNode)
                                    .logged()
                                    .hasPriority(recordWith()
                                            .transfers(includingDeduction(contract.getPersistent()::getNum, 1))),
                            novelContractIfDesired(contract)))
                    .then();
        } catch (Exception e) {
            log.warn("Unable to initialize contract scenario, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static HapiSpecOperation[] novelContractIfDesired(ContractScenario contract) {
        if (!params.isNovelContent()) {
            return new HapiSpecOperation[0];
        }

        final var suffix = "Novel";
        KeyShape complex = KeyShape.listOf(KeyShape.threshOf(2, 3), KeyShape.threshOf(1, 3));
        return new HapiSpecOperation[] {
            newKeyNamed("firstNovelKey").shape(complex),
            newKeyNamed("secondNovelKey"),
            contractCustomCreate(NOVEL_CONTRACT_NAME, suffix)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .adminKey("firstNovelKey")
                    .balance(1)
                    .bytecode(() -> idLiteral(contract.getPersistent().getBytecode())),
            contractUpdate(NOVEL_CONTRACT_NAME + suffix)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .newKey("secondNovelKey"),
            contractDelete(NOVEL_CONTRACT_NAME + suffix)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .transferAccount(PERSISTENT_CONTRACT_NAME),
            withOpContext((spec, opLog) -> novelContractUsed.set(
                    HapiPropertySource.asAccountString(spec.registry().getAccountID(NOVEL_CONTRACT_NAME + suffix))))
        };
    }

    private static HapiSpecOperation ensureValidatedContractExistence(
            String name,
            Integer luckyNo,
            String bytecodeLoc,
            String pemLoc,
            LongSupplier num,
            LongConsumer numUpdate,
            LongConsumer bytecodeNumUpdate,
            IntConsumer luckyNoUpdate,
            Consumer<String> sourceUpdate) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            long contractNo = num.getAsLong();
            if (contractNo > 0) {
                var literal = idLiteral(num.getAsLong());
                var infoCheck = getContractInfo(literal).setNodeFrom(ValidationScenarios::nextNode);
                allRunFor(spec, infoCheck);

                var info = infoCheck.getResponse().getContractGetInfo().getContractInfo();
                final var key = Ed25519Utils.readKeyFrom(pemLoc, KeyFactory.PEM_PASSPHRASE);
                var expectedKey = Key.newBuilder()
                        .setEd25519(ByteString.copyFrom(key.getAbyte()))
                        .build();
                Assertions.assertEquals(
                        expectedKey,
                        info.getAdminKey(),
                        String.format("Contract 0.0.%d had a different key than expected!", contractNo));

                var bytecodeCheck = getContractBytecode(literal)
                        .setNodeFrom(ValidationScenarios::nextNode)
                        .isNonEmpty();
                allRunFor(spec, bytecodeCheck);

                Object[] expected = new Object[] {Long.valueOf(luckyNo)};
                spec.registry().saveContractId(PERSISTENT_CONTRACT_NAME, asContract(literal));
                spec.registry().saveAccountId(PERSISTENT_CONTRACT_NAME, asAccount(literal));
                var luckyNoCheck = contractCallLocal(PERSISTENT_CONTRACT_NAME, "pick")
                        .setNodeFrom(ValidationScenarios::nextNode)
                        .has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "pick", PERSISTENT_CONTRACT_NAME),
                                        isLiteralResult(expected)));
                allRunFor(spec, luckyNoCheck);

                spec.registry().saveKey(name, expectedKey);
                spec.registry().saveContractId(name, contractId(contractNo));
                spec.registry().saveAccountId(name, accountId(contractNo));
                spec.keys().incorporate(name, key);
            } else {
                var baseName = (bytecodeLoc != null) ? bytecodeLoc : DEFAULT_BYTECODE_RESOURCE;
                var bytecode = (bytecodeLoc != null)
                        ? Files.readAllBytes(Paths.get(pathToContract(bytecodeLoc)))
                        : ValidationScenarios.class
                                .getClassLoader()
                                .getResourceAsStream(DEFAULT_BYTECODE_RESOURCE)
                                .readAllBytes();
                var contractsDir = new File("contracts/");
                if (!contractsDir.exists()) {
                    contractsDir.mkdir();
                }

                var fileName = baseName.substring(baseName.lastIndexOf("/") + 1);
                var fout = Files.newOutputStream(Paths.get(pathToContract(fileName)));
                fout.write(bytecode);
                fout.close();
                sourceUpdate.accept(fileName);
                fileName = fileName.replace(".bin", ".sol");
                var sol = new String(ValidationScenarios.class
                        .getClassLoader()
                        .getResourceAsStream(DEFAULT_CONTRACT_RESOURCE)
                        .readAllBytes());
                var sourceOut = Files.newBufferedWriter(Paths.get(pathToContract(fileName)));
                sourceOut.write(sol);
                sourceOut.close();

                var bytecodeName = name + "Bytecode";
                var bytecodeCreate = fileCreate(bytecodeName)
                        .key(DEFAULT_PAYER)
                        .setNodeFrom(ValidationScenarios::nextNode)
                        .contents(bytecode);
                allRunFor(spec, bytecodeCreate);
                bytecodeNumUpdate.accept(bytecodeCreate.numOfCreatedFile());

                var create = contractCreate(PERSISTENT_CONTRACT_NAME)
                        .setNodeFrom(ValidationScenarios::nextNode)
                        .bytecode(bytecodeName);
                allRunFor(spec, create);

                Integer numberToUse = (luckyNo == null) ? DEFAULT_LUCKY_NUMBER : luckyNo;
                Object[] args = new Object[] {Long.valueOf(numberToUse)};
                var setLucky = contractCall(PERSISTENT_CONTRACT_NAME, "believeIn", args);
                allRunFor(spec, setLucky);

                var createdNo = create.numOfCreatedContract();
                var newLoc = pemLoc.replace("contract-1", String.format("contract%d", createdNo));
                spec.keys().exportEd25519Key(newLoc, name);
                numUpdate.accept(createdNo);

                if (luckyNo == null) {
                    luckyNoUpdate.accept(DEFAULT_LUCKY_NUMBER);
                }
            }
        });
    }

    private static LongSupplier persistentContractOrNegativeOne(ContractScenario contract) {
        return () -> ofNullable(contract.getPersistent())
                .flatMap(s -> ofNullable(s.getNum()))
                .orElse(-1L);
    }

    private static Stream<DynamicTest> consensusScenario() {
        try {
            ensureScenarios();
            if (scenarios.getConsensus() == null) {
                scenarios.setConsensus(new ConsensusScenario());
            }
            var consensus = scenarios.getConsensus();
            var expectedSeqNo = new AtomicLong(0);

            return customHapiSpec("ConsensusScenario")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(
                            keyFromPem(() -> pemForAccount(targetNetwork().getScenarioPayer()))
                                    .name(SCENARIO_PAYER_NAME)
                                    .linkedTo(() -> String.format(
                                            PATTERN, targetNetwork().getScenarioPayer())),
                            ensureValidatedTopicExistence(
                                    PERSISTENT_TOPIC_NAME,
                                    pemForTopic(persistentTopicOrNegativeOne(consensus)
                                            .getAsLong()),
                                    persistentTopicOrNegativeOne(consensus),
                                    consensus::setPersistent,
                                    expectedSeqNo))
                    .when(flattened(
                            submitMessageTo(PERSISTENT_TOPIC_NAME)
                                    .payingWith(SCENARIO_PAYER_NAME)
                                    .setNodeFrom(ValidationScenarios::nextNode)
                                    .message("The particular is pounded till it is man."),
                            novelTopicIfDesired()))
                    .then(getTopicInfo(PERSISTENT_TOPIC_NAME)
                            .payingWith(SCENARIO_PAYER_NAME)
                            .setNodeFrom(ValidationScenarios::nextNode)
                            .hasSeqNo(expectedSeqNo::get)
                            .logged());
        } catch (Exception e) {
            log.warn("Unable to initialize consensus scenario, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static Stream<DynamicTest> stakingScenario() {
        try {
            ensureScenarios();
            if (scenarios.getStaking() == null) {
                scenarios.setStaking(new StakingScenario());
            }

            return customHapiSpec("StakingScenario")
                    .withProperties(Map.of(
                            NODES,
                            nodes(),
                            DEFAULT_PAYER1,
                            primaryPayer(),
                            DEFAULT_NODE,
                            defaultNode(),
                            FEES_USE_FIXED_OFFER,
                            "true",
                            FEES_FIXED_OFFER,
                            "" + feeToOffer(),
                            DEFAULT_PAYER_KEY,
                            payerKeySeed()))
                    .given(keyFromPem(() -> pemForAccount(targetNetwork().getScenarioPayer()))
                            .name(SCENARIO_PAYER_NAME)
                            .linkedTo(
                                    () -> String.format(PATTERN, targetNetwork().getScenarioPayer())))
                    .when()
                    .then(withOpContext((spec, opLog) -> {
                        final AtomicReference<NodeAddressBook> addressBook = new AtomicReference<>();
                        final var getNodeDetails = getFileContents(NODE_DETAILS)
                                .payingWith(SCENARIO_PAYER_NAME)
                                .alertingPost(response -> {
                                    try {
                                        addressBook.set(NodeAddressBook.parseFrom(response.getFileGetContents()
                                                .getFileContents()
                                                .getContents()));
                                    } catch (InvalidProtocolBufferException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                        allRunFor(spec, getNodeDetails);
                        final var allUpdates = addressBook.get().getNodeAddressList().stream()
                                .map(nodeAddress -> cryptoUpdate(SCENARIO_PAYER_NAME)
                                        .payingWith(SCENARIO_PAYER_NAME)
                                        .newStakedNodeId(nodeAddress.getNodeId()))
                                .toArray(HapiSpecOperation[]::new);
                        allRunFor(spec, allUpdates);
                    }));
        } catch (Exception e) {
            log.warn("Unable to initialize staking scenario, skipping it!", e);
            errorsOccurred.set(true);
            return null;
        }
    }

    private static HapiSpecOperation[] novelTopicIfDesired() {
        if (!params.isNovelContent()) {
            return new HapiSpecOperation[0];
        }

        KeyShape complex = KeyShape.threshOf(1, KeyShape.listOf(2), KeyShape.threshOf(1, 3));
        return new HapiSpecOperation[] {
            newKeyNamed(NOVEL_TOPIC_ADMIN).shape(complex),
            createTopic(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .adminKeyName(NOVEL_TOPIC_ADMIN)
                    .submitKeyShape(KeyShape.SIMPLE),
            submitMessageTo(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .signedBy(SCENARIO_PAYER_NAME)
                    .hasKnownStatus(INVALID_SIGNATURE),
            updateTopic(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .signedBy(SCENARIO_PAYER_NAME, NOVEL_TOPIC_ADMIN)
                    .submitKey(EMPTY_KEY),
            submitMessageTo(NOVEL_TOPIC_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(ValidationScenarios::nextNode)
                    .signedBy(SCENARIO_PAYER_NAME),
            deleteTopic(NOVEL_TOPIC_NAME).payingWith(SCENARIO_PAYER_NAME).setNodeFrom(ValidationScenarios::nextNode),
            withOpContext((spec, opLog) -> novelTopicUsed.set(
                    HapiPropertySource.asTopicString(spec.registry().getTopicID(NOVEL_TOPIC_NAME))))
        };
    }

    private static HapiSpecOperation ensureValidatedTopicExistence(
            String name, String pemLoc, LongSupplier num, LongConsumer update, AtomicLong expectedSeqNo) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            long topicNo = num.getAsLong();
            if (topicNo > 0) {
                var check = getTopicInfo(idLiteral(num.getAsLong())).setNodeFrom(ValidationScenarios::nextNode);
                allRunFor(spec, check);

                var info = check.getResponse().getConsensusGetTopicInfo().getTopicInfo();
                final var key = Ed25519Utils.readKeyFrom(pemLoc, KeyFactory.PEM_PASSPHRASE);
                var expectedKey = Key.newBuilder()
                        .setEd25519(ByteString.copyFrom(key.getAbyte()))
                        .build();
                Assertions.assertEquals(
                        expectedKey,
                        info.getAdminKey(),
                        String.format("Topic 0.0.%d had a different key than expected!", topicNo));
                expectedSeqNo.set(info.getSequenceNumber() + 1);
                spec.registry().saveKey(name, expectedKey);
                spec.registry().saveTopicId(name, topicId(topicNo));
                spec.keys().incorporate(name, key);
            } else {
                var create = createTopic(name)
                        .setNodeFrom(ValidationScenarios::nextNode)
                        .adminKeyShape(KeyShape.SIMPLE);
                allRunFor(spec, create);
                var createdNo = create.numOfCreatedTopic();
                var newLoc = pemLoc.replace("topic-1", String.format("topic%d", createdNo));
                spec.keys().exportEd25519Key(newLoc, name);
                update.accept(createdNo);
                expectedSeqNo.set(1);
            }
        });
    }

    private static LongSupplier persistentTopicOrNegativeOne(ConsensusScenario consensus) {
        return () -> ofNullable(consensus.getPersistent()).orElse(-1L);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    private static void parse(String[] args) {
        var KEY_VALUE_PATTERN = Pattern.compile("([\\w\\d]+)=([\\w\\d,.-]+)");

        for (String arg : args) {
            var matcher = KEY_VALUE_PATTERN.matcher(arg);
            if (!matcher.matches()) {
                log.warn(String.format("Ignoring command-line argument '%s'", arg));
            } else {
                if ("target".equals(keyOf(matcher))) {
                    params.setTargetNetwork(valueOf(matcher));
                } else if ("defaultNodePayment".equals(keyOf(matcher))) {
                    try {
                        params.setDefaultNodePayment(Long.parseLong(valueOf(matcher)));
                    } catch (NumberFormatException ignore) {
                    }
                } else if ("novel".equals(keyOf(matcher))) {
                    params.setNovelContent(valueOf(matcher).toLowerCase().equals("true"));
                } else if ("revocation".equals(keyOf(matcher))) {
                    params.setRevocationService(valueOf(matcher).toLowerCase().equals("true"));
                } else if ("scenarios".equals(keyOf(matcher))) {
                    Set<String> legal = Arrays.stream(Scenario.class.getEnumConstants())
                            .map(Object::toString)
                            .collect(Collectors.toSet());
                    List<String> listed = Arrays.stream(valueOf(matcher).split(","))
                            .map(name -> name.equalsIgnoreCase("staking") ? STAKE_TO_EVERYBODY.name() : name)
                            .map(name -> name.equals("syskeys") ? "SYSTEM_KEYS" : name)
                            .map(name -> name.equals("xfers") ? "TRANSFERS_ONLY" : name)
                            .map(name -> name.equals("sysFilesDown") ? "SYS_FILES_DOWN" : name)
                            .map(name -> name.equals("sysFilesUp") ? "SYS_FILES_UP" : name)
                            .filter(v -> legal.contains(v.toUpperCase()))
                            .collect(toList());
                    if (listed.size() == 1) {
                        params.setScenarios(
                                EnumSet.of(Scenario.valueOf(listed.get(0).toUpperCase())));
                    } else if (listed.size() > 1) {
                        params.setScenarios(EnumSet.of(
                                Scenario.valueOf(listed.get(0).toUpperCase()),
                                listed.subList(1, listed.size()).stream()
                                        .map(String::toUpperCase)
                                        .map(Scenario::valueOf)
                                        .toArray(n -> new Scenario[n])));
                    }
                } else if ("config".equals(keyOf(matcher))) {
                    params.setConfigLoc(valueOf(matcher));
                } else {
                    log.warn(String.format("Ignoring unknown parameter key '%s'", keyOf(matcher)));
                }
            }
        }
        System.out.println(params.getScenarios());
    }

    private static String keyOf(Matcher m) {
        return m.group(1);
    }

    private static String valueOf(Matcher m) {
        return m.group(2);
    }

    private static class ScenarioParams {

        static long DEFAULT_NODE_PAYMENT_TINYBARS = 25;
        static final String PASSPHRASE_ENV_VAR = "BOOTSTRAP_PASSPHRASE";
        static final String DEFAULT_PASSPHRASE = "swirlds";

        private String configLoc = DEFAULT_CONFIG_LOC;
        private long defaultNodePayment = DEFAULT_NODE_PAYMENT_TINYBARS;
        private String targetNetwork;
        private boolean revocationService = false;
        private boolean novelContent = true;
        private EnumSet<Scenario> scenarios = EnumSet.noneOf(Scenario.class);

        boolean isRevocationService() {
            return revocationService;
        }

        String getRawPassphrase() {
            return ofNullable(System.getenv(PASSPHRASE_ENV_VAR)).orElse(DEFAULT_PASSPHRASE);
        }

        public String getConfigLoc() {
            return configLoc;
        }

        public void setConfigLoc(String configLoc) {
            this.configLoc = configLoc;
        }

        public void setDefaultNodePayment(long defaultNodePayment) {
            this.defaultNodePayment = defaultNodePayment;
        }

        public void setRevocationService(boolean revocationService) {
            this.revocationService = revocationService;
        }

        public EnumSet<Scenario> getScenarios() {
            return scenarios;
        }

        public void setScenarios(EnumSet<Scenario> scenarios) {
            this.scenarios = scenarios;
        }

        public String getTargetNetwork() {
            return targetNetwork;
        }

        public void setTargetNetwork(String targetNetwork) {
            this.targetNetwork = targetNetwork;
        }

        public boolean isNovelContent() {
            return novelContent;
        }

        public void setNovelContent(boolean novelContent) {
            this.novelContent = novelContent;
        }
    }

    private static void assertValidParams() {
        boolean exit = false;
        if (!validationConfig.getNetworks().containsKey(params.getTargetNetwork())) {
            log.error(String.format("No config present for network '%s', exiting.", params.getTargetNetwork()));
            exit = true;
        }
        for (Node node : targetNetwork().getNodes()) {
            nodeAccounts.add(String.format(PATTERN, node.getAccount()));
        }
        if (exit) {
            System.exit(1);
        }
    }

    private static void readConfig() {
        var yamlIn = new Yaml(new Constructor(ValidationConfig.class, new LoaderOptions()));
        try {
            System.out.println("Config loc: " + params.getConfigLoc());
            validationConfig = yamlIn.load(Files.newInputStream(Paths.get(params.getConfigLoc())));
        } catch (IOException e) {
            log.error("Could not locate '{}' exiting!", params.getConfigLoc(), e);
            System.exit(1);
        }
    }

    private static String nextNode() {
        var account = nodeAccounts.get(nextAccount++);
        nextAccount %= nodeAccounts.size();
        try {
            Thread.sleep(validationConfig.getSleepMsBeforeNextNode());
        } catch (InterruptedException ignore) {
        }
        return account;
    }

    private static String nodes() {
        return targetNetwork().getNodes().stream()
                .map(node -> String.format("%s:0.0.%d", node.getIpv4Addr(), node.getAccount()))
                .collect(Collectors.joining(","));
    }

    private static String defaultNode() {
        return String.format(PATTERN, targetNetwork().getDefaultNode());
    }

    private static String feeToOffer() {
        return "" + (targetNetwork().getDefaultFeeInHbars() * TINYBARS_PER_HBAR);
    }

    private static String primaryPayer() {
        return String.format(PATTERN, targetNetwork().getBootstrap());
    }

    private static Network targetNetwork() {
        return validationConfig.getNetworks().get(params.getTargetNetwork());
    }

    private static String payerKeySeed() {
        final var loc = pemForAccount(targetNetwork().getBootstrap());
        var f = new File(loc);
        if (!f.exists()) {
            log.error(String.format("Missing bootstrap PEM @ '%s', exiting.", loc));
        }

        final var payerKey = Ed25519Utils.readKeyFrom(f, params.getRawPassphrase());
        return CommonUtils.hex(payerKey.getSeed());
    }

    private static AccountID accountId(long num) {
        return HapiPropertySource.asAccount(String.format(PATTERN, num));
    }

    private static TopicID topicId(long num) {
        return HapiPropertySource.asTopic(String.format(PATTERN, num));
    }

    private static FileID fileId(long num) {
        return HapiPropertySource.asFile(String.format(PATTERN, num));
    }

    private static ContractID contractId(long num) {
        return asContract(String.format(PATTERN, num));
    }

    private static String idLiteral(long num) {
        return String.format(PATTERN, num);
    }

    private static String pemForAccount(long num) {
        return pemForEntity(num, "account");
    }

    private static String pemForTopic(long num) {
        return pemForEntity(num, "topic");
    }

    private static String pemForFile(long num) {
        return pemForEntity(num, "file");
    }

    private static String pemForContract(long num) {
        return pemForEntity(num, "contract");
    }

    private static String pemForEntity(long num, String entity) {
        var preferredLoc = String.format("keys/%s/%s%d.pem", params.getTargetNetwork(), entity, num);
        if (new File(preferredLoc).exists()) {
            return preferredLoc;
        } else {
            return String.format("keys/%s-%s%d.pem", params.getTargetNetwork(), entity, num);
        }
    }

    private static String pathTo(String contents) {
        return String.format("files/%s", contents);
    }

    private static String pathToContract(String contents) {
        return String.format("contracts/%s", contents);
    }

    private static void persistUpdatedConfig() {
        var yamlOut = new Yaml(new SkipNullRepresenter());
        var doc = yamlOut.dumpAs(validationConfig, Tag.MAP, null);
        try {
            var writer = Files.newBufferedWriter(Paths.get(params.getConfigLoc()));
            writer.write(doc);
            writer.close();
        } catch (IOException e) {
            log.warn("Could not update {} with scenario results, skipping!", params.getConfigLoc(), e);
        }
    }

    private static class SkipNullRepresenter extends Representer {

        public SkipNullRepresenter() {
            super(new DumperOptions());
        }

        @Override
        protected NodeTuple representJavaBeanProperty(
                Object javaBean, Property property, Object propertyValue, Tag customTag) {
            if (propertyValue == null) {
                return null;
            } else {
                return super.representJavaBeanProperty(javaBean, property, propertyValue, customTag);
            }
        }
    }

    private static void ensureScenarios() {
        if (targetNetwork().getScenarios() == null) {
            scenarios = new Scenarios();
            targetNetwork().setScenarios(scenarios);
        } else {
            scenarios = targetNetwork().getScenarios();
        }
    }

    private static void printBalanceChange() {
        if (startingBalance.get() >= 0 && endingBalance.get() >= 0) {
            long payerChange = endingBalance.get() - startingBalance.get();
            log.info(String.format(
                    "0.0.%d balance change was %d tinyBars (%.2f \u0127)",
                    targetNetwork().getBootstrap(), payerChange, (double) payerChange / 100_000_000));
        } else if (startingBalance.get() >= 0) {
            log.info(String.format(
                    "0.0.%d balance is now %d tinyBars (%.2f \u0127)",
                    targetNetwork().getBootstrap(),
                    startingBalance.get(),
                    (double) startingBalance.get() / 100_000_000));
        }
    }

    private static void printNovelUsage() {
        log.info("------------------------------------------------------------------");
        ofNullable(novelAccountUsed.get())
                .ifPresent(s -> log.info("Novel account used (should now be deleted) was {}", s));
        ofNullable(novelFileUsed.get()).ifPresent(s -> log.info("Novel file used (should now be deleted) was {}", s));
        ofNullable(novelContractUsed.get())
                .ifPresent(s -> log.info("Novel contract used (should now be deleted) was {}", s));
        ofNullable(novelTopicUsed.get()).ifPresent(s -> log.info("Novel topic used (should now be deleted) was {}", s));
    }
}

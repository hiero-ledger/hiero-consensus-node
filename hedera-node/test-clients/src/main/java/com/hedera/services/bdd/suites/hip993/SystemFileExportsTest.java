// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip993;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromByteString;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.hapi.utils.forensics.OrderedComparison.statusHistograms;
import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.CLASSIC_HAPI_TEST_NETWORK_SIZE;
import static com.hedera.services.bdd.spec.HapiPropertySource.asDnsServiceEndpoint;
import static com.hedera.services.bdd.spec.HapiPropertySource.asServiceEndpoint;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUtf8Bytes;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.resourceAsString;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.sysFileUpdateTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.simulatePostUpgradeTransaction;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.allVisibleItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.given;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.nOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.writeToNodeWorkingDirs;
import static com.hedera.services.bdd.spec.utilops.grouping.GroupingVerbs.getSystemFiles;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.EventualRecordStreamAssertion.eventuallyAssertingExplicitPassWithReplay;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsAssertion.ALL_TX_IDS;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.StandardSerdes.SYS_FILE_SERDES;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.NodeStakeUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.NodeUpdate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.NodeAddressBook;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.grouping.SysFileLookups;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItems;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NodeUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.ThrottleDefinitions;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.hiero.base.utility.CommonUtils;
import org.hiero.consensus.model.roster.Address;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

/**
 * Asserts the synthetic file creations stipulated by HIP-993 match the file contents returned by the gRPC
 * API both before after the network has handled the genesis transaction. (It would be a annoyance for various tools
 * and tests if they needed to ensure a transaction was handled before issuing any {@code FileGetContents} queries
 * or submitting {@code FileUpdate} transactions.)
 */
public class SystemFileExportsTest {
    private static final String DESCRIPTION_PREFIX = "Revision #";

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticNodeDetailsUpdateHappensAtUpgradeBoundary() {
        final var grpcCertHashes = new byte[][] {
            randomUtf8Bytes(48), randomUtf8Bytes(48), randomUtf8Bytes(48), randomUtf8Bytes(48),
        };
        final AtomicReference<Map<Long, X509Certificate>> gossipCertificates = new AtomicReference<>();
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        nodeDetailsExportValidator(grpcCertHashes, gossipCertificates),
                        1,
                        sysFileUpdateTo("files.nodeDetails"))),
                recordStreamMustIncludeNoFailuresFrom(allVisibleItems(uniqueConsTimesValidator())),
                given(() -> gossipCertificates.set(generateCertificates(CLASSIC_HAPI_TEST_NETWORK_SIZE))),
                // This is the genesis transaction
                cryptoCreate("firstUser"),
                overriding("nodes.updateAccountIdAllowed", "true"),
                sourcing(() -> blockingOrder(nOps(CLASSIC_HAPI_TEST_NETWORK_SIZE, i -> nodeUpdate("" + i)
                        .description(DESCRIPTION_PREFIX + i)
                        .serviceEndpoint(endpointsFor(i))
                        .grpcCertificateHash(grpcCertHashes[i])
                        .gossipCaCertificate(derEncoded(gossipCertificates.get().get((long) i)))))),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                cryptoCreate("secondUser").via("addressBookExport"),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticAddressBookUpdateHappensAtUpgradeBoundary() {
        final var grpcCertHashes = new byte[][] {
            randomUtf8Bytes(48), randomUtf8Bytes(48), randomUtf8Bytes(48), randomUtf8Bytes(48),
        };
        final AtomicReference<Map<Long, X509Certificate>> gossipCertificates = new AtomicReference<>();
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        addressBookExportValidator("files.addressBook", grpcCertHashes), 2, TxnUtils::isSysFileUpdate)),
                given(() -> gossipCertificates.set(generateCertificates(CLASSIC_HAPI_TEST_NETWORK_SIZE))),
                // This is the genesis transaction
                cryptoCreate("firstUser"),
                overriding("nodes.updateAccountIdAllowed", "true"),
                sourcing(() -> blockingOrder(nOps(CLASSIC_HAPI_TEST_NETWORK_SIZE, i -> nodeUpdate("" + i)
                        .description(DESCRIPTION_PREFIX + i)
                        .serviceEndpoint(endpointsFor(i))
                        .grpcCertificateHash(grpcCertHashes[i])
                        .gossipCaCertificate(derEncoded(gossipCertificates.get().get((long) i)))))),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                cryptoCreate("secondUser").via("addressBookExport"),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticFeeSchedulesUpdateHappensAtUpgradeBoundary()
            throws InvalidProtocolBufferException {
        final var feeSchedulesJson = resourceAsString("scheduled-contract-fees.json");
        final var upgradeFeeSchedules =
                CurrentAndNextFeeSchedule.parseFrom(SYS_FILE_SERDES.get(111L).toRawFile(feeSchedulesJson, null));
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        sysFileExportValidator(
                                "files.feeSchedules", upgradeFeeSchedules, SystemFileExportsTest::parseFeeSchedule),
                        3,
                        TxnUtils::isSysFileUpdate)),
                // This is the genesis transaction
                sourcingContextual(spec -> overriding("scheduling.whitelist", "ContractCall")),
                // Write the upgrade file to the node's working dirs
                doWithStartupConfig(
                        "networkAdmin.upgradeFeeSchedulesFile",
                        feeSchedulesFile ->
                                writeToNodeWorkingDirs(feeSchedulesJson, "data", "config", feeSchedulesFile)),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                // Verify the new fee schedules (which include a subtype for scheduled contract fees) are in effect
                uploadInitCode("SimpleUpdate"),
                withOpContext((spec, opLog) -> spec.tryReinitializingFees()),
                contractCreate("SimpleUpdate").gas(300_000L),
                cryptoCreate("civilian"),
                scheduleCreate(
                                "contractCall",
                                contractCall("SimpleUpdate", "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                        .gas(24_000)
                                        .memo("")
                                        .fee(ONE_HBAR))
                        .payingWith("civilian")
                        .via("contractCall"),
                validateChargedUsdWithin("contractCall", 0.1, 3.0),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticThrottlesUpdateHappensAtUpgradeBoundary() throws InvalidProtocolBufferException {
        final var throttlesJson = resourceAsString("testSystemFiles/one-tps-nft-mint.json");
        final var upgradeThrottleDefs =
                ThrottleDefinitions.parseFrom(SYS_FILE_SERDES.get(123L).toRawFile(throttlesJson, null));
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        sysFileExportValidator(
                                "files.throttleDefinitions",
                                upgradeThrottleDefs,
                                SystemFileExportsTest::parseThrottleDefs),
                        3,
                        TxnUtils::isSysFileUpdate)),
                // This is the genesis transaction
                sourcingContextual(spec -> overriding("tokens.nfts.mintThrottleScaleFactor", "1:1")),
                // Now write the upgrade file to the node's working dirs
                doWithStartupConfig(
                        "networkAdmin.upgradeThrottlesFile",
                        throttleDefsFile -> writeToNodeWorkingDirs(throttlesJson, "data", "config", throttleDefsFile)),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                // Then verify the new throttles are in effect
                cryptoCreate("civilian"),
                tokenCreate("nft").supplyKey("civilian"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("YES")))
                        .payingWith("civilian")
                        .deferStatusResolution(),
                mintToken("nft", List.of(ByteString.copyFromUtf8("NO")))
                        .payingWith("civilian")
                        .hasPrecheck(BUSY),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticPropertyOverridesUpdateHappensAtUpgradeBoundary()
            throws InvalidProtocolBufferException {
        final var overrideProperties = "tokens.nfts.maxBatchSizeMint=2";
        final var upgradePropOverrides =
                ServicesConfigurationList.parseFrom(SYS_FILE_SERDES.get(121L).toRawFile(overrideProperties, null));
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        sysFileExportValidator(
                                "files.networkProperties",
                                upgradePropOverrides,
                                SystemFileExportsTest::parseConfigList),
                        3,
                        TxnUtils::isSysFileUpdate)),
                // This is the genesis transaction
                cryptoCreate("genesisAccount"),
                // Now write the upgrade file to the node's working dirs
                doWithStartupConfig(
                        "networkAdmin.upgradePropertyOverridesFile",
                        propOverridesFile ->
                                writeToNodeWorkingDirs(overrideProperties, "data", "config", propOverridesFile)),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                // Then verify the new properties are in effect
                tokenCreate("nft").supplyKey(DEFAULT_PAYER),
                mintToken(
                                "nft",
                                List.of(
                                        ByteString.copyFromUtf8("ONE"),
                                        ByteString.copyFromUtf8("TOO"),
                                        ByteString.copyFromUtf8("MANY")))
                        .hasKnownStatus(BATCH_SIZE_LIMIT_EXCEEDED),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticPropertyOverridesUpdateCanBeEmptyFile() {
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        sysFileExportValidator(
                                "files.networkProperties",
                                ServicesConfigurationList.getDefaultInstance(),
                                SystemFileExportsTest::parseConfigList),
                        3,
                        TxnUtils::isSysFileUpdate)),
                // This is the genesis transaction
                sourcingContextual(spec -> overriding("tokens.nfts.maxBatchSizeMint", "2")),
                // Now write the upgrade file to the node's working dirs
                doWithStartupConfig(
                        "networkAdmin.upgradePropertyOverridesFile",
                        propOverridesFile -> writeToNodeWorkingDirs("", "data", "config", propOverridesFile)),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                // Then verify the previous override properties are cleared
                tokenCreate("nft").supplyKey(DEFAULT_PAYER),
                mintToken(
                        "nft",
                        List.of(
                                ByteString.copyFromUtf8("ONCE"),
                                ByteString.copyFromUtf8("AGAIN"),
                                ByteString.copyFromUtf8("OK"))),
                getFileContents(APP_PROPERTIES).hasContents(ignore -> new byte[0]),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticPermissionOverridesUpdateHappensAtUpgradeBoundary()
            throws InvalidProtocolBufferException {
        final var overridePermissions = "tokenMint=0-1";
        final var upgradePermissionOverrides =
                ServicesConfigurationList.parseFrom(SYS_FILE_SERDES.get(122L).toRawFile(overridePermissions, null));
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        sysFileExportValidator(
                                "files.hapiPermissions",
                                upgradePermissionOverrides,
                                SystemFileExportsTest::parseConfigList),
                        3,
                        TxnUtils::isSysFileUpdate)),
                // This is the genesis transaction
                cryptoCreate("genesisAccount"),
                // Now write the upgrade file to the node's working dirs
                doWithStartupConfig(
                        "networkAdmin.upgradePermissionOverridesFile",
                        permissionOverridesFile ->
                                writeToNodeWorkingDirs(overridePermissions, "data", "config", permissionOverridesFile)),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                // Then verify the new permissions are in effect
                cryptoCreate("civilian"),
                tokenCreate("nft").supplyKey("civilian"),
                mintToken(
                                "nft",
                                List.of(
                                        ByteString.copyFromUtf8("NOT"),
                                        ByteString.copyFromUtf8("TO"),
                                        ByteString.copyFromUtf8("BE")))
                        .payingWith("civilian")
                        .hasKnownStatus(UNAUTHORIZED),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticNodeAdminKeysUpdateHappensAtUpgradeBoundary() {
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        nodeUpdatesValidator(),
                        // Our node admin key file will contain two override keys
                        2,
                        (spec, item) -> {
                            final var entry = RecordStreamEntry.from(item);
                            return entry.function() == NodeUpdate
                                    && entry.txnId().getAccountID().getAccountNum()
                                            == spec.startupProperties().getLong("accounts.systemAdmin");
                        })),
                newKeyNamed("node0AdminKey").shape(ED25519_ON),
                newKeyNamed("node3AdminKey").shape(ED25519_ON),
                // This is the genesis transaction
                cryptoCreate("anybody"),
                // Now write the node admin key overrides file to the node's working dirs
                sourcingContextual(spec -> doWithStartupConfig(
                        "networkAdmin.upgradeNodeAdminKeysFile",
                        nodeAdminKeysFile -> writeToNodeWorkingDirs(
                                toJson(Map.of(
                                        0L, spec.registry().getKey("node0AdminKey"),
                                        3L, spec.registry().getKey("node3AdminKey"))),
                                "data",
                                "config",
                                nodeAdminKeysFile))),
                waitUntilNextBlock().withBackgroundTraffic(true),
                // And now simulate an upgrade boundary
                simulatePostUpgradeTransaction(),
                // Then verify the new admin keys are in effect
                cryptoCreate("civilian"),
                // We cannot update 0 or 3 because the admin keys have changed
                nodeUpdate("0").payingWith(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                nodeUpdate("3").payingWith(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                // But we still can update 1
                nodeUpdate("1").payingWith(GENESIS).description("B"),
                // And by signing with the override admin keys, we can even update 0 and 3
                nodeUpdate("0")
                        .payingWith(GENESIS)
                        .signedBy(GENESIS, "node0AdminKey")
                        .description("A"),
                nodeUpdate("3")
                        .payingWith(GENESIS)
                        .signedBy(GENESIS, "node3AdminKey")
                        .description("C"),
                // Trigger block closure to ensure block is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> syntheticFileCreationsMatchQueries() {
        final AtomicReference<Map<FileID, Bytes>> preGenesisContents = new AtomicReference<>();
        return hapiTest(
                eventuallyAssertingExplicitPassWithReplay(
                        selectedItems(validatorFor(preGenesisContents), 17, (ignore, item) -> item.getRecord()
                                .getReceipt()
                                .hasFileID()),
                        Duration.ofSeconds(10)),
                getSystemFiles(preGenesisContents::set),
                cryptoCreate("firstUser").via("genesisTxn"),
                // Assert the first created entity still has the expected number
                withOpContext((spec, opLog) -> assertEquals(
                        spec.startupProperties().getLong("hedera.firstUserEntity"),
                        spec.registry().getAccountID("firstUser").getAccountNum(),
                        "First user entity num doesn't match config")));
    }

    private static CurrentAndNextFeeSchedule parseFeeSchedule(final byte[] bytes)
            throws InvalidProtocolBufferException {
        return CurrentAndNextFeeSchedule.parseFrom(bytes);
    }

    private static ThrottleDefinitions parseThrottleDefs(final byte[] bytes) throws InvalidProtocolBufferException {
        return ThrottleDefinitions.parseFrom(bytes);
    }

    private static ServicesConfigurationList parseConfigList(final byte[] bytes) throws InvalidProtocolBufferException {
        return ServicesConfigurationList.parseFrom(bytes);
    }

    private interface ParseFunction<T> {
        T parse(@NonNull byte[] bytes) throws InvalidProtocolBufferException;
    }

    private static VisibleItemsValidator nodeUpdatesValidator() {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No post-upgrade node updates found");
            final Map<Long, Key> newAdminKeys = items.entries().stream()
                    .filter(item -> item.function() == NodeUpdate)
                    .map(item -> item.body().getNodeUpdate())
                    .collect(toMap(NodeUpdateTransactionBody::getNodeId, NodeUpdateTransactionBody::getAdminKey));
            assertEquals(spec.registry().getKey("node0AdminKey"), newAdminKeys.get(0L));
            assertEquals(spec.registry().getKey("node3AdminKey"), newAdminKeys.get(3L));
        };
    }

    private static <T> VisibleItemsValidator sysFileExportValidator(
            @NonNull final String fileNumProperty,
            @NonNull final T expectedValue,
            @NonNull final ParseFunction<T> parser) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No post-upgrade txn found");
            final var targetId = new FileID(
                    spec.shard(),
                    spec.realm(),
                    Long.parseLong(spec.startupProperties().get(fileNumProperty)));
            final var updateItem = items.entries().stream()
                    .filter(item -> item.function() == FileUpdate)
                    .filter(item ->
                            toPbj(item.body().getFileUpdate().getFileID()).equals(targetId))
                    .findFirst()
                    .orElse(null);
            assertNotNull(updateItem, "No update for " + fileNumProperty + " found in post-upgrade txn");
            final var synthOp = updateItem.body().getFileUpdate();
            final T actual;
            try {
                actual = parser.parse(synthOp.getContents().toByteArray());
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException(fileNumProperty + " update was not parseable", e);
            }
            assertEquals(expectedValue, actual);
        };
    }

    private static VisibleItemsValidator uniqueConsTimesValidator() {
        return (spec, records) -> {
            final var items = records.get(ALL_TX_IDS);
            final var uniqueConsTimes = items.entries().stream()
                    .map(RecordStreamEntry::consensusTime)
                    .collect(toSet());
            assertEquals(items.size(), uniqueConsTimes.size(), "Expected all entries to have unique consensus times");
        };
    }

    private static VisibleItemsValidator nodeDetailsExportValidator(
            @NonNull final byte[][] grpcCertHashes,
            @NonNull final AtomicReference<Map<Long, X509Certificate>> gossipCertificates) {
        return (spec, records) -> {
            final var items = requireNonNull(records.get(SELECTED_ITEMS_KEY));
            final var histogram = statusHistograms(items.entries());
            assertEquals(Map.of(SUCCESS, 1), histogram.get(FileUpdate));
            final var updateItem = items.entries().stream()
                    .filter(item -> item.function() == FileUpdate)
                    .findFirst()
                    .orElseThrow();
            final var synthOp = updateItem.body().getFileUpdate();
            final var nodeDetailsId = new FileID(
                    spec.shard(),
                    spec.realm(),
                    Long.parseLong(spec.startupProperties().get("files.nodeDetails")));
            assertEquals(nodeDetailsId, toPbj(synthOp.getFileID()));
            try {
                final var updatedAddressBook = NodeAddressBook.PROTOBUF.parse(
                        Bytes.wrap(synthOp.getContents().toByteArray()));
                var prevNodeId = -1L;
                for (final var address : updatedAddressBook.nodeAddress()) {
                    assertTrue(address.nodeId() > prevNodeId, "Node IDs must be in ascending order");
                    final var expectedCert = gossipCertificates.get().get(address.nodeId());
                    final var expectedPubKey = expectedCert.getPublicKey().getEncoded();
                    final var actualPubKey = unhex(address.rsaPubKey());
                    assertArrayEquals(expectedPubKey, actualPubKey, "node" + address.nodeId() + " has wrong RSA key");

                    final var actualCertHash = address.nodeCertHash().toByteArray();
                    assertArrayEquals(
                            getHexStringBytesFromBytes(grpcCertHashes[(int) address.nodeId()]),
                            actualCertHash,
                            "node" + address.nodeId() + " has wrong cert hash");

                    final var expectedDescription = DESCRIPTION_PREFIX + address.nodeId();
                    assertEquals(expectedDescription, address.description());

                    final var expectedServiceEndpoint = endpointsFor((int) address.nodeId());
                    assertEquals(expectedServiceEndpoint, address.serviceEndpoint());
                    prevNodeId = address.nodeId();
                }
            } catch (ParseException e) {
                Assertions.fail("Update contents was not protobuf " + e.getMessage());
            }
        };
    }

    private static byte[] getHexStringBytesFromBytes(final byte[] rawBytes) {
        final String hexString = HexFormat.of().formatHex(rawBytes);
        return Normalizer.normalize(hexString, Normalizer.Form.NFD).getBytes(UTF_8);
    }

    private static VisibleItemsValidator addressBookExportValidator(
            @NonNull final String fileNumProperty, @NonNull final byte[][] grpcCertHashes) {
        return (spec, records) -> {
            final var items = records.get(SELECTED_ITEMS_KEY);
            assertNotNull(items, "No post-upgrade txn found");
            final var targetId = new FileID(
                    spec.shard(),
                    spec.realm(),
                    Long.parseLong(spec.startupProperties().get(fileNumProperty)));
            final var updateItem = items.entries().stream()
                    .filter(item -> item.function() == FileUpdate)
                    .filter(item ->
                            toPbj(item.body().getFileUpdate().getFileID()).equals(targetId))
                    .findFirst()
                    .orElse(null);
            assertNotNull(updateItem, "No update for " + fileNumProperty + " found in post-upgrade txn");
            final var synthOp = updateItem.body().getFileUpdate();
            final var addressBookId = new FileID(
                    spec.shard(),
                    spec.realm(),
                    Long.parseLong(spec.startupProperties().get(fileNumProperty)));
            assertEquals(addressBookId, toPbj(synthOp.getFileID()));
            try {
                final var updatedAddressBook = NodeAddressBook.PROTOBUF.parse(
                        Bytes.wrap(synthOp.getContents().toByteArray()));
                var prevNodeId = -1L;
                for (final var address : updatedAddressBook.nodeAddress()) {
                    assertTrue(address.nodeId() > prevNodeId, "Node IDs must be in ascending order");
                    final var actualCertHash = address.nodeCertHash().toByteArray();
                    assertArrayEquals(
                            getHexStringBytesFromBytes(grpcCertHashes[(int) address.nodeId()]),
                            actualCertHash,
                            "node" + address.nodeId() + " has wrong cert hash");

                    final var expectedServiceEndpoint = endpointsFor((int) address.nodeId());
                    assertEquals(expectedServiceEndpoint, address.serviceEndpoint());
                    prevNodeId = address.nodeId();
                }
            } catch (ParseException e) {
                Assertions.fail("Update contents was not protobuf " + e.getMessage());
            }
        };
    }

    private static VisibleItemsValidator validatorFor(
            @NonNull final AtomicReference<Map<FileID, Bytes>> preGenesisContents) {
        return (spec, records) -> validateSystemFileExports(spec, records, preGenesisContents.get());
    }

    private static void validateSystemFileExports(
            @NonNull final HapiSpec spec,
            @NonNull final Map<String, VisibleItems> genesisRecords,
            @NonNull final Map<FileID, Bytes> preGenesisContents) {
        final var items = requireNonNull(genesisRecords.get(SELECTED_ITEMS_KEY));
        final var histogram = statusHistograms(items.entries());
        final var systemFileNums =
                SysFileLookups.allSystemFileNums(spec).boxed().toList();
        assertEquals(Map.of(SUCCESS, systemFileNums.size()), histogram.get(FileCreate));
        final var postGenesisContents = SysFileLookups.getSystemFileContents(spec, fileNum -> true);
        items.entries().forEach(item -> {
            final var fileId = item.createdFileId();
            final var preContents = requireNonNull(
                    preGenesisContents.get(item.createdFileId()), "No pre-genesis contents for " + fileId);
            final var postContents = requireNonNull(
                    postGenesisContents.get(item.createdFileId()), "No post-genesis contents for " + fileId);
            final var exportedContents =
                    fromByteString(item.body().getFileCreate().getContents());
            if (fileId.fileNum()
                    != 102) { // for nodedetail, the node's weight changed between preContent and exportedContents
                assertEquals(exportedContents, preContents, fileId + " contents don't match pre-genesis query");
            }
            assertEquals(exportedContents, postContents, fileId + " contents don't match post-genesis query");
        });
    }

    private static VisibleItemsValidator validatorSpecificSysFileFor(
            @NonNull final AtomicReference<Bytes> fileContent,
            @NonNull final String fileNumProperty,
            @NonNull final String specTxnIds) {
        return (spec, records) ->
                specificSysFileValidator(spec, records, fileContent.get(), fileNumProperty, specTxnIds);
    }

    private static void specificSysFileValidator(
            @NonNull final HapiSpec spec,
            @NonNull final Map<String, VisibleItems> genesisRecords,
            @NonNull final Bytes fileContent,
            @NonNull final String fileNumProperty,
            @NonNull final String specTxnIds) {
        final var items = requireNonNull(genesisRecords.get(specTxnIds));
        final long fileNumb = spec.startupProperties().getLong(fileNumProperty);
        final var histogram = statusHistograms(items.entries());
        final var systemFileNums =
                SysFileLookups.allSystemFileNums(spec).boxed().toList();
        assertEquals(Map.of(SUCCESS, systemFileNums.size()), histogram.get(FileCreate));
        // Also check we export a node stake update at genesis
        assertEquals(Map.of(SUCCESS, 1), histogram.get(NodeStakeUpdate));
        final var fileItem = items.entries().stream()
                .filter(item -> item.function() == FileCreate)
                .filter(item -> item.createdFileId().equals(new FileID(spec.shard(), spec.realm(), fileNumb)))
                .findFirst()
                .orElse(null);

        assertNotNull(fileItem, "No create item for " + fileNumProperty + " found in " + specTxnIds + " txn");
        final var fileCreateContents = fileItem.body().getFileCreate().getContents();
        assertNotNull(
                fileCreateContents, "No create content for " + fileNumProperty + " found in " + specTxnIds + " txn");
        if (fileNumProperty.equals("files.nodeDetails")) {
            try {
                final var addressBook = NodeAddressBook.PROTOBUF.parse(fileContent);
                final var updatedAddressBook =
                        NodeAddressBook.PROTOBUF.parse(Bytes.wrap(fileCreateContents.toByteArray()));
                assertEquals(
                        addressBook.nodeAddress().size(),
                        updatedAddressBook.nodeAddress().size(),
                        "address book size mismatch");

                for (int i = 0;
                        i < addressBook.nodeAddress().size();
                        i++) { // only stake not matching because of recalculating
                    final var address = updatedAddressBook.nodeAddress().get(i);
                    final var updatedAddress = updatedAddressBook.nodeAddress().get(i);
                    assertEquals(address.nodeId(), updatedAddress.nodeId(), "nodeId mismatch");
                    assertEquals(address.nodeAccountId(), updatedAddress.nodeAccountId(), "nodeAccountId mismatch");
                    assertEquals(address.nodeCertHash(), updatedAddress.nodeCertHash(), "nodeCertHash mismatch");
                    assertEquals(address.description(), updatedAddress.description(), "description mismatch");
                    assertEquals(address.rsaPubKey(), updatedAddress.rsaPubKey(), "rsaPubKey mismatch");
                    assertEquals(
                            address.serviceEndpoint(), updatedAddress.serviceEndpoint(), "serviceEndpoint mismatch");
                }
            } catch (ParseException e) {
                Assertions.fail("Update contents was not protobuf " + e.getMessage());
            }
        } else {
            assertEquals(
                    fileContent, fromByteString(fileCreateContents), fileNumb + " contents don't match genesis query");
        }
    }

    private static Map<Long, X509Certificate> generateCertificates(final int n) {
        final var randomAddressBook = RandomAddressBookBuilder.create(new Random())
                .withSize(n)
                .withRealKeysEnabled(true)
                .build();
        final var nextNodeId = new AtomicLong();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(randomAddressBook.iterator(), 0), false)
                .map(Address::getSigCert)
                .collect(Collectors.toMap(cert -> nextNodeId.getAndIncrement(), cert -> cert));
    }

    private static byte[] derEncoded(final X509Certificate cert) {
        try {
            return cert.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("Failed to DER encode cert", e);
        }
    }

    private static List<ServiceEndpoint> endpointsFor(final int i) {
        if (i % 2 == 0) {
            return List.of(asServiceEndpoint("127.0.0." + (i * 2 + 1) + ":" + (80 + i)));
        } else {
            return List.of(asDnsServiceEndpoint("host" + i + ":" + (80 + i)));
        }
    }

    private static String toJson(@NonNull final Map<Long, Key> nodeAdminKeys) {
        final var mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(nodeAdminKeys.entrySet().stream()
                    .collect(toMap(
                            entry -> entry.getKey().toString(),
                            entry -> CommonUtils.hex(
                                    entry.getValue().getEd25519().toByteArray()))));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize node admin keys", e);
        }
    }
}

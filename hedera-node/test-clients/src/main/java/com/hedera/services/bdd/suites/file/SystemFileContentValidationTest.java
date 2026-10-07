// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file;

import static com.hedera.services.bdd.junit.ContextRequirement.PERMISSION_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.file.HapiFileUpdate.getUpdated121;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONFIG_FILE_PART_UPLOADED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Asserts that updates to 0.0.121 and 0.0.122 report a non-{@code SUCCESS} status when their contents
 * cannot be parsed. Each spec restores the file it touches, since unparseable contents in either one drop
 * every active property override for the specs that follow.
 */
public class SystemFileContentValidationTest {
    private static final String PROPERTIES_SNAPSHOT = "networkPropertiesSnapshot";
    private static final String PERMISSIONS_SNAPSHOT = "hapiPermissionsSnapshot";
    private static final String UNPARSEABLE_CONTENTS = "NOT_A_SERVICES_CONFIGURATION_LIST";

    private static final String TRANSFERS_MAX_LEN = "ledger.transfers.maxLen";
    private static final String CIVILIAN = "civilian";
    private static final String RECEIVER_A = "receiverA";
    private static final String RECEIVER_B = "receiverB";

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> unparseableNetworkPropertiesUpdateIsNotSuccess() {
        return hapiTest(
                getFileContents(APP_PROPERTIES).saveToRegistry(PROPERTIES_SNAPSHOT),
                fileUpdate(APP_PROPERTIES)
                        .contents(UNPARSEABLE_CONTENTS)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(CONFIG_FILE_PART_UPLOADED),
                updateLargeFile(GENESIS, APP_PROPERTIES, PROPERTIES_SNAPSHOT));
    }

    @LeakyHapiTest(requirement = PERMISSION_OVERRIDES)
    final Stream<DynamicTest> unparseableHapiPermissionsUpdateIsNotSuccess() {
        return hapiTest(
                getFileContents(API_PERMISSIONS).saveToRegistry(PERMISSIONS_SNAPSHOT),
                fileUpdate(API_PERMISSIONS)
                        .contents(UNPARSEABLE_CONTENTS)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(CONFIG_FILE_PART_UPLOADED),
                updateLargeFile(GENESIS, API_PERMISSIONS, PERMISSIONS_SNAPSHOT));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> validNetworkPropertiesUpdateSucceedsAndApplies() {
        return hapiTest(
                getFileContents(APP_PROPERTIES).saveToRegistry(PROPERTIES_SNAPSHOT),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_A).balance(0L),
                cryptoCreate(RECEIVER_B).balance(0L),
                fileUpdate(APP_PROPERTIES)
                        .overridingProps(Map.of(TRANSFERS_MAX_LEN, "2"))
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(SUCCESS),
                // The override is in effect, not just written to the file
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, RECEIVER_A, 1L), tinyBarsFromTo(CIVILIAN, RECEIVER_B, 1L))
                        .payingWith(CIVILIAN)
                        .hasKnownStatus(TRANSFER_LIST_SIZE_LIMIT_EXCEEDED),
                updateLargeFile(GENESIS, APP_PROPERTIES, PROPERTIES_SNAPSHOT));
    }

    @LeakyHapiTest(requirement = PERMISSION_OVERRIDES)
    final Stream<DynamicTest> validHapiPermissionsUpdateSucceedsAndApplies() {
        return hapiTest(
                getFileContents(API_PERMISSIONS).saveToRegistry(PERMISSIONS_SNAPSHOT),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                fileUpdate(API_PERMISSIONS)
                        .overridingProps(Map.of("createFile", "0-100"))
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(SUCCESS),
                // The permission change is in effect, not just written to the file
                fileCreate("denied").contents("x").payingWith(CIVILIAN).hasKnownStatus(UNAUTHORIZED),
                updateLargeFile(GENESIS, API_PERMISSIONS, PERMISSIONS_SNAPSHOT));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> multiPartNetworkPropertiesUploadIsNotRejectedMidSequence() {
        final AtomicReference<byte[]> payload = new AtomicReference<>();
        return hapiTest(
                getFileContents(APP_PROPERTIES).saveToRegistry(PROPERTIES_SNAPSHOT),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_A).balance(0L),
                cryptoCreate(RECEIVER_B).balance(0L),
                withOpContext((spec, opLog) -> payload.set(getUpdated121(spec, Map.of(TRANSFERS_MAX_LEN, "2")))),
                // Ending mid-field is what makes a partial upload unparseable
                sourcing(() -> fileUpdate(APP_PROPERTIES)
                        .contents(Arrays.copyOfRange(payload.get(), 0, payload.get().length - 1))
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(CONFIG_FILE_PART_UPLOADED)),
                sourcing(() -> fileAppend(APP_PROPERTIES)
                        .content(Arrays.copyOfRange(payload.get(), payload.get().length - 1, payload.get().length))
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(SUCCESS)),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, RECEIVER_A, 1L), tinyBarsFromTo(CIVILIAN, RECEIVER_B, 1L))
                        .payingWith(CIVILIAN)
                        .hasKnownStatus(TRANSFER_LIST_SIZE_LIMIT_EXCEEDED),
                updateLargeFile(GENESIS, APP_PROPERTIES, PROPERTIES_SNAPSHOT));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> clearingNetworkPropertiesWithEmptyContentsIsSuccess() {
        return hapiTest(
                getFileContents(APP_PROPERTIES).saveToRegistry(PROPERTIES_SNAPSHOT),
                // Empty contents clear the file and parse as an empty list
                fileUpdate(APP_PROPERTIES)
                        .contents(new byte[0])
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .hasKnownStatus(SUCCESS),
                updateLargeFile(GENESIS, APP_PROPERTIES, PROPERTIES_SNAPSHOT));
    }
}

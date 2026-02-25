// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freeze;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeAbort;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.prepareUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.telemetryUpgrade;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FREEZE_START_TIME_MUST_BE_FUTURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FREEZE_UPDATE_FILE_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FREEZE_UPGRADE_IN_PROGRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_UPGRADE_HAS_BEEN_PREPARED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PREPARED_UPDATE_FILE_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UPDATE_FILE_ID_DOES_NOT_MATCH_PREPARED;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.suites.HapiSuite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;

public class UpgradeSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(UpgradeSuite.class);

    public static final String pragmatism = "Think of the children!";
    public static final String poeticUpgradeLoc = "testfiles/poeticUpgrade.zip";
    public static final String heavyPoeticUpgradeLoc = "testfiles/heavyPoeticUpgrade.zip";

    public static void main(String... args) {
        new UpgradeSuite().runSuiteSync();
    }

    private final byte[] poeticUpgrade;
    private final byte[] heavyPoeticUpgrade;
    private final byte[] poeticUpgradeHash;
    private final byte[] heavyPoeticUpgradeHash;
    private final byte[] notEvenASha384Hash = "abcdefgh".getBytes(StandardCharsets.UTF_8);

    public static final String standardUpdateFile = "150";
    public static final String standardTelemetryFile = "159";

    public UpgradeSuite() {
        try {
            final var sha384 = MessageDigest.getInstance("SHA-384");
            poeticUpgrade = Files.readAllBytes(Paths.get(poeticUpgradeLoc));
            poeticUpgradeHash = sha384.digest(poeticUpgrade);
            heavyPoeticUpgrade = Files.readAllBytes(Paths.get(heavyPoeticUpgradeLoc));
            heavyPoeticUpgradeHash = sha384.digest(heavyPoeticUpgrade);
            log.info("Poetic upgrade hash: {}", CommonUtils.hex(poeticUpgradeHash));
            log.info("Heavy poetic upgrade hash: {}", CommonUtils.hex(heavyPoeticUpgradeHash));
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("UpgradeSuite environment is unsuitable", e);
        }
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(
                precheckRejectsUnknownFreezeType(),
                freezeOnlyPrecheckRejectsInvalid(),
                freezeUpgradeValidationRejectsInvalid(),
                prepareUpgradeValidationRejectsInvalid(),
                telemetryUpgradeValidationRejectsInvalid(),
                canFreezeUpgradeWithPreparedUpgrade(),
                canTelemetryUpgradeWithValid(),
                freezeAbortIsIdempotent());
    }

    final Stream<DynamicTest> precheckRejectsUnknownFreezeType() {
        return hapiTest(freeze().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY));
    }

    final Stream<DynamicTest> freezeOnlyPrecheckRejectsInvalid() {
        return hapiTest(
                freezeOnly().withRejectedStartHr().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeOnly().withRejectedStartMin().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeOnly().withRejectedEndHr().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeOnly().withRejectedEndMin().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeOnly().startingIn(-60).minutes().hasPrecheck(FREEZE_START_TIME_MUST_BE_FUTURE));
    }

    final Stream<DynamicTest> freezeUpgradeValidationRejectsInvalid() {
        return hapiTest(
                freezeUpgrade().withRejectedStartHr().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeUpgrade().withRejectedStartMin().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeUpgrade().withRejectedEndHr().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeUpgrade().withRejectedEndMin().hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeUpgrade().startingIn(-60).minutes().hasPrecheck(FREEZE_START_TIME_MUST_BE_FUTURE),
                freezeUpgrade()
                        .startingIn(2)
                        .minutes()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(poeticUpgradeHash)
                        .hasKnownStatus(NO_UPGRADE_HAS_BEEN_PREPARED),
                freezeUpgrade()
                        .startingIn(2)
                        .minutes()
                        .havingHash(poeticUpgradeHash)
                        .hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY),
                freezeUpgrade()
                        .startingIn(2)
                        .minutes()
                        .withUpdateFile(standardUpdateFile)
                        .hasPrecheck(INVALID_FREEZE_TRANSACTION_BODY));
    }

    final Stream<DynamicTest> freezeAbortIsIdempotent() {
        return hapiTest(freezeAbort().hasKnownStatus(SUCCESS), freezeAbort().hasKnownStatus(SUCCESS));
    }

    final Stream<DynamicTest> prepareUpgradeValidationRejectsInvalid() {
        return hapiTest(
                fileUpdate(standardUpdateFile)
                        .signedBy(FREEZE_ADMIN)
                        .contents(pragmatism)
                        .payingWith(FREEZE_ADMIN),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FREEZE_ADMIN, ONE_HUNDRED_HBARS)),
                prepareUpgrade()
                        .withUpdateFile("149")
                        .havingHash(poeticUpgradeHash)
                        .hasPrecheck(FREEZE_UPDATE_FILE_DOES_NOT_EXIST),
                prepareUpgrade()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(notEvenASha384Hash)
                        .hasPrecheck(FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH),
                prepareUpgrade()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(poeticUpgradeHash)
                        .hasKnownStatus(FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH),
                fileUpdate(standardUpdateFile)
                        .signedBy(FREEZE_ADMIN)
                        .contents(pragmatism)
                        .payingWith(FREEZE_ADMIN),
                prepareUpgrade()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(poeticUpgradeHash)
                        .hasKnownStatus(FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH),
                fileUpdate(standardUpdateFile)
                        .signedBy(FREEZE_ADMIN)
                        .path(poeticUpgradeLoc)
                        .payingWith(FREEZE_ADMIN),
                getFileContents(standardUpdateFile).hasByteStringContents(ignore -> ByteString.copyFrom(poeticUpgrade)),
                prepareUpgrade().withUpdateFile(standardUpdateFile).havingHash(poeticUpgradeHash),
                prepareUpgrade()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(poeticUpgradeHash)
                        .hasKnownStatus(FREEZE_UPGRADE_IN_PROGRESS),
                freezeOnly().startingIn(60).minutes().hasKnownStatus(FREEZE_UPGRADE_IN_PROGRESS),
                telemetryUpgrade()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(poeticUpgradeHash)
                        .startingIn(60)
                        .minutes()
                        .hasKnownStatus(FREEZE_UPGRADE_IN_PROGRESS),
                fileUpdate(standardUpdateFile)
                        .signedBy(FREEZE_ADMIN)
                        .path(poeticUpgradeLoc)
                        .payingWith(FREEZE_ADMIN)
                        .hasKnownStatus(PREPARED_UPDATE_FILE_IS_IMMUTABLE),
                fileAppend(standardUpdateFile)
                        .signedBy(FREEZE_ADMIN)
                        .path(poeticUpgradeLoc)
                        .payingWith(FREEZE_ADMIN)
                        .hasKnownStatus(PREPARED_UPDATE_FILE_IS_IMMUTABLE),
                freezeAbort());
    }

    final Stream<DynamicTest> telemetryUpgradeValidationRejectsInvalid() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FREEZE_ADMIN, ONE_HUNDRED_HBARS)),
                telemetryUpgrade()
                        .withUpdateFile(standardTelemetryFile)
                        .havingHash(poeticUpgradeHash)
                        .startingIn(-60)
                        .minutes()
                        .hasPrecheck(FREEZE_START_TIME_MUST_BE_FUTURE),
                telemetryUpgrade()
                        .withUpdateFile("149")
                        .havingHash(poeticUpgradeHash)
                        .startingIn(3)
                        .minutes()
                        .hasPrecheck(FREEZE_UPDATE_FILE_DOES_NOT_EXIST),
                telemetryUpgrade()
                        .startingIn(3)
                        .minutes()
                        .withUpdateFile(standardTelemetryFile)
                        .havingHash(notEvenASha384Hash)
                        .hasPrecheck(FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH),
                fileUpdate(standardTelemetryFile)
                        .signedBy(FREEZE_ADMIN)
                        .contents(pragmatism)
                        .payingWith(FREEZE_ADMIN),
                telemetryUpgrade()
                        .startingIn(3)
                        .minutes()
                        .withUpdateFile(standardTelemetryFile)
                        .havingHash(poeticUpgradeHash)
                        .hasKnownStatus(FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH));
    }

    final Stream<DynamicTest> canFreezeUpgradeWithPreparedUpgrade() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FREEZE_ADMIN, ONE_HUNDRED_HBARS)),
                fileUpdate(standardUpdateFile)
                        .signedBy(FREEZE_ADMIN)
                        .path(poeticUpgradeLoc)
                        .payingWith(FREEZE_ADMIN),
                prepareUpgrade().withUpdateFile(standardUpdateFile).havingHash(poeticUpgradeHash),
                freezeUpgrade()
                        .startingIn(60)
                        .minutes()
                        .withUpdateFile(standardTelemetryFile)
                        .havingHash(poeticUpgradeHash)
                        .hasKnownStatus(UPDATE_FILE_ID_DOES_NOT_MATCH_PREPARED),
                freezeUpgrade()
                        .startingIn(60)
                        .minutes()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(heavyPoeticUpgradeHash)
                        .hasKnownStatus(UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED),
                freezeUpgrade()
                        .startingIn(60)
                        .minutes()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(poeticUpgradeHash),
                freezeAbort());
    }

    final Stream<DynamicTest> canTelemetryUpgradeWithValid() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FREEZE_ADMIN, ONE_HUNDRED_HBARS)),
                fileUpdate(standardUpdateFile)
                        .signedBy(FREEZE_ADMIN)
                        .path(heavyPoeticUpgradeLoc)
                        .payingWith(FREEZE_ADMIN),
                getFileContents(standardUpdateFile)
                        .hasByteStringContents(ignore -> ByteString.copyFrom(heavyPoeticUpgrade)),
                telemetryUpgrade()
                        .startingIn(60)
                        .minutes()
                        .withUpdateFile(standardUpdateFile)
                        .havingHash(heavyPoeticUpgradeHash));
    }
}

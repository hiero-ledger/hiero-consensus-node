// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_DETAILS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class PermissionSemanticsSpec {
    public static final String NEVER_TO_BE_USED = "neverToBeUsed";
    public static final String CIVILIAN = "civilian";
    public static final String ETERNAL = "eternal";
    public static final String WACL = "wacl";

    @HapiTest
    final Stream<DynamicTest> addressBookAdminExemptFromFeesGivenAuthorizedOps() {
        long amount = 100 * 100_000_000L;
        AtomicReference<byte[]> origContents = new AtomicReference<>();
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, amount))
                        .fee(ONE_HUNDRED_HBARS),
                fileCreate("tbu"),
                getFileContents(NODE_DETAILS).consumedBy(origContents::set),
                fileUpdate(NODE_DETAILS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .contents(ignore -> ByteString.copyFrom(origContents.get()))
                        .via("authorizedTxn"),
                fileUpdate("tbu")
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .contents("This is something new.")
                        .via("unauthorizedTxn"),
                getTxnRecord("unauthorizedTxn").hasPriority(recordWith().feeDifferentThan(0L)),
                getTxnRecord("authorizedTxn").hasPriority(recordWith().fee(0L)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> supportsImmutableFiles() {
        long extensionSecs = 666L;
        AtomicLong approxExpiry = new AtomicLong();

        return hapiTest(
                newKeyNamed(NEVER_TO_BE_USED).type(KeyFactory.KeyType.LIST),
                cryptoCreate(CIVILIAN),
                fileCreate(ETERNAL).payingWith(CIVILIAN).unmodifiable(),
                fileDelete(ETERNAL).payingWith(CIVILIAN).signedBy(CIVILIAN).hasKnownStatus(UNAUTHORIZED),
                fileAppend(ETERNAL)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .content("Ignored.")
                        .hasKnownStatus(UNAUTHORIZED),
                fileUpdate(ETERNAL)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .contents("Ignored.")
                        .hasKnownStatus(UNAUTHORIZED),
                fileUpdate(ETERNAL)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, NEVER_TO_BE_USED)
                        .wacl(NEVER_TO_BE_USED)
                        .hasKnownStatus(UNAUTHORIZED),
                withOpContext((spec, opLog) ->
                        approxExpiry.set(spec.registry().getTimestamp(ETERNAL).getSeconds())),
                fileUpdate(ETERNAL).payingWith(CIVILIAN).signedBy(CIVILIAN).extendingExpiryBy(extensionSecs),
                getFileInfo(ETERNAL)
                        .isUnmodifiable()
                        .hasExpiryPassing(l -> Math.abs(l - approxExpiry.get() - extensionSecs) < 5));
    }

    @HapiTest
    final Stream<DynamicTest> allowsDeleteWithOneTopLevelSig() {
        KeyShape wacl = KeyShape.listOf(KeyShape.SIMPLE, KeyShape.listOf(2));

        var deleteSig = wacl.signedWith(sigs(ON, sigs(OFF, OFF)));
        var failedDeleteSig = wacl.signedWith(sigs(OFF, sigs(OFF, ON)));

        var updateSig = wacl.signedWith(sigs(ON, sigs(ON, ON)));
        var failedUpdateSig = wacl.signedWith(sigs(ON, sigs(OFF, ON)));

        return hapiTest(
                newKeyNamed(WACL).shape(wacl),
                fileCreate("tbd").key(WACL),
                fileUpdate("tbd")
                        .contents("Some more contents!")
                        .signedBy(GENESIS, WACL)
                        .sigControl(ControlForKey.forKey(WACL, failedUpdateSig))
                        .hasKnownStatus(INVALID_SIGNATURE),
                fileUpdate("tbd")
                        .contents("Some new contents!")
                        .signedBy(GENESIS, WACL)
                        .sigControl(ControlForKey.forKey(WACL, updateSig)),
                fileDelete("tbd")
                        .signedBy(GENESIS, WACL)
                        .sigControl(ControlForKey.forKey(WACL, failedDeleteSig))
                        .hasKnownStatus(INVALID_SIGNATURE),
                fileDelete("tbd").signedBy(GENESIS, WACL).sigControl(ControlForKey.forKey(WACL, deleteSig)));
    }
}

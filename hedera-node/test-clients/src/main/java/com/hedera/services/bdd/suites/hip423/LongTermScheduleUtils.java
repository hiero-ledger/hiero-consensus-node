// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip423;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUppercase;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildUpgradeZipFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.prepareUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateSpecialFile;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.FAKE_ASSETS_LOC;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileAppendsPerBurst;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;

import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

public final class LongTermScheduleUtils {

    public static final String SENDER = "sender";
    public static final String SENDER_KEY = "senderKey";
    public static final String SENDER_TXN = "senderTxn";
    public static final String NEW_SENDER_KEY = "newSenderKey";
    public static final String RECEIVER = "receiver";
    public static final String CREATE_TXN = "createTxn";
    public static final String TRIGGERING_TXN = "triggeringTxn";
    public static final String PAYER = "payer";
    public static final String ADMIN = "admin";
    public static final String EXTRA_KEY = "extraKey";
    public static final String SHARED_KEY = "sharedKey";
    public static final String BASIC_XFER = "basicXfer";
    public static final String TWO_SIG_XFER = "twoSigXfer";
    public static final String DEFERRED_XFER = "deferredXfer";
    public static final String THREE_SIG_XFER = "threeSigXfer";
    public static final String TOKEN_A = "tokenA";
    public static final String CREATION = "creation";
    public static final String BEFORE = "before";
    public static final String DEFERRED_FALL = "deferredFall";
    public static final String DEFERRED_CREATION = "deferredCreation";
    public static final String PAYING_ACCOUNT_2 = "payingAccount2";
    public static final String SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED = "Scheduled transaction must not succeed";
    public static final String SCHEDULE_CREATE_FEE = "scheduleCreateFee";
    public static final String SENDER_1 = "sender1";
    public static final String SENDER_2 = "sender2";
    public static final String SENDER_3 = "sender3";
    public static final String SIMPLE_UPDATE = "SimpleUpdate";
    public static final String SUCCESS_TXN = "successTxn";
    public static final String TRANSACTION_NOT_SCHEDULED = "Transaction not scheduled!";
    public static final String VALID_SCHEDULE = "validSchedule";
    public static final String WEIRDLY_POPULAR_KEY = "weirdlyPopularKey";
    public static final String WRONG_CONSENSUS_TIMESTAMP = "Wrong consensus timestamp!";
    public static final String WRONG_RECORD_ACCOUNT_ID = "Wrong record account ID!";
    public static final String WRONG_SCHEDULE_ID = "Wrong schedule ID!";
    public static final String WRONG_TRANSACTION_VALID_START = "Wrong transaction valid start!";
    public static final String WRONG_TRANSFER_LIST = "Wrong transfer list!";

    public static final byte[] ORIG_FILE = "SOMETHING".getBytes();

    private LongTermScheduleUtils() {}

    public static boolean transferListCheck(
            HapiGetTxnRecord triggered,
            AccountID givingAccountID,
            AccountID receivingAccountID,
            AccountID payingAccountID,
            Long amount) {
        AccountAmount givingAmount = AccountAmount.newBuilder()
                .setAccountID(givingAccountID)
                .setAmount(-amount)
                .build();

        AccountAmount receivingAmount = AccountAmount.newBuilder()
                .setAccountID(receivingAccountID)
                .setAmount(amount)
                .build();

        var accountAmountList = triggered.getResponseRecord().getTransferList().getAccountAmountsList();
        System.out.println("accountAmountList: " + accountAmountList);
        boolean payerHasPaid =
                accountAmountList.stream().anyMatch(a -> a.getAccountID().equals(payingAccountID) && a.getAmount() < 0);
        System.out.println("payerHasPaid: " + payerHasPaid);
        boolean amountHasBeenTransferred =
                accountAmountList.contains(givingAmount) && accountAmountList.contains(receivingAmount);
        System.out.println("amountHasBeenTransferred: " + amountHasBeenTransferred);

        return amountHasBeenTransferred && payerHasPaid;
    }

    public static SpecOperation[] scheduleFakeUpgrade(
            @NonNull final String payer, final long lifetime, @NonNull final String via) {
        final var operations = List.of(
                buildUpgradeZipFrom(FAKE_ASSETS_LOC),
                // Upload it to file 0.0.150; need sourcing() here because the operation reads contents eagerly
                sourcing(() -> updateSpecialFile(
                        GENESIS,
                        DEFAULT_UPGRADE_FILE_ID,
                        FAKE_UPGRADE_ZIP_LOC,
                        TxnUtils.BYTES_4K,
                        upgradeFileAppendsPerBurst())),
                // Issue PREPARE_UPGRADE; need sourcing() here because we want to hash only after creating the ZIP
                sourcing(() -> scheduleCreate(
                                VALID_SCHEDULE,
                                prepareUpgrade()
                                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC)))
                        .withEntityMemo(randomUppercase(100))
                        .designatingPayer(GENESIS)
                        .payingWith(payer)
                        .recordingScheduledTxn()
                        .waitForExpiry()
                        .expiringIn(lifetime)
                        .via(via)));
        return operations.toArray(SpecOperation[]::new);
    }

    public static SpecOperation[] triggerSchedule(String schedule, long waitForSeconds) {
        return flattened(
                sleepForSeconds(waitForSeconds),
                cryptoCreate("foo").via(TRIGGERING_TXN),
                // Pause execution for 1 second to allow time for the scheduled transaction to be
                // processed and removed from the state
                sleepForSeconds(1),
                getScheduleInfo(schedule).hasCostAnswerPrecheck(INVALID_SCHEDULE_ID));
    }

    public static SpecOperation[] triggerSchedule(String schedule) {
        return triggerSchedule(schedule, 6);
    }
}

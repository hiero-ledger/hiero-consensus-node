// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody.Builder;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;

public final class ScheduleUtils {
    static final String ADMIN = "admin";
    static final String A_SCHEDULE = "validSchedule";
    static final String A_TOKEN = "token";
    static final String BASIC_XFER = "basicXfer";
    static final String BEGIN = "begin";
    static final String CONTINUE = "continue";
    static final String COPYCAT = "copycat";
    static final String CREATE_TXN = "createTx";
    static final String CREATION = "creation";
    static final String DEFERRED_XFER = "deferredXfer";
    static final String DESIGNATING_PAYER = "111.222.333";
    static final String ENTITY_MEMO = "This was Mr. Bleaney's room. He stayed";
    static final String FAILED_XFER = "failedXfer";
    static final String FAILING_TXN = "failingTxn";
    static final String FIRST_PAYER = "firstPayer";
    static final String INSOLVENT_PAYER = "insolventPayer";
    static final String LUCKY_RECEIVER = "luckyReceiver";
    static final String NEVER_TO_BE = "neverToBe";
    static final String NEW_SENDER_KEY = "newSKey";
    static final String ONLY_BODY = "onlyBody";
    static final String ONLY_BODY_AND_ADMIN_KEY = "onlyBodyAndAdminKey";
    static final String ONLY_BODY_AND_MEMO = "onlyBodyAndMemo";
    static final String ONLY_BODY_AND_PAYER = "onlyBodyAndPayer";
    static final String ORIGINAL = "original";
    public static final String OTHER_PAYER = "otherPayer";
    static final String PAYER = "payer";
    static final String PAYING_ACCOUNT = "payingAccount";
    static final String PAYING_ACCOUNT_2 = "payingAccount2";
    public static final String PAYING_SENDER = "payingSender";
    static final String RANDOM_KEY = "randomKey";
    static final String RANDOM_MSG =
            "Little did they care who danced between / And little she by whom her dance was seen";
    public static final String RECEIVER = "receiver";
    static final String RECEIVER_A = "receiverA";
    static final String RECEIVER_B = "receiverB";
    static final String RECEIVER_C = "receiverC";
    static final String SCHEDULE = "schedule";
    static final String SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED = "Scheduled transaction must not succeed";
    static final String SCHEDULED_TRANSACTION_MUST_SUCCEED = "Scheduled transaction must succeed";
    static final String SCHEDULE_CREATE_FEE = "scheduleCreateFee";
    static final String SCHEDULE_PAYER = "schedulePayer";
    static final String SCHEDULING_WHITELIST = "scheduling.whitelist";
    static final String SECOND_PAYER = "secondPayer";
    static final String SENDER = "sender";
    static final String SENDER_1 = "sender1";
    static final String SENDER_2 = "sender2";
    static final String SENDER_3 = "sender3";
    static final String SHARED_KEY = "sharedKey";
    static final String SIGN_TX = "signTx";
    static final String SIGN_TXN = "signTx";
    public static final String SIMPLE_UPDATE = "SimpleUpdate";
    static final String SIMPLE_XFER_SCHEDULE = "simpleXferSchedule";
    static final String SOMEBODY = "somebody";
    static final String SUCCESS_TXN = "successTxn";
    static final String SUPPLY_KEY = "supplyKey";
    static final String TOKEN_A = "tokenA";
    static final String TRANSACTION_NOT_SCHEDULED = "Transaction not scheduled!";
    static final String TREASURY = "treasury";
    static final String TRIGGER = "trigger";
    static final String TWO_SIG_XFER = "twoSigXfer";
    static final String UNWILLING_PAYER = "unwillingPayer";
    static final String VALID_SCHEDULE = "validSchedule";
    static final String VALID_SCHEDULED_TXN = "validScheduledTxn";
    static final String WEIRDLY_POPULAR_KEY = "weirdlyPopularKey";
    static final String WRONG_CONSENSUS_TIMESTAMP = "Wrong consensus timestamp!";
    static final String WRONG_RECORD_ACCOUNT_ID = "Wrong record account ID!";
    static final String WRONG_SCHEDULE_ID = "Wrong schedule ID!";
    static final String WRONG_TRANSACTION_VALID_START = "Wrong transaction valid start!";
    static final String WRONG_TRANSFER_LIST = "Wrong transfer list!";

    static final byte[] ORIG_FILE = "SOMETHING".getBytes();

    private ScheduleUtils() {}

    // public because this is used in HapiScheduleCreate
    public static SchedulableTransactionBody fromOrdinary(TransactionBody txn) {
        Builder scheduleBuilder = SchedulableTransactionBody.newBuilder();
        scheduleBuilder.setTransactionFee(txn.getTransactionFee());
        scheduleBuilder.setMemo(txn.getMemo());

        if (txn.hasContractCall()) {
            scheduleBuilder.setContractCall(txn.getContractCall());
        } else if (txn.hasContractCreateInstance()) {
            scheduleBuilder.setContractCreateInstance(txn.getContractCreateInstance());
        } else if (txn.hasContractUpdateInstance()) {
            scheduleBuilder.setContractUpdateInstance(txn.getContractUpdateInstance());
        } else if (txn.hasContractDeleteInstance()) {
            scheduleBuilder.setContractDeleteInstance(txn.getContractDeleteInstance());
        } else if (txn.hasCryptoCreateAccount()) {
            scheduleBuilder.setCryptoCreateAccount(txn.getCryptoCreateAccount());
        } else if (txn.hasCryptoDelete()) {
            scheduleBuilder.setCryptoDelete(txn.getCryptoDelete());
        } else if (txn.hasCryptoTransfer()) {
            scheduleBuilder.setCryptoTransfer(txn.getCryptoTransfer());
        } else if (txn.hasCryptoUpdateAccount()) {
            scheduleBuilder.setCryptoUpdateAccount(txn.getCryptoUpdateAccount());
        } else if (txn.hasFileAppend()) {
            scheduleBuilder.setFileAppend(txn.getFileAppend());
        } else if (txn.hasFileCreate()) {
            scheduleBuilder.setFileCreate(txn.getFileCreate());
        } else if (txn.hasFileDelete()) {
            scheduleBuilder.setFileDelete(txn.getFileDelete());
        } else if (txn.hasFileUpdate()) {
            scheduleBuilder.setFileUpdate(txn.getFileUpdate());
        } else if (txn.hasSystemDelete()) {
            scheduleBuilder.setSystemDelete(txn.getSystemDelete());
        } else if (txn.hasSystemUndelete()) {
            scheduleBuilder.setSystemUndelete(txn.getSystemUndelete());
        } else if (txn.hasFreeze()) {
            scheduleBuilder.setFreeze(txn.getFreeze());
        } else if (txn.hasConsensusCreateTopic()) {
            scheduleBuilder.setConsensusCreateTopic(txn.getConsensusCreateTopic());
        } else if (txn.hasConsensusUpdateTopic()) {
            scheduleBuilder.setConsensusUpdateTopic(txn.getConsensusUpdateTopic());
        } else if (txn.hasConsensusDeleteTopic()) {
            scheduleBuilder.setConsensusDeleteTopic(txn.getConsensusDeleteTopic());
        } else if (txn.hasConsensusSubmitMessage()) {
            scheduleBuilder.setConsensusSubmitMessage(txn.getConsensusSubmitMessage());
        } else if (txn.hasTokenCreation()) {
            scheduleBuilder.setTokenCreation(txn.getTokenCreation());
        } else if (txn.hasTokenFreeze()) {
            scheduleBuilder.setTokenFreeze(txn.getTokenFreeze());
        } else if (txn.hasTokenUnfreeze()) {
            scheduleBuilder.setTokenUnfreeze(txn.getTokenUnfreeze());
        } else if (txn.hasTokenGrantKyc()) {
            scheduleBuilder.setTokenGrantKyc(txn.getTokenGrantKyc());
        } else if (txn.hasTokenRevokeKyc()) {
            scheduleBuilder.setTokenRevokeKyc(txn.getTokenRevokeKyc());
        } else if (txn.hasTokenDeletion()) {
            scheduleBuilder.setTokenDeletion(txn.getTokenDeletion());
        } else if (txn.hasTokenUpdate()) {
            scheduleBuilder.setTokenUpdate(txn.getTokenUpdate());
        } else if (txn.hasTokenMint()) {
            scheduleBuilder.setTokenMint(txn.getTokenMint());
        } else if (txn.hasTokenBurn()) {
            scheduleBuilder.setTokenBurn(txn.getTokenBurn());
        } else if (txn.hasTokenWipe()) {
            scheduleBuilder.setTokenWipe(txn.getTokenWipe());
        } else if (txn.hasTokenAssociate()) {
            scheduleBuilder.setTokenAssociate(txn.getTokenAssociate());
        } else if (txn.hasTokenDissociate()) {
            scheduleBuilder.setTokenDissociate(txn.getTokenDissociate());
        } else if (txn.hasScheduleDelete()) {
            scheduleBuilder.setScheduleDelete(txn.getScheduleDelete());
        } else if (txn.hasCryptoApproveAllowance()) {
            scheduleBuilder.setCryptoApproveAllowance(txn.getCryptoApproveAllowance());
        } else if (txn.hasNodeCreate()) {
            scheduleBuilder.setNodeCreate(txn.getNodeCreate());
        } else if (txn.hasNodeDelete()) {
            scheduleBuilder.setNodeDelete(txn.getNodeDelete());
        } else if (txn.hasNodeUpdate()) {
            scheduleBuilder.setNodeUpdate(txn.getNodeUpdate());
        }
        return scheduleBuilder.build();
    }

    static boolean transferListCheck(
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

    static TransactionID scheduledVersionOf(TransactionID txnId) {
        return txnId.toBuilder().setScheduled(true).build();
    }
}

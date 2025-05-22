// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.explicitBytesOf;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.approxChangeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.including;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHip32Auto;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_ACCOUNT_SAME_AS_DELETE_ACCOUNT;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.ContextRequirement;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class CryptoDeleteSuite {
    private static final long TOKEN_INITIAL_SUPPLY = 500;
    private static final String TRANSFER_ACCOUNT = "transferAccount";
    public static final String TREASURY = "treasury";
    private static final String ACCOUNT_TO_BE_DELETED = "toBeDeleted";

    @HapiTest
    final Stream<DynamicTest> accountIdVariantsTreatedAsExpected() {
        return hapiTest(
                cryptoCreate(TRANSFER_ACCOUNT),
                cryptoCreate(ACCOUNT_TO_BE_DELETED),
                submitModified(withSuccessivelyVariedBodyIds(), () -> cryptoDelete(ACCOUNT_TO_BE_DELETED)
                        .transfer(TRANSFER_ACCOUNT)));
    }

    @LeakyHapiTest(requirement = ContextRequirement.SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> deletedAccountCannotBePayer() {
        final var submittingNodeAccount = "3";
        final var beneficiaryAccount = "beneficiaryAccountForDeletedAccount";
        final var submittingNodePreTransfer = "submittingNodePreTransfer";
        final var submittingNodeAfterBalanceLoad = "submittingNodeAfterBalanceLoad";
        return hapiTest(
                cryptoCreate(ACCOUNT_TO_BE_DELETED),
                cryptoCreate(beneficiaryAccount).balance(0L),
                balanceSnapshot(submittingNodePreTransfer, submittingNodeAccount),
                cryptoTransfer(tinyBarsFromTo(GENESIS, submittingNodeAccount, 1000000000)),
                balanceSnapshot(submittingNodeAfterBalanceLoad, submittingNodeAccount),
                cryptoDelete(ACCOUNT_TO_BE_DELETED).transfer(beneficiaryAccount).deferStatusResolution(),
                cryptoTransfer(tinyBarsFromTo(beneficiaryAccount, GENESIS, 1))
                        .payingWith(ACCOUNT_TO_BE_DELETED)
                        .hasKnownStatus(PAYER_ACCOUNT_DELETED),
                // since the account is already deleted, we have less signatures to verify
                getAccountBalance(submittingNodeAccount)
                        .hasTinyBars(approxChangeFromSnapshot(submittingNodeAfterBalanceLoad, -30000, 15000))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> canQueryForRecordsWithDeletedPayers() {
        final var stillQueryableTxn = "stillQueryableTxn";
        return hapiTest(
                cryptoCreate(ACCOUNT_TO_BE_DELETED),
                cryptoTransfer(tinyBarsFromTo(ACCOUNT_TO_BE_DELETED, FUNDING, 1))
                        .payingWith(ACCOUNT_TO_BE_DELETED)
                        .via(stillQueryableTxn),
                cryptoDelete(ACCOUNT_TO_BE_DELETED),
                getTxnRecord(stillQueryableTxn).hasPriority(recordWith().payer(ACCOUNT_TO_BE_DELETED)));
    }

    @HapiTest
    final Stream<DynamicTest> fundsTransferOnDelete() {
        long B = HapiSpecSetup.getDefaultInstance().defaultBalance();

        return hapiTest(
                cryptoCreate(ACCOUNT_TO_BE_DELETED),
                cryptoCreate(TRANSFER_ACCOUNT).balance(0L),
                cryptoDelete(ACCOUNT_TO_BE_DELETED).transfer(TRANSFER_ACCOUNT).via("deleteTxn"),
                getAccountInfo(TRANSFER_ACCOUNT).has(accountWith().balance(B)),
                getTxnRecord("deleteTxn")
                        .hasPriority(recordWith()
                                .transfers(including(tinyBarsFromTo(ACCOUNT_TO_BE_DELETED, TRANSFER_ACCOUNT, B)))));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteAccountsWithNonzeroTokenBalances() {
        return hapiTest(
                newKeyNamed("admin"),
                cryptoCreate(ACCOUNT_TO_BE_DELETED).maxAutomaticTokenAssociations(1),
                cryptoCreate(TRANSFER_ACCOUNT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate("misc")
                        .adminKey("admin")
                        .initialSupply(TOKEN_INITIAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(ACCOUNT_TO_BE_DELETED, "misc"),
                cryptoTransfer(moving(TOKEN_INITIAL_SUPPLY, "misc").between(TOKEN_TREASURY, ACCOUNT_TO_BE_DELETED)),
                cryptoDelete(ACCOUNT_TO_BE_DELETED)
                        .transfer(TRANSFER_ACCOUNT)
                        .hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES),
                cryptoTransfer(moving(TOKEN_INITIAL_SUPPLY, "misc").between(ACCOUNT_TO_BE_DELETED, TOKEN_TREASURY)),
                tokenDissociate(ACCOUNT_TO_BE_DELETED, "misc"),
                cryptoTransfer(moving(TOKEN_INITIAL_SUPPLY, "misc").between(TOKEN_TREASURY, ACCOUNT_TO_BE_DELETED)),
                cryptoDelete(ACCOUNT_TO_BE_DELETED)
                        .transfer(TRANSFER_ACCOUNT)
                        .hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES),
                cryptoDelete(TOKEN_TREASURY).hasKnownStatus(ACCOUNT_IS_TREASURY),
                cryptoTransfer(moving(TOKEN_INITIAL_SUPPLY, "misc").between(ACCOUNT_TO_BE_DELETED, TOKEN_TREASURY)),
                cryptoDelete(ACCOUNT_TO_BE_DELETED),
                cryptoDelete(ACCOUNT_TO_BE_DELETED).hasKnownStatus(ACCOUNT_DELETED),
                tokenDelete("misc"),
                tokenDissociate(TOKEN_TREASURY, "misc"),
                cryptoDelete(TOKEN_TREASURY));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteAlreadyDeletedAccount() {
        return hapiTest(
                cryptoCreate(ACCOUNT_TO_BE_DELETED),
                cryptoCreate(TRANSFER_ACCOUNT),
                cryptoDelete(ACCOUNT_TO_BE_DELETED).transfer(TRANSFER_ACCOUNT).hasKnownStatus(SUCCESS),
                cryptoDelete(ACCOUNT_TO_BE_DELETED).transfer(TRANSFER_ACCOUNT).hasKnownStatus(ACCOUNT_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> mustSpecifyTargetId() {
        return hapiTest(
                cryptoDelete("0.0.0").sansTargetId().signedBy(DEFAULT_PAYER).hasPrecheck(ACCOUNT_ID_DOES_NOT_EXIST));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteAccountWithSameBeneficiary() {
        return hapiTest(
                cryptoCreate(ACCOUNT_TO_BE_DELETED),
                cryptoDelete(ACCOUNT_TO_BE_DELETED)
                        .transfer(ACCOUNT_TO_BE_DELETED)
                        .hasPrecheck(TRANSFER_ACCOUNT_SAME_AS_DELETE_ACCOUNT));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteTreasuryAccount() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(TRANSFER_ACCOUNT),
                tokenCreate("toBeTransferred")
                        .initialSupply(TOKEN_INITIAL_SUPPLY)
                        .treasury(TREASURY),
                cryptoDelete(TREASURY).transfer(TRANSFER_ACCOUNT).hasKnownStatus(ACCOUNT_IS_TREASURY));
    }

    @HapiTest
    final Stream<DynamicTest> deleteEcdsaKeyAliasWorked() {
        return hapiTest(
                createHip32Auto(1, SECP_256K1_SHAPE, i -> ACCOUNT_TO_BE_DELETED),
                balanceSnapshot("before", ACCOUNT_TO_BE_DELETED),
                withAddressOfKey(
                        ACCOUNT_TO_BE_DELETED,
                        address -> cryptoTransfer((spec, builder) -> builder.setTransfers(TransferList.newBuilder()
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(asAccount(spec, 2))
                                        .setAmount(-ONE_HUNDRED_HBARS)
                                        .build())
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAccountID(asAccount(spec, ByteString.copyFrom(explicitBytesOf(address))))
                                        .setAmount(ONE_HUNDRED_HBARS)
                                        .build())
                                .build()))),
                getAccountBalance(ACCOUNT_TO_BE_DELETED).hasTinyBars(changeFromSnapshot("before", ONE_HUNDRED_HBARS)),
                cryptoDelete(ACCOUNT_TO_BE_DELETED),
                withAddressOfKey(ACCOUNT_TO_BE_DELETED, address -> getAliasedAccountInfo(
                                ByteString.copyFrom(explicitBytesOf(address)))
                        .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)),
                withAddressOfKey(ACCOUNT_TO_BE_DELETED, address -> cryptoTransfer(
                                (spec, builder) -> builder.setTransfers(TransferList.newBuilder()
                                        .addAccountAmounts(AccountAmount.newBuilder()
                                                .setAccountID(asAccount(spec, 2))
                                                .setAmount(-ONE_HUNDRED_HBARS)
                                                .build())
                                        .addAccountAmounts(AccountAmount.newBuilder()
                                                .setAccountID(
                                                        asAccount(spec, ByteString.copyFrom(explicitBytesOf(address))))
                                                .setAmount(ONE_HUNDRED_HBARS)
                                                .build())
                                        .build()))
                        .via("shouldBeAutoCreation")),
                getTxnRecord("shouldBeAutoCreation")
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(1)
                        .logged());
    }
}

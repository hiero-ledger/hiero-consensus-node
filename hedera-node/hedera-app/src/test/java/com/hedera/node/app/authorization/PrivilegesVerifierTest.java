// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.authorization;

import static com.hedera.node.app.hapi.utils.CommonUtils.functionOf;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.addressbook.NodeCreateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.file.FileAppendTransactionBody;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.file.SystemDeleteTransactionBody;
import com.hedera.hapi.node.file.SystemUndeleteTransactionBody;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.UncheckedSubmitBody;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// This test may look a little weird without context. The original test in mono is very extensive. To ensure that
// we don't break anything, I copied the test from mono and hacked it a little to run it with the new code.
// (It was a good thing. I discovered two bugs...) :)
class PrivilegesVerifierTest {

    private Wrapper subject;

    private record TestCase(
            com.hedera.hapi.node.base.AccountID payerId,
            com.hedera.hapi.node.base.HederaFunctionality function,
            com.hedera.hapi.node.transaction.TransactionBody txn) {
        public TestCase withPayerId(com.hedera.hapi.node.base.AccountID newPayerId) {
            return new TestCase(newPayerId, function, txn);
        }
    }

    private static class Wrapper {
        private final PrivilegesVerifier delegate;

        Wrapper(final ConfigProvider configProvider) {
            delegate = new PrivilegesVerifier(configProvider);
        }

        SystemOpAuthorization authForTestCase(final TestCase testCase) {
            final var pbjResult = delegate.hasPrivileges(testCase.payerId, testCase.function, testCase.txn);
            return SystemOpAuthorization.valueOf(pbjResult.name());
        }

        boolean canPerformNonCryptoUpdate(final long accountNum, final long fileNum) {
            final var accountID = com.hedera.hapi.node.base.AccountID.newBuilder()
                    .accountNum(accountNum)
                    .build();
            final var fileID = com.hedera.hapi.node.base.FileID.newBuilder()
                    .fileNum(fileNum)
                    .build();
            final var fileUpdateTxBody = com.hedera.hapi.node.transaction.TransactionBody.newBuilder()
                    .fileUpdate(com.hedera.hapi.node.file.FileUpdateTransactionBody.newBuilder()
                            .fileID(fileID)
                            .build())
                    .build();
            final var fileAppendTxBody = com.hedera.hapi.node.transaction.TransactionBody.newBuilder()
                    .fileAppend(com.hedera.hapi.node.file.FileAppendTransactionBody.newBuilder()
                            .fileID(fileID)
                            .build())
                    .build();
            return delegate.hasPrivileges(accountID, HederaFunctionality.FILE_UPDATE, fileUpdateTxBody)
                            == SystemPrivilege.AUTHORIZED
                    && delegate.hasPrivileges(accountID, HederaFunctionality.FILE_APPEND, fileAppendTxBody)
                            == SystemPrivilege.AUTHORIZED;
        }
    }

    @BeforeEach
    void setUp() {
        final var configuration = HederaTestConfigBuilder.createConfig();
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(configuration, 1L);

        subject = new Wrapper(configProvider);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void testConstructorWithIllegalParameters() {
        assertThatThrownBy(() -> new PrivilegesVerifier(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void treasuryCanUpdateAllNonAccountEntities() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(2, 101));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 102));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 111));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 112));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 121));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 122));
        assertTrue(subject.canPerformNonCryptoUpdate(2, 123));
        for (var num = 150; num <= 159; num++) {
            assertTrue(subject.canPerformNonCryptoUpdate(2, num));
        }
    }

    @Test
    void sysAdminCanUpdateKnownSystemFiles() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(50, 101));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 102));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 111));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 112));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 121));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 122));
        assertTrue(subject.canPerformNonCryptoUpdate(50, 123));
        for (var num = 150; num <= 159; num++) {
            assertTrue(subject.canPerformNonCryptoUpdate(50, num));
        }
    }

    @Test
    void softwareUpdateAdminCanUpdateExpected() {
        // expect:
        assertFalse(subject.canPerformNonCryptoUpdate(54, 101));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 102));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 121));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 122));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 123));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(54, 112));
        for (var num = 150; num <= 159; num++) {
            assertTrue(subject.canPerformNonCryptoUpdate(54, num));
        }
    }

    @Test
    void addressBookAdminCanUpdateExpected() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(55, 101));
        assertTrue(subject.canPerformNonCryptoUpdate(55, 102));
        assertTrue(subject.canPerformNonCryptoUpdate(55, 121));
        assertTrue(subject.canPerformNonCryptoUpdate(55, 122));
        assertTrue(subject.canPerformNonCryptoUpdate(55, 123));
        assertFalse(subject.canPerformNonCryptoUpdate(55, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(55, 112));
        for (var num = 150; num <= 159; num++) {
            assertFalse(subject.canPerformNonCryptoUpdate(55, num));
        }
    }

    @Test
    void feeSchedulesAdminCanUpdateExpected() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(56, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 101));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 102));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 121));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 122));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 123));
        assertFalse(subject.canPerformNonCryptoUpdate(56, 112));
        for (var num = 150; num <= 159; num++) {
            assertFalse(subject.canPerformNonCryptoUpdate(56, num));
        }
    }

    @Test
    void exchangeRatesAdminCanUpdateExpected() {
        // expect:
        assertTrue(subject.canPerformNonCryptoUpdate(57, 121));
        assertTrue(subject.canPerformNonCryptoUpdate(57, 122));
        assertTrue(subject.canPerformNonCryptoUpdate(57, 123));
        assertTrue(subject.canPerformNonCryptoUpdate(57, 112));
        assertFalse(subject.canPerformNonCryptoUpdate(57, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(57, 101));
        assertFalse(subject.canPerformNonCryptoUpdate(57, 102));
        assertFalse(subject.canPerformNonCryptoUpdate(57, 150));
        for (var num = 150; num <= 159; num++) {
            assertFalse(subject.canPerformNonCryptoUpdate(57, num));
        }
    }

    @Test
    void freezeAdminCanUpdateExpected() {
        // expect:
        assertFalse(subject.canPerformNonCryptoUpdate(58, 121));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 122));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 123));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 112));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 111));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 101));
        assertFalse(subject.canPerformNonCryptoUpdate(58, 102));
        for (var num = 150; num <= 159; num++) {
            assertFalse(subject.canPerformNonCryptoUpdate(58, num));
        }
    }

    @Test
    void uncheckedSubmitRejectsUnauthorized() throws ParseException {
        // given:
        var txn = civilianTxn()
                .uncheckedSubmit(
                        UncheckedSubmitBody.newBuilder().transactionBytes(Bytes.wrap("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void sysAdminCanSubmitUnchecked() throws ParseException {
        // given:
        var txn = sysAdminTxn()
                .uncheckedSubmit(
                        UncheckedSubmitBody.newBuilder().transactionBytes(Bytes.wrap("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void treasuryCanSubmitUnchecked() throws ParseException {
        // given:
        var txn = treasuryTxn()
                .uncheckedSubmit(
                        UncheckedSubmitBody.newBuilder().transactionBytes(Bytes.wrap("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesAuthorized() throws ParseException {
        // given:
        var txn = treasuryTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(75)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesUnnecessaryForSystem() throws ParseException {
        // given:
        var txn = civilianTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(75)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesUnnecessaryForNonSystem() throws ParseException {
        // given:
        var txn = civilianTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(1001)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesAuthorizedForTreasury() throws ParseException {
        // given:
        var selfUpdateTxn = treasuryTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(2)));
        var otherUpdateTxn = treasuryTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(50)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(selfUpdateTxn)));
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(otherUpdateTxn)));
    }

    @Test
    void cryptoUpdateRecognizesUnauthorized() throws ParseException {
        // given:
        var civilianTxn = civilianTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(2)));
        var sysAdminTxn = sysAdminTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(2)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(civilianTxn)));
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(sysAdminTxn)));
    }

    @Test
    void fileUpdateRecognizesUnauthorized() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(file(111)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesUnauthorized() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .fileAppend(FileAppendTransactionBody.newBuilder().fileID(file(111)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesAuthorized() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .fileAppend(FileAppendTransactionBody.newBuilder().fileID(file(112)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void treasuryCanFreeze() throws ParseException {
        // given:
        var txn = treasuryTxn().freeze(FreezeTransactionBody.DEFAULT);
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void sysAdminCanFreeze() throws ParseException {
        // given:
        var txn = sysAdminTxn().freeze(FreezeTransactionBody.DEFAULT);
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void freezeAdminCanFreeze() throws ParseException {
        // given:
        var txn = freezeAdminTxn().freeze(FreezeTransactionBody.DEFAULT);
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void treasuryCanCreateNode() throws ParseException {
        // given:
        var txn = treasuryTxn().nodeCreate(NodeCreateTransactionBody.DEFAULT);
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void sysAdminnCanCreateNode() throws ParseException {
        // given:
        var txn = sysAdminTxn().nodeCreate(NodeCreateTransactionBody.DEFAULT);
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void addressBookAdminCanCreateNode() throws ParseException {
        // given:
        var txn = addressBookAdminTxn().nodeCreate(NodeCreateTransactionBody.DEFAULT);
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void randomAdminCannotFreeze() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn().freeze(FreezeTransactionBody.DEFAULT);
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesImpermissibleContractDel() throws ParseException {
        // given:
        var txn = treasuryTxn()
                .systemDelete(SystemDeleteTransactionBody.newBuilder().contractID(contract(123)));
        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesImpermissibleContractUndel() throws ParseException {
        // given:
        var txn = treasuryTxn()
                .systemUndelete(SystemUndeleteTransactionBody.newBuilder().contractID(contract(123)));
        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesUnauthorizedContractUndel() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .systemUndelete(SystemUndeleteTransactionBody.newBuilder().contractID(contract(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesAuthorizedContractUndel() throws ParseException {
        // given:
        var txn = sysUndeleteTxn()
                .systemUndelete(SystemUndeleteTransactionBody.newBuilder().contractID(contract(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesAuthorizedFileUndel() throws ParseException {
        // given:
        var txn = sysUndeleteTxn()
                .systemUndelete(SystemUndeleteTransactionBody.newBuilder().fileID(file(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesUnauthorizedFileUndel() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .systemUndelete(SystemUndeleteTransactionBody.newBuilder().fileID(file(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesImpermissibleFileUndel() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .systemUndelete(SystemUndeleteTransactionBody.newBuilder().fileID(file(123)));
        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesImpermissibleFileDel() throws ParseException {
        // given:
        var txn = treasuryTxn()
                .systemDelete(SystemDeleteTransactionBody.newBuilder().fileID(file(123)));
        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesUnauthorizedFileDel() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .systemDelete(SystemDeleteTransactionBody.newBuilder().fileID(file(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesAuthorizedFileDel() throws ParseException {
        // given:
        var txn = sysDeleteTxn()
                .systemDelete(SystemDeleteTransactionBody.newBuilder().fileID(file(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesUnauthorizedContractDel() throws ParseException {
        // given:
        var txn = civilianTxn()
                .systemDelete(SystemDeleteTransactionBody.newBuilder().contractID(contract(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesAuthorizedContractDel() throws ParseException {
        // given:
        var txn = sysDeleteTxn()
                .systemDelete(SystemDeleteTransactionBody.newBuilder().contractID(contract(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesUnnecessary() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .fileAppend(FileAppendTransactionBody.newBuilder().fileID(file(1122)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void contractUpdateRecognizesUnnecessary() throws ParseException {
        // given:
        var txn = treasuryTxn()
                .contractUpdateInstance(
                        ContractUpdateTransactionBody.newBuilder().contractID(contract(1233)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileUpdateRecognizesAuthorized() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(file(112)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void softwareUpdateAdminCanUpdateZipFile() throws ParseException {
        // given:
        var txn = softwareUpdateAdminTxn()
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(file(150)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileUpdateRecognizesUnnecessary() throws ParseException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(file(1122)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemFilesCannotBeDeleted() throws ParseException {
        // given:
        var txn =
                treasuryTxn().fileDelete(FileDeleteTransactionBody.newBuilder().fileID(file(100)));

        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void civilianFilesAreDeletable() throws ParseException {
        // given:
        var txn =
                treasuryTxn().fileDelete(FileDeleteTransactionBody.newBuilder().fileID(file(1001)));

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void civilianContractsAreDeletable() throws ParseException {
        // given:
        var txn = treasuryTxn()
                .contractDeleteInstance(
                        ContractDeleteTransactionBody.newBuilder().contractID(contract(1001)));

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemAccountsCannotBeDeleted() throws ParseException {
        // given:
        var txn = treasuryTxn()
                .cryptoDelete(CryptoDeleteTransactionBody.newBuilder().deleteAccountID(account(100)));

        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void civilianAccountsAreDeletable() throws ParseException {
        // given:
        var txn = civilianTxn()
                .cryptoDelete(CryptoDeleteTransactionBody.newBuilder().deleteAccountID(account(1001)));

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void createAccountAlwaysOk() throws ParseException {
        // given:
        var txn = civilianTxn().cryptoCreateAccount(CryptoCreateTransactionBody.DEFAULT);

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void ethereumTxnAlwaysOk() throws ParseException {
        // given:
        var txn = ethereumTxn().ethereumTransaction(EthereumTransactionBody.DEFAULT);

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void handlesDifferentPayer() throws ParseException {
        // given:
        var selfUpdateTxn = civilianTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(2)));
        var otherUpdateTxn = civilianTxn()
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(account(50)));
        // expect:
        assertEquals(
                SystemOpAuthorization.AUTHORIZED,
                subject.authForTestCase(accessorWithPayer(selfUpdateTxn, account(2))));
        assertEquals(
                SystemOpAuthorization.AUTHORIZED,
                subject.authForTestCase(accessorWithPayer(otherUpdateTxn, account(2))));
    }

    private TestCase accessor(TransactionBody.Builder transaction) throws ParseException {
        var txn = transaction.build().copyBuilder().build();
        return testCaseFrom(Transaction.PROTOBUF
                .toBytes(Transaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(txn))
                        .build())
                .toByteArray());
    }

    private TestCase accessorWithPayer(TransactionBody.Builder txn, AccountID payer) throws ParseException {
        return accessor(txn).withPayerId(payer);
    }

    private TransactionBody.Builder ethereumTxn() {
        return txnWithPayer(123);
    }

    private TransactionBody.Builder civilianTxn() {
        return txnWithPayer(75231);
    }

    private TransactionBody.Builder treasuryTxn() {
        return txnWithPayer(2);
    }

    private TransactionBody.Builder softwareUpdateAdminTxn() {
        return txnWithPayer(54);
    }

    private TransactionBody.Builder freezeAdminTxn() {
        return txnWithPayer(58);
    }

    private TransactionBody.Builder sysAdminTxn() {
        return txnWithPayer(50);
    }

    private TransactionBody.Builder sysDeleteTxn() {
        return txnWithPayer(59);
    }

    private TransactionBody.Builder sysUndeleteTxn() {
        return txnWithPayer(60);
    }

    private TransactionBody.Builder exchangeRatesAdminTxn() {
        return txnWithPayer(57);
    }

    private TransactionBody.Builder addressBookAdminTxn() {
        return txnWithPayer(55);
    }

    private TransactionBody.Builder txnWithPayer(long num) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(account(num)));
    }

    private ContractID contract(long num) {
        return ContractID.newBuilder().contractNum(num).build();
    }

    private FileID file(long num) {
        return FileID.newBuilder().fileNum(num).build();
    }

    private AccountID account(long num) {
        return AccountID.newBuilder().accountNum(num).build();
    }

    /**
     * The relationship of an operation to its required system privileges, if any.
     */
    private enum SystemOpAuthorization {
        /** The operation does not require any system privileges. */
        UNNECESSARY,
        /** The operation requires system privileges that its payer does not have. */
        UNAUTHORIZED,
        /** The operation cannot be performed, no matter the privileges of its payer. */
        IMPERMISSIBLE,
        /** The operation requires system privileges, and its payer has those privileges. */
        AUTHORIZED;
    }

    private TestCase testCaseFrom(final byte[] signedTxnWrapperBytes) throws ParseException {
        final Transaction signedTxnWrapper = Transaction.PROTOBUF.parse(Bytes.wrap(signedTxnWrapperBytes));

        final var signedTxnBytes = signedTxnWrapper.signedTransactionBytes();
        final byte[] txnBytes;
        if (signedTxnBytes.length() == 0) {
            txnBytes = signedTxnWrapper.bodyBytes().toByteArray();
        } else {
            final var signedTxn = SignedTransaction.PROTOBUF.parse(signedTxnBytes);
            txnBytes = signedTxn.bodyBytes().toByteArray();
        }
        final var protoTxnBody = TransactionBody.PROTOBUF.parse(Bytes.wrap(txnBytes));
        final var payerId = protoTxnBody.transactionIDOrThrow().accountIDOrThrow();
        try {
            final var function = functionOf(protoTxnBody);
            return new TestCase(payerId, function, protoTxnBody);
        } catch (com.hedera.hapi.util.UnknownHederaFunctionality e) {
            throw new IllegalStateException(e);
        }
    }
}

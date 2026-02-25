// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.authorization;

import static com.hedera.node.app.hapi.utils.ByteStringUtils.unwrapUnsafelyIfPossible;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.hapi.utils.CommonUtils.functionOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ContractUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.FileAppendTransactionBody;
import com.hederahashgraph.api.proto.java.FileDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.FreezeTransactionBody;
import com.hederahashgraph.api.proto.java.NodeCreateTransactionBody;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.SystemDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.SystemUndeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.UncheckedSubmitBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// This test may look a little weird without context. The original test in mono is very extensive. To ensure that
// we don't break anything, I copied the test from mono and hacked it a little to run it with the new code.
// (It was a good thing. I discovered two bugs...) :)
class PrivilegesVerifierTest {
    private static final AccountID EVM_ACCOUNT_ID = AccountID.newBuilder()
            .setAlias(ByteString.fromHex("abcd1234abcd1234abcd1234abcd1234abcd1234"))
            .build();

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
    void uncheckedSubmitRejectsUnauthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setUncheckedSubmit(UncheckedSubmitBody.newBuilder()
                        .setTransactionBytes(ByteString.copyFrom("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void sysAdminCanSubmitUnchecked() throws InvalidProtocolBufferException {
        // given:
        var txn = sysAdminTxn()
                .setUncheckedSubmit(UncheckedSubmitBody.newBuilder()
                        .setTransactionBytes(ByteString.copyFrom("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void treasuryCanSubmitUnchecked() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setUncheckedSubmit(UncheckedSubmitBody.newBuilder()
                        .setTransactionBytes(ByteString.copyFrom("DOESN'T MATTER".getBytes())));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesAuthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(75)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesUnnecessaryForSystem() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(75)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesUnnecessaryForNonSystem() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(1001)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoUpdateRecognizesAuthorizedForTreasury() throws InvalidProtocolBufferException {
        // given:
        var selfUpdateTxn = treasuryTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(2)));
        var otherUpdateTxn = treasuryTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(50)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(selfUpdateTxn)));
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(otherUpdateTxn)));
    }

    @Test
    void cryptoUpdateRecognizesUnauthorized() throws InvalidProtocolBufferException {
        // given:
        var civilianTxn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(2)));
        var sysAdminTxn = sysAdminTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(2)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(civilianTxn)));
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(sysAdminTxn)));
    }

    @Test
    void fileUpdateRecognizesUnauthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileUpdate(FileUpdateTransactionBody.newBuilder().setFileID(file(111)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesUnauthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileAppend(FileAppendTransactionBody.newBuilder().setFileID(file(111)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesAuthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileAppend(FileAppendTransactionBody.newBuilder().setFileID(file(112)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void treasuryCanFreeze() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn().setFreeze(FreezeTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void sysAdminCanFreeze() throws InvalidProtocolBufferException {
        // given:
        var txn = sysAdminTxn().setFreeze(FreezeTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void freezeAdminCanFreeze() throws InvalidProtocolBufferException {
        // given:
        var txn = freezeAdminTxn().setFreeze(FreezeTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void treasuryCanCreateNode() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn().setNodeCreate(NodeCreateTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void sysAdminnCanCreateNode() throws InvalidProtocolBufferException {
        // given:
        var txn = sysAdminTxn().setNodeCreate(NodeCreateTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void addressBookAdminCanCreateNode() throws InvalidProtocolBufferException {
        // given:
        var txn = addressBookAdminTxn().setNodeCreate(NodeCreateTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void randomAdminCannotFreeze() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn().setFreeze(FreezeTransactionBody.getDefaultInstance());
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesImpermissibleContractDel() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setContractID(contract(123)));
        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesImpermissibleContractUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setContractID(contract(123)));
        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesUnauthorizedContractUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setContractID(contract(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesAuthorizedContractUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = sysUndeleteTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setContractID(contract(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesAuthorizedFileUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = sysUndeleteTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setFileID(file(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesUnauthorizedFileUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setFileID(file(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemUndeleteRecognizesImpermissibleFileUndel() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().setFileID(file(123)));
        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesImpermissibleFileDel() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setFileID(file(123)));
        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesUnauthorizedFileDel() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setFileID(file(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesAuthorizedFileDel() throws InvalidProtocolBufferException {
        // given:
        var txn = sysDeleteTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setFileID(file(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesUnauthorizedContractDel() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setContractID(contract(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemDeleteRecognizesAuthorizedContractDel() throws InvalidProtocolBufferException {
        // given:
        var txn = sysDeleteTxn()
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().setContractID(contract(1234)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileAppendRecognizesUnnecessary() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileAppend(FileAppendTransactionBody.newBuilder().setFileID(file(1122)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void contractUpdateRecognizesUnnecessary() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setContractUpdateInstance(
                        ContractUpdateTransactionBody.newBuilder().setContractID(contract(1233)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileUpdateRecognizesAuthorized() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileUpdate(FileUpdateTransactionBody.newBuilder().setFileID(file(112)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void softwareUpdateAdminCanUpdateZipFile() throws InvalidProtocolBufferException {
        // given:
        var txn = softwareUpdateAdminTxn()
                .setFileUpdate(FileUpdateTransactionBody.newBuilder().setFileID(file(150)));
        // expect:
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void fileUpdateRecognizesUnnecessary() throws InvalidProtocolBufferException {
        // given:
        var txn = exchangeRatesAdminTxn()
                .setFileUpdate(FileUpdateTransactionBody.newBuilder().setFileID(file(1122)));
        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void systemFilesCannotBeDeleted() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setFileDelete(FileDeleteTransactionBody.newBuilder().setFileID(file(100)));

        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void civilianFilesAreDeletable() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setFileDelete(FileDeleteTransactionBody.newBuilder().setFileID(file(1001)));

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void civilianContractsAreDeletable() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setContractDeleteInstance(
                        ContractDeleteTransactionBody.newBuilder().setContractID(contract(1001)));

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void cryptoDeleteDecodesLongZeroAliases() throws InvalidProtocolBufferException {
        final var impermissibleDeletion = treasuryTxn()
                .setCryptoDelete(CryptoDeleteTransactionBody.newBuilder().setDeleteAccountID(longZeroAccountId(100)));
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(impermissibleDeletion)));

        final var privilegesUnnecessaryDeletion = treasuryTxn()
                .setCryptoDelete(CryptoDeleteTransactionBody.newBuilder().setDeleteAccountID(longZeroAccountId(1001)));
        assertEquals(
                SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(privilegesUnnecessaryDeletion)));
    }

    @Test
    void cryptoUpdateDecodesLongZeroAliases() throws InvalidProtocolBufferException {
        final var authorizedTreasuryUpdate = treasuryTxn()
                .setCryptoUpdateAccount(
                        CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(longZeroAccountId(2)));
        assertEquals(SystemOpAuthorization.AUTHORIZED, subject.authForTestCase(accessor(authorizedTreasuryUpdate)));

        final var unauthorizedTreasuryUpdate = sysAdminTxn()
                .setCryptoUpdateAccount(
                        CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(longZeroAccountId(2)));
        assertEquals(SystemOpAuthorization.UNAUTHORIZED, subject.authForTestCase(accessor(unauthorizedTreasuryUpdate)));

        final var privilegesUnnecessaryUpdate = sysAdminTxn()
                .setCryptoUpdateAccount(
                        CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(longZeroAccountId(1001)));
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(privilegesUnnecessaryUpdate)));
    }

    @Test
    void cryptoDeleteAssumesEvmAddressesAreNotSystemEntities() throws InvalidProtocolBufferException {
        final var privilegesUnnecessaryDeletion = treasuryTxn()
                .setCryptoDelete(CryptoDeleteTransactionBody.newBuilder().setDeleteAccountID(EVM_ACCOUNT_ID));
        assertEquals(
                SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(privilegesUnnecessaryDeletion)));
    }

    @Test
    void systemAccountsCannotBeDeleted() throws InvalidProtocolBufferException {
        // given:
        var txn = treasuryTxn()
                .setCryptoDelete(CryptoDeleteTransactionBody.newBuilder().setDeleteAccountID(account(100)));

        // expect:
        assertEquals(SystemOpAuthorization.IMPERMISSIBLE, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void civilianAccountsAreDeletable() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn()
                .setCryptoDelete(CryptoDeleteTransactionBody.newBuilder().setDeleteAccountID(account(1001)));

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void createAccountAlwaysOk() throws InvalidProtocolBufferException {
        // given:
        var txn = civilianTxn().setCryptoCreateAccount(CryptoCreateTransactionBody.getDefaultInstance());

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void ethereumTxnAlwaysOk() throws InvalidProtocolBufferException {
        // given:
        var txn = ethereumTxn().setEthereumTransaction(EthereumTransactionBody.getDefaultInstance());

        // expect:
        assertEquals(SystemOpAuthorization.UNNECESSARY, subject.authForTestCase(accessor(txn)));
    }

    @Test
    void handlesDifferentPayer() throws InvalidProtocolBufferException {
        // given:
        var selfUpdateTxn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(2)));
        var otherUpdateTxn = civilianTxn()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder().setAccountIDToUpdate(account(50)));
        // expect:
        assertEquals(
                SystemOpAuthorization.AUTHORIZED,
                subject.authForTestCase(accessorWithPayer(selfUpdateTxn, account(2))));
        assertEquals(
                SystemOpAuthorization.AUTHORIZED,
                subject.authForTestCase(accessorWithPayer(otherUpdateTxn, account(2))));
    }

    private TestCase accessor(TransactionBody.Builder transaction) throws InvalidProtocolBufferException {
        var txn = TransactionBody.newBuilder().mergeFrom(transaction.build()).build();
        return testCaseFrom(Transaction.newBuilder()
                .setBodyBytes(txn.toByteString())
                .build()
                .toByteArray());
    }

    private TestCase accessorWithPayer(TransactionBody.Builder txn, AccountID payer)
            throws InvalidProtocolBufferException {
        return accessor(txn).withPayerId(toPbj(payer));
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
                .setTransactionID(TransactionID.newBuilder().setAccountID(account(num)));
    }

    private ContractID contract(long num) {
        return ContractID.newBuilder().setContractNum(num).build();
    }

    private FileID file(long num) {
        return FileID.newBuilder().setFileNum(num).build();
    }

    private AccountID account(long num) {
        return AccountID.newBuilder().setAccountNum(num).build();
    }

    private AccountID longZeroAccountId(long num) {
        return AccountID.newBuilder()
                .setAlias(ByteString.copyFrom(asEvmAddress(num)))
                .build();
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

    private TestCase testCaseFrom(final byte[] signedTxnWrapperBytes) throws InvalidProtocolBufferException {
        final Transaction signedTxnWrapper = Transaction.parseFrom(signedTxnWrapperBytes);

        final var signedTxnBytes = signedTxnWrapper.getSignedTransactionBytes();
        final byte[] txnBytes;
        if (signedTxnBytes.isEmpty()) {
            txnBytes = unwrapUnsafelyIfPossible(signedTxnWrapper.getBodyBytes());
        } else {
            final var signedTxn = SignedTransaction.parseFrom(signedTxnBytes);
            txnBytes = unwrapUnsafelyIfPossible(signedTxn.getBodyBytes());
        }
        final var protoTxnBody = TransactionBody.parseFrom(txnBytes);
        final var txn = toPbj(protoTxnBody);
        final var payerId = txn.transactionIDOrThrow().accountIDOrThrow();
        try {
            final var function = functionOf(protoTxnBody);
            return new TestCase(payerId, toPbj(function), txn);
        } catch (com.hedera.hapi.util.UnknownHederaFunctionality e) {
            throw new IllegalStateException(e);
        }
    }
}

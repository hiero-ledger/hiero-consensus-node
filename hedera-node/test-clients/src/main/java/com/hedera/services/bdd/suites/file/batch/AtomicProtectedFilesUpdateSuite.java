// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_DETAILS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileContents;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo;
import com.hederahashgraph.api.proto.java.NodeAddress;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of ProtectedFilesUpdateSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@OrderedInIsolation
public class AtomicProtectedFilesUpdateSuite {

    private static final String IGNORE = "ignore";
    private static final String TARGET_MEMO = "0.0.5";
    private static final String REPLACE_MEMO = "0.0.6";
    private static final String NEW_CONTENTS = "newContents";
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Logger log = LogManager.getLogger(AtomicProtectedFilesUpdateSuite.class);
    private static final String BATCH_OPERATOR = "batchOperator";

    // The number of chars that separate a property and its value
    private static final int PROPERTY_VALUE_SPACE_LENGTH = 2;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    final Stream<DynamicTest> account2CanUpdateApplicationProperties() {
        return specialAccountCanUpdateSpecialPropertyFile(GENESIS, APP_PROPERTIES, "throttlingTps", "10");
    }

    @HapiTest
    final Stream<DynamicTest> account50CanUpdateApplicationProperties() {
        return specialAccountCanUpdateSpecialPropertyFile(SYSTEM_ADMIN, APP_PROPERTIES, "getReceiptTps", "100");
    }

    @HapiTest
    final Stream<DynamicTest> unauthorizedAccountCannotUpdateApplicationProperties() {
        return unauthorizedAccountCannotUpdateSpecialFile(APP_PROPERTIES, NEW_CONTENTS);
    }

    @HapiTest
    final Stream<DynamicTest> account2CanUpdateApiPermissions() {
        return specialAccountCanUpdateSpecialPropertyFile(GENESIS, API_PERMISSIONS, "createTopic", "1-*");
    }

    @HapiTest
    final Stream<DynamicTest> account50CanUpdateApiPermissions() {
        return specialAccountCanUpdateSpecialPropertyFile(SYSTEM_ADMIN, API_PERMISSIONS, "updateFile", "1-*");
    }

    @HapiTest
    final Stream<DynamicTest> unauthorizedAccountCannotUpdateApiPermissions() {
        return unauthorizedAccountCannotUpdateSpecialFile(API_PERMISSIONS, NEW_CONTENTS);
    }

    @HapiTest
    final Stream<DynamicTest> account2CanUpdateAddressBook() {
        return specialAccountCanUpdateSpecialFile(
                GENESIS, ADDRESS_BOOK, true, contents -> extendedBioAddressBook(contents, TARGET_MEMO, REPLACE_MEMO));
    }

    @HapiTest
    final Stream<DynamicTest> account2CanUpdateAddressBookAccount() {
        return specialAccountCanUpdateSpecialFile(
                GENESIS, ADDRESS_BOOK, true, contents -> extendedBioAddressBook2(contents, TARGET_MEMO, REPLACE_MEMO));
    }

    @HapiTest
    final Stream<DynamicTest> account50CanUpdateAddressBook() {
        return specialAccountCanUpdateSpecialFile(SYSTEM_ADMIN, ADDRESS_BOOK, TARGET_MEMO, REPLACE_MEMO);
    }

    @HapiTest
    final Stream<DynamicTest> account55CanUpdateAddressBook() {
        return specialAccountCanUpdateSpecialFile(ADDRESS_BOOK_CONTROL, ADDRESS_BOOK, TARGET_MEMO, REPLACE_MEMO, false);
    }

    @HapiTest
    final Stream<DynamicTest> unauthorizedAccountCannotUpdateAddressBook() {
        return unauthorizedAccountCannotUpdateSpecialFile(ADDRESS_BOOK, NEW_CONTENTS);
    }

    @HapiTest
    final Stream<DynamicTest> account2CanUpdateNodeDetails() {
        return specialAccountCanUpdateSpecialFile(
                GENESIS, NODE_DETAILS, true, contents -> extendedBioNodeDetails(contents, TARGET_MEMO, REPLACE_MEMO));
    }

    @HapiTest
    final Stream<DynamicTest> account50CanUpdateNodeDetails() {
        return specialAccountCanUpdateSpecialFile(SYSTEM_ADMIN, NODE_DETAILS, TARGET_MEMO, REPLACE_MEMO);
    }

    @HapiTest
    final Stream<DynamicTest> account55CanUpdateNodeDetails() {
        return specialAccountCanUpdateSpecialFile(ADDRESS_BOOK_CONTROL, NODE_DETAILS, TARGET_MEMO, REPLACE_MEMO, false);
    }

    @HapiTest
    final Stream<DynamicTest> unauthorizedAccountCannotUpdateNodeDetails() {
        return unauthorizedAccountCannotUpdateSpecialFile(NODE_DETAILS, NEW_CONTENTS);
    }

    @HapiTest
    final Stream<DynamicTest> account2CanUpdateFeeSchedule() {
        return specialAccountCanUpdateSpecialFile(GENESIS, FEE_SCHEDULE, IGNORE, IGNORE);
    }

    @HapiTest
    final Stream<DynamicTest> account50CanUpdateFeeSchedule() {
        return specialAccountCanUpdateSpecialFile(SYSTEM_ADMIN, FEE_SCHEDULE, IGNORE, IGNORE);
    }

    @HapiTest
    final Stream<DynamicTest> account56CanUpdateFeeSchedule() {
        return specialAccountCanUpdateSpecialFile(FEE_SCHEDULE_CONTROL, FEE_SCHEDULE, IGNORE, IGNORE);
    }

    @HapiTest
    final Stream<DynamicTest> unauthorizedAccountCannotUpdateFeeSchedule() {
        return unauthorizedAccountCannotUpdateSpecialFile(FEE_SCHEDULE, NEW_CONTENTS);
    }

    @HapiTest
    final Stream<DynamicTest> account2CanUpdateExchangeRates() {
        return specialAccountCanUpdateSpecialFile(GENESIS, EXCHANGE_RATES, IGNORE, IGNORE);
    }

    @HapiTest
    final Stream<DynamicTest> account50CanUpdateExchangeRates() {
        return specialAccountCanUpdateSpecialFile(SYSTEM_ADMIN, EXCHANGE_RATES, IGNORE, IGNORE);
    }

    @HapiTest
    final Stream<DynamicTest> account57CanUpdateExchangeRates() {
        return specialAccountCanUpdateSpecialFile(EXCHANGE_RATE_CONTROL, EXCHANGE_RATES, IGNORE, IGNORE);
    }

    @HapiTest
    final Stream<DynamicTest> unauthorizedAccountCannotUpdateExchangeRates() {
        return unauthorizedAccountCannotUpdateSpecialFile(EXCHANGE_RATES, NEW_CONTENTS);
    }

    final Stream<DynamicTest> specialAccountCanUpdateSpecialPropertyFile(
            final String specialAccount, final String specialFile, final String property, final String expected) {
        return specialAccountCanUpdateSpecialPropertyFile(specialAccount, specialFile, property, expected, true);
    }

    final Stream<DynamicTest> specialAccountCanUpdateSpecialPropertyFile(
            final String specialAccount,
            final String specialFile,
            final String property,
            final String expected,
            final boolean isFree) {
        return hapiTest(flattened(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                givenOps(specialAccount, specialFile),
                atomicBatch(fileUpdate(specialFile)
                                .overridingProps(Map.of(property, expected))
                                .payingWith(specialAccount)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                validateAndCleanUpOps(
                        propertyFileValidationOp(specialAccount, specialFile, property, expected),
                        specialAccount,
                        specialFile,
                        isFree)));
    }

    private HapiSpecOperation propertyFileValidationOp(
            String account, String fileName, String property, String expected) {
        return UtilVerbs.withOpContext((spec, ctxLog) -> {
            String registryEntry = fileName + "_CHANGED_BY_" + account;
            HapiGetFileContents subOp = getFileContents(fileName).saveToRegistry(registryEntry);
            CustomSpecAssert.allRunFor(spec, subOp);
            String newContents = new String(spec.registry().getBytes(registryEntry));
            int propertyIndex = newContents.indexOf(property);
            Assertions.assertTrue(propertyIndex >= 0);
            int valueIndex = propertyIndex + property.length() + PROPERTY_VALUE_SPACE_LENGTH;
            String actual = newContents.substring(valueIndex, valueIndex + expected.length());
            Assertions.assertEquals(expected, actual);
        });
    }

    final Stream<DynamicTest> specialAccountCanUpdateSpecialFile(
            final String specialAccount, final String specialFile, final String target, final String replacement) {
        return specialAccountCanUpdateSpecialFile(specialAccount, specialFile, target, replacement, true);
    }

    final Stream<DynamicTest> specialAccountCanUpdateSpecialFile(
            final String specialAccount,
            final String specialFile,
            final String target,
            final String replacement,
            final boolean isFree) {
        return specialAccountCanUpdateSpecialFile(
                specialAccount,
                specialFile,
                isFree,
                contents -> target.equals(IGNORE)
                        ? contents
                        : (new String(contents).replace(target, replacement)).getBytes());
    }

    final Stream<DynamicTest> specialAccountCanUpdateSpecialFile(
            final String specialAccount,
            final String specialFile,
            final boolean isFree,
            final UnaryOperator<byte[]> contentsTransformer) {
        final String newFileName = "NEW_" + specialFile;

        return defaultHapiSpec(specialAccount + "CanUpdate" + specialFile)
                .given(ArrayUtils.add(givenOps(specialAccount, specialFile), UtilVerbs.withOpContext((spec, ctxLog) -> {
                    var origContents = spec.registry().getBytes(specialFile);
                    var newContents = contentsTransformer.apply(origContents);
                    spec.registry().saveBytes(newFileName, ByteString.copyFrom(newContents));
                })))
                .when(UtilVerbs.updateLargeFile(specialAccount, specialFile, newFileName))
                .then(validateAndCleanUpOps(
                        getFileContents(specialFile).hasContents(newFileName), specialAccount, specialFile, isFree));
    }

    private HapiSpecOperation[] givenOps(String account, String fileName) {
        HapiSpecOperation[] opsArray = {
            UtilVerbs.fundAnAccount(account),
            getFileContents(fileName).saveToRegistry(fileName),
            UtilVerbs.balanceSnapshot("preUpdate", account)
        };
        return opsArray;
    }

    private HapiSpecOperation[] validateAndCleanUpOps(
            final HapiSpecOperation validateOp, final String account, final String fileName, final boolean isFree) {
        HapiSpecOperation[] accountBalanceUnchanged = {
            getAccountBalance(account).hasTinyBars(changeFromSnapshot("preUpdate", 0))
        };
        HapiSpecOperation[] opsArray = {
            validateOp, UtilVerbs.updateLargeFile(account, fileName, fileName),
        };
        if (account.equals(GENESIS) || !isFree) {
            return opsArray;
        }
        return ArrayUtils.addAll(accountBalanceUnchanged, opsArray);
    }

    final Stream<DynamicTest> unauthorizedAccountCannotUpdateSpecialFile(
            final String specialFile, final String newContents) {
        return hapiTest(
                cryptoCreate("unauthorizedAccount"),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                atomicBatch(fileUpdate(specialFile)
                                .contents(newContents)
                                .payingWith("unauthorizedAccount")
                                .hasPrecheck(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    private byte[] extendedBioAddressBook(byte[] contents, String targetMemo, String replaceMemo) {
        var r = new SplittableRandom();
        try {
            var book = NodeAddressBook.parseFrom(contents);
            var builder = book.toBuilder();
            byte[] randCertHash = new byte[32];
            long nodeId = 0;
            for (NodeAddress.Builder node : builder.getNodeAddressBuilderList()) {
                node.setNodeId(nodeId++);
                r.nextBytes(randCertHash);
                node.setNodeCertHash(ByteString.copyFrom(randCertHash));
                node.setNodeAccountId(node.getNodeAccountId());
                if (new String(node.getMemo().toByteArray()).equals(targetMemo)) {
                    node.setMemo(ByteString.copyFrom(replaceMemo.getBytes()));
                }
            }
            var newBook = builder.build();
            var bookJson = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(AddressBookPojo.addressBookFrom(newBook));
            log.info("New address book w/ extended bio: {}", bookJson);
            return builder.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            log.error("Basic address book could not be parsed", e);
            throw new AssertionError("Unparseable address book!");
        } catch (JsonProcessingException e) {
            log.error("Extended address book could not be serialized", e);
            throw new AssertionError("Unserializable address book!");
        }
    }

    private byte[] extendedBioAddressBook2(byte[] contents, String targetAccount, String replaceAccount) {
        var r = new SplittableRandom();
        try {
            var book = NodeAddressBook.parseFrom(contents);
            var builder = book.toBuilder();
            byte[] randCertHash = new byte[32];
            long nodeId = 0;
            for (NodeAddress.Builder node : builder.getNodeAddressBuilderList()) {
                node.setNodeId(nodeId++);
                r.nextBytes(randCertHash);
                node.setNodeCertHash(ByteString.copyFrom(randCertHash));
                node.setNodeAccountId(node.getNodeAccountId());
                if (node.getNodeAccountId().equals(asAccount(targetAccount))) {
                    node.setNodeAccountId(asAccount(replaceAccount));
                }
            }
            var newBook = builder.build();
            var bookJson = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(AddressBookPojo.addressBookFrom(newBook));
            log.info("New address book w/ extended bio: {}", bookJson);
            return builder.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            log.error("Basic address book could not be parsed", e);
            throw new AssertionError("Unparseable address book!");
        } catch (JsonProcessingException e) {
            log.error("Extended address book could not be serialized", e);
            throw new AssertionError("Unserializable address book!");
        }
    }

    private byte[] extendedBioNodeDetails(byte[] contents, String targetMemo, String replaceMemo) {
        var r = new SplittableRandom();
        try {
            var book = NodeAddressBook.parseFrom(contents);
            var builder = book.toBuilder();
            byte[] randPubKey = new byte[422];
            long nodeId = 0;
            for (NodeAddress.Builder node : builder.getNodeAddressBuilderList()) {
                node.setNodeId(nodeId++);
                r.nextBytes(randPubKey);
                node.setRSAPubKey(CommonUtils.hex(randPubKey));
                if (new String(node.getMemo().toByteArray()).equals(targetMemo)) {
                    node.setMemo(ByteString.copyFrom(replaceMemo.getBytes()));
                }
            }
            var newBook = builder.build();
            var bookJson = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(AddressBookPojo.nodeDetailsFrom(newBook));
            log.info("New node details w/ extended bio: {}", bookJson);
            return builder.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            log.error("Basic node details could not be parsed", e);
            throw new AssertionError("Unparseable node details!");
        } catch (JsonProcessingException e) {
            log.error("Extended node details could not be serialized", e);
            throw new AssertionError("Unserializable node details!");
        }
    }
}

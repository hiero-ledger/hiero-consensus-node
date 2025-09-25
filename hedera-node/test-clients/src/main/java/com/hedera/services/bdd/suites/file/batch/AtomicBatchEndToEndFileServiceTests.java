// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class AtomicBatchEndToEndFileServiceTests {
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String PAYER = "payer";
    private static final String PAYER_INSUFFICIENT_BALANCE = "payerInsufficientBalance";
    private static final String TEST_FILE = "testFile";
    private static final String TEST_FILE_A = "testFileA";
    private static final String TEST_FILE_B = "testFileB";
    private static final String TEST_CONTENT = "TestContent";
    private static final String TEST_MEMO = "testMemo";

    private static final String fileKey = "fileKey";
    private static final String newFileKey = "newFileKey";
    private static final String firstFileSignerKey = "firstFileSignerKey";
    private static final String secondFileSignerKey = "secondFileSignerKey";

    @HapiTest
    @DisplayName("File Update and File Append Success in Atomic batch")
    final Stream<DynamicTest> fileUpdateAndFileAppendSuccessInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var expectedContent = new byte[initialContent.length + appendContent.length];
        System.arraycopy(initialContent, 0, expectedContent, 0, initialContent.length);
        System.arraycopy(appendContent, 0, expectedContent, initialContent.length, appendContent.length);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn)
                        .via("atomicBatchFileUpdateAndFileAppend")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> expectedContent)));
    }

    @HapiTest
    @DisplayName("File Append and File Delete Success in Atomic batch")
    final Stream<DynamicTest> fileAppendAndFileDeleteSuccessInAtomicBatch() {
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileAppendTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAppendAndDelete")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file is deleted
                getFileInfo(TEST_FILE).hasDeleted(true)));
    }

    @HapiTest
    @DisplayName("File Update and File Delete Success in Atomic batch")
    final Stream<DynamicTest> fileUpdateAndFileDeleteSuccessInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAndFileDelete")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file is deleted
                getFileInfo(TEST_FILE).hasDeleted(true)));
    }

    @HapiTest
    @DisplayName("File Update, Append and Delete Success in Atomic batch")
    final Stream<DynamicTest> fileUpdateAppendAndDeleteSuccessInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAppendAndFileDelete")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file is deleted
                getFileInfo(TEST_FILE).hasDeleted(true)));
    }

    @HapiTest
    @DisplayName("Update File Key and File Update Success in Atomic batch")
    final Stream<DynamicTest> updateFileKeyAndFileUpdateSuccessInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var expectedContent = new byte[initialContent.length];
        System.arraycopy(initialContent, 0, expectedContent, 0, initialContent.length);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateKeyTxn = fileUpdate(TEST_FILE)
                .memo("Update key")
                .wacl("newKeyList")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey, newFileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileUpdateContentTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, newFileKey)
                .via("fileUpdateContentTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateKeyTxn, fileUpdateContentTxn)
                        .via("atomicBatchFileUpdateKeyAndContent")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> expectedContent)));
    }

    @HapiTest
    @DisplayName("Update File Key and File Append Success in Atomic batch")
    final Stream<DynamicTest> updateFileKeyAndFileAppendSuccessInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var expectedContent = new byte[initialContent.length + appendContent.length];
        System.arraycopy(initialContent, 0, expectedContent, 0, initialContent.length);
        System.arraycopy(appendContent, 0, expectedContent, initialContent.length, appendContent.length);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateKeyTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo("Update content and key")
                .wacl("newKeyList")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey, newFileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendContentTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, newFileKey)
                .via("fileAppendContentTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateKeyTxn, fileAppendContentTxn)
                        .via("atomicBatchFileUpdateAndFileAppend")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> expectedContent)));
    }

    @HapiTest
    @DisplayName("Update File Key and Multiple File Append Success in Atomic batch")
    final Stream<DynamicTest> updateFileKeyAndMultipleFileAppendSuccessInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContentFirst = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContentSecond = "B".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var expectedContent =
                new byte[initialContent.length + appendContentFirst.length + appendContentSecond.length];
        System.arraycopy(initialContent, 0, expectedContent, 0, initialContent.length);
        System.arraycopy(appendContentFirst, 0, expectedContent, initialContent.length, appendContentFirst.length);
        System.arraycopy(
                appendContentSecond,
                0,
                expectedContent,
                initialContent.length + appendContentFirst.length,
                appendContentSecond.length);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo("Update content and key")
                .wacl("newKeyList")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey, newFileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxnFirst = fileAppend(TEST_FILE)
                .content(appendContentFirst)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, newFileKey)
                .via("fileAppendTxnFirst")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxnSecond = fileAppend(TEST_FILE)
                .content(appendContentSecond)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, newFileKey)
                .via("fileAppendTxnSecond")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxnFirst, fileAppendTxnSecond)
                        .via("atomicBatchFileUpdateAndMultipleFileAppend")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> expectedContent)));
    }

    @HapiTest
    @DisplayName("File Update, Append and Delete with Multiple Keys List Success in Atomic batch")
    final Stream<DynamicTest> fileUpdateAppendAndDeleteWithMultipleKeysListSuccessInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("MultipleKeyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAppendAndFileDelete")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file is deleted
                getFileInfo(TEST_FILE).hasDeleted(true)));
    }

    @HapiTest
    @DisplayName(
            "File with Multiple Keys List, Update, Append and Delete Signed By Only One Key Success in Atomic batch")
    final Stream<DynamicTest> fileUpdateAppendAndDeleteSignedByOneKeySuccessInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("MultipleKeyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAppendAndFileDelete")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify file is deleted
                getFileInfo(TEST_FILE).hasDeleted(true)));
    }

    @HapiTest
    @DisplayName("Multiple File Updates Success in Atomic batch")
    final Stream<DynamicTest> multipleFileUpdatesSuccessInAtomicBatch() {
        final var updatedContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxnFirst = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("MultipleKeyList")
                .payingWith(PAYER)
                .via("fileCreateTxnFirst");

        final var fileCreateTxnSecond = fileCreate(TEST_FILE_A)
                .contents(TEST_CONTENT)
                .key("MultipleKeyList")
                .payingWith(PAYER)
                .via("fileCreateTxnSecond");

        final var fileCreateTxnThird = fileCreate(TEST_FILE_B)
                .contents(TEST_CONTENT)
                .key("MultipleKeyList")
                .payingWith(PAYER)
                .via("fileCreateTxnThird");

        final var fileUpdateTxnFirst = fileUpdate(TEST_FILE)
                .contents(updatedContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileUpdateTxnFirst")
                .batchKey(BATCH_OPERATOR);

        final var fileUpdateTxnSecond = fileUpdate(TEST_FILE_A)
                .contents(updatedContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileUpdateTxnSecond")
                .batchKey(BATCH_OPERATOR);

        final var fileUpdateTxnThird = fileUpdate(TEST_FILE_B)
                .contents(updatedContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileUpdateTxnThird")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxnFirst,
                fileCreateTxnSecond,
                fileCreateTxnThird,
                atomicBatch(fileUpdateTxnFirst, fileUpdateTxnSecond, fileUpdateTxnThird)
                        .via("atomicBatchMultipleFilesUpdates")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS),
                // Verify files contents
                getFileContents(TEST_FILE).hasContents(spec -> updatedContent),
                getFileContents(TEST_FILE_A).hasContents(spec -> updatedContent),
                getFileContents(TEST_FILE_B).hasContents(spec -> updatedContent)));
    }

    @HapiTest
    @DisplayName("Delete and Update Deleted File Key Fails in Atomic batch")
    final Stream<DynamicTest> deleteAndUpdateDeletedFileKeyFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo("Update key")
                .wacl("newKeyList")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey, newFileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(FILE_DELETED);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileDeleteTxn, fileUpdateTxn)
                        .via("atomicBatchFileDeleteAndUpdate")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("Delete and Append Deleted File Key Fails in Atomic batch")
    final Stream<DynamicTest> deleteAndAppendDeletedFileKeyFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(initialContent)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey, newFileKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(FILE_DELETED);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileDeleteTxn, fileAppendTxn)
                        .via("atomicBatchFileDeleteAndAppend")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("Update Immutable File Fails in Atomic batch")
    final Stream<DynamicTest> updateImmutableFileFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn =
                fileCreate(TEST_FILE).contents(TEST_CONTENT).payingWith(PAYER).via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo("Update immutable file")
                .wacl("keyList")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_SIGNATURE);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn)
                        .via("atomicBatchFileUpdate")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("Delete Immutable File Fails in Atomic batch")
    final Stream<DynamicTest> deleteImmutableFileFailsInAtomicBatch() {
        final var fileCreateTxn =
                fileCreate(TEST_FILE).contents(TEST_CONTENT).payingWith(PAYER).via("fileCreateTxn");

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_SIGNATURE);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileDeleteTxn)
                        .via("atomicBatchFileDeleteAndAppend")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("Update Not Existing File Fails in Atomic batch")
    final Stream<DynamicTest> updateFileKeyWithInvalidKeyFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);

        final var fileUpdateTxn = fileUpdate("0.0.123456") // not existing file
                .contents(initialContent)
                .memo("Update file")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_FILE_ID);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                atomicBatch(fileUpdateTxn)
                        .via("atomicBatchFileUpdateInvalidFile")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    @DisplayName("Update File Key Not Signed by New Key and File Append Fails in Atomic batch")
    final Stream<DynamicTest> updateFileKeyNotSignedWithNewKeyAndFileAppendFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo("Update content and key")
                .wacl("newKeyList")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_SIGNATURE);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, newFileKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn)
                        .via("atomicBatchFileUpdateAndFileAppend")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("Update File Key and File Append Signed by Old Key Fails in Atomic batch")
    final Stream<DynamicTest> updateFileKeyAndFileAppendSignedByOldKeyFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo("Update content and key")
                .wacl("newKeyList")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey, newFileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_SIGNATURE);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn)
                        .via("atomicBatchFileUpdateAndFileAppend")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("Update File Key and Delete File Signed by Old Key Fails in Atomic batch")
    final Stream<DynamicTest> updateFileKeyAndDeletedFileSignedByOldKeyFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo("Update key")
                .wacl("newKeyList")
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey, newFileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_SIGNATURE);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAndDeleteWithOldKey")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("File Update, Append and Delete with Insufficient Payer Balance Fails in Atomic batch")
    final Stream<DynamicTest> fileUpdateAppendAndDeleteWithInsufficientPayerBalanceFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("keyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, fileKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER_INSUFFICIENT_BALANCE)
                .signedBy(PAYER_INSUFFICIENT_BALANCE, fileKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAppendAndFileDelete")
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("File with Multiple Keys List, Update Not Signed By All Keys, Append and Delete Fails in Atomic batch")
    final Stream<DynamicTest> fileUpdateNotSignedByAllKeysAppendAndDeleteFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("MultipleKeyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_SIGNATURE);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAppendAndFileDelete")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    @HapiTest
    @DisplayName("File with Multiple Keys List, Update, Append Not Signed By All Keys and Delete Fails in Atomic batch")
    final Stream<DynamicTest> fileUpdateAppendNotSignedByAllKeysAndDeleteFailsInAtomicBatch() {
        final var initialContent = "0".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var appendContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        final var fileCreateTxn = fileCreate(TEST_FILE)
                .contents(TEST_CONTENT)
                .key("MultipleKeyList")
                .payingWith(PAYER)
                .via("fileCreateTxn");

        final var fileUpdateTxn = fileUpdate(TEST_FILE)
                .contents(initialContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileUpdateTxn")
                .batchKey(BATCH_OPERATOR);

        final var fileAppendTxn = fileAppend(TEST_FILE)
                .content(appendContent)
                .memo(TEST_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey)
                .via("fileAppendTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_SIGNATURE);

        final var fileDeleteTxn = fileDelete(TEST_FILE)
                .payingWith(PAYER)
                .signedBy(PAYER, firstFileSignerKey, secondFileSignerKey)
                .via("fileDeleteTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                fileCreateTxn,
                atomicBatch(fileUpdateTxn, fileAppendTxn, fileDeleteTxn)
                        .via("atomicBatchFileUpdateAppendAndFileDelete")
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Verify file contents
                getFileContents(TEST_FILE).hasContents(spec -> TEST_CONTENT.getBytes(StandardCharsets.UTF_8))));
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HBAR),
                newKeyNamed(fileKey).shape(KeyShape.SIMPLE),
                newKeyNamed(newFileKey).shape(KeyShape.SIMPLE),
                newKeyListNamed("keyList", List.of(fileKey)),
                newKeyListNamed("newKeyList", List.of(newFileKey)),
                // For Multiple keys
                newKeyNamed(firstFileSignerKey).shape(KeyShape.SIMPLE),
                newKeyNamed(secondFileSignerKey).shape(KeyShape.SIMPLE),
                newKeyListNamed("MultipleKeyList", List.of(firstFileSignerKey, secondFileSignerKey)),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER_INSUFFICIENT_BALANCE).balance(0L));
    }
}

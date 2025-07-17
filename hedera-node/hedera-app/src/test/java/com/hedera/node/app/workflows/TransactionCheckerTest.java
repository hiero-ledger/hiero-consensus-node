// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_PREFIX_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_HAS_UNKNOWN_FIELDS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_ID_FIELD_NOT_ALLOWED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Signature;
import com.hedera.hapi.node.base.SignatureList;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

final class TransactionCheckerTest extends AppTestBase {
    private static final int MAX_TX_SIZE = 1024 * 6;
    private static final int MAX_JUMBO_TX_SIZE = 1024 * 130;
    private static final long MAX_DURATION = 120L;
    private static final long MIN_DURATION = 10L;
    private static final int MIN_VALIDITY_BUFFER = 2;
    /** Value for {@link TransactionBody#memo()} for most tests */
    private static final Bytes CONTENT = Bytes.wrap("Hello world!");
    /** The standard {@link TransactionBody#transactionValidDuration()} for most tests */
    private static final Duration ONE_MINUTE = Duration.newBuilder().seconds(60).build();

    private ConfigProvider props;

    private final int maxBytes = MAX_TX_SIZE;

    private Transaction tx;
    private SignatureMap signatureMap;
    private SignedTransaction signedTx;
    private TransactionBody txBody;
    private Bytes inputBuffer;

    private TransactionChecker checker;

    private TransactionID.Builder txIdBuilder() {
        return TransactionID.newBuilder()
                .accountID(asAccount("0.0.1024"))
                .transactionValidStart(asTimestamp(Instant.now()));
    }

    private TransactionBody.Builder bodyBuilder(TransactionID.Builder txId) {
        return bodyBuilder(txId.build());
    }

    private TransactionBody.Builder bodyBuilder(TransactionID txId) {
        // An empty transaction body data. It isn't actually valid (i.e. the service implementation
        // would fail this empty transaction), but it is enough to test the checker.
        final var content = ConsensusCreateTopicTransactionBody.newBuilder().build();
        return TransactionBody.newBuilder()
                .consensusCreateTopic(content)
                .transactionID(txId)
                .nodeAccountID(nodeSelfAccountId)
                .transactionValidDuration(ONE_MINUTE)
                .memo(CONTENT.asUtf8String());
    }

    private SignatureMap.Builder sigMapBuilder() {
        final var sigPair = SignaturePair.newBuilder()
                .pubKeyPrefix(Bytes.wrap(new byte[] {1, 2, 3, 4, 5}))
                .ed25519(randomBytes(64))
                .build();
        final var sigPair2 = SignaturePair.newBuilder()
                .pubKeyPrefix(Bytes.wrap(new byte[] {1, 2, 7}))
                .ed25519(randomBytes(64))
                .build();
        return SignatureMap.newBuilder().sigPair(sigPair, sigPair2);
    }

    private SignedTransaction.Builder signedTxBuilder(TransactionBody.Builder txBody, SignatureMap.Builder sigMap) {
        return signedTxBuilder(txBody.build(), sigMap.build());
    }

    private SignedTransaction.Builder signedTxBuilder(TransactionBody txBody, SignatureMap sigMap) {
        // Create the signed transaction object. We hold a reference to it to make sure after
        // we parseStrict the object we get the same thing back.
        final var bodyBytes = asBytes(TransactionBody.PROTOBUF, txBody);
        return SignedTransaction.newBuilder().bodyBytes(bodyBytes).sigMap(sigMap);
    }

    private Transaction.Builder txBuilder(SignedTransaction.Builder signedTx) {
        return txBuilder(signedTx.build());
    }

    private Transaction.Builder txBuilder(SignedTransaction signedTx) {
        final var signedTransactionBytes = asBytes(SignedTransaction.PROTOBUF, signedTx);
        return Transaction.newBuilder().signedTransactionBytes(signedTransactionBytes);
    }

    /**
     * For these tests, we will create an actual transaction and properly convert it to serialized
     * protobuf bytes. The {@link TransactionChecker} will deserialize the bytes, and we need to make
     * sure it does so correctly!
     */
    @BeforeEach
    void setup() {
        txBody = bodyBuilder(txIdBuilder()).build();
        signatureMap = sigMapBuilder().build();
        signedTx = signedTxBuilder(txBody, signatureMap).build();
        tx = txBuilder(signedTx).build();
        inputBuffer = Bytes.wrap(asByteArray(tx));

        // Set up the properties
        props = () -> new VersionedConfigImpl(
                HederaTestConfigBuilder.create()
                        .withValue("hedera.transaction.minValidityBufferSecs", MIN_VALIDITY_BUFFER)
                        .withValue("hedera.transaction.minValidDuration", MIN_DURATION)
                        .withValue("hedera.transaction.maxValidDuration", MAX_DURATION)
                        .withValue("hedera.transaction.maxBytes", MAX_TX_SIZE)
                        .getOrCreateConfig(),
                1);

        // And create the checker itself
        checker = new TransactionChecker(nodeSelfAccountId, props, metrics);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTest {
        @Test
        @SuppressWarnings("ConstantConditions")
        @DisplayName("Constructor throws on illegal arguments")
        void testConstructorWithIllegalArguments() {
            assertThatThrownBy(() -> new TransactionChecker(null, props, metrics))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new TransactionChecker(nodeSelfAccountId, null, metrics))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new TransactionChecker(nodeSelfAccountId, props, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * These tests focus on parsing behavior, but not on validity itself.
     */
    @Nested
    @DisplayName("Tests for Parsing")
    class ParseTest {

        @Test
        @SuppressWarnings("ConstantConditions")
        @DisplayName("`parseAndCheck` requires Bytes")
        void parseAndCheck() {
            assertThatThrownBy(() -> checker.parse(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("`parseAndCheck` bytes must have no more than the configured transactionMaxBytes bytes")
        void parseAndCheckWithTooManyBytes() {
            assertThatThrownBy(() -> checker.parseAndCheck(randomBytes(MAX_TX_SIZE + 1), maxBytes))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(TRANSACTION_OVERSIZE));

            // NOTE: I'm going to also try a number of bytes that JUST FITS. But these are not real transaction
            //       bytes, so they will fail to parse. But that is OK, as long as it is not TRANSACTION_OVERSIZE.
            assertThatThrownBy(() -> checker.parseAndCheck(randomBytes(MAX_TX_SIZE), maxBytes))
                    .isInstanceOf(PreCheckException.class)
                    .doesNotHave(responseCode(TRANSACTION_OVERSIZE));
        }

        @Test
        @DisplayName("A transaction with no bytes at all fails")
        void parseAndCheckWithNoBytes() throws PreCheckException {
            // Given a transaction with no bytes at all
            // Then the checker should throw a PreCheckException
            final var transaction = checker.parse(Bytes.EMPTY);
            assertThatThrownBy(() -> checker.check(transaction))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));
        }

        /**
         * This test verifies that, given a valid transaction encoded as bytes, the {@link TransactionChecker} will
         * parse it correctly. The transaction in this case is using the "signed transaction bytes" fields.
         *
         * @throws PreCheckException Not throw by this test if all goes well
         */
        @Test
        @DisplayName("A valid transaction passes parse and check")
        void happyPath() throws PreCheckException {
            // Given a valid serialized transaction, when we parseStrict and check
            final var transaction = checker.parse(inputBuffer);
            final var info = checker.check(transaction);

            // Then the parsed data is as we expected
            assertThat(info.signedTx()).isEqualTo(signedTx);
            assertThat(info.txBody()).isEqualTo(txBody);
            assertThat(info.signatureMap()).isEqualTo(signatureMap);
            assertThat(info.functionality()).isEqualTo(CONSENSUS_CREATE_TOPIC);
            // And neither deprecation counter has been incremented
            assertThat(counterMetric("DeprTxnsRcv").get()).isZero();
            assertThat(counterMetric("SuperDeprTxnsRcv").get()).isZero();
        }

        /**
         * This test is the same as {@link #happyPath()} except that instead of
         * using "signed transaction bytes" in the transaction, it uses the deprecated fields.
         *
         * @throws PreCheckException Not throw by this test if all goes well
         */
        @Test
        @DisplayName("A transaction with deprecated fields passes parse and check")
        void happyDeprecatedPath() throws PreCheckException {
            // Given a transaction using the deprecated fields
            final var localTx = Transaction.newBuilder()
                    .bodyBytes(signedTx.bodyBytes())
                    .sigMap(signedTx.sigMap())
                    .build();
            final var repackagedSignedTx = SignedTransaction.newBuilder()
                    .bodyBytes(localTx.bodyBytes())
                    .sigMap(localTx.sigMap())
                    .useSerializedTxMessageHashAlgorithm(true)
                    .build();
            inputBuffer = Bytes.wrap(asByteArray(localTx));

            // When we parseStrict and check
            final var transaction = checker.parse(inputBuffer);
            final var info = checker.check(transaction);

            // Then everything works because the deprecated fields are supported
            assertThat(info.signedTx()).isEqualTo(repackagedSignedTx);
            assertThat(info.txBody()).isEqualTo(txBody);
            assertThat(info.signatureMap()).isEqualTo(signatureMap);
            assertThat(info.functionality()).isEqualTo(CONSENSUS_CREATE_TOPIC);

            // And the deprecation counter has been incremented
            assertThat(counterMetric("DeprTxnsRcv").get()).isEqualTo(1);
            // But the super deprecation counter has not
            assertThat(counterMetric("SuperDeprTxnsRcv").get()).isZero();
        }

        @Test
        @DisplayName("A transaction with super deprecated fields alone will throw")
        @SuppressWarnings("deprecation")
        void parseAndCheckWithSuperDeprecatedFields() throws PreCheckException {
            // Given a transaction using the super deprecated fields
            final var sig = Signature.newBuilder().ed25519(randomBytes(64)).build();
            final var localTx = Transaction.newBuilder()
                    .body(txBody)
                    .sigs(SignatureList.newBuilder().sigs(sig).build())
                    .build();
            inputBuffer = Bytes.wrap(asByteArray(localTx));

            // When we check, then we get a PreCheckException with INVALID_TRANSACTION_BODY
            final var transaction = checker.parse(inputBuffer);
            assertThatThrownBy(() -> checker.check(transaction))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));

            // And the super deprecation counter has been incremented
            assertThat(counterMetric("SuperDeprTxnsRcv").get()).isEqualTo(1);
            // But the deprecation counter has not
            assertThat(counterMetric("DeprTxnsRcv").get()).isZero();
        }

        @Test
        @DisplayName("If the transaction bytes are not valid protobuf, it will fail")
        void badTransactionProtobuf() {
            // Given an invalid protobuf message
            inputBuffer = Bytes.wrap(invalidProtobuf());

            // When we parse and check, then the parsing fails because this is an INVALID_TRANSACTION
            assertThatThrownBy(() -> checker.parse(inputBuffer))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION));
        }

        @Test
        @DisplayName("If the transaction protobuf has unknown fields, then fail")
        void unknownFieldInTransaction() {
            // Given a valid protobuf but with an unknown field
            inputBuffer = Bytes.wrap(appendUnknownField(asByteArray(tx)));

            // When we parse and check, then the parsing fails because has unknown fields
            assertThatThrownBy(() -> checker.parse(inputBuffer))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(TRANSACTION_HAS_UNKNOWN_FIELDS));
        }

        @Test
        void doesNotPassIfMoreThenMaxJumboSizeWithEnabledJumbo() {
            // Enabled jumbo transactions
            props = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("jumboTransactions.isEnabled", true)
                            .getOrCreateConfig(),
                    1);

            checker = new TransactionChecker(nodeSelfAccountId, props, metrics);

            int maxJumboTxnSize = props.getConfiguration()
                    .getConfigData(JumboTransactionsConfig.class)
                    .maxTxnSize();

            // assert that passing more than maxJumboTxnSize will fail
            assertThatThrownBy(() -> checker.parseAndCheck(randomBytes(maxJumboTxnSize + 1), maxBytes))
                    .isInstanceOf(PreCheckException.class)
                    .is(responseCode(TRANSACTION_OVERSIZE));
        }

        @Test
        void passedWithMoreThen6KbWithJumboEnabled() {
            // Enabled jumbo transactions
            props = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("jumboTransactions.isEnabled", true)
                            .getOrCreateConfig(),
                    1);

            checker = new TransactionChecker(nodeSelfAccountId, props, metrics);

            // assert that even if we are sending a transaction with more than 6KB,
            // it will not fail with TRANSACTION_OVERSIZE
            assertThatThrownBy(() -> checker.parseAndCheck(randomBytes(MAX_TX_SIZE + 1), MAX_JUMBO_TX_SIZE))
                    .isInstanceOf(PreCheckException.class)
                    .isNot(responseCode(TRANSACTION_OVERSIZE));
        }
    }

    @Nested
    @DisplayName("Test jumbo transaction body")
    class CheckJumboTransactionBody {

        @Test
        void happyPath() {
            props = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("jumboTransactions.isEnabled", true)
                            .withValue("jumboTransactions.maxTxnSize", 1024 * 10) // 10 KB
                            .withValue("hedera.transaction.maxBytes", 1024 * 6) // 6 KB
                            .getOrCreateConfig(),
                    1);

            checker = new TransactionChecker(nodeSelfAccountId, props, metrics);

            final var maxJumboEthereumCallDataSize = props.getConfiguration()
                    .getConfigData(JumboTransactionsConfig.class)
                    .ethereumMaxCallDataSize();

            TransactionInfo txInfo = mock(TransactionInfo.class);
            when(txInfo.signedTx())
                    .thenReturn(SignedTransaction.newBuilder()
                            .bodyBytes(Bytes.wrap(new byte[maxJumboEthereumCallDataSize]))
                            .build());
            when(txInfo.functionality()).thenReturn(HederaFunctionality.ETHEREUM_TRANSACTION);

            var transactionBodyMock = mock(TransactionBody.class);
            when(txInfo.txBody()).thenReturn(transactionBodyMock);
            when(transactionBodyMock.hasEthereumTransaction()).thenReturn(true);

            var mockEthTransactionBody = mock(EthereumTransactionBody.class);
            when(transactionBodyMock.ethereumTransaction()).thenReturn(mockEthTransactionBody);
            when(mockEthTransactionBody.ethereumData()).thenReturn(Bytes.wrap(new byte[maxJumboEthereumCallDataSize]));

            assertDoesNotThrow(() -> checker.checkJumboTransactionBody(txInfo));
        }

        @Test
        void withEnabledJumboSizeBiggerThenMaxTxnSizeWithNotSupportedFunctionality() {
            props = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("jumboTransactions.isEnabled", true)
                            .withValue("jumboTransactions.maxTxnSize", 1024 * 10) // 10 KB
                            .withValue("hedera.transaction.maxBytes", 1024 * 6) // 6 KB
                            .getOrCreateConfig(),
                    1);

            checker = new TransactionChecker(nodeSelfAccountId, props, metrics);

            TransactionInfo txInfo = mock(TransactionInfo.class);
            when(txInfo.signedTx())
                    .thenReturn(SignedTransaction.newBuilder()
                            .bodyBytes(Bytes.wrap(new byte[1024 * 7]))
                            .build()); // 7 KB
            when(txInfo.functionality()).thenReturn(HederaFunctionality.TOKEN_MINT);

            assertThrows(PreCheckException.class, () -> checker.checkJumboTransactionBody(txInfo));
        }
    }

    /**
     * These tests and nested tests cover all the validation checks EXCEPT FOR bytes length, which was
     * covered in the {@link ParseTest} tests.
     */
    @Nested
    @DisplayName("Check Tests")
    class CheckTest {
        @Test
        @SuppressWarnings("ConstantConditions")
        @DisplayName("`check` requires a transaction")
        void checkWithNull() {
            assertThatThrownBy(() -> checker.check(null)).isInstanceOf(NullPointerException.class);
        }

        @Nested
        @DisplayName("Happy Paths")
        class HappyPaths {
            /**
             * This test verifies that, given a valid transaction, the {@link TransactionChecker} will succeed in
             * checking a valid transaction.
             *
             * @throws PreCheckException Not throw by this test if all goes well
             */
            @Test
            @DisplayName("A valid transaction passes parseAndCheck with a BufferedData")
            void happyPath() throws PreCheckException {
                // Given a valid serialized transaction, when we parse and check
                final var info = checker.check(tx);

                // Then the parsed data is as we expected
                assertThat(info.signedTx()).isEqualTo(signedTx);
                assertThat(info.txBody()).isEqualTo(txBody);
                assertThat(info.signatureMap()).isEqualTo(signatureMap);
                assertThat(info.functionality()).isEqualTo(CONSENSUS_CREATE_TOPIC);
                // And neither deprecation counter has been incremented
                assertThat(counterMetric("DeprTxnsRcv").get()).isZero();
                assertThat(counterMetric("SuperDeprTxnsRcv").get()).isZero();
            }

            /**
             * This test is the same as {@link #happyPath()} but with deprecated fields.
             *
             * @throws PreCheckException Not throw by this test if all goes well
             */
            @Test
            @DisplayName("A transaction with deprecated fields passes check")
            void happyWithDeprecatedFields() throws PreCheckException {
                // Given a transaction using the deprecated fields
                final var localTx = Transaction.newBuilder()
                        .bodyBytes(signedTx.bodyBytes())
                        .sigMap(signedTx.sigMap())
                        .build();
                final var repackagedSignedTx = SignedTransaction.newBuilder()
                        .bodyBytes(localTx.bodyBytes())
                        .sigMap(localTx.sigMap())
                        .useSerializedTxMessageHashAlgorithm(true)
                        .build();

                // When we parse and check
                final var info = checker.check(localTx);

                // Then everything works because the deprecated fields are supported
                assertThat(info.signedTx()).isEqualTo(repackagedSignedTx);
                assertThat(info.txBody()).isEqualTo(txBody);
                assertThat(info.signatureMap()).isEqualTo(signatureMap);
                assertThat(info.functionality()).isEqualTo(CONSENSUS_CREATE_TOPIC);

                // And the deprecation counter has been incremented
                assertThat(counterMetric("DeprTxnsRcv").get()).isEqualTo(1);
                // But the super deprecation counter has not
                assertThat(counterMetric("SuperDeprTxnsRcv").get()).isZero();
            }

            @Test
            @DisplayName("A transaction with super deprecated fields alone will throw")
            @SuppressWarnings("deprecation")
            void happyWithSuperDeprecatedFields() {
                // Given a transaction using the super deprecated fields
                final var sig = Signature.newBuilder().ed25519(randomBytes(64)).build();
                final var localTx = Transaction.newBuilder()
                        .body(txBody)
                        .sigs(SignatureList.newBuilder().sigs(sig).build())
                        .build();

                // When we check, then we get a PreCheckException with INVALID_TRANSACTION_BODY
                assertThatThrownBy(() -> checker.check(localTx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(INVALID_TRANSACTION_BODY));

                // And the super deprecation counter has been incremented
                assertThat(counterMetric("SuperDeprTxnsRcv").get()).isEqualTo(1);
                // But the deprecation counter has not
                assertThat(counterMetric("DeprTxnsRcv").get()).isZero();
            }

            @Test
            @DisplayName(
                    "A transaction with super deprecated fields and signedTransactionBytes ignores super deprecated fields")
            @SuppressWarnings("deprecation")
            void checkWithSuperDeprecatedFieldsAndSignedTransactionBytes() throws PreCheckException {
                // Given a transaction using the super deprecated fields and signedTransactionBytes
                final var sig = Signature.newBuilder().ed25519(randomBytes(64)).build();
                final var localTx = Transaction.newBuilder()
                        .body(txBody)
                        .sigs(SignatureList.newBuilder().sigs(sig).build())
                        .signedTransactionBytes(asBytes(SignedTransaction.PROTOBUF, signedTx))
                        .build();

                // When we check
                final var info = checker.check(localTx);
                // Then the parsed data is as we expected
                assertThat(info.signedTx()).isEqualTo(signedTx);
                assertThat(info.txBody()).isEqualTo(txBody);
                assertThat(info.signatureMap()).isEqualTo(signatureMap);
                assertThat(info.functionality()).isEqualTo(CONSENSUS_CREATE_TOPIC);
                // And the super-deprecated counter is incremented, but not the deprecated counter
                assertThat(counterMetric("DeprTxnsRcv").get()).isZero();
                assertThat(counterMetric("SuperDeprTxnsRcv").get()).isEqualTo(1);
            }

            @Test
            @DisplayName(
                    "A transaction with super deprecated fields and deprecated fields ignores super deprecated fields")
            @SuppressWarnings("deprecation")
            void checkWithSuperDeprecatedFieldsAndDeprecatedFields() throws PreCheckException {
                // Given a transaction using the super deprecated fields and signedTransactionBytes
                final var sig = Signature.newBuilder().ed25519(randomBytes(64)).build();
                final var localTx = Transaction.newBuilder()
                        .body(txBody)
                        .sigs(SignatureList.newBuilder().sigs(sig).build())
                        .bodyBytes(asBytes(TransactionBody.PROTOBUF, txBody))
                        .sigMap(signatureMap)
                        .build();
                final var repackagedSignedTx = SignedTransaction.newBuilder()
                        .bodyBytes(localTx.bodyBytes())
                        .sigMap(localTx.sigMap())
                        .useSerializedTxMessageHashAlgorithm(true)
                        .build();

                // When we check
                final var info = checker.check(localTx);
                // Then the parsed data is as we expected
                assertThat(info.signedTx()).isEqualTo(repackagedSignedTx);
                assertThat(info.txBody()).isEqualTo(txBody);
                assertThat(info.signatureMap()).isEqualTo(signatureMap);
                assertThat(info.functionality()).isEqualTo(CONSENSUS_CREATE_TOPIC);
                // And the super-deprecated counter is incremented, and also the deprecated counter
                assertThat(counterMetric("DeprTxnsRcv").get()).isEqualTo(1);
                assertThat(counterMetric("SuperDeprTxnsRcv").get()).isEqualTo(1);
            }
        }

        @Nested
        @DisplayName("Deprecated Fields tests")
        class DeprecatedFields {
            @Test
            @DisplayName("A transaction using both signed bytes and body bytes is invalid")
            void badTransactionWithSignedBytesAndBodyBytes() {
                // Given a transaction using both signed bytes and body bytes
                final var tx = Transaction.newBuilder()
                        .signedTransactionBytes(CONTENT)
                        .bodyBytes(CONTENT)
                        .build();

                // When we check the transaction, then we find it is invalid
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(INVALID_TRANSACTION));

                // And the deprecation counter is incremented, but not the super-deprecation counter
                assertThat(counterMetric("DeprTxnsRcv").get()).isEqualTo(1);
                assertThat(counterMetric("SuperDeprTxnsRcv").get()).isZero();
            }

            @Test
            @DisplayName("A transaction using both signed bytes and sig map is invalid")
            void badTransactionWithSignedBytesAndSigMap() {
                // Given a transaction using both signed bytes (new style) and sig map (deprecated style)
                final var signatureMap = SignatureMap.newBuilder().build();
                final var tx = Transaction.newBuilder()
                        .signedTransactionBytes(CONTENT)
                        .sigMap(signatureMap)
                        .build();

                // Then the checker should throw a PreCheckException
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(INVALID_TRANSACTION));

                // And the deprecation counter is incremented, but not the super-deprecation counter
                assertThat(counterMetric("DeprTxnsRcv").get()).isEqualTo(1);
                assertThat(counterMetric("SuperDeprTxnsRcv").get()).isZero();
            }
        }

        @Nested
        @DisplayName("Signing Key  Mismatch Tests")
        final class MismatchTests {

            static Stream<Arguments> badPrefixesInSigMap() {
                return Stream.of(
                        Arguments.of(Named.of("Two empty prefixes", (Object) new byte[][] {{}, {}})),
                        Arguments.of(Named.of("Duplicate prefixes", (Object) new byte[][] {{1, 2, 3}, {1, 2, 3}})),
                        Arguments.of(Named.of("Duplicate prefixes with unique prefix between", (Object)
                                new byte[][] {{1, 2, 3}, {7, 8, 9}, {1, 2, 3}})),
                        Arguments.of(Named.of("Unique prefix followed by two duplicate prefixes", (Object)
                                new byte[][] {{7, 8, 9}, {1, 2, 3}, {1, 2, 3}})),
                        Arguments.of(Named.of("Duplicate prefixes followed by a unique prefix", (Object)
                                new byte[][] {{1, 2, 3}, {1, 2, 3}, {7, 8, 9}})),
                        Arguments.of(Named.of(
                                "Prefix P followed by a prefix of P", (Object) new byte[][] {{1, 2, 3}, {1, 2}})),
                        Arguments.of(Named.of("Prefix P followed by unique prefix followed by prefix of P", (Object)
                                new byte[][] {{1, 2, 3}, {6, 7, 8}, {1, 2}})),
                        Arguments.of(Named.of(
                                "Prefix P preceded by a prefix of P", (Object) new byte[][] {{1, 2}, {1, 2, 3}})),
                        Arguments.of(Named.of("Little prefix of prefix P, unique prefix, then prefix P", (Object)
                                new byte[][] {{1, 2}, {6, 7, 8}, {1, 2, 3}})),
                        Arguments.of(Named.of(
                                "Empty Prefix followed by non-empty Prefix", (Object) new byte[][] {{}, {6, 7, 8}})));
            }

            @ParameterizedTest
            @DisplayName("Duplicate Prefixes and Prefixes of Prefixes")
            @MethodSource("badPrefixesInSigMap")
            void badPrefixes(byte[]... prefixes) {
                // Given a signature map with some prefixes that are identical
                final var localSignatureMap = SignatureMap.newBuilder()
                        .sigPair(Arrays.stream(prefixes)
                                .map(prefix -> SignaturePair.newBuilder()
                                        .pubKeyPrefix(Bytes.wrap(prefix))
                                        .ed25519(randomBytes(64))
                                        .build())
                                .collect(toList()))
                        .build();
                final var localTx =
                        txBuilder(signedTxBuilder(txBody, localSignatureMap)).build();

                // When we check the transaction, we find it is invalid due to duplicate prefixes
                assertThatThrownBy(() -> checker.check(localTx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(KEY_PREFIX_MISMATCH));
            }
        }

        @Nested
        @DisplayName("Validate the signed transaction (if available)")
        class ValidateSignedTransactionTest {
            @Test
            @DisplayName("If the signed transaction bytes are not valid protobuf, it will fail")
            void badSignedTransactionProtobuf() {
                // Given an invalid protobuf message
                final var localTx = Transaction.newBuilder()
                        .signedTransactionBytes(Bytes.wrap(invalidProtobuf()))
                        .build();

                // When we parse and check, then the parsing fails because this is an INVALID_TRANSACTION
                assertThatThrownBy(() -> checker.check(localTx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(INVALID_TRANSACTION));
            }

            @Test
            @DisplayName("If the signed transaction protobuf has unknown fields, then fail")
            void unknownFieldInSignedTransaction() {
                // Given a valid protobuf but with an unknown field
                final var badSignedTxBytes = appendUnknownField(SignedTransaction.PROTOBUF, signedTx);
                tx = Transaction.newBuilder()
                        .signedTransactionBytes(badSignedTxBytes)
                        .build();

                // When we parse and check, then the parsing fails because has unknown fields
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(TRANSACTION_HAS_UNKNOWN_FIELDS));
            }
        }

        @Nested
        @DisplayName("Validate TransactionBody")
        class ValidateTransactionBodyTest {
            @Test
            @DisplayName("If the transaction body bytes are not valid protobuf, it will fail")
            void badTransactionBodyProtobuf() {
                // Given an invalid protobuf message
                signedTx = SignedTransaction.newBuilder()
                        .bodyBytes(Bytes.wrap(invalidProtobuf()))
                        .sigMap(signatureMap)
                        .build();

                final var signedTransactionBytes = asBytes(SignedTransaction.PROTOBUF, signedTx);
                tx = Transaction.newBuilder()
                        .signedTransactionBytes(signedTransactionBytes)
                        .build();

                // When we parse and check, then the parsing fails because has unknown fields
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(INVALID_TRANSACTION_BODY));
            }

            @Test
            @DisplayName("If the transaction body protobuf has unknown fields, then fail")
            void unknownFieldInTransactionBody() {
                // Given a valid protobuf but with an unknown field
                final var badBodyBytes = appendUnknownField(TransactionBody.PROTOBUF, txBody);
                signedTx = SignedTransaction.newBuilder()
                        .bodyBytes(badBodyBytes)
                        .sigMap(signatureMap)
                        .build();

                final var signedTransactionBytes = asBytes(SignedTransaction.PROTOBUF, signedTx);
                tx = Transaction.newBuilder()
                        .signedTransactionBytes(signedTransactionBytes)
                        .build();

                // When we parse and check, then the parsing fails because this is an TRANSACTION_HAS_UNKNOWN_FIELDS
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(TRANSACTION_HAS_UNKNOWN_FIELDS));
            }

            @Test
            @DisplayName("A transaction body must have a transaction ID")
            void testCheckTransactionBodyWithoutTransactionIDFails() {
                // Given a transaction body without a transaction ID
                final var body = bodyBuilder((TransactionID) null);
                final var tx = txBuilder(signedTxBuilder(body, sigMapBuilder())).build();

                // Then the checker should throw a PreCheckException
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(INVALID_TRANSACTION_ID));
            }

            @Test
            @DisplayName("Aliased Payer accountID should be rejected")
            void testCheckTransactionBodyWithAliasAsPayer() throws PreCheckException {
                // Given a transaction ID with an alias as the payer
                final var payerId =
                        AccountID.newBuilder().alias(Bytes.wrap("alias")).build();
                final var body = bodyBuilder(txIdBuilder().accountID(payerId));
                final var tx = txBuilder(signedTxBuilder(body, sigMapBuilder())).build();

                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(PAYER_ACCOUNT_NOT_FOUND));
            }

            @ParameterizedTest
            @ValueSource(longs = {0L, -1L})
            @DisplayName("A transaction ID with an impossible account number fails")
            void testCheckTransactionBodyWithZeroAccountNumFails(long account) {
                // Given a transaction ID with an account number that is not valid (0 is not a valid number)
                final var payerId = AccountID.newBuilder().accountNum(account).build();
                final var body = bodyBuilder(txIdBuilder().accountID(payerId));
                final var tx = txBuilder(signedTxBuilder(body, sigMapBuilder())).build();

                // Then the checker should throw a PreCheckException
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(PAYER_ACCOUNT_NOT_FOUND));
            }

            @ParameterizedTest
            @ValueSource(longs = {1L, -1L})
            @DisplayName("A transaction ID with the wrong shard fails")
            void testCheckTransactionBodyWithBadShardFails(long shard) {
                // Given a transaction ID with an account number that is not valid (0 is not a valid number)
                final var payerId =
                        AccountID.newBuilder().shardNum(shard).accountNum(10L).build();
                final var body = bodyBuilder(txIdBuilder().accountID(payerId));
                final var tx = txBuilder(signedTxBuilder(body, sigMapBuilder())).build();

                // Then the checker should throw a PreCheckException
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(PAYER_ACCOUNT_NOT_FOUND));
            }

            @ParameterizedTest
            @ValueSource(longs = {1L, -1L})
            @DisplayName("A transaction ID with the wrong realm fails")
            void testCheckTransactionBodyWithBadRealmFails(long realm) {
                // Given a transaction ID with an account number that is not valid (0 is not a valid number)
                final var payerId =
                        AccountID.newBuilder().realmNum(realm).accountNum(10L).build();
                final var body = bodyBuilder(txIdBuilder().accountID(payerId));
                final var tx = txBuilder(signedTxBuilder(body, sigMapBuilder())).build();

                // Then the checker should throw a PreCheckException
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(PAYER_ACCOUNT_NOT_FOUND));
            }

            @Test
            @DisplayName("A scheduled transaction should fail")
            void testScheduledTransactionFails() {
                final var body = bodyBuilder(txIdBuilder().scheduled(true));
                final var tx = txBuilder(signedTxBuilder(body, sigMapBuilder())).build();

                // Then the checker should throw a PreCheckException
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(TRANSACTION_ID_FIELD_NOT_ALLOWED));
            }

            @Test
            @DisplayName("An internal transaction should fail")
            void testInternalTransactionFails() {
                final var body = bodyBuilder(txIdBuilder().nonce(1));
                final var tx = txBuilder(signedTxBuilder(body, sigMapBuilder())).build();

                // Then the checker should throw a PreCheckException
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(TRANSACTION_ID_FIELD_NOT_ALLOWED));
            }

            @ParameterizedTest
            @ValueSource(
                    longs = {
                        /*0L,*/
                        -1L
                    }) // QUESTION: What about 0 fee?
            @DisplayName("A transaction fee that is less than 0 is completely implausible")
            void testCheckTransactionBodyWithInvalidFeeFails(final long fee) {
                // Given a transaction body with a negative fee
                final var body = bodyBuilder(txIdBuilder()).transactionFee(fee);
                final var tx = txBuilder(signedTxBuilder(body, sigMapBuilder())).build();

                // When we check the transaction body
                assertThatThrownBy(() -> checker.check(tx))
                        .isInstanceOf(PreCheckException.class)
                        .hasFieldOrPropertyWithValue("responseCode", INSUFFICIENT_TX_FEE);
            }
        }

        @Nested
        @DisplayName("Validate timebox")
        class ValidateTimebox {

            @Test
            @DisplayName("A transaction body with less duration than the minimum will simply fail")
            void testCheckTransactionBodyWithTooSmallDurationFails() {
                // Given a transaction body with a duration that is too small
                final var duration =
                        Duration.newBuilder().seconds(MIN_DURATION - 1).build();
                final var body = bodyBuilder(txIdBuilder())
                        .transactionValidDuration(duration)
                        .build();
                final var consensusNow = Instant.now();

                // When we check the transaction body
                assertThatThrownBy(() -> checker.checkTimeBox(
                                body, consensusNow, TransactionChecker.RequireMinValidLifetimeBuffer.YES))
                        .isInstanceOf(PreCheckException.class)
                        .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_DURATION);
            }

            @Test
            @DisplayName("A transaction body with a longer duration than the maximum will simply fail")
            void testCheckTransactionBodyWithTooLargeDurationFails() {
                // Given a transaction body with a duration that is too large
                final var duration =
                        Duration.newBuilder().seconds(MAX_DURATION + 1).build();
                final var body = bodyBuilder(txIdBuilder())
                        .transactionValidDuration(duration)
                        .build();
                final var consensusNow = Instant.now();

                // When we check the transaction body
                assertThatThrownBy(() -> checker.checkTimeBox(
                                body, consensusNow, TransactionChecker.RequireMinValidLifetimeBuffer.YES))
                        .isInstanceOf(PreCheckException.class)
                        .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_DURATION);
            }

            @Test
            void testCheckTransactionBodyWithExpiredTimeFails() {
                // Given a transaction body who's valid start time is in the past
                final var consensusNow = Instant.now();
                final var past = Timestamp.newBuilder()
                        .seconds(consensusNow.getEpochSecond() - 100)
                        .build();
                final var txId = txIdBuilder().transactionValidStart(past);
                final var body = bodyBuilder(txId).build();

                // When we check the transaction body
                assertThatThrownBy(() -> checker.checkTimeBox(
                                body, consensusNow, TransactionChecker.RequireMinValidLifetimeBuffer.YES))
                        .isInstanceOf(PreCheckException.class)
                        .hasFieldOrPropertyWithValue("responseCode", TRANSACTION_EXPIRED);
            }

            @Test
            void testCheckTransactionBodyWithFutureStartFails() {
                // Given a transaction body who's valid start time is in the future
                final var consensusNow = Instant.now();
                final var future = Timestamp.newBuilder()
                        .seconds(consensusNow.getEpochSecond() + 100)
                        .build();
                final var txId = txIdBuilder().transactionValidStart(future);
                final var body = bodyBuilder(txId).build();

                // When we check the transaction body
                assertThatThrownBy(() -> checker.checkTimeBox(
                                body, consensusNow, TransactionChecker.RequireMinValidLifetimeBuffer.YES))
                        .isInstanceOf(PreCheckException.class)
                        .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_START);
            }
        }

        @Nested
        @DisplayName("Unknown Transaction Type")
        class CreateTransactionBodyTest {
            @Test
            @DisplayName("If we ever cannot determine the HederaFunctionality, then we throw an exception")
            void unknownFunctionality() {
                try (MockedStatic<HapiUtils> hapiUtils = mockStatic(HapiUtils.class)) {
                    // Given a HederaFunctionality that is unknown
                    hapiUtils.when(() -> HapiUtils.functionOf(eq(txBody))).thenThrow(new UnknownHederaFunctionality());

                    // When we parse and check, then the parsing fails due to the exception
                    assertThatThrownBy(() -> checker.check(tx))
                            .isInstanceOf(PreCheckException.class)
                            .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION_BODY);
                }
            }
        }
    }

    private byte[] invalidProtobuf() {
        // The first byte is the "tag" and the high order 5 bits are the field number. The low order 3 bits
        // are the wire type. But the field number is 0, which is not valid. And the wire
        // type is 7 which is not a valid wire type. So this is doubly wrong.
        return new byte[] {0b00000111};
    }

    private <T> Bytes appendUnknownField(@NonNull final Codec<T> codec, T tx) {
        final var bytes = asByteArray(codec, tx);
        return Bytes.wrap(appendUnknownField(bytes));
    }

    private byte[] appendUnknownField(@NonNull final byte[] bytes) {
        // We'll take the bytes and append a field number of 255 (which none of our protobuf objects use),
        // which is guaranteed to be a field that we don't know about. When we parse the protobuf,
        // we will encounter an unknown field, and this will allow us to verify that we fail when we
        // parse unknown fields. Since the 255 is varInt encoded, it will take 2 bytes and looks a
        // little wonky.
        final var arr = new byte[bytes.length + 2];
        System.arraycopy(bytes, 0, arr, 0, bytes.length);
        arr[arr.length - 2] = (byte) 0b11111000;
        arr[arr.length - 1] = (byte) 0b00001111;
        return arr;
    }
}

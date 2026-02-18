// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.client;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.clpr.ClprGetLedgerConfigurationQuery;
import org.hiero.hapi.interledger.clpr.ClprGetMessageQueueMetadataQuery;
import org.hiero.hapi.interledger.clpr.ClprGetMessagesQuery;
import org.hiero.hapi.interledger.clpr.ClprProcessMessageBundleTransactionBody;
import org.hiero.hapi.interledger.clpr.ClprServiceInterface;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.clpr.ClprUpdateMessageQueueMetadataTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageBundle;
import org.hiero.interledger.clpr.client.ClprClient;

/**
 * Implementation of the CLPR (Cross-Ledger Protocol) client.
 * <p>
 * This class provides methods to interact with a remote CLPR Endpoint, allowing retrieval and submission of ledger
 * configurations.
 */
public class ClprClientImpl implements ClprClient {

    private static final Logger log = LogManager.getLogger(ClprClientImpl.class);

    private static final PbjGrpcClientConfig clientConfig = new PbjGrpcClientConfig(
            Duration.ofSeconds(1),
            Tls.builder().enabled(false).build(),
            Optional.empty(),
            ServiceInterface.RequestOptions.APPLICATION_GRPC_PROTO);
    private static final ServiceInterface.RequestOptions requestOptions = new ServiceInterface.RequestOptions() {
        @Override
        public @NonNull Optional<String> authority() {
            return Optional.empty();
        }

        @Override
        public @NonNull String contentType() {
            return ServiceInterface.RequestOptions.APPLICATION_GRPC_PROTO;
        }
    };

    final ClprServiceInterface.ClprServiceClient clprServiceClient;
    /**
     * The backing PBJ gRPC client, if this instance created its own client stack.
     * <p>
     * When {@link ClprClientImpl} is constructed with an already-initialized {@link ClprServiceInterface.ClprServiceClient}
     * (for tests), there is nothing to close here.
     */
    @Nullable
    private final PbjGrpcClient pbjGrpcClient;

    private final TransactionSigner signer;

    /**
     * Constructs a ClprClientImpl instance with the specified service endpoint.
     *
     * @param serviceEndpoint the service endpoint to connect to
     * @throws UnknownHostException if the IP address of the service endpoint cannot be determined
     */
    public ClprClientImpl(@NonNull final ServiceEndpoint serviceEndpoint) throws UnknownHostException {
        final String address = resolveAddress(serviceEndpoint);
        final int port = serviceEndpoint.port();

        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + address + ":" + port)
                .tls(Tls.builder().enabled(false).build())
                .build();

        pbjGrpcClient = new PbjGrpcClient(webClient, clientConfig);
        clprServiceClient = new ClprServiceInterface.ClprServiceClient(pbjGrpcClient, requestOptions);
        signer = DevTransactionSignerHolder.signer();
    }

    private static String resolveAddress(@NonNull final ServiceEndpoint serviceEndpoint) throws UnknownHostException {
        final var domainName = serviceEndpoint.domainName();
        if (domainName != null && !domainName.isBlank()) {
            return domainName.trim();
        }
        final var ipAddressV4 = serviceEndpoint.ipAddressV4();
        if (ipAddressV4 == null || ipAddressV4.length() != 4) {
            throw new UnknownHostException("ServiceEndpoint missing usable ipAddressV4 and domainName (ipBytes="
                    + (ipAddressV4 == null ? 0 : ipAddressV4.length())
                    + ")");
        }
        return Inet4Address.getByAddress(ipAddressV4.toByteArray()).getHostAddress();
    }

    /**
     * Constructs a ClprClientImpl instance with the specified CLPR service client.
     *
     * @param clprServiceClient the CLPR service client
     */
    ClprClientImpl(@NonNull final ClprServiceInterface.ClprServiceClient clprServiceClient) {
        this(clprServiceClient, DevTransactionSignerHolder.signer());
    }

    /**
     * Constructs a ClprClientImpl instance with the specified CLPR service client and transaction signer.
     *
     * @param clprServiceClient the CLPR service client
     * @param signer the transaction signer
     */
    ClprClientImpl(
            @NonNull final ClprServiceInterface.ClprServiceClient clprServiceClient,
            @NonNull final TransactionSigner signer) {
        this.clprServiceClient = Objects.requireNonNull(clprServiceClient);
        this.pbjGrpcClient = null;
        this.signer = Objects.requireNonNull(signer);
    }

    @Override
    public StateProof getConfiguration() {
        final var queryBody = ClprGetLedgerConfigurationQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();
        final var queryTxn =
                Query.newBuilder().getClprLedgerConfiguration(queryBody).build();
        final var response = clprServiceClient.getLedgerConfiguration(queryTxn);
        if (response.hasClprLedgerConfiguration()) {
            final var stateProof =
                    Objects.requireNonNull(response.clprLedgerConfiguration()).ledgerConfigurationProof();
            if (stateProof != null) {
                return stateProof;
            }
        }
        return null;
    }

    @Override
    public @Nullable StateProof getConfiguration(@NonNull final ClprLedgerId ledgerId) {
        final var queryBody = ClprGetLedgerConfigurationQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .ledgerId(ledgerId)
                .build();

        final var queryTxn =
                Query.newBuilder().getClprLedgerConfiguration(queryBody).build();
        final var response = clprServiceClient.getLedgerConfiguration(queryTxn);
        if (response.hasClprLedgerConfiguration()) {
            final var stateProof =
                    Objects.requireNonNull(response.clprLedgerConfiguration()).ledgerConfigurationProof();
            if (stateProof != null) {
                return stateProof;
            }
        }
        return null;
    }

    /**
     * Builds and submits a CLPR set-configuration transaction. The method fabricates a PBJ
     * transaction, signs it (using a dev-mode key when available), and synchronously invokes the
     * gRPC method. Any exception is converted into {@link ResponseCodeEnum#FAIL_INVALID} so callers
     * can retry without crashing the endpoint loop.
     *
     * @param payerAccountId the ID of the account paying for the transaction
     * @param nodeAccountId the ID of the node to which the transaction is submitted
     * @param ledgerConfigurationProof the state proof of the ledger configuration
     * @return the response code from the node
     */
    @Override
    public @NonNull ResponseCodeEnum setConfiguration(
            @NonNull final AccountID payerAccountId,
            @NonNull final AccountID nodeAccountId,
            @NonNull final StateProof ledgerConfigurationProof) {
        Objects.requireNonNull(ledgerConfigurationProof);
        try {
            final var txnBody = TransactionBody.newBuilder()
                    .transactionID(newTransactionId(payerAccountId))
                    .clprSetLedgerConfiguration(ClprSetLedgerConfigurationTransactionBody.newBuilder()
                            .ledgerConfigurationProof(ledgerConfigurationProof)
                            .build())
                    .transactionFee(1L)
                    .transactionValidDuration(com.hedera.hapi.node.base.Duration.newBuilder()
                            .seconds(120)
                            .build())
                    .nodeAccountID(nodeAccountId)
                    .build();
            final var bodyBytes = TransactionBody.PROTOBUF.toBytes(txnBody);
            final var signatureMap = signer.sign(txnBody);
            final var signedTransaction = SignedTransaction.newBuilder()
                    .bodyBytes(bodyBytes)
                    .sigMap(signatureMap)
                    .build();
            final var transaction = Transaction.newBuilder()
                    .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(signedTransaction))
                    .build();
            final var response = clprServiceClient.setLedgerConfiguration(transaction);
            return response.nodeTransactionPrecheckCode();
        } catch (final Exception e) {
            log.error("CLPR client failed to submit configuration for payer {}", payerAccountId, e);
            return ResponseCodeEnum.FAIL_INVALID;
        }
    }

    /**
     * Builds and submits a CLPR update-message-queue-metadata transaction. The method fabricates a PBJ
     * transaction, signs it (using a dev-mode key when available), and synchronously invokes the
     * gRPC method. Any exception is converted into {@link ResponseCodeEnum#FAIL_INVALID}.
     *
     * @param payerAccountId the ID of the account paying for the transaction
     * @param nodeAccountId the ID of the node to which the transaction is submitted
     * @param ledgerId the ID of the ledger
     * @param messageQueueMetadataProof the state proof of the message queue metadata
     * @return the response code from the node
     */
    @Override
    public @NonNull ResponseCodeEnum updateMessageQueueMetadata(
            @NonNull AccountID payerAccountId,
            @NonNull AccountID nodeAccountId,
            @NonNull ClprLedgerId ledgerId,
            @NonNull StateProof messageQueueMetadataProof) {
        try {
            final var txnBody = TransactionBody.newBuilder()
                    .transactionID(newTransactionId(payerAccountId))
                    .clprUpdateMessageQueueMetadata(ClprUpdateMessageQueueMetadataTransactionBody.newBuilder()
                            .ledgerId(ledgerId)
                            .messageQueueMetadataProof(messageQueueMetadataProof)
                            .build())
                    .transactionFee(1L)
                    .transactionValidDuration(com.hedera.hapi.node.base.Duration.newBuilder()
                            .seconds(120)
                            .build())
                    .nodeAccountID(nodeAccountId)
                    .build();
            final var bodyBytes = TransactionBody.PROTOBUF.toBytes(txnBody);
            final var signatureMap = signer.sign(txnBody);
            final var signedTransaction = SignedTransaction.newBuilder()
                    .bodyBytes(bodyBytes)
                    .sigMap(signatureMap)
                    .build();
            final var transaction = Transaction.newBuilder()
                    .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(signedTransaction))
                    .build();
            final var response = clprServiceClient.updateMessageQueueMetadata(transaction);
            return response.nodeTransactionPrecheckCode();
        } catch (final Exception e) {
            log.error(
                    "CLPR client failed to submit message queue metadata for payer {} and ledger {}",
                    payerAccountId,
                    ledgerId,
                    e);
            return ResponseCodeEnum.FAIL_INVALID;
        }
    }

    /**
     * Retrieves the message queue metadata state proof for a given ledger.
     *
     * <p>This method queries the CLPR service for the message queue metadata
     * associated with the specified ledger and returns the cryptographic state
     * proof if available.
     *
     * @param ledgerId the ID of the ledger to query
     * @return the state proof containing the message queue metadata, or {@code null}
     *         if the metadata is not available or the ledger does not exist
     */
    @Override
    public @Nullable StateProof getMessageQueueMetadata(@NonNull ClprLedgerId ledgerId) {
        // create query payload with header and specified ledger id
        final var queryBody = ClprGetMessageQueueMetadataQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .ledgerId(ledgerId)
                .build();

        final var queryTxn =
                Query.newBuilder().getClprMessageQueueMetadata(queryBody).build();
        final var response = clprServiceClient.getMessageQueueMetadata(queryTxn);
        if (response.hasClprMessageQueueMetadata()) {
            return response.clprMessageQueueMetadata().messageQueueMetadataProof();
        }
        return null;
    }

    /**
     * Submits a CLPR process-message-bundle transaction.
     *
     * @param payerAccountId the ID of the account paying for the transaction
     * @param nodeAccountId the ID of the node to which the transaction is submitted
     * @param ledgerId the ID of the ledger
     * @param messageBundle the bundle of messages to process
     * @return the response code from the node
     */
    @Override
    public @NonNull ResponseCodeEnum submitProcessMessageBundleTxn(
            @NonNull AccountID payerAccountId,
            @NonNull AccountID nodeAccountId,
            @NonNull ClprLedgerId ledgerId,
            @NonNull ClprMessageBundle messageBundle) {
        try {
            final var txnBody = TransactionBody.newBuilder()
                    .transactionID(newTransactionId(payerAccountId))
                    .clprProcessMessageBundle(ClprProcessMessageBundleTransactionBody.newBuilder()
                            .messageBundle(messageBundle)
                            .build())
                    .transactionFee(1L)
                    .transactionValidDuration(com.hedera.hapi.node.base.Duration.newBuilder()
                            .seconds(120)
                            .build())
                    .nodeAccountID(nodeAccountId)
                    .build();
            final var bodyBytes = TransactionBody.PROTOBUF.toBytes(txnBody);
            final var signatureMap = signer.sign(txnBody);
            final var signedTransaction = SignedTransaction.newBuilder()
                    .bodyBytes(bodyBytes)
                    .sigMap(signatureMap)
                    .build();
            final var transaction = Transaction.newBuilder()
                    .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(signedTransaction))
                    .build();
            final var response = clprServiceClient.processMessageBundle(transaction);
            return response.nodeTransactionPrecheckCode();
        } catch (final Exception e) {
            log.error(
                    "CLPR client failed to submit message bundle for payer {} and ledger {}",
                    payerAccountId,
                    ledgerId,
                    e);
            return ResponseCodeEnum.FAIL_INVALID;
        }
    }

    /**
     * Retrieves a bundle of messages from the ledger.
     *
     * @param ledgerId the ID of the ledger
     * @param maxNumMsg the maximum number of messages to retrieve
     * @param maxNumBytes the maximum number of bytes to retrieve
     * @return the bundle of messages, or null if not available
     */
    @Override
    public @Nullable ClprMessageBundle getMessages(@NonNull ClprLedgerId ledgerId, int maxNumMsg, int maxNumBytes) {
        // create query payload with header and specified ledger id
        final var queryBody = ClprGetMessagesQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .ledgerId(ledgerId)
                .maxBundleBytes(maxNumBytes)
                .maxNumberOfMessages(maxNumMsg)
                .build();

        final var queryTxn = Query.newBuilder().getClprMessages(queryBody).build();
        final var response = clprServiceClient.getMessageBundle(queryTxn);
        if (response.hasClprMessages()) {
            return response.clprMessages().messageBundle();
        }
        return null;
    }

    @Override
    public void close() {
        if (pbjGrpcClient != null) {
            try {
                pbjGrpcClient.close();
            } catch (final Exception e) {
                log.warn("Failed to close CLPR gRPC client", e);
            }
        }
    }

    @NonNull
    private static TransactionID newTransactionId(@NonNull final AccountID payerAccountId) {
        final Instant now = Instant.now();
        final var timestamp = Timestamp.newBuilder()
                .seconds(now.getEpochSecond())
                .nanos(now.getNano())
                .build();
        return TransactionID.newBuilder()
                .accountID(payerAccountId)
                .transactionValidStart(timestamp)
                .build();
    }

    interface TransactionSigner {
        TransactionSigner NO_OP = txnBody -> SignatureMap.DEFAULT;

        @NonNull
        SignatureMap sign(@NonNull TransactionBody txnBody);
    }

    private static final class DevTransactionSignerHolder {
        private static final TransactionSigner SIGNER =
                DevTransactionSigner.load().orElse(TransactionSigner.NO_OP);

        private DevTransactionSignerHolder() {}

        static TransactionSigner signer() {
            return SIGNER;
        }
    }

    /**
     * Dev-only signer that looks for a local private key and produces the single signature required
     * to submit CLPR configuration transactions without full onboarding.
     */
    private static final class DevTransactionSigner implements TransactionSigner {
        private static final Logger devLog = LogManager.getLogger(DevTransactionSigner.class);
        private static final String PEM_RELATIVE_PATH = "data/onboard/devGenesisKeypair.pem";
        private static final String STARTUP_RELATIVE_PATH = "data/onboard/StartUpAccount.txt";
        private static final String HEDERA_NODE_PREFIX = "hedera-node/";
        private static final String DEV_PEM_PASSPHRASE = "passphrase";
        private static final String STARTUP_PRIVATE_KEY_PREFIX = "302e020100300506032b657004220420";

        private final EdDSAPrivateKey privateKey;
        private final Bytes publicKeyBytes;

        private DevTransactionSigner(@NonNull final EdDSAPrivateKey privateKey) {
            this.privateKey = Objects.requireNonNull(privateKey);
            this.publicKeyBytes = Bytes.wrap(privateKey.getAbyte());
        }

        static Optional<TransactionSigner> load() {
            return loadPrivateKey().map(DevTransactionSigner::new);
        }

        @Override
        public @NonNull SignatureMap sign(@NonNull final TransactionBody txnBody) {
            final var bodyBytes = TransactionBody.PROTOBUF.toBytes(txnBody).toByteArray();
            try {
                final var signatureBytes = SignatureGenerator.signBytes(bodyBytes, privateKey);
                final var signaturePair = SignaturePair.newBuilder()
                        .pubKeyPrefix(publicKeyBytes)
                        .ed25519(Bytes.wrap(signatureBytes))
                        .build();
                return SignatureMap.newBuilder().sigPair(signaturePair).build();
            } catch (final Exception e) {
                devLog.warn("CLPR client failed to sign configuration transaction", e);
                return SignatureMap.DEFAULT;
            }
        }

        private static Optional<EdDSAPrivateKey> loadPrivateKey() {
            for (final Path candidate : candidatePaths(PEM_RELATIVE_PATH)) {
                if (Files.isRegularFile(candidate)) {
                    try (final InputStream in = Files.newInputStream(candidate)) {
                        devLog.debug("Loaded CLPR dev-mode key from {}", candidate);
                        return Optional.of(Ed25519Utils.readKeyFrom(in, DEV_PEM_PASSPHRASE));
                    } catch (final Exception e) {
                        devLog.warn("Failed to read dev-mode key from {}", candidate, e);
                    }
                }
            }
            for (final Path candidate : candidatePaths(STARTUP_RELATIVE_PATH)) {
                if (Files.isRegularFile(candidate)) {
                    final Optional<EdDSAPrivateKey> key = readFromStartUpAccount(candidate);
                    if (key.isPresent()) {
                        devLog.debug("Loaded CLPR dev-mode key from {}", candidate);
                        return key;
                    }
                }
            }
            devLog.warn("No dev-mode signing key found; CLPR submissions will use empty signature maps");
            return Optional.empty();
        }

        private static Optional<EdDSAPrivateKey> readFromStartUpAccount(@NonNull final Path path) {
            try {
                final String encoded =
                        Files.readString(path, StandardCharsets.UTF_8).trim();
                final byte[] decoded = Base64.getDecoder().decode(encoded);
                final String hex = toHex(decoded);
                final int idx = hex.indexOf(STARTUP_PRIVATE_KEY_PREFIX);
                if (idx >= 0) {
                    final int start = idx + STARTUP_PRIVATE_KEY_PREFIX.length();
                    if (hex.length() >= start + 64) {
                        final String seedHex = hex.substring(start, start + 64);
                        final var seed = Bytes.fromHex(seedHex).toByteArray();
                        return Optional.of(Ed25519Utils.keyFrom(seed));
                    }
                }
                devLog.warn("Unable to locate private key bytes in StartUpAccount file at {}", path);
            } catch (final IOException | IllegalArgumentException e) {
                devLog.warn("Failed to parse StartUpAccount file at {}", path, e);
            }
            return Optional.empty();
        }

        private static List<Path> candidatePaths(final String relativePath) {
            return List.of(Path.of(relativePath), Path.of(HEDERA_NODE_PREFIX + relativePath));
        }

        private static String toHex(final byte[] data) {
            final StringBuilder sb = new StringBuilder(data.length * 2);
            for (final byte b : data) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}

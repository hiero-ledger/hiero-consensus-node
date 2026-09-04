// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.issues;

import static com.hedera.services.bdd.junit.hedera.SystemFunctionalityTarget.NA;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.getUniqueTimestampPlusSecs;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TransactionGetReceipt;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionGetReceiptQuery;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.security.PrivateKey;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Diagnostic replay of a ContractCreate captured on the BNCE environment that
 * failed precheck with {@code INVALID_TRANSACTION_BODY}.
 *
 * <p>The captured bytes are submitted verbatim through the gRPC stub so the
 * node sees the exact same payload the original client sent. The fully decoded
 * outer {@code Transaction}, inner {@code SignedTransaction}, {@code TransactionBody}
 * and {@code ContractCreateTransactionBody} are logged before submission so we
 * can spot any structural anomaly. The resulting precheck code is logged but
 * not asserted, since the captured {@code valid_start} is now stale.
 */
public class BnceFailingContractCreateReplay {
    private static final Logger log = LogManager.getLogger(BnceFailingContractCreateReplay.class);

    // Captured txnId 0.0.2@1778776968.388857856, node 0.0.3, mirror result INVALID_TRANSACTION_BODY.
    private static final String CAPTURED_HEX = "2aa7020abc010a120a0c0888f797d006108080b6b90112021802120218031880"
            + "a8d6b907220208784297010a0418e090011a2212200aa8e21064c61eab86e2a9"
            + "c164565b4e7a9a4146106e0a6cd03a8c395a110e9220a0c21e42050880ceda03"
            + "4a60000000000000000000000000000000000000000000000000000000000000"
            + "0020000000000000000000000000000000000000000000000000000000000000"
            + "001268656c6c6f2066726f6d2068656465726121000000000000000000000000"
            + "000012660a640a200aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6c"
            + "d03a8c395a110e921a40993960e4cc7904b00188123048bcbefe9fdb65968797"
            + "d84c231ced7769c98edbca8746c4a52f169b4c2932ce259cbff0aaf7be6d4634"
            + "d9b27ca32fb08d668700";

    // The exact bytecode the Swift example uploads on BNCE — HieroExampleUtilities.Resources.simpleContract,
    // i.e. the `object` field of hello-world.json (a "Greeter" with greet() returning "Hello, world!" and a
    // kill() selfdestruct gated to the deployer). 1,110 ASCII hex chars = 555 bytes of raw EVM bytecode.
    // Swift uploads it as bytecode.data(using: .utf8)!, so we feed it to fileCreate(...).contents(String)
    // which stores the same UTF-8 bytes. The handler's initcodeFor() then hex-decodes the file contents
    // and appends the captured constructor parameters before running the result as initcode.
    private static final String SIMPLE_CONTRACT_HEX =
            "608060405234801561001057600080fd5b50336000806101000a81548173ffffffffffffffffffffffffffffffffffffffff021916908373ffffffffffffffffffffffffffffffffffffffff1602179055506101cb806100606000396000f3fe608060405260043610610046576000357c01000000000000000000000000000000000000000000000000000000009004806341c0e1b51461004b578063"
                    + "cfae321714610062575b600080fd5b34801561005757600080fd5b506100606100f2565b005b34801561006e57600080fd5b50610077610162565b6040518080602001828103825283818151815260200191508051906020019080838360005b838110156100b757808201518184015260208101905061009c565b50505050905090810190601f1680156100e45780820380516001836020036101000a"
                    + "031916815260200191505b509250505060405180910390f35b6000809054906101000a900473ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff163373ffffffffffffffffffffffffffffffffffffffff161415610160573373ffffffffffffffffffffffffffffffffffffffff16ff5b565b60606040805190810160405280600d8152602001"
                    + "7f48656c6c6f2c20776f726c64210000000000000000000000000000000000000081525090509056fea165627a7a72305820ae96fb3af7cde9c0abfe365272441894ab717f816f07f41f07b1cbede54e256e0029";

    // Verbatim BNCE node-level application.properties (the file the BNCE consensus node was
    // started with, supplied by the BNCE operators). Some entries here are bootstrap-only
    // (fees.createSimpleFeeSchedule, ledger.id) or require real TSS infrastructure to flip
    // safely (tss.hintsEnabled / tss.historyEnabled / tss.wrapsEnabled / tss.forceMockSignatures),
    // so this exact set may not apply cleanly on a fresh local test network — we still try the
    // override to keep the local config as close to BNCE as possible.
    private static final Map<String, String> BNCE_OVERRIDES = Map.ofEntries(
            Map.entry("ledger.id", "0x03"),
            Map.entry("nodes.gossipFqdnRestricted", "false"),
            Map.entry("fees.simpleFeesEnabled", "true"),
            Map.entry("fees.createSimpleFeeSchedule", "true"),
            Map.entry("staking.perHbarRewardRate", "0"),
            Map.entry("blockStream.writerMode", "GRPC"),
            Map.entry("blockStream.streamMode", "BLOCKS"),
            Map.entry("blockStream.buffer.isBufferPersistenceEnabled", "true"),
            Map.entry("blockStream.enableStateProofs", "true"),
            Map.entry("tss.hintsEnabled", "true"),
            Map.entry("tss.historyEnabled", "true"),
            Map.entry("tss.wrapsEnabled", "true"),
            Map.entry("tss.forceMockSignatures", "false"),
            Map.entry("tss.bootstrapProofKeyGracePeriod", "300s"),
            Map.entry("tss.initialCrsParties", "16"));

    @HapiTest
    final Stream<DynamicTest> replayCapturedContractCreateAndLogPrecheck() {
        return hapiTest(withOpContext((spec, opLog) -> {
            // Apply BNCE's full network-properties override before doing anything else, so the
            // ContractCreate handle path runs under the same config the BNCE node is running.
            // If the failure on BNCE is config-sensitive, this should reproduce it locally.
            log.info("Applying {} BNCE property overrides", BNCE_OVERRIDES.size());
            allRunFor(spec, overridingAllOf(BNCE_OVERRIDES));

            final byte[] raw = HexFormat.of().parseHex(CAPTURED_HEX);
            log.info("=== Replay: captured bytes ({} bytes) ===", raw.length);

            final Transaction txn = parseAsTransaction(raw);

            final SignedTransaction signedTxn = !txn.getSignedTransactionBytes().isEmpty()
                    ? SignedTransaction.parseFrom(txn.getSignedTransactionBytes())
                    : SignedTransaction.getDefaultInstance();

            final ByteString bodyBytes = !signedTxn.getBodyBytes().isEmpty()
                    ? signedTxn.getBodyBytes()
                    : txn.getBodyBytes();
            final TransactionBody body = TransactionBody.parseFrom(bodyBytes);

            log.info("sender: {}", body.getTransactionID().getAccountID());

            log.info("Outer Transaction:\n{}", TextFormat.printer().printToString(txn));
            log.info("SignedTransaction:\n{}", TextFormat.printer().printToString(signedTxn));
            log.info("TransactionBody:\n{}", TextFormat.printer().printToString(body));
            if (body.hasContractCreateInstance()) {
                final ContractCreateTransactionBody cc = body.getContractCreateInstance();
                log.info("ContractCreateInstance:\n{}", TextFormat.printer().printToString(cc));
                log.info(
                        "  initcodeSource case: {}, hasAdminKey={}, gas={}, "
                                + "initialBalance={}, autoRenewSecs={}, memoLen={}, "
                                + "ctorParamsLen={}, stakedIdCase={}, maxAutoAssoc={}",
                        cc.getInitcodeSourceCase(),
                        cc.hasAdminKey(),
                        cc.getGas(),
                        cc.getInitialBalance(),
                        cc.getAutoRenewPeriod().getSeconds(),
                        cc.getMemoBytes().size(),
                        cc.getConstructorParameters().size(),
                        cc.getStakedIdCase(),
                        cc.getMaxAutomaticTokenAssociations());
            } else {
                log.warn("Body is NOT a ContractCreate; data case = {}", body.getDataCase());
            }

            // Submitting the captured bytes verbatim is a non-starter on a local network: the
            // payer encoded in the bytes is 0.0.2 (shard=0, realm=0 — BNCE/mainnet shape), which
            // does not resolve when the spec is configured for a different shard/realm. Even if
            // it did, the captured SignatureMap was produced by BNCE's 0.0.2 private key, so it
            // would not verify locally. So we rebuild the body with the spec's default payer, a
            // fresh validStart, and the local node — keeping every ContractCreate field
            // verbatim — then re-sign with the default payer's key. We wrap the result in
            // SignedTransaction { bodyBytes, sigMap } and set it on Transaction.signedTransactionBytes
            // (the modern format the BNCE bytes used), rather than the deprecated
            // Transaction.bodyBytes / Transaction.sigMap pair that HapiSpec's KeyFactory.sign
            // produces. This still answers the question we care about — does the body shape
            // itself trigger INVALID_TRANSACTION_BODY on the current Services version? — and
            // exercises the same envelope the original client used.
            final String defaultPayerName = spec.setup().defaultPayerName();
            final AccountID defaultPayerId = spec.registry().getAccountID(defaultPayerName);
            final Key defaultPayerPubKey = spec.registry().getKey(defaultPayerName);
            // The spec's default payer Key may be a KeyList wrapper around a single ed25519
            // primitive (not a raw ed25519 like the captured BNCE adminKey), so walk into
            // composites until we find the first ed25519 leaf.
            final byte[] payerPubKeyBytes = firstEd25519PubKeyOrThrow(defaultPayerPubKey);
            final String payerPubKeyHex = org.hiero.base.utility.CommonUtils.hex(payerPubKeyBytes);
            final PrivateKey payerPrivateKey = spec.keys().getEd25519PrivateKey(payerPubKeyHex);
            if (payerPrivateKey == null) {
                throw new IllegalStateException(
                        "No ed25519 private key for payer pubKey " + payerPubKeyHex + " (name=" + defaultPayerName
                                + ")");
            }

            final AccountID nodeAccountId = AccountID.newBuilder()
                    .setShardNum(spec.shard())
                    .setRealmNum(spec.realm())
                    .setAccountNum(3)
                    .build();

            // The captured body references fileID 0.0.18528 — only meaningful on BNCE. Pre-stage the
            // *same* simpleContract bytecode the Swift SDK uploads on BNCE, the way the SDK uploads it:
            // bytecode.data(using: .utf8)! writes the ASCII-hex string verbatim as file bytes, which
            // HapiFileCreate.contents(String) mirrors. The handler then reads the file, ASCII-hex-decodes
            // the contents, appends the captured 96-byte ctor params hex, and decodes the combined
            // string into initcode. Using the real bytecode (not a 1-byte STOP) exercises the same
            // hex-decode + EVM frame setup BNCE exercised — any quirk in that path will surface here.
            final String simpleContractFileName = "bnceSimpleContractInitcode";
            allRunFor(spec, fileCreate(simpleContractFileName).contents(SIMPLE_CONTRACT_HEX).noLogging());
            final FileID simpleContractFileId = spec.registry().getFileId(simpleContractFileName);
            log.info("Pre-staged simpleContract initcode file as {}", simpleContractFileId);

            final var rebuiltCreateOp = body.getContractCreateInstance().toBuilder()
                    .setFileID(simpleContractFileId)
                    .build();

            final TransactionID rebuiltTxnId = TransactionID.newBuilder()
                    .setAccountID(defaultPayerId)
                    .setTransactionValidStart(getUniqueTimestampPlusSecs(spec.setup().txnStartOffsetSecs()))
                    .build();
            final TransactionBody rebuiltBody = body.toBuilder()
                    .setTransactionID(rebuiltTxnId)
                    .setNodeAccountID(nodeAccountId)
                    .setContractCreateInstance(rebuiltCreateOp)
                    .build();

            // Sign the exact bytes that will be placed in SignedTransaction.bodyBytes.
            final byte[] rebuiltBodyBytesArr = rebuiltBody.toByteArray();
            final byte[] sig = SignatureGenerator.signBytes(rebuiltBodyBytesArr, payerPrivateKey);
            final SignatureMap rebuiltSigMap = SignatureMap.newBuilder()
                    .addSigPair(SignaturePair.newBuilder()
                            .setPubKeyPrefix(ByteString.copyFrom(payerPubKeyBytes))
                            .setEd25519(ByteString.copyFrom(sig))
                            .build())
                    .build();
            final SignedTransaction rebuiltSignedTransaction = SignedTransaction.newBuilder()
                    .setBodyBytes(ByteString.copyFrom(rebuiltBodyBytesArr))
                    .setSigMap(rebuiltSigMap)
                    .build();
            final Transaction rebuiltSigned = Transaction.newBuilder()
                    .setSignedTransactionBytes(rebuiltSignedTransaction.toByteString())
                    .build();

            log.info(
                    "Submitting rebuilt txn: payer={} validStart={} node={}",
                    defaultPayerName,
                    rebuiltTxnId.getTransactionValidStart(),
                    nodeAccountId);
            final var response = spec.targetNetworkOrThrow().submit(rebuiltSigned, ContractCreate, NA, nodeAccountId);
            log.info("Replay precheck response: {}", response.getNodeTransactionPrecheckCode());

            // The precheck code only reflects ingest acceptance. The BNCE failure happened at handle
            // time (the mirror record had a consensus_timestamp), so poll the receipt to observe the
            // actual handle-time status, which is what we need to compare against BNCE.
            if (response.getNodeTransactionPrecheckCode() == ResponseCodeEnum.OK) {
                final Query receiptQuery = Query.newBuilder()
                        .setTransactionGetReceipt(TransactionGetReceiptQuery.newBuilder()
                                .setHeader(QueryHeader.newBuilder()
                                        .setResponseType(ResponseType.ANSWER_ONLY)
                                        .build())
                                .setTransactionID(rebuiltTxnId)
                                .build())
                        .build();
                ResponseCodeEnum handleStatus = ResponseCodeEnum.UNKNOWN;
                for (int attempt = 1; attempt <= 30; attempt++) {
                    Thread.sleep(500L);
                    final var receiptResp = spec.targetNetworkOrThrow()
                            .send(receiptQuery, TransactionGetReceipt, nodeAccountId);
                    final var grg = receiptResp.getTransactionGetReceipt();
                    final ResponseCodeEnum precheck = grg.getHeader().getNodeTransactionPrecheckCode();
                    if (precheck == ResponseCodeEnum.RECEIPT_NOT_FOUND
                            || precheck == ResponseCodeEnum.BUSY
                            || precheck == ResponseCodeEnum.UNKNOWN) {
                        // Receipt not yet available; keep polling.
                        continue;
                    }
                    if (precheck != ResponseCodeEnum.OK) {
                        log.warn("Receipt query precheck returned {} on attempt {}", precheck, attempt);
                        handleStatus = precheck;
                        break;
                    }
                    final var receipt = grg.getReceipt();
                    if (receipt.getStatus() == ResponseCodeEnum.UNKNOWN) {
                        // Receipt present but handle hasn't finalized status yet.
                        continue;
                    }
                    handleStatus = receipt.getStatus();
                    log.info(
                            "Replay handle-time receipt (attempt {}):\n{}",
                            attempt,
                            TextFormat.printer().printToString(receipt));
                    break;
                }
                log.info("Replay handle-time status (from receipt): {}", handleStatus);
            }
        }));
    }

    private static byte[] firstEd25519PubKeyOrThrow(final Key key) {
        final byte[] found = firstEd25519PubKey(key);
        if (found == null) {
            throw new IllegalStateException("No ed25519 primitive found in key:\n"
                    + TextFormat.printer().printToString(key));
        }
        return found;
    }

    private static byte[] firstEd25519PubKey(final Key key) {
        if (!key.getEd25519().isEmpty()) {
            return key.getEd25519().toByteArray();
        }
        if (key.hasKeyList()) {
            for (final Key child : key.getKeyList().getKeysList()) {
                final byte[] hit = firstEd25519PubKey(child);
                if (hit != null) {
                    return hit;
                }
            }
        }
        if (key.hasThresholdKey()) {
            for (final Key child : key.getThresholdKey().getKeys().getKeysList()) {
                final byte[] hit = firstEd25519PubKey(child);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static Transaction parseAsTransaction(final byte[] raw) throws InvalidProtocolBufferException {
        try {
            final Transaction parsed = Transaction.parseFrom(raw);
            if (!parsed.getSignedTransactionBytes().isEmpty()
                    || !parsed.getBodyBytes().isEmpty()
                    || parsed.getSerializedSize() == raw.length) {
                return parsed;
            }
        } catch (InvalidProtocolBufferException ignored) {
            // fall through to SignedTransaction parse
        }
        final SignedTransaction signed = SignedTransaction.parseFrom(raw);
        return Transaction.newBuilder()
                .setSignedTransactionBytes(signed.toByteString())
                .build();
    }
}
// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.EIP1559;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.LEGACY_ETHEREUM;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall.fromSignedBytes;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromEcdsaFile.createAndLinkEcdsaKey;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromEcdsaFile.ecdsaFrom;
import static com.hedera.services.bdd.suites.HapiSuite.CHAIN_ID;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.util.Integers;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.utils.Signing;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * An {@code EthereumTransaction} with no {@code to} address and empty initcode is a contract creation, as on
 * Ethereum: it runs no code, consumes the sender's nonce, and leaves a contract with empty bytecode at
 * {@code keccak256(rlp([sender, nonce]))[12:]}.
 *
 * <p>Each spec builds and signs its transaction in-spec from a fixed private key, so the sender and the expected
 * contract address are derived rather than hard-coded. The expected address is computed with RLP and Keccak
 * directly rather than through the code under test. The specs use disjoint keys so they stay independent of
 * ordering when sharing a network.
 */
@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S5960")
public class EthereumEmptyInitcodeCreationTest {
    // Exactly the intrinsic gas of a creation with empty initcode: 21_000 base + 32_000 for the creation
    private static final long INTRINSIC_GAS_OF_EMPTY_CREATION = 53_000L;
    private static final long MAX_PRIORITY_GAS_WEIBAR = 1_000L;
    private static final long MAX_GAS_WEIBAR = 500_000_000_000L;

    private static final Sender EIP1559_SENDER = new Sender("eip1559EmptyInitcodeSender", 0xe1c0de, EIP1559);
    private static final Sender LEGACY_SENDER = new Sender("legacyEmptyInitcodeSender", 0xe2c0de, LEGACY_ETHEREUM);

    @HapiTest
    final Stream<DynamicTest> eip1559CreationWithEmptyInitcodeCreatesEmptyContract() {
        return EIP1559_SENDER.createsEmptyContract();
    }

    @HapiTest
    final Stream<DynamicTest> legacyCreationWithEmptyInitcodeCreatesEmptyContract() {
        return LEGACY_SENDER.createsEmptyContract();
    }

    /// Left-pads `value` to a 32-byte secp256k1 private key.
    private static byte[] as32Bytes(final BigInteger value) {
        final var magnitude = EthTxData.asUnsignedByteArray(value);
        final var padded = new byte[32];
        System.arraycopy(magnitude, 0, padded, 32 - magnitude.length, magnitude.length);
        return padded;
    }

    /**
     * One EOA that signs a creation of the given type with empty initcode, and the ordinary account that pays for
     * the HAPI wrapper. The {@code seed} gives each spec a distinct EVM address, so their nonces are independent.
     */
    private record Sender(String senderKey, int seed, EthTransactionType type) {
        private byte[] privateKey() {
            return as32Bytes(BigInteger.valueOf(seed).shiftLeft(200).add(BigInteger.ONE));
        }

        private String submitter() {
            return senderKey + "Submitter";
        }

        private String txnName() {
            return senderKey + "Creation";
        }

        Stream<DynamicTest> createsEmptyContract() {
            final var signedBytes = signedCreationWithEmptyInitcode();
            final var expectedAddress = expectedContractAddress();
            final var hexedExpectedAddress = HexFormat.of().formatHex(expectedAddress);
            return hapiTest(
                    withOpContext((spec, opLog) -> createAndLinkEcdsaKey(
                            spec,
                            ecdsaFrom(new BigInteger(1, privateKey())),
                            senderKey,
                            Optional.empty(),
                            Optional.empty(),
                            opLog)),
                    cryptoCreate(submitter()).balance(ONE_HUNDRED_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, senderKey, ONE_HUNDRED_HBARS)),
                    getAliasedAccountInfo(senderKey).has(accountWith().nonce(0L)),
                    fromSignedBytes(signedBytes)
                            .payingWith(submitter())
                            .maxGasAllowance(10 * ONE_HBAR)
                            .via(txnName())
                            .hasKnownStatus(SUCCESS),
                    getTxnRecord(txnName())
                            .hasPriority(recordWith()
                                    .status(SUCCESS)
                                    .ethereumHash(ByteString.copyFrom(MiscCryptoUtils.keccak256DigestOf(signedBytes)))
                                    .contractCreateResult(resultWith()
                                            .evmAddress(ByteString.copyFrom(expectedAddress))
                                            .signerNonce(1L))),
                    // The creation consumed the sender's nonce
                    getAliasedAccountInfo(senderKey).has(accountWith().nonce(1L)),
                    // And left a contract with empty bytecode at the address derived from the sender and nonce
                    getContractInfo(hexedExpectedAddress),
                    getContractBytecode(hexedExpectedAddress).hasBytecode(new byte[0]));
        }

        /** Builds and signs this sender's creation with empty initcode, so the raw bytes are derived. */
        private byte[] signedCreationWithEmptyInitcode() {
            final var isLegacy = type == LEGACY_ETHEREUM;
            final var unsigned = new EthTxData(
                    null, // rawTx - null so encodeTx() yields the canonical encoding
                    type,
                    Integers.toBytes(CHAIN_ID),
                    0L, // nonce
                    isLegacy ? Integers.toBytes(MAX_GAS_WEIBAR) : null, // gasPrice
                    isLegacy ? null : Integers.toBytes(MAX_PRIORITY_GAS_WEIBAR),
                    isLegacy ? null : Integers.toBytes(MAX_GAS_WEIBAR),
                    INTRINSIC_GAS_OF_EMPTY_CREATION,
                    new byte[0], // to - none, so this is a creation
                    BigInteger.ZERO, // value
                    new byte[0], // callData - the empty initcode
                    new byte[0], // accessList
                    new Object[0],
                    null, // authorizationList
                    null,
                    0, // recId - replaced by signing
                    null, // v - replaced by signing
                    new byte[0], // r - replaced by signing
                    new byte[0]); // s - replaced by signing
            return Signing.signMessage(unsigned, privateKey()).encodeTx();
        }

        /** The address of a contract created by this sender at nonce 0: {@code keccak256(rlp([sender, 0]))[12:]}. */
        private byte[] expectedContractAddress() {
            final var senderAddress = EthSigsUtils.recoverAddressFromPrivateKey(privateKey());
            final var hash = MiscCryptoUtils.keccak256DigestOf(RLPEncoder.list(senderAddress, Integers.toBytes(0L)));
            return Arrays.copyOfRange(hash, 12, 32);
        }
    }
}

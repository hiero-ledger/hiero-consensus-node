// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import com.esaulpaugh.headlong.rlp.RLPDecoder;
import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.rlp.RLPItem;
import com.esaulpaugh.headlong.util.Integers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.util.BigIntegers;

public record EthTxData(
        byte[] rawTx,
        EthTransactionType type,
        byte[] chainId,
        long nonce,
        byte[] gasPrice,
        byte[] maxPriorityGas,
        byte[] maxGas,
        long gasLimit,
        byte[] to,
        BigInteger value, // weibar, always positive - note that high-bit might be ON in RLP encoding: still positive
        byte[] callData,
        byte[] accessList,
        int recId, // "recovery id" part of a v,r,s ECDSA signature - range 0..1
        byte[] v, // actual `v` value, incoming, recovery id (`recId` above) (possibly) encoded with chain id
        byte[] r,
        byte[] s) {

    /**
     * A "wiebar" is 10⁻¹⁸ of an hbar.  The relationship is weibar : hbar as wei : ether.  Ethereum
     * transactions come in with transfer amounts in units of weibar.  Elsewhere in Hedera we use
     * units of tinybar (10⁻⁸ of an hbar), and here is the conversion factor:
     */
    public static final BigInteger WEIBARS_IN_A_TINYBAR = BigInteger.valueOf(10_000_000_000L);

    // Copy of constants from besu-native, remove when next besu-native publishes
    static final int SECP256K1_FLAGS_TYPE_COMPRESSION = 1 << 1;
    static final int SECP256K1_FLAGS_BIT_COMPRESSION = 1 << 8;
    static final int SECP256K1_EC_COMPRESSED = (SECP256K1_FLAGS_TYPE_COMPRESSION | SECP256K1_FLAGS_BIT_COMPRESSION);

    // EIP155 note support for v = 27|28 cases in unprotected transaction cases
    static final BigInteger LEGACY_V_BYTE_SIGNATURE_0 = BigInteger.valueOf(27);
    static final BigInteger LEGACY_V_BYTE_SIGNATURE_1 = BigInteger.valueOf(28);

    // The specific transaction bytes that are used to deploy the Deterministic Deployer contract
    // see -  https://github.com/Arachnid/deterministic-deployment-proxy?tab=readme-ov-file#deployment-transaction
    public static final byte[] DETERMINISTIC_DEPLOYER_TRANSACTION = HexFormat.of()
            .parseHex(
                    "f8a58085174876e800830186a08080b853604580600e600039806000f350fe7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe03601600081602082378035828234f58015156039578182fd5b8082525050506014600cf31ba02222222222222222222222222222222222222222222222222222222222222222a02222222222222222222222222222222222222222222222222222222222222222");

    public static EthTxData populateEthTxData(byte[] data) {
        try {
            var decoder = RLPDecoder.RLP_STRICT.sequenceIterator(data);
            var rlpItem = decoder.next();
            if (rlpItem.isList()) {
                return populateLegacyEthTxData(rlpItem, data);
            }

            return switch (asByte(rlpItem)) {
                case 1 -> populateEip2390EthTxData(decoder.next(), data);
                case 2 -> populateEip1559EthTxData(decoder.next(), data);
                case 3 -> null; // We don't currently support Cancun "blob" transactions
                default -> null;
            };

        } catch (IllegalArgumentException | NoSuchElementException e) {
            return null;
        }
    }

    public EthTxData replaceCallData(final byte[] newCallData) {
        return new EthTxData(
                null,
                type,
                chainId,
                nonce,
                gasPrice,
                maxPriorityGas,
                maxGas,
                gasLimit,
                to,
                value,
                newCallData,
                accessList,
                recId,
                v,
                r,
                s);
    }

    @VisibleForTesting
    EthTxData replaceValue(@NonNull final BigInteger replacementValue) {
        return new EthTxData(
                null,
                type,
                chainId,
                nonce,
                gasPrice,
                maxPriorityGas,
                maxGas,
                gasLimit,
                to,
                replacementValue,
                callData,
                accessList,
                recId,
                v,
                r,
                s);
    }

    // For more information on "recovery id" see
    // https://coinsbench.com/understanding-digital-signatures-the-role-of-v-r-s-in-cryptographic-security-and-signature-b9d2b89bbc0c

    // For more information on encoding `v` see EIP-155 - https://eips.ethereum.org/EIPS/eip-155

    public byte[] encodeTx() {
        if (accessList != null && accessList.length > 0) {
            throw new IllegalStateException("Re-encoding access list is unsupported");
        }
        return switch (type) {
            case LEGACY_ETHEREUM ->
                RLPEncoder.list(
                        Integers.toBytes(nonce),
                        gasPrice,
                        Integers.toBytes(gasLimit),
                        to,
                        Integers.toBytesUnsigned(value),
                        callData,
                        v,
                        r,
                        s);
            case EIP2930 ->
                RLPEncoder.sequence(
                        Integers.toBytes(0x01),
                        List.of(
                                chainId,
                                Integers.toBytes(nonce),
                                gasPrice,
                                Integers.toBytes(gasLimit),
                                to,
                                Integers.toBytesUnsigned(value),
                                callData,
                                List.of(/*accessList*/ ),
                                Integers.toBytes(recId),
                                r,
                                s));
            case EIP1559 ->
                RLPEncoder.sequence(
                        Integers.toBytes(0x02),
                        List.of(
                                chainId,
                                Integers.toBytes(nonce),
                                maxPriorityGas,
                                maxGas,
                                Integers.toBytes(gasLimit),
                                to,
                                Integers.toBytesUnsigned(value),
                                callData,
                                List.of(/*accessList*/ ),
                                Integers.toBytes(recId),
                                r,
                                s));
        };
    }

    public long getAmount() {
        return value.divide(WEIBARS_IN_A_TINYBAR).longValueExact();
    }

    public BigInteger getMaxGasAsBigInteger(final long tinybarGasPrice) {
        long multiple = 1L;
        if (type == EthTransactionType.LEGACY_ETHEREUM && Arrays.equals(rawTx, DETERMINISTIC_DEPLOYER_TRANSACTION)) {
            multiple = tinybarGasPrice;
        }
        return switch (type) {
            case LEGACY_ETHEREUM -> new BigInteger(1, gasPrice).multiply(BigInteger.valueOf(multiple));
            case EIP2930 -> new BigInteger(1, gasPrice);
            case EIP1559 -> new BigInteger(1, maxGas);
        };
    }

    /**
     * Returns the effective offered gas price for this transaction, defined as the minimum of the
     * nominal offered gas price in tinybars and {@code (Long.MAX_VALUE / gasLimit)}.
     *
     * <p>Clearly the latter value would always be un-payable, since the transaction would cost more
     * than the entire hbar supply. We just do this to avoid integral overflow.
     *
     * @param weibarGasPrice the current gas price in weibars
     * @return the effective offered gas price in tinybars
     */
    public long effectiveOfferedGasPriceInTinybars(final long weibarGasPrice) {
        return BigInteger.valueOf(Long.MAX_VALUE)
                .min(getMaxGasAsBigInteger(weibarGasPrice).divide(WEIBARS_IN_A_TINYBAR))
                .longValueExact();
    }

    /**
     * Returns the effective tinybar value of this transaction, defined as the minimum of the nominal
     * value in tinybars and {@code Long.MAX_VALUE}.
     *
     * <p>Clearly the latter value would always be un-payable, since the transaction would send more
     * than the entire hbar supply. We just do this to avoid integral overflow.
     *
     * @return the effective tinybar value
     */
    public long effectiveTinybarValue() {
        return BigInteger.valueOf(Long.MAX_VALUE)
                .min(value.divide(WEIBARS_IN_A_TINYBAR))
                .longValueExact();
    }

    public byte[] getEthereumHash() {
        return new Keccak.Digest256().digest(rawTx == null ? encodeTx() : rawTx);
    }

    public enum EthTransactionType {
        LEGACY_ETHEREUM,
        EIP2930,
        EIP1559,
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;

        final EthTxData ethTxData = (EthTxData) other;

        return (nonce == ethTxData.nonce)
                && (gasLimit == ethTxData.gasLimit)
                && (recId == ethTxData.recId)
                && (Arrays.equals(rawTx, ethTxData.rawTx))
                && (type == ethTxData.type)
                && (Arrays.equals(chainId, ethTxData.chainId))
                && (Arrays.equals(gasPrice, ethTxData.gasPrice))
                && (Arrays.equals(maxPriorityGas, ethTxData.maxPriorityGas))
                && (Arrays.equals(maxGas, ethTxData.maxGas))
                && (Arrays.equals(to, ethTxData.to))
                && (Objects.equals(value, ethTxData.value))
                && (Arrays.equals(callData, ethTxData.callData))
                && (Arrays.equals(accessList, ethTxData.accessList))
                && (Arrays.equals(v, ethTxData.v))
                && (Arrays.equals(r, ethTxData.r))
                && (Arrays.equals(s, ethTxData.s));
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(rawTx);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + Arrays.hashCode(chainId);
        result = 31 * result + (int) (nonce ^ (nonce >>> 32));
        result = 31 * result + Arrays.hashCode(gasPrice);
        result = 31 * result + Arrays.hashCode(maxPriorityGas);
        result = 31 * result + Arrays.hashCode(maxGas);
        result = 31 * result + (int) (gasLimit ^ (gasLimit >>> 32));
        result = 31 * result + Arrays.hashCode(to);
        result = 31 * result + (value != null ? value.hashCode() : 0);
        result = 31 * result + Arrays.hashCode(callData);
        result = 31 * result + Arrays.hashCode(accessList);
        result = 31 * result + recId;
        result = 31 * result + Arrays.hashCode(v);
        result = 31 * result + Arrays.hashCode(r);
        result = 31 * result + Arrays.hashCode(s);
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("rawTx", rawTx == null ? null : Hex.encodeHexString(rawTx))
                .add("type", type)
                .add("chainId", chainId == null ? null : Hex.encodeHexString(chainId))
                .add("nonce", nonce)
                .add("gasPrice", gasPrice == null ? null : Hex.encodeHexString(gasPrice))
                .add("maxPriorityGas", maxPriorityGas == null ? null : Hex.encodeHexString(maxPriorityGas))
                .add("maxGas", maxGas == null ? null : Hex.encodeHexString(maxGas))
                .add("gasLimit", gasLimit)
                .add("to", to == null ? null : Hex.encodeHexString(to))
                .add("value", value)
                .add("callData", Hex.encodeHexString(callData))
                .add("accessList", accessList == null ? null : Hex.encodeHexString(accessList))
                .add("recId", recId)
                .add("v", v == null ? null : Hex.encodeHexString(v))
                .add("r", Hex.encodeHexString(r))
                .add("s", Hex.encodeHexString(s))
                .toString();
    }

    public boolean hasCallData() {
        return callData != null && callData.length > 0;
    }

    public boolean hasToAddress() {
        return to != null && to.length > 0;
    }

    public boolean matchesChainId(final byte[] hederaChainId) {
        // first two checks handle the unprotected ethereum transactions
        // before EIP155 - source: https://eips.ethereum.org/EIPS/eip-155
        return chainId == null || chainId.length == 0 || Arrays.compare(chainId, hederaChainId) == 0;
    }

    @VisibleForTesting
    public EthTxData replaceTo(final byte[] newTo) {
        return new EthTxData(
                null,
                type,
                chainId,
                nonce,
                gasPrice,
                maxPriorityGas,
                maxGas,
                gasLimit,
                newTo,
                value,
                callData,
                accessList,
                recId,
                v,
                r,
                s);
    }

    @VisibleForTesting
    public EthTxData replaceRecId(final int newRecId) {
        return new EthTxData(
                null,
                type,
                chainId,
                nonce,
                gasPrice,
                maxPriorityGas,
                maxGas,
                gasLimit,
                to,
                value,
                callData,
                accessList,
                newRecId,
                v,
                r,
                s);
    }

    @VisibleForTesting
    public EthTxData replaceR(final byte[] newR) {
        return new EthTxData(
                null,
                type,
                chainId,
                nonce,
                gasPrice,
                maxPriorityGas,
                maxGas,
                gasLimit,
                to,
                value,
                callData,
                accessList,
                recId,
                v,
                newR,
                s);
    }

    @VisibleForTesting
    public EthTxData replaceS(final byte[] newS) {
        return new EthTxData(
                null,
                type,
                chainId,
                nonce,
                gasPrice,
                maxPriorityGas,
                maxGas,
                gasLimit,
                to,
                value,
                callData,
                accessList,
                recId,
                v,
                r,
                newS);
    }

    /**
     * Encodes the transaction data into a EthTxData according to legacy RLP format.
     *
     * @return the encoded transaction data
     */
    private static EthTxData populateLegacyEthTxData(RLPItem rlpItem, byte[] rawTx) {
        List<RLPItem> rlpList = rlpItem.asRLPList().elements();
        if (rlpList.size() != 9) {
            return null;
        }

        byte[] chainId = null;
        byte[] val = rlpList.get(6).asBytes();
        BigInteger vBI = new BigInteger(1, val);
        byte recId = vBI.testBit(0) ? (byte) 0 : 1;
        // https://eips.ethereum.org/EIPS/eip-155
        if (vBI.compareTo(BigInteger.valueOf(34)) > 0) {
            // after EIP155 the chain id is equal to
            // CHAIN_ID = (v - {0,1} - 35) / 2
            // BigIntegers.asUnsignedByteArray method is used here to ensure no extra byte is added at the beginning
            // of the byte array, which can happen in BigInteger.toByteArray when the highest bit
            // in the result is already occupied by stored values. This issue is further explained
            // in https://github.com/hashgraph/hedera-services/issues/15953
            chainId = BigIntegers.asUnsignedByteArray(
                    vBI.subtract(BigInteger.valueOf(35)).shiftRight(1));
        } else if (isLegacyUnprotectedEtx(vBI)) {
            // before EIP155 the chain id is considered equal to 0
            chainId = new byte[0];
        }

        return new EthTxData(
                rawTx,
                EthTransactionType.LEGACY_ETHEREUM,
                chainId,
                asLong(rlpList.get(0)), // nonce
                rlpList.get(1).asBytes(), // gasPrice
                null, // maxPriorityGas
                null, // maxGas
                asLong(rlpList.get(2)), // gasLimit
                rlpList.get(3).data(), // to
                rlpList.get(4).asBigInt(), // value
                rlpList.get(5).data(), // callData
                null, // accessList
                recId,
                val,
                rlpList.get(7).data(), // r
                rlpList.get(8).data() // s
                );
    }

    /**
     * Encodes the transaction data into a EthTxData according to EIP 1559 RLP format.
     *
     * @return the encoded transaction data
     */
    private static EthTxData populateEip1559EthTxData(RLPItem rlpItem, byte[] rawTx) {
        if (!rlpItem.isList()) {
            return null;
        }

        List<RLPItem> rlpList = rlpItem.asRLPList().elements();
        if (rlpList.size() != 12) {
            return null;
        }

        return new EthTxData(
                rawTx,
                EthTransactionType.EIP1559,
                rlpList.get(0).data(), // chainId
                asLong(rlpList.get(1)), // nonce
                null, // gasPrice
                rlpList.get(2).data(), // maxPriorityGas
                rlpList.get(3).data(), // maxGas
                asLong(rlpList.get(4)), // gasLimit
                rlpList.get(5).data(), // to
                rlpList.get(6).asBigInt(), // value
                rlpList.get(7).data(), // callData
                rlpList.get(8).data(), // accessList
                asByte(rlpList.get(9)), // recId
                null, // v
                rlpList.get(10).data(), // r
                rlpList.get(11).data() // s
                );
    }

    /**
     * Encodes the transaction data into a EthTxData according to EIP 2930 RLP format.
     *
     * @return the encoded transaction data
     */
    private static EthTxData populateEip2390EthTxData(RLPItem rlpItem, byte[] rawTx) {
        if (!rlpItem.isList()) {
            return null;
        }

        List<RLPItem> rlpList = rlpItem.asRLPList().elements();
        if (rlpList.size() != 11) {
            return null;
        }

        return new EthTxData(
                rawTx,
                EthTransactionType.EIP2930,
                rlpList.get(0).data(), // chainId
                asLong(rlpList.get(1)), // nonce
                rlpList.get(2).data(), // gasPrice
                null, // maxPriorityGas
                null, // maxGas
                asLong(rlpList.get(3)), // gasLimit
                rlpList.get(4).data(), // to
                rlpList.get(5).asBigInt(), // value
                rlpList.get(6).data(), // callData
                rlpList.get(7).data(), // accessList
                asByte(rlpList.get(8)), // recId
                null, // v
                rlpList.get(9).data(), // r
                rlpList.get(10).data() // s
                );
    }

    // before EIP155 the value of v in
    // (unprotected) ethereum transactions is either 27 or 28
    private static boolean isLegacyUnprotectedEtx(@NonNull BigInteger vBI) {
        return vBI.compareTo(LEGACY_V_BYTE_SIGNATURE_0) == 0 || vBI.compareTo(LEGACY_V_BYTE_SIGNATURE_1) == 0;
    }

    // `asByte` and `asLong` always return positive values by replacing out of range values with
    // `MAX_VALUE`.  (`RLPItem.asBigInt` cannot return negative values: Negative values cannot be
    // encoded in RLP.)

    private static byte asByte(@NonNull final RLPItem rlpItem) {
        var v = rlpItem.asBigInt(false);
        if (v.compareTo(BigInteger.ZERO) < 0) throwOutOfRange();
        if (v.compareTo(BigInteger.valueOf(Byte.MAX_VALUE)) > 0) throwOutOfRange();
        return v.byteValueExact();
    }

    private static long asLong(@NonNull final RLPItem rlpItem) {
        var v = rlpItem.asBigInt(false);
        if (v.compareTo(BigInteger.ZERO) < 0) throwOutOfRange();
        if (v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) throwOutOfRange();
        return v.longValueExact();
    }

    private static void throwOutOfRange() {
        class OutOfRangeException extends IllegalArgumentException {
            public OutOfRangeException() {
                super("EthTxData has RLPItem out of range");
            }
        }
        throw new OutOfRangeException();
    }
}

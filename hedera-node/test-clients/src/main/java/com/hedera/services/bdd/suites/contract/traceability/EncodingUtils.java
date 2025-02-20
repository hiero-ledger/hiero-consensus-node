// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.traceability;

import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.stripSelector;
import static com.hedera.services.bdd.suites.contract.Utils.extractBytecodeUnhexed;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.getResourcePath;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.suites.contract.Utils;
import java.math.BigInteger;
import org.apache.commons.lang3.StringUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Utility class with methods for encoding and decoding data.
 *
 * @author vyanev
 */
public final class EncodingUtils {

    private EncodingUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Returns the init code for a contract.
     * @param binFileName the name of the bin file
     * @param constructorArgs the constructor arguments
     * @return {@link ByteString} of the init code
     */
    public static ByteString getInitcode(final String binFileName, final Object... constructorArgs) {
        final var initCode = extractBytecodeUnhexed(getResourcePath(binFileName, ".bin"));

        final byte[] params;
        if (constructorArgs.length == 0) {
            params = new byte[] {};
        } else {
            final var abi = getABIFor(Utils.FunctionType.CONSTRUCTOR, StringUtils.EMPTY, binFileName);
            params = Function.fromJson(abi)
                    .encodeCall(Tuple.from(constructorArgs))
                    .array();
        }

        final var byteCode = ByteStringUtils.wrapUnsafely(params.length > 4 ? stripSelector(params) : params);
        return initCode.concat(byteCode);
    }

    /**
     * Encodes a function call.
     * @param contractName the contract name
     * @param functionName the function name
     * @param args the arguments passed to the function
     * @return {@link ByteString} of the encoded function call
     */
    public static ByteString encodeFunctionCall(
            final String contractName, final String functionName, final Object... args) {
        return ByteStringUtils.wrapUnsafely(
                Function.fromJson(getABIFor(Utils.FunctionType.FUNCTION, functionName, contractName))
                        .encodeCallWithArgs(args)
                        .array());
    }

    /**
     * Encodes a tuple.
     * @param argumentsSignature the arguments signature (e.g., "(bool,bytes32)")
     * @param actualArguments the arguments value
     * @return {@code byte[]} of the encoded tuple
     */
    public static byte[] encodeTuple(final String argumentsSignature, final Object... actualArguments) {
        return TupleType.parse(argumentsSignature)
                .encode(Tuple.from(actualArguments))
                .array();
    }

    /**
     * Encodes a {@link BigInteger} into a tuple of type (uint256).
     * @param value value of the {@link BigInteger} to encode
     * @return {@link ByteString} of the encoded tuple
     */
    public static ByteString uint256ReturnWithValue(final BigInteger value) {
        return ByteStringUtils.wrapUnsafely(encodeTuple("(uint256)", value));
    }

    /**
     * Converts a hexed solidity address to a headlong {@link Address}.
     * @param hexedSolidityAddress the hexed solidity address
     * @return headlong {@link Address} of the passed solidity address
     */
    public static Address hexedSolidityAddressToHeadlongAddress(final String hexedSolidityAddress) {
        return Address.wrap(Address.toChecksumAddress("0x" + hexedSolidityAddress));
    }

    /**
     * Converts the passed non-negative long value to a {@link ByteString}.
     * @param value the long value
     * @return {@link ByteString} of the passed long value
     */
    public static ByteString formattedAssertionValue(final long value) {
        return ByteString.copyFrom(
                Bytes.wrap(UInt256.valueOf(value)).trimLeadingZeros().toArrayUnsafe());
    }

    /**
     * Converts the passed hex string of a {@link UInt256} value to a {@link ByteString}.
     * @param hexString the hex string of a {@link UInt256} value
     * @return {@link ByteString} of the passed hex string
     */
    public static ByteString formattedAssertionValue(final String hexString) {
        return ByteString.copyFrom(
                Bytes.wrap(UInt256.fromHexString(hexString)).trimLeadingZeros().toArrayUnsafe());
    }
}

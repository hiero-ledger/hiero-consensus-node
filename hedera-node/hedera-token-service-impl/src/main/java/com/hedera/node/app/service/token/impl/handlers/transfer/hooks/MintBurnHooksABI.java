// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;

/**
 * ABI helpers for mint and burn hook entrypoints.
 * Provides encoding and decoding utilities for allowMint and allowBurn functions.
 */
public class MintBurnHooksABI {

    /**
     * Enum representing the party responsible for signing.
     * Maps to the Party enum in the hook contract.
     */
    public enum Party {
        /** Treasury account (value 0) */
        TREASURY(0),
        /** Sender account (value 1) */
        SENDER(1);

        private final int value;

        Party(int value) {
            this.value = value;
        }

        /**
         * Returns the integer value of this party.
         * @return the integer value
         */
        public int value() {
            return value;
        }

        /**
         * Returns the Party corresponding to the given value.
         * @param value the integer value
         * @return the corresponding Party
         * @throws IllegalArgumentException if the value is invalid
         */
        public static Party fromValue(int value) {
            for (Party party : values()) {
                if (party.value == value) {
                    return party;
                }
            }
            throw new IllegalArgumentException("Invalid Party value: " + value);
        }
    }

    /**
     * Record representing the result of an allow call.
     * @param allowed whether the operation is allowed
     * @param partyValue the integer value of the party responsible for signing
     */
    public record AllowResult(boolean allowed, int partyValue) {}

    /** Function for allowMint(uint256,bool) - selector 0x029e433c */
    public static final Function ALLOW_MINT = new Function("allowMint(uint256,bool)", "(bool,uint8)");

    /** Function for allowBurn(uint256,bool) - selector 0x81c003b7 */
    public static final Function ALLOW_BURN = new Function("allowBurn(uint256,bool)", "(bool,uint8)");

    /**
     * Encodes arguments for the allowMint function.
     *
     * @param amount the amount to mint
     * @param supplyKeySigned whether the supply key has signed
     * @return the encoded byte array
     */
    public static byte[] encodeAllowMintArgs(long amount, boolean supplyKeySigned) {
        return ALLOW_MINT.encodeCall(Tuple.of(BigInteger.valueOf(amount), supplyKeySigned))
                .array();
    }

    /**
     * Encodes arguments for the allowBurn function.
     *
     * @param amount the amount to burn
     * @param supplyKeySigned whether the supply key has signed
     * @return the encoded byte array
     */
    public static byte[] encodeAllowBurnArgs(long amount, boolean supplyKeySigned) {
        return ALLOW_BURN.encodeCall(Tuple.of(BigInteger.valueOf(amount), supplyKeySigned))
                .array();
    }

    /**
     * Decodes the result of an allowMint or allowBurn call.
     *
     * @param returnData the raw return data from the hook call
     * @return the decoded AllowResult
     */
    public static AllowResult decodeAllowResult(@NonNull final byte[] returnData) {
        // Both allowMint and allowBurn have the same return type (bool,uint8)
        final Tuple result = ALLOW_MINT.decodeReturn(returnData);
        final boolean allowed = result.get(0);
        final int partyValue = ((BigInteger) result.get(1)).intValue();
        return new AllowResult(allowed, partyValue);
    }

    private MintBurnHooksABI() {
        throw new UnsupportedOperationException("Utility class");
    }
}


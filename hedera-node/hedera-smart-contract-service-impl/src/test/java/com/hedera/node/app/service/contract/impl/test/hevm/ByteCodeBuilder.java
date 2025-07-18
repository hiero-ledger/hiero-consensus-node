// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import org.apache.tuweni.units.bigints.UInt256;

/**
 * A helper class for building raw EVM byte code.
 */
public class ByteCodeBuilder {

    public enum Operation {
        STOP("00"),
        ADD("01"),
        SUB("03"),
        JUMPDEST("5b"),
        JUMP("56"),
        JUMPI("57"),
        PUSH1("60"),
        PUSH32("7f"),
        DUP1("80"),
        SWAP1("90");

        private final String value;

        Operation(final String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    final StringBuilder byteCode = new StringBuilder("0x");

    @Override
    public String toString() {
        return byteCode.toString();
    }

    public ByteCodeBuilder push(final int value) {
        return this.push(UInt256.valueOf(value));
    }

    public ByteCodeBuilder push(final UInt256 value) {
        String byteString = standardizeByteString(value);
        byteCode.append(Operation.PUSH1);
        byteCode.append(byteString);
        return this;
    }

    public ByteCodeBuilder push32(final UInt256 value) {
        byteCode.append(Operation.PUSH32);
        byteCode.append(value.toUnprefixedHexString());
        return this;
    }

    private String standardizeByteString(final UInt256 value) {
        String byteString = value.toMinimalBytes().toUnprefixedHexString();
        if (byteString.length() % 2 == 1) {
            byteString = "0" + byteString;
        } else if (byteString.isEmpty()) {
            byteString = "00";
        }
        return byteString;
    }

    public ByteCodeBuilder conditionalJump(final int dest) {
        this.push(dest);
        byteCode.append(Operation.JUMPI);
        return this;
    }

    public ByteCodeBuilder jump(final int dest) {
        this.push(dest);
        byteCode.append(Operation.JUMP);
        return this;
    }

    public ByteCodeBuilder swap1() {
        byteCode.append(Operation.SWAP1);
        return this;
    }

    public ByteCodeBuilder jumpdest() {
        byteCode.append(Operation.JUMPDEST);
        return this;
    }

    public ByteCodeBuilder dup1() {
        byteCode.append(Operation.DUP1);
        return this;
    }

    public ByteCodeBuilder stop() {
        byteCode.append(Operation.STOP);
        return this;
    }

    public ByteCodeBuilder sub() {
        byteCode.append(Operation.SUB);
        return this;
    }

    public ByteCodeBuilder op(final Operation operation) {
        byteCode.append(operation.toString());
        return this;
    }
}

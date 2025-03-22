// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.tokenkey;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.ZERO_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALID_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenkey.TokenKeyCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenkey.address_0x167.TokenKeyTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenKeyCallTest extends CallTestBase {
    @Test
    void returnsEd25519KeyStatusForPresentToken() {
        final var key = Key.newBuilder().ed25519(AN_ED25519_KEY.ed25519()).build();
        final var subject = new TokenKeyCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN, key);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenKeyTranslator.TOKEN_KEY
                        .getOutputs()
                        .encode(Tuple.of(
                                SUCCESS.protoOrdinal(),
                                Tuple.of(
                                        false,
                                        headlongAddressOf(ZERO_CONTRACT_ID),
                                        key.ed25519().toByteArray(),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        headlongAddressOf(ZERO_CONTRACT_ID))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsEcdsaKeyStatusForPresentToken() {
        final var key =
                Key.newBuilder().ecdsaSecp256k1(AN_ED25519_KEY.ed25519()).build();
        final var subject = new TokenKeyCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN, key);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenKeyTranslator.TOKEN_KEY
                        .getOutputs()
                        .encode(Tuple.of(
                                SUCCESS.protoOrdinal(),
                                Tuple.of(
                                        false,
                                        headlongAddressOf(ZERO_CONTRACT_ID),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        key.ecdsaSecp256k1().toByteArray(),
                                        headlongAddressOf(ZERO_CONTRACT_ID))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsContractKeyStatusForPresentToken() {
        final var key = Key.newBuilder().contractID(VALID_CONTRACT_ADDRESS).build();
        final var subject = new TokenKeyCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN, key);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenKeyTranslator.TOKEN_KEY
                        .getOutputs()
                        .encode(Tuple.of(
                                SUCCESS.protoOrdinal(),
                                Tuple.of(
                                        false,
                                        headlongAddressOf(VALID_CONTRACT_ADDRESS),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        headlongAddressOf(ZERO_CONTRACT_ID))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsDelegatableContractKeyStatusForPresentToken() {
        final var key =
                Key.newBuilder().delegatableContractId(VALID_CONTRACT_ADDRESS).build();
        final var subject = new TokenKeyCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN, key);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenKeyTranslator.TOKEN_KEY
                        .getOutputs()
                        .encode(Tuple.of(
                                SUCCESS.protoOrdinal(),
                                Tuple.of(
                                        false,
                                        headlongAddressOf(ZERO_CONTRACT_ID),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        headlongAddressOf(VALID_CONTRACT_ADDRESS))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsTokenKeyStatusForMissingToken() {
        final var subject = new TokenKeyCall(gasCalculator, mockEnhancement(), false, null, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenKeyTranslator.TOKEN_KEY
                        .getOutputs()
                        .encode(Tuple.of(
                                INVALID_TOKEN_ID.protoOrdinal(),
                                Tuple.of(
                                        false,
                                        headlongAddressOf(ZERO_CONTRACT_ID),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY.toByteArray(),
                                        headlongAddressOf(ZERO_CONTRACT_ID))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsTokenKeyStatusForMissingTokenStaticCall() {
        final var subject = new TokenKeyCall(gasCalculator, mockEnhancement(), true, null, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }
}

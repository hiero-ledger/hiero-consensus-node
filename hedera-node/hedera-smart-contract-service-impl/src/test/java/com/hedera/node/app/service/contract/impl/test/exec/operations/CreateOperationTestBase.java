// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.state.ProxyEvmContract;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import java.util.Deque;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A base class which aims to combine common logic for Create and Create2 tests
 */
@ExtendWith(MockitoExtension.class)
public class CreateOperationTestBase {
    protected static final long VALUE = 123_456L;
    protected static final long NONCE = 789L;
    protected static final long GAS_COST = 1_234L;
    protected static final int INPUT_OFFSET = 0;
    protected static final int INPUT_SIZE = 10;
    protected static final long TX_CREATE_COST = 500L;
    protected static final long MEMORY_EXPANSION_COST = 200L;
    protected static final long INITCODE_COST = 534L;
    protected static final long CHILD_STIPEND = 1_000_000L;
    protected static final Wei GAS_PRICE = Wei.of(1000L);
    protected static final Bytes INITCODE = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
    protected static final Address RECIEVER_ADDRESS = Address.BLAKE2B_F_COMPRESSION;
    protected static final Address NEW_CONTRACT_ADDRESS = Address.BLS12_MAP_FP2_TO_G2;
    protected static final Address ORIGINATOR_ADDRESS = Address.BLS12_G1ADD;
    protected static final Address COINBASE_ADDRESS = Address.BLS12_G1MULTIEXP;

    @Mock
    protected EVM evm;

    @Mock
    protected BlockValues blockValues;

    @Mock
    protected MessageFrame frame;

    @Mock
    protected GasCalculator gasCalculator;

    @Mock
    protected ProxyWorldUpdater worldUpdater;

    @Mock
    protected ProxyEvmContract receiver;

    @Mock
    protected Deque<MessageFrame> stack;

    protected void givenBuilderPrereqs() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(worldUpdater.updater()).willReturn(worldUpdater);
        given(gasCalculator.gasAvailableForChildCreate(anyLong())).willReturn(CHILD_STIPEND);
        given(frame.getOriginatorAddress()).willReturn(ORIGINATOR_ADDRESS);
        given(frame.getGasPrice()).willReturn(GAS_PRICE);
        given(frame.getBlockValues()).willReturn(blockValues);
        given(frame.getMiningBeneficiary()).willReturn(COINBASE_ADDRESS);
        given(frame.getBlockHashLookup()).willReturn((f, l) -> Hash.ZERO);
    }

    protected void givenSpawnPrereqs() {
        givenSpawnPrereqs(3);
    }

    protected void givenSpawnPrereqs(final int stackSize) {
        given(frame.stackSize()).willReturn(stackSize);
        given(frame.getStackItem(anyInt())).willReturn(Bytes.ofUnsignedLong(1));
        given(frame.getRemainingGas()).willReturn(GAS_COST);
        given(frame.getStackItem(0)).willReturn(Bytes.ofUnsignedLong(VALUE));
        given(frame.getRecipientAddress()).willReturn(RECIEVER_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getAccount(RECIEVER_ADDRESS)).willReturn(receiver);
        given(receiver.getBalance()).willReturn(Wei.of(VALUE));
        given(frame.getDepth()).willReturn(1023);
    }

    protected void givenGasCostPrereqs() {
        given(frame.getStackItem(1)).willReturn(Bytes.ofUnsignedInt(INPUT_OFFSET));
        given(frame.getStackItem(2)).willReturn(Bytes.ofUnsignedInt(INPUT_SIZE));
        given(gasCalculator.txCreateCost()).willReturn(TX_CREATE_COST);
        given(gasCalculator.memoryExpansionGasCost(frame, INPUT_OFFSET, INPUT_SIZE))
                .willReturn(MEMORY_EXPANSION_COST);
        given(gasCalculator.initcodeCost(INPUT_SIZE)).willReturn(INITCODE_COST);
    }
}

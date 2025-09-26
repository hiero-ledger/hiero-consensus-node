// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.operations.CustomizedOpcodes.CREATE;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.code.CodeV0.EMPTY_CODE;

import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.CreateOperation;

/**
 * A Hedera customization of the Besu {@link org.hyperledger.besu.evm.operation.CreateOperation}.
 */
public class CustomCreateOperation extends AbstractCustomCreateOperation {
    /**
     * Constructor for custom create operations.
     * @param gasCalculator the gas calculator to use
     */
    public CustomCreateOperation(@NonNull final GasCalculator gasCalculator, @NonNull final CodeFactory codeFactory) {
        super(CREATE.opcode(), "ħCREATE", 3, 1, gasCalculator, codeFactory);
    }

    @Override
    protected boolean isEnabled(@NonNull final MessageFrame frame) {
        return true;
    }

    @Override
    protected void onSuccess(@NonNull final MessageFrame frame, @NonNull final Address createdAddress) {
        // Nothing to do here, the record of the creation will be tracked as
        // a side effect of dispatching from ProxyWorldUpdater#createAccount()
    }

    /**
     * Returns the amount of gas the CREATE operation will consume.
     *
     * @param frame The current frame
     * @return the amount of gas the CREATE operation will consume
     * <p>Compose the operation cost from {@link GasCalculator#txCreateCost()}, {@link
     * GasCalculator#memoryExpansionGasCost(MessageFrame, long, long)}, and {@link GasCalculator#initcodeCost(int)} As done
     * in {@link org.hyperledger.besu.evm.operation.CreateOperation#cost(MessageFrame, Supplier)}
     */
    @Override
    protected long cost(@NonNull final MessageFrame frame) {
        return new CreateOperation(gasCalculator()).cost(frame, () -> EMPTY_CODE);
    }

    @Override
    protected @NonNull Address setupPendingCreation(@NonNull final MessageFrame frame) {
        final var updater = (ProxyWorldUpdater) frame.getWorldUpdater();

        final var origin = frame.getRecipientAddress();
        final var originNonce = requireNonNull(updater.getAccount(origin)).getNonce();
        // Decrement nonce by 1 to normalize the effect of transaction execution
        final var address = Address.contractAddress(origin, originNonce - 1);

        updater.setupInternalAliasedCreate(origin, address);
        frame.warmUpAddress(address);
        return address;
    }
}

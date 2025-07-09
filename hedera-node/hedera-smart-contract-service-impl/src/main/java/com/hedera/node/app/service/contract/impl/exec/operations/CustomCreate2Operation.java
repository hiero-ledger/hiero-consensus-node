// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.operations.CustomizedOpcodes.CREATE2;
import static org.hyperledger.besu.evm.code.CodeV0.EMPTY_CODE;
import static org.hyperledger.besu.evm.internal.Words.clampedToLong;

import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Supplier;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Create2Operation;

/**
 * A Hedera customization of the Besu {@link org.hyperledger.besu.evm.operation.Create2Operation}.
 */
public class CustomCreate2Operation extends AbstractCustomCreateOperation {
    private static final Bytes EIP_1014_PREFIX = Bytes.fromHexString("0xFF");

    private final FeatureFlags featureFlags;

    /**
     * Constructor for custom create2 operations.
     * @param gasCalculator the gas calculator to use
     * @param featureFlags current evm module feature flags
     */
    public CustomCreate2Operation(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final CodeFactory codeFactory) {
        super(CREATE2.opcode(), "ħCREATE2", 4, 1, gasCalculator, codeFactory);
        this.featureFlags = featureFlags;
    }

    @Override
    protected boolean isEnabled(@NonNull final MessageFrame frame) {
        return featureFlags.isCreate2Enabled(frame);
    }

    /**
     * Returns the amount of gas the CREATE2 operation will consume.
     *
     * @param frame The current frame
     * @return the amount of gas the CREATE2 operation will consume
     * <p>Compose the operation cost from {@link GasCalculator#txCreateCost()}, {@link
     * GasCalculator#memoryExpansionGasCost(MessageFrame, long, long)}, {@link GasCalculator#createKeccakCost(int)}, and
     * {@link GasCalculator#initcodeCost(int)}. As done in {@link
     * org.hyperledger.besu.evm.operation.Create2Operation#cost(MessageFrame, Supplier)}
     */
    @Override
    protected long cost(@NonNull final MessageFrame frame) {
        return new Create2Operation(gasCalculator()).cost(frame, () -> EMPTY_CODE);
    }

    @Override
    protected @Nullable Address setupPendingCreation(@NonNull final MessageFrame frame) {
        final var alias = eip1014AddressFor(frame);
        final var updater = (ProxyWorldUpdater) frame.getWorldUpdater();
        // A bit arbitrary maybe, but if implicit creation isn't enabled, we also
        // don't support finalizing hollow accounts as contracts, so return null
        if (updater.isHollowAccount(alias) && !featureFlags.isImplicitCreationEnabled()) {
            return null;
        }
        updater.setupInternalAliasedCreate(frame.getRecipientAddress(), alias);
        frame.warmUpAddress(alias);
        return alias;
    }

    @Override
    protected void onSuccess(@NonNull final MessageFrame frame, @NonNull final Address creation) {
        // No-op, CustomContractCreationProcessor will finalize the creation address if a hollow account
    }

    private Address eip1014AddressFor(@NonNull final MessageFrame frame) {
        // If the recipient has an EIP-1014 address, it must have been used here
        final var creatorAddress = frame.getRecipientAddress();
        final var offset = clampedToLong(frame.getStackItem(1));
        final var length = clampedToLong(frame.getStackItem(2));
        final Bytes32 salt = UInt256.fromBytes(frame.getStackItem(3));
        final var initCode = frame.readMutableMemory(offset, length);
        final var hash = keccak256(Bytes.concatenate(EIP_1014_PREFIX, creatorAddress, salt, keccak256(initCode)));
        return Address.wrap(hash.slice(12, 20));
    }

    private Bytes32 keccak256(final Bytes input) {
        return Bytes32.wrap(keccak256DigestOf(input.toArrayUnsafe()));
    }

    private static byte[] keccak256DigestOf(final byte[] msg) {
        return new Keccak.Digest256().digest(msg);
    }
}

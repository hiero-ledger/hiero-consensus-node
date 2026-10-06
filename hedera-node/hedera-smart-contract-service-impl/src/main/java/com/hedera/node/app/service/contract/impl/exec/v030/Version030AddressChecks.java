// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v030;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * The initial implementation of {@link AddressChecks} from v0.30; did not have a concept of system accounts.
 */
@Singleton
public class Version030AddressChecks implements AddressChecks {
    private final int[] systemContractNumbers;
    private final HederaSystemContract[] systemContracts;

    @Inject
    public Version030AddressChecks(@NonNull final Map<Address, HederaSystemContract> systemContracts) {
        systemContractNumbers = new int[systemContracts.size()];
        this.systemContracts = new HederaSystemContract[systemContracts.size()];
        int i = 0;
        for (final var entry : systemContracts.entrySet()) {
            final var address = entry.getKey();
            if (address.getBytes().numberOfLeadingZeroBytes() != 18) {
                throw new IllegalArgumentException("Precompile address " + address + " is outside system range");
            }
            systemContractNumbers[i] = address.getBytes().getInt(16);
            this.systemContracts[i++] = entry.getValue();
        }
    }

    @Override
    public boolean isPresent(@NonNull final Address address, @NonNull final MessageFrame frame) {
        return isEnabledHederaPrecompile(address, frame)
                || frame.getWorldUpdater().get(address) != null;
    }

    @Override
    public boolean isSystemAccount(@NonNull final Address address) {
        return false;
    }

    @Override
    public boolean isNonUserAccount(@NonNull final Address address) {
        return false;
    }

    @Override
    public boolean isHederaPrecompile(@NonNull final Address address) {
        return address.getBytes().numberOfLeadingZeroBytes() >= 18
                && isPrecompile(address.getBytes().getInt(16));
    }

    private boolean isPrecompile(final int number) {
        for (final var precompileNumber : systemContractNumbers) {
            if (precompileNumber == number) {
                return true;
            }
        }
        return false;
    }

    private boolean isEnabledHederaPrecompile(@NonNull final Address address, @NonNull final MessageFrame frame) {
        if (address.getBytes().numberOfLeadingZeroBytes() < 18) {
            return false;
        }
        final int number = address.getBytes().getInt(16);
        for (int i = 0; i < systemContractNumbers.length; i++) {
            if (systemContractNumbers[i] == number) {
                // A disabled system contract's address is treated as if no system contract were registered there
                return !systemContracts[i].isDisabled(frame);
            }
        }
        return false;
    }
}

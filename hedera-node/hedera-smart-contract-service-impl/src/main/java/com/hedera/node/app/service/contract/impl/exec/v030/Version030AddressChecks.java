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

    @Inject
    public Version030AddressChecks(@NonNull final Map<Address, HederaSystemContract> systemContracts) {
        systemContractNumbers = new int[systemContracts.size()];
        int i = 0;
        for (final var address : systemContracts.keySet()) {
            if (address.numberOfLeadingZeroBytes() != 18) {
                throw new IllegalArgumentException("Precompile address " + address + " is outside system range");
            }
            systemContractNumbers[i++] = address.getInt(16);
        }
    }

    @Override
    public boolean isPresent(@NonNull final Address address, @NonNull final MessageFrame frame) {
        return isHederaPrecompile(address) || frame.getWorldUpdater().get(address) != null;
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
        return address.numberOfLeadingZeroBytes() >= 18 && isPrecompile(address.getInt(16));
    }

    private boolean isPrecompile(final int number) {
        for (final var precompileNumber : systemContractNumbers) {
            if (precompileNumber == number) {
                return true;
            }
        }
        return false;
    }
}

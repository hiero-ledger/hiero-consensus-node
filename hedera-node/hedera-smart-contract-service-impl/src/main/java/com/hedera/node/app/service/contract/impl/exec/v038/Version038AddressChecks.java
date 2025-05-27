// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v038;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;

import com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.v030.Version030AddressChecks;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.processor.AbstractMessageProcessor;

/**
 * The system-aware address checks for the 0.38+, which customize some {@link Operation} and
 * {@link AbstractMessageProcessor} behavior to treat system addresses below 0.0.750 as special.
 */
@Singleton
public class Version038AddressChecks extends Version030AddressChecks {
    private static final int FIRST_USER_ACCOUNT = 1_001;
    private final EntityIdFactory entityIdFactory;

    @Inject
    public Version038AddressChecks(
            @NonNull Map<Address, HederaSystemContract> systemContracts,
            @NonNull final EntityIdFactory entityIdFactory) {
        super(systemContracts);
        this.entityIdFactory = entityIdFactory;
    }

    @Override
    public boolean isSystemAccount(@NonNull final Address address) {
        if (address.numberOfLeadingZeroBytes() >= 18 && address.getInt(16) <= ProcessorModule.NUM_SYSTEM_ACCOUNTS) {
            return true;
        }
        return isLongZero(address) && numberOfLongZero(address.toArray()) <= ProcessorModule.NUM_SYSTEM_ACCOUNTS;
    }

    @Override
    public boolean isNonUserAccount(@NonNull final Address address) {
        if (address.numberOfLeadingZeroBytes() >= 18 && address.getInt(16) < FIRST_USER_ACCOUNT) {
            return true;
        }
        return isLongZero(address) && numberOfLongZero(address.toArray()) < FIRST_USER_ACCOUNT;
    }
}

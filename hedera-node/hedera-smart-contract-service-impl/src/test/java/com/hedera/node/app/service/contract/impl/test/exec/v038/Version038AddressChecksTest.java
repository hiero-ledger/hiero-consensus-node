// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.v038;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.contract.impl.exec.v038.Version038AddressChecks;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import java.util.Map;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class Version038AddressChecksTest {
    private Version038AddressChecks subject = new Version038AddressChecks(Map.of(), entityIdFactory);

    @Test
    void onlyBelow750IsSystem() {
        assertTrue(subject.isSystemAccount(Address.fromHexString(Integer.toHexString(749))));
        assertTrue(subject.isSystemAccount(Address.fromHexString(Integer.toHexString(750))));
        assertFalse(subject.isSystemAccount(Address.fromHexString(Integer.toHexString(751))));
        assertFalse(subject.isSystemAccount(TestHelpers.EIP_1014_ADDRESS));
    }

    @Test
    void onlyAbove1000IsUserAccount() {
        assertTrue(subject.isNonUserAccount(Address.fromHexString(Integer.toHexString(1000))));
        assertFalse(subject.isNonUserAccount(Address.fromHexString(Integer.toHexString(1001))));
        assertFalse(subject.isSystemAccount(TestHelpers.EIP_1014_ADDRESS));
    }
}

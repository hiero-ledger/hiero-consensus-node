// SPDX-License-Identifier: Apache-2.0
package com.swirlds.constructable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.constructable.ConstructableRegistryFactory;
import com.swirlds.common.constructable.ConstructorRegistry;
import com.swirlds.common.constructable.NoArgsConstructor;
import com.swirlds.common.merkle.utility.MerkleLong;
import org.junit.jupiter.api.Test;

public class ConstructableRegistryTest {
    @Test
    public void testRegisterClassesFromAnotherJPMSModule() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistryFactory.createConstructableRegistry();
        final long classId = new MerkleLong().getClassId();
        registry.registerConstructables(MerkleLong.class.getPackageName());

        final ConstructorRegistry<NoArgsConstructor> subRegistry = registry.getRegistry(NoArgsConstructor.class);
        assertNotNull(subRegistry.getConstructor(classId));
        assertNotNull(subRegistry.getConstructor(classId).get());
        assertEquals(classId, subRegistry.getConstructor(classId).get().getClassId());
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

/**
 * Guards the hook-execution policy of {@link CallManager#checkHookExec}: DELEGATECALL/CALLCODE must
 * be blocked whenever a hook owner is active, with no recipient/facade exemption. This mirrors the
 * canonical {@code CustomDelegateCallOperation}/{@code CustomCallCodeOperation} guard and prevents
 * regressing to the old {@code isRegularAccount()} exemption (VLN-349).
 */
class BonnevilleHookGuardTest {

    @Test
    void blocksWhenHookOwnerActive() throws Exception {
        assertTrue(checkHookExec(bevmWithHookOwner(Address.fromHexString("0x1234"))));
    }

    @Test
    void allowsWhenNoHookOwner() throws Exception {
        assertFalse(checkHookExec(bevmWithHookOwner(null)));
    }

    // A BEVM whose _top carries the given hook owner. _bonneville is never dereferenced by
    // checkHookExec, so a bare TopXTN(null) is enough to exercise the guard in isolation.
    private static BEVM bevmWithHookOwner(final Address hookOwner) throws Exception {
        final Constructor<TopXTN> topCtor = TopXTN.class.getDeclaredConstructor(BonnevilleEVM.class);
        topCtor.setAccessible(true);
        final TopXTN top = topCtor.newInstance((BonnevilleEVM) null);

        final Field hookOwnerField = TopXTN.class.getDeclaredField("_hookOwner");
        hookOwnerField.setAccessible(true);
        hookOwnerField.set(top, hookOwner);

        final BEVM bevm = new BEVM();
        final Field topField = BEVM.class.getDeclaredField("_top");
        topField.setAccessible(true);
        topField.set(bevm, top);
        return bevm;
    }

    private static boolean checkHookExec(final BEVM bevm) throws Exception {
        final Method m = CallManager.class.getDeclaredMethod("checkHookExec", BEVM.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, bevm);
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.profile;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.asYcDefaultNetworkKey;

import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hedera.services.bdd.spec.utilops.inventory.NewSpecKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.atomic.AtomicReference;

/**
 *  Represents a civilian profile with no special permissions
 */
public record Civilian(String suffix, long initialBalance) implements KnownProfileDefinition {
    // Default civilian profile
    public static final KnownProfileDefinition CIVILIAN = new Civilian();

    private static final long DEFAULT_INITIAL_BALANCE = 10 * ONE_HBAR;

    public Civilian() {
        this(null, DEFAULT_INITIAL_BALANCE);
    }

    /**
     * Creates a civilian profile with the given suffix and initial balance.
     * @param suffix the suffix to append to the civilian profile name; if null, no suffix is appended.
     *               This param is meant to distinguish between multiple civilian profiles
     * @param initialBalance the initial balance for the civilian's account
     */
    public Civilian(@Nullable final String suffix, final long initialBalance) {
        this.suffix = (suffix == null ? "" : suffix);
        this.initialBalance = initialBalance;
    }

    @Override
    public String name() {
        return "civilian" + suffix;
    }

    @Override
    public HapiCryptoCreate newAccount(@NonNull final AtomicReference<Long> civAcctNum) {
        return cryptoCreate(name())
                .key(keyName())
                .balance(initialBalance)
                .exposingCreatedIdTo(acct -> civAcctNum.set(acct.getAccountNum()));
    }

    @Override
    public NewSpecKey newKey() {
        final var civKey = keyName();
        return newKeyNamed(civKey)
                .shape(SigControl.ED25519_ON)
                .exportingTo(() -> asYcDefaultNetworkKey(civKey + ".pem"), "keypass");
    }

    private String keyName() {
        return name() + "Key";
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import static com.hedera.services.bdd.spec.keys.SigControl.KeyAlgo.UNSPECIFIED;
import static com.hedera.services.bdd.spec.keys.SigControl.Nature.*;

import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;

public class SigControl implements Serializable {
    private static final long serialVersionUID = 1L;

    private static boolean isContract(final Nature nature) {
        return nature == CONTRACT_ID || nature == DELEGATABLE_CONTRACT_ID;
    }

    public enum Nature {
        SIG_ON,
        SIG_OFF,
        LIST,
        THRESHOLD,
        CONTRACT_ID,
        DELEGATABLE_CONTRACT_ID,
        PREDEFINED
    }

    public enum KeyAlgo {
        UNSPECIFIED,
        ED25519,
        SECP256K1
    }

    private final Nature nature;
    private int threshold = -1;
    private String contract;
    private String predefined;
    private String delegatableContract;
    private SigControl[] childControls = new SigControl[0];

    protected KeyAlgo keyAlgo = UNSPECIFIED;

    public static final SigControl ON = new SigControl(SIG_ON);
    public static final SigControl OFF = new SigControl(SIG_OFF);
    public static final SigControl ED25519_ON = new SigControl(SIG_ON, KeyAlgo.ED25519);
    public static final SigControl ED25519_OFF = new SigControl(SIG_OFF, KeyAlgo.ED25519);
    public static final SigControl SECP256K1_ON = new SigControl(SIG_ON, KeyAlgo.SECP256K1);
    public static final SigControl SECP256K1_OFF = new SigControl(SIG_OFF, KeyAlgo.SECP256K1);
    public static final SigControl ANY = new SigControl(SIG_ON);

    public String predefined() {
        return predefined;
    }

    public KeyAlgo keyAlgo() {
        return keyAlgo;
    }

    public String contract() {
        return contract;
    }

    public String delegatableContract() {
        return delegatableContract;
    }

    public Nature getNature() {
        return nature;
    }

    public int getThreshold() {
        return threshold;
    }

    public SigControl[] getChildControls() {
        return childControls;
    }

    public int numSimpleKeys() {
        return countSimpleKeys(this);
    }

    private int countSimpleKeys(SigControl controller) {
        if (isContract(controller.nature)) {
            return 0;
        }

        return (EnumSet.of(SIG_ON, SIG_OFF).contains(controller.nature))
                ? 1
                : Stream.of(controller.childControls)
                        .mapToInt(this::countSimpleKeys)
                        .sum();
    }

    public boolean appliesTo(Key key) {
        if (this == ON || this == OFF) {
            return (!key.hasKeyList() && !key.hasThresholdKey());
        } else if (nature == CONTRACT_ID) {
            return key.hasContractID();
        } else if (nature == DELEGATABLE_CONTRACT_ID) {
            return key.hasDelegatableContractId();
        } else {
            KeyList composite = TxnUtils.getCompositeList(key);
            if (composite.getKeysCount() == childControls.length) {
                return IntStream.range(0, childControls.length)
                        .allMatch(i -> childControls[i].appliesTo(composite.getKeys(i)));
            } else {
                return false;
            }
        }
    }

    public static SigControl listSigs(SigControl... childControls) {
        Assertions.assertTrue(childControls.length > 0, "A list must have at least one child key!");
        return new SigControl(childControls);
    }

    public static SigControl threshSigs(int M, SigControl... childControls) {
        Assertions.assertTrue(childControls.length > 0, "A threshold must have at least one child key!");
        return new SigControl(M, childControls);
    }

    public static SigControl emptyList() {
        return new SigControl(LIST);
    }

    protected SigControl(Nature nature) {
        this.nature = nature;
    }

    protected SigControl(Nature nature, KeyAlgo keyAlgo) {
        this.nature = nature;
        this.keyAlgo = keyAlgo;
    }

    protected SigControl(final Nature nature, final String id) {
        this.nature = nature;
        if (nature == PREDEFINED) {
            this.predefined = id;
        } else {
            if (!isContract(nature)) {
                throw new IllegalArgumentException("Contract " + id + " n/a to nature " + nature);
            }
            if (nature == CONTRACT_ID) {
                this.contract = id;
            } else {
                this.delegatableContract = id;
            }
        }
    }

    protected SigControl(SigControl... childControls) {
        nature = Nature.LIST;
        this.childControls = childControls;
    }

    protected SigControl(int threshold, SigControl... childControls) {
        nature = Nature.THRESHOLD;
        this.threshold = threshold;
        this.childControls = childControls;
    }

    @Override
    public String toString() {
        return "SigControl{"
                + "nature="
                + nature
                + ", keyAlgo="
                + keyAlgo
                + ", threshold="
                + threshold
                + ", childControls="
                + Arrays.toString(childControls)
                + '}';
    }
}

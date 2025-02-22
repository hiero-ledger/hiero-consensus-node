// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import static com.hedera.services.bdd.spec.keys.SigControl.Nature.CONTRACT_ID;

import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class KeyShape extends SigControl {
    public static final KeyShape SIMPLE = new KeyShape(Nature.SIG_ON);
    public static final KeyShape ED25519 = new KeyShape(Nature.SIG_ON, KeyAlgo.ED25519);
    public static final KeyShape SECP256K1 = new KeyShape(Nature.SIG_ON, KeyAlgo.SECP256K1);
    public static final KeyShape CONTRACT = new KeyShape(CONTRACT_ID);
    public static final KeyShape PREDEFINED_SHAPE = new KeyShape(Nature.PREDEFINED);
    public static final KeyShape DELEGATE_CONTRACT = new KeyShape(Nature.DELEGATABLE_CONTRACT_ID);

    protected KeyShape(SigControl.Nature nature) {
        super(nature);
    }

    protected KeyShape(SigControl.Nature nature, KeyAlgo keyAlgo) {
        super(nature, keyAlgo);
    }

    protected KeyShape(SigControl... childControls) {
        super(childControls);
    }

    protected KeyShape(int threshold, SigControl... childControls) {
        super(threshold, childControls);
    }

    public static KeyShape listOf(int N) {
        return listOf(IntStream.range(0, N).mapToObj(ignore -> SIMPLE).toArray(KeyShape[]::new));
    }

    public static KeyShape listOf(KeyShape... childShapes) {
        return new KeyShape(childShapes);
    }

    public static KeyShape threshOf(int M, int N) {
        if (M > N) {
            throw new IllegalArgumentException("A threshold key requires M <= N");
        }
        return threshOf(M, IntStream.range(0, N).mapToObj(ignore -> SIMPLE).toArray(KeyShape[]::new));
    }

    public static KeyShape threshOf(int M, KeyShape... childShapes) {
        return new KeyShape(M, childShapes);
    }

    public static KeyShape randomly(
            int depthAtMost,
            IntSupplier listSizeSupplier,
            Supplier<KeyFactory.KeyType> typeSupplier,
            Supplier<Map.Entry<Integer, Integer>> thresholdSizesSupplier) {
        KeyFactory.KeyType type = (depthAtMost == 1) ? KeyFactory.KeyType.SIMPLE : typeSupplier.get();
        switch (type) {
            case SIMPLE:
                return SIMPLE;
            case LIST:
                int listSize = listSizeSupplier.getAsInt();
                return listOf(randomlyListing(
                        listSize, depthAtMost - 1, listSizeSupplier, typeSupplier, thresholdSizesSupplier));
            case THRESHOLD:
                Map.Entry<Integer, Integer> mOfN = thresholdSizesSupplier.get();
                int M = mOfN.getKey(), N = mOfN.getValue();
                return threshOf(
                        M, randomlyListing(N, depthAtMost - 1, listSizeSupplier, typeSupplier, thresholdSizesSupplier));
        }
        throw new IllegalStateException("Unanticipated key type - " + type);
    }

    private static KeyShape[] randomlyListing(
            int N,
            int depthAtMost,
            IntSupplier listSizeSupplier,
            Supplier<KeyFactory.KeyType> typeSupplier,
            Supplier<Map.Entry<Integer, Integer>> thresholdSizesSupplier) {
        return IntStream.range(0, N)
                .mapToObj(ignore -> randomly(depthAtMost, listSizeSupplier, typeSupplier, thresholdSizesSupplier))
                .toArray(KeyShape[]::new);
    }

    public static List<Object> sigs(Object... controls) {
        return List.of(controls);
    }

    @SuppressWarnings("unchecked")
    public SigControl signedWith(Object control) {
        if (this == SIMPLE || this == ED25519 || this == SECP256K1) {
            if (!(control instanceof SigControl)) {
                throw new IllegalArgumentException("Shape is simple but multiple controls given!");
            }
            final var reqControl = (SigControl) control;
            switch (keyAlgo) {
                default:
                case UNSPECIFIED:
                    return reqControl.getNature() == Nature.SIG_ON ? SigControl.ON : SigControl.OFF;
                case ED25519:
                    return reqControl.getNature() == Nature.SIG_ON ? SigControl.ED25519_ON : SigControl.ED25519_OFF;
                case SECP256K1:
                    return reqControl.getNature() == Nature.SIG_ON ? SigControl.SECP256K1_ON : SigControl.SECP256K1_OFF;
            }
        } else if (this == CONTRACT || this == DELEGATE_CONTRACT || this == PREDEFINED_SHAPE) {
            if (control instanceof String id) {
                return new SigControl(this.getNature(), id);
            } else {
                throw new IllegalArgumentException(
                        "Shape is " + this.getNature() + " but " + control + " not a contract ref or key name");
            }
        } else {
            KeyShape[] childShapes = (KeyShape[]) getChildControls();
            int size = childShapes.length;
            final var controls = (List<Object>) control;
            if (size != controls.size()) {
                final var errMsg = "Shape is "
                        + this.getNature()
                        + "[n="
                        + size
                        + (this.getNature().equals(Nature.THRESHOLD) ? ",m=" + this.getThreshold() : "")
                        + "] but "
                        + controls.size()
                        + " controls given";
                throw new IllegalArgumentException(errMsg);
            }
            final var childControls = IntStream.range(0, size)
                    .mapToObj(i -> childShapes[i].signedWith(controls.get(i)))
                    .toArray(SigControl[]::new);
            if (this.getNature() == Nature.LIST) {
                return listSigs(childControls);
            } else {
                return threshSigs(this.getThreshold(), childControls);
            }
        }
    }
}

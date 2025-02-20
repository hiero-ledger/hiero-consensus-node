// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import static com.hedera.services.bdd.spec.keys.SigControl.Nature.SIG_OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.Nature.SIG_ON;
import static java.util.stream.Collectors.joining;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class KeyLabels {
    public enum Kind {
        SIMPLE,
        COMPLEX
    }

    private String label;
    private KeyLabels[] constituents;

    private final Kind kind;

    private KeyLabels(Kind kind, String label) {
        this.kind = kind;
        this.label = label;
    }

    private KeyLabels(Kind kind, KeyLabels[] constituents) {
        this.kind = kind;
        this.constituents = constituents;
    }

    public KeyLabels[] getConstituents() {
        return constituents;
    }

    public String literally() {
        return label;
    }

    public static KeyLabels simple(String label) {
        return new KeyLabels(Kind.SIMPLE, label);
    }

    public static KeyLabels complex(Object... objs) {
        KeyLabels[] constituents = Stream.of(objs)
                .map(obj -> (obj instanceof KeyLabels) ? obj : simple((String) obj))
                .toArray(n -> new KeyLabels[n]);
        return new KeyLabels(Kind.COMPLEX, constituents);
    }

    public static KeyLabels uniquelyLabeling(SigControl control) {
        return uniquelyLabeling(control, new AtomicInteger(0));
    }

    private static KeyLabels uniquelyLabeling(SigControl control, AtomicInteger id) {
        if (EnumSet.of(SIG_ON, SIG_OFF).contains(control.getNature())) {
            int idHere = id.incrementAndGet();
            return simple("" + idHere);
        } else {
            SigControl[] children = control.getChildControls();
            return complex(Stream.of(children)
                    .map(child -> uniquelyLabeling(child, id))
                    .toArray());
        }
    }

    @Override
    public String toString() {
        switch (kind) {
            default:
                return label;
            case COMPLEX:
                return new StringBuilder()
                        .append("[")
                        .append(Stream.of(constituents).map(KeyLabels::toString).collect(joining(", ")))
                        .append("]")
                        .toString();
        }
    }
}

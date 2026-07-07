// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

/**
 * The kind of code reference an anchor makes. Also names the check ("resolver") applied to it, and
 * so is part of a finding's stable identity. Tiers follow the spec: Tier 0 = text/filesystem,
 * Tier 1 = symbol existence, Tier 2 = signature/value equality.
 */
public enum AnchorKind {
    // ---- Tier 0: text / filesystem existence ----
    /** A link or path pointing at a module directory. */
    MODULE_DIR(0),
    /** A path to a source file (from {@code components:}, markdown links, or abbreviated form). */
    SOURCE_PATH(0),
    /** A relative link from one KB doc to another. */
    CROSS_DOC_LINK(0),
    /** A {@code #heading} fragment that must exist in the linked doc. */
    DOC_HEADING(0),
    /** A catalog-ID reference (INV/TUN/ADR/SYM/RUL/SCN/HEU-NNN). */
    CATALOG_ID(0),

    // ---- Tier 1: symbol existence ----
    /** A class/type cited with an owning module or path. */
    CLASS(1),
    /** A method named on a resolvable class (e.g. {@code verification:} "Class — method"). */
    METHOD_ON_CLASS(1),
    /** A method reference of the form {@code Class::method}. */
    METHOD_REF(1),
    /** An enum constant, e.g. {@code EventOrigin.RUNTIME}. */
    ENUM_CONSTANT(1),
    /** A config key resolved against its {@code *Config} class. */
    CONFIG_KEY(1),

    // ---- Tier 2: signature / value equality ----
    /** A method cited with a parameter list, e.g. {@code Class.method(ParamType)}, checked for signature equality. */
    METHOD_SIGNATURE(2),
    /** A documented interface method compared against the interface's declared method set. */
    INTERFACE_METHOD(2);

    /** The resolution tier of this kind (0 = text/filesystem, 1 = symbol, 2 = signature/value). */
    private final int tier;

    /**
     * Creates a kind with its resolution tier.
     *
     * @param tier the resolution tier of the check for this kind.
     */
    AnchorKind(final int tier) {
        this.tier = tier;
    }

    /**
     * Returns the resolution tier of this kind.
     *
     * @return the tier: 0 for text/filesystem, 1 for symbol existence, 2 for signature/value.
     */
    public int tier() {
        return tier;
    }
}

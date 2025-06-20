// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.authorization;

/**
 * The possible results of a system privilege check.
 */
public enum SystemPrivilege {
    /** The operation does not require any system privileges. */
    UNNECESSARY,

    /** The operation requires system privileges that its payer does not have. */
    UNAUTHORIZED,

    /** The operation cannot be performed, no matter the privileges of its payer. */
    IMPERMISSIBLE,

    /** The operation requires system privileges, and its payer has those privileges. */
    AUTHORIZED;
}

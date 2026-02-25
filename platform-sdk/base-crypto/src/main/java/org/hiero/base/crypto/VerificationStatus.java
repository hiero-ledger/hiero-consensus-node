// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

/**
 * Indicates whether a Signature is awaiting verification, valid, or invalid.
 */
public enum VerificationStatus {
    /** A signature that has not yet been verified or is pending verification */
    UNKNOWN,

    /** A valid signature */
    VALID,

    /** An invalid signature, possibly due to payload corruption or signature tampering */
    INVALID
}

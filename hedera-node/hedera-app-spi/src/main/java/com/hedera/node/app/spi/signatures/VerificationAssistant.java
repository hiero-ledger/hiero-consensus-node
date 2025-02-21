// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.signatures;

import com.hedera.hapi.node.base.Key;
import java.util.function.BiPredicate;

/**
 * A BiPredicate specialized to assisting and observing the Signature Verification process. Implementations of this
 * interface are expected to, in the test method, return true if and only if the Key passed should be considered valid.
 * This may be based on both the {@link SignatureVerification} provided, as well as other information (such as a set of
 * previously valid keys in state). The "test" method is also an observer of all keys and associated verifications, and
 * may choose to store or further inspect each such key and SignatureVerification.
 * <p>
 * The "test" method will only be called for keys of the "primitive", "Contract ID", and "Delegatable Contract ID"
 * types.
 */
public interface VerificationAssistant extends BiPredicate<Key, SignatureVerification> {}

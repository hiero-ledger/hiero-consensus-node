// SPDX-License-Identifier: Apache-2.0
package com.x;

/** Fixture: a class whose method {@code run} sits below Javadoc and an annotation, for signature-line testing. */
public class AnnotatedMethod {

    /** Javadoc above the annotation, which must not shift the signature line either. */
    @Deprecated
    public void run() {}
}

// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable;

import java.util.Random;

/**
 * Generates random long to use as a class ID for RuntimeConstructable.
 */
public final class GenerateClassId {

    private GenerateClassId() {}

    public static void main(final String[] args) {
        generateAndPrintClassId();
    }

    /**
     * Generate a class ID and print it to standard out.
     */
    public static void generateAndPrintClassId() {
        System.out.printf(
                """
						
						\tprivate static final long CLASS_ID = 0x%sL;

						\tprivate static final class ClassVersion {
						\t\tpublic static final int ORIGINAL = 1;
						\t}

						\t/**
						\t * {@inheritDoc}
						\t */
						\t@Override
						\tpublic long getClassId() {
						\t\treturn CLASS_ID;
						\t}

						\t/**
						\t * {@inheritDoc}
						\t */
						\t@Override
						\tpublic int getVersion() {
						\t\treturn ClassVersion.ORIGINAL;
						\t}
						""",
                Long.toHexString(new Random().nextLong()));
    }
}

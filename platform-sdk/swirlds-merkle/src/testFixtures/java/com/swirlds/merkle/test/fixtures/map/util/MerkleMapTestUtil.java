// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.util;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.test.fixtures.dummy.Key;
import com.swirlds.common.test.fixtures.dummy.Value;
import com.swirlds.merkle.tree.MerkleBinaryTree;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

public final class MerkleMapTestUtil {

    private MerkleMapTestUtil() {}

    public static final Random random = new Random();

    /**
     * Path to log4j2.xml (which might not exist)
     */
    private static final File LOG_PATH = canonicalFile(".", "src", "test", "resources", "log4j2-test.xml");

    /**
     * Given a sequence of directory and file names, such as {".","sdk","test","..","config.txt"}, convert
     * it to a File in canonical form, such as /full/path/sdk/config.txt by assuming it starts in the
     * current working directory (which is the same as System.getProperty("user.dir")).
     *
     * @param names
     * 		the sequence of names
     * @return the File in canonical form
     */
    private static File canonicalFile(String... names) {
        return canonicalFile(new File("."), names);
    }

    /**
     * Given a starting directory and a sequence of directory and file names, such as "sdk" and
     * {"data","test","..","config.txt"}, convert it to a File in canonical form, such as
     * /full/path/sdk/data/config.txt by assuming it starts in the current working directory (which is the
     * same as System.getProperty("user.dir")).
     *
     * @param names
     * 		the sequence of names
     * @return the File in canonical form, or null if there are any errors
     */
    private static File canonicalFile(File start, String... names) {
        File f = start;
        try {
            f = f.getCanonicalFile();
            for (String name : names) {
                f = new File(f, name).getCanonicalFile();
            }
        } catch (IOException e) {
            f = null;
        }
        return f;
    }

    public static void loadLogging() {
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.setConfigLocation(MerkleMapTestUtil.LOG_PATH.toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A no-op implementation of an updateCache method. Useful for tests where we dont' care about a cache
     */
    public static <V extends MerkleNode & Keyed<?>> void updateCache(final V original) {
        // no-op implementation
    }

    public static void insertIntoMap(final int size, final Map<Key, Value> fcm) {
        insertIntoMap(0, size, fcm);
    }

    public static void insertIntoMap(final int startIndex, final int endIndex, final Map<Key, Value> fcm) {
        MapMutatorSet.insertKeyValueIntoMap(startIndex, endIndex, fcm);
    }

    public static void insertIntoTreeWithoutBalanceCheck(
            final int startIndex, final int endIndex, final MerkleBinaryTree<Value> tree) {
        for (int index = startIndex; index < endIndex; index++) {
            final Key key = new Key(new long[] {index, index, index});
            final Value value = new Value(index, index, index, true);
            value.setKey(key);
            tree.insert(value, MerkleMapTestUtil::updateCache);
        }
    }
}

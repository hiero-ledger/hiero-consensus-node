// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Utils {

    private static final Logger logger = LogManager.getLogger(Utils.class);

    private Utils() {
        // do not instantiate
    }

    public static String buildVersion() {
        try (InputStream is = Utils.class.getClassLoader().getResourceAsStream("git.properties")) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                return String.format(
                        "%s (%s)",
                        p.getProperty("git.build.version", "?version?"),
                        p.getProperty("git.commit.id.abbrev", "?commit?"));
            }
        } catch (IOException ignore) {
        }
        return "(unknown)";
    }

    public static void deleteRecursively(Path path) {
        if (Files.notExists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    logger.warn("Couldn't delete {}: {}", p, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void printClassHistogram(int topN) {
        try {
            ObjectName objectName = new ObjectName("com.sun.management:type=DiagnosticCommand");
            String operationName = "gcClassHistogram";
            Object[] params = {null};
            String[] signature = {String[].class.getName()};

            MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
            String result = (String) mbeanServer.invoke(objectName, operationName, params, signature);

            StringBuilder sb = new StringBuilder("Class Histogram:\n");
            if (topN > 0) {
                String[] lines = result.split("\n");
                Arrays.stream(lines).limit(topN).forEach(line -> sb.append(line).append("\n"));
                sb.append(" ...\n").append(lines[lines.length - 1]);
            } else {
                sb.append(result);
            }
            logger.info(sb);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /* Random utils */

    public static long randomLong() {
        return ThreadLocalRandom.current().nextLong();
    }

    public static long randomLong(long bound) {
        return ThreadLocalRandom.current().nextLong(bound);
    }

    public static int randomInt(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    public static void toBytes(long seed, byte[] bytes) {
        long val = seed;
        for (int i = 0; i < bytes.length; ++i) {
            bytes[i] = (byte) val;
            val = (val >>> 8) | (val << 56);
        }
    }

    public static long fromBytes(byte[] bytes) {
        long val = 0;
        for (int i = 0; i < Math.min(bytes.length, 8); ++i) {
            val |= ((long) bytes[i] & 0xff) << (i * 8);
        }
        return val;
    }

    public static long fromBytes(Bytes bytes) {
        long val = 0;
        for (int i = 0; i < Math.min(bytes.length(), 8); ++i) {
            val |= ((long) bytes.getByte(i) & 0xff) << (i * 8);
        }
        return val;
    }
}

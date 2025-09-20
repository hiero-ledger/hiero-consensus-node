// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;

public class FileWritingCrashExperimentTest {
    private static final Logger logger = LogManager.getLogger(FileWritingCrashExperimentTest.class);
    private static final int REGION_SIZE = 4096;
    private static final String MEMAP_SOURCE =
            """
            import java.io.*;
            import java.nio.channels.FileChannel;
            import java.nio.MappedByteBuffer;
            import sun.misc.Unsafe;

            public class %s {
                public static void main(String[] args) throws Exception {
                    System.setProperty("jdk.lang.processExitOnOOM", "true");
                    if (args.length != 1) {
                        System.err.println("Expected one file name argument");
                        System.exit(1);
                    }
                    int REGION_SIZE = %d;
                    byte[] writeBuff = new byte[REGION_SIZE];
                    for (int i = 0; i < writeBuff.length; i++) {
                        writeBuff[i] = (byte)(%s);
                    }

                    File file = new File(args[0]);
                     System.out.println(": " + args[0]);
                    try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
                         FileChannel fc = raf.getChannel()) {

                        MappedByteBuffer mappedBuffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, REGION_SIZE);

                        mappedBuffer.put(writeBuff);
                        if (%s) {
                          Thread.sleep(600000);
                          return;
                        }

                        // No force() call
                        double decision = Math.random();
                        if (decision < 0.5) {
                            Runtime.getRuntime().halt(42);
                        } else {
                            Unsafe.getUnsafe().putAddress(0L, 42L); // SIGSEGV
                        }
                    }
                }
            }
            """;

    private static final String FILE_CHANNEL_SOURCE =
            """
                import java.io.*;
                import java.nio.ByteBuffer;
                import java.nio.channels.FileChannel;
                import java.nio.file.StandardOpenOption;
                import sun.misc.Unsafe;

                public class %s {
                  public static void main(String[] args) throws Exception {
                      System.setProperty("jdk.lang.processExitOnOOM", "true");

                      if (args.length != 1) {
                          System.err.println("Expected one file name argument");
                          System.exit(1);
                      }

                      int REGION_SIZE = %d;
                      byte[] writeBuff = new byte[REGION_SIZE];
                      for (int i = 0; i < writeBuff.length; i++) {
                          writeBuff[i] = (byte)(%s);
                      }

                      File file = new File(args[0]);

                      FileChannel fc = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

                      ByteBuffer buffer = ByteBuffer.wrap(writeBuff);

                      fc.write(buffer);
                      // No force() call

                      if (%s) {
                        Thread.sleep(600000);
                        return;
                      }

                      double decision = Math.random();
                      if (decision < 0.5) {
                          Runtime.getRuntime().halt(42);
                      } else {
                          Unsafe.getUnsafe().putAddress(0L, 42L); // SIGSEGV
                      }

                  }
                }
            """;

    private static final String FILE_CHANNEL_DSYNC_SOURCE =
            """
                import java.io.*;
                import java.nio.ByteBuffer;
                import java.nio.channels.FileChannel;
                import java.nio.file.StandardOpenOption;
                import sun.misc.Unsafe;

                public class %s {
                  public static void main(String[] args) throws Exception {
                      System.setProperty("jdk.lang.processExitOnOOM", "true");

                      if (args.length != 1) {
                          System.err.println("Expected one file name argument");
                          System.exit(1);
                      }

                      int REGION_SIZE = %d;
                      byte[] writeBuff = new byte[REGION_SIZE];
                      for (int i = 0; i < writeBuff.length; i++) {
                          writeBuff[i] = (byte)(%s);
                      }

                      File file = new File(args[0]);

                        FileChannel fc = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.DSYNC);

                        ByteBuffer buffer = ByteBuffer.wrap(writeBuff);

                        fc.write(buffer);
                        // No force() call

                        if (%s) {
                            Thread.sleep(600000);
                            return;
                        }
                        double decision = Math.random();
                        if (decision < 0.5) {
                            Runtime.getRuntime().halt(42);
                        } else {
                            Unsafe.getUnsafe().putAddress(0L, 42L); // SIGSEGV
                        }

                  }
                }
            """;

    private static final String MULTI_THREAD_RUN =
            """
            import java.io.*;
            import java.nio.channels.FileChannel;
            import java.nio.MappedByteBuffer;
            import sun.misc.Unsafe;

            public class %s {
                public static void main(String[] args) throws Exception {
                    System.setProperty("jdk.lang.processExitOnOOM", "true");
                    if (args.length != 1) {
                        System.err.println("Expected one file name argument");
                        System.exit(1);
                    }
                    int REGION_SIZE = %d;
                    new Thread(() -> {
                        try {
                            byte[] writeBuff = new byte[REGION_SIZE];
                            for (int i = 0; i < writeBuff.length; i++) {
                                writeBuff[i] = (byte)(%s);
                            }
                            File file = new File(args[0]);
                            System.out.println(": " + args[0]);
                            try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
                                FileChannel fc = raf.getChannel()) {
                                MappedByteBuffer mappedBuffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, REGION_SIZE);
                                mappedBuffer.put(writeBuff);
                                if (%s) {
                                  Thread.sleep(600000);
                                  return;
                                }
                                // No force() call
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }).run();

                  new Thread(() -> {
                    double decision = Math.random();
                    if (decision < 0.5) {
                        Runtime.getRuntime().halt(42);
                    } else {
                        Unsafe.getUnsafe().putAddress(0L, 42L); // SIGSEGV
                    }
                  }).run();
                }
            }
            """;
    public static final Path EXPECTED_FILE = Path.of("expected.dat");
    static Path tempDir;

    @BeforeAll
    static void before() throws IOException {
        logger.info("{} before test", FileWritingCrashExperimentTest.class.toString());
        tempDir = Files.createTempDirectory("dynjava");
        compile("MemMapCrashWriter", MEMAP_SOURCE.formatted("MemMapCrashWriter", REGION_SIZE, "'a' + i%26", "false"));
        compile(
                "FileChannelCrashWriter",
                FILE_CHANNEL_SOURCE.formatted("FileChannelCrashWriter", REGION_SIZE, "'a' + i%26", "false"));
        compile(
                "FileChannelDsyncCrashWriter",
                FILE_CHANNEL_DSYNC_SOURCE.formatted("FileChannelDsyncCrashWriter", REGION_SIZE, "'a' + i%26", "false"));

        compile(
                "MemMapCrashWriterSleep",
                MEMAP_SOURCE.formatted("MemMapCrashWriterSleep", REGION_SIZE, "'a' + i%26", "true"));
        compile(
                "FileChannelCrashWriterSleep",
                FILE_CHANNEL_SOURCE.formatted("FileChannelCrashWriterSleep", REGION_SIZE, "'a' + i%26", "true"));
        compile(
                "FileChannelDsyncCrashWriterSleep",
                FILE_CHANNEL_DSYNC_SOURCE.formatted(
                        "FileChannelDsyncCrashWriterSleep", REGION_SIZE, "'a' + i%26", "true"));

        compile(
                "MultiThreadedCrashFileChannelExperiment",
                MULTI_THREAD_RUN.formatted(
                        "MultiThreadedCrashFileChannelExperiment", REGION_SIZE, "'a' + i%26", "false"));
        createExpectedFile();
    }

    @RepeatedTest(10)
    public void testMappedFile() throws Exception {

        final Path resultingFile = executeAndGetResult("MemMapCrashWriter");
        compare(EXPECTED_FILE, resultingFile);

        Files.deleteIfExists(resultingFile);
    }

    @RepeatedTest(10)
    public void testFileChannel() throws Exception {

        final Path resultingFile = executeAndGetResult("FileChannelCrashWriter");
        Thread.sleep(1000);
        compare(EXPECTED_FILE, resultingFile);

        Files.deleteIfExists(resultingFile);
    }

    @RepeatedTest(10)
    public void testFileChannelSleep() throws Exception {
        var resultingFile = UUID.randomUUID().toString();
        final var process = startProcess("FileChannelCrashWriterSleep", resultingFile);
        Thread.sleep(1000); // give enough time to reach the sleep
        kill(process.pid());
        compare(EXPECTED_FILE, Path.of(resultingFile));

        Files.deleteIfExists(Path.of(resultingFile));
    }

    @RepeatedTest(10)
    public void testMappedFileSleep() throws Exception {
        var resultingFile = UUID.randomUUID().toString();
        final var process = startProcess("MemMapCrashWriterSleep", resultingFile);
        Thread.sleep(1000); // give enough time to reach the sleep
        kill(process.pid());
        compare(EXPECTED_FILE, Path.of(resultingFile));

        Files.deleteIfExists(Path.of(resultingFile));
    }

    @RepeatedTest(10)
    public void testFileChannelFileSync() throws Exception {

        final Path resultingFile = executeAndGetResult("FileChannelDsyncCrashWriter");
        Thread.sleep(1000);
        compare(EXPECTED_FILE, resultingFile);

        Files.deleteIfExists(resultingFile);
    }

    @RepeatedTest(10)
    public void testFileChannelFileSyncSleep() throws Exception {
        var resultingFile = UUID.randomUUID().toString();
        final var process = startProcess("FileChannelDsyncCrashWriterSleep", resultingFile);
        Thread.sleep(1000); // give enough time to reach the sleep
        kill(process.pid());
        compare(EXPECTED_FILE, Path.of(resultingFile));

        Files.deleteIfExists(Path.of(resultingFile));
    }

    @RepeatedTest(10)
    public void testMultithreadedRun() throws Exception {
        var resultingFile = UUID.randomUUID().toString();
        final var process = startProcess("MultiThreadedCrashFileChannelExperiment", resultingFile);
        Thread.sleep(1000); // give enough time to reach the sleep
        kill(process.pid());
        compare(EXPECTED_FILE, Path.of(resultingFile));

        Files.deleteIfExists(Path.of(resultingFile));
    }

    @AfterAll
    static void after() throws IOException {
        Files.deleteIfExists(EXPECTED_FILE);
    }

    /**
     * Compiles a java source string
     */
    private static void compile(String className, String javaCode) throws IOException {
        Path javaFile = tempDir.resolve(className + ".java");
        Files.writeString(javaFile, javaCode);
        logger.info("Created source at: {}", javaFile);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(
                null,
                null,
                null,
                "--add-exports",
                "jdk.unsupported/sun.misc=ALL-UNNAMED",
                "-Xlint:-options",
                "-proc:none",
                "-Xlint:none",
                javaFile.toString());
        if (result != 0) {
            throw new RuntimeException("Compilation failed");
        }
    }

    /**
     * Compares the two pats using the shasum process result
     */
    private static void compare(Path expectedFile, Path resultingFile) throws Exception {
        final String expectedChs = checkSum(expectedFile);
        final long expectedSize = Files.size(expectedFile);
        final String resultingChs = checkSum(resultingFile);
        final long resultingSize = Files.size(resultingFile);
        logger.info("Expected checksum:{} obtained: {}", expectedChs, resultingChs);
        logger.info("Expected filesize:{} obtained:{}", expectedSize, resultingSize);
        System.out.printf("Expected checksum:%s obtained: %s%n", expectedChs, resultingChs);
        assertEquals(expectedChs, resultingChs);
        assertEquals(expectedSize, resultingSize);
    }

    /**
     * Executes one of the compiled java classes and return the result of the generated file
     */
    private static Path executeAndGetResult(final String className) throws Exception {
        var testFile = UUID.randomUUID().toString();
        final Process process = startProcess(className, testFile);
        int exit = process.waitFor();
        process.destroy();
        logger.info("Process exited with code: {}", exit);
        return Path.of(testFile);
    }

    /**
     * Starts one of the compiled java classes
     */
    private static Process startProcess(final String className, final String testFile) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "--add-exports",
                "jdk.unsupported/sun.misc=ALL-UNNAMED",
                "-cp",
                tempDir.toString(),
                className,
                testFile);
        pb.inheritIO();
        return pb.start();
    }

    /**
     * Compute SHA256 checksum of a file (Linux/macOS)
     */
    private static String checkSum(Path val) throws Exception {
        ProcessBuilder pb =
                new ProcessBuilder("shasum", "-a", "256", val.toFile().getAbsolutePath());
        Process process = pb.start();

        String line;
        StringBuilder builder = new StringBuilder();
        // Read output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while ((line = reader.readLine()) != null) {
                builder.append(line.split(" ")[0]);
            }
        }
        return builder.toString();
    }

    /**
     * Kills a process with kill -9 (Linux/macOS)
     */
    private static void kill(Long pid) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("kill", "-9", pid.toString());
        Process process = pb.start();
        process.waitFor();
        logger.info("killed process {}. result: {}", pid, process.exitValue());
        process.destroy();
    }

    /**
     * Creates the same test file each source is producing for the purposes of comparing it
     */
    private static void createExpectedFile() {
        try (RandomAccessFile raf = new RandomAccessFile(FileWritingCrashExperimentTest.EXPECTED_FILE.toFile(), "rw");
                FileChannel fc = raf.getChannel()) {

            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, REGION_SIZE);
            byte[] writeBuff = new byte[REGION_SIZE];
            for (int i = 0; i < writeBuff.length; i++) {
                writeBuff[i] = (byte) ('a' + i % 26);
            }
            buf.put(writeBuff);
            buf.force();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

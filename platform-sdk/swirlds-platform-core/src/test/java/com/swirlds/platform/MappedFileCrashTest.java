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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;

public class MappedFileCrashTest {

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

                        for (int i = 0; i<REGION_SIZE; i++) {
                            mappedBuffer.put(writeBuff);
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
                        double decision = Math.random();
                        if (decision < 0.5) {
                            Runtime.getRuntime().halt(42);
                        } else {
                            Unsafe.getUnsafe().putAddress(0L, 42L); // SIGSEGV
                        }

                  }
                }
            """;

    public static final Path EXPECTED_FILE = Path.of("expected.dat");
    static Path tempDir;

    @BeforeAll
    static void before() throws IOException {
        tempDir = Files.createTempDirectory("dynjava");
        compile("MemMapCrashWriter", MEMAP_SOURCE.formatted("MemMapCrashWriter", REGION_SIZE, "'a' + i%26"));
        compile(
                "FileChannelCrashWriter",
                FILE_CHANNEL_SOURCE.formatted("FileChannelCrashWriter", REGION_SIZE, "'a' + i%26"));
        compile(
                "FileChannelDsyncCrashWriter",
                FILE_CHANNEL_DSYNC_SOURCE.formatted("FileChannelDsyncCrashWriter", REGION_SIZE, "'a' + i%26"));
        createExpectedFile();
    }

    static Path compile(String className, String javaCode) throws IOException {
        Path javaFile = tempDir.resolve(className + ".java");
        Files.writeString(javaFile, javaCode);
        System.out.println("Created source at: " + javaFile);
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

        return javaFile;
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
    public void testFileChannelFileSync() throws Exception {

        final Path resultingFile = executeAndGetResult("FileChannelDsyncCrashWriter");
        Thread.sleep(1000);
        compare(EXPECTED_FILE, resultingFile);

        Files.deleteIfExists(resultingFile);
    }

    @AfterAll
    static void after() throws IOException {
        Files.deleteIfExists(EXPECTED_FILE);
    }

    private static void compare(Path expectedFile, Path resultingFile) throws Exception {
        final String expectedChs = checkSum(expectedFile);
        final long expectedSize = Files.size(expectedFile);
        final String resultingChs = checkSum(resultingFile);
        final long resultingSize = Files.size(resultingFile);
        System.out.printf("Expected checksum:%s obtained: %s%n", expectedChs, resultingChs);
        System.out.printf("Expected filesize:%s obtained: %s%n", expectedSize, resultingSize);
        assertEquals(expectedChs, resultingChs);
        assertEquals(expectedSize, resultingSize);
    }

    public static Path executeAndGetResult(final String className) throws Exception {
        var testFile = UUID.randomUUID().toString();
        ProcessBuilder pb = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "--add-exports",
                "jdk.unsupported/sun.misc=ALL-UNNAMED",
                "-cp",
                tempDir.toString(),
                className,
                testFile);
        pb.inheritIO();
        Process process = pb.start();
        int exit = process.waitFor();
        process.destroy();
        System.out.println("Process exited with code: " + exit);
        return Path.of(testFile);
    }

    /**
     * Compute SHA256 checksum of a file (Linux/macOS)
     * @param val
     * @return
     * @throws Exception
     */
    static String checkSum(Path val) throws Exception {
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

    private static void createExpectedFile() {
        try (RandomAccessFile raf = new RandomAccessFile(MappedFileCrashTest.EXPECTED_FILE.toFile(), "rw");
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

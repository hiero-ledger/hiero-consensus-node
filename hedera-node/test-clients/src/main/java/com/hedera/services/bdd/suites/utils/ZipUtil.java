// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ZipUtil {
    private static final Logger log = LogManager.getLogger(ZipUtil.class);
    private static final int BUFFER_SIZE = 4096;

    public static void main(String... args) {
        final var toyArchiveDir = "poems";
        final var toyArchive = "poeticUpgrade.zip";

        createZip(toyArchiveDir, toyArchive, null);
    }

    public static void createZip(String srcDirName, String zipFile, String defaultScript) {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
                ZipOutputStream zos = new ZipOutputStream(fos)) {

            if (defaultScript != null) {
                Path src = Paths.get(defaultScript);
                Path dest = Paths.get(srcDirName + src.getFileName());
                Files.copy(src, dest);
            }

            File srcDir = new File(srcDirName);
            addZipEntry(zos, srcDir, srcDir);
            zos.flush();
            fos.flush();
        } catch (IOException e) {
            log.error(e);
        }
    }

    /**
     * Compress a directory recursively to zip output stream
     *
     * @param zos zip output stream
     * @param rootDirectory source directory
     * @param currentDirectory current working directory
     */
    public static void addZipEntry(ZipOutputStream zos, File rootDirectory, File currentDirectory) {
        log.info("Root = {}, current = {}", rootDirectory, currentDirectory);
        if (!rootDirectory.equals(currentDirectory)) {
            try {
                String pathDiff = currentDirectory.toString().replace(rootDirectory.toString(), "");
                // remove leading File.separator
                while (pathDiff.charAt(0) == File.separatorChar) {
                    pathDiff = pathDiff.substring(1);
                }
                String dirName = pathDiff + File.separator;
                log.info("Adding dir {}", dirName);
                zos.putNextEntry(new ZipEntry(dirName));
            } catch (IOException e) {
                log.error(e);
            }
        }

        log.info("Current directory {}", currentDirectory);
        File[] files = currentDirectory.listFiles();
        byte[] buffer = new byte[BUFFER_SIZE];

        if (files != null) {
            for (File file : files) {
                // if the file is directory, use recursion
                if (file.isDirectory()) {
                    addZipEntry(zos, rootDirectory, file);
                    continue;
                }

                try (FileInputStream fis = new FileInputStream(file)) {
                    String name = file.getAbsolutePath().replace(rootDirectory.getAbsolutePath(), "");
                    log.info("Adding file:{}", name);
                    zos.putNextEntry(new ZipEntry(name));
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }
                    zos.closeEntry();
                } catch (IOException e) {
                    log.error(e);
                }
            } // for
        } else {
            log.info("Directory {} is empty", currentDirectory);
        }
    }
}

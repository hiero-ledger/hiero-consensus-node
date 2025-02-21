// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.fs.stresstest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class Util {
    public static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static String byteToHexString(byte b) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public static boolean checkParentOverLap(String directory, List<String> prevPaths) {

        // derive all parent paths
        String[] paths = directory.split("(?=/)");

        String totalPath = "";
        List<String> pathList = new ArrayList<String>();
        if (paths.length >= 1) {
            for (int i = 0; i < paths.length - 1; i++) { // don't include itself
                totalPath += paths[i];
                pathList.add(totalPath);
            }

            List<String> intersect =
                    pathList.stream().filter(prevPaths::contains).collect(Collectors.toList());

            return (intersect.size() > 0); // if intersec set is not empty return true
        } else {
            return false;
        }
    }

    public static byte[] mergeByteArray(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(a);
            out.write(b);
            byte[] arr_combined = out.toByteArray();
            return arr_combined;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] expandByteArray(byte[] base, int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int written = 0;
        int baseLength = base.length;
        try {
            while (true) {
                if ((written + baseLength) <= size) {
                    out.write(base);
                    written += baseLength;
                } else {
                    out.write(base, 0, size - written);
                    break;
                }
            }
            byte[] arr_full = out.toByteArray();
            return arr_full;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] stringToMetaData(String filePath) {
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance("SHA-384");
            md.update(filePath.getBytes());
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;

public class JsonHelper {
    private static Gson gson =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    /**
     * Merges the report with the existing report if it exists
     * @param report the report to be merged
     * @param path the path to the existing report
     */
    public static void writeReport(Report report, Path path) {
        writeJSON(report, path);
    }

    public static void writeJSON(Object obj, Path path) {
        try (Writer writer = new FileWriter(path.toFile())) {
            gson.toJson(obj, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String toJsonString(Object obj) {
        return gson.toJson(obj);
    }

    public static <T> T readJSON(String json, Class<T> classOfT) {
        return gson.fromJson(json, classOfT);
    }
}

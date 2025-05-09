/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.statevalidation.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
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

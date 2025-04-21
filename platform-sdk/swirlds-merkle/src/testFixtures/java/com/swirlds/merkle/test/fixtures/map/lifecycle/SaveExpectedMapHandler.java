// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.lifecycle;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.serialization.MapKeyDeserializer;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * Serialize and deserialize the expectedMap to/from JSON
 */
public class SaveExpectedMapHandler {
    private static final Logger logger = LogManager.getLogger(SaveExpectedMapHandler.class);
    private static final Marker MARKER = MarkerManager.getMarker("SAVE_EXPECTED_MAP");

    public static final String STORAGE_DIRECTORY = "data/lifecycle";
    private static final String JSON_FILE_NAME_TEMPLATE = "Node%04d_ExpectedMap_%d_%d.json";

    private static final ObjectMapper objectMapper;
    private static final ObjectWriter objectWriter;

    static {
        final SimpleModule hashModule = new SimpleModule();
        hashModule.addSerializer(Hash.class, new HashSerializer());
        hashModule.addDeserializer(Hash.class, new HashDeserializer());
        objectMapper = new ObjectMapper().registerModule(hashModule).disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        objectWriter = objectMapper.writer(new DefaultPrettyPrinter());
    }

    /**
     * Serialize the expectedMap to JSON
     *
     * @param map
     * 		ExpectedMap to be serialized
     * @param directory
     * 		location where the file should be serialized
     * @param returnJsonString
     * 		it should only be true for unit test; because for big regression test such as FCM1M, it would throw
     * 		`NegativeArraySizeException` because StringBuilder's capacity overflow
     * @return String of JSON that is serialized to disk when returnJsonString is true
     */
    public static String serialize(
            final Map<MapKey, ExpectedValue> map,
            final File directory,
            final String fileName,
            boolean returnJsonString) {

        try {
            return serializeThrowing(map, directory, fileName, returnJsonString);
        } catch (IOException e) {
            logger.error(EXCEPTION.getMarker(), "Error occurred while serializing ExpectedMap", e);
        }
        return "";
    }

    public static String serializeThrowing(
            final Map<MapKey, ExpectedValue> map, final File directory, final String fileName, boolean returnJsonString)
            throws IOException {
        String jsonValue = "";

        if (!directory.exists()) {
            directory.mkdirs();
        }

        final String jsonZipFileName = String.format("%s.gz", fileName);
        final File jsonZip = new File(directory, jsonZipFileName);

        if (jsonZip.exists()) {
            jsonZip.delete();
        }

        logger.info(MARKER, "Serializing ExpectedMap {}", jsonZipFileName);
        try (final FileOutputStream fos = new FileOutputStream(jsonZip);
                final ZipOutputStream zos = new ZipOutputStream((new BufferedOutputStream(fos)))) {
            zos.putNextEntry(new ZipEntry(fileName));

            if (returnJsonString) {
                jsonValue = objectWriter.writeValueAsString(map);
            }

            objectWriter.writeValue(zos, map);

            zos.flush();
            fos.flush();
        }

        return jsonValue;
    }

    /**
     * Deserialize JSON stored on disk to expectedMap
     *
     * @param sourceFile
     * 		the JSON file
     * @return ExpectedMap de-serialized from JSON file serialized to disk
     */
    public static Map<MapKey, ExpectedValue> deserialize(final File sourceFile) throws IOException {
        Map<MapKey, ExpectedValue> newMap = new ConcurrentHashMap<>();
        registerModule();
        final File jsonFile = unzipExpectedMap(sourceFile);

        if (jsonFile == null) {
            throw new IllegalArgumentException("No JSON file found in the zip file: %s".formatted(sourceFile));
        }

        try (FileInputStream fileInputStream = new FileInputStream(jsonFile)) {
            newMap = objectMapper.readValue(fileInputStream, new TypeReference<Map<MapKey, ExpectedValue>>() {});
        }

        jsonFile.delete();

        return newMap;
    }

    public static String createExpectedMapName(final long nodeId, final Instant consensusTime) {
        return String.format(JSON_FILE_NAME_TEMPLATE, nodeId, consensusTime.toEpochMilli(), consensusTime.getNano());
    }

    private static File unzipExpectedMap(final File sourceFile) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(sourceFile))) {
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory()
                        && entry.getName().endsWith(".json")
                        && !entry.getName().startsWith("__MACOSX")) {
                    return extractFile(zis, sourceFile.getParentFile(), entry.getName());
                }
                zis.closeEntry();
                entry = zis.getNextEntry();
            }

        } catch (IOException e) {
            logger.error(EXCEPTION.getMarker(), "Error occurred while unzipping: {}", sourceFile, e);
        }

        return null;
    }

    private static File extractFile(final ZipInputStream zis, final File directory, final String fileName) {
        final File newFile = new File(directory, fileName);

        if (newFile.exists()) {
            newFile.delete();
        }

        byte[] buffer = new byte[1024];
        try (FileOutputStream fos = new FileOutputStream(newFile)) {
            int len;
            while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        } catch (IOException e) {
            logger.error(EXCEPTION.getMarker(), "Error occurred while extracting file", e);
        }

        return newFile;
    }

    private static void registerModule() {
        SimpleModule mapKeyModule = new SimpleModule();
        mapKeyModule.addKeyDeserializer(MapKey.class, new MapKeyDeserializer());
        objectMapper.registerModule(mapKeyModule);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static class HashSerializer extends StdSerializer<Hash> {
        public HashSerializer() {
            super(Hash.class);
        }

        @Override
        public void serialize(final Hash value, final JsonGenerator gen, final SerializerProvider provider)
                throws IOException {
            gen.writeStartObject();
            gen.writeStringField("digestType", value.getDigestType().toString());
            gen.writeStringField("bytes", value.getBytes().toHex());
            gen.writeEndObject();
        }
    }

    public static class HashDeserializer extends StdDeserializer<Hash> {
        public HashDeserializer() {
            super(Hash.class);
        }

        @Override
        public Hash deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            final JsonNode node = p.getCodec().readTree(p);
            final String digestString = node.get("digestType").asText();
            final DigestType digestType = DigestType.valueOf(digestString);
            final String hex = node.get("bytes").asText();
            return new Hash(Bytes.fromHex(hex), digestType);
        }
    }
}

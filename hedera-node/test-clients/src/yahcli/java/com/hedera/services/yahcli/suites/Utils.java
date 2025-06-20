// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isIdLiteral;

import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hederahashgraph.api.proto.java.FileID;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class Utils {
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd.HH:mm:ss").withZone(ZoneId.of("Etc/UTC"));

    public static final long FIRST_SPECIAL_FILE_NUM = 150L;
    public static final long LAST_SPECIAL_FILE_NUM = 159L;

    public static boolean isSpecialFile(final long num) {
        return FIRST_SPECIAL_FILE_NUM <= num && num <= LAST_SPECIAL_FILE_NUM;
    }

    public static String specialFileLoc(final String srcDir, final long num) {
        if (num == 150) {
            return srcDir + File.separator + "softwareUpgrade.zip";
        } else if (num == 159) {
            return srcDir + File.separator + "telemetryUpgrade.zip";
        } else {
            return srcDir + File.separator + "specialFile" + num + ".bin";
        }
    }

    public static String extractEntity(final long shard, final long realm, final String entity) {
        if (isIdLiteral(entity)) {
            return entity;
        } else {
            try {
                long number = Long.parseLong(entity);
                return asEntityString(shard, realm, number);
            } catch (NumberFormatException ignore) {
                throw new IllegalArgumentException("Named accounts not yet supported!");
            }
        }
    }

    // (FUTURE) Remove this method. It's temporary scaffolding until all yahcli ops are properly using shard/realm
    public static String extractAccount(final String account) {
        return extractEntity(HapiPropertySource.getConfigShard(), HapiPropertySource.getConfigRealm(), account);
    }

    public static Instant parseFormattedInstant(final String timeStampInStr) {
        return Instant.from(DATE_TIME_FORMAT.parse(timeStampInStr));
    }

    enum ServiceType {
        CRYPTO,
        CONSENSUS,
        TOKEN,
        FILE,
        CONTRACT,
        SCHEDULED,
        INVALID
    }

    private static final Map<String, ServiceType> SERVICES_TO_ENUM = Map.ofEntries(
            Map.entry("crypto", ServiceType.CRYPTO),
            Map.entry("consensus", ServiceType.CONSENSUS),
            Map.entry("token", ServiceType.TOKEN),
            Map.entry("file", ServiceType.FILE),
            Map.entry("contract", ServiceType.CONTRACT),
            Map.entry("scheduled", ServiceType.SCHEDULED));
    private static final Set<ServiceType> VALID_SERVICE_TYPES = new HashSet<>(SERVICES_TO_ENUM.values());

    private static final Map<String, Long> NAMES_TO_NUMBERS = Map.ofEntries(
            Map.entry("address-book", 101L),
            Map.entry("addressBook.json", 101L),
            Map.entry("node-details", 102L),
            Map.entry("nodeDetails.json", 102L),
            Map.entry("rates", 112L),
            Map.entry("exchangeRates.json", 112L),
            Map.entry("fees", 111L),
            Map.entry("feeSchedules.json", 111L),
            Map.entry("props", 121L),
            Map.entry("application.properties", 121L),
            Map.entry("permissions", 122L),
            Map.entry("api-permission.properties", 122L),
            Map.entry("throttles", 123L),
            Map.entry("throttles.json", 123L),
            Map.entry("software-zip", 150L),
            Map.entry("telemetry-zip", 159L));
    private static final Map<Long, String> NUMBERS_TO_NAMES = NAMES_TO_NUMBERS.entrySet().stream()
            // For any found duplicates, use the actual file name as the key
            .filter(e -> e.getKey().matches("^[a-zA-Z0-9-]\\.[a-z0-9]+$"))
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    private static final Set<Long> VALID_NUMBERS = new HashSet<>(NAMES_TO_NUMBERS.values());

    public static String nameOf(FileID fid) {
        return Optional.ofNullable(NUMBERS_TO_NAMES.get(fid.getFileNum())).orElse("<N/A>");
    }

    public static EnumSet<ServiceType> rationalizedServices(final String[] services) {
        if (Arrays.asList(services).contains("all")) {
            return EnumSet.copyOf(VALID_SERVICE_TYPES);
        }
        return Arrays.stream(services)
                .map(s -> SERVICES_TO_ENUM.getOrDefault(s, ServiceType.INVALID))
                .peek(s -> {
                    if (!VALID_SERVICE_TYPES.contains(s)) {
                        throw new IllegalArgumentException("Invalid ServiceType provided!");
                    }
                })
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ServiceType.class)));
    }

    public static long rationalized(String sysfile) {
        long fileId;
        try {
            fileId = Long.parseLong(sysfile);
        } catch (Exception e) {
            fileId = NAMES_TO_NUMBERS.getOrDefault(sysfile, 0L);
        }
        if (!VALID_NUMBERS.contains(fileId)) {
            throw new IllegalArgumentException("No such system file '" + sysfile + "'!");
        }
        return fileId;
    }

    public static long[] rationalized(String[] sysfiles) {
        if (Arrays.asList(sysfiles).contains("all")) {
            return VALID_NUMBERS.stream().mapToLong(Number::longValue).toArray();
        }

        return Arrays.stream(sysfiles)
                .map(Utils::getFileId)
                .peek(num -> {
                    if (!VALID_NUMBERS.contains(num)) {
                        throw new IllegalArgumentException("No such system file '" + num + "'!");
                    }
                })
                .mapToLong(l -> l)
                .toArray();
    }

    private static long getFileId(String file) {
        long fileId;
        try {
            fileId = Long.parseLong(file);
        } catch (Exception e) {
            fileId = NAMES_TO_NUMBERS.getOrDefault(file, 0L);
            if (fileId == 0) {
                throw new IllegalArgumentException("No such system file '" + file + "'!");
            }
        }
        return fileId;
    }
}

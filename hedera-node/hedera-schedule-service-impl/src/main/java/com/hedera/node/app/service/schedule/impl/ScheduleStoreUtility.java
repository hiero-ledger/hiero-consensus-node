// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static java.util.Objects.requireNonNull;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduleIdList;
import com.hedera.hapi.node.state.schedule.ScheduleList;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Provides utility methods for the schedule store.
 * Used to calculate the hash of a schedule which is then used to store the schedule in the schedule store.
 * */
public final class ScheduleStoreUtility {
    private ScheduleStoreUtility() {}

    /**
     * Calculate bytes hash of a schedule based on the schedule's memo, admin key, scheduled transaction, expiration
     * time, and wait for expiry flag.
     *
     * @param schedule the schedule to hash
     * @return the bytes
     */
    @SuppressWarnings("UnstableApiUsage")
    public static Bytes calculateBytesHash(@NonNull final Schedule schedule) {
        requireNonNull(schedule);
        final Hasher hasher = Hashing.sha256().newHasher();
        hasher.putString(schedule.memo(), StandardCharsets.UTF_8);
        if (schedule.adminKey() != null) {
            addToHash(hasher, schedule.adminKey());
        }
        // @note We should check scheduler here, but mono doesn't, so we cannot either, yet.
        if (schedule.scheduledTransaction() != null) {
            addToHash(hasher, schedule.scheduledTransaction());
        }
        // @todo('9447') This should be modified to use calculated expiration once
        //               differential testing completes
        hasher.putLong(schedule.providedExpirationSecond());
        hasher.putBoolean(schedule.waitForExpiry());
        return Bytes.wrap(hasher.hash().asBytes());
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void addToHash(final Hasher hasher, final Key keyToAdd) {
        final byte[] keyBytes = Key.PROTOBUF.toBytes(keyToAdd).toByteArray();
        hasher.putInt(keyBytes.length);
        hasher.putBytes(keyBytes);
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void addToHash(final Hasher hasher, final SchedulableTransactionBody transactionToAdd) {
        final byte[] bytes =
                SchedulableTransactionBody.PROTOBUF.toBytes(transactionToAdd).toByteArray();
        hasher.putInt(bytes.length);
        hasher.putBytes(bytes);
    }

    private static boolean isScheduleInList(final ScheduleID scheduleId, final ScheduleList scheduleList) {
        return scheduleList.schedules().stream()
                .anyMatch(s -> s.scheduleIdOrThrow().equals(scheduleId));
    }

    private static boolean isScheduleIdInList(final ScheduleID scheduleId, final ScheduleIdList scheduleIdList) {
        return scheduleIdList.scheduleIds().stream().anyMatch(id -> id.equals(scheduleId));
    }

    /**
     * Adds a {@link Schedule} to a {@link ScheduleList}, replacing it if it already exists.
     *
     * <p>This method checks if the provided {@code Schedule} is already present in the {@code ScheduleList}.
     * If it is, the existing {@code Schedule} is replaced with the new one. If it isn't, the {@code Schedule}
     * is added to the list. This allows for updating entries within a {@code ScheduleList} without needing to
     * manually manage duplicates or replacements.
     *
     * @param schedule The {@link Schedule} to add or replace in the {@code ScheduleList}. Must not be {@code null},
     *     unless the {@code ScheduleList} is also {@code null}.
     * @param scheduleList The {@link ScheduleList} to which the {@code Schedule} will be added or replaced. May be
     *     {@code null}, in which case a new {@link ScheduleList} containing only the provided
     *     {@code Schedule} is returned.
     * @return A new {@link ScheduleList} containing the {@link Schedule} either added or replacing an existing one
     */
    static @NonNull ScheduleList addOrReplace(final Schedule schedule, @Nullable final ScheduleList scheduleList) {
        if (scheduleList == null) {
            return new ScheduleList(Collections.singletonList(schedule));
        }
        final var newScheduleList = scheduleList.copyBuilder();
        final var scheduleId = schedule.scheduleIdOrThrow();
        final var schedules = new ArrayList<>(scheduleList.schedules());
        if (!isScheduleInList(scheduleId, scheduleList)) {
            schedules.add(schedule);
        } else {
            for (int i = 0; i < schedules.size(); i++) {
                final var existingSchedule = schedules.get(i);
                if (existingSchedule.scheduleIdOrThrow().equals(scheduleId)) {
                    schedules.set(i, schedule);
                }
            }
        }
        return newScheduleList.schedules(schedules).build();
    }

    /**
     * Adds a {@link ScheduleID} to a {@link ScheduleIdList}.
     *
     * <p>This method checks if the provided {@code ScheduleID} is already present in the {@code ScheduleIdList}.
     * If it isn't, the {@code ScheduleID} is added to the list. This allows for updating entries within a {@code ScheduleIdList} without needing to
     * manually manage duplicates.
     *
     * @param scheduleId The {@link ScheduleID} to add in the {@code ScheduleList}. Must not be {@code null},
     *     unless the {@code ScheduleList} is also {@code null}.
     * @param scheduleIdList The {@link ScheduleIdList} to which the {@code Schedule} will be added. May be
     *     {@code null}, in which case a new {@link ScheduleIdList} containing only the provided
     *     {@code Schedule} is returned.
     * @return A new {@link ScheduleIdList} containing the {@link ScheduleID} either added if it's not in the list
     */
    static @NonNull ScheduleIdList add(final ScheduleID scheduleId, @Nullable final ScheduleIdList scheduleIdList) {
        if (scheduleIdList == null) {
            return new ScheduleIdList(Collections.singletonList(scheduleId));
        }
        final var newScheduleIdList = scheduleIdList.copyBuilder();
        final var scheduleIds = new ArrayList<>(scheduleIdList.scheduleIds());
        if (!isScheduleIdInList(scheduleId, scheduleIdList)) {
            scheduleIds.add(scheduleId);
        }
        return newScheduleIdList.scheduleIds(scheduleIds).build();
    }
}

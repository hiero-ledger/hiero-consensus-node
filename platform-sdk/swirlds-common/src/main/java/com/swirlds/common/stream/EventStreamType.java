// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

/**
 * Contains properties related to EventStream file type;
 * Its constructor is private. Users need to use the singleton to denote this type
 */
public final class EventStreamType implements StreamType {
    /**
     * description of the streamType, used for logging
     */
    public static final String EVENT_DESCRIPTION = "events";
    /**
     * file name extension
     */
    public static final String EVENT_EXTENSION = "evts";
    /**
     * file name extension of signature file
     */
    public static final String EVENT_SIG_EXTENSION = "evts_sig";
    /**
     * a singleton denotes EventStreamType
     */
    private static final EventStreamType INSTANCE = new EventStreamType();
    /**
     * Header which is written in the beginning of a stream file, before writing the Object Stream Version.
     * the int in fileHeader denotes version 5
     */
    private static final int[] EVENT_FILE_HEADER = new int[] {5};
    /**
     * Header which is written in the beginning of a stream signature file, before writing the Object Stream Signature
     * Version.
     * the byte in sigFileHeader denotes version 5
     */
    private static final byte[] EVENT_SIG_FILE_HEADER = new byte[] {5};

    private EventStreamType() {}

    public static EventStreamType getInstance() {
        return INSTANCE;
    }

    @Override
    public String getDescription() {
        return EVENT_DESCRIPTION;
    }

    @Override
    public String getExtension() {
        return EVENT_EXTENSION;
    }

    @Override
    public String getSigExtension() {
        return EVENT_SIG_EXTENSION;
    }

    @Override
    public int[] getFileHeader() {
        return EVENT_FILE_HEADER;
    }

    @Override
    public byte[] getSigFileHeader() {
        return EVENT_SIG_FILE_HEADER;
    }
}

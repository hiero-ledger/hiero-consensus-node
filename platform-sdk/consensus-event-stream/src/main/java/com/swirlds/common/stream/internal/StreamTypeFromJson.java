// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream.internal;

import com.swirlds.common.stream.StreamType;

/**
 * Contains properties related to certain stream file type.
 * The object can be built from a json file
 */
public final class StreamTypeFromJson implements StreamType {
    /**
     * description of the streamType, used for logging
     */
    private String description;
    /**
     * file name extension
     */
    private String extension;
    /**
     * file name extension of signature file
     */
    private String sigExtension;
    /**
     * Header which is written in the beginning of a stream file, before writing the Object Stream Version.
     */
    private int[] fileHeader;
    /**
     * Header which is written in the beginning of a stream signature file, before writing the Object Stream Signature
     * Version.
     */
    private byte[] sigFileHeader;

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getExtension() {
        return extension;
    }

    @Override
    public String getSigExtension() {
        return sigExtension;
    }

    @Override
    public int[] getFileHeader() {
        return fileHeader;
    }

    @Override
    public byte[] getSigFileHeader() {
        return sigFileHeader;
    }
}

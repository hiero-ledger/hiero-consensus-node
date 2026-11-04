// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/**
 * Pass-through CLPR verifier contract for testing.
 *
 * Accepts a serialized StateProof (com.hedera.hapi.block.stream.StateProof) and
 * extracts attested content WITHOUT any cryptographic verification.
 *
 * verifyConfig(bytes,bytes32,bytes): extracts ClprLedgerConfiguration from the config proof and
 * ClprEndpointManifest from the manifest proof, and returns their fields in the ABI 8-tuple expected
 * by EvmClprVerifier.decodeVerifyConfigWithManifestReturn (7-field throttles + trailing manifest struct).
 *
 * verifyBundle(bytes,bytes,bytes): walks all state_item_leaf paths and dispatches on the StateValue tag:
 *   - [0xE2,0x03] = field 60 = ClprChannel → queue metadata source
 *   - [0xF2,0x03] = field 62 = ClprMessageValue → ordered message payloads
 * Returns the ABI 5-tuple
 * ((uint64,bytes32,uint64,bytes32,uint8), bytes[], bytes, bytes, (uint64,bytes,(string,uint32,bytes,bytes)[]))
 * expected by EvmClprVerifier.decodeVerifyBundleWithManifestReturn. This pass-through never carries a
 * bundle manifest update, so the trailing manifest is returned absent (version 0).
 *
 * No Merkle path verification, no TSS signature check, no hash computation.
 * DO NOT use in production.
 */
contract ClprPassThroughVerifier {

    // StateProof field 1 (paths, repeated MerklePath), WT 2 → 0x0a
    uint64 private constant SP_PATHS = 0x0a;
    // MerklePath field 4 (state_item_leaf, bytes), WT 2 → 0x22
    uint64 private constant MP_LEAF = 0x22;
    // StateItem field 3 (value, StateValue), WT 2 → 0x1a
    uint64 private constant SI_VALUE = 0x1a;

    // StateValue first-byte discriminants (2-byte varints):
    //   field 60 ClprChannel  WT 2 → 482 → [0xE2, 0x03]
    uint8 private constant SV_CHANNEL_B0 = 0xe2;
    //   field 62 ClprMessageValue WT 2 → 498 → [0xF2, 0x03]
    uint8 private constant SV_MSG_B0  = 0xf2;
    uint8 private constant SV_B1      = 0x03;

    // ClprEndpointManifest field tags (parsed from the manifest proof)
    uint64 private constant MANIFEST_VERSION = 0x08; // field 1, WT 0
    uint64 private constant MANIFEST_SVC_ADDR = 0x12; // field 2, WT 2
    uint64 private constant MANIFEST_ENDPOINT = 0x1a; // field 3, WT 2 (repeated ClprEndpoint)

    // ClprLedgerConfiguration.endpoints tag (field 6, repeated ClprEndpoint) — retained for the
    // shared endpoint parser, though config endpoints are deprecated under the manifest ABI.
    uint64 private constant CONFIG_ENDPOINT = 0x32;

    // ClprChannel field tags
    uint64 private constant CHANNEL_STATUS    = 0x38; // field 7,  WT 0
    uint64 private constant CHANNEL_ACKED     = 0x48; // field 9,  WT 0
    uint64 private constant CHANNEL_RCVD_ID   = 0x58; // field 11, WT 0
    uint64 private constant CHANNEL_RCVD_HASH = 0x62; // field 12, WT 2
    uint64 private constant CHANNEL_MANIFEST_VER = 0xa0; // field 20, WT 0

    // ClprMessageValue field tags
    uint64 private constant MV_PAYLOAD = 0x0a; // field 1, WT 2
    uint64 private constant MV_HASH    = 0x12; // field 2, WT 2

    // Packs the six ClprChannel fields extracted by _scanLeaves into one stack slot.
    struct ConnData {
        uint64 status;
        uint64 acked;
        uint64 rcvdId;
        uint256 rcvdHS;
        uint256 rcvdHL;
        uint64 manifestVer;
    }

    // Manifest-aware config return: ClprThrottles as ABI
    // (uint32,uint64,uint64,uint32,uint64,uint32,uint32).
    struct ThrottlesData {
        uint32 maxMessagesPerBundle;
        uint64 maxMessagePayloadBytes;
        uint64 maxGasPerMessage;
        uint32 maxQueueDepth;
        uint64 maxSyncBytes;
        uint32 maxLocalEndpoints;
        uint32 maxPeerEndpoints;
    }

    // ClprEndpoint as ABI (string,uint32,bytes,bytes)
    struct EndpointData {
        string ipAddress;
        uint32 port;
        bytes tlsCertificate;
        bytes accountId;
    }

    // ClprEndpointManifest as ABI (uint64,bytes,(string,uint32,bytes,bytes)[])
    struct ManifestData {
        uint64 version;
        bytes serviceAddress;
        EndpointData[] endpoints;
    }

    // Bundle return metadata: ClprQueueMetadata as ABI (uint64,bytes32,uint64,bytes32,uint8,uint64)
    struct BundleMeta {
        uint64 nextMessageId;
        bytes32 sentRunningHash;
        uint64 receivedMessageId;
        bytes32 receivedRunningHash;
        uint8 status;
        uint64 endpointManifestVersion;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public entrypoints
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Manifest-aware: extracts ClprLedgerConfiguration from the config proof and ClprEndpointManifest
     * from the manifest proof, returning the ABI 8-tuple expected by
     * EvmClprVerifier.decodeVerifyConfigWithManifestReturn.
     *
     * Returns (channelContext, chainId, serviceAddress, peerConfigNanos,
     *          throttles, initialTrustAnchor, initialTrustAnchorId, manifest).
     * channelContext is built as abi.encodePacked(channelId32, serviceAddress).
     */
    function verifyConfig(
        bytes calldata proofBytes,
        bytes32 channelId32,
        bytes calldata manifestProofBytes
    )
        external pure
        returns (
            bytes memory channelContext,
            string memory chainId,
            bytes memory serviceAddress,
            uint96 peerConfigNanos,
            ThrottlesData memory throttles,
            bytes memory initialTrustAnchor,
            bytes memory initialTrustAnchorId,
            ManifestData memory manifest
        )
    {
        (uint256 innerS, uint256 innerL) = _extractLeafValue(proofBytes);
        uint256 cfgEnd = innerS + innerL;

        // chain_id: ClprLedgerConfiguration field 2 (string), tag 0x12, WT 2
        {
            (uint256 s, uint256 l,) = _findLd(proofBytes, innerS, cfgEnd, 0x12);
            chainId = _copyAsString(proofBytes, s, l);
        }
        // service_address: field 3 (bytes), tag 0x1a, WT 2
        {
            (uint256 s, uint256 l,) = _findLd(proofBytes, innerS, cfgEnd, 0x1a);
            serviceAddress = _copy(proofBytes, s, l);
        }
        // timestamp: field 4 (message), tag 0x22, WT 2 → seconds(0x08,WT0), nanos(0x10,WT0)
        {
            (uint256 tsS, uint256 tsL,) = _findLd(proofBytes, innerS, cfgEnd, 0x22);
            if (tsL > 0) {
                (uint64 secs,) = _findV0(proofBytes, tsS, tsS + tsL, 0x08);
                (uint64 nanos,) = _findV0(proofBytes, tsS, tsS + tsL, 0x10);
                peerConfigNanos = uint96(secs) * 1_000_000_000 + uint96(nanos);
            }
        }
        // throttles: field 5 (message), tag 0x2a, WT 2
        {
            (uint256 thrS, uint256 thrL,) = _findLd(proofBytes, innerS, cfgEnd, 0x2a);
            if (thrL > 0) {
                uint256 thrEnd = thrS + thrL;
                uint64 v;
                (v,) = _findV0(proofBytes, thrS, thrEnd, 0x08); throttles.maxMessagesPerBundle = uint32(v);
                (v,) = _findV0(proofBytes, thrS, thrEnd, 0x10); throttles.maxMessagePayloadBytes = v;
                (v,) = _findV0(proofBytes, thrS, thrEnd, 0x18); throttles.maxGasPerMessage = v;
                (v,) = _findV0(proofBytes, thrS, thrEnd, 0x20); throttles.maxQueueDepth = uint32(v);
                (v,) = _findV0(proofBytes, thrS, thrEnd, 0x28); throttles.maxSyncBytes = v;
                (v,) = _findV0(proofBytes, thrS, thrEnd, 0x30); throttles.maxLocalEndpoints = uint32(v);
                (v,) = _findV0(proofBytes, thrS, thrEnd, 0x38); throttles.maxPeerEndpoints = uint32(v);
            }
        }
        // initial_trust_anchor: field 7 (bytes), tag 0x3a, WT 2
        {
            (uint256 s, uint256 l,) = _findLd(proofBytes, innerS, cfgEnd, 0x3a);
            initialTrustAnchor = _copy(proofBytes, s, l);
        }
        // initial_trust_anchor_id: field 8 (bytes), tag 0x42, WT 2
        {
            (uint256 s, uint256 l,) = _findLd(proofBytes, innerS, cfgEnd, 0x42);
            initialTrustAnchorId = _copy(proofBytes, s, l);
        }
        // channelContext = abi.encodePacked(channelId32, serviceAddress)
        {
            uint256 saLen = serviceAddress.length;
            channelContext = new bytes(32 + saLen);
            for (uint256 k = 0; k < 32; k++) channelContext[k] = channelId32[k];
            for (uint256 k = 0; k < saLen; k++) channelContext[32 + k] = serviceAddress[k];
        }
        // manifest: parsed from the separate manifest StateProof (endpoints live here under the
        // manifest ABI, not in config.endpoints).
        manifest = _parseManifest(manifestProofBytes);
    }

    /**
     * Extracts bundle content from a StateProof and returns the ABI 5-tuple
     * expected by EvmClprVerifier.decodeVerifyBundleWithManifestReturn:
     * (BundleMeta meta, bytes[] messages, bytes newTrustAnchor, bytes newTrustAnchorId, ManifestData newManifest).
     *
     * trustAnchor and channelContext are accepted for selector compatibility
     * but not used — this pass-through verifier performs no cryptographic checks. The trailing
     * manifest is always returned absent (version 0): this verifier never carries a bundle manifest update.
     */
    function verifyBundle(
        bytes calldata bp,
        bytes calldata /* trustAnchor */,
        bytes calldata /* channelContext */
    )
        external pure
        returns (
            BundleMeta memory meta,
            bytes[] memory messages,
            bytes memory newTrustAnchor,
            bytes memory newTrustAnchorId,
            ManifestData memory newManifest
        )
    {
        uint256 msgCount = _countMsgs(bp);
        uint256[] memory pS = new uint256[](msgCount);
        uint256[] memory pL = new uint256[](msgCount);
        uint256[] memory hS = new uint256[](msgCount);
        uint256[] memory hL = new uint256[](msgCount);

        ConnData memory conn;
        require(_scanLeaves(bp, conn, pS, pL, hS, hL), "verifyBundle: no channel leaf");

        meta.nextMessageId  = conn.acked + 1 + uint64(msgCount);
        meta.receivedMessageId = conn.rcvdId;
        meta.status = uint8(conn.status);
        meta.endpointManifestVersion = conn.manifestVer;
        if (msgCount > 0 && hL[msgCount - 1] >= 32) {
            meta.sentRunningHash = _copyBytes32(bp, hS[msgCount - 1]);
        }
        if (conn.rcvdHL >= 32) {
            meta.receivedRunningHash = _copyBytes32(bp, conn.rcvdHS);
        }

        messages = new bytes[](msgCount);
        for (uint256 i = 0; i < msgCount; i++) {
            messages[i] = _copy(bp, pS[i], pL[i]);
        }

        // Pass-through verifier never rotates trust anchor or carries a bundle manifest update.
        newTrustAnchor   = new bytes(0);
        newTrustAnchorId = new bytes(0);
        newManifest.serviceAddress = new bytes(0);
        newManifest.endpoints = new EndpointData[](0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal: scanning
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Walks the proof paths for the first state_item_leaf and returns the inner byte range of the
     * wrapped StateValue's single field (the domain value). Reverts if no leaf is present.
     */
    function _extractLeafValue(bytes calldata buf)
        private pure returns (uint256 innerS, uint256 innerL)
    {
        uint256 n = buf.length;
        uint256 i = 0;
        bool found;
        while (i < n) {
            (uint64 tag, uint256 tl) = _readV(buf, i); i += tl;
            (uint64 vLen, uint256 ll) = _readV(buf, i); i += ll;
            uint256 vEnd = i + uint256(vLen);
            require(vEnd <= n, "extract: OOB");
            if (tag == SP_PATHS) {
                (uint256 lS, uint256 lL, bool lOk) = _findLd(buf, i, vEnd, MP_LEAF);
                if (lOk) {
                    (uint256 svS, uint256 svL, bool svOk) = _findLd(buf, lS, lS + lL, SI_VALUE);
                    if (svOk && svL > 0) {
                        (innerS, innerL) = _unwrapFirst(buf, svS);
                        found = true;
                        break;
                    }
                }
            }
            i = vEnd;
        }
        require(found, "extract: no state-item leaf found");
    }

    /**
     * Parses a ClprEndpointManifest from a manifest StateProof into a ManifestData struct. Returns an
     * absent manifest (version 0, empty) when the proof is empty or carries no leaf, so callers that pass
     * no manifest proof still get a well-formed (absent) struct.
     */
    function _parseManifest(bytes calldata manifestProofBytes)
        private pure returns (ManifestData memory manifest)
    {
        manifest.serviceAddress = new bytes(0);
        manifest.endpoints = new EndpointData[](0);
        if (manifestProofBytes.length == 0) {
            return manifest;
        }
        (uint256 mS, uint256 mL) = _extractLeafValue(manifestProofBytes);
        uint256 mEnd = mS + mL;
        (uint64 ver,) = _findV0(manifestProofBytes, mS, mEnd, MANIFEST_VERSION);
        manifest.version = ver;
        {
            (uint256 s, uint256 l,) = _findLd(manifestProofBytes, mS, mEnd, MANIFEST_SVC_ADDR);
            manifest.serviceAddress = _copy(manifestProofBytes, s, l);
        }
        uint256 epCount = _countLdTag(manifestProofBytes, mS, mEnd, MANIFEST_ENDPOINT);
        manifest.endpoints = new EndpointData[](epCount);
        if (epCount > 0) {
            _fillEndpoints(manifestProofBytes, mS, mEnd, manifest.endpoints, MANIFEST_ENDPOINT);
        }
    }

    /** First pass: count ClprMessageValue leaves so arrays can be pre-allocated. */
    function _countMsgs(bytes calldata buf) private pure returns (uint256 count) {
        uint256 n = buf.length;
        uint256 i = 0;
        while (i < n) {
            (uint64 tag, uint256 tl) = _readV(buf, i); i += tl;
            (uint64 vLen, uint256 ll) = _readV(buf, i); i += ll;
            uint256 vEnd = i + uint256(vLen);
            if (vEnd > n) break;
            if (tag == SP_PATHS) {
                (uint256 lS, uint256 lL, bool lOk) = _findLd(buf, i, vEnd, MP_LEAF);
                if (lOk) {
                    (uint256 svS, uint256 svL, bool svOk) = _findLd(buf, lS, lS + lL, SI_VALUE);
                    if (svOk && svL >= 2 &&
                            uint8(buf[svS]) == SV_MSG_B0 && uint8(buf[svS + 1]) == SV_B1) {
                        count++;
                    }
                }
            }
            i = vEnd;
        }
    }

    /**
     * Second pass: populate conn and fill per-message payload/hash ranges.
     * Arrays pS/pL (payload start/len) and hS/hL (running-hash start/len) are
     * filled in the order messages appear in the proof paths.
     * Returns true when a ClprChannel leaf was found.
     */
    function _scanLeaves(
        bytes calldata buf, ConnData memory conn,
        uint256[] memory pS, uint256[] memory pL,
        uint256[] memory hS, uint256[] memory hL
    ) private pure returns (bool connOk) {
        uint256 n = buf.length;
        uint256 i = 0;
        uint256 mi = 0;
        while (i < n) {
            (uint64 tag, uint256 tl) = _readV(buf, i); i += tl;
            (uint64 vLen, uint256 ll) = _readV(buf, i); i += ll;
            uint256 vEnd = i + uint256(vLen);
            if (vEnd > n) break;
            if (tag == SP_PATHS) {
                (uint256 lS, uint256 lL, bool lOk) = _findLd(buf, i, vEnd, MP_LEAF);
                if (lOk) {
                    (uint256 svS, uint256 svL, bool svOk) = _findLd(buf, lS, lS + lL, SI_VALUE);
                    if (svOk && svL >= 2) {
                        uint8 b0 = uint8(buf[svS]);
                        uint8 b1 = uint8(buf[svS + 1]);
                        if (b0 == SV_CHANNEL_B0 && b1 == SV_B1) {
                            connOk = true;
                            (uint256 cS, uint256 cL) = _unwrapFirst(buf, svS);
                            uint256 cEnd = cS + cL;
                            (conn.status,) = _findV0(buf, cS, cEnd, CHANNEL_STATUS);
                            (conn.acked,)  = _findV0(buf, cS, cEnd, CHANNEL_ACKED);
                            (conn.rcvdId,) = _findV0(buf, cS, cEnd, CHANNEL_RCVD_ID);
                            (conn.rcvdHS, conn.rcvdHL,) = _findLd(buf, cS, cEnd, CHANNEL_RCVD_HASH);
                            (conn.manifestVer,) = _findV0(buf, cS, cEnd, CHANNEL_MANIFEST_VER);
                        } else if (b0 == SV_MSG_B0 && b1 == SV_B1) {
                            (uint256 mvS, uint256 mvL) = _unwrapFirst(buf, svS);
                            uint256 mvEnd = mvS + mvL;
                            // pL[mi] stays 0 if the payload is absent
                            (pS[mi], pL[mi],) = _findLd(buf, mvS, mvEnd, MV_PAYLOAD);
                            (hS[mi], hL[mi],) = _findLd(buf, mvS, mvEnd, MV_HASH);
                            mi++;
                        }
                    }
                }
            }
            i = vEnd;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal: proto parsing helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Scans [start, end) for the first WT-2 field whose tag matches tgt.
     * Returns (valStart, valLen, found) — valStart/valLen are the byte range of
     * the field's value (after the tag+length prefix).
     */
    function _findLd(
        bytes calldata buf, uint256 start, uint256 end, uint64 tgt
    ) private pure returns (uint256 vs, uint256 vl, bool ok) {
        uint256 i = start;
        while (i < end) {
            (uint64 tag, uint256 tl) = _readV(buf, i); i += tl;
            if (i > end) break;
            uint64 wt = tag & 0x07;
            if (wt == 2) {
                (uint64 vLen, uint256 ll) = _readV(buf, i); i += ll;
                if (tag == tgt) return (i, uint256(vLen), true);
                i += uint256(vLen);
            } else if (wt == 0) {
                (, uint256 ll) = _readV(buf, i); i += ll;
            } else { break; }
        }
    }

    /**
     * Scans [start, end) for the first WT-0 field whose tag matches tgt.
     * Returns (value, found).
     */
    function _findV0(
        bytes calldata buf, uint256 start, uint256 end, uint64 tgt
    ) private pure returns (uint64 val, bool ok) {
        uint256 i = start;
        while (i < end) {
            (uint64 tag, uint256 tl) = _readV(buf, i); i += tl;
            if (i > end) break;
            uint64 wt = tag & 0x07;
            if (wt == 0) {
                (uint64 v, uint256 ll) = _readV(buf, i); i += ll;
                if (tag == tgt) return (v, true);
            } else if (wt == 2) {
                (uint64 vLen, uint256 ll) = _readV(buf, i); i += ll + uint256(vLen);
            } else { break; }
        }
    }

    /**
     * Reads the first tag+length at position start and returns the inner byte range.
     * Used to unwrap a StateValue field: skip the outer 2-byte tag varint ([0xE2,0x03]
     * or [0xF2,0x03]), read the length varint, return (innerStart, innerLen).
     */
    function _unwrapFirst(
        bytes calldata buf, uint256 start
    ) private pure returns (uint256 innerStart, uint256 innerLen) {
        (, uint256 tl) = _readV(buf, start);
        uint256 pos = start + tl;
        (uint64 vLen, uint256 ll) = _readV(buf, pos);
        return (pos + ll, uint256(vLen));
    }

    /** Copies buf[start .. start+len) into a fresh memory bytes array. */
    function _copy(
        bytes calldata buf, uint256 start, uint256 len
    ) private pure returns (bytes memory out) {
        out = new bytes(len);
        for (uint256 i = 0; i < len; i++) out[i] = buf[start + i];
    }

    /**
     * Counts all WT-2 field occurrences with the given tag in [start, end).
     * Used to size repeated-field arrays before allocating.
     */
    function _countLdTag(
        bytes calldata buf, uint256 start, uint256 end, uint64 tgt
    ) private pure returns (uint256 count) {
        uint256 i = start;
        while (i < end) {
            (uint64 tag, uint256 tl) = _readV(buf, i); i += tl;
            if (i > end) break;
            uint64 wt = tag & 0x07;
            if (wt == 2) {
                (uint64 vLen, uint256 ll) = _readV(buf, i); i += ll;
                if (tag == tgt) count++;
                i += uint256(vLen);
            } else if (wt == 0) {
                (, uint256 ll) = _readV(buf, i); i += ll;
            } else { break; }
        }
    }

    /**
     * Fills eps[] by scanning [start, end) for ClprEndpoint fields with the given repeated-field tag
     * (0x1a for ClprEndpointManifest.endpoints, 0x32 for ClprLedgerConfiguration.endpoints).
     * Each ClprEndpoint is parsed into an EndpointData struct.
     */
    function _fillEndpoints(
        bytes calldata buf, uint256 start, uint256 end,
        EndpointData[] memory eps, uint64 epTag
    ) private pure {
        uint256 i = start;
        uint256 idx = 0;
        while (i < end && idx < eps.length) {
            (uint64 tag, uint256 tl) = _readV(buf, i); i += tl;
            if (i > end) break;
            uint64 wt = tag & 0x07;
            if (wt == 2) {
                (uint64 vLen, uint256 ll) = _readV(buf, i); i += ll;
                uint256 vEnd = i + uint256(vLen);
                if (tag == epTag) {
                    // ClprServiceEndpoint service_endpoint = 1 (tag 0x0a)
                    (uint256 seS, uint256 seL,) = _findLd(buf, i, vEnd, 0x0a);
                    // bytes tls_certificate = 2 (tag 0x12)
                    (uint256 tcS, uint256 tcL,) = _findLd(buf, i, vEnd, 0x12);
                    // bytes account_id = 3 (tag 0x1a)
                    (uint256 acS, uint256 acL,) = _findLd(buf, i, vEnd, 0x1a);
                    if (seL > 0) {
                        // string ip_address = 1 (tag 0x0a)
                        (uint256 ipS, uint256 ipL,) = _findLd(buf, seS, seS + seL, 0x0a);
                        // uint32 port = 2 (tag 0x10, WT 0)
                        (uint64 port,) = _findV0(buf, seS, seS + seL, 0x10);
                        eps[idx].ipAddress = _copyAsString(buf, ipS, ipL);
                        eps[idx].port = uint32(port);
                    }
                    eps[idx].tlsCertificate = _copy(buf, tcS, tcL);
                    eps[idx].accountId = _copy(buf, acS, acL);
                    idx++;
                }
                i = vEnd;
            } else if (wt == 0) {
                (, uint256 ll) = _readV(buf, i); i += ll;
            } else { break; }
        }
    }

    /** Copies buf[start .. start+len) as a UTF-8 string. */
    function _copyAsString(
        bytes calldata buf, uint256 start, uint256 len
    ) private pure returns (string memory) {
        bytes memory out = new bytes(len);
        for (uint256 i = 0; i < len; i++) out[i] = buf[start + i];
        return string(out);
    }

    /**
     * Copies exactly 32 bytes from buf[start .. start+32) into a bytes32 value
     * (big-endian, byte 0 is the leftmost/most-significant byte).
     */
    function _copyBytes32(
        bytes calldata buf, uint256 start
    ) private pure returns (bytes32 r) {
        for (uint256 k; k < 32; k++) {
            r |= bytes32(uint256(uint8(buf[start + k])) << (248 - k * 8));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal: varint primitives
    // ──────────────────────────────────────────────────────────────────────────

    function _readV(bytes calldata buf, uint256 off)
        private pure returns (uint64 v, uint256 read)
    {
        uint64 shift;
        for (uint256 j; j < 10; j++) {
            require(off + j < buf.length, "varint OOB");
            uint8 b = uint8(buf[off + j]);
            v |= uint64(b & 0x7f) << shift;
            if ((b & 0x80) == 0) return (v, j + 1);
            shift += 7;
        }
        revert("varint overflow");
    }
}

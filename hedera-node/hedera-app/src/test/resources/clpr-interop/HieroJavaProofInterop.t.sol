// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

import {ClprTypes} from "@hiero-ledger/clpr/libraries/ClprTypes.sol";
import {ClprHieroProofCodec} from "@hiero-ledger/clpr/libraries/proof/hiero/ClprHieroProofCodec.sol";
import {HieroVerifier} from "@hiero-ledger/clpr/verifiers/hiero/HieroVerifier.sol";
import {IClprService} from "@hiero-ledger/clpr/interfaces/IClprService.sol";
import {IClprVerifier} from "@hiero-ledger/clpr/interfaces/IClprVerifier.sol";
import {ConnectorRegistrar} from "@test/helpers/ConnectorRegistrar.sol";
import {HieroWitnessSubmitBundleTest} from "./HieroWitnessSubmitBundle.t.sol";

/// @dev Uses the real service and verifier from the Solidity fixture suite to submit Java-encoded bytes.
contract HieroJavaProofInteropTest is HieroWitnessSubmitBundleTest {
    string internal constant JAVA_FIXTURES = "test/verifiers/hiero/fixtures/java-interop/v1/";

    function _javaProof(string memory name) internal view returns (bytes memory) {
        return vm.readFileBinary(string.concat(JAVA_FIXTURES, name, ".bin"));
    }

    function test_javaMatchingAnchorDelivers() public {
        service.submitBundle(channelId, _javaProof("matching-anchor"));
        ClprTypes.Channel memory channel = service.getChannel(channelId);
        assertEq(channel.trustAnchorId, abi.encodePacked(hex"01", _anchor("key0")));
        assertEq(channel.receivedMessageId, 1);
    }

    function test_javaShortRotationDelivers() public {
        service.submitBundle(channelId, _javaProof("short-rotation"));
        ClprTypes.Channel memory channel = service.getChannel(channelId);
        assertEq(channel.trustAnchorId, abi.encodePacked(hex"01", _anchor("key1")));
        assertEq(channel.receivedMessageId, 1);
    }

    function test_javaCatchUpThenDelivers() public {
        ClprTypes.Channel memory before = service.getChannel(channelId);
        service.submitBundle(channelId, _javaProof("catch-up-1"));
        _assertOnlyAnchorChanged(before, "key2");
        service.submitBundle(channelId, _javaProof("catch-up-2"));
        _assertOnlyAnchorChanged(before, "key3");
        service.submitBundle(channelId, _javaProof("after-catch-up"));
        ClprTypes.Channel memory channel = service.getChannel(channelId);
        assertEq(channel.trustAnchorId, abi.encodePacked(hex"01", _anchor("key4")));
        assertEq(channel.receivedMessageId, 1);
        assertEq(channel.ackedMessageId, 1);
    }

    function test_javaNativeWitnessAdvancesAnchor() public {
        bytes32 nativeChannel = _openJavaChannel("native-initial", keccak256("native witness interoperability"));
        service.submitBundle(nativeChannel, _javaProof("native-witness"));
        ClprTypes.Channel memory channel = service.getChannel(nativeChannel);
        assertEq(channel.trustAnchorId, _javaAnchorId("native-final"));
        assertEq(channel.receivedMessageId, 0);
        assertEq(channel.ackedMessageId, 0);
    }

    function test_javaNativeElevenRotationsThenDelivers() public {
        // Recreate the base channel on a fresh service under the Java fixture's initial hinTS key.
        // The mock config binds the proof's fixed channel ID into the verifier context.
        ClprTypes.EconomicConfig memory economics = service.getEconomicConfig();
        service = _deployClprService(1, "eip155:1337");
        service.initialize("", defaultThrottles, "", "", economics);
        service.setClprEnabled(true);
        assertEq(_openJavaChannel("rotations-initial", bytes32(0)), channelId);
        connectorId = ConnectorRegistrar.register(
            IClprService(address(service)),
            channelId,
            keccak256("hiero-witness-connector"),
            address(connector),
            owner,
            0
        );
        service.sendMessage(channelId, connectorId, abi.encodePacked(owner), hex"deadbeef");

        ClprTypes.Channel memory before = service.getChannel(channelId);
        service.submitBundle(channelId, _javaProof("rotations-first"));
        _assertJavaAnchorOnly(before, "rotations-first");
        service.submitBundle(channelId, _javaProof("rotations-second"));
        _assertJavaAnchorOnly(before, "rotations-second");
        service.submitBundle(channelId, _javaProof("rotations-state"));
        ClprTypes.Channel memory channel = service.getChannel(channelId);
        assertEq(channel.trustAnchorId, _javaAnchorId("rotations-final"));
        assertEq(channel.receivedMessageId, 1);
        assertEq(channel.ackedMessageId, 1);
    }

    function test_javaMissingEndpointWitnessReverts() public {
        bytes memory before = abi.encode(service.getChannel(channelId));
        vm.expectRevert(ClprHieroProofCodec.MalformedHieroProof.selector);
        service.submitBundle(channelId, _javaProof("rotations-missing-witness"));
        assertEq(abi.encode(service.getChannel(channelId)), before);
    }

    function test_javaUnknownAnchorReverts() public {
        bytes memory before = abi.encode(service.getChannel(channelId));
        vm.expectRevert(HieroVerifier.ClprHieroAnchorMismatch.selector);
        service.submitBundle(channelId, _javaProof("rotations-first"));
        assertEq(abi.encode(service.getChannel(channelId)), before);
    }

    function test_javaStaleWitnessBatchReverts() public {
        bytes memory batch = _javaProof("catch-up-1");
        service.submitBundle(channelId, batch);
        bytes memory before = abi.encode(service.getChannel(channelId));
        vm.expectRevert(HieroVerifier.ClprHieroAnchorMismatch.selector);
        service.submitBundle(channelId, batch);
        assertEq(abi.encode(service.getChannel(channelId)), before);
    }

    function _javaAnchor(string memory key) private view returns (bytes memory) {
        return abi.encodePacked(keccak256(_javaProof(string.concat(key, "-key"))));
    }

    function _javaAnchorId(string memory key) private view returns (bytes memory) {
        return _javaProof(string.concat(key, "-anchor-id"));
    }

    function _assertJavaAnchorOnly(ClprTypes.Channel memory before, string memory key) private {
        ClprTypes.Channel memory channel = service.getChannel(channelId);
        before.trustAnchor = _javaAnchor(key);
        before.trustAnchorId = _javaAnchorId(key);
        assertEq(abi.encode(channel), abi.encode(before));
    }

    function _openJavaChannel(string memory initialKey, bytes32 salt) private returns (bytes32 nativeChannel) {
        bytes memory pubKey = _signerPubKey();
        nativeChannel = service.deriveChannelId("hiero:localnet", pubKey, salt);
        bytes memory initialAnchor = _javaAnchor(initialKey);
        vm.clearMockedCalls();
        vm.mockCall(
            address(hieroVerifier),
            abi.encodeWithSelector(IClprVerifier.verifyConfig.selector),
            abi.encode(
                ClprTypes.encodeChannelContext(
                    ClprTypes.ChannelContext({
                        channelId: FIXTURE_CHANNEL_ID, remoteServiceAddress: PEER_SERVICE_ADDRESS
                    })
                ),
                "hiero:localnet",
                PEER_SERVICE_ADDRESS,
                uint96(0),
                defaultThrottles,
                initialAnchor,
                abi.encodePacked(hex"01", initialAnchor),
                ClprTypes.ClprEndpointManifest({
                    version: 1, serviceAddress: PEER_SERVICE_ADDRESS, endpoints: new ClprTypes.Endpoint[](0)
                })
            )
        );
        service.registerChannel(nativeChannel, keccak256(abi.encodePacked(nativeChannel, pubKey)));
        (uint8 v, bytes32 r, bytes32 s) =
            vm.sign(signerPk, _ethSignedHash(keccak256(abi.encodePacked(nativeChannel, address(service)))));
        service.completeChannel(nativeChannel, pubKey, abi.encodePacked(r, s, v), salt, address(hieroVerifier), "", "");
        assertEq(service.getChannel(nativeChannel).trustAnchorId, _javaAnchorId(initialKey));
    }
}

// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "../IHederaTokenService/IHederaTokenService.sol";

/**
 * A contract to verify that throttle capacity is not double-reclaimed when a child dispatch reverts.
 * With a 1 TPS throttle:
 * 1. Revert an inner mint (should reclaim exactly 1 capacity unit)
 * 2. Do a successful outer mint (should use the reclaimed capacity)
 * 3. Try a third mint which should be THROTTLED (proving we didn't double-reclaim)
 */
contract ReclaimCheck {
    address constant HTS_ENTRY_POINT = address(0x167);

    // Response codes
    int32 constant SUCCESS = 22;
    int32 constant THROTTLED_AT_CONSENSUS = 366;

    /**
     * Tests that after reverting an inner mint and doing a successful outer mint,
     * a third mint is throttled (proving no double-reclaim).
     */
    function testNoDoubleReclaim(address token, bytes[] memory meta1, bytes[] memory meta2, bytes[] memory meta3) external {
        // Step 1: Do an inner mint that will be reverted
        try this.mintAndRevert(token, meta1) {
            // Should not reach here
            revert("Inner mint should have reverted");
        } catch {
            // Expected - inner mint reverted, capacity should be reclaimed
        }

        // Step 2: Do a successful mint using the reclaimed capacity
        (bool success2, bytes memory result2) = HTS_ENTRY_POINT.call(
            abi.encodeWithSelector(
                IHederaTokenService.mintToken.selector, token, 0, meta2));
        require(success2, "Second mint call failed");
        (int32 rc2, , ) = abi.decode(result2, (int32, uint64, int64[]));
        require(rc2 == SUCCESS, "Second mint should succeed with reclaimed capacity");

        // Step 3: Try a third mint - this should be throttled (proving no double-reclaim)
        (bool success3, bytes memory result3) = HTS_ENTRY_POINT.call(
            abi.encodeWithSelector(
                IHederaTokenService.mintToken.selector, token, 0, meta3));
        require(success3, "Third mint call failed");
        (int32 rc3, , ) = abi.decode(result3, (int32, uint64, int64[]));
        require(rc3 == THROTTLED_AT_CONSENSUS, "Third mint should be throttled - no double reclaim allowed");
    }

    /**
     * Helper function that mints and then reverts.
     */
    function mintAndRevert(address token, bytes[] memory meta) external {
        (bool success, bytes memory result) = HTS_ENTRY_POINT.call(
            abi.encodeWithSelector(
                IHederaTokenService.mintToken.selector, token, 0, meta));
        require(success, "Mint call failed");
        (int32 rc, , ) = abi.decode(result, (int32, uint64, int64[]));
        require(rc == SUCCESS, "Mint should succeed before revert");
        revert("Intentional revert to reclaim throttle capacity");
    }

    /**
     * Tests multiple reverted mints to ensure each only reclaims its own capacity.
     * With 1 TPS throttle, reverting 2 mints should only allow 1 successful mint after.
     */
    function testMultipleRevertsNoExtraReclaim(
        address token, 
        bytes[] memory revertMeta1, 
        bytes[] memory revertMeta2,
        bytes[] memory successMeta,
        bytes[] memory throttledMeta
    ) external {
        // Revert first inner mint
        try this.mintAndRevert(token, revertMeta1) {
        } catch {
            // Expected
        }

        // Revert second inner mint
        try this.mintAndRevert(token, revertMeta2) {
        } catch {
            // Expected
        }

        // Only ONE mint should succeed (the original capacity, not 2x reclaimed)
        (bool success1, bytes memory result1) = HTS_ENTRY_POINT.call(
            abi.encodeWithSelector(
                IHederaTokenService.mintToken.selector, token, 0, successMeta));
        require(success1, "Success mint call failed");
        (int32 rc1, , ) = abi.decode(result1, (int32, uint64, int64[]));
        require(rc1 == SUCCESS, "First real mint should succeed");

        // Second mint should be throttled
        (bool success2, bytes memory result2) = HTS_ENTRY_POINT.call(
            abi.encodeWithSelector(
                IHederaTokenService.mintToken.selector, token, 0, throttledMeta));
        require(success2, "Throttled mint call failed");
        (int32 rc2, , ) = abi.decode(result2, (int32, uint64, int64[]));
        require(rc2 == THROTTLED_AT_CONSENSUS, "Second mint should be throttled - multiple reverts don't stack");
    }
}


// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

/// Exercises the MODEXP precompile (address 0x05) with different length headers and a
/// bounded gas stipend, recording whether the sub-call succeeded and how much gas it used.
contract ModExpCaller {
    event Called(bool success, uint256 gasBefore, uint256 gasAfter);

    /// Invokes MODEXP with all length headers set to their maximum, forwarding only `stipend` gas.
    function callWithMaxHeaders(uint256 stipend) external {
        bytes memory input = new bytes(96);
        for (uint256 i = 0; i < 96; i++) {
            input[i] = 0xff;
        }
        uint256 g0 = gasleft();
        (bool ok, ) = address(0x05).call{gas: stipend}(input);
        emit Called(ok, g0, gasleft());
    }

    /// Invokes MODEXP with caller-supplied length headers, forwarding only `stipend` gas.
    function callWithHeaders(uint256 baseLen, uint256 expLen, uint256 modLen, uint256 stipend) external {
        bytes memory input = abi.encodePacked(baseLen, expLen, modLen);
        uint256 g0 = gasleft();
        (bool ok, ) = address(0x05).call{gas: stipend}(input);
        emit Called(ok, g0, gasleft());
    }

    /// Invokes MODEXP with a small, valid operand set (base=2, exp=2, mod=5).
    function callSmall(uint256 stipend) external {
        bytes memory input = abi.encodePacked(uint256(1), uint256(1), uint256(1),
            bytes1(0x02), bytes1(0x02), bytes1(0x05));
        uint256 g0 = gasleft();
        (bool ok, ) = address(0x05).call{gas: stipend}(input);
        emit Called(ok, g0, gasleft());
    }
}

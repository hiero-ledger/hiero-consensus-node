// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract GlobalProperties {

    function getChainID() external view returns (uint256) {
        return block.chainid;
    }

    function getGasLimit() external view returns (uint256) {
        return block.gaslimit;
    }

    // Reads block.gaslimit from a child frame given only a small gas stipend; GASLIMIT is
    // block-scoped, so this must equal the top-level value (not the stipend or the tx gas)
    function getGasLimitNested() external view returns (uint256) {
        (bool success, bytes memory result) =
            address(this).staticcall{gas: 100000}(abi.encodeWithSignature("getGasLimit()"));
        require(success, "nested call failed");
        return abi.decode(result, (uint256));
    }

    function getBaseFee() external view returns (uint256) {
        return block.basefee;
    }

    function getCoinbase() external view returns (address) {
        return block.coinbase;
    }

}

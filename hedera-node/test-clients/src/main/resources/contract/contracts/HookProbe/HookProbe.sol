// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

contract HookProbe {
    // Proves execution address is 0x16d when mapped by getCode()
    function whoAmI() external view returns (address self) {
        return address(this);
    }

    // Echoes the first 32 bytes of calldata (useful if you pass hookId in call input)
    fallback() external payable {
        assembly {
            // copy first 32 bytes of calldata to memory[0..31]
            calldatacopy(0x00, 0x00, 0x20)
            return(0x00, 0x20)
        }
    }
}

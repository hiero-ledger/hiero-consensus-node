// SPDX-License-Identifier: Apache-2.0
contract SmartContractsFees {
    function contractCall1Byte(bytes1 value) public pure {
    }

    function contractLocalCallGet1Byte() public pure returns (bytes1) {
        return 0x00;
    }
}

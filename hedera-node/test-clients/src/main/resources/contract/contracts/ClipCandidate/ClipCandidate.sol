// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;

contract ClipCandidate {
    function loopLargeCalldata(uint256 numLoops, uint256 calldataSize) external returns (bytes memory result) {
        bytes memory callData = new bytes(calldataSize);
        result = callData;

        for (uint256 i = 1; i <= numLoops; i++) {
            result = this.receiveAndReturn(callData);
        }
    }

    function receiveAndReturn(bytes calldata callData) external pure returns (bytes memory) {
        return callData;
    }

    function loopCreations(uint256 numChildren) external returns (address lastChild) {
        for (uint256 i = 1; i <= numChildren; i++) {
            ClipCandidateChild child = new ClipCandidateChild(i);
            lastChild = address(child);
        }
    }
}

contract ClipCandidateChild {
    uint256 private immutable serialNumber;

    constructor(uint256 _serialNumber) {
        serialNumber = _serialNumber;
    }

    function getSerialNumber() external view returns (uint256) {
        return serialNumber;
    }
}

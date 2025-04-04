// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.0;
/**
 * @title Benchmarks
 */
contract Benchmarks {
    bytes32 singleProp;
    mapping(bytes32 => bytes32) public benchmarkingMap;
    bytes32[] public mapKeys;
    mapping(bytes32 => uint256[]) public bigArray;
    uint256 public counter;

    /**
     * Triggers the execution of a single SSTORE opcode
     * @dev Stores to provided variable into storage. Example variable: 0xf2eeb729e636a8cb783be044acf6b7b1e2c5863735b60d6daae84c366ee87d97
     * @param _singleProp value to store
     */
    function twoSSTOREs(bytes32 _singleProp) public {
        singleProp = _singleProp;
        counter++;
    }
    /**
     * Triggers the execution of the MLOAD opcode. Loads the stored variable from storage
     * @dev Return value
     * @return value of 'singleProp'
     */
    function singleMLOAD() public view returns (bytes32) {
        return singleProp;
    }
    /**
     * Stores N number of pseudo randomly generated bytes32 variables to a mapping. Triggers 2*N number of SSTORE (create)
     */
    function sstoreCreate(uint256 n) public {
        for (uint256 i = 0; i < n; i++) {
            bytes32 pseudoRandom =
            keccak256(abi.encodePacked(bytes32(i), block.number));
            benchmarkingMap[pseudoRandom] = pseudoRandom;
            mapKeys.push(pseudoRandom);
        }
        counter++;
    }
    /**
     * Updates N number of bytes32 variables. Triggers N number of SSTORE (update)
     * IMPORTANT! N must be lower than the number of sstoreCreates's performed in the contract so far!
     */
    function sstoreUpdate(uint256 n) public {
        require(n <= mapKeys.length, "not enough keys have been generated");
        for (uint256 i = 0; i < n; i++) {
            bytes32 pseudoRandom =
            keccak256(abi.encodePacked(bytes32(i), block.number));
            benchmarkingMap[mapKeys[i]] = pseudoRandom;
        }
        counter++;
    }
    /**
     * Stores arbitrary array of bytes32 at a pseudo random key.
     * This means that the SSTORE executed is Create (not Update)
     */
    function bigSSTORE(uint256[] memory data) public {
        bytes32 pseudoRandomKey = keccak256(abi.encodePacked(block.number));
        bigArray[pseudoRandomKey] = data;
        counter++;
    }

    function loadTx(uint256 n) public {
        singleProp = keccak256(abi.encodePacked(bytes32(n), block.timestamp));
        for (uint256 i = 0; i < n; i++) {
            bytes32 loadProp = singleProp;
        }
        counter++;
    }
}

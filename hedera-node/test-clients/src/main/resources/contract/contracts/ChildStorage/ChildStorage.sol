// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;
contract Child {
    uint256[] sizedArray;
    uint256 totalEntries = 0;

    // Grow the child's storage size by appending to the array
    function growInKB(uint256 _howManyKB, uint256 _value) public {
        uint256 newEntries = 16 * _howManyKB;
        totalEntries = totalEntries + newEntries;
        uint256 startSize = sizedArray.length;
        sizedArray.length = totalEntries;
        for (uint i = startSize; i < totalEntries; i++) {
            sizedArray[i] = _value;
        }
    }

    // Persist one value, which should persist the whole thing
    function setValueZero(uint256 _value) public {
        sizedArray[0] = _value;
    }

    // Check the last value that was persisted
    function getValueZero() public view returns (uint _get) {
        return sizedArray[0];
    }

}
contract ChildStorage {
    uint256 myValue = 73;
    Child[2] myChildren;
    constructor() public {
        myChildren[0] =  new Child();
        myChildren[1] =  new Child();
    }

    // Set the child's storage size and values, plus my own value
    function growChild (uint256 _childId,uint256 _howManyKB, uint256 _value) public {
        myChildren[_childId].growInKB(_howManyKB, _value);
        myValue = _value;
    }

    // Set one child's value[0] and read the other
    function setZeroReadOne (uint256 _value) public returns (uint _getOne) {
        myChildren[0].setValueZero(_value);
        myValue = _value;
        uint256 childOneValue = myChildren[1].getValueZero();
        return childOneValue;
     }

    // Set both children's value[0], plus my own value
    function setBoth (uint256 _value) public {
        myChildren[0].setValueZero(_value);
        myChildren[1].setValueZero(_value);
        myValue = _value;
    }

    // Get value[0] from a child
    function getChildValue(uint256 _childId) public view returns (uint _get) {
        return myChildren[_childId].getValueZero();
    }

    // Get my own value
    function getMyValue() public view returns (uint _get) {
        return myValue;
    }

}


// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.0 <0.5.0;

contract TraceabilityCallcode {
    uint256 slot0;
    uint256 slot1;
    uint256 slot2;

    constructor (uint256 _slot0, uint256 _slot1, uint256 _slot2) public {
        slot0 = _slot0;
        slot1 = _slot1;
        slot2 = _slot2;
    }

    function eetScenario7(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot1(55252);

        TraceabilityCallcode contractB = TraceabilityCallcode(_contractBAddress);
        contractB.getSlot2();
        contractB.setSlot2(524);

        contractB.callcodeAddressGetSlot0(_contractCAddress);
        contractB.callcodeAddressSetSlot0(_contractCAddress, 54);
        contractB.callcodeAddressGetSlot1(_contractCAddress);
        contractB.callcodeAddressSetSlot1(_contractCAddress, 0);
    }

    function eetScenario8(address _contractBAddress, address _contractCAddress) external {
        this.getSlot0();
        this.setSlot0(2);

        this.getSlot1();
        this.setSlot1(55252);

        this.callcodeAddressGetSlot2(_contractBAddress);
        this.callcodeAddressSetSlot2(_contractBAddress, 524);

        _contractBAddress.callcode(abi.encodeWithSignature("callcodeAddressSetSlot0(address,uint256)", _contractCAddress, 55));
    }

    ////////////////////////////////////////////////////////////////////////////////////////////

    // GETTERS AND SETTERS

    function getSlot0() external returns(uint256) {
        return slot0;
    }

    function setSlot0(uint256 _slot0) external {
        slot0 = _slot0;
    }

    function getSlot1() external returns(uint256) {
        return slot1;
    }

    function setSlot1(uint256 _slot1) external {
        slot1 = _slot1;
    }

    function getSlot2() external returns(uint256) {
        return slot2;
    }

    function setSlot2(uint256 _slot2) external {
        slot2 = _slot2;
    }



    // CALLCODE TO ADDRESS

    function callcodeAddressGetSlot0(address _address) external {
        _address.callcode(abi.encodeWithSignature("getSlot0()"));
    }

    function callcodeAddressSetSlot0(address _address, uint256 slot) external{
        _address.callcode(abi.encodeWithSignature("setSlot0(uint256)", slot));
    }

    function callcodeAddressGetSlot1(address _address) external {
        _address.callcode(abi.encodeWithSignature("getSlot1()"));
    }

    function callcodeAddressSetSlot1(address _address, uint256 slot) external {
        _address.callcode(abi.encodeWithSignature("setSlot1(uint256)", slot));
    }

    function callcodeAddressSetSlot2(address _address, uint256 slot) external {
        _address.callcode(abi.encodeWithSignature("setSlot2(uint256)", slot));
    }

    function callcodeAddressGetSlot2(address _address) external {
        _address.callcode(abi.encodeWithSignature("getSlot2()"));
    }
}

# This is a custom filter plugin for Ansible that converts an IP address to a base64 encoded hex string.
import ipaddress
import base64
import re
from ansible.errors import AnsibleFilterError

class FilterModule(object):
    def filters(self):
        return {
            'ip_to_hex_b64': self.ip_to_hex_b64,
            'hex_to_b64': self.hex_to_b64,
            'validate_b64': self.validate_b64,
            'is_valid_ip': self.is_valid_ip,
            'is_valid_hex': self.is_valid_hex
        }

    def is_valid_ip(self, ip):
        """Validate if a string is a valid IP address."""
        try:
            ipaddress.ip_address(ip)
            return True
        except ValueError:
            return False

    def is_valid_hex(self, hex_str):
        """Validate if a string is a valid hexadecimal string."""
        if not isinstance(hex_str, str):
            return False
        return bool(re.match(r'^[0-9A-Fa-f]+$', hex_str))

    def validate_b64(self, b64_str):
        """Validate if a string is a valid base64 encoded string."""
        if not isinstance(b64_str, str):
            return False
        return bool(re.match(r'^[A-Za-z0-9+/]+={0,2}$', b64_str))

    def ip_to_hex_b64(self, ip):
        """Convert IP to base64 encoded hex with validation."""
        if not self.is_valid_ip(ip):
            raise AnsibleFilterError(f"Invalid IP address: {ip}")

        try:
            # Convert IP to integer
            ip_int = int(ipaddress.ip_address(ip))
            # Convert to hex, remove '0x' prefix, and pad to 8 characters
            hex_str = '{:08X}'.format(ip_int)
            # Convert hex to bytes
            hex_bytes = bytes.fromhex(hex_str)
            # Encode to base64
            result = base64.b64encode(hex_bytes).decode('utf-8')

            # Verify result is valid base64
            if not self.validate_b64(result):
                raise AnsibleFilterError(f"Generated invalid base64 string: {result}")

            return result
        except Exception as e:
            raise AnsibleFilterError(f"Error converting IP to base64 encoded hex: {str(e)}") from e

    def hex_to_b64(self, hex_str):
        """Convert hex to base64 with validation."""
        if not self.is_valid_hex(hex_str):
            raise AnsibleFilterError(f"Invalid hexadecimal string: {hex_str}")

        try:
            # Ensure even length for hex string
            if len(hex_str) % 2 != 0:
                hex_str = '0' + hex_str

            # Convert hex to bytes
            binary_data = bytes.fromhex(hex_str)
            # Encode to base64
            result = base64.b64encode(binary_data).decode('utf-8')

            # Verify result is valid base64
            if not self.validate_b64(result):
                raise AnsibleFilterError(f"Generated invalid base64 string: {result}")

            return result
        except Exception as e:
            raise AnsibleFilterError(f"Error converting hex to base64: {str(e)}") from e

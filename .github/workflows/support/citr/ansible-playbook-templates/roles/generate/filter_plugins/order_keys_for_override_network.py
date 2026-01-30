# This is a custom filter plugin for Ansible that orders the keys in a dictionary.
# This is used in the override-network.json.j2 file to ensure that the keys are in the correct order.
from collections import OrderedDict

def order_node_entry(entry):
    ordered = OrderedDict()

    # Define the order for both rosterEntry and node
    structure_order = {
        'rosterEntry': ['nodeId', 'weight', 'gossipCaCertificate', 'gossipEndpoint'],
        'node': ['nodeId', 'accountId', 'description', 'gossipEndpoint', 'serviceEndpoint', 'grpcCertificateHash', 'weight', 'adminKey']
    }

    for section, order in structure_order.items():
        if section in entry:
            ordered[section] = OrderedDict()
            for key in order:
                if key in entry[section]:
                    ordered[section][key] = entry[section][key]
                else:
                    # Optionally, you can add a placeholder or skip
                    # ordered[section][key] = None
                    pass

    # Add any additional keys that weren't in the predefined order
    for section in entry:
        if section not in ordered:
            ordered[section] = entry[section]
        elif isinstance(entry[section], dict):
            for key in entry[section]:
                if key not in ordered[section]:
                    ordered[section][key] = entry[section][key]

    return ordered

class FilterModule(object):
    def filters(self):
        return {'order_node_entry': order_node_entry}

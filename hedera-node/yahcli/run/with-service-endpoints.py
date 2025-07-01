### 
# A script to augment deprecated-format 0.0.101 and 0.0.102 system 
# files with ServiceEndpoint fields.
### 

import sys
import json
from collections import defaultdict

hashes = {}
ips_by_id = defaultdict(list)

def svc_endpoint_with(ip, port):
    return { 'ipAddressV4': ip, 'port': port }

def book_enriched(entry):
    node_id = entry['nodeId']
    all_ips = ips_by_id[node_id]
    all_endpoints = []
    for ip in all_ips:
        for port in [ 50211, 50212 ]:
            all_endpoints.append(svc_endpoint_with(ip, port))
    entry['endpoints'] = all_endpoints
    entry['certHash'] = hashes[node_id]
    return entry

book_loc, details_loc = sys.argv[1], sys.argv[2]
with open(book_loc, 'r') as fin:
    wrapper = json.load(fin)
    entries = wrapper['entries']
    for entry in entries:
        node_id = entry['nodeId']
        ips_by_id[node_id].append(entry['deprecatedIp'])
        hashes[node_id] = entry['certHash']
    mod_book_loc = book_loc[(book_loc.rindex('/') + 1):]
    with open(mod_book_loc, 'w') as fout:
        new_wrapper = { 'entries': [ book_enriched(entry) for entry in entries ] }
        json.dump(new_wrapper, fout, indent=2)

with open(details_loc, 'r') as fin:
    wrapper = json.load(fin)
    entries = wrapper['entries']
    mod_details_loc = details_loc[(details_loc.rindex('/') + 1):]
    with open(mod_details_loc, 'w') as fout:
        new_wrapper = { 'entries': [ book_enriched(entry) for entry in entries ] }
        json.dump(new_wrapper, fout, indent=2)

import json
import re
import os
import sys

if len(sys.argv) < 3:
  print("Usage: process_json_release_notes.py <input_json> <output_md>")
  sys.exit(1)

json_file = sys.argv[1]
output_file = sys.argv[2]

repo = os.environ.get("GITHUB_REPOSITORY")
pr_url_prefix = f"https://github.com/{repo}/pull/"

with open(json_file) as f:
  data = json.load(f)

features = []
bug_fixes = []
others = []

for item in data:
  typ = item.get("type")
  desc = item.get("description", "").strip()
  if not desc:
    continue

  # Convert PR numbers (#12345) into links
  desc = re.sub(r"\(#(\d+)\)", r"[#\1](" + pr_url_prefix + r"\1)", desc)

  if typ == "feat":
    features.append(desc)
  elif typ == "fix":
    bug_fixes.append(desc)
  else:
    others.append(desc)

with open(output_file, "w") as out:
  if features:
    out.write("### Features\n")
    for f in features:
      out.write(f"- {f}\n")
    out.write("\n")

  if bug_fixes:
    out.write("### Bug Fixes\n")
    for f in bug_fixes:
      out.write(f"- {f}\n")
    out.write("\n")

  if others:
    out.write("### Other Changes\n")
    for f in others:
      out.write(f"- {f}\n")
    out.write("\n")

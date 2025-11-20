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
fixes = []
build = []
chore = []
ci = []
docs = []
performance = []
refactoring = []
style = []
tests = []
others = []

for item in data:
  commit_type = item.get("type")
  desc = item.get("description", "").strip()
  if not desc:
    continue

  # Convert PR numbers (#12345) into links
  desc = re.sub(r"\(#(\d+)\)", r"[#\1](" + pr_url_prefix + r"\1)", desc)

  if commit_type == "feat":
    features.append(desc)
  elif commit_type == "fix":
    fixes.append(desc)
  elif commit_type == "build":
    build.append(desc)
  elif commit_type == "chore":
    chore.append(desc)
  elif commit_type == "ci":
    ci.append(desc)
  elif commit_type == "docs":
    docs.append(desc)
  elif commit_type == "perf":
    performance.append(desc)
  elif commit_type == "refactor":
    refactoring.append(desc)
  elif commit_type == "style":
    style.append(desc)
  elif commit_type == "test":
    tests.append(desc)
  else:
    others.append(desc)

other_commits_exist = any([
  build,
  chore,
  ci,
  docs,
  performance,
  refactoring,
  style,
  tests
])

with open(output_file, "w") as out:
  if features or fixes or other_commits_exist:
    out.write("# Release Notes\n")
  if features:
    out.write("## Features\n")
    for f in features:
      out.write(f"- {f}\n")
    out.write("\n")

  if fixes:
    out.write("## Bug Fixes\n")
    for f in fixes:
      out.write(f"- {f}\n")
    out.write("\n")

  if other_commits_exist:
    out.write("## Other Changes\n")
    if build:
      out.write("### Build System\n")
      for f in build:
        out.write(f"- {f}\n")
      out.write("\n")
    if chore:
      out.write("### Chores\n")
      for f in chore:
        out.write(f"- {f}\n")
      out.write("\n")
    if ci:
      out.write("### Continuous Integration\n")
      for f in ci:
        out.write(f"- {f}\n")
      out.write("\n")
    if docs:
      out.write("### Documentation Updates\n")
      for f in docs:
        out.write(f"- {f}\n")
      out.write("\n")
    if performance:
      out.write("### Performance Improvements\n")
      for f in performance:
        out.write(f"- {f}\n")
      out.write("\n")
    if refactoring:
      out.write("### Refactoring\n")
      for f in refactoring:
        out.write(f"- {f}\n")
      out.write("\n")
    if style:
      out.write("### Style Changes\n")
      for f in style:
        out.write(f"- {f}\n")
      out.write("\n")
    if tests:
      out.write("### Tests\n")
      for f in tests:
        out.write(f"- {f}\n")
      out.write("\n")
    if others:
      out.write("### Everything Else\n")
      for f in others:
        out.write(f"- {f}\n")
      out.write("\n")

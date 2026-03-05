import json
import os
import re
import requests
import sys

if len(sys.argv) < 3:
  print("Usage: process_json_release_notes.py <input_json> <output_md>")
  sys.exit(1)

json_file = sys.argv[1]
output_file = sys.argv[2]

repo = os.environ.get("GITHUB_REPOSITORY")
if not repo:
  print("GITHUB_REPOSITORY must be set")
  sys.exit(1)

token = os.environ.get("GITHUB_TOKEN")
if not token:
  print("GITHUB_TOKEN must be set")
  sys.exit(1)

pr_url_prefix = f"https://github.com/{repo}/pull/"

session = requests.Session()
session.headers["Authorization"] = f"Bearer {token}"
session.headers["Accept"] = "application/vnd.github+json"

# cache PR number list if we have multiple PR lookups
pr_user_cache = {}

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

# func to get the author of a PR
def get_pr_author(pr_number: str):
  if pr_number in pr_user_cache:
    return pr_user_cache[pr_number]

  url = f"https://api.github.com/repos/{repo}/pulls/{pr_number}"
  resp = session.get(url)
  if resp.status_code != 200:
    pr_user_cache[pr_number] = None
    return None
  data = resp.json()
  username = data.get("user", {}).get("login")
  pr_user_cache[pr_number] = username
  return username

for item in data:
  commit_type = item.get("type")
  desc = item.get("description", "").strip()
  if not desc:
    continue

  # find all PR numbers in the release notes
  pr_numbers = re.findall(r"#(\d+)", desc)

  # convert PR numbers into links
  desc = re.sub(r"\(#(\d+)\)", r"[#\1](" + pr_url_prefix + r"\1)", desc)

  # get the authors on the PR
  authors = []
  for pr_number in pr_numbers:
    username = get_pr_author(pr_number)
    if username:
      authors.append(f"@{username}")

  # deduplicate authors while keeping order
  seen = set()
  deduped_authors = []
  for a in authors:
    if a not in seen:
      deduped_authors.append(a)
      seen.add(a)

  # add the deduped authors to description
  if deduped_authors:
    desc = f"{desc} by {', '.join(deduped_authors)}"

  # categorize commits by conventional commit type
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

# if we have other commits, we'll need to create the Other Changes section
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

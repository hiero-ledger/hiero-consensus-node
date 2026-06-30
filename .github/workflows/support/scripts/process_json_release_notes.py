import json
import os
import re
import sys

import requests


# Ordered category definitions. Each conventional-commit type maps to the
# heading used in the release notes. "feat" and "fix" get their own top-level
# sections; everything else is grouped under the "Other Changes" section.
# Unknown commit types fall through to the FALLBACK_TYPE bucket.
TOP_LEVEL_SECTIONS = [
  ("feat", "Features"),
  ("fix", "Bug Fixes"),
]

OTHER_SECTIONS = [
  ("build", "Build System"),
  ("chore", "Chores"),
  ("ci", "Continuous Integration"),
  ("docs", "Documentation Updates"),
  ("perf", "Performance Improvements"),
  ("refactor", "Refactoring"),
  ("style", "Style Changes"),
  ("test", "Tests"),
  ("other", "Everything Else"),
]

# Bucket key for any commit type we don't explicitly recognize.
FALLBACK_TYPE = "other"


class PrAuthorResolver:
  """Looks up and caches the GitHub author for a pull request number."""

  def __init__(self, repo, token):
    self._repo = repo
    self._cache = {}
    self._session = requests.Session()
    self._session.headers["Authorization"] = f"Bearer {token}"
    self._session.headers["Accept"] = "application/vnd.github+json"

  def author_for(self, pr_number):
    if pr_number in self._cache:
      return self._cache[pr_number]

    url = f"https://api.github.com/repos/{self._repo}/pulls/{pr_number}"
    resp = self._session.get(url)
    username = None
    if resp.status_code == 200:
      username = resp.json().get("user", {}).get("login")
    self._cache[pr_number] = username
    return username


class DescriptionFormatter:
  """Turns a raw commit description into a release-notes line: PR references
  become Markdown links and the resolved PR authors are appended."""

  PR_REFERENCE = re.compile(r"#(\d+)")
  PR_PARENTHETICAL = re.compile(r"\(#(\d+)\)")

  def __init__(self, repo, author_resolver):
    self._pr_url_prefix = f"https://github.com/{repo}/pull/"
    self._authors = author_resolver

  def format(self, desc):
    pr_numbers = self.PR_REFERENCE.findall(desc)
    desc = self.PR_PARENTHETICAL.sub(
      r"[#\1](" + self._pr_url_prefix + r"\1)", desc
    )

    authors = self._collect_authors(pr_numbers)
    if authors:
      desc = f"{desc} by {', '.join(authors)}"
    return desc

  def _collect_authors(self, pr_numbers):
    # Resolve authors, dropping misses and de-duplicating while keeping order.
    seen = set()
    authors = []
    for pr_number in pr_numbers:
      username = self._authors.author_for(pr_number)
      if username and username not in seen:
        authors.append(f"@{username}")
        seen.add(username)
    return authors


class ReleaseNotes:
  """Collects formatted release-note lines, bucketed by commit type."""

  def __init__(self):
    self._headings = dict(TOP_LEVEL_SECTIONS + OTHER_SECTIONS)
    self._lines = {key: [] for key in self._headings}

  def add(self, commit_type, line):
    key = commit_type if commit_type in self._lines else FALLBACK_TYPE
    self._lines[key].append(line)

  def lines_for(self, key):
    return self._lines[key]

  def heading_for(self, key):
    return self._headings[key]

  def has_lines(self, keys):
    return any(self._lines[key] for key in keys)


class MarkdownRenderer:
  """Renders collected release notes to Markdown."""

  def __init__(self, notes):
    self._notes = notes
    self._top_level_keys = [key for key, _ in TOP_LEVEL_SECTIONS]
    self._other_keys = [key for key, _ in OTHER_SECTIONS]

  def render(self, out):
    # The "Other Changes" section is emitted when any non-feat/fix type has
    # content, including the catch-all FALLBACK_TYPE bucket for unrecognized
    # commit types.
    has_other = self._notes.has_lines(self._other_keys)

    # Build the document as a list of lines. Each populated section is followed
    # by an empty string, which renders as a blank-line separator once joined.
    # Joining (rather than trailing each section with "\n") leaves exactly one
    # newline at end of file.
    lines = []
    if self._notes.has_lines(self._top_level_keys) or has_other:
      lines.append("# Release Notes")

    for key in self._top_level_keys:
      self._append_section(lines, key, level=2)

    if has_other:
      lines.append("## Other Changes")
      for key in self._other_keys:
        self._append_section(lines, key, level=3)

    out.write("\n".join(lines))

  def _append_section(self, lines, key, level):
    entries = self._notes.lines_for(key)
    if not entries:
      return
    lines.append(f"{'#' * level} {self._notes.heading_for(key)}")
    lines.extend(f"- {entry}" for entry in entries)
    lines.append("")  # blank-line separator after the section


def main():
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

  with open(json_file) as f:
    data = json.load(f)

  formatter = DescriptionFormatter(repo, PrAuthorResolver(repo, token))
  notes = ReleaseNotes()

  for item in data:
    desc = item.get("description", "").strip()
    if not desc:
      continue
    notes.add(item.get("type"), formatter.format(desc))

  with open(output_file, "w") as out:
    MarkdownRenderer(notes).render(out)


if __name__ == "__main__":
  main()

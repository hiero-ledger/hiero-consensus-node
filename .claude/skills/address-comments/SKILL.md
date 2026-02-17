---
name: address-comments
description: Analyze all unresolved review comments on a GitHub PR. For each comment, assess validity, suggest code fixes or rebuttals, and draft reply messages.
---

Analyze every unresolved review comment on a GitHub Pull Request and provide actionable guidance for each one.

**Argument:** $ARGUMENTS (required — a GitHub PR URL or PR number, e.g. "https://github.com/hiero-ledger/hiero-consensus-node/pull/12345" or "12345")

If no PR is provided, ask the user for one. Do not proceed without it.

---

## Phase 1: Parse Input and Fetch PR Metadata

### 1a. Parse the PR reference

Extract the owner, repo, and PR number from the argument:
- If a full URL like `https://github.com/<owner>/<repo>/pull/<number>`, parse all three.
- If just a number, assume the current repository (`hiero-ledger/hiero-consensus-node`).

### 1b. Fetch PR metadata

```bash
gh pr view <NUMBER> --repo <owner>/<repo> --json title,body,headRefName,baseRefName,author,state,url
```

Display the PR title and author for context.

### 1c. Fetch the PR diff

```bash
gh pr diff <NUMBER> --repo <owner>/<repo>
```

Store this for reference when analyzing comments against actual code changes.

---

## Phase 2: Fetch All Unresolved Review Threads

Use the GitHub GraphQL API to fetch review threads with their resolution status. This is critical — the REST API does not expose `isResolved`.

```bash
gh api graphql -f query='
query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      reviewThreads(first: 100) {
        nodes {
          isResolved
          isOutdated
          comments(first: 20) {
            nodes {
              id
              body
              author { login }
              path
              line
              originalLine
              startLine
              originalStartLine
              diffHunk
              createdAt
              url
            }
          }
        }
      }
    }
  }
}' -F owner="<OWNER>" -F repo="<REPO>" -F number=<NUMBER>
```

If there are more than 100 threads, paginate using `after` cursor.

**Filter:** Keep only threads where `isResolved == false`. Discard resolved threads entirely.

If there are zero unresolved threads, report that and stop.

---

## Phase 3: Build Context for Each Comment

For each unresolved thread:

### 3a. Identify the file and lines
Extract `path`, `line` (or `originalLine`), `startLine` (for multi-line comments), and `diffHunk` from the first comment in the thread.

### 3b. Read the current file
Use the `Read` tool to read the file at the path indicated by the comment. Read enough surrounding context (at least 30 lines before and after the commented line) to fully understand the code.

### 3c. Collect the full thread
A thread may have multiple comments (the original review comment plus replies). Collect ALL comments in the thread in chronological order to understand the full conversation — the reviewer may have clarified their point, the author may have already partially responded, etc.

### 3d. Understand the diff context
Cross-reference the comment against the PR diff to understand:
- Was this line added, modified, or is it existing code shown as context?
- What was the code before vs after?
- Is the comment about the new code or about something missing?

---

## Phase 4: Deep Analysis of Each Comment

For each unresolved thread, perform a thorough analysis:

### 4a. Categorize the comment
Classify as one of:
- **Bug/Correctness** — The reviewer identified a potential bug, logic error, or incorrect behavior
- **Style/Convention** — Naming, formatting, code style, project conventions
- **Design/Architecture** — Structural concern, wrong pattern, abstraction issues
- **Performance** — Efficiency concern, unnecessary allocations, O(n) issues
- **Safety/Security** — Null safety, injection risk, access control
- **Documentation** — Missing/wrong javadoc, comments, or explanatory text
- **Testing** — Missing test coverage, inadequate assertions, test quality
- **Nit** — Minor preference, optional improvement, cosmetic
- **Question** — Reviewer is asking for explanation, not necessarily requesting a change
- **Suggestion** — GitHub suggestion block (ready to apply)

### 4b. Assess validity
For each comment, determine:

1. **Is the reviewer's concern valid?** Read the surrounding code carefully. Check project conventions (CLAUDE.md rules). Look for similar patterns elsewhere in the codebase if the comment questions a pattern choice.

2. **Does the change the reviewer requests actually improve the code?** Sometimes a reviewer's concern is valid but their proposed fix is wrong or there's a better alternative.

3. **Is this consistent with the project's existing patterns?** Search the codebase for similar code to see if the existing pattern matches what the reviewer wants. If the rest of the codebase does it the way the PR does, the reviewer may be wrong.

4. **Is there a spec or HIP requirement that dictates the current approach?** Check if the code is implementing a specific requirement that justifies the current design.

### 4c. Determine the verdict
For each comment, conclude with one of:
- **AGREE — Fix needed**: The reviewer is right, and you should make the change.
- **PARTIALLY AGREE — Adjust**: The reviewer has a point, but the fix should be different from what they suggest.
- **DISAGREE — Push back**: The current code is correct; explain why.
- **QUESTION — Clarify**: Need more information from the reviewer before acting.
- **ALREADY ADDRESSED**: The concern was already fixed in a subsequent commit or is handled elsewhere.

---

## Phase 5: Generate Action Plans and Responses

For each unresolved thread, produce:

### If AGREE or PARTIALLY AGREE:

1. **What to change**: Describe the exact code change needed, with the file path and line numbers.
2. **Code snippet**: Show the before/after code (or the suggested fix).
3. **Draft reply**: Write a concise, professional reply to the reviewer acknowledging their point. Examples:
   - "Good catch, fixed in the next push."
   - "You're right — I've updated this to use `X` instead. Thanks for the suggestion."
   - "Partially addressed — I went with `Y` instead of `Z` because [reason], but I've incorporated your core feedback."

### If DISAGREE:

1. **Why it's fine as-is**: Provide a clear, evidence-based explanation. Reference codebase patterns, spec requirements, or technical reasoning.
2. **Draft reply**: Write a respectful reply that explains the reasoning without being dismissive. Examples:
   - "I considered that, but went with the current approach because [reason]. The existing pattern in `SimilarHandler.java:L45` does the same thing."
   - "This is intentional — the HIP-XXXX spec requires [specific behavior], which is why the check is structured this way."
   - "Good question — the reason for this is [explanation]. Happy to add a comment clarifying this if it helps."

### If QUESTION:

1. **What to clarify**: Identify what information is missing.
2. **Draft reply**: Write a reply that either answers the question directly or asks for clarification.

---

## Phase 6: Output

Present the analysis as a structured report:

### Summary
- PR: [title] (#number) by @author
- Total review threads: X
- Unresolved: Y
- Resolved: Z (skipped)

### Comment-by-Comment Analysis

For each unresolved thread, present:

```
---
### Comment #N: @reviewer on `file/path.java:L42`
**Category:** Bug/Correctness
**Verdict:** AGREE — Fix needed

> [quoted reviewer comment]

**Analysis:**
[Your assessment of why the reviewer is right/wrong, with evidence]

**Action:**
[Exact code change needed, with before/after]

**Suggested reply:**
> [Draft message to post as a reply]
---
```

### Action Summary
At the end, provide a consolidated list:
1. **Changes to make** — ordered list of code changes with file paths
2. **Replies to send** — summary of which comments to agree with and which to push back on
3. **Questions to ask** — any threads where you need reviewer clarification before acting

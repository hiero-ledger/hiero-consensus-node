# Named metric groups

Named groups are shortcuts for selections you use often. A selection file, or another group, can refer to a group as `group:<name>`.
List the groups with `catalog.py OUT_DIR --list-groups`.

No groups are defined yet, because the useful ones depend on who is investigating and why.
To add one, tell Claude "save this selection as group X". Claude adds a section in the format below. Keep the names short,
lowercase, and hyphenated.

Format: a `## group: <name>` heading, then a one-line description, then a fenced block with one selector per line.
The selector syntax is the same as in selection files (see the docstring of `scripts/catalog.py`):
`topic:`, `category:`, `family:`, `names:`, `name:`, `re:`, `desc:`, `group:`, plain text, and `-` to exclude.
Don't save `menu:` numbers in a group: they change when topics are edited. Convert them to `topic:`, `family:` or
`names:` selectors first.

Example (indented, so it is not read as a real group):

    ## group: example-queues
    Backlog and busy fraction of every platform component queue.
    ```
    family:*_unhandled_task_count
    family:*_busy_fraction
    ```

<!-- Add groups below this line. -->

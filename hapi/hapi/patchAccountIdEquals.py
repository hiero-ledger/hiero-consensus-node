#!/usr/bin/env python3
"""Rewrite generated AccountID.equals to compare numeric accounts as primitives."""
import pathlib
import re
import sys

HELPER_RE = re.compile(
    r"\n    /\*\* [Ff]ast-path numeric account OneOf equality\.? \*/\n"
    r"    private static boolean accountOneOfEquals\([\s\S]*?\n    \}\n"
)
IDENTITY = """        if (this == that) {
            return true;
        }
        if (that == null || this.getClass() != that.getClass()) {"""
NO_IDENTITY = """        if (that == null || this.getClass() != that.getClass()) {"""
HELPER = """
    /** Fast-path numeric account OneOf equality. */
    private static boolean accountOneOfEquals(
            @NonNull final OneOf<AccountID.AccountOneOfType> a,
            @NonNull final OneOf<AccountID.AccountOneOfType> b) {
        if (a == b) {
            return true;
        }
        final var kind = a.kind();
        if (kind != b.kind()) {
            return false;
        }
        if (kind == AccountOneOfType.ACCOUNT_NUM) {
            return ((Long) a.value()).longValue() == ((Long) b.value()).longValue();
        }
        return java.util.Objects.equals(a.value(), b.value());
    }

"""
TOSTRING_JAVADOC = "    /**\n     * Override the default toString method for AccountID"


def main(path: pathlib.Path) -> None:
    if not path.is_file():
        return
    source = path.read_text()
    source = HELPER_RE.sub("\n", source)
    source = source.replace(IDENTITY, NO_IDENTITY)
    source = source.replace(
        "if (account != null && !accountOneOfEquals(account, thatObj.account))",
        "if (account != null && !account.equals(thatObj.account))",
    )
    needle = "if (account != null && !account.equals(thatObj.account))"
    if needle not in source:
        raise SystemExit("AccountID.equals OneOf comparison not found; PBJ codegen changed")
    source = source.replace(
        "    public boolean equals(Object that) {\n        if (that == null || this.getClass() != that.getClass()) {",
        "    public boolean equals(Object that) {\n        if (this == that) {\n            return true;\n        }\n        if (that == null || this.getClass() != that.getClass()) {",
    )
    source = source.replace(
        needle, "if (account != null && !accountOneOfEquals(account, thatObj.account))"
    )
    insert_at = source.find(TOSTRING_JAVADOC)
    if insert_at < 0:
        raise SystemExit("AccountID.toString javadoc not found; cannot insert equals helper")
    path.write_text(source[:insert_at] + HELPER + source[insert_at:])


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))

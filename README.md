# kotoba-lang/org-ieee-grep — fixed-string `grep`, as a Kotoba command binary

Line search in the shape of POSIX `grep` (IEEE Std 1003.1) restricted to
`-F`, written in `.kotoba` and compiled to a standalone native executable.

```sh
./grep PATTERN FILE      # the lines containing PATTERN
```

Exit **0** when at least one line matched, **1** when none did. For grep that
status *is* the answer, so it is asserted alongside the bytes.

## The comparison is `grep -F`, and that is not a convenience

POSIX grep reads its pattern as a basic regular expression; this reads it
literally. Measured 2026-09-10 on a file holding `a.c` and `abc`:

```
grep    'a.c'  ->  a.c   abc      (both: `.` is any character)
grep -F 'a.c'  ->  a.c              (one)
```

Comparing against plain `grep` would be comparing two different operations
and calling the difference a bug.

## The newline grep adds — the opposite of `head`

A matched last line **without** a trailing newline is printed **with** one.
Measured: a 20-byte file whose only line has no newline produces **21**
bytes.

`head` adds nothing in the same situation. Two commands, two contracts, and
the only way to know which is which is to run them. Both are in this family
and both are tested for it.

## Measured against the system utility

`test/grep_test.cljs` compiles the guest, packages it, **runs the binary**,
and compares bytes and exit status. Fifteen cases, all identical.

Each earns its place: two matching lines where one is a substring match
(`alpha` also finds `alphabet`), a no-match file, an *empty* file (not the
same thing), the added newline, a line matching more than once printed
**once**, a metacharacter, and a multi-byte needle in a multi-byte haystack.

Verified to fail as well as pass — and one control is worth naming. Dropping
the exit status while keeping the output fails three cases **on the status
alone**, with identical bytes on both sides:

```
FAIL ["zzz" "words"] -> "" but grep says "" exits [0 1]
```

A suite that compared only stdout would have called that green.

Not adding the newline fails exactly one case; matching whole lines instead
of substrings fails three.

## What this refuses

An **empty pattern**. `grep -F ''` matches every line; `string-index-of`
refuses an empty needle, and that refusal is a language invariant rather
than a gap. This exits **2** rather than pretending, and says so here.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37). Fuel, the string
arena, the grant and the filesystem scope are constants of the packaged
binary.

## What this is not

One pattern, one file. No `-i`, `-v`, `-c`, `-n`, `-l`, `-r`, no regular
expressions, no reading standard input. `grep -r` over a tree needs the
recursive walk that `kotoba-lang/find` has, and that runs into the string
arena rather than into anything here.

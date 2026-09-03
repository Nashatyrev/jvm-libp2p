# CLAUDE.md — examples:dc

Module-specific guidance for Claude when working in `examples/dc`. This extends the root `CLAUDE.md`.

## Test placement

All test code belongs under `src/test/kotlin`, never under `src/main`. Conversely, reusable
non-test code — network builders, node programs, scenario definitions — belongs under
`src/main/kotlin`, so tests stay in their own source set. Create the folder if it does not exist
yet rather than putting a test next to production code.

## Commit messages

When Claude commits changes in this module (or the repo generally) automatically, the commit
message must start with `[Claude]`, e.g. `[Claude] Add block dissemination scenario`.

## Staging new files

Any new file Claude creates must be `git add`ed (staged) before committing — never leave new files
untracked.

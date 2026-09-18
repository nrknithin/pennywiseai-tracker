# Start here

Written on 2026-09-18 so a fresh session — or a fresh person — can pick this up without the
conversation that produced it. Everything below is on disk; nothing important lives only in a
chat transcript.

## What this is

**Fin**, a private Android money tracker for one person on one phone. It reads bank SMS and Gmail
and answers four questions without typing: what is pending, was every recurring payment made on
time, how much is in each account, and how much can I spend today.

It is a fork of `sarim2000/pennywiseai-tracker` at `c512c7c`, on branch **`fin/main`**.
**Nothing has ever been pushed.** Every commit is local and waiting for review.

## Read these, in this order

| File | What it gives you |
|---|---|
| `docs/BUILD-STATUS.md` | The ledger: milestone states, the toolchain, the gate commands, and a verification log that only ever gets appended to. **The workflow parses this** — keep its structure. |
| `docs/NEEDS-YOU.md` | Everything the build cannot do on its own. Nothing here blocks work; it routes around and leaves a note. |
| `docs/FORK-CHANGES.md` | Every upstream file the fork touches, and every deliberate deviation from the spec, with reasons. |
| `spec/` | The 50-file specification. **Git-ignored on purpose** — it quotes the owner's real mailbox. It is on disk; read it. |
| `CLAUDE.md` | Upstream's own agent guide. Its hard constraints still apply. |

## Build and verify

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export ANDROID_HOME="/c/Users/nrkni/AppData/Local/Android/Sdk"
./init.sh parser     # parser-core tests — the fast loop
./init.sh app        # app compile + unit tests
```

`init.sh` was rewritten because the original printed its checkmark on Gradle's exit code alone,
and `:parser-core:test` is an actionless lifecycle task that is **always** `UP-TO-DATE` — so it
could report green having run nothing. It now deletes test-results, forces `--rerun`, and fails
unless Gradle reports the task line bare. **Trust its output over any agent's summary, including
your own.**

Three flavors now exist, so a compile check is not one command:
`./gradlew :app:compilePersonalDebugKotlin :app:compileFdroidDebugKotlin :app:compileStandardDebugKotlin`

## Where the work stands

M0, M1a, M1b, M1c are done. M1d is code-complete. M2 onwards has not started.
See `docs/BUILD-STATUS.md` for the authoritative table and the current test counts.

**M1's engine is finished and heavily tested, and it has never seen real mail.** That gap closes
with the owner's app password and a device, not with more code.

## Things that will bite you

- **`spec/` is git-ignored** and contains the owner's real financial data. Never copy values out
  of it into code, comments, tests or fixtures. `PiiLintTest` enforces this for `.kt` files —
  but it does **not** scan Markdown, so sweep tracked docs with `git grep` by hand.
- **`.env` holds a live Gmail app password.** Git-ignored. Never print it, never paste it
  anywhere, never let it reach a log or a commit.
- **Line endings are pinned to LF** by `.gitattributes`. The clone was originally made with
  `core.autocrlf=true`, which left 374 files as CRLF while git reported them clean — it silently
  broke two tests and would have corrupted every diff. Do not re-enable autocrlf.
- **Migrations cannot be verified headlessly.** `MigrationTestHelper` needs instrumentation
  assets, so `Migration(63,64)` needs an `app/src/androidTest` suite and a real device. A green
  `./init.sh` says nothing about a migration.
- **Subagents can message other sessions under the parent session's address**, without the parent
  seeing the send. If a peer session quotes a message you did not write, this is why.

## The automation

`.claude/workflows/fin-advance.js` runs one verified step: assess the real build state, pick the
next unit of work, implement it, run the actual gate, record what happened, commit locally. It is
**not resolvable by name** — the registry only lists `deep-research`. Invoke it by path:

```
Workflow({scriptPath: "c:/Users/nrkni/Desktop/finspp/.claude/workflows/fin-advance.js"})
```

Its ground rules are the important part: never push, never invent a number, never put personal
data in the repo, never claim a red build is green, never block on the owner.

One hard-won rule inside it: **every shared file must be named in exactly one task, including
files that do not exist yet.** Two agents once collided on a helper that appeared in no brief —
the design phase invented it after the jobs were scoped, so the disjointness check could not see
it and correctly returned true. A file owned by nobody is owned by whoever writes last, and these
files are untracked, so there is nothing to restore.

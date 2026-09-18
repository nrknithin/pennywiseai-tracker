export const meta = {
  name: 'fin-advance',
  description: 'Assess Fin\'s build state, do the next unit of work, verify it, and record progress',
  whenToUse: 'Recurring driver for the Fin build. Run it to move the project forward one verified step.',
  phases: [
    { title: 'Assess', detail: 'git state, last build result, spec-vs-code gap' },
    { title: 'Decide', detail: 'pick the next unit of work; route owner-blocked items to NEEDS-YOU.md' },
    { title: 'Implement', detail: 'write the code' },
    { title: 'Verify', detail: 'run the real gate, then review adversarially' },
    { title: 'Record', detail: 'update BUILD-STATUS.md and NEEDS-YOU.md, commit locally' },
  ],
}

/*
 * Where this machine keeps things — read from the environment, with fallbacks that work when
 * nothing is set.
 *
 * None of these may be hardcoded. An absolute path under the owner's Windows profile publishes
 * the owner's account name, and this repository is AGPL and ships beside every release APK
 * (spec 04 §4.5; redaction convention spec 06 §6.2). `PiiLintTest` cannot catch it: it scans
 * only `.kt` files under its fork-owned paths, and this is a `.js` file outside them. So the
 * defaults below are derived from the *current* user's environment rather than written down.
 *
 * Set `FIN_ROOT` / `FIN_JDK` / `FIN_SDK` to override any of them.
 */
const env = (typeof process !== 'undefined' && process.env) ? process.env : {}
const cwd = () => (typeof process !== 'undefined' && process.cwd) ? process.cwd() : '.'

/** `C:\Users\x` → `c:/Users/x`. Forward slashes and a lower-case drive, as every tool here takes it. */
const winPath = (p) => String(p).replace(/\\/g, '/').replace(/^([A-Za-z]):/, (_m, d) => d.toLowerCase() + ':')

/** `c:/Users/x` → `/c/Users/x`, the MSYS spelling git-bash wants inside an `export`. */
const shPath = (p) => winPath(p).replace(/^([a-z]):\//, '/$1/')

// The workflow is launched from the repo root, so the working directory is the fallback. Kept in
// drive-letter form because agents pass it to file tools as well as to `cd`.
const ROOT = winPath(env.FIN_ROOT || cwd())

// JAVA_HOME is deliberately NOT consulted: `init.sh` requires JDK 21, and on this kind of machine
// JAVA_HOME is as likely to hold Android Studio's bundled JBR 25, which it refuses. The fallback
// is a product install path, so it names no user.
const JDK = shPath(env.FIN_JDK || '/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot')

// The SDK does live under the user's profile, so the last resort builds the platform default from
// the environment's own idea of where that is rather than from a name written here.
const SDK = shPath(
  env.FIN_SDK ||
  env.ANDROID_HOME ||
  env.ANDROID_SDK_ROOT ||
  (env.LOCALAPPDATA ? env.LOCALAPPDATA + '/Android/Sdk' : '$HOME/Android/Sdk')
)

/** Every agent gets this. It is the contract for the whole automation. */
const GROUND_RULES = `
# Fin — ground rules for every agent in this workflow

## The project
Fin is a private Android money tracker (Kotlin, Compose, Room, Hilt, WorkManager), forked from
the open-source FinTrack AI tracker at commit c512c7c. It reads bank SMS and Gmail, and turns
them into transactions, dues, subscriptions, loans and receipts.

Repo root: ${ROOT}   Branch: fin/main

## Where the truth lives, in this order
1. ${ROOT}/spec/           the 50-file specification. Git-ignored on purpose (it quotes the
                           owner's real mailbox). It is on disk — read it.
2. ${ROOT}/docs/BUILD-STATUS.md   what is done, what is next. Machine-readable; keep its shape.
3. ${ROOT}/docs/NEEDS-YOU.md      things only the owner can do. Never block on these.
4. ${ROOT}/docs/FORK-CHANGES.md   every upstream file the fork touches, and every deliberate
                           deviation from the spec, with reasons. Keep it current.
5. ${ROOT}/CLAUDE.md       upstream's own agent guide. Its hard constraints still apply.

## Building and testing — this machine, these paths
    export JAVA_HOME="${JDK}"
    export ANDROID_HOME="${SDK}"
    cd ${ROOT}
    ./init.sh parser      # parser-core tests — the fast loop, use this for parser work
    ./init.sh app         # app compile + unit tests
    ./gradlew :parser-core:test --console=plain    # what init.sh parser wraps

JDK 21 is required and init.sh refuses anything else. Android Studio's bundled JBR is 25 — do
not point JAVA_HOME at it.

## Rules that are not negotiable
1. **Never \`git push\`.** Never open a PR. Local commits only. The owner reviews and pushes.
2. **Never invent a number.** If a value is unknown the code returns null and the UI shows "—".
   This is spec constraint C7 and it is the point of the app.
3. **No personal data in the repo.** No real account/card digits, names, VPAs, phone numbers,
   amounts or merchants in code, comments, tests or fixtures. The spec quotes the owner's real
   mailbox; redact everything you copy out of it, per spec 06 §6.2 — last-4 → 1234, VPAs →
   someone@upi, names → SOMEONE, refs → 123456789012, amounts changed. \`PiiLintTest\` enforces
   this and must stay green.
4. **Never edit anything under \`spec/\`.** It is the owner's input, not your output.
5. **Never touch credentials.** No keystore, no google-services.json, no app password, no
   Firebase values. If a task needs one, it is an owner task — see rule 6.
6. **Owner-blocked work is skipped, never blocked on.** If a unit of work genuinely cannot
   proceed without the owner (a credential, an account, a decision), append a clear entry to
   docs/NEEDS-YOU.md in that file's existing format, and pick the next unblocked task instead.
   Build everything that can be built around the gap.
7. **Verify before claiming done.** Run the gate. A change that has not compiled is not done,
   and saying it is done is worse than leaving it unfinished.
8. **Report failures honestly.** If tests fail, say so and quote the output. Never describe a
   red build as green.

## How to write code here
Match the surrounding code: 4-space indent, KDoc on classes and non-obvious members, comments
that explain *why* rather than restating *what*. Parsing logic lives in \`:parser-core\` and never
in \`:app\`. Business rules live in pure-Kotlin \`domain/engine\` classes with injected clocks so
they are testable. Every new behaviour gets a test in the same commit.

When the spec is self-contradictory — and it is, in about twenty places — implement the reading
that loses no data, comment the choice at the site, and add a row to docs/FORK-CHANGES.md.
`

const ASSESS_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    headCommit: { type: 'string' },
    workingTreeClean: { type: 'boolean' },
    buildState: { type: 'string', description: 'green | red | unknown | not-run' },
    buildEvidence: { type: 'string', description: 'The actual command run and the tail of its output. Quote it.' },
    failures: { type: 'array', description: 'Concrete failing tests or compile errors, file:line where known', items: { type: 'string' } },
    milestoneStates: { type: 'array', items: { type: 'string' }, description: 'One line per milestone from BUILD-STATUS.md with its current state' },
    gapsFound: { type: 'array', description: 'Specified behaviour with no implementation yet, most important first', items: { type: 'string' } },
    ownerBlockers: { type: 'array', description: 'Anything encountered that genuinely needs the owner', items: { type: 'string' } },
  },
  required: ['buildState', 'buildEvidence', 'failures', 'gapsFound'],
}

phase('Assess')

const assessments = await parallel([
  {
    key: 'build',
    ask: `Establish the BUILD STATE, by running it — not by reading notes about it.

Run the parser gate and, if that passes, the app gate:
    export JAVA_HOME="${JDK}"; export ANDROID_HOME="${SDK}"; cd ${ROOT}
    ./init.sh parser
    ./init.sh app

Gradle's first run on a machine downloads a lot and can take many minutes; let it finish rather
than killing it. If a run fails, capture the ACTUAL errors — failing test names, compile errors
with file and line — not a summary. Also report git branch, HEAD, and whether the tree is clean.

If a build is already running in another process and the Gradle lock is held, say so and report
buildState "unknown" rather than fighting it.`,
  },
  {
    key: 'gap',
    ask: `Establish the SPEC-VS-CODE GAP for the milestone BUILD-STATUS.md says is in progress.

Read docs/BUILD-STATUS.md to see where the build is, then read the spec files for that milestone
and compare against what exists under ${ROOT}/parser-core/src and ${ROOT}/app/src.

Report what is specified but absent, ordered by what blocks the most downstream work. Be
concrete: name the class or table that is missing and the spec section that requires it. Do not
list things that are deliberately deferred — BUILD-STATUS.md and FORK-CHANGES.md say which
those are.`,
  },
  {
    key: 'hygiene',
    ask: `Check the REPO HYGIENE invariants, and report any that are broken.

  - No personal data in fork-owned paths. Run the PII sweep: grep the fork's own files under
    parser-core/src/{main,test}/kotlin/com/nrknithin/fintrack/parser/core/{email,text} and
    app/src/main/java/com/nrknithin/fintrack/data/email for real-looking account digits,
    names, UPI handles, phone numbers. Read docs/FORK-CHANGES.md for what redaction convention
    is in force. Upstream's own files are deliberately out of scope.
  - spec/ is git-ignored and untracked (git ls-files spec must return nothing).
  - No credentials tracked: local.properties, *.jks, keystore.properties, google-services.json.
  - docs/FORK-CHANGES.md lists every upstream file currently modified. Compare it against
    \`git diff --name-only c512c7c HEAD\` and report drift.
  - Nothing has been pushed: \`git log origin/fin/main..fin/main\` should fail or list commits,
    and there should be no remote named origin for the fork.`,
  },
].map(a => () => agent(GROUND_RULES + '\n\n# Your job this run\n' + a.ask, {
  label: 'assess:' + a.key,
  phase: 'Assess',
  schema: ASSESS_SCHEMA,
})))

const assessed = assessments.filter(Boolean)
const buildState = assessed.find(a => a.buildState && a.buildState !== 'unknown')?.buildState || 'unknown'
log('assessed — build is ' + buildState)

phase('Decide')

const PLAN_SCHEMA = {
  type: 'object',
  properties: {
    situation: { type: 'string', description: 'Two or three sentences: where the build actually is.' },
    tasks: {
      type: 'array',
      description: 'The units of work to do THIS run, ordered. Between one and four. Each must be finishable and verifiable in one sitting.',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          title: { type: 'string' },
          why: { type: 'string', description: 'What it unblocks' },
          files: { type: 'string', description: 'Files it will create or edit — used to check two tasks do not collide' },
          spec: { type: 'string', description: 'Spec sections that govern it' },
          gate: { type: 'string', description: 'The command that proves it works' },
          parallelSafe: { type: 'boolean', description: 'True only if its files are disjoint from every other task this run' },
        },
        required: ['id', 'title', 'why', 'files', 'gate', 'parallelSafe'],
      },
    },
    ownerItems: {
      type: 'array',
      description: 'Work deferred because it needs the owner. Each becomes a NEEDS-YOU.md entry.',
      items: {
        type: 'object',
        properties: {
          item: { type: 'string' },
          whyBlocked: { type: 'string' },
          meanwhile: { type: 'string', description: 'What gets built around the gap' },
        },
        required: ['item', 'whyBlocked', 'meanwhile'],
      },
    },
  },
  required: ['situation', 'tasks', 'ownerItems'],
}

const plan = await agent(
  GROUND_RULES + `

# Your job this run

You are the tech lead. Decide what gets done THIS run, then stop — you are not implementing it.

Here is what the assessment agents found:
${JSON.stringify(assessed).slice(0, 300000)}

Rules for choosing:
  - **A red build outranks everything.** If the gate is failing, this run's only tasks are
    fixing it. Do not add features on top of a red build.
  - Otherwise follow BUILD-STATUS.md's milestone order. Finish the in-progress milestone before
    starting the next; spec 07's sequencing rule is that a milestone's screens come only after
    its engine tests are green.
  - Pick work that can actually be finished and VERIFIED this run. A task whose gate cannot run
    is not a task — it is a wish.
  - Anything needing a credential, an account or an owner decision goes in ownerItems, not
    tasks. Never make the build wait on the owner.
  - Two tasks may only be parallelSafe together if they touch genuinely disjoint files. When in
    doubt, mark false — a merge conflict costs more than the parallelism saves.
  - **Name every shared file in exactly one task's \`files\`, including ones that do not exist
    yet.** Disjoint briefs are not enough. On 2026-09-18 a run scoped three genuinely disjoint
    jobs — IMAP client, discovery engine, storage — and two of its agents still collided, because
    both needed a helper that appeared in no brief: it was invented between scoping and
    execution, so the disjointness test could not see it and correctly returned true. If a task
    needs a type another task will also need, either give it to one task outright or make it a
    task of its own that runs BEFORE the parallel group. A file owned by nobody is owned by
    whoever writes last.
  - Between one and four tasks. Prefer fewer and finished over more and half-done.`,
  { label: 'decide:next', phase: 'Decide', schema: PLAN_SCHEMA, effort: 'high' }
)

if (!plan || !plan.tasks || plan.tasks.length === 0) {
  log('nothing to do this run')
  return { situation: plan ? plan.situation : 'planner returned nothing', tasks: [], done: [] }
}

log(plan.tasks.length + ' task(s): ' + plan.tasks.map(t => t.title).join(' | '))

phase('Implement')

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    taskId: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    whatChanged: { type: 'string' },
    gateRun: { type: 'string', description: 'The exact command you ran' },
    gateResult: { type: 'string', description: 'pass | fail | could-not-run' },
    gateOutput: { type: 'string', description: 'The tail of the real output. Quote it, do not paraphrase.' },
    deviations: { type: 'array', description: 'Places you departed from the spec, and why', items: { type: 'string' } },
    ownerItems: { type: 'array', description: 'Anything you hit that needs the owner', items: { type: 'string' } },
    unfinished: { type: 'string', description: 'What you did NOT finish, stated plainly. Empty if complete.' },
  },
  required: ['taskId', 'filesChanged', 'whatChanged', 'gateResult'],
}

const implPrompt = (task) => GROUND_RULES + `

# Your task this run

**${task.title}**

Why it matters: ${task.why}
Files it should touch: ${task.files}
Governing spec: ${task.spec || 'see BUILD-STATUS.md for the current milestone'}
Your gate: \`${task.gate}\`

${task.parallelSafe ? `**Do NOT run Gradle.** You are running alongside other agents in this same working tree, and
Gradle keeps one incremental state per build directory: two builds at once corrupt it, producing
impossible results like a missing FinTrackDatabase_Impl.class while the compile task reports
UP-TO-DATE. Report gateResult "could-not-run" — the Verify phase runs the gate once, serially,
and its result is the authoritative one. A wrong green here is worse than no green.` :
`Do the work, then RUN THE GATE and report what it actually printed. You are running serially, so
the build directory is yours. A task whose gate you did not run is reported as gateResult
"could-not-run" — an acceptable answer, and far better than claiming a pass you did not see.`}

Write the tests in the same pass as the code, not after. If you find the spec contradicts itself
here, implement the reading that loses no data, comment why at the site, and add a row to
docs/FORK-CHANGES.md.

Stay inside the files listed above. Another agent may be working elsewhere in this repo at the
same time; do not reformat, refactor or "tidy" anything outside your task.

**If you need a file that is not in your list, do not create it.** Say so in \`unfinished\` and
work around it, or stop short. A sibling agent may be creating the same helper right now under a
different name, and whichever of you writes last silently destroys the other's work — these files
are usually untracked, so there is no version to restore. This is not hypothetical: it happened
on 2026-09-18, and the agent that overwrote the file believed it was resolving a conflict with a
different *session*, because a sibling's output is indistinguishable from a stranger's.`

const parallelSafe = plan.tasks.filter(t => t.parallelSafe)
const serial = plan.tasks.filter(t => !t.parallelSafe)

const done = []

if (parallelSafe.length > 0) {
  const results = await parallel(parallelSafe.map(t => () =>
    agent(implPrompt(t), { label: 'build:' + t.id, phase: 'Implement', schema: IMPL_SCHEMA })
  ))
  done.push(...results.filter(Boolean))
}

// Tasks that share files run one after another, so the second sees the first's work.
for (const t of serial) {
  const r = await agent(implPrompt(t), { label: 'build:' + t.id, phase: 'Implement', schema: IMPL_SCHEMA })
  if (r) done.push(r)
}

phase('Verify')

const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    gateCommand: { type: 'string' },
    gateResult: { type: 'string', description: 'green | red | could-not-run' },
    gateOutput: { type: 'string' },
    failures: { type: 'array', items: { type: 'string' } },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          severity: { type: 'string', description: 'blocker | bug | risk | nit' },
          summary: { type: 'string' },
          evidence: { type: 'string' },
        },
        required: ['file', 'severity', 'summary'],
      },
    },
    piiClean: { type: 'boolean' },
    verdict: { type: 'string', description: 'ship | fix-first' },
  },
  required: ['gateResult', 'findings', 'verdict'],
}

const verification = await parallel([
  {
    key: 'gate',
    ask: `Run the FULL gate from a clean state and report exactly what it prints.

    export JAVA_HOME="${JDK}"; export ANDROID_HOME="${SDK}"; cd ${ROOT}
    ./init.sh parser
    ./init.sh app

Report green only if both actually passed. Quote real output. If something fails, name the
failing tests and the assertion messages — that is what the next run needs to fix them.`,
  },
  {
    key: 'review',
    ask: `Adversarially review what was just written this run.

Work out what changed with \`git status --short\` and \`git diff\`, read those files, and hunt for:
correctness bugs, a regex that does not match the sample it claims to, a code path that invents
a number the message never stated (spec C7), a missing test for a behaviour that was added, and
any personal data that reached the repo. Report only what you verified by reading. Do not fix
anything — report it.`,
  },
].map(v => () => agent(GROUND_RULES + '\n\n# Your job this run\n' + v.ask, {
  label: 'verify:' + v.key,
  phase: 'Verify',
  schema: VERIFY_SCHEMA,
  effort: 'high',
})))

const verified = verification.filter(Boolean)
const gate = verified.find(v => v.gateResult && v.gateResult !== 'could-not-run')
const green = gate ? gate.gateResult === 'green' : false

phase('Record')

const RECORD_SCHEMA = {
  type: 'object',
  properties: {
    statusUpdated: { type: 'boolean' },
    needsYouAppended: { type: 'array', items: { type: 'string' } },
    committed: { type: 'boolean' },
    commitSubject: { type: 'string' },
    summaryForOwner: { type: 'string', description: 'Plain prose, 3-6 sentences: what moved, what is red, what is waiting on them.' },
    nextAction: { type: 'string', description: 'The single next thing the following run should do.' },
  },
  required: ['statusUpdated', 'committed', 'summaryForOwner', 'nextAction'],
}

const record = await agent(
  GROUND_RULES + `

# Your job this run

Record what happened. You are the only agent that writes to the status files and to git.

What was implemented:
${JSON.stringify(done).slice(0, 200000)}

What verification found:
${JSON.stringify(verified).slice(0, 150000)}

Owner-blocked items the planner identified:
${JSON.stringify(plan.ownerItems).slice(0, 50000)}

Do these, in order:

1. **docs/BUILD-STATUS.md** — update milestone states, the "Done" list, "Next action", and append
   one row to the verification log with today's date, the command, and the real result. Keep the
   file's existing structure exactly; the next run parses it.

2. **docs/NEEDS-YOU.md** — append any new owner items in that file's format (\`- [ ]\`, why it
   matters, what happens meanwhile, where it goes). Never delete or tick an existing entry; the
   owner ticks those. Add a row to its log table.

3. **docs/FORK-CHANGES.md** — if this run touched a new upstream file or added a deviation,
   record it there.

4. **Commit, locally.** Stage everything and make ONE commit with a real message: a subject line
   under 72 characters saying what changed, then a body explaining why and stating plainly
   whether the gate passed. If the gate is red, say so in the commit message — do not hide it.
   ${green ? 'The gate is GREEN this run.' : 'The gate is NOT green this run — say so explicitly in the message.'}
   End the message with:
   Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>

   **Do not push.** Do not add a remote. Do not open a PR.

5. Write \`summaryForOwner\` as plain prose for someone who has not been watching — what moved,
   what is broken, what is waiting on them. No jargon, no bullet lists, no false cheer.`,
  { label: 'record', phase: 'Record', schema: RECORD_SCHEMA }
)

return {
  situation: plan.situation,
  tasksAttempted: plan.tasks.map(t => t.title),
  gateGreen: green,
  implemented: done,
  verification: verified,
  record,
}

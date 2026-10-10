# Debugging unexpected rebuilds: sstate and task signatures, hands-on

A throwaway layer for learning why BitBake reruns a task instead of reusing
shared state (sstate). Every command and output below was run on the
`hockey-qemuarm64-poky` build (wrynose, BitBake 2.18, `OEEquivHash`); hashes
are shortened to `…`.

```
meta-sstate-demo/
├── conf/layer.conf
├── conf/other-machine.conf        # used in experiment 8
└── recipes-demo/
    ├── sstate-demo/               # tiny C program, target arch
    │   ├── sstate-demo_1.0.bb
    │   └── files/hello.c
    └── sstate-demo-data/          # allarch data recipe
        └── sstate-demo-data_1.0.bb
```

---

## Part 1: How the caching works

### Two caches, two questions

BitBake skips work using two separate mechanisms:

| Mechanism | Lives in | Question it answers | Scope |
|---|---|---|---|
| **Stamps** | `tmp/stamps/<arch>/<recipe>/` | "Did *this build dir* already run this exact task?" | One TMPDIR |
| **Shared state (sstate)** | `SSTATE_DIR` (here `/workspace/caches/sstate-cache`) | "Has *anyone* already produced the output of this exact task?" | Any build that can read the dir or an `SSTATE_MIRRORS` |

Both are keyed by the **task signature** (a hash). A stamp file is just
`<PV>.do_compile.<hash>`. If the hash for the task in the current
configuration matches the stamp, the task is "Current" and nothing happens.

Only some tasks produce sstate. These are the `SSTATETASKS`: `do_populate_sysroot`,
`do_package`, `do_packagedata`, `do_package_write_rpm`, `do_package_qa`,
`do_populate_lic`, `do_deploy`, `do_create_spdx`, and others. Each one has a
`<task>_setscene` twin that **restores** the output from an archive instead of
producing it. `do_fetch`, `do_unpack`, `do_configure`, `do_compile` and
`do_install` have no sstate. They are skipped only when their stamp is current, or
when nothing downstream needs them because the later sstate tasks were restored.

### How a signature is computed

For every task, BitBake computes:

```
basehash = hash( task's own code
               + every variable/function it references, transitively
                 (the UNEXPANDED value of each, e.g. "${DATE}${TIME}")
               + relevant varflags )
           minus anything in BB_BASEHASH_IGNORE_VARS
           (you'll see it as "basehash_ignore_vars" in bitbake-dumpsig)

taskhash = hash( basehash
               + hashes of the tasks it depends on
               + checksums of local files (SRC_URI file://, [file-checksums])
               + taint, if any (forced run, nostamp) )
```

Variable references are found automatically by parsing shell and Python
code. You adjust them with varflags:

- `TASK_OR_VAR[vardeps] += "X"` adds a dependency the parser can't see,
  for example a variable used through `d.getVar(name_built_at_runtime)`.
- `TASK_OR_VAR[vardepsexclude] += "X"` drops a dependency. **This only
  removes direct references from *that* variable or task** (see experiment 6).
- `VAR[vardepvalue] = "..."` hashes this string instead of VAR's real value.

Because a task's hash includes its dependencies' hashes, one change spreads
to **everything downstream**. In `bitbake-diffsigs` output this shows up as
"Hash for task dependency X changed", and the tool follows it back to the root cause.

### Unihash and hash equivalence

This build uses `BB_SIGNATURE_HANDLER = "OEEquivHash"` with a local hash
server (`BB_HASHSERVE = unix://…/hashserve.sock`). That adds a third hash:

- When an sstate task finishes, OE computes an **outhash** over its *output*.
- The hash server stores `taskhash → unihash`. If a new taskhash produces an
  outhash it has already seen, it gets the **existing unihash**.
- Dependent tasks, stamp files, sigdata filenames and sstate filenames all
  use the **unihash**.

Result: if a change alters the inputs but produces bit-identical output (a
comment, a reordered variable, a build-tool change with no effect), BitBake
notices mid-build and switches everything downstream to restore from sstate.
Experiment 5 shows this happening.

### The setscene phase

When you run `bitbake <target>`:

1. Parse the recipes (results are cached in `tmp/cache` and reused until a
   recipe or config change invalidates the cache).
2. Compute every task's hash and ask the hash server for unihashes.
3. Look up which sstate objects exist (`SSTATE_DIR`, then `SSTATE_MIRRORS`)
   and print the **Sstate summary**.
4. Run `_setscene` tasks from the top down, restoring only what the target
   actually needs.
5. Run the real tasks for whatever wasn't covered.

Reading the summary line:

```
Sstate summary: Wanted 69 Local 58 Mirrors 0 Missed 11 Current 264 (84% match, 96% complete)
```

| Field | Meaning |
|---|---|
| Wanted | sstate tasks whose stamps aren't current, so their output is needed |
| Local / Mirrors | of those, found in `SSTATE_DIR` / in `SSTATE_MIRRORS` |
| Missed | not found anywhere, so they must be built (**your rebuild list**) |
| Current | stamps already match, nothing to do |
| match % | (Local+Mirrors)/Wanted |
| complete % | (Local+Mirrors+Current)/(Wanted+Current) |

The summary is a **prediction** made before hash equivalence has a chance to
act. The actual build can do less work than "Missed" suggests (experiment 5).

### Where signature data lives

| File | Where | Written when |
|---|---|---|
| `<PV>.do_X.sigdata.<unihash>` | `tmp/stamps/<arch>/<recipe>/` | a task runs, **and** on every `bitbake -S none` / `-S printdiff` |
| `sstate:…:<unihash>_<task>.tar.zst.siginfo` | `SSTATE_DIR/<h0h1>/<h2h3>/` | an sstate object is created |

The filenames use the **unihash**. Inside the file, `Computed task hash`
shows the real taskhash, and the two can differ (experiment 4).

The sstate object name has this form:
`sstate:PN:PACKAGE_ARCH-TARGET_OS:PV:PR:SSTATE_PKGARCH:SSTATE_VERSION:<unihash>_<task>.tar.zst`.
For example:

```
/workspace/caches/sstate-cache/46/a2/sstate:busybox:cortexa57-poky-linux:1.37.0:r0:cortexa57:14:46a2ee6f…_populate_sysroot.tar.zst
```

---

## Part 2: The toolkit

```sh
bitbake -S printdiff <target>            # BEFORE building: why will tasks miss sstate?
bitbake -S none <target>                 # write sigdata for all tasks without building
bitbake-diffsigs -t <recipe> <task>      # AFTER: compare the last two sigdata of a task
bitbake-diffsigs -t <recipe> <task> -s <hash1> <hash2>   # ...two specific ones
bitbake-diffsigs a.sigdata b.sigdata     # compare any two files (e.g. from two hosts)
bitbake-dumpsig <file>                   # everything that went into one signature
bitbake-getvar -r <recipe> VAR           # value + history: where was VAR set?
```

`bitbake-dumpsig` prints thousands of lines. These greps are the useful ones:

```sh
bitbake-dumpsig F | grep -E '^(Tasks this task|This task depends on|Hash for|Computed|Taint)'
bitbake-dumpsig F | grep 'List of dependencies for variable'   # who pulls in what
```

---

## Part 3: The lab

### Setup

Run everything from the build directory, in a BitBake terminal or after
`. ./init-build-env`:

```sh
cd /workspace/yocto-hockey-poky/bitbake-builds/hockey-qemuarm64-poky/build
. ./init-build-env

bitbake-layers add-layer ../meta-sstate-demo

# Helper: print only the sstate summary, errors, and the demo's task lines.
bbt() { bitbake "$@" 2>&1 | grep -E 'Sstate summary|^ERROR|recipe sstate-demo.*(Succeeded|Failed)' \
        | sed 's/^Initialising tasks...//; s/.*recipe //; s/: Succeeded//'; }
```

> **Start cold.** This lab was already run once on this machine, so the
> shared cache has `sstate-demo` objects in it. To see a real cold build
> in experiment 1, remove *only this recipe's* objects first:
> `bitbake -c cleansstate sstate-demo sstate-demo-data`
> (Skip this and experiment 1 will just restore from sstate.)

### Experiment 1: cold build

```sh
bbt sstate-demo
```
```
Sstate summary: Wanted 69 Local 58 Mirrors 0 Missed 11 Current 264 (84% match, 96% complete)
sstate-demo-1.0-r0: task do_fetch
sstate-demo-1.0-r0: task do_unpack
…
sstate-demo-1.0-r0: task do_compile
sstate-demo-1.0-r0: task do_install
sstate-demo-1.0-r0: task do_populate_sysroot
sstate-demo-1.0-r0: task do_package
sstate-demo-1.0-r0: task do_packagedata
sstate-demo-1.0-r0: task do_package_qa
sstate-demo-1.0-r0: task do_package_write_rpm
```

The 11 misses are this recipe's own sstate tasks. The 58 local hits are
dependencies, such as the toolchain sysroot, that had to be staged.

```sh
ls tmp/stamps/cortexa57-poky-linux/sstate-demo/     # stamp + sigdata per task
ls /workspace/caches/sstate-cache/*/*/sstate:sstate-demo:*   # the sstate objects
```

### Experiment 2: no-op rebuild

```sh
bbt sstate-demo
```
```
Sstate summary: Wanted 58 Local 58 Mirrors 0 Missed 0 Current 275 (100% match, 100% complete)
```

Every stamp matches, so no tasks run.

### Experiment 3: delete the work, keep the cache

```sh
bitbake -c clean sstate-demo      # deletes the WORKDIR and stamps, NOT sstate
ls tmp/stamps/cortexa57-poky-linux/sstate-demo | wc -l   # 0
bbt sstate-demo
```
```
Sstate summary: Wanted 69 Local 69 Mirrors 0 Missed 0 Current 264 (100% match, 100% complete)
sstate-demo-1.0-r0: task do_package_qa_setscene
sstate-demo-1.0-r0: task do_populate_lic_setscene
sstate-demo-1.0-r0: task do_package_write_rpm_setscene
sstate-demo-1.0-r0: task do_create_package_spdx_setscene
sstate-demo-1.0-r0: task do_create_spdx_setscene
sstate-demo-1.0-r0: task do_populate_sysroot_setscene
sstate-demo-1.0-r0: task do_packagedata_setscene
sstate-demo-1.0-r0: task do_recipe_qa_setscene
```

Look at what didn't happen. There is no `do_compile`, and
`ls tmp/work/cortexa57-poky-linux/sstate-demo/1.0/` has no `sources/`
directory, because the source was never fetched or unpacked.
`do_package_setscene` didn't run either: once `do_packagedata` and
`do_package_write_rpm` are restored, nothing needs `do_package`'s output.

Compare:

- `-c clean` removes WORKDIR and stamps.
- `-c cleansstate` also removes this recipe's sstate objects.
- `-c cleanall` also removes downloads.

The last two touch the shared caches.

### Experiment 4: a local.conf change you didn't expect to matter

```sh
echo 'DEMO_GREETING = "Hello from local.conf"' >> conf/local.conf
bitbake -S printdiff sstate-demo
```
```
The differences between the current build and any cached tasks start at the following tasks:
…/sstate-demo_1.0.bb:do_compile

Task sstate-demo:do_compile couldn't be used from the cache because:
  We need hash c75a02ff…, most recent matching task was 97789df0…
  basehash changed from 237eca4d… to f52dff93…
  Variable DEMO_GREETING value changed:
"Hello from [-sstate-demo-] {+local.conf+}"
```

`printdiff` found the root task and the root cause without building
anything. Now build and look at it after the fact:

```sh
bbt sstate-demo
bitbake-diffsigs -t sstate-demo do_compile
bitbake-diffsigs -t sstate-demo do_install
```
```
# do_compile: the root cause
basehash changed from 237eca4d… to f52dff93…
Variable DEMO_GREETING value changed:
"Hello from [-sstate-demo-] {+local.conf+}"

# do_install: one hop downstream, diffsigs follows the chain back
Hash for task dependency sstate-demo:do_compile changed from 97789df0… to c75a02ff…
    basehash changed from 237eca4d… to f52dff93…
    Variable DEMO_GREETING value changed:
    "Hello from [-sstate-demo-] {+local.conf+}"
```

Two things to notice:

- `do_fetch`, `do_unpack`, `do_patch` and `do_configure` also ran, even
  though their hashes didn't change. Experiment 3 deleted the WORKDIR, and
  those tasks have no sstate, so they had to run again to give `do_compile`
  its input.
- **Hash equivalence already happened here.** Run
  `bitbake-diffsigs -t sstate-demo do_package_write_rpm`. It reports only
  `do_packagedata changed from 03b336… to a0bcd…` and stops there. Dump the
  sigdata:

  ```sh
  bitbake-dumpsig tmp/stamps/cortexa57-poky-linux/sstate-demo/1.0.do_packagedata.sigdata.a0bcd* | grep Computed
  # Computed task hash is 03b336…
  ```

  The file is named after unihash `a0bcd…`, which was the *old* build's
  hash, but it holds the *new* taskhash `03b336…`. The new greeting changed
  the binary but not the pkgdata (file list, sizes and dependencies), so the
  hash server mapped the new task to the old unihash.

  Also, the "last two" sigdata files that `-t` compared were the
  `printdiff` run's file and the build's file, both written *after* the
  config change. If `-t` compares the wrong pair, choose the pair with
  `-s <hash1> <hash2>`.

Now revert:

```sh
sed -i '/^DEMO_GREETING = /d' conf/local.conf
bbt sstate-demo
```
```
Sstate summary: Wanted 65 Local 65 Mirrors 0 Missed 0 Current 268 (100% match, 100% complete)
sstate-demo-1.0-r0: task do_populate_sysroot_setscene
sstate-demo-1.0-r0: task do_packagedata_setscene
…
```

Nothing is rebuilt. sstate is **content-addressed**, not "the last build",
so both variants now sit in the cache side by side. Switching between
configurations you've already built is cheap.

### Experiment 5: hash equivalence on purpose

Add a comment inside `do_compile`. A comment changes the function text, so it
changes the hash, but it can't change the output:

```sh
sed -i 's|^do_compile() {|do_compile() {\n    # a harmless comment|' \
    ../meta-sstate-demo/recipes-demo/sstate-demo/sstate-demo_1.0.bb
bbt sstate-demo
```
```
Sstate summary: Wanted 65 Local 58 Mirrors 0 Missed 7 Current 268 (89% match, 97% complete)
sstate-demo-1.0-r0: task do_compile
sstate-demo-1.0-r0: task do_install
sstate-demo-1.0-r0: task do_populate_sysroot
sstate-demo-1.0-r0: task do_package
sstate-demo-1.0-r0: task do_package_qa_setscene          <-- switched to restore
sstate-demo-1.0-r0: task do_package_write_rpm_setscene
sstate-demo-1.0-r0: task do_create_package_spdx_setscene
sstate-demo-1.0-r0: task do_create_spdx_setscene
sstate-demo-1.0-r0: task do_packagedata_setscene
```

The summary predicted 7 misses. `do_package` produced the same outhash as
before, got the old unihash, and everything downstream was restored instead
of built. In a large build this saves a lot of work, for example when a
`-native` tool changes without changing its output.

Undo the comment before continuing:
`sed -i '/# a harmless comment/d' ../meta-sstate-demo/recipes-demo/sstate-demo/sstate-demo_1.0.bb`

### Experiment 6: the timestamp trap (and where `vardepsexclude` must go)

Embed the build time:

```sh
R=../meta-sstate-demo/recipes-demo/sstate-demo/sstate-demo_1.0.bb
echo 'DEMO_EXTRA_CFLAGS = "-DBUILD_TIME=${DATETIME}"' >> $R
bbt sstate-demo
```
```
ERROR: When reparsing …/sstate-demo_1.0.bb:do_compile, the basehash value changed from 639de79d… to …
       The metadata is not deterministic and this needs to be fixed.
ERROR: sstate-demo-1.0-r0 do_compile: Taskhash mismatch 5f6b9198… versus acb57219… for …:do_compile
```

The server parsed the recipe at one second, and the worker re-parsed it at
another before running the task. `DATETIME` differed, so the hashes
disagreed.

Run it again:

```sh
bbt sstate-demo
```
```
Sstate summary: Wanted 58 Local 58 Mirrors 0 Missed 0 Current 275 (100% match, 100% complete)
```

**No errors and no rebuild.** `DATE` and `TIME` are in
`BB_HASHCONFIG_IGNORE_VARS`, so they don't invalidate the parse cache, and
the cached parse keeps the old timestamp. The problem only comes back when
something forces a re-parse. That's why this bug seems to come and go:

```sh
touch $R; bbt sstate-demo                       # errors + rebuild again
bitbake-diffsigs -t sstate-demo do_compile
```
```
basehash changed from f27b546b… to 27f2ad87…
Variable TIME value changed from '162548' to '162615'
```

`diffsigs` blames **`TIME`**, not your variable. To trace who pulls it in:

```sh
bitbake-dumpsig $(ls -t tmp/stamps/cortexa57-poky-linux/sstate-demo/1.0.do_compile.sigdata.* | head -1) \
  | grep -E 'List of dependencies for variable (DEMO_EXTRA_CFLAGS|DATETIME|TIME)'
```
```
List of dependencies for variable DATETIME is ['DATE', 'TIME']
List of dependencies for variable DEMO_EXTRA_CFLAGS is ['DATETIME']
List of dependencies for variable TIME is []
```

The chain is `do_compile → DEMO_EXTRA_CFLAGS → DATETIME → TIME`.

**Fix A, the commonly quoted one, doesn't work here:**

```sh
echo 'do_compile[vardepsexclude] += "DATETIME"' >> $R
bbt sstate-demo        # still: "metadata is not deterministic"
```

`vardepsexclude` removes only *direct* references made by the task or
variable it's set on. `do_compile` doesn't mention `DATETIME`;
`DEMO_EXTRA_CFLAGS` does.

**Fix B: exclude it where it is referenced.**

```sh
sed -i 's/^do_compile\[vardepsexclude\] += "DATETIME"$/DEMO_EXTRA_CFLAGS[vardepsexclude] += "DATETIME"/' $R
bbt sstate-demo                 # builds once, cleanly
touch $R; bbt sstate-demo       # re-parse: nothing reruns, so it's deterministic now
```

**This "fix" has a cost.** The hash no longer reflects the timestamp, so
sstate will happily give back the binary with *whatever time it was first
built*:

```sh
strings tmp/work/cortexa57-poky-linux/sstate-demo/1.0/image/usr/bin/sstate-demo | grep '^20'
```

That's correct sstate behaviour, but it's a lie if you expected "time of
this build". The real fixes:

- Don't embed build times.
- Use `SOURCE_DATE_EPOCH` for reproducible timestamps.
- If the value truly must be fresh, accept that the task can't be cached.

Clean up:
`sed -i '/^DEMO_EXTRA_CFLAGS = "-DBUILD_TIME/d; /^DEMO_EXTRA_CFLAGS\[vardepsexclude\]/d' $R`

### Experiment 7: a forced task leaves a taint that persists

```sh
bitbake -f -c compile sstate-demo
ls tmp/stamps/cortexa57-poky-linux/sstate-demo/ | grep taint   # 1.0.do_compile.taint
bitbake -S printdiff sstate-demo
```
```
Task sstate-demo:do_install couldn't be used from the cache because:
  …
  Hash for task dependency sstate-demo:do_compile changed from 440d4a0c… to 309d0cfc…
      Taint (by forced/invalidated task) changed from None to 57bb2262-78c7-432e-…
```

`-f` writes a random UUID into a `.taint` file, which goes into the
hash. **Every later build** of anything depending on that task misses sstate
until the taint is cleared with `bitbake -c clean sstate-demo`. If someone ran
`-f` days ago, this is why you keep rebuilding.

### Experiment 8: MACHINE leaking into an allarch recipe

`sstate-demo-data` is `inherit allarch`, so its output should be shared
across every machine. Make it reference `MACHINE`:

```sh
D=../meta-sstate-demo/recipes-demo/sstate-demo-data/sstate-demo-data_1.0.bb
sed -i 's|echo "shared data"|echo "shared data, built for ${MACHINE}"|' $D

bitbake -S none sstate-demo-data                                            # qemuarm64
bitbake -r ../meta-sstate-demo/conf/other-machine.conf -S none sstate-demo-data   # qemux86-64
bitbake-diffsigs -t sstate-demo-data do_install
```
```
basehash changed from 81975c97… to 9d7ec3f6…
Variable MACHINE value changed from 'qemuarm64' to 'qemux86-64'
```

`-S none` computes signatures without building anything, which is the
cheap way to compare configurations. Fix by not using machine-specific values
in allarch recipes, or make the recipe machine-specific
(`PACKAGE_ARCH = "${MACHINE_ARCH}"` and drop `allarch`).

> On this bitbake-setup build, `MACHINE=qemux86-64 bitbake …` is
> **rejected** ("A builtin fragment 'machine/qemuarm64' is used while MACHINE
> has already got an assignment"). `conf/other-machine.conf` switches
> `OE_FRAGMENTS` for a single run instead, without editing `toolcfg.conf`.

Undo: `sed -i 's|, built for ${MACHINE}||' $D`

### Teardown

```sh
bitbake-layers remove-layer ../meta-sstate-demo
# optional: drop the demo's objects from the shared cache
# bitbake -c cleansstate sstate-demo sstate-demo-data   (run BEFORE remove-layer)
```

---

## Part 4: Culprit reference

| Culprit | What you see | Diagnose | Fix |
|---|---|---|---|
| Changed config value (local.conf, distro, machine) | `Variable X value changed` | `-S printdiff` | Intended? Fine. If not, find it with `bitbake-getvar -r R X` (history) |
| `DATETIME`/`DATE`/`TIME` | "metadata is not deterministic" errors that come and go with re-parses; diffsigs blames `TIME` | `dumpsig` → `List of dependencies for variable` | `VAR[vardepsexclude] += "DATETIME"` on the variable that **directly** references it, or better, `SOURCE_DATE_EPOCH` |
| `AUTOREV` / `SRCREV = "${AUTOREV}"` | `do_fetch` hash changes whenever upstream moves; `SRCREV`/`SRCPV` diff | `diffsigs -t R do_fetch` | Pin `SRCREV` for releases |
| Machine values in `allarch` | Same recipe rebuilds when switching MACHINE | `-S none` per machine + `diffsigs` | Remove the reference or use `MACHINE_ARCH` |
| Absolute paths (`${TOPDIR}`, `$HOME`) in a variable | sstate never reused across build dirs/hosts; diff shows paths | Compare `-S none` sigdata from both hosts | Use `${WORKDIR}`/`${S}` etc. (already ignored), or `vardepsexclude` on the path var |
| `bitbake -f` taint | `Taint (by forced/invalidated task) changed` | `ls tmp/stamps/…/*.taint` | `bitbake -c clean R` |
| `[nostamp]` task | Downstream reruns every build | `diffsigs` shows a `nostamp:` taint | Remove `nostamp`; give the task real inputs (`vardeps`, `file-checksums`) |
| Local file edits (`SRC_URI file://`) | `This task depends on the checksums of files` changed | `diffsigs -t R do_fetch`/`do_unpack` | Expected; hash equivalence often limits the ripple |
| Python `d.getVar(name)` with computed name | Changes **not** detected, so stale output comes from sstate | `dumpsig`: variable missing from `Task dependencies` | `do_task[vardeps] += "VAR"` |

The last row is the opposite problem: a task that **doesn't** rebuild when it
should. It's the same tools and the same reasoning, but the variable you
expect is missing from the dump.

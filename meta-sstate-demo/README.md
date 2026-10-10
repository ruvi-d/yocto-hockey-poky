# Debugging unexpected rebuilds: sstate and task signatures, hands-on

A small demo layer (`/workspace/yocto-hockey-poky/meta-sstate-demo`) for learning why BitBake reruns a task instead of reusing
shared state (sstate). Every command and output below was run on the
`hockey-qemuarm64-poky` build (wrynose, BitBake 2.18, `OEEquivHash`); hashes
are shortened to `…`.

```
meta-sstate-demo/
├── README.md                      # this guide
├── whopulls.py                    # traces why a variable is in a task's hash (experiment 6)
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

LAYER=/workspace/yocto-hockey-poky/meta-sstate-demo   # used by every command below
bitbake-layers add-layer $LAYER                       # no-op if already added

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
    $LAYER/recipes-demo/sstate-demo/sstate-demo_1.0.bb
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
`sed -i '/# a harmless comment/d' $LAYER/recipes-demo/sstate-demo/sstate-demo_1.0.bb`

### Experiment 6: the timestamp trap (and where `vardepsexclude` must go)

Embed the build time:

```sh
R=$LAYER/recipes-demo/sstate-demo/sstate-demo_1.0.bb
echo 'DEMO_EXTRA_CFLAGS = "-DBUILD_TIME=${DATETIME}"' >> $R
bbt sstate-demo
```
```
ERROR: When reparsing …/sstate-demo_1.0.bb:do_compile, the basehash value changed from 639de79d… to …
       The metadata is not deterministic and this needs to be fixed.
ERROR: sstate-demo-1.0-r0 do_compile: Taskhash mismatch 5f6b9198… versus acb57219… for …:do_compile
```

Each task is checked twice, by two different processes using two
different clock readings:

1. **The server parses the recipe.** `DATE`/`TIME` were set with `:=` in
   `bitbake.conf` when the *configuration* was parsed, call it time T1.
   `DATETIME` expands to T1 and is hashed into `do_compile`'s basehash.
2. **The build starts.** Just before scheduling tasks,
   `cooker.buildSetVars()` resets `DATE`/`TIME` to *now* (T2), and passes
   them to the workers.
3. **The worker re-parses the recipe** before running the task, with
   `DATE`/`TIME` = T2, and recomputes the basehash. In `siggen.py`
   `_build_data()`, T1 ≠ T2, so you get "When reparsing … the basehash value
   changed".

The task still runs and succeeds; bitbake just exits non-zero because of
the errors.

Run it again:

```sh
bbt sstate-demo
```
```
Sstate summary: Wanted 58 Local 58 Mirrors 0 Missed 0 Current 275 (100% match, 100% complete)
```

**No errors and no rebuild.** Two things combine to cause this:

- **The parse cache is reused.** Parsed recipe info, including every
  task's basehash, is stored in `tmp/cache/…/bb_cache.dat.<config-hash>`.
  The `<config-hash>` covers the whole configuration *except*
  `BB_HASHCONFIG_IGNORE_VARS`, which includes `DATE` and `TIME`, so the clock
  moving doesn't select a new cache file. Within that file, a recipe is
  re-parsed only if its own mtime or the mtime of a file it depends on
  changed. Neither happened, so run 2 got run 1's basehash, which still has
  run 1's timestamp in it.
- **Nothing ran, so nothing was checked.** That cached hash matches the
  stamps run 1 wrote, so every task is "Current". The determinism check only
  happens when a worker re-parses to *execute* a task, so it never fired.

The timestamp is frozen until the next re-parse of this recipe, which
happens when:

- the `.bb`, a `.bbappend`, or an `.inc`/`.bbclass` it uses is edited or
  touched (that includes `git pull` of a layer);
- any configuration that isn't ignored changes (local.conf, a layer added,
  `-r`/`-R` files, a different MACHINE), which gives a new config hash and a
  re-parse of every recipe;
- `tmp/cache` is deleted, or a fresh build directory is used (every CI
  run).

Each re-parse gives a new timestamp, which means a new hash, a rebuild, the
error again, **and a new sstate object nobody will ever reuse**. On your
machine the bug seems to come and go with unrelated edits. On CI, with a
fresh TMPDIR each time, it rebuilds every single run. Reproduce it:

```sh
touch $R; bbt sstate-demo                       # errors + rebuild again
bitbake-diffsigs -t sstate-demo do_compile
```
```
basehash changed from f27b546b… to 27f2ad87…
Variable TIME value changed from '162548' to '162615'
```

`diffsigs` blames **`TIME`**, not your variable. `do_compile` never
mentions `TIME`, so how did it get into the hash? The dump has what you need
to work it out:

| Dump line | What it tells you |
|---|---|
| `Task dependencies: [...]` | **Every** variable in the hash, flattened. `TIME` is in it, but this doesn't say *why*. |
| `List of dependencies for variable X is [...]` | **One edge** of the graph: the variables X's value references *directly*. (After `vardepsexclude` has been applied to X.) |
| `Variable do_compile value is ...` | The task body. Its `${...}` references are the first edge. There is **no** "List of dependencies" line for the task itself. |

Find the cause by walking the edges **backwards** from the blamed variable:

```sh
F=$(ls -t tmp/stamps/cortexa57-poky-linux/sstate-demo/1.0.do_compile.sigdata.* | head -1)
bitbake-dumpsig $F > /tmp/sig.txt

grep -E "^List of dependencies for variable .* is .*'TIME'" /tmp/sig.txt
#   List of dependencies for variable DATETIME is ['DATE', 'TIME']        <- DATETIME uses TIME
grep -E "^List of dependencies for variable .* is .*'DATETIME'" /tmp/sig.txt
#   List of dependencies for variable DEMO_EXTRA_CFLAGS is ['DATETIME']   <- DEMO_EXTRA_CFLAGS uses DATETIME
grep -E "^List of dependencies for variable .* is .*'DEMO_EXTRA_CFLAGS'" /tmp/sig.txt
#   (nothing: no other variable uses it, so look at the task body)
grep '^Variable do_compile value is' /tmp/sig.txt
#   Variable do_compile value is     ${CC} ... ${DEMO_EXTRA_CFLAGS} ...    <- the task uses it
```

The chain is `do_compile → DEMO_EXTRA_CFLAGS → DATETIME → TIME`. The
**last variable before the task** (`DEMO_EXTRA_CFLAGS`) is the one making the
direct reference, so that's where `vardepsexclude` must go, as fixes A and B
below show.

`whopulls.py` in this layer does the same backward walk and prints every
path:

```sh
bitbake-dumpsig $F | python3 $LAYER/whopulls.py TIME
#   do_compile -> DEMO_EXTRA_CFLAGS -> DATETIME -> TIME
```

A variable can reach a task by more than one path, and not always through a
`${...}` in the body. Shell tasks also depend on every **exported**
variable:

```
$ ... | whopulls.py DEBUG_PREFIX_MAP_EXTRA
do_compile -> CFLAGS -> TARGET_CFLAGS -> DEBUG_PREFIX_MAP -> DEBUG_PREFIX_MAP_EXTRA
(exported to task env) -> CXXFLAGS -> TARGET_CXXFLAGS -> TARGET_CFLAGS -> DEBUG_PREFIX_MAP -> DEBUG_PREFIX_MAP_EXTRA
```

When there are several paths, you have to cut **each** of them; a
`vardepsexclude` on just one path leaves the others in the hash.

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
this build". OE-core makes the same trade-off on purpose: `os-release` has
`BUILD_ID[vardepsexclude] = "DATETIME"`, so its build ID is the time the
package was last *really* built.

**Fix C: if the value truly must be fresh**, keep fix B and add `nostamp`:

```sh
echo 'do_compile[nostamp] = "1"' >> $R
bbt sstate-demo; bbt sstate-demo     # both runs: do_compile ... do_package_write_rpm, no errors
```

The two lines solve two different problems:

- **`vardepsexclude` fixes the mismatch error.** The clock is no longer in
  the hash, so the server and the worker agree.
- **`nostamp` makes the value fresh.** The task runs every build no matter
  what its hash is.

The value really is current, even with a cached parse. The worker
re-parses the recipe with `DATE`/`TIME` set to *this* build's start time, so
every task in one build sees the same time. Don't use
`BB_HASH_IGNORE_MISMATCH = "1"` instead: it hides the error, but the value
stays frozen.

**The cost:** a `nostamp` task gives everything downstream a random taint,
so all of it reruns every build and is never restored from sstate. That
includes `do_rootfs` of any image containing the package. Each run also
writes new sstate objects that nothing will ever reuse. Keep the
time-dependent part as late and as small as possible:

- **Prefer an identifier over the clock.** `SOURCE_DATE_EPOCH` (time of the
  last source change), a version or a git hash is deterministic and fully
  cacheable.
- **Don't compile the time into a binary.** Have the program read it at
  runtime from a file such as `/etc/build-time`, so the expensive recipe
  stays cacheable.
- **Write that file in a tiny dedicated recipe with a `nostamp` task, or at
  image level** with a `ROOTFS_POSTPROCESS_COMMAND` function.

Clean up:
`sed -i '/^DEMO_EXTRA_CFLAGS = "-DBUILD_TIME/d; /^DEMO_EXTRA_CFLAGS\[vardepsexclude\]/d; /^do_compile\[nostamp\]/d' $R`

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
D=$LAYER/recipes-demo/sstate-demo-data/sstate-demo-data_1.0.bb
sed -i 's|echo "shared data"|echo "shared data, built for ${MACHINE}"|' $D

bitbake -S none sstate-demo-data                                            # qemuarm64
bitbake -r $LAYER/conf/other-machine.conf -S none sstate-demo-data   # qemux86-64
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
# Optional: drop the demo's objects from the shared cache (needs the layer still added).
bitbake -c cleansstate sstate-demo sstate-demo-data

bitbake-layers remove-layer $LAYER
```

---

## Part 4: Culprit reference

| Culprit | What you see | Diagnose | Fix |
|---|---|---|---|
| Changed config value (local.conf, distro, machine) | `Variable X value changed` | `-S printdiff` | Intended? Fine. If not, find it with `bitbake-getvar -r R X` (history) |
| `DATETIME`/`DATE`/`TIME` | "metadata is not deterministic" errors that come and go with re-parses; diffsigs blames `TIME` | `dumpsig` → `List of dependencies for variable` | `VAR[vardepsexclude] += "DATETIME"` on the variable that **directly** references it, or better, `SOURCE_DATE_EPOCH`. Add `[nostamp]` only if it must be fresh (experiment 6, fix C) |
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

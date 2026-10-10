# Why did a package get installed?

How to trace any package in a Yocto image back to the reason it is there.
The examples are real output from `core-image-minimal` in the
`hockey-qemuarm64-poky` build (distro `poky`, `PACKAGE_CLASSES = package_rpm`,
so the rootfs is assembled by dnf), with the demo recipes described at the end.

All commands run in the build directory after sourcing the environment:

```sh
bash -c '. /workspace/yocto-hockey-poky/bitbake-builds/hockey-qemuarm64-poky/build/init-build-env >/dev/null && <command>'
BH=buildhistory/images/qemuarm64/glibc/core-image-minimal   # used below
```

## TL;DR

1. Find the parent: `grep -- '-> "PKG"' $BH/depends.dot`
2. Or the whole chain:
   `sed 's/ \[.*//' $BH/depends.dot > /tmp/rdeps.dot && oe-depends-dot -w -k PKG /tmp/rdeps.dot`
3. Read the edge style (see the legend below) to see what *kind* of dependency it is.
4. Ask why the parent has that dependency: `bitbake-getvar -r <parent-recipe> RDEPENDS:<parent-pkg>`
   (or `RRECOMMENDS:`, `RPROVIDES:`, `PACKAGECONFIG`).
5. No incoming edge? It was requested directly: look at
   `tmp/work/*/<image>/*/image_initial_manifest` and the
   `Installing complementary packages` line in `log.do_rootfs`.

## 0. Turn on buildhistory

Which packages end up in the image is decided by the package manager (dnf) in
`do_rootfs`, not by bitbake. buildhistory is the only thing that writes dnf's
resolved graph to disk: inside `do_rootfs` it asks the rpm database of the
finished rootfs what every installed package requires and which package
satisfied it, before the database is deleted (it is only kept with the
`package-management` image feature). That graph, `depends.dot`, is what most of
this document reads. See [Why not `bitbake -g`?](#why-not-bitbake--g) for why
the build-time graph is not a substitute.

It is enabled in this build through the `meta-hockey/buildhistory` fragment
([meta-hockey/conf/fragments/buildhistory.conf](../meta-hockey/conf/fragments/buildhistory.conf)):

```sh
bitbake-config-build enable-fragment meta-hockey/buildhistory
bitbake core-image-minimal
```

Each image build then writes `$BH/` and commits it to git in `buildhistory/`:

| File                          | What it tells you                                                          |
|-------------------------------|----------------------------------------------------------------------------|
| `installed-package-names.txt` | Final package list (after renaming, after removals)                        |
| `installed-package-info.txt`  | `package  pkg-name-in-pkgdata  recipe  version  size-KiB`, biggest first   |
| `depends.dot`                 | Runtime dependency graph between installed packages, **the key file**      |
| `depends-nokernel*.dot`       | Same graph with kernel/libc/update-alternatives/modules noise removed      |
| `files-in-image.txt`          | Every file in the rootfs                                                   |
| `image-info.txt`              | `IMAGE_INSTALL`, `IMAGE_FEATURES`, `BAD_RECOMMENDATIONS`, `PACKAGE_EXCLUDE`… as used |

As a side benefit, `buildhistory-diff` compares the last two builds (or any two
git revisions), which answers "why did this package *suddenly* appear?".

Without buildhistory you can still use `log.do_rootfs` and `oe-pkgdata-util`
(section 6), but you lose the graph.

### Why not `bitbake -g`?

`bitbake -g <image>` writes `task-depends.dot` and `pn-buildlist` without
building anything, and `oe-depends-dot` was written for that file (its lines
have no edge attributes, so no `sed` is needed). It answers a different
question, though: which **recipes** have to be **built** to produce the image.

| | `bitbake -g` → `task-depends.dot` | buildhistory `depends.dot` |
|---|---|---|
| Question answered | What must be built | What dnf installed, and what each package required |
| Nodes | Recipes (`glibc`, `openssl`) | Packages (`libc6`, `libcrypto3`, `tar-doc`) |
| Computed | Before the build, from metadata | After `do_rootfs`, from the rootfs rpm database |
| Shared-library deps found in `do_package` | No | Yes |
| `BAD_RECOMMENDATIONS`, `PACKAGE_EXCLUDE` | Ignored | Applied |
| Complementary packages (`*-doc`, `*-dev`) | Absent | Present |
| Packages removed after install (`run-postinsts`) | Not known | Gone |
| Build-only deps (`DEPENDS`, native tools, ptest, kernel) | Included, mixed with runtime edges | Absent |

Measured on this image with the demo enabled: **113 target recipes are built,
but only 33 recipes have a package in the image.** `oe-depends-dot` still gives
each of the other 80 a convincing chain:

```console
$ bitbake -g core-image-minimal
$ oe-depends-dot -w -k perl task-depends.dot
core-image-minimal -> libarchive -> acl -> coreutils -> gawk -> ptest-runner -> shadow -> attr -> perl
$ oe-depends-dot -w -k bash task-depends.dot
core-image-minimal -> packagegroup-core-boot -> eudev -> util-linux -> kmod -> bash-completion -> bash
$ oe-depends-dot -w -k xauth task-depends.dot      # excluded by BAD_RECOMMENDATIONS
core-image-minimal -> packagegroup-core-ssh-dropbear -> dropbear -> xauth
```

None of `perl`, `bash` or `xauth` is installed. Those are build-time edges
(`DEPENDS`, ptest, recommendations that bitbake builds but dnf skips), so they
are false positives. In the other direction, packages that *are* installed are
not found at all, because the graph only knows recipe names:

```console
$ oe-depends-dot -w -k libc6 task-depends.dot
ERROR: Can't find key libc6 in task-depends.dot          # also tar-doc, util-linux-flock
```

Even a correct-looking chain such as `whydemo-app -> libwhydemo` is there
because of `DEPENDS`; a static or header-only library would produce the same
chain and install nothing.

Use `bitbake -g` for "why is this recipe being **built** or fetched?" (build
time, licenses, download failures), or as a cheap hint before a build. To
answer "why is this package **installed**?", every result would have to be
checked against the installed list, which needs post-rootfs data anyway.

## 1. Pin down exactly what is installed

The name you see in the image is often not the recipe name.

```console
$ oe-pkgdata-util find-path /usr/lib/libwhydemo.so.1     # file -> package
libwhydemo: /usr/lib/libwhydemo.so.1
$ oe-pkgdata-util lookup-pkg libwhydemo                  # pkgdata name -> name in the image
libwhydemo1
$ oe-pkgdata-util lookup-pkg -r libwhydemo1              # name in the image -> pkgdata name
libwhydemo
$ oe-pkgdata-util lookup-recipe libwhydemo1              # package -> recipe
libwhydemo
$ oe-pkgdata-util list-pkgs -p libwhydemo                # all packages a recipe produces
libwhydemo  libwhydemo-dbg  libwhydemo-dev  libwhydemo-src
```

> **Package renaming.** Poky inherits the `debian` class, which renames a
> package that ships one shared library after its soname: `libwhydemo` →
> `libwhydemo1`, `glibc` → `libc6`, `openssl`'s `libcrypto` → `libcrypto3`,
> `zlib` → `libz1`. buildhistory, dnf and the image manifest use the **renamed**
> name; `oe-pkgdata-util read-value`, `find-path` and recipe metadata use the
> **original** name. Translate with `lookup-pkg` / `lookup-pkg -r` when a grep
> comes back empty. Section 9 explains the rules, with openssl as a full example.

## 2. Find who pulls it in

Grep for incoming edges:

```console
$ grep -- '-> "libwhydemo1"' $BH/depends.dot
"whydemo-app" -> "libwhydemo1" [label=">= 1.0"]
"whydemo-app" -> "libwhydemo1" [label="libwhydemo.so.1()(64bit)"] [style=dashed]
```

Repeat on the parent until you reach a package with no incoming edges, or let
`oe-depends-dot` walk the whole chain. It does not understand the edge
attributes, so strip them first:

```console
$ sed 's/ \[.*//' $BH/depends.dot > /tmp/rdeps.dot
$ oe-depends-dot -w -k libwhydemo1 /tmp/rdeps.dot
Because: whydemo-app packagegroup-whydemo
packagegroup-whydemo -> whydemo-app -> libwhydemo1
$ oe-depends-dot -w -k libcrypto3 /tmp/rdeps.dot
Because: kmod libkmod2 eudev packagegroup-core-boot
packagegroup-core-boot -> eudev -> kmod -> libcrypto3
eudev -> libkmod2 -> libcrypto3
```

`Because:` lists every ancestor. If it says `Can't find key`, the package has
no edges at all: it was installed directly (section 4).

### Edge legend for `depends.dot`

buildhistory asks the package manager what each installed package requires
and how that requirement was satisfied:

| Edge                                   | Meaning                                                                                   | Example                                                     |
|----------------------------------------|-------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| solid, no label                        | Requires this package **by name**                                                         | `"packagegroup-whydemo" -> "whydemo-app"`                   |
| solid, `label=">= x"`                  | Versioned requirement by name. Usually **added automatically** from shlibs, not typed in a recipe | `"whydemo-app" -> "libwhydemo1" [label=">= 1.0"]`    |
| dashed, soname label                   | Satisfied by a library the package provides (automatic shlib dependency)                  | `[label="libwhydemo.so.1()(64bit)"] [style=dashed]`         |
| dashed, path label                     | Satisfied by a file the package provides (e.g. a script interpreter or postinst shell)    | `"base-passwd" -> "busybox" [label="/bin/sh"]`              |
| dashed, plain-name label               | Satisfied by an **`RPROVIDES`** (virtual name); the label is the name that was required   | `"whydemo-app" -> "whydemo-plugin-foo" [label="whydemo-plugin"]` |
| dotted                                 | `RRECOMMENDS` (weak). **The target may not be installed** (see section 5)                 | `"whydemo-app" -> "whydemo-extras" [style=dotted]`          |

## 3. Find out why the parent has that dependency

The edge tells you the kind; the parent recipe tells you where it came from.

### 3a. Explicit `RDEPENDS` / `RRECOMMENDS` / `RPROVIDES`

`bitbake-getvar` with `--value` prints every file and line that touched the
variable. Use the **package** name in the override and the **recipe** name for
`-r`:

```console
$ bitbake-getvar -r whydemo-app RDEPENDS:whydemo-app
# $RDEPENDS:whydemo-app [2 operations]
#   set /workspace/yocto-hockey-poky/meta-hockey/recipes-demo/whydemo/whydemo-app_1.0.bb:21
#     "whydemo-plugin"
...
```

This is also how you find a dependency added by a `.bbappend` in some other
layer, by a distro config, or by a class (e.g. `update-alternatives.bbclass`
adds `update-alternatives-opkg`, `update-rc.d.bbclass` recommends `update-rc.d`).

### 3b. Automatic shared-library dependency (nobody wrote it)

Compare what the recipe says with what ended up in the package:

```console
$ bitbake-getvar -r whydemo-app --value RDEPENDS:whydemo-app    # metadata
whydemo-plugin
$ oe-pkgdata-util read-value RDEPENDS whydemo-app               # after packaging
whydemo-plugin glibc (>= 2.43+git0+1c9988e525) libwhydemo (>= 1.0)
```

Anything in the second list but not the first was added during `do_package`
(`shlibdeps`, plus the rpm generator's soname requirements shown as dashed
edges). To find which binary needs it, check the files of the parent package:

```sh
oe-pkgdata-util list-pkg-files whydemo-app
readelf -d tmp/work/*/whydemo-app/*/packages-split/whydemo-app/usr/bin/whydemo-app | grep NEEDED
```

To get rid of it you must stop the binary linking against the library, which
is usually a `PACKAGECONFIG` option of the parent recipe (3d).

### 3c. Virtual names: `RPROVIDES` and `VIRTUAL-RUNTIME_*`

A dashed edge with a plain-name label means the parent required a **name**
that is not the name of the package that got installed. Three kinds of thing
are involved, and they are easy to mix up:

- **Recipe**: a `.bb` file. It is built and produces packages.
- **Package**: an output of a recipe (`.rpm`); what dnf installs.
- **Runtime name**: any word in an `RDEPENDS`/`RRECOMMENDS`. It is satisfied
  either by a package with that name, or by a package that lists the name in
  its `RPROVIDES`.

Worked through for `eudev` in this image:

| Name | What it is | Defined in |
|---|---|---|
| `eudev` | **recipe** | `meta/recipes-core/udev/eudev_3.2.14.bb` |
| `eudev`, `eudev-hwdb`, `libudev`, `eudev-dev`, … | **packages** made by recipe `eudev` (`oe-pkgdata-util list-pkgs -p eudev`) | same recipe |
| `udev` | **runtime name only**. No package is called `udev` in this build; package `eudev` claims the name with `RPROVIDES:${PN} = "hotplug udev"` | `eudev_3.2.14.bb:77` |
| `packagegroup-core-boot` | recipe, and the one package it makes | `meta/recipes-core/packagegroups/packagegroup-core-boot.bb` |
| `VIRTUAL-RUNTIME_dev_manager` | an ordinary **variable** whose value is a runtime name (`udev`) | distro config, see step 3 |

**Step 1: the graph shows the name and the package that satisfied it.**

```console
$ grep -- '-> "eudev"' $BH/depends.dot
"packagegroup-core-boot" -> "eudev" [label="udev"] [style=dashed]
```

Read it as: package `packagegroup-core-boot` requires runtime name `udev`
(label); package `eudev` satisfied it (dashed = through `RPROVIDES`).

**Step 2: confirm which package provides the name.**

```console
$ oe-pkgdata-util read-value RPROVIDES eudev
hotplug udev
$ grep -lE '^RPROVIDES:.*[: ]udev( |$)' tmp/pkgdata/qemuarm64/runtime/*    # who else provides "udev"?
tmp/pkgdata/qemuarm64/runtime/eudev
```

**Step 3: find where the parent's `udev` comes from.** The word `udev` does not
appear in `packagegroup-core-boot.bb`. Compare the final value of its
`RDEPENDS` with the value before variables are expanded; both are in the same
`bitbake-getvar` output (trimmed here):

```console
$ bitbake-getvar -r packagegroup-core-boot RDEPENDS:packagegroup-core-boot
# pre-expansion value:
#   "base-files base-passwd ${VIRTUAL-RUNTIME_base-utils} ... ${VIRTUAL-RUNTIME_init_manager} ${VIRTUAL-RUNTIME_dev_manager} ..."
RDEPENDS:packagegroup-core-boot="base-files base-passwd busybox ... sysvinit udev ..."
```

`udev` sits where `${VIRTUAL-RUNTIME_dev_manager}` was, so the recipe depends
on whatever that variable holds. That is the link from the package graph to
the variable. Next, where is the variable set?

```console
$ bitbake-getvar -r packagegroup-core-boot VIRTUAL-RUNTIME_dev_manager
#   set .../meta/conf/distro/include/init-manager-sysvinit.inc:6
#     [_defaultval] "udev"
```

`init-manager-sysvinit.inc` is used because poky sets
`INIT_MANAGER = "sysvinit"` (`meta-poky/conf/distro/poky.conf:68-69`) and
`defaultsetup.conf:21` does `require conf/distro/include/init-manager-${INIT_MANAGER}.inc`.

The whole chain:

```text
poky.conf                      INIT_MANAGER = "sysvinit"
defaultsetup.conf              require init-manager-sysvinit.inc
init-manager-sysvinit.inc      VIRTUAL-RUNTIME_dev_manager ??= "udev"
packagegroup-core-boot.bb      RDEPENDS:${PN} = "... ${VIRTUAL-RUNTIME_dev_manager} ..."   -> requires "udev"
eudev_3.2.14.bb                RPROVIDES:${PN} = "hotplug udev"                              -> package eudev provides it
dnf                            installs package eudev
```

**What `VIRTUAL-RUNTIME_*` is.** A naming convention, not a BitBake feature.
Generic recipes such as packagegroups depend on `${VIRTUAL-RUNTIME_<role>}`
instead of a fixed package, so a distro can choose the implementation of a role
without editing those recipes. The same `RDEPENDS` line uses several of them;
in this build `VIRTUAL-RUNTIME_init_manager` = `sysvinit`,
`VIRTUAL-RUNTIME_login_manager` = `busybox`, `VIRTUAL-RUNTIME_base-utils` =
`busybox`, `VIRTUAL-RUNTIME_update-alternatives` = `update-alternatives-opkg`.
With `INIT_MANAGER = "systemd"`, `init-manager-systemd.inc` sets
`VIRTUAL-RUNTIME_dev_manager` to `systemd`; `init-manager-mdev-busybox.inc`
sets it to `busybox-mdev`. Whenever a word in an `RDEPENDS` is not written in
the recipe, look for a variable in the pre-expansion value.

**Changing it.** There are two separate knobs:

| You want to change | Set | Example |
|---|---|---|
| Which runtime name the recipe requires | the `VIRTUAL-RUNTIME_*` variable, in a distro config | `VIRTUAL-RUNTIME_dev_manager = "busybox-mdev"` |
| Which **recipe** supplies a name that several recipes provide | `PREFERRED_PROVIDER_<name>` (build time, `PROVIDES`) / `PREFERRED_RPROVIDER_<name>` (runtime, `RPROVIDES`) | `default-providers.inc` sets `PREFERRED_PROVIDER_udev` to `systemd` if `systemd` is in `DISTRO_FEATURES`, otherwise `eudev` |

Recipe `eudev` also has `PROVIDES = "udev"`, the build-time counterpart used
when another recipe has `DEPENDS = "udev"`. Recipe `systemd` provides `udev`
too (and makes a package literally named `udev`), but here it is skipped
because poky lacks the `systemd` distro feature, so `eudev` is the only
provider (`bitbake-layers show-recipes eudev systemd`). When several providers
are buildable and no preference is set, bitbake warns, and which one dnf
installs is no longer under your control.

### 3d. `PACKAGECONFIG` and `DISTRO_FEATURES`

When the history points at `base.bbclass` instead of a recipe line, the
dependency came from a `PACKAGECONFIG` option. Its flag fields are
`enable, disable, DEPENDS, RDEPENDS, RRECOMMENDS, conflicts`:

```console
$ bitbake-getvar -r dropbear RRECOMMENDS:dropbear
#   append base.bbclass:508 [appendVar]
#     " xauth"
$ grep PACKAGECONFIG layers/openembedded-core/meta/recipes-core/dropbear/dropbear_*.bb
PACKAGECONFIG ?= "${@bb.utils.filter('DISTRO_FEATURES', 'pam x11', d)}"
PACKAGECONFIG[x11] = ",,,,xauth"          # 5th field = RRECOMMENDS
```

`poky` has `x11` in `DISTRO_FEATURES`, so dropbear recommends `xauth`, which
links against `libX11` and friends: about 1.9 MiB of X11 libraries in a
headless image (see example B).

`PACKAGECONFIG` can also add a dependency indirectly, by enabling a configure
option that links a library (3b). `kmod` has `PACKAGECONFIG ??= "zlib xz openssl"`;
the `openssl` option makes kmod link `libcrypto.so.3`, which is why the biggest
package in this image (`libcrypto3`, 5.6 MiB) is there.

## 4. No incoming edge: it was requested directly

The root set of the install is written to `image_initial_manifest`:

```console
$ grep -v '^#' tmp/work/qemuarm64-poky-linux/core-image-minimal/1.0/image_initial_manifest
mip,packagegroup-core-boot
mip,packagegroup-core-ssh-dropbear
mip,packagegroup-whydemo
mip,run-postinsts
```

(`mip` = must install, `aop` = attempt only, `lgp` = language, `mlp` = multilib.)
These come from `PACKAGE_INSTALL`, which is built from several variables:

| Source                                   | Example in this image                                      | Check with                                          |
|------------------------------------------|------------------------------------------------------------|-----------------------------------------------------|
| `IMAGE_INSTALL` (incl. `CORE_IMAGE_EXTRA_INSTALL`) | `packagegroup-core-boot`, `packagegroup-whydemo`  | `bitbake-getvar -r <image> IMAGE_INSTALL`           |
| `IMAGE_FEATURES` → `FEATURE_PACKAGES_<feature>` | `ssh-server-dropbear` → `packagegroup-core-ssh-dropbear` | `bitbake-getvar -r <image> --value FEATURE_PACKAGES_ssh-server-dropbear` |
| `ROOTFS_BOOTSTRAP_INSTALL`               | `run-postinsts` (removed again later, see section 5)       | `bitbake-getvar -r <image> --value ROOTFS_BOOTSTRAP_INSTALL` |
| `IMAGE_LINGUAS`                          | none here (`lgp,` lines, `locale-base-*`)                  | `bitbake-getvar -r <image> --value IMAGE_LINGUAS`   |
| `PACKAGE_INSTALL_ATTEMPTONLY`            | none here (`aop,` lines, silently skipped if missing)      |                                                     |
| Complementary packages (`IMAGE_FEATURES` such as `dev-pkgs`, `dbg-pkgs`, `doc-pkgs`, `ptest-pkgs`) | all `*-doc`, e.g. `whydemo-app-doc` | `log.do_rootfs` (below)                       |

Complementary packages are **not** in `image_initial_manifest` and have no
edges in `depends.dot`. They are added in a second dnf run, by taking every
installed package and asking pkgdata for its `*-doc` (or `*-dev`, …) sibling:

```console
$ grep -E 'glob|complementary' tmp/work/*/core-image-minimal/*/temp/log.do_rootfs
NOTE: Running ['oe-pkgdata-util', ..., 'glob', '/tmp/installed-pkgs...', '*-doc', '--exclude=openssh']
NOTE: Installing complementary packages ... acl-doc attr-doc ... whydemo-app-doc xz-doc
```

The globs come from `COMPLEMENTARY_GLOB[<feature>]`; `PACKAGE_EXCLUDE_COMPLEMENTARY`
filters them (`core-image.bbclass` adds `openssh` when dropbear is installed).
`bitbake-getvar -r <image> --value IMAGE_INSTALL_COMPLEMENTARY` shows the globs
in effect (`*-doc` here); `bitbake -c list_image_features <image>` lists every
feature name you can use.

To find which variable put a root there, run `bitbake-getvar -r <image>` on
`IMAGE_INSTALL` / `IMAGE_FEATURES`; it shows the bbappend line:

```console
$ bitbake-getvar -r core-image-minimal IMAGE_INSTALL
#   set .../meta/recipes-core/images/core-image-minimal.bb:3
#     "packagegroup-core-boot ${CORE_IMAGE_EXTRA_INSTALL}"
#   :append[whydemo] /workspace/yocto-hockey-poky/meta-hockey/recipes-core/images/core-image-minimal.bbappend:6
#     " packagegroup-whydemo"
```

## 5. Edge cases and traps

**A dotted edge does not mean the target is installed.** With
`BAD_RECOMMENDATIONS += "xauth"` the graph still shows the recommendation, but
`xauth` is not in `installed-package-names.txt`:

```console
$ grep xauth $BH/depends.dot $BH/installed-package-names.txt
.../depends.dot:"dropbear" -> "xauth" [style=dotted]
```

The same happens when the recommended package was never created because it
is empty (`openssl-ossl-module-legacy`, see section 9). Always confirm against
`installed-package-names.txt`.

**With rpm, `BAD_RECOMMENDATIONS` is a hard exclude.** OE passes both
`BAD_RECOMMENDATIONS` and `PACKAGE_EXCLUDE` to dnf as `-x` (shown as
`Excludes in dnf.conf:` in `log.do_rootfs`). If anything hard-requires the
package, `do_rootfs` fails. The error is itself a "why" chain:

```text
Excludes in dnf.conf: whydemo-plugin-foo, xauth
Error:
 Problem: package packagegroup-whydemo-1.0-r0.noarch from oe-repo requires whydemo-app, but none of the providers can be installed
  - package whydemo-app-1.0-r0.cortexa57 from oe-repo requires whydemo-plugin, but none of the providers can be installed
  - conflicting requests
  - package whydemo-plugin-foo-1.0-r0.noarch from oe-repo is filtered out by exclude filtering
```

Trick: to find out who needs a package, temporarily exclude it and read the
error. No file edit needed:

```sh
BB_ENV_PASSTHROUGH_ADDITIONS="$BB_ENV_PASSTHROUGH_ADDITIONS PACKAGE_EXCLUDE" \
PACKAGE_EXCLUDE="whydemo-plugin-foo" bitbake -c rootfs core-image-minimal
```

(The next normal build reruns `do_rootfs` because the variable changed back.)

**Installed during `do_rootfs`, but not in the final image.** After the
transaction, `run-postinsts` is removed when no postinst was deferred to first
boot, and dnf also removes the dependencies nothing else uses. With
`read-only-rootfs` the `ROOTFS_RO_UNNEEDED` packages (`update-rc.d`,
`base-passwd`, `shadow`, the update-alternatives provider) go too. Here
`util-linux-flock` (an `RDEPENDS` of `run-postinsts`) appears in the dnf
transaction and then disappears:

```console
$ grep -E 'Removed:|will be erased' tmp/work/*/core-image-minimal/*/temp/log.do_rootfs
---> Package run-postinsts.noarch 1.0-r0 will be erased
---> Package util-linux-flock.cortexa57 2.41.5-r0 will be erased
```

Rootfs postprocess commands (`ROOTFS_POSTPROCESS_COMMAND`) can also delete or
add files without changing the package list, so "file present" and "package
installed" are separate questions: use `files-in-image.txt` and `find-path`.

**Built is not installed.** A recipe in `tmp/work/` or in the `bitbake -g`
graph says nothing about the image: `xauth` and its X11 libraries are still
built when `BAD_RECOMMENDATIONS` keeps them out. See
[Why not `bitbake -g`?](#why-not-bitbake--g).

**A solid versioned edge is usually not hand-written.** `"whydemo-app" -> "libwhydemo1" [label=">= 1.0"]`
comes from shlibs, not from the recipe. Check with 3b before searching the
layers for a `RDEPENDS` that does not exist.

**pkgdata and the image use different names.** See section 9.
`oe-pkgdata-util read-value RDEPENDS kmod` says `libcrypto`, the image has
`libcrypto3`.

**Weak dependencies you never wrote.** `NO_RECOMMENDATIONS = "1"` drops *all*
recommendations, including `MACHINE_EXTRA_RRECOMMENDS` (kernel modules,
firmware) and `ldconfig`, `update-rc.d`, `busybox-udhcpc` here. Prefer targeted
`BAD_RECOMMENDATIONS`.

**The kernel is not always there.** This `core-image-minimal` contains no
`kernel-image-*`: QEMU boots the kernel from `tmp/deploy/images/`. On real
hardware it typically comes in through `IMAGE_INSTALL` or the machine's
`MACHINE_ESSENTIAL_EXTRA_RDEPENDS` / `MACHINE_EXTRA_RRECOMMENDS` (kernel
modules), and the `depends-nokernel*.dot` files exist to hide it.

## 6. Without buildhistory

`log.do_rootfs` splits the first dnf transaction into the three reasons a
package can be installed:

```text
Installing:                     <- requested (PACKAGE_INSTALL)
 packagegroup-core-boot ...
Installing dependencies:        <- hard requirement of something else
 libwhydemo1 ...
Installing weak dependencies:   <- RRECOMMENDS
 openssh-sftp-server ...
 whydemo-extras ...
```

and `NOTE: Installing complementary packages ...` lists the second pass.
It does not say *which* package required each one; for that walk pkgdata by
hand: `oe-pkgdata-util read-value RDEPENDS <pkg>` / `RRECOMMENDS` / `RPROVIDES`.

## 7. Removing a package: what to change

| Cause                                         | Fix                                                                     |
|-----------------------------------------------|-------------------------------------------------------------------------|
| `IMAGE_INSTALL` / packagegroup `RDEPENDS`     | `IMAGE_INSTALL:remove`, or a different packagegroup                     |
| `IMAGE_FEATURES`                              | Drop the feature; or override `FEATURE_PACKAGES_<feature>`              |
| Complementary glob                            | Drop the `*-pkgs` feature, or `PACKAGE_EXCLUDE_COMPLEMENTARY += "<regex>"` |
| `RRECOMMENDS` (dotted edge)                   | `BAD_RECOMMENDATIONS += "<pkg>"` in the image (renamed name; hard exclude with rpm) |
| `PACKAGECONFIG` RDEPENDS/RRECOMMENDS          | `PACKAGECONFIG:remove:pn-<recipe> = "<option>"` (bbappend or distro conf) |
| `DISTRO_FEATURES` switching on a PACKAGECONFIG | Change the distro features, or override the recipe's `PACKAGECONFIG`   |
| Shared-library link                           | Disable the option that links it (`PACKAGECONFIG`), not `RDEPENDS:remove` |
| Explicit hard `RDEPENDS`                      | `RDEPENDS:<pkg>:remove` in a bbappend, only if the parent really works without it |
| Virtual name (`RPROVIDES`)                    | Change `VIRTUAL-RUNTIME_*` / `PREFERRED_RPROVIDER_*`                    |

Then rebuild and confirm with `buildhistory-diff`:

```console
$ buildhistory-diff
images/qemuarm64/glibc/core-image-minimal: IMAGESIZE changed from 20708 to 23668 (+14%)
Changes to images/qemuarm64/glibc/core-image-minimal (installed-package-names.txt):
  libx11-6 was removed
  ...
  xauth was removed
  whydemo-app was added
```

## 8. Worked examples from this image

**A. `libwhydemo1`: automatic shlib dependency + renamed package.**
`grep -- '-> "libwhydemo1"'` gives `whydemo-app` with a versioned solid edge and
a soname dashed edge. `RDEPENDS:whydemo-app` in the recipe does not mention it,
`oe-pkgdata-util read-value RDEPENDS whydemo-app` does → added by shlibdeps
because `/usr/bin/whydemo-app` links `libwhydemo.so.1`. The recipe is
`libwhydemo`; the package was renamed by the `debian` class.

**B. `libx11-6` in a headless image: recommendation of a PACKAGECONFIG.**
Chain without the demo fragment: `packagegroup-core-ssh-dropbear` → `dropbear`
`··>` `xauth` → `libxext6`/`libxmuu1` → `libx11-6` → `libxcb1` → `libxau6`/`libxdmcp6`.
Only the `dropbear ··> xauth` edge is dotted. Its source is
`PACKAGECONFIG[x11] = ",,,,xauth"`, enabled because `x11` is in poky's
`DISTRO_FEATURES`. `BAD_RECOMMENDATIONS += "xauth"` removes all seven packages
(about 1.9 MiB); this is what the demo does.

**C. `libcrypto3` (5.6 MiB): a PACKAGECONFIG link dependency.**
`packagegroup-core-boot` → `eudev` (via `udev`) → `kmod` → `libcrypto3`, all
solid/dashed (hard). `kmod`'s `PACKAGECONFIG` contains `openssl` (module
signature support), so the binary links `libcrypto.so.3`. Removing it would
need `PACKAGECONFIG:remove:pn-kmod = "openssl"`; `BAD_RECOMMENDATIONS` would
just break `do_rootfs`.

**D. `eudev`: a virtual runtime provider.** See 3c.

**E. `whydemo-plugin-foo`: an `RPROVIDES`.** `whydemo-app` requires
`whydemo-plugin`; no package has that name, `whydemo-plugin-foo` provides it.
Dashed edge labelled `whydemo-plugin`.

**F. `whydemo-extras` and `openssh-sftp-server`: plain `RRECOMMENDS`.**
Dotted edges from `whydemo-app` and `packagegroup-core-ssh-dropbear`, listed
under `Installing weak dependencies` in `log.do_rootfs`.

**G. `whydemo-app-doc`, `libc6-doc`, …: complementary packages.** No edges,
not in `image_initial_manifest`; listed on the
`Installing complementary packages` line because `doc-pkgs` is in
`IMAGE_FEATURES`.

**H. `ttyrun`: a hard `RDEPENDS` you can read in the recipe.**
`"sysvinit-inittab" -> "ttyrun"`, solid with no label;
`sysvinit-inittab_2.88dsf.bb` has `RDEPENDS:${PN} = "ttyrun"` because the
generated inittab calls it.

## 9. Package renaming (`debian.bbclass`)

`debian.bbclass` is inherited through `INHERIT_DISTRO` in
`meta/conf/distro/defaultsetup.conf`, so every OE-core distro has it, poky
included, unless the distro overrides `INHERIT_DISTRO`. During `do_package` it
renames a package that is "just a shared library" after that library's soname,
using the Debian naming convention.

### Why it is done

The package name follows the library's **ABI**, not the project's name or version:

- **An ABI break changes the package name.** When upstream bumps the soname
  (`libssl.so.1.1` → `libssl.so.3`), the package changes from `libssl1.1` to
  `libssl3`. A package manager then sees a *new* package, not an upgrade that
  silently replaces a library that installed binaries still link against.
  Because the files differ (`.so.1.1` vs `.so.3`), both versions can be
  installed side by side until nothing needs the old one. This matters on
  devices updated from package feeds (`package-management` in `IMAGE_FEATURES`).
- **Dependencies name the ABI.** `openssl-bin` requires `libcrypto3 >= 3.5.8`,
  which says exactly which ABI it was linked against.
- **ABI breaks show up in package lists.** `buildhistory-diff` reports
  `libfoo1 was removed` / `libfoo2 was added` instead of a version change.

It has to happen during packaging, not in the recipe, because the soname is
only known once the library is built. The class reads it with `objdump -p`.

### Rules

The class checks each package in `AUTO_LIBNAME_PKGS` (default: all of
`${PACKAGES}`). A package is renamed only if all of these are true:

1. It has files directly in `${libdir}` or `${base_libdir}` (subdirectories
   such as `/usr/lib/engines-3` do not count).
2. It has **no** files in `${bindir}`, `${sbindir}`, `${base_bindir}` or `${base_sbindir}`.
3. The `lib*.so*` files in it have **exactly one** `SONAME`. If there are
   several, `LEAD_SONAME` (a regex) must select exactly one; otherwise you get
   `NOTE: Multiple libraries (...) found and LEAD_SONAME not defined` and no rename.

The new name comes from the soname:

| SONAME | New package name | Rule |
|---|---|---|
| `libwhydemo.so.1` | `libwhydemo1` | name + major version |
| `libcrypto.so.3` | `libcrypto3` | |
| `libz.so.1` | `libz1` | |
| `libfoo2.so.3` | `libfoo2-3` | `-` inserted when the name already ends in a digit |

**Sibling packages are renamed too.** Each other package of the recipe has the
original package name replaced by the soname without its version (`libz`,
`libcrypto`), if its name contains it:

| Recipe | Result |
|---|---|
| `zlib` | `zlib` → `libz1`, `zlib-dev` → `libz-dev`, `zlib-doc` → `libz-doc` |
| `libwhydemo` | `libwhydemo` → `libwhydemo1`; `libwhydemo-dev` unchanged (already `libwhydemo`) |
| `openssl` | `libcrypto` → `libcrypto3`; `openssl-dev` unchanged (does not contain `libcrypto`) |
| `glibc` | `glibc` → `libc6`, `glibc-dev` → `libc6-dev`, set explicitly with `PKG:` in `glibc-package.inc`, not by the class |

Every rename is logged:

```console
$ grep 'debian: renaming' tmp/work/*/openssl/*/temp/log.do_package
NOTE: debian: renaming libcrypto to libcrypto3
NOTE: debian: renaming libssl to libssl3
```

### How dependencies still resolve

Recipes keep writing the **original** names (`RDEPENDS = "libcrypto"`). Two
mechanisms connect them to the renamed packages:

1. **The renamed package provides its old name.** `add_rprovides()` adds
   `RPROVIDES:<pkg> = "<pkg> (=<PKGV>)"`.
2. **Dependency lists are translated when the package is written.**
   `do_package_write_rpm` calls `mapping_rename_hook()` (`meta/lib/oe/packagedata.py`),
   which looks every dependency up in pkgdata and substitutes the renamed name.
   That needs the dependency's pkgdata to exist already, which is why
   `do_package_write_rpm` depends on `do_packagedata` of runtime dependencies
   and circular `RDEPENDS` do not work.

### Full example: openssl (a CLI tool plus two libraries)

The openssl recipe splits its output so the libraries can be renamed and
installed without the tool:

```bitbake
inherit lib_package ...    # adds ${PN}-bin before ${PN}; FILES:${PN}-bin = "${bindir}/* ${sbindir}/*"

PACKAGES =+ "libcrypto libssl openssl-conf ${PN}-engines ${PN}-misc ${PN}-ossl-module-legacy ${PN}-ossl-module-fips"

FILES:libcrypto = "${libdir}/libcrypto${SOLIBS}"
FILES:libssl = "${libdir}/libssl${SOLIBS}"
FILES:${PN}-engines = "${libdir}/engines-3"
FILES:${PN}-misc = "${libdir}/ssl-3/misc ${bindir}/c_rehash"

RRECOMMENDS:libcrypto += "openssl-conf ${PN}-ossl-module-legacy"
RDEPENDS:${PN}-bin += "openssl-conf"
```

What the class decided for each package. The `libs`/`bins` values are from the
`DEBUG: LIBNAMES: pkg ... libs N bins N sonames [...]` lines in
`log.do_package`:

| Package (recipe name) | Final name | Contents | libs / bins / SONAMEs | Result |
|---|---|---|---|---|
| `libcrypto` | **`libcrypto3`** | `/usr/lib/libcrypto.so.3` | 1 / 0 / `libcrypto.so.3` | renamed |
| `libssl` | **`libssl3`** | `/usr/lib/libssl.so.3` | 1 / 0 / `libssl.so.3` | renamed |
| `openssl-bin` | `openssl-bin` | `/usr/bin/openssl` | 0 / 1 / – | kept: no library |
| `openssl` | `openssl` | `/usr/lib/ssl-3/ct_log_list.cnf*` | 0 / 0 / – | kept |
| `openssl-dev` | `openssl-dev` | headers, `libcrypto.so`/`libssl.so` symlinks, `.pc` | 1 / 0 / none | kept: the dev symlinks point into other packages, so no SONAME is read |
| `openssl-engines` | `openssl-engines` | `/usr/lib/engines-3/*.so` | 0 / 0 / – | kept: subdirectory of `${libdir}` |
| `openssl-misc` | `openssl-misc` | `c_rehash` | 0 / 1 / – | kept |
| `openssl-conf` | `openssl-conf` | `/etc/ssl/openssl.cnf`, `/usr/lib/ssl-3/openssl.cnf*` | 0 / 0 / – | kept |

Dependencies as the recipe and pkgdata see them (original names) and as the
rpm sees them (translated). `rpm` here is the image's native sysroot copy;
ignore its `cannot open Packages database` warning:

```console
$ oe-pkgdata-util read-value RDEPENDS openssl-bin
openssl-conf glibc (>= 2.43+git0+1c9988e525) libcrypto (>= 3.5.8) libssl (>= 3.5.8)

$ RPM=tmp/work/qemuarm64-poky-linux/core-image-minimal/1.0/recipe-sysroot-native/usr/bin/rpm
$ $RPM -qp --requires tmp/deploy/rpm/cortexa57/openssl-bin-3.5.8-r0.cortexa57.rpm   # trimmed
libc6 >= 2.43+git0+1c9988e525
libcrypto.so.3()(64bit)
libcrypto3 >= 3.5.8
libssl.so.3()(64bit)
libssl3 >= 3.5.8
openssl-conf

$ $RPM -qp --provides tmp/deploy/rpm/cortexa57/libcrypto3-3.5.8-r0.cortexa57.rpm    # trimmed
libcrypto = 3.5.8              # old name, from add_rprovides()
libcrypto.so.3()(64bit)        # soname, from the rpm dependency generator
libcrypto3 = 3.5.8-r0          # its own (new) name
```

`libcrypto (>= 3.5.8)` was not written in the recipe: shlibdeps added it
because `/usr/bin/openssl` links `libcrypto.so.3`, and the rpm writer
translated it to `libcrypto3 >= 3.5.8`.

In this image, `kmod` links `libcrypto.so.3` (section 8, example C), so the
graph shows how renaming and the package split play out:

```console
$ grep -E -- '"libcrypto3" ->|-> "libcrypto3"' $BH/depends.dot | grep -v 'label="lib\|"libc6"\|/bin/sh'
"kmod" -> "libcrypto3" [label=">= 3.5.8"]
"libcrypto3" -> "openssl-conf" [style=dotted]
"libcrypto3" -> "openssl-ossl-module-legacy" [style=dotted]
"libkmod2" -> "libcrypto3" [label=">= 3.5.8"]
```

- `kmod`'s dependency on `libcrypto` became `libcrypto3`, and only the library
  (plus the recommended `openssl-conf`) is installed. `openssl-bin` and `libssl3`
  are not, because nothing requires them.
- `openssl-ossl-module-legacy` is **not** installed although the edge exists:
  the package is empty in this build, so it was never created
  (`tmp/pkgdata/qemuarm64/runtime/openssl-ossl-module-legacy.packaged` is
  missing). A recommendation of a package that does not exist is ignored.

**What the split buys.** If `libcrypto.so.3` were in the same package as
`/usr/bin/openssl`, rule 2 would fail: no rename, the package would stay
`openssl`, and `kmod` would pull the CLI tool into the image just to get the
library. If `libcrypto.so.3` and `libssl.so.3` shared one package, rule 3
would fail (two SONAMEs) unless the recipe set `LEAD_SONAME`. That is why
library recipes inherit `lib_package` or list their libraries as separate
packages with `PACKAGES =+`.

### Where each name appears

| Place | Name used |
|---|---|
| Recipe metadata, `bitbake-getvar`, the `RDEPENDS` you write | Original (`libcrypto`) |
| `oe-pkgdata-util read-value`, `find-path`, `tmp/pkgdata/*/runtime/<pkg>` | Original |
| rpm files, dnf, `log.do_rootfs`, image manifest, buildhistory, `BAD_RECOMMENDATIONS`, `PACKAGE_EXCLUDE` | **Renamed** (`libcrypto3`) |

```sh
oe-pkgdata-util lookup-pkg libcrypto        # -> libcrypto3
oe-pkgdata-util lookup-pkg -r libcrypto3    # -> libcrypto
ls -l tmp/pkgdata/qemuarm64/runtime-reverse/libcrypto3   # symlink -> ../runtime/libcrypto
```

### Pitfalls

- **Grepping image-side names in pkgdata, or the reverse, silently finds
  nothing.** Translate with `lookup-pkg` first.
- **The name can change between builds.** It comes from the built files, not
  the recipe. A version bump that changes the soname turns `libfoo1` into
  `libfoo2`; `buildhistory-diff` shows one removed and one added.
- **Adding a binary to a library package stops the renaming.** It falls back
  to its original name, and a `BAD_RECOMMENDATIONS` / `PACKAGE_EXCLUDE` entry
  using the renamed name quietly stops matching.
- **A second library in the package also stops the renaming**, unless
  `LEAD_SONAME` picks one.

### Controlling it

| Goal | How |
|---|---|
| Keep the original name for one package | `DEBIAN_NOAUTONAME:<pkg> = "1"` |
| Choose the name yourself | `DEBIANNAME:<pkg> = "..."`, or set `PKG:<pkg>` as glibc does |
| Pick the soname when a package has several | `LEAD_SONAME = "libfoo.so"` |
| Turn it off for the whole distro | Remove `debian` from `INHERIT_DISTRO`. This renames packages distro-wide, so lists that use the renamed names must be updated |

## Demo recipes and how to reproduce

The demo lives in `meta-hockey` and is off by default, so the normal
`core-image-minimal` is unchanged:

| File | Demonstrates |
|------|--------------|
| [recipes-demo/whydemo/libwhydemo_1.0.bb](../meta-hockey/recipes-demo/whydemo/libwhydemo_1.0.bb) | Shared library, renamed to `libwhydemo1`, installed only through shlibdeps |
| [recipes-demo/whydemo/whydemo-app_1.0.bb](../meta-hockey/recipes-demo/whydemo/whydemo-app_1.0.bb) | Links the library; `RDEPENDS` on a virtual name; `RRECOMMENDS`; ships a `-doc` package |
| [recipes-demo/whydemo/whydemo-plugin-foo_1.0.bb](../meta-hockey/recipes-demo/whydemo/whydemo-plugin-foo_1.0.bb) | `RPROVIDES:${PN} = "whydemo-plugin"` |
| [recipes-demo/whydemo/whydemo-extras_1.0.bb](../meta-hockey/recipes-demo/whydemo/whydemo-extras_1.0.bb) | Installed only as a recommendation |
| [recipes-demo/whydemo/packagegroup-whydemo.bb](../meta-hockey/recipes-demo/whydemo/packagegroup-whydemo.bb) | Packagegroup root in `IMAGE_INSTALL` |
| [recipes-core/images/core-image-minimal.bbappend](../meta-hockey/recipes-core/images/core-image-minimal.bbappend) | Under the `whydemo` override: adds the packagegroup, `doc-pkgs`, and `BAD_RECOMMENDATIONS += "xauth"` |
| [conf/fragments/why-demo.conf](../meta-hockey/conf/fragments/why-demo.conf) | Adds `whydemo` to `OVERRIDES` |

```sh
bitbake-config-build enable-fragment meta-hockey/why-demo
bitbake core-image-minimal
buildhistory-diff                       # what the demo added and removed
bitbake-config-build disable-fragment meta-hockey/why-demo
bitbake core-image-minimal              # back to normal
```

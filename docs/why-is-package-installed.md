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

Almost everything here reads buildhistory output. It is enabled in this build
through the `meta-hockey/buildhistory` fragment
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

`buildhistory-diff` compares the last two builds (or any two git revisions),
which is how you answer "why did this package *suddenly* appear?".

Without buildhistory you can still use `log.do_rootfs` and `oe-pkgdata-util`
(section 6), but you lose the graph.

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
> comes back empty.

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

`bitbake-getvar` without `--value` prints every file and line that touched the
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

A dashed edge with a plain-name label means the parent required a name, and
some other package `RPROVIDES` it:

```console
$ grep -- '-> "eudev"' $BH/depends.dot
"packagegroup-core-boot" -> "eudev" [label="udev"] [style=dashed]
$ oe-pkgdata-util read-value RPROVIDES eudev
hotplug udev
$ bitbake-getvar -r packagegroup-core-boot VIRTUAL-RUNTIME_dev_manager
#   set .../meta/conf/distro/include/init-manager-sysvinit.inc:6
#     [_defaultval] "udev"
```

So: `packagegroup-core-boot` RDEPENDS on `${VIRTUAL-RUNTIME_dev_manager}`, the
sysvinit init manager config sets that to `udev`, and `eudev` provides `udev`.
Change the provider by setting the `VIRTUAL-RUNTIME_*` variable in a distro
config, or with `PREFERRED_RPROVIDER_<name>` when several recipes provide the
same name (bitbake warns about multiple providers, and which one gets
installed is then up to the package manager).

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

Always confirm against `installed-package-names.txt`.

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

**Built is not installed.** `bitbake -g <image>` writes `task-depends.dot`,
the *build-time* graph. Recommendations are build dependencies too, so `xauth`
is built even when it is excluded from the image:

```console
$ bitbake -g core-image-minimal && oe-depends-dot -w -k xauth task-depends.dot
core-image-minimal -> packagegroup-core-ssh-dropbear -> dropbear -> xauth
```

Use `task-depends.dot` for "why is this recipe being built?" and
`depends.dot` for "why is this package in the image?".

**A solid versioned edge is usually not hand-written.** `"whydemo-app" -> "libwhydemo1" [label=">= 1.0"]`
comes from shlibs, not from the recipe. Check with 3b before searching the
layers for a `RDEPENDS` that does not exist.

**pkgdata and the image use different names.** See the renaming note in
section 1. `oe-pkgdata-util read-value RDEPENDS kmod` says `libcrypto`, the
image has `libcrypto3`.

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

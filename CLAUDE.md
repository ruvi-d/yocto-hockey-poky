# CLAUDE.md

Yocto (wrynose) dev environment set up with bitbake-setup. See `README.md` for
the repo layout and human setup steps.

## Where to make changes

- Local customisations go in `meta-hockey/` (tracked).
- `bitbake-builds/` is generated and untracked. Do not edit the upstream layers
  under `bitbake-builds/*/layers/`; use a bbappend in `meta-hockey` instead.
- Sources, layers and machines are defined in `hockey-poky.conf.json`.

## Running bitbake commands

Each Bash tool call is a fresh shell, so source the build environment and run
the command in the **same** call, under `bash` (the default shell is zsh):

```sh
bash -c '. /workspace/yocto-hockey-poky/bitbake-builds/<build>/build/init-build-env >/dev/null && bitbake-getvar --value MACHINE'
```

- Pick `<build>` from `ls bitbake-builds/` (e.g. `hockey-qemuarm64`,
  `hockey-qemuarm`). If exactly one exists, use it. If more than one exists
  and the user hasn't said which, ask. If none exists, ask before running
  `./setup.sh <config>`.
- `init-build-env` changes into the build directory, so paths after it are
  relative to `bitbake-builds/<build>/build`.
- `DL_DIR` and `SSTATE_DIR` come from the container environment (shared
  caches in `/workspace/caches`). Do not set them in `local.conf`.

Useful commands (all after sourcing as above):

| Purpose                          | Command                                        |
|----------------------------------|------------------------------------------------|
| Check a variable                 | `bitbake-getvar --value VAR [-r recipe]`       |
| Parse only (fast sanity check)   | `bitbake -p`                                   |
| Build a recipe or image          | `bitbake <recipe>`                             |
| Run one task                     | `bitbake -c <task> <recipe>`                   |
| Find which layer provides recipe | `bitbake-layers show-recipes <recipe>`         |

## Long builds and failures

- Image and toolchain builds can take far longer than the tool timeout. Run
  them in the background and add `-q` to keep output short.
- On failure, read the task log named in the error, under
  `tmp/work/<arch>/<recipe>/<version>/temp/log.do_<task>`.
- Only one bitbake server can use a build directory at a time. The VS Code
  BitBake extension may already be running one; if bitbake reports a lock,
  wait or ask the user. Do not kill bitbake processes.
- Do not run `bitbake -c cleanall`, delete `tmp/`, or touch the shared caches
  without asking: the caches are shared by all builds.

## runqemu

`runqemu slirp nographic` takes over the terminal and waits for a login, so do
not run it from the Bash tool. Ask the user to run it in a BitBake terminal.

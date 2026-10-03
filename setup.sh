#!/bin/sh
# Initialise a bitbake-setup build for one configuration in hockey-poky.conf.json
# and add this repo (and so meta-hockey) to the generated VS Code workspace.
#
# Usage: ./setup.sh <config>   e.g. ./setup.sh qemuarm64
set -e

if [ $# -ne 1 ]; then
    echo "Usage: $0 <config>   (e.g. qemuarm64, qemuarm)" >&2
    exit 1
fi

cd "$(dirname "$0")"
bitbake-setup init --non-interactive --init-vscode ./hockey-poky.conf.json "$1"

# bitbake-setup only adds the git repos under layers/ to the workspace. It keeps
# user-added folders on later updates, so adding this repo once is enough.
python3 - "bitbake-builds/hockey-$1/bitbake.code-workspace" <<'EOF'
import json, sys
path = sys.argv[1]
with open(path) as f:
    ws = json.load(f)
if not any(folder["path"] == "../../" for folder in ws["folders"]):
    ws["folders"].append({"name": "hockey-poky", "path": "../../"})
    with open(path, "w") as f:
        json.dump(ws, f, indent=4)
EOF

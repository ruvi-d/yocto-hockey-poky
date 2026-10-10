# nooelint: oelint.vars.outofcontext
IMAGE_FEATURES:append:poky = " ssh-server-dropbear"

# Demo for docs/why-is-package-installed.md, enabled by the
# meta-hockey/why-demo fragment.
IMAGE_INSTALL:append:whydemo = " packagegroup-whydemo"
# nooelint: oelint.vars.outofcontext
IMAGE_FEATURES:append:whydemo = " doc-pkgs"
BAD_RECOMMENDATIONS:append:whydemo = " xauth"

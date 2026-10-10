SUMMARY = "Demo packagegroup for docs/why-is-package-installed.md"
DESCRIPTION = "Listed in IMAGE_INSTALL by the why-demo fragment; pulls in whydemo-app."
LICENSE = "MIT"

inherit packagegroup

RDEPENDS:${PN} = "whydemo-app"

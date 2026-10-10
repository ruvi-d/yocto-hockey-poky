# nooelint: oelint.var.mandatoryvar.HOMEPAGE
SUMMARY = "Demo plugin for docs/why-is-package-installed.md"
DESCRIPTION = "Installed because whydemo-app RDEPENDS on whydemo-plugin, a \
virtual name that this package RPROVIDES."
LICENSE = "MIT"
# nooelint: oelint.var.licenseremotefile
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://foo.plugin"

S = "${UNPACKDIR}"

inherit allarch

RPROVIDES:${PN} = "whydemo-plugin"

do_install() {
    install -d ${D}${datadir}/${BPN}
    install -m 0644 ${S}/foo.plugin ${D}${datadir}/${BPN}/
}

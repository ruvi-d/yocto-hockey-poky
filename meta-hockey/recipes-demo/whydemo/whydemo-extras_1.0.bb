# nooelint: oelint.var.mandatoryvar.HOMEPAGE
SUMMARY = "Demo extras for docs/why-is-package-installed.md"
DESCRIPTION = "Installed only because whydemo-app RRECOMMENDS it."
LICENSE = "MIT"
# nooelint: oelint.var.licenseremotefile
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://extras.txt"

S = "${UNPACKDIR}"

inherit allarch

do_install() {
    install -d ${D}${datadir}/${BPN}
    install -m 0644 ${S}/extras.txt ${D}${datadir}/${BPN}/
}

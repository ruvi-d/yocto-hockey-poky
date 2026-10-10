# nooelint: oelint.var.mandatoryvar.HOMEPAGE
SUMMARY = "Demo shared library for docs/why-is-package-installed.md"
DESCRIPTION = "Provides libwhydemo.so.1. Nothing lists it in RDEPENDS: it is \
installed because whydemo-app links against it, and the debian class renames \
its package from libwhydemo to libwhydemo1."
LICENSE = "MIT"
# nooelint: oelint.var.licenseremotefile
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://libwhydemo.c \
           file://whydemo.h"

S = "${UNPACKDIR}"

do_compile() {
    ${CC} ${CFLAGS} ${LDFLAGS} -fPIC -shared -Wl,-soname,libwhydemo.so.1 \
        -o ${B}/libwhydemo.so.1.0 ${S}/libwhydemo.c
}

do_install() {
    install -d ${D}${libdir} ${D}${includedir}
    install -m 0755 ${B}/libwhydemo.so.1.0 ${D}${libdir}/
    ln -sf libwhydemo.so.1.0 ${D}${libdir}/libwhydemo.so.1
    ln -sf libwhydemo.so.1.0 ${D}${libdir}/libwhydemo.so
    install -m 0644 ${S}/whydemo.h ${D}${includedir}/
}

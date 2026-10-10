# nooelint: oelint.var.mandatoryvar.HOMEPAGE
SUMMARY = "Demo program for docs/why-is-package-installed.md"
DESCRIPTION = "Pulls in packages through each kind of runtime dependency: \
a shared library (automatic), a virtual name (RPROVIDES) and a \
recommendation (RRECOMMENDS). Its -doc package is installed by the doc-pkgs \
image feature."
LICENSE = "MIT"
# nooelint: oelint.var.licenseremotefile
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# Build-time only. The runtime dependency on libwhydemo1 is added
# automatically because the binary links against libwhydemo.so.1.
DEPENDS = "libwhydemo"

SRC_URI = "file://whydemo-app.c \
           file://README"

S = "${UNPACKDIR}"

# Hard dependency on a name no package has; whydemo-plugin-foo RPROVIDES it.
RDEPENDS:${PN} = "whydemo-plugin"

# Weak dependency: installed unless blocked by BAD_RECOMMENDATIONS or
# NO_RECOMMENDATIONS.
RRECOMMENDS:${PN} = "whydemo-extras"

do_compile() {
    ${CC} ${CFLAGS} ${LDFLAGS} -o ${B}/whydemo-app ${S}/whydemo-app.c -lwhydemo
}

do_install() {
    install -d ${D}${bindir} ${D}${docdir}/${BPN}
    install -m 0755 ${B}/whydemo-app ${D}${bindir}/
    install -m 0644 ${S}/README ${D}${docdir}/${BPN}/
}

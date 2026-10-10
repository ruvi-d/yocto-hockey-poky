SUMMARY = "Toy recipe for exploring sstate signatures"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://hello.c"
S = "${UNPACKDIR}"

# Override from local.conf to see a config change ripple into the signature.
DEMO_GREETING ?= "Hello from sstate-demo"
# Hook for the experiments below.
DEMO_EXTRA_CFLAGS ?= ""

do_compile() {
    ${CC} ${CFLAGS} ${LDFLAGS} ${DEMO_EXTRA_CFLAGS} -DGREETING='"${DEMO_GREETING}"' hello.c -o sstate-demo
}

do_install() {
    install -D -m 0755 sstate-demo ${D}${bindir}/sstate-demo
}

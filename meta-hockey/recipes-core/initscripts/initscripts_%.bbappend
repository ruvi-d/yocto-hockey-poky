# On qemu, runqemu configures networking via the kernel "ip=" argument, which
# publishes the DNS servers in /proc/net/pnp (resolv.conf format) but nothing
# writes /etc/resolv.conf. Point resolv.conf at /proc/net/pnp instead of an
# empty file.
# nooelint: oelint.vars.pathhardcode.localstatedir oelint.vars.noncoreoverride
do_install:append:qemuall() {
    sed -i -e 's@^f root root 0644 /var/run/resolv.conf none$@l root root 0644 /var/run/resolv.conf /proc/net/pnp@' \
        ${D}${sysconfdir}/default/volatiles/00_core
    grep -q '^l root root 0644 /var/run/resolv.conf /proc/net/pnp$' ${D}${sysconfdir}/default/volatiles/00_core
}

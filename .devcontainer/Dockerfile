FROM crops/yocto:ubuntu-24.04-base

# Enable passwordless sudo user to the 1000 uid user "ubuntu"
USER root
RUN echo 'ubuntu ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ubuntu && \
chmod 0440 /etc/sudoers.d/ubuntu

# Install build requirements
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        python3.12-venv=3.12.3* && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
# bitbake_setup 2.19.0 is the only viable version available on PyPI, so it does
# not match the bitbake 2.18 pinned in hockey-poky.conf.json.
# websockets is needed by the hashserv client.
RUN pip install --no-cache-dir "bitbake_setup==2.19.0" "websockets==17.1"

# Switch user to 1000 uid user "ubuntu"
USER ubuntu

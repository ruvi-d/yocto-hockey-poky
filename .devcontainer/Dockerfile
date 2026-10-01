FROM crops/yocto:ubuntu-24.04-base

# Enable passwordless sudo user to the 1000 uid user "ubuntu"
USER root
RUN echo 'ubuntu ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ubuntu && \
chmod 0440 /etc/sudoers.d/ubuntu

# Switch user to 1000 uid user "ubuntu"
USER ubuntu

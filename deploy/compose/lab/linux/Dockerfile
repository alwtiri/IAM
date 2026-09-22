# syntax=docker/dockerfile:1.7
# Lab Linux target for Phase 3 smoke tests (compose profile "lab"). NOT part of the platform and never deployed.
# Base: the Ubuntu 24.04 (noble) image already pinned for the platform runtime, so no extra base image is pulled.
FROM eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f
RUN apt-get update \
 && apt-get install -y --no-install-recommends openssh-server sudo libpam-modules-bin \
 && rm -rf /var/lib/apt/lists/* /etc/ssh/ssh_host_*
# Service account used by the platform (key-only) with least-privilege sudo for exactly the commands the provider runs.
RUN useradd -m -s /bin/bash svc-iam \
 && useradd -m -s /bin/bash -c "Alice Admin" alice && usermod -aG sudo alice \
 && useradd -m -s /bin/bash -c "Bob Builder" bob \
 && useradd -m -s /bin/bash -c "Carol Contractor" carol \
 && useradd -r -m -s /usr/sbin/nologin svc-app \
 && for u in alice bob carol; do echo "$u:$(head -c 18 /dev/urandom | base64)" | chpasswd; done \
 && mkdir -p /etc/ssh/authorized_keys
COPY sudoers-svc-iam /etc/sudoers.d/svc-iam
COPY sshd-lab.conf /etc/ssh/sshd_config.d/lab.conf
COPY entrypoint.sh /usr/local/bin/lab-entrypoint
RUN chmod 0440 /etc/sudoers.d/svc-iam && chmod 0755 /usr/local/bin/lab-entrypoint && visudo -c
EXPOSE 22
HEALTHCHECK --interval=5s --timeout=3s --retries=10 CMD ["/bin/sh", "-c", "test -f /run/sshd.pid"]
# sshd must start as root (privilege separation). This disposable lab fixture is excluded from the Semgrep/Trivy
# gates (ci.yml: deploy/compose/lab) because it is never shipped; the platform images keep their non-root rules.
ENTRYPOINT ["/usr/local/bin/lab-entrypoint"]

#!/usr/bin/env bash
# One-shot bootstrap for a FRESH Ubuntu 24.04 server. Run ONCE, as root:
#
#   scp deploy/server-setup.sh root@SERVER:
#   ssh root@SERVER 'bash server-setup.sh'
#
# What it does: system update, ufw (SSH+80/443 only), fail2ban, Docker, rclone,
# user "marel" (docker group, root's SSH keys), /opt/marel layout, nightly backup
# cron, disables SSH password login. Next steps are printed at the end
# (and documented in deploy/README.md §1-§3).

set -euo pipefail

[[ ${EUID} -eq 0 ]] || { echo "Run as root."; exit 1; }

echo "==> System update + base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get upgrade -y
apt-get install -y ufw fail2ban unattended-upgrades rclone

echo "==> Firewall: only SSH + HTTP/HTTPS (443/udp = HTTP/3)"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable

echo "==> Docker"
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh

echo "==> User 'marel' (deploy user, no password, root's SSH keys)"
id marel &>/dev/null || adduser --disabled-password --gecos "" marel
usermod -aG docker marel
mkdir -p /home/marel/.ssh
cp /root/.ssh/authorized_keys /home/marel/.ssh/authorized_keys
chown -R marel:marel /home/marel/.ssh
chmod 700 /home/marel/.ssh
chmod 600 /home/marel/.ssh/authorized_keys

echo "==> /opt/marel layout"
mkdir -p /opt/marel/backups /opt/marel/web/releases /opt/marel/uploads
chown -R marel:marel /opt/marel
chown 10001:10001 /opt/marel/uploads   # the app container writes as uid 10001

echo "==> Nightly backup cron for marel (02:15)"
(
    crontab -u marel -l 2>/dev/null | grep -v 'backup\.sh' || true
    echo "15 2 * * * /opt/marel/backup.sh nightly >> /opt/marel/backups/backup.log 2>&1"
) | crontab -u marel -

echo "==> SSH hardening: key-only login"
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl reload ssh

cat <<'EOF'

============================================================
 DONE. Next, from YOUR machine (deploy/README.md §1-§3):

 1. scp deploy/docker-compose.yml deploy/Caddyfile \
        deploy/deploy.sh deploy/backup.sh  marel@SERVER:/opt/marel/
    scp deploy/.env.example marel@SERVER:/opt/marel/.env
 2. ssh marel@SERVER 'chmod +x /opt/marel/*.sh && chmod 600 /opt/marel/.env'
    then edit /opt/marel/.env and fill in EVERY value.
 3. On the server: docker login ghcr.io -u alexdegustitch
    (GitHub PAT with read:packages)
 4. Point DNS (spikytail.com, www, api) at this server, then:
    cd /opt/marel && docker compose up -d db caddy
 5. Add DEPLOY_HOST / DEPLOY_USER / DEPLOY_SSH_KEY secrets to both
    GitHub repos and push master/main.
============================================================
EOF

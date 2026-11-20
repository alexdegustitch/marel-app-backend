#!/usr/bin/env bash
# Database (and uploads) backup.
#   /opt/marel/backup.sh            -> label "nightly" (cron)
#   /opt/marel/backup.sh pre-deploy -> called by deploy.sh before each deploy
#
# Output: /opt/marel/backups/marel_app-<timestamp>-<label>.dump  (pg_dump -Fc)
# plus a .sha256, plus - on Sundays - a tar of uploads/.
# If RCLONE_REMOTE is set in .env, every dump is also copied offsite.
#
# Retention on this disk: nightly 14 days, pre-deploy 30 days.
# Offsite retention is governed by the bucket (see deploy/README.md).

set -euo pipefail
cd /opt/marel

LABEL="${1:-nightly}"
STAMP="$(date +%F-%H%M%S)"
FILE="backups/marel_app-${STAMP}-${LABEL}.dump"

mkdir -p backups

echo "==> pg_dump -> ${FILE}"
docker compose exec -T db pg_dump -U marel -Fc marel_app > "${FILE}"
(cd backups && sha256sum "$(basename "${FILE}")" > "$(basename "${FILE}").sha256")

# Weekly uploads archive (product images etc.) - Sundays, nightly run only.
if [[ "${LABEL}" == "nightly" && "$(date +%u)" == "7" && -d uploads ]]; then
    tar czf "backups/uploads-${STAMP}.tgz" uploads
fi

# Offsite copy (Cloudflare R2 or any rclone remote), if configured in .env.
RCLONE_REMOTE="$(grep -E '^RCLONE_REMOTE=' .env 2>/dev/null | cut -d= -f2- || true)"
if [[ -n "${RCLONE_REMOTE}" ]] && command -v rclone >/dev/null; then
    echo "==> Offsite copy -> ${RCLONE_REMOTE}"
    rclone copy "${FILE}" "${RCLONE_REMOTE}"
    rclone copy "${FILE}.sha256" "${RCLONE_REMOTE}"
else
    echo "==> No RCLONE_REMOTE configured - dump stays on this disk only."
fi

# Local retention.
find backups -name '*-nightly.dump*' -mtime +14 -delete
find backups -name '*-pre-deploy.dump*' -mtime +30 -delete
find backups -name 'uploads-*.tgz' -mtime +35 -delete

echo "==> Done: $(du -h "${FILE}" | cut -f1)"

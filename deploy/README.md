# Produkcija — runbook

Sve što treba da se app digne, održava, vraća unazad i backup-uje. Fajlovi u ovom
folderu su izvor istine; na serveru žive u `/opt/marel/`.

## Arhitektura

```
                      ┌──────────────────────────── VPS (Ubuntu) ───────────────────────────┐
 spikytail.com ────► │ Caddy (TLS) ──► /opt/marel/web/current  (Vite build, symlink)       │
 api.spikytail.com ► │ Caddy ──► app:8080 (Spring Boot, GHCR image) ──► db:5432 (Postgres) │
                      │                      │                                              │
                      │                      └─► /opt/marel/uploads (slike)                 │
                      │ /opt/marel/backups  ◄── backup.sh (cron + pre-deploy)               │
                      └──────────────────────────────────────────────────────────────────────┘
```

- **Backend deploy**: push na `master` → GitHub Actions (testovi → Docker image na
  GHCR `:latest` + `:sha` → SSH → `deploy.sh <sha>`).
- **Web deploy**: push na `main` → Actions (`vite build` → rsync u `web/releases/<sha>`
  → symlink `web/current`).
- **Electron**: ručno `npm run dist:win` / `dist:mac` — prod URL-ovi se sami upeku
  iz `.env.production`.

## 1. Prvi put: priprema servera

Ubuntu 24.04 VPS (preporuka: Hetzner CX32 ili CPX31, lokacija Falkenstein/Nürnberg;
pri poručivanju dodaj svoj SSH javni ključ).

Ceo bootstrap radi jedna skripta — sa svoje mašine, na svež server:

```bash
scp deploy/server-setup.sh root@SERVER:
```

```bash
ssh root@SERVER 'bash server-setup.sh'
```

Ona: ažurira sistem, uključi ufw (samo SSH + 80/443) i fail2ban, instalira Docker
i rclone, napravi deploy korisnika `marel` (docker grupa, nasledi root-ove SSH
ključeve), složi `/opt/marel/{backups,web,uploads}`, upiše noćni backup cron
(02:15) i ugasi SSH login lozinkom.

**Hetzner Cloud Firewall (preporučeno, besplatno)**: Console → Firewalls →
Create Firewall → inbound **TCP 22, TCP 80, TCP 443, UDP 443** → Apply to server.
Docker objavljene portove upisuje direktno u iptables i time zaobilazi ufw, pa je
spoljni firewall drugi sloj zaštite. Naš compose ionako ne izlaže ništa osim
Caddy-ja (Postgres nema objavljen port, API je vezan na 127.0.0.1), ali ovaj sloj
štiti i od buduće greške u compose-u.

Zatim fajlovi i secrets:

```bash
scp deploy/docker-compose.yml deploy/Caddyfile deploy/deploy.sh deploy/backup.sh marel@SERVER:/opt/marel/
```

```bash
scp deploy/.env.example marel@SERVER:/opt/marel/.env
```

```bash
ssh marel@SERVER 'chmod +x /opt/marel/*.sh && chmod 600 /opt/marel/.env'
```

Na serveru: `nano /opt/marel/.env` — popuni SVE vrednosti (uputstva su u fajlu),
pa jednokratni login na GHCR (privatan registry; GitHub PAT sa `read:packages`):

```bash
docker login ghcr.io -u alexdegustitch
```

**DNS pre prvog starta**: `spikytail.com`, `www.spikytail.com` i
`api.spikytail.com` → A record na IP servera (Caddy tek tad može da izda
sertifikate).

## 2. GitHub podešavanje (jednokratno, oba repo-a)

Generiši poseban deploy ključ na svojoj mašini:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/marel-deploy -C "github-actions-deploy" -N ""
ssh-copy-id -i ~/.ssh/marel-deploy.pub marel@SERVER
```

U **oba** repo-a (Settings → Secrets and variables → Actions) dodaj:

| Secret | Vrednost |
|---|---|
| `DEPLOY_HOST` | IP ili hostname servera |
| `DEPLOY_USER` | `marel` |
| `DEPLOY_SSH_KEY` | sadržaj `~/.ssh/marel-deploy` (privatni ključ) |

## 3. Prvi deploy

```bash
cd /opt/marel
docker compose up -d db caddy
```

**Prazna baza**: ništa ručno — Flyway na prvom startu aplikacije sâm izgradi celu
šemu (V1 baseline → najnovija).

**Prenos postojećih podataka** (dump je `pg_dump -Fc`, isti format kao
`backups/marel_app-*-pre-V70.dump`):

```bash
docker compose exec -T db pg_restore -U marel -d marel_app --no-owner --no-privileges < marel_app-YYYY-MM-DD.dump
```

Zatim push na `master` (backend) i `main` (frontend) — Actions odradi ostalo.
Provera:

```bash
curl https://api.spikytail.com/actuator/health   # {"status":"UP"}
```

Smoke test: login, unos u karton, recalc se vidi uživo (WebSocket), PDF obračuna.

## 4. Svakodnevni tok (dev → prod)

1. Razvijaš lokalno kao i do sada (localhost, lokalni `.env`).
2. `git push` na `master`/`main`.
3. Actions: testovi → deploy. Backend deploy sam snima **pre-deploy dump** baze i
   odbija da proglasi uspeh dok health ne kaže UP.

Ako hoćeš push bez deploy-a: radi na feature grani — deploy ide samo sa
`master`/`main`.

## 5. Rollback

**Backend** (≈30 sekundi, bilo koja ranije deploy-ovana verzija):

```bash
/opt/marel/deploy.sh <stariji-git-sha>
```

Sha nađeš u GitHub Actions istoriji ili `git log`; `deploy.sh` ispisuje prethodni
tag pri svakom deploy-u.

**Web frontend**:

```bash
cd /opt/marel/web && ls releases/        # poslednjih 5 build-ova stoji tu
ln -sfn releases/<stariji-sha> current
```

**Baza**: Flyway migracije se NE vraćaju unazad. Pravila:

- migracije piši unapred-kompatibilno (dodaj kolonu/tabelu; brisanje tek u nekoj
  kasnijoj verziji kad stari kod više nije kandidat za rollback);
- rollback koda tada ne traži nikakvu intervenciju u bazi;
- za katastrofu postoji pre-deploy dump: `pg_restore --clean` (poslednje
  sredstvo — briše sve unešeno posle dump-a!).

## 6. Backup i restore

- **Noćni cron** (02:15): `pg_dump -Fc` + sha256; nedeljom i tar `uploads/`.
- **Pre svakog deploy-a**: automatski dump (`deploy.sh`).
- **Offsite**: postavi `RCLONE_REMOTE` u `.env` + `rclone config` (Cloudflare R2
  bucket, S3-kompatibilan; `apt-get install rclone`). Backup na istom disku nije
  backup.
- **Retencija lokalno**: nightly 14 dana, pre-deploy 30 dana.
- **Mesečni restore test** (backup koji nikad nije vraćen ne postoji):

```bash
docker compose exec -T db createdb -U marel restore_test
docker compose exec -T db pg_restore -U marel -d restore_test --no-owner < backups/<najnoviji>.dump
docker compose exec -T db psql -U marel -d restore_test -c "select count(*) from work_logs"
docker compose exec -T db dropdb -U marel restore_test
```

## 7. Flyway — pravila

- Nova izmena šeme = novi fajl `src/main/resources/db/migration/V<n>__opis.sql`.
- **Nikad ne menjaj već primenjenu migraciju** (checksum u `flyway_schema_history`
  → app odbija start). Ispravka = nova V-migracija.
- Primenjuje se automatski na startu; `ddl-auto=validate` je drugi ček.
- Deploy sa migracijom se ničim ne razlikuje — pre-deploy dump je osiguranje.

## 8. Electron buildovi za radnike

```bash
npm run dist:win     # Windows .exe (pokreće se na Windows mašini ili uz wine)
npm run dist:mac     # macOS
```

`.env.production` u repo-u garantuje da paket gađa produkciju. Instalere deli
kroz GitHub Releases. (Auto-update je sledeći korak kad zatreba — electron-updater.)

## 9. Monitoring

- `https://api.spikytail.com/actuator/health` — javan, vraća samo UP/DOWN.
- `info`/`metrics` — samo `developer` nalog (postavi `BOOTSTRAP_DEVELOPER_PASSWORD`).
- Postavi **UptimeRobot/Better Stack** (besplatno) da pinguje health URL i šalje
  mejl kad padne.
- Logovi: `docker compose logs -f app` ; recalc metrike idu u log na 60 s.
- Disk: `df -h` povremeno — dump-ovi i image-i umeju da ga napune
  (`docker system prune -f` čisti stare image-e; deploy.sh to već radi).

## 10. Go-live checklist

- [ ] DNS (3 rekorda) pokazuje na server
- [ ] `.env` popunjen NOVIM vrednostima (JWT_SECRET, lozinke — ništa sa dev mašine)
- [ ] Google Cloud Console: prod redirect URI registrovan
- [ ] Postmark: domen verifikovan (DKIM/Return-Path), MAIL_* popunjen
- [ ] `docs/pending-migrations` (V64 contract) — odluka doneta
- [ ] GitHub secrets u oba repo-a
- [ ] Prvi deploy prošao, health UP, smoke test rukom
- [ ] Noćni backup cron upisan; probni `./backup.sh` izvršen; offsite podešen
- [ ] Probni rollback izveden (deploy.sh sa prethodnim sha)
- [ ] Uptime monitor pinguje health
- [ ] Electron build podeljen radnicima

# Hosting: the site on one host, the API on another

The chosen arrangement is **frontend on `https://dooklytics.com`, backend on
`https://api.dooklytics.com`**. This document says what that decision costs and
what has to be set for it to work.

## Why this arrangement needs CORS at all

CORS is a rule the BROWSER enforces. It compares the origin of the page with the
origin of the request, where an origin is **scheme + host + port** and all three
must match.

`dooklytics.com` and `api.dooklytics.com` differ in host, so every call the site
makes to the API is cross-origin and the API must say, in a response header, that
it accepts the site. That is `app.security.cors.allowed-origins`.

Serving the API under `https://dooklytics.com/api` through a reverse proxy would
have made them the same origin and removed CORS from the picture entirely. It is
the simpler arrangement and is worth knowing about; it was not the one chosen.

## Why the refresh cookie still works

Cross-ORIGIN is not the same as cross-SITE. Both hosts sit under the registrable
domain `dooklytics.com`, so they are the same site, and a `SameSite=Lax` cookie
is still sent on requests between them.

So **`SameSite` stays `Lax`.** `None` is only needed if the API ever moves to an
unrelated domain, and it would give up the CSRF protection `Lax` provides.

The cookie is host-only — no `Domain` attribute. It is set by whichever host
serves `/api/auth`, which is the API host, and sent back to that same host. That
is exactly what is wanted. Adding `Domain=.dooklytics.com` would widen it to
every subdomain, which is more exposure than this needs.

Two things must hold or the session dies at the first token refresh:

* the browser must send credentials — `apiClient.ts` already sets
  `credentials: 'include'`;
* the API must answer `Access-Control-Allow-Credentials: true` with a NAMED
  origin. A wildcard is rejected outright when credentials are involved, which
  is why the origin has to be listed and cannot be `*`.

## What to set

### Backend (environment variables)

Every one of these has a development default in `application.properties`; the
variable overrides it. Setting nothing keeps the development behaviour.

```
WEB_APP_URL=https://dooklytics.com
CORS_ALLOWED_ORIGINS=https://dooklytics.com
REFRESH_COOKIE_SECURE=true
REFRESH_COOKIE_SAME_SITE=Lax
GOOGLE_OAUTH_REDIRECT_URI=https://api.dooklytics.com/api/auth/google/callback
```

`WEB_APP_URL` deserves attention. It is what every deep link in a notification
e-mail is built from — `NotificationEmailComposer` composes `webAppUrl + "/#" +
route`. A wrong value is baked into mails that have already been sent, so it is
not something a later correction repairs.

`GOOGLE_OAUTH_REDIRECT_URI` must match what is registered for the OAuth client in
Google Cloud Console, character for character.

### Frontend (build-time)

Vite substitutes these when it BUILDS. They are not read at run time, so
changing them means building again.

```
VITE_API_BASE=https://api.dooklytics.com/api
VITE_WS_URL=wss://api.dooklytics.com/ws
```

Note `wss://`, not `ws://`. A page served over HTTPS may not open an insecure
socket: the browser refuses it as mixed content and does so QUIETLY. The symptom
is not an error — it is that recalculation and report updates stop arriving,
with nothing on screen to say why.

## nginx

The site — a static bundle. The application uses a HASH router, so the server
only ever sees `GET /` and no SPA fallback rule is needed. `try_files` is kept
anyway so that adding one later is a one-line change rather than a debugging
session.

```nginx
server {
    listen 443 ssl http2;
    server_name dooklytics.com;

    root /var/www/dooklytics/dist-react;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    # Hashed filenames, so these may be cached hard. index.html may NOT be, or a
    # browser keeps loading yesterday's bundle after a deploy.
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location = /index.html {
        add_header Cache-Control "no-cache";
    }
}
```

The API — a reverse proxy to Spring on 8080.

```nginx
server {
    listen 443 ssl http2;
    server_name api.dooklytics.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        # Spring needs this to know the request arrived over HTTPS. Without it a
        # cookie marked Secure is set on what the application believes is a plain
        # HTTP request, and redirects come back as http://.
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # The STOMP socket. It is a plain WebSocket upgrade — no SockJS — and needs
    # the two upgrade headers plus a read timeout long enough to outlast a quiet
    # connection. The default 60s would drop an idle socket and the client would
    # reconnect every minute for no reason.
    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host       $host;
        proxy_read_timeout 3600s;
    }
}
```

Spring must be told to trust those forwarded headers, or it will not know the
request came in over HTTPS:

```
server.forward-headers-strategy=native
```

`/actuator` is reachable through this proxy and is restricted to the `developer`
role by `SecurityConfig`; `/actuator/health` is open, which is what a monitor
polls. If the API host is public, consider blocking `/actuator` at nginx as well
— defence in depth, since it is operational detail about the running machine.

## Verifying it before trusting it

```bash
# Should answer 200 with Access-Control-Allow-Origin naming the site.
curl -s -o /dev/null -D - -H "Origin: https://dooklytics.com" \
  https://api.dooklytics.com/api/departments | grep -i "access-control"

# Should answer 403. An origin that is not listed must be refused, and seeing
# this refusal is how you know the rule is switched on at all.
curl -s -o /dev/null -w "%{http_code}\n" -H "Origin: https://evil.example" \
  https://api.dooklytics.com/api/departments
```

## The desktop build does NOT work under this arrangement

`main.ts` loads the packaged UI with `loadFile()`, which means the window's
origin is `file://` and every request it makes carries `Origin: null`.

This was measured, not assumed:

```
$ curl -o /dev/null -D - -H "Origin: null" http://localhost:8080/api/departments
HTTP/1.1 403
```

Against a running server, `null` is refused. So a packaged desktop application
gets 403 on every API call, while the same code in development works — because
in development Electron loads `http://localhost:5123`, which IS an allowed
origin. The failure appears only in the packaged build.

Three ways out, in the order they are worth considering:

1. **Point the desktop app at the deployed site.** `loadURL('https://dooklytics.com')`
   instead of `loadFile()`. The origin becomes the site's, which is already
   allowed, and there is one bundle to keep in step instead of two. It also means
   the desktop app needs the network to start.
2. **Register a privileged `app://` scheme** in Electron, load the bundle through
   it, and add that origin to `CORS_ALLOWED_ORIGINS`. Keeps the app working
   offline-ish and gives it a real, nameable origin.
3. **Allow the literal origin `null`.** It works and it is a bad trade: any local
   HTML file the user opens would then be allowed to call the API, and the
   HttpOnly refresh cookie would go with the request. Listed so that it is
   rejected knowingly rather than discovered later.

Nothing here has been changed — the choice belongs with whoever decides whether
the desktop application has a future now that there is a website.

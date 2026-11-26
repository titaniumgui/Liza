"""Liza web: a tiny local server for the Liza browser app.

Sign-in works like the Android app: you sign in on EZVIZ's own page and
the session token comes back to this server. Your password never passes
through it, and no AppSecret is needed.

The server keeps the EZVIZ session on this laptop (session.json), forwards
a fixed list of EZVIZ API calls, and serves the web page. It listens on
127.0.0.1 only.

Run:  python server.py            (then open http://localhost:8790)
      python server.py --check    (test the saved session and device list)
"""
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
SESSION_FILE = os.path.join(ROOT, "session.json")

# Region -> API server used by the EZVIZ web player and Open API.
REGION_DOMAINS = {
    "Europe": "https://ieuopen.ezvizlife.com",
    "Asia": "https://isgpopen.ezvizlife.com",
    "North America": "https://iusopen.ezvizlife.com",
    "South America": "https://isaopen.ezvizlife.com",
    "Russia": "https://irusopen.ezvizru.com",
}
# Region -> (sign-in server, areaId), matching the Android app's ServerRegion.
AUTH = {
    "Europe": ("https://ieuopenauth.ezvizlife.com", 25),
    "Asia": ("https://isgpopenauth.ezvizlife.com", 10),
    "North America": ("https://iusopenauth.ezvizlife.com", 15),
    "South America": ("https://isaopenauth.ezvizlife.com", 20),
    "Russia": ("https://irusopenauth.ezvizru.com", 5),
}

# The only EZVIZ calls the page may make. Name -> Open API path.
ALLOWED = {
    "deviceList": "/api/lapp/device/list",
    "deviceInfo": "/api/lapp/device/info",
    "cameraList": "/api/lapp/camera/list",
    "alarmList": "/api/lapp/alarm/list",
    "deviceAlarms": "/api/lapp/alarm/device/list",
    "defenceSet": "/api/lapp/device/defence/set",
    "nameUpdate": "/api/lapp/device/name/update",
    "recordsByTime": "/api/lapp/video/by/time",
    "mirror": "/api/lapp/device/ptz/mirror",
    "capture": "/api/lapp/device/capture",
}
# Newer v3 calls: GET, with the token and serial sent as headers.
ALLOWED_V3 = {
    "storageStatus": "/api/v3/device/format/status",
}


CONFIG_FILE = os.path.join(ROOT, "config.json")
DEFAULT_CONFIG = {"appKey": "", "appSecret": "", "region": "Europe", "port": 8790}


def load_config():
    """Read config.json, or start with defaults (the app then shows its setup screen)."""
    cfg = dict(DEFAULT_CONFIG)
    if os.path.isfile(CONFIG_FILE):
        with open(CONFIG_FILE, encoding="utf-8") as f:
            cfg.update(json.load(f))
    if "PASTE" in (cfg.get("appSecret") or ""):
        cfg["appSecret"] = ""
    return cfg


def save_config(cfg):
    tmp = CONFIG_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    os.replace(tmp, CONFIG_FILE)


def token_url(region):
    # AppKeys are registered per region: the global open.ezvizlife.com
    # answers 10017 "AppKey doesn't exist" for a Europe AppKey.
    return REGION_DOMAINS.get(region, REGION_DOMAINS["Europe"]) + "/api/lapp/token/get"


def post_form(url, fields, timeout=20):
    data = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def parse_signin_address(url):
    """Pull the session fields out of EZVIZ's post-sign-in address.

    The fields may be in the query (?a=b), the fragment (#a=b), or both,
    and may be percent-encoded more than once.
    """
    url = url.strip()
    for _ in range(2):
        if "%26" in url or "%3D" in url:
            url = urllib.parse.unquote(url)
    parts = urllib.parse.urlsplit(url)
    params = {}
    for chunk in (parts.query, parts.fragment):
        if "?" in chunk:
            chunk = chunk.split("?", 1)[1]
        params.update(urllib.parse.parse_qsl(chunk))
    aliases = {"accessToken": "access_token", "token": "access_token", "expireTime": "expires_in",
               "refreshToken": "refresh_token", "area_domain": "areaDomain"}
    for src, dst in aliases.items():
        if src in params and dst not in params:
            params[dst] = params[src]
    if "access_token" not in params:
        import re
        m = re.search(r"\bat\.[A-Za-z0-9\-]+", url)
        if m:
            params["access_token"] = m.group(0)
    return params


class NotSignedIn(Exception):
    pass


class Ezviz:
    """Holds the EZVIZ session from the user's own sign-in."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.lock = threading.Lock()
        self.session = {}
        if os.path.isfile(SESSION_FILE):
            try:
                with open(SESSION_FILE, encoding="utf-8") as f:
                    self.session = json.load(f)
            except Exception:
                self.session = {}

    # --- sign-in -------------------------------------------------------
    def login_url(self, callback):
        auth, area = AUTH.get(self.cfg.get("region"), AUTH["Europe"])
        query = urllib.parse.urlencode({
            "response_type": "refresh_token", "client_id": self.cfg["appKey"],
            "bundleId": "app.liza.android", "view_logo": 1, "view_reg": 0,
            # EZVIZ rejects unregistered return addresses (error 1016), so use
            # "default" like the Android SDK; EZVIZ then shows /oauth/success
            # with the session in its address, which the user pastes back.
            "redirect_uri": "default", "scope": "xx", "state": "liza",
            "v": "mobilezx", "sign": "lizaweb", "client_type": 13,
            "areaId": area, "timesnap": int(time.time() * 1000),
        })
        return f"{auth}/oauth/authorize?{query}"

    def save_from_params(self, params):
        token = params.get("access_token")
        if not token:
            raise ValueError("No access_token in the address.")
        now_ms = int(time.time() * 1000)
        raw = int(params.get("expires_in") or 0)
        # expires_in may be seconds from now or an absolute ms timestamp.
        if raw > 10**12:
            expires = raw
        elif raw > 0:
            expires = now_ms + raw * 1000
        else:
            expires = now_ms + 7 * 86400_000
        domain = params.get("areaDomain") or REGION_DOMAINS.get(self.cfg.get("region"), "")
        with self.lock:
            self.session = {
                "accessToken": token, "expires": expires,
                "refreshToken": params.get("refresh_token"),
                "apiDomain": domain.rstrip("/"),
                "authDomain": (params.get("authDomain") or "").rstrip("/"),
                "openId": params.get("open_id"), "signedInAt": now_ms,
            }
            with open(SESSION_FILE, "w", encoding="utf-8") as f:
                json.dump(self.session, f)

    def has_secret(self):
        secret = self.cfg.get("appSecret") or ""
        return bool(secret) and "PASTE" not in secret

    def public_config(self):
        """What the page may see: never the secret itself."""
        return {"appKey": self.cfg.get("appKey", ""), "region": self.cfg.get("region", "Europe"),
                "hasSecret": self.has_secret(), "regions": list(AUTH),
                "configured": bool(self.cfg.get("appKey"))}

    def update_config(self, body):
        key = str(body.get("appKey", self.cfg.get("appKey", ""))).strip()
        region = str(body.get("region", self.cfg.get("region", "Europe")))
        if not key or len(key) < 16 or not key.isalnum():
            raise ValueError("That AppKey doesn't look right. Copy it again from the EZVIZ Open Platform console.")
        if region not in AUTH:
            raise ValueError("Unknown region.")
        secret = self.cfg.get("appSecret", "")
        if body.get("clearSecret"):
            secret = ""
        elif str(body.get("appSecret", "")).strip():
            secret = str(body["appSecret"]).strip()
            if len(secret) < 16 or not secret.isalnum():
                raise ValueError("That AppSecret doesn't look right. Copy it again from the EZVIZ Open Platform console.")
        changed = key != self.cfg.get("appKey") or region != self.cfg.get("region") or secret != self.cfg.get("appSecret", "")
        new_cfg = dict(self.cfg, appKey=key, region=region, appSecret=secret)
        if secret and changed:
            # Check the AppKey/AppSecret pair with EZVIZ before saving it.
            res = post_form(token_url(region), {"appKey": key, "appSecret": secret})
            if str(res.get("code")) != "200":
                raise ValueError(f"EZVIZ didn't accept that AppKey and AppSecret (error {res.get('code')}: {res.get('msg')}).")
        save_config(new_cfg)
        self.cfg.clear()
        self.cfg.update(new_cfg)
        if changed:
            self.sign_out()  # old session belongs to the old key/region/method

    def ensure_session(self):
        """With an AppSecret in config.json, get or renew the token automatically."""
        if not self.has_secret():
            return
        with self.lock:
            fresh = self.session.get("accessToken") and time.time() * 1000 < self.session.get("expires", 0) - 86400_000
        if fresh:
            return
        res = post_form(token_url(self.cfg.get("region")),
                        {"appKey": self.cfg["appKey"], "appSecret": self.cfg["appSecret"]})
        if str(res.get("code")) != "200":
            raise NotSignedIn(f"AppSecret sign-in failed (EZVIZ error {res.get('code')}: {res.get('msg')}).")
        data = res["data"]
        self.save_from_params({"access_token": data["accessToken"], "expires_in": data.get("expireTime"),
                               "areaDomain": data.get("areaDomain")})
        with self.lock:
            self.session["source"] = "appSecret"

    def sign_out(self):
        with self.lock:
            self.session = {}
            if os.path.isfile(SESSION_FILE):
                os.remove(SESSION_FILE)

    def signed_in(self):
        return bool(self.session.get("accessToken")) and time.time() * 1000 < self.session.get("expires", 0)

    # --- API -------------------------------------------------------------
    @property
    def api_domain(self):
        return self.session.get("apiDomain") or REGION_DOMAINS.get(self.cfg.get("region"), "")

    def call(self, name, params):
        self.ensure_session()
        if not self.signed_in():
            raise NotSignedIn("Sign in to EZVIZ first.")
        if name in ALLOWED_V3:
            headers = {k: str(v) for k, v in params.items()}
            headers["accessToken"] = self.session["accessToken"]
            req = urllib.request.Request(self.api_domain + ALLOWED_V3[name], headers=headers)
            with urllib.request.urlopen(req, timeout=20) as resp:
                raw = json.loads(resp.read().decode("utf-8"))
            meta = raw.get("meta") or {}
            res = {"code": str(meta.get("code")), "msg": meta.get("message"), "data": raw.get("data")}
        else:
            fields = dict(params, accessToken=self.session["accessToken"])
            res = post_form(self.api_domain + ALLOWED[name], fields)
        # 10002: token expired or invalid. (10001 is a bad parameter, not a sign-out.)
        if str(res.get("code")) == "10002":
            raise NotSignedIn("EZVIZ session expired. Sign in again.")
        return res

    def player_domain(self):
        return REGION_DOMAINS.get(self.cfg.get("region"), self.api_domain)


class Handler(SimpleHTTPRequestHandler):
    ezviz = None

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=os.path.join(ROOT, "public"), **kwargs)

    def log_message(self, fmt, *args):
        first = str(args[0]) if args else ""
        if "/api/" in first or "/auth/" in first:
            # Never log query strings: the sign-in callback carries the token.
            sys.stderr.write("%s\n" % first.split("?")[0])

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def send_json(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def serve_vendor(self, path):
        rel = urllib.parse.unquote(path)[len("/vendor/"):]
        base = os.path.join(ROOT, "vendor")
        target = os.path.normpath(os.path.join(base, rel))
        if not target.startswith(base) or not os.path.isfile(target):
            return self.send_error(404)
        ctype = "application/wasm" if target.endswith(".wasm") else self.guess_type(target)
        with open(target, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def request_allowed(self, post=False):
        """Only answer pages served by Liza itself on this laptop.

        The Host check blocks DNS-rebinding; the Origin check blocks other
        websites from posting to this server (for example to change config).
        """
        port = self.server.server_address[1]
        allowed = {f"localhost:{port}", f"127.0.0.1:{port}"}
        if self.headers.get("Host") not in allowed:
            return False
        origin = self.headers.get("Origin")
        if post and origin and origin not in {f"http://{h}" for h in allowed}:
            return False
        return True

    def do_GET(self):
        if not self.request_allowed():
            return self.send_error(403)
        path, _, qs = self.path.partition("?")
        if path.startswith("/vendor/"):
            return self.serve_vendor(path)
        if path == "/api/config":
            return self.send_json(self.ezviz.public_config())
        if path == "/auth/start":
            if not self.ezviz.cfg.get("appKey"):
                self.send_response(302)
                self.send_header("Location", "/")
                self.end_headers()
                return
            port = self.server.server_address[1]
            self.send_response(302)
            self.send_header("Location", self.ezviz.login_url(f"http://localhost:{port}/auth/callback"))
            self.end_headers()
            return
        if path == "/auth/callback":
            params = dict(urllib.parse.parse_qsl(qs))
            try:
                self.ezviz.save_from_params(params)
                self.send_response(302)
                self.send_header("Location", "/")
                self.end_headers()
            except Exception as e:
                self.send_json({"error": str(e), "receivedKeys": sorted(params)}, 400)
            return
        if path == "/api/session":
            ez = self.ezviz
            try:
                ez.ensure_session()
            except Exception as e:
                sys.stderr.write(f"{e}\n")
            if not ez.signed_in():
                return self.send_json({"signedIn": False})
            return self.send_json({"signedIn": True, "accessToken": ez.session["accessToken"],
                                   "domain": ez.player_domain(), "expires": ez.session["expires"],
                                   "method": "appSecret" if ez.has_secret() else "signIn"})
        return super().do_GET()

    def do_POST(self):
        if not self.request_allowed(post=True):
            return self.send_json({"error": "forbidden"}, 403)
        if self.path == "/api/config":
            try:
                self.ezviz.update_config(self.read_json())
                return self.send_json(dict(self.ezviz.public_config(), ok=True))
            except Exception as e:
                return self.send_json({"error": str(e)}, 400)
        if self.path == "/auth/paste":
            # The user pastes the address EZVIZ showed after sign-in.
            params = {}
            try:
                params = parse_signin_address(str(self.read_json().get("url", "")))
                self.ezviz.save_from_params(params)
                return self.send_json({"ok": True})
            except Exception as e:
                # Report field names only, never values.
                return self.send_json({"error": str(e), "fieldsFound": sorted(params)}, 400)
        if self.path == "/auth/logout":
            self.ezviz.sign_out()
            return self.send_json({"ok": True})
        if not self.path.startswith("/api/ezviz/"):
            return self.send_error(404)
        name = self.path[len("/api/ezviz/"):]
        if name not in ALLOWED and name not in ALLOWED_V3:
            return self.send_json({"error": "not allowed"}, 403)
        try:
            params = {k: v for k, v in self.read_json().items() if v is not None}
            return self.send_json(self.ezviz.call(name, params))
        except NotSignedIn as e:
            return self.send_json({"error": str(e), "signedOut": True}, 401)
        except Exception as e:
            return self.send_json({"error": str(e)}, 502)


def check(cfg):
    ez = Ezviz(cfg)
    if "--appsecret" in sys.argv:
        if not ez.has_secret():
            sys.exit("No appSecret in config.json yet.")
        ez.session = {}  # force a fresh AppSecret token for the test
    ez.ensure_session()
    print("Session source:", ez.session.get("source", "sign-in"))
    if not ez.signed_in():
        sys.exit("Not signed in yet. Start the server and use Sign in with EZVIZ.")
    print("Session OK. API domain:", ez.api_domain, "| expires:",
          time.strftime("%Y-%m-%d %H:%M", time.localtime(ez.session["expires"] / 1000)))
    res = ez.call("deviceList", {"pageStart": 0, "pageSize": 20})
    print("deviceList code:", res.get("code"), res.get("msg"))
    for d in res.get("data") or []:
        print(" - status", d.get("status"), "| defence", d.get("defence"),
              "| encrypted", d.get("isEncrypt"), "| model", d.get("model"))


def main():
    cfg = load_config()
    if "--check" in sys.argv:
        return check(cfg)
    Handler.ezviz = Ezviz(cfg)
    port = int(cfg.get("port", 8790))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"Liza web is running at http://localhost:{port}  (Ctrl+C to stop)")
    if not cfg.get("appKey"):
        print("First run: open that address to enter your EZVIZ AppKey (and AppSecret, if you have one).")
    server.serve_forever()


if __name__ == "__main__":
    main()

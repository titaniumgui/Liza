"""Check that every user-facing English string has an Arabic translation.

    python tools/check_i18n.py           # report missing and unused keys
    python tools/check_i18n.py --list    # print every key found in the code

English text in the code is the lookup key:
  - Liza web: t("…") in web/public/app.js, data-i18n* elements in
    web/public/index.html, and the error messages web/server.py sends.
  - Android: tr("…") in app/src/main/java/app/liza/android/*.java.
Translations: web/public/i18n/ar.json and app/src/main/res/raw/ar.json.
Exit code 1 if anything is missing or a placeholder doesn't match.
"""
import glob
import json
import os
import re
import sys
from html.parser import HTMLParser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# A double-quoted JS/Java string literal (with escapes), as the first argument of t()/tr().
LIT = r'"((?:[^"\\]|\\.)*)"'


def unescape(s):
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def calls(text, fn):
    return {unescape(m.group(1)) for m in re.finditer(r"\b" + fn + r"\(\s*" + LIT, text)}


class Static(HTMLParser):
    """Collect data-i18n* strings from index.html, as the browser would see them."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.keys = set()
        self.stack = []  # [mode, tag, depth, parts]

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        for attr, name in (("data-i18n-ph", "placeholder"), ("data-i18n-title", "title"), ("data-i18n-aria", "aria-label")):
            if attr in a and a.get(name):
                self.keys.add(a[name])
        for frame in self.stack:
            if frame[0] == "html":
                frame[3].append(self.get_starttag_text())
            frame[2] += 1 if tag == frame[1] else 0
        if "data-i18n" in a:
            self.stack.append(["text", tag, 1, []])
        elif "data-i18n-html" in a:
            self.stack.append(["html", tag, 1, []])

    def handle_endtag(self, tag):
        for frame in list(self.stack):
            if tag == frame[1]:
                frame[2] -= 1
                if frame[2] == 0:
                    self.stack.remove(frame)
                    self.keys.add(re.sub(r"\s+", " ", "".join(frame[3])).strip())
                    continue
            if frame[0] == "html":
                frame[3].append(f"</{tag}>")

    def handle_data(self, data):
        for frame in self.stack:
            frame[3].append(data)


def web_keys():
    js = open(os.path.join(ROOT, "web/public/app.js"), encoding="utf-8").read()
    keys = calls(js, "t")
    # Region and quality names are passed to t() as variables.
    keys |= set(re.findall(r'"([A-Z][A-Za-z ]+)"', re.search(r"REGION_DOMAINS = \{[^}]*\}", open(os.path.join(ROOT, "web/server.py"), encoding="utf-8").read()).group(0)))
    keys |= set(re.findall(r':\s*"([^"]+)"', re.search(r"const LEVEL_NAMES = \{[^}]*\}", js).group(0)))
    p = Static()
    p.feed(open(os.path.join(ROOT, "web/public/index.html"), encoding="utf-8").read())
    keys |= p.keys
    # Messages the server sends to the page. f-string fields become {0}, {1}, …
    srv = open(os.path.join(ROOT, "web/server.py"), encoding="utf-8").read()
    for m in re.finditer(r'raise (?:ValueError|NotSignedIn)\((f?)"((?:[^"\\]|\\.)*)"\)', srv):
        msg = m.group(2)
        if m.group(1):
            n = iter(range(10))
            msg = re.sub(r"\{[^}]*\}", lambda _: "{%d}" % next(n), msg)
        keys.add(unescape(msg))
    keys.add("No response from the Liza server.")
    return keys


def android_keys():
    keys = set()
    for f in glob.glob(os.path.join(ROOT, "app/src/main/java/app/liza/android/*.java")):
        keys |= calls(open(f, encoding="utf-8").read(), "tr")
    # Region labels are passed to tr() as variables.
    regions = open(os.path.join(ROOT, "app/src/main/java/app/liza/android/ServerRegion.java"), encoding="utf-8").read()
    keys |= set(re.findall(r'^\s+[A-Z_]+\("([^"]+)"', regions, re.M))
    return keys


def check(name, keys, path):
    full = os.path.join(ROOT, path)
    tr = json.load(open(full, encoding="utf-8")) if os.path.exists(full) else {}
    missing = sorted(k for k in keys if k not in tr)
    unused = sorted(k for k in tr if k not in keys)
    bad = sorted(k for k in keys & tr.keys()
                 if sorted(re.findall(r"\{\d\}", k)) != sorted(re.findall(r"\{\d\}", tr[k]))
                 or sorted(re.findall(r"</?\w+>", k)) != sorted(re.findall(r"</?\w+>", tr[k])))
    print(f"{name}: {len(keys)} strings, {len(missing)} missing, {len(bad)} with mismatched placeholders or tags, {len(unused)} unused")
    for k in missing:
        print("  missing:", json.dumps(k, ensure_ascii=False))
    for k in bad:
        print("  mismatch:", json.dumps(k, ensure_ascii=False))
    for k in unused:
        print("  unused:", json.dumps(k, ensure_ascii=False))
    return not missing and not bad


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sets = {"web": web_keys(), "android": android_keys()}
    if "--list" in sys.argv:
        which = sys.argv[sys.argv.index("--list") + 1] if len(sys.argv) > sys.argv.index("--list") + 1 else "web"
        print(json.dumps(sorted(sets[which]), ensure_ascii=False, indent=1))
        sys.exit(0)
    ok = check("Liza web", sets["web"], "web/public/i18n/ar.json")
    ok &= check("Android", sets["android"], "app/src/main/res/raw/ar.json")
    sys.exit(0 if ok else 1)

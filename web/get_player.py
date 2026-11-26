"""Download EZVIZ's official web player (EZUIKit) into web/vendor/.

The player is not stored in this repository (about 27 MB). This script
fetches the exact npm release Liza was tested with, checks its SHA-512
checksum, and extracts only the files Liza needs.

Run:  python get_player.py
"""
import base64
import hashlib
import io
import os
import shutil
import sys
import tarfile
import urllib.request

VERSION = "9.0.26"
URL = f"https://registry.npmjs.org/ezuikit-js/-/ezuikit-js-{VERSION}.tgz"
# npm "dist.integrity" for ezuikit-js@9.0.26
INTEGRITY = "sha512-Q2gRq5LKu+bXX/lw/pbMeiqO04iltvPklgaJbaQSGWSjpO6VtBXB0o4xbegr7WcuhydhqcSTEXxU33OioP2Z+g=="

ROOT = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(ROOT, "vendor")
MARKER = os.path.join(VENDOR, ".version")


def installed():
    try:
        with open(MARKER, encoding="utf-8") as f:
            return f.read().strip() == VERSION and os.path.isfile(os.path.join(VENDOR, "ezuikit.js"))
    except OSError:
        return False


def main():
    if installed() and "--force" not in sys.argv:
        print(f"EZUIKit {VERSION} is already in web/vendor.")
        return
    print(f"Downloading EZUIKit {VERSION} from npm...")
    with urllib.request.urlopen(URL, timeout=120) as resp:
        data = resp.read()
    digest = "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()
    if digest != INTEGRITY:
        sys.exit("Checksum mismatch: the downloaded player is not the expected release. Nothing was installed.")

    tmp = VENDOR + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    wanted = ("package/ezuikit.js", "package/ezuikit_static/", "package/README.md", "package/package.json")
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for member in tar.getmembers():
            if not member.name.startswith(wanted) or not (member.isfile() or member.isdir()):
                continue
            rel = member.name[len("package/"):]
            if rel == "README.md":
                rel = "EZUIKIT-README.md"
            target = os.path.normpath(os.path.join(tmp, rel))
            if not target.startswith(tmp + os.sep):
                continue  # never write outside vendor/
            if member.isdir():
                os.makedirs(target, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with tar.extractfile(member) as src, open(target, "wb") as dst:
                shutil.copyfileobj(src, dst)
    with open(os.path.join(tmp, ".version"), "w", encoding="utf-8") as f:
        f.write(VERSION)
    shutil.rmtree(VENDOR, ignore_errors=True)
    os.replace(tmp, VENDOR)
    print("Done. The player is in web/vendor.")


if __name__ == "__main__":
    main()

#!/usr/bin/env bash
# Verifies the SHA-256 of every bundled AI model listed in models/models.json.
# Missing files are downloaded from the official URL and verified (never used if the hash differs).
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import hashlib, json, os, sys, urllib.request
ok = True
for m in json.load(open("models/models.json"))["models"]:
    path = m["file"]
    if not os.path.exists(path):
        print(f"downloading {m['name']} ...")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        urllib.request.urlretrieve(m["url"], path + ".part")
        os.replace(path + ".part", path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    status = "OK" if digest == m["sha256"] else "MISMATCH"
    print(f"{status}  {digest}  {path}")
    if digest != m["sha256"]:
        ok = False
        os.remove(path)
sys.exit(0 if ok else 1)
PY

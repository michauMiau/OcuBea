"""Lists every alias IP Webcam clients may send that applySetting() does not accept.

IpWebcamCompat declares the surface, StreamServer.applySetting implements it,
and nothing checks that the two agree. A key present in the first and missing
from the second is a 404 for a client that did nothing wrong -- which is exactly
how "focus" survived while "focusmode" worked. A JVM test should own this
permanently; this script is how the gap was found.
"""
import re
import sys

ROOT = "/home/truenas_admin/OcuBea/app/src/main/java/com/ocubea/server"

server = open(f"{ROOT}/StreamServer.kt", encoding="utf-8").read()
compat = open(f"{ROOT}/IpWebcamCompat.kt", encoding="utf-8").read()

# The body of applySetting: from the function to the next top-level fun.
i = server.find("private fun applySetting")
if i < 0:
    sys.exit("nie znaleziono applySetting")
j = server.find("\n    private fun ", i + 10)
body = server[i:j if j > 0 else len(server)]

# Every string literal inside applySetting, which is the superset that matters.
handled = set(re.findall(r'"([a-z_0-9]+)"', body))

# Every key the compat layer promises to accept, from its linkedSetOf block.
mi = compat.find("private val SUPPORTED")
mj = compat.find(")", mi)
promised = set(re.findall(r'"([a-z_0-9]+)"', compat[mi:mj]))

print(f"  klucze w tabeli kompatybilnosci: {len(promised)}")
print(f"  klucze widziane w applySetting:  {len(handled)}")

missing = sorted(promised - handled)
extra = sorted(handled - promised)

if missing:
    print(f"\n  NIEOBSLUZONE ({len(missing)}) - klient dostanie 404:")
    for k in missing:
        print(f"    {k}")
else:
    print("\n  brak nieobsluzonych kluczy - powierzchnia sie zgadza")

if extra:
    print(f"\n  dodatkowe w applySetting, nieobecne w tabeli ({len(extra)}):")
    print("   ", ", ".join(extra))

# PlatformIO pre-script: the firmware's version is the apps' version, MAJOR.MINOR from
# native/gradle.properties (scraper.version) + the build number (commit count + buildBase):
# 1.6.120, like the apps. FW_VERSION, FW_BUILD.
import os
import re
import subprocess

Import("env")  # noqa: F821  (PlatformIO)

root = os.path.dirname(env.subst("$PROJECT_DIR"))  # noqa: F821
with open(os.path.join(root, "native", "gradle.properties")) as f:
    version = re.search(r"^scraper\.version=(.+)$", f.read(), re.M).group(1).strip()
with open(os.path.join(root, "native", "gradle.properties")) as f:
    m = re.search(r"^scraper\.buildBase=(\d+)", f.read(), re.M)
base = int(m.group(1)) if m else 0
try:
    build = str(base + int(subprocess.check_output(["git", "rev-list", "--count", "HEAD"], cwd=root, text=True).strip()))
except Exception:
    build = "0"
version = "%s.%s" % (version, build)
env.Append(CPPDEFINES=[("FW_VERSION", '\\"%s\\"' % version), ("FW_BUILD", build)])  # noqa: F821
print("firmware %s (%s)" % (version, build))

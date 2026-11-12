# PlatformIO pre-script: the firmware's version is the apps' version, MAJOR.MINOR from
# native/gradle.properties (scraper.version) + the build number (commit count + buildBase):
# 1.6.120, like the apps. FW_VERSION, FW_BUILD.
import os
import re
import subprocess

Import("env")  # noqa: F821  (PlatformIO)

root = os.path.dirname(env.subst("$PROJECT_DIR"))  # noqa: F821
with open(os.path.join(root, "native", "gradle.properties")) as f:
    props = dict(re.findall(r"^scraper\.(\w+)=(.+)$", f.read(), re.M))
try:
    build = str(int(props["buildBase"]) + int(subprocess.check_output(["git", "rev-list", "--count", "HEAD"], cwd=root, text=True)))
except Exception:   # no git history (a source archive)
    build = "0"
version = "%s.%s" % (props["version"].strip(), build)
env.Append(CPPDEFINES=[("FW_VERSION", '\\"%s\\"' % version), ("FW_BUILD", build)])  # noqa: F821
print("firmware %s (%s)" % (version, build))

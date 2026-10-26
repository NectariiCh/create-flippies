"""Puts the packages' module folders on sys.path, so the tests import them as installed."""
import os
import sys

APPS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for pkg in ("flippies-apps", "flippies-sharedfiles", "flippies-sysinfo"):
    sys.path.insert(0, os.path.join(APPS, pkg, "usr", "lib", pkg))
MENU = os.path.join(APPS, "flippies-desktop", "usr", "bin", "flippies-apps-menu")
FEATURED = os.path.join(APPS, "flippies-apps", "usr", "lib", "flippies-apps", "featured.txt")

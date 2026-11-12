#!/usr/bin/env python3
"""Fails when a version pinned in backend/pom.xml (to get a security fix early) is older than what Spring Boot now
manages itself, i.e. when the pin has turned into a downgrade and should be removed."""
import re
import sys
import urllib.request

pom = open("backend/pom.xml", encoding="utf-8").read()
boot = re.search(r"<artifactId>spring-boot-starter-parent</artifactId>\s*<version>([^<]+)</version>", pom)
if not boot:
    sys.exit("Couldn't find the Spring Boot version in backend/pom.xml")
pins = dict(re.findall(r"<([a-z0-9.-]+\.version)>([^<]+)</\1>", pom))
url = (f"https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/"
       f"{boot.group(1)}/spring-boot-dependencies-{boot.group(1)}.pom")
managed = dict(re.findall(r"<([a-z0-9.-]+\.version)>([^<$]+)</\1>", urllib.request.urlopen(url, timeout=30).read().decode()))


def key(v):
    return [int(p) if p.isdigit() else p for p in re.split(r"[.-]", v)]


failed = False
for name, ours in sorted(pins.items()):
    theirs = managed.get(name)
    if theirs is None:
        continue
    if key(ours) < key(theirs):
        print(f"::error file=backend/pom.xml::{name} is pinned to {ours} but Spring Boot {boot.group(1)} manages "
              f"{theirs}; the pin is now a downgrade, remove it.")
        failed = True
    else:
        print(f"{name}: pinned {ours}, Spring Boot manages {theirs} (ok)")
sys.exit(1 if failed else 0)

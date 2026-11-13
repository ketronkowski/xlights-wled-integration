#!/usr/bin/env python3
"""Sync A records for the Light Show VLAN controllers into Technitium DNS.

Reads hosts.csv (name,ip,device) and makes sure each <name>.<suffix> has
exactly one A record pointing at its IP. Dry run by default; pass --apply
to change anything.

The API token is read from the TECHNITIUM_TOKEN environment variable and is
never printed. Example:

    export TECHNITIUM_TOKEN="$(bws secret get <secret-id> | jq -r .value)"
    ./sync_show_dns.py              # show what would change
    ./sync_show_dns.py --apply      # make the changes

Only the Technitium primary should be written to; the secondary syncs.
"""

from __future__ import annotations

import argparse
import csv
import ipaddress
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_SERVER = "https://dns.lab.ri.tronkowski.net"
DEFAULT_ZONE = "nh.tronkowski.net"
DEFAULT_SUFFIX = "show.nh.tronkowski.net"
DEFAULT_TTL = 3600
DEFAULT_HOSTS = Path(__file__).with_name("hosts.csv")


class TechnitiumError(RuntimeError):
    pass


class Technitium:
    def __init__(self, server: str, token: str, insecure: bool = False):
        self.server = server.rstrip("/")
        self._token = token
        self._ctx = ssl._create_unverified_context() if insecure else None

    def call(self, path: str, **params: str) -> dict:
        query = urllib.parse.urlencode({"token": self._token, **params})
        url = f"{self.server}{path}?{query}"
        try:
            with urllib.request.urlopen(url, timeout=15, context=self._ctx) as resp:
                body = json.load(resp)
        except urllib.error.HTTPError as e:
            raise TechnitiumError(f"{path}: HTTP {e.code}") from None
        except urllib.error.URLError as e:
            raise TechnitiumError(f"{path}: {e.reason}") from None
        if body.get("status") != "ok":
            # Never echo the request URL: it contains the token.
            raise TechnitiumError(f"{path}: {body.get('errorMessage', body.get('status'))}")
        return body.get("response", {})

    def get_a_records(self, domain: str, zone: str) -> list[str]:
        try:
            resp = self.call("/api/zones/records/get", domain=domain, zone=zone)
        except TechnitiumError as e:
            if "no such" in str(e).lower() or "not found" in str(e).lower():
                return []
            raise
        return [
            r["rData"]["ipAddress"]
            for r in resp.get("records", [])
            if r.get("type") == "A" and r.get("name", "").lower() == domain.lower()
        ]

    def set_a_record(self, domain: str, zone: str, ip: str, ttl: int) -> None:
        # overwrite=true replaces any existing A records for this name.
        self.call(
            "/api/zones/records/add",
            domain=domain,
            zone=zone,
            type="A",
            ipAddress=ip,
            ttl=str(ttl),
            overwrite="true",
        )


def load_hosts(path: Path) -> list[tuple[str, str, str]]:
    hosts = []
    with path.open(newline="") as f:
        for row in csv.DictReader(f):
            name = row["name"].strip().lower()
            ip = row["ip"].strip()
            ipaddress.IPv4Address(ip)  # raises on a typo
            if not name or "." in name:
                raise ValueError(f"bad host name in {path}: {row['name']!r}")
            hosts.append((name, ip, row.get("device", "").strip()))
    names = [h[0] for h in hosts]
    dupes = {n for n in names if names.count(n) > 1}
    if dupes:
        raise ValueError(f"duplicate names in {path}: {sorted(dupes)}")
    return hosts


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--apply", action="store_true", help="make changes (default is a dry run)")
    p.add_argument("--hosts", type=Path, default=DEFAULT_HOSTS, help="CSV of name,ip,device")
    p.add_argument("--server", default=os.environ.get("TECHNITIUM_URL", DEFAULT_SERVER))
    p.add_argument("--zone", default=DEFAULT_ZONE, help="Technitium zone that holds the records")
    p.add_argument("--suffix", default=DEFAULT_SUFFIX, help="domain appended to each name")
    p.add_argument("--ttl", type=int, default=DEFAULT_TTL)
    p.add_argument("--insecure", action="store_true", help="skip TLS verification (self-signed cert)")
    args = p.parse_args(argv)

    token = os.environ.get("TECHNITIUM_TOKEN")
    if not token:
        print("TECHNITIUM_TOKEN is not set.", file=sys.stderr)
        return 2
    if not args.suffix.endswith(args.zone):
        print(f"--suffix {args.suffix} is not inside --zone {args.zone}", file=sys.stderr)
        return 2

    hosts = load_hosts(args.hosts)
    dns = Technitium(args.server, token, insecure=args.insecure)
    mode = "APPLY" if args.apply else "DRY RUN"
    print(f"{mode}: {len(hosts)} hosts -> *.{args.suffix} on {args.server} (zone {args.zone})")

    changes = errors = 0
    for name, ip, device in hosts:
        fqdn = f"{name}.{args.suffix}"
        try:
            current = dns.get_a_records(fqdn, args.zone)
        except TechnitiumError as e:
            print(f"  ERROR  {fqdn}: {e}")
            errors += 1
            continue
        if current == [ip]:
            print(f"  ok     {fqdn:<40} {ip}")
            continue
        action = "add" if not current else f"change {','.join(current)} ->"
        print(f"  {'set  ' if args.apply else 'would'}  {fqdn:<40} {action} {ip}  ({device})")
        changes += 1
        if args.apply:
            try:
                dns.set_a_record(fqdn, args.zone, ip, args.ttl)
            except TechnitiumError as e:
                print(f"  ERROR  {fqdn}: {e}")
                errors += 1

    print(f"{changes} change(s){'' if args.apply else ' pending'}, {errors} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

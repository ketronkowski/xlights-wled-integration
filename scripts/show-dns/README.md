# Light Show DNS names

`sync_show_dns.py` keeps one A record per show controller in Technitium DNS, so boards on the
Light Show VLAN (VLAN 6, 192.168.6.0/24) can be reached by name, e.g. `octa5.show.nh.tronkowski.net`.

The names are for people and browsers. xLights and FPP should keep using raw IPs so the show never
depends on DNS.

## Usage

Python 3 standard library only. Run it from a machine that can reach the Technitium primary.

```sh
export TECHNITIUM_TOKEN="..."         # API token from the Technitium primary; never commit it
./sync_show_dns.py                    # dry run: shows what would change
./sync_show_dns.py --apply            # add or update the records
dig octa5.show.nh.tronkowski.net @192.168.16.101
```

Edit `hosts.csv` when a controller is added, retired or renumbered, then run it again. Records not
listed in the CSV are left alone.

| Option | Default |
| --- | --- |
| `--server` (or `TECHNITIUM_URL`) | `https://dns.lab.ri.tronkowski.net` |
| `--zone` | `nh.tronkowski.net` |
| `--suffix` | `show.nh.tronkowski.net` |
| `--ttl` | `3600` |
| `--insecure` | off; skips TLS verification for a self-signed cert |

Write to the primary only; the secondary picks the records up through cluster sync.

#!/usr/bin/env python3
"""Apply a SYN admission budget only to the two public Zyrex Stratum ports."""
import argparse
import os
from pathlib import Path
import subprocess


TABLE = "zyrex_pool_guard"
RULES = """table inet zyrex_pool_guard {
    set clients4 {
        type ipv4_addr
        flags dynamic,timeout
        timeout 60s
        size 65536
    }
    set clients6 {
        type ipv6_addr
        flags dynamic,timeout
        timeout 60s
        size 65536
    }
    chain admission {
        type filter hook input priority -5; policy accept;
        tcp dport { 3333, 3443 } ct state new tcp flags & (syn | ack) == syn \
update @clients4 { ip saddr timeout 60s limit rate over 3/second burst 16 packets } counter drop
        tcp dport { 3333, 3443 } ct state new tcp flags & (syn | ack) == syn \
update @clients6 { ip6 saddr timeout 60s limit rate over 3/second burst 16 packets } counter drop
    }
}
"""


def command(args, data=None):
    return subprocess.run(args, input=data, text=True, capture_output=True, timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate without changing the kernel rules")
    parser.add_argument("--remove", action="store_true", help="Remove only the owned Zyrex Stratum guard table")
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error("The edge operator must run this command as root")
    script = Path(__file__).resolve()
    if script.stat().st_uid != 0 or script.stat().st_mode & 0o022:
        raise RuntimeError("The root-executed guard source must be root-owned and not group/other writable")
    existing = command(["nft", "list", "table", "inet", TABLE])
    if existing.returncode and "No such file or directory" not in existing.stderr:
        raise RuntimeError("Cannot inspect the owned guard table: " + existing.stderr)
    exists = existing.returncode == 0
    if args.remove:
        if args.check:
            parser.error("--check and --remove are mutually exclusive")
        if exists:
            result = command(["nft", "delete", "table", "inet", TABLE])
            if result.returncode:
                raise RuntimeError(result.stderr)
        print("Zyrex Stratum guard removed; all other tables retained")
        return
    # One nft transaction replaces only this table; failed checks leave old rules intact.
    rules = (f"delete table inet {TABLE}\n" if exists else "") + RULES
    result = command(["nft", "--check", "-f", "-"], rules)
    if result.returncode:
        raise RuntimeError(result.stderr)
    if not args.check:
        result = command(["nft", "-f", "-"], rules)
        if result.returncode:
            raise RuntimeError(result.stderr)
    print("Zyrex Stratum guard " + ("validated" if args.check else "applied") +
          ": TCP 3333/3443 only, 3 new SYNs/second per source with a burst of 16")


if __name__ == "__main__":
    main()

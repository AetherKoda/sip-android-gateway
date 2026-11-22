"""Create an offline QQ-SIP account QR. Credentials are entered interactively, never as CLI arguments."""
import argparse
import getpass
from pathlib import Path
from urllib.parse import urlencode, quote

import qrcode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, help='Private PNG output path; contains the SIP password')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output already exists; choose another path')
    domain = input('SIP domain / server: ').strip()
    username = input('SIP username: ').strip()
    password = getpass.getpass('SIP password (hidden): ')
    transport = input('Transport [udp/tcp/tls, default udp]: ').strip() or 'udp'
    port = input('Port [default 5061 for TLS, otherwise 5060]: ').strip() or ('5061' if transport == 'tls' else '5060')
    if not domain or not username or not password or transport not in ('udp', 'tcp', 'tls') or not port.isdigit() or not 1 <= int(port) <= 65535:
        parser.error('Invalid account settings')
    content = 'qqsip://login?' + urlencode(dict(domain=domain, username=username, password=password,
                                              transport=transport, port=port), quote_via=quote)
    with args.output.open('xb') as target:
        args.output.chmod(0o600)
        qrcode.make(content, box_size=10, border=4).save(target, format='PNG')
    print('Saved account QR. Keep the image private; it contains credentials and has no automatic expiry.')


if __name__ == '__main__':
    main()

# SIP account QR login

QQ-SIP accepts QR data that contains the SIP account settings. Scanning imports the account and uses
the same login action as the manual form. Android permissions and the call service must be ready
before registration can start. A registered indicator, not successful parsing, confirms SIP login.
Account changes are blocked during an active call.

## Supported formats

Use UTF-8 percent encoding for reserved characters, including `&`, `:`, `@` and `#` in passwords.
These are synthetic examples; they are not working accounts:

```text
qqsip://login?domain=pbx.example.org&username=demo&password=example-only&transport=tls&port=5061
sips:demo:example-only@pbx.example.org:5061
sip:demo:example-only@pbx.example.org:5060;transport=udp
```

`domain`, `username` and `password` are required for `qqsip`. Optional fields are `server` (if the
connection host differs from the SIP identity domain), `transport` (`udp`, `tcp`, `tls`) and `port`.
Defaults are UDP/5060, or 5061 when TLS is chosen. TLS QR import requires SRTP; adjust only if the PBX
administrator specifies a different media policy. Unknown/duplicate fields and invalid values are rejected.
SIP URIs without passwords cannot log in and are rejected. A SIP URI `+` remains a literal plus sign.

Old managed-platform activation links and Linphone HTTPS provisioning links are **not** account-data QR
codes and are rejected. This independent build does not contact an old management service, copy its
binding/session rules, or fetch arbitrary QR URLs. Ask the PBX administrator for an account QR in the
format above. Management QR expiry/revocation rules do not apply to credentials encoded directly in a QR.

## Create a QR locally

Install `qrcode[pil]` in a Python environment and run:

```sh
python tools/create_sip_qr.py /private/path/account.png
```

The script prompts for settings; the password prompt is hidden. Do not put credentials in command-line
arguments, Git, or issue reports. The resulting image contains the password, so keep it private. It has
no automatic expiry: change the SIP password at the PBX to invalidate it. The app stores its saved SIP
password encrypted with Android Keystore. It never logs the full QR contents.

In the app select **Scan QR Code to log in**, or paste the content under **Advanced settings**.
On Android 12+ primary users, select **Pair and start service**, complete the system wireless-debugging
pairing and start the service. After reboot, enable wireless debugging and start the service again.

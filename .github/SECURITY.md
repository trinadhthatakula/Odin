# Security Policy

## Supported versions

Only the latest release of Odin (`com.trinadhthatakula:odin`) receives security fixes. Please
update to the newest version before reporting.

| Version          | Supported |
|------------------|-----------|
| Latest release   | ✅        |
| Older versions   | ❌        |

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Report privately through GitHub's **Report a vulnerability** button on the
[Security → Advisories page](https://github.com/trinadhthatakula/Odin/security/advisories/new).
This opens a private advisory visible only to you and the maintainer.

Because Odin manages a **persistent root shell** and a **Binder/AIDL `RootService`** running in a
privileged (`app_process`) process, a good report includes:

- The **Odin version** and the host app / Android version.
- Steps to reproduce and the **impact** — e.g. privilege escalation, command injection into the
  root shell, an unauthorized caller binding the `RootService`, or leakage of the root stdin/stdout
  pipe.
- Any proof-of-concept, logs, or affected code paths.

## What to expect

- Acknowledgement of your report as soon as reasonably possible.
- An assessment and, if valid, a fix in a subsequent release — with credit, unless you prefer to
  remain anonymous.

Please give us reasonable time to ship a fix before any public disclosure. Thank you for helping
keep Odin and its downstream apps safe.

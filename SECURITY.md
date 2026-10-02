# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. Use
GitHub's private vulnerability reporting: open the **Security** tab of this repository and choose
**Report a vulnerability**
(https://github.com/raphaelpanta/scalable-e-commerce-platform-kotlin/security/advisories/new).
Your report stays private between you and the maintainer until a fix is available.

Please include what you found, how to reproduce it, the affected files or versions and the impact you see.

## What to expect

- A first response within **5 business days**.
- Progress updates until the issue is resolved or declined, with reasons.
- Credit in the advisory, if you want it, once a fix is released.

## Supported versions

This project has no tagged releases yet. Only the latest commit on `main` is supported; fixes are made
there.

## Leaked secrets

If you find a credential in the repository or its history, report it the same way and, if it is yours,
rotate it first. The maintainer rewrites history with `git filter-repo`, force-pushes through a temporary
branch-protection exception and asks GitHub support to purge cached views.
Secret scanning with push protection is enabled and a local `gitleaks` pre-push hook is provided (see
[CONTRIBUTING.md](CONTRIBUTING.md)).

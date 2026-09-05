# Agent instructions

## Native/web parity (standing rule)

Every change made in the native app (`apps/cli-server`) must also be made in the
web version (`apps/web`, `apps/landing`) and vice versa. The dashboard UI is one
product served two ways — the owned system-webview window and the browser — and
they must never drift: same features, same flags honored, same wording.

# Secrets are safe's wrapped secrets

> Changes in part [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md) (`:access-key` and `:secret-key` are `safe/secret`, no longer text). Since v0.1.1, on [safe](https://github.com/p4ulcristian/safe) v0.1.0.

**Decision:** the bucket's key is two secrets of safe's, `[:access-key safe/secret]` and `[:secret-key safe/secret]` (`#secret :attic/access-key` and `#secret :attic/secret-key` in the app's settings file). Each is revealed only where it is used:

- **both**, handed to aws-api's credentials provider when the client is made (`attic.bucket/make-client`);
- **the access key** in a signed link's `X-Amz-Credential`, and **the secret key** for the link's signing key (`attic.links/make-signed-link`).

Either written out as text is refused, `:attic/bad-settings` with `"should be a #secret, read from the settings' secrets source"` under its key, and the refusal never holds the text. The tests make theirs with `safe/make-secret`.

**Why:** the settings are printed, logged and put in ex-data; a key in them as a string leaks by every one of those. Wrapped, each prints as its name everywhere but the calls that use it, which can be searched for. safe's [A secret stays wrapped until it is used](https://github.com/p4ulcristian/safe/blob/main/decisions/2026-10-23-a-secret-stays-wrapped-until-it-is-used.md) says how. The access key is no secret on its own (every signed link carries it), but it is half of a credential and names the account: it is kept the same way, so the pair reads from one place.

**Instead of:**
- strings, as before: every log of the settings is a leak
- the access key as text, only the secret key wrapped: the pair would come from two places, and a log would still name the account's key

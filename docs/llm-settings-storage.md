# LLM settings storage consistency

LLM settings use `llm_settings` (Android encrypted preferences) with the existing
`llm_settings_fallback` recovery store when encrypted storage cannot be written. No app-wide reset,
key deletion, or model-list reset is needed to recover from stale storage selection.

## Selection and compatibility

- A store without any known LLM setting fields is absent, not a saved default configuration.
  Encryption metadata and the storage-selection marker alone do not constitute settings.
- The fallback store keeps a non-secret `active_store` marker. A fallback save commits the complete
  settings snapshot and the `fallback` marker together. Reads continue to use it even when an old
  encrypted snapshot becomes readable again, including after an app restart.
- A successful encrypted save is followed by a synchronous fallback commit that selects `encrypted`
  and removes only the fallback LLM fields. Unrelated preference entries are preserved. A failed
  selection commit is a save failure, not a successful switch; existing pending-edit retries remain
  responsible for persisting it.
- Before the marker existed, successful encrypted saves cleared the fallback store. Consequently,
  when legacy data has both snapshots and no marker, a nonempty readable fallback takes precedence.
  There are no historical timestamps to reconstruct a different ordering reliably. If the preferred
  snapshot is absent or unreadable, the other readable snapshot is used.
- Reading is non-destructive. The next explicit settings save can return to encrypted storage when
  it works, then remove the obsolete plaintext recovery fields.

## Verification and limits

`LlmSettingsRepositoryTest` covers empty and metadata-only stores, legacy dual snapshots, encrypted
write exceptions and failed commits, fallback durability across readers/restart, encrypted recovery,
failed selection commits and retries, preservation of unrelated fields, and unreadable stores.
Preference proxies model Android's memory-before-disk commit behaviour; they do not implement or
verify Android Keystore itself. Existing settings ViewModel tests cover queued edits and retries.

This fixes a demonstrated stale-configuration bug. It does not establish that the bug caused a
particular user's HTTP 400: the settings screen also sends freshly entered connection values directly
to model-list and model-test requests. User-device and live-service retesting is still necessary.

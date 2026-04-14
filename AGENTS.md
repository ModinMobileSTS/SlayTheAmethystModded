# Repository Rules

## Mod-side fixes

- If a gameplay/runtime fix is shipped through a mod under `mods/`, document every individual fix in that mod's `README.md` in the same change.
- The mod `README.md` must state what each fix does, what symptom it addresses, and which patch class implements it.
- Do not accumulate unrelated Spire patches in one monolithic patch file. Split them by fix domain so each fix can be reviewed and reverted independently.

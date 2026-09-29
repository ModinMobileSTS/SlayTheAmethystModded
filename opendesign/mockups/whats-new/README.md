# What's New 1.6.1 — review prototype

Open `index.html` in a browser. This is a design-only artifact and is not wired into the Android app.

## Direction

Borrow the KDE Plasma release-announcement pattern of a short release promise followed by feature-specific stories and concrete outcomes. Adapt it to a compact, single-scroll Material 3 modal using the launcher-product semantic theme roles. No release-note file is read or generated.

The two leading stories are Amethyst Agent and Sling Break. The **More in this update** section always shows the remaining seven changes and five fixes in a compact list. The release note was consulted to check coverage, but the prototype maintains its own wording and content.

## Behavior to approve

- Show once on each app start for matching 1.6.1 / 1.6.1-hotfixN until the user checks “Don't show again” and closes (including close icon, Back, or feature action). A checked dismissal persists the release ID `1.6.1`; unchecked dismissal only skips the current session.
- Entry CTA may navigate to My mods. Dismissal returns to the underlying screen; the primary CTA closes the modal.
- Content and footer are separate scroll regions. Keep the checkbox and primary CTA visible at large font sizes; allow full-screen dialog when compact height prevents this.
- English, Simplified Chinese and Traditional Chinese use equivalent meaning. Illustrations are optional and must have alt text if informative.

## References

- [KDE Plasma 6.4 release announcement](https://kde.org/announcements/plasma/6/6.4.0/) — release headline, themed sections and feature illustrations (structure only, not branding or text).
- `opendesign/design-systems/launcher-product/` — semantic colors, restrained tonal surfaces, operational copy.

## Review checks

1. Is the level of detail in “More in this update” right for a startup dialog?
2. Are the Amethyst Agent and Sling Break summaries accurate and sufficiently specific?
3. Should “Browse my mods” navigate immediately or only after the user closes What's New?

# Mod display sorting

The mod page's overflow menu offers **Sort mods**. Choose name ascending/descending or local
file modification time newest/oldest first in the dropdown, then confirm. A second dialog warns
that the existing custom order will be overwritten. Cancelling either step leaves it unchanged.

Only the second confirmation replaces the saved `ModDisplayOrder`. Sorting applies to all cards,
including cards currently hidden by search, filters or collapsed folders. Cards remain in their
existing folders; folder ordering, enabled state, favorites and game load priorities are unchanged.
Name sorting uses the displayed alias/name and the current locale. Time sorting reads the local
file timestamp off the UI thread; cards without a known local timestamp go last in either direction.
Equal keys preserve input order. This is a one-time sort: users can still drag cards afterward,
and later imports do not trigger automatic re-sorting.

The list observes saved display-order changes, so applying a sort updates the visible cards
without leaving the page. The same action is available in the multi-selection overflow menu;
it sorts all cards, not just the selection.

Offline verification: `:app:testDebugUnitTest` for `ModSortOrderTest` and existing display-order/
drag tests; `:app:compileDebugAndroidTestKotlin` compiles `ModSortDialogInstrumentedTest`.
The instrumentation tests cover the two-step flow, cancellation, disabled controls, persistence
and order observation, but require separately authorized device execution (no automatic install).

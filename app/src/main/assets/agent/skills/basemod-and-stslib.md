# BaseMod 5.56.0 + StSLib 2.12.0

Pinned reference for the modding APIs bundled with this launcher. Both projects are frozen.

Signatures drift between versions, so treat every signature below as a lead, not a licence to guess:
find the type with `search_agent_api` and confirm the overload with `describe_agent_api_class`
before compiling. That is the only way to be sure the call exists in the bytecode you are patching.

## 1. Which layer to use

- **BaseMod API first.** If BaseMod exposes a subscriber hook or an `add*` method for what you want,
  use it. It survives game updates and does not fight other mods.
- **ModTheSpire `@SpirePatch2` only when no API exists.** Never use a `Replace` patch unless there is
  no alternative: it overwrites the method and breaks every other mod that patches it too.
- A patch mod can be either shape, and both package fine:
  - a hook set (`@SpirePatch2` + `Prefix`/`Postfix`), or
  - a **content mod**: one `@SpireInitializer` class whose `initialize()` registers content, plus
    whatever `AbstractCard`/`AbstractRelic`/`CustomPlayer` classes it registers.
- A resource-only patch mod (no entry point) also packages; the launcher only warns.

## 2. Mod skeleton and registration order

`@SpireInitializer` runs after mods load and before the game starts.

```java
@SpireInitializer
public class MyMod implements EditCardsSubscriber, EditRelicsSubscriber, EditStringsSubscriber {

    public MyMod() {
        BaseMod.subscribe(this); // subscribes to every subscriber interface this class implements
    }

    public static void initialize() {
        new MyMod();
    }

    @Override public void receiveEditStrings() { /* load string files */ }
    @Override public void receiveEditCards()  { /* addCard(...) */ }
    @Override public void receiveEditRelics() { /* addRelic(...) */ }
}
```

Subscribe **only from the main mod class**. Subscribing a throwaway instance inside a callback makes
duplicate subscribers that are never removed.

BaseMod runs the registration hooks in exactly this order. Content that depends on another piece
(for example a relic that references card strings) must be registered after its dependency:

1. `receiveEditStrings`
2. `receiveAddAudio`
3. `receiveEditKeywords`
4. `receiveSetUnlocks`
5. `receiveEditCards`
6. `receiveEditRelics`
7. `receivePostCreateStartingRelics`
8. `receiveEditCharacters`
9. `receivePostInitialize` (after every adder hook; use it for events and cleanup)

`receivePostInitialize` is also where you remove vanilla/shared content, because removal must happen
after registration.

## 3. Where each kind of content goes

| Want to add | Subscriber | Call |
| --- | --- | --- |
| Card | `EditCardsSubscriber` | `BaseMod.addCard(card)` |
| Relic (shared / vanilla pool) | `EditRelicsSubscriber` | `BaseMod.addRelic(relic, RelicType)` |
| Relic (custom character pool) | `EditRelicsSubscriber` | `BaseMod.addRelicToCustomPool(relic, CardColor)` |
| Potion | `EditPotionsSubscriber` | `BaseMod.addPotion(potionClass, liquid, hybrid, spots, id[, playerClass])` |
| Event | `PostInitializeSubscriber` | `BaseMod.addEvent(id, clazz[, dungeonID])` or `new AddEventParams.Builder(...)` |
| Keyword | `EditKeywordsSubscriber` | `BaseMod.addKeyword(...)` |
| Strings / localization | `EditStringsSubscriber` | `BaseMod.loadCustomStringsFile(RelicStrings.class, path)` |
| Character | `EditCharactersSubscriber` | `BaseMod.addCharacter(instance, colorEnum, button, portrait, classEnum)` |
| Card color | none | `BaseMod.addColor(...)` from `@SpireInitializer`, see below |

`addRelicToCustomPool` is for a custom character's pool only. Do not use it for the shared or vanilla
pools, and do not use `addRelic` for a custom character.

Registration gotchas that cause crashes rather than compile errors:

- **`addColor` must be called from the `@SpireInitializer` method**, not `receivePostInitialize`.
  A custom color also needs `@SpireEnum` patches of `AbstractCard.CardColor` and
  `CardLibrary.LibraryType`, and **both enum constants must have identical names**.
- **A relic without `RelicStrings` crashes the game on startup.** Always ship the string file.
- **Use `BaseMod.MAX_HAND_SIZE`, never a hardcoded 10.**
- Events added through `AddEventParams.Builder` default to `EventType.NORMAL` (seen once).

## 4. Strings and IDs

- Load JSON with `BaseMod.loadCustomStringsFile(<Type>.class, path)` in `receiveEditStrings`, then
  read it with `CardCrawlGame.languagePack.get<Type>(id)`:
  `getCardStrings`, `getRelicStrings`, `getPowerStrings`, `getEventString`/`getEventStrings`,
  `getPotionStrings`, `getMonsterStrings`, `getCharacterStrings`, `getUIStrings`, `getOrbStrings`,
  `getKeywordStrings`, `getScoreBonusStrings`, `getTutorialStrings`, `getAchievementStrings`.
- **Prefix every content ID with `yourmodid:`** (`"mymod:Flare"`). IDs are global; unprefixed IDs
  collide with other mods.
- **Put resources in a mod-named subdirectory** (`mymod/localization/...`, `mymod/img/...`). All mods
  are extracted into one directory, so identical resource paths overwrite each other.
- Card art conventions: `img` is the 250x190 card image, the 500x380 inspect image is `img + "_p"`
  (`my_card.png` → `my_card_p.png`), beta art is `_b` and `_b_p`.
- Relic textures are 128x128 with the art only in the centre ~48-64px; the rest transparent.

## 5. Extending the base classes

`CustomCard(String id, String name, String img, int cost, String rawDescription, CardType type,
CardColor color, CardRarity rarity, CardTarget target)` — override `use(AbstractPlayer, AbstractMonster)`,
`makeCopy()`, and `upgrade()`. Optional per-card art: `setBackgroundTexture`, `setOrbTexture`,
`setBannerTexture`.

`CustomRelic(String id, Texture texture, RelicTier tier, LandingSound sfx)` — override
`getUpdatedDescription()` and `makeCopy()`. Extending `AbstractRelic` directly means loading the
texture yourself, so prefer `CustomRelic`.

Persisting a value that is not a card's `misc` or a relic's `counter`: implement
`CustomSavable<T>` with `onSave()`/`onLoad(T)`. If the callbacks never fire, add
`savedType()`; for anything that is not a card, relic, or potion, register it with
`BaseMod.addSaveField(key, field)`.

## 6. Hooks

Interface name is `<Name>Subscriber`, method is `receive<Name>`. Occurrence runs
Before → Pre → Post → After; Pre and Post hooks receive the live event objects and can modify them.

- **Before (no event object yet):** `PreStartGame`, `OnPlayerTurnStart`, `OnPlayerTurnStartPostDraw`.
- **Pre (can modify, often returns a replacement value):** `PreMonsterTurn` (false skips the turn),
  `OnPlayerDamaged` (returns override amount), `OnPlayerLoseBlock`, `MaxHpChange`, `OnCardUse`,
  `OnCreateDescription`, `PostCampfire` (false allows another campfire action), `PrePotionUse`.
- **After:** `PostExhaust`, `PostDraw`, `PostBattle`, `OnStartBattle`, `PostPotionUse`, `PostPowerApply`,
  `PotionGet`, `RelicGet`, `PostCreateStartingDeck`, `PostCreateStartingRelics`,
  `PostCreateShopRelic`, `PostCreateShopPotion`, `OnPowersModified`.
- **Lifecycle:** `PostDeath` (also fires on Abandon Run), `PostDungeonInitialize`, `StartAct`,
  `StartGame`, `PostEnergyRecharge`.
- **Update:** order is `PreUpdate` → `PreDungeonUpdate` → `PrePlayerUpdate` → `PostPlayerUpdate` →
  `PostDungeonUpdate` → `PostUpdate` (input is read before `PreUpdate` and disposed after `PostUpdate`).
- **Render:** order is `PreRender`/`receiveCameraRender` → `ModelRender` → `PreRoomRender` → `Render`
  → `PostRender`. In `Render`, call `spriteBatch.setColor(Color.WHITE)` before drawing or nothing shows.

To stop listening from inside a callback, call `BaseMod.unsubscribeLater(this)` — calling
`unsubscribe` there throws `ConcurrentModificationException` because BaseMod is iterating its
subscriber list.

## 7. Events

Extend `AbstractEvent`/`AbstractImageEvent`, give it `EventStrings`, and set dialog options with
`imageEventText.setDialogOption(...)`. `buttonEffect(int buttonPressed)` receives the option index.
**Every path must end in `transitionKey(...)` or `openMap()` or the event softlocks.**
`PhasedEvent` (with `TextPhase`, `CombatPhase`, `InteractionPhase`) handles `buttonEffect` for you and
is the better choice for multi-step or combat events.

## 8. StSLib 2.12.0

StSLib depends on BaseMod and supplies keywords and mechanics. Implement the interface or set the
field directly; text uses the `stslib:` prefix.

Keywords — set in the card constructor unless noted:

| Keyword | Field / interface | Use |
| --- | --- | --- |
| Autoplay | `AutoplayField.autoplay` | Plays itself when drawn |
| Exhaustive | `ExhaustiveVariable.setBaseValue(this, n)` | Exhausts after n uses |
| Fleeting | `FleetingField.fleeting` | Purges and leaves the deck on use |
| Grave | `GraveField.grave` | Starts each combat in the discard pile |
| Persist | `PersistFields.setBaseValue(this, n)` | Discards only after n uses per turn |
| Purge | `PurgeField.purge` | Removed for the combat, not exhausted |
| Refund | `RefundVariable.setBaseValue(this, n)` | Refunds up to n energy |
| Retain | `AlwaysRetainField.alwaysRetain` | Not discarded at end of turn |
| Snecko | `SneckoField.snecko` | Randomises cost when drawn |
| Soulbound | `SoulboundField.soulbound` | Cannot be removed from the deck |
| Startup | `StartupCard` interface | Triggers at the start of each combat |

Dynamics in card text: `stslib:Exhaustive !stslib:ex!`.

Relic hooks to implement on your relic: `ClickableRelic`, `OnChannelRelic`,
`BeforeRenderIntentRelic`, `BetterOnLoseHpRelic`, `BetterOnSmithRelic`, `BetterOnUsePotionRelic`,
`SuperRareRelic`, `OnReceivePowerRelic`, `OnApplyPowerRelic`, `OnAnyPowerAppliedRelic`,
`OnAfterUseCardRelic`, `OnSkipCardRelic`, `OnRemoveCardFromMasterDeckRelic`, `OnLoseTempHpRelic`,
`OnLoseBlockRelic`, `OnPlayerDeathRelic`, `DamageModApplyingRelic`, `OnCreateBlockInstanceRelic`,
`CardRewardSkipButtonRelic`, `OnCreateCardInterface`.

Power hooks/mixins: `BeforeRenderIntentPower`, `BetterOnApplyPowerPower`, `BetterOnExhaustPower`,
`OnLoseBlockPower`, `OnLoseTempHpPower`, `OnMyBlockBrokenPower`, `OnPlayerDeathPower`,
`OnReceivePowerPower`, `HealthBarRenderPower`, `InvisiblePower`, `NonStackablePower`,
`TwoAmountPower`, `DamageModApplyingPower`, `OnCreateBlockInstancePower`, `OnDrawPileShufflePower`,
`OnCreateCardInterface`.

Actions: `StunMonsterAction`, `FetchAction`, `MoveCardsAction`, `AddTemporaryHPAction`,
`RemoveAllTemporaryHPAction`, `EvokeSpecificOrbAction`, `TriggerPassiveAction`, `SelectCardsAction`,
`SelectCardsInHandAction`, `MultiGroupSelectAction`, `MultiGroupMoveAction`, `DamageCallbackAction`.

Card mechanics: `BranchingUpgradesCard` for two upgrade paths (call `isBranchUpgrade()` then
`branchUpgrade()`/`baseUpgrade()`); `CommonKeywordIconsField.useIcons.set(card, true)` renders icons
for Innate/Ethereal/Retain/Purge/Exhaust; `SpawnModificationCard` (`canSpawn` / `replaceWith` /
`onRewardListCreated`) controls card-reward spawning.

Custom targeting needs three things: a `@SpireEnum AbstractCard.CardTarget`, a
`TargetingHandler` subclass (`updateHovered` / `getHovered` / `clearHovered` / `hasTarget`), and
registration in `receivePostInitialize`. Always null-check the target in `use`, because effects like
Mayhem play the card without assigning a real target.

Flavor text: add a `FLAVOR` field to the card's entry in `CardStrings.json`; potions use
`PotionFlavorFields`. Damage and block modifiers are applied through `DamageModApplyingRelic`/
`DamageModApplyingPower` and `OnCreateBlockInstanceRelic`/`OnCreateBlockInstancePower`.

## 9. Before you package

- Confirm every API call with `describe_agent_api_class`; do not trust this document's signatures.
- A content mod still needs its resource files inside the patch mod, or the game crashes on load
  (`RelicStrings`, card art, localization JSON).
- Prefer the smallest patch that does the job, then run `smoke_test_agent_patch_mod`.

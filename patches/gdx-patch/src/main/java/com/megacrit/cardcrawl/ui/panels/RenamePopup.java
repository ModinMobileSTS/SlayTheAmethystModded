package com.megacrit.cardcrawl.ui.panels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.codedisaster.steamworks.SteamUtils;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.MathHelper;
import com.megacrit.cardcrawl.helpers.SaveHelper;
import com.megacrit.cardcrawl.helpers.TypeHelper;
import com.megacrit.cardcrawl.helpers.controller.CInputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.helpers.input.ScrollInputProcessor;
import com.megacrit.cardcrawl.helpers.steamInput.SteamInputHelper;
import com.megacrit.cardcrawl.localization.UIStrings;
import com.megacrit.cardcrawl.screens.mainMenu.SaveSlotScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Android runtime patch for rename popup buttons.
 *
 * The desktop popup waits for Hitbox.clicked, which depends on a matching
 * release event after switching the input processor to TypeHelper. Touch input
 * on Android can reach the hover/press path but miss the release completion, so
 * confirm/cancel are committed on press here.
 */
public class RenamePopup {
    private static final Logger logger = LogManager.getLogger(RenamePopup.class.getName());
    private static final UIStrings uiStrings = CardCrawlGame.languagePack.getUIString("RenamePanel");
    public static final String[] TEXT = RenamePopup.uiStrings.TEXT;
    private int slot = 0;
    private boolean newSave = false;
    private boolean shown = false;
    public static String textField = "";
    public Hitbox yesHb = new Hitbox(160.0f * Settings.scale, 70.0f * Settings.scale);
    public Hitbox noHb = new Hitbox(160.0f * Settings.scale, 70.0f * Settings.scale);
    private static final int CONFIRM_W = 360;
    private static final int CONFIRM_H = 414;
    private static final int YES_W = 173;
    private static final int NO_W = 161;
    private static final int BUTTON_H = 74;
    private Color faderColor = new Color(0.0f, 0.0f, 0.0f, 0.0f);
    private Color uiColor = new Color(1.0f, 0.965f, 0.886f, 0.0f);
    private float waitTimer = 0.0f;

    public RenamePopup() {
        this.updateHitboxPositions();
    }

    private void updateHitboxPositions() {
        float centerX = (float) Settings.WIDTH / 2.0f;
        float centerY = Settings.OPTION_Y - 120.0f * Settings.scale;
        float yesCenterX = centerX - 106.0f * Settings.scale;
        float noCenterX = centerX + 106.0f * Settings.scale;
        this.yesHb.move(yesCenterX, centerY);
        this.noHb.move(noCenterX, centerY);
    }

    public void update() {
        if (Gdx.input.isKeyPressed(67) && !textField.equals("") && this.waitTimer <= 0.0f) {
            textField = textField.substring(0, textField.length() - 1);
            this.waitTimer = 0.09f;
        }
        if (this.waitTimer > 0.0f) {
            this.waitTimer -= Gdx.graphics.getDeltaTime();
        }
        if (this.shown) {
            this.faderColor.a = MathHelper.fadeLerpSnap(this.faderColor.a, 0.75f);
            this.uiColor.a = MathHelper.fadeLerpSnap(this.uiColor.a, 1.0f);
            this.updateButtons();
            if (Gdx.input.isKeyJustPressed(66)) {
                this.confirm();
            } else if (InputHelper.pressedEscape) {
                InputHelper.pressedEscape = false;
                this.cancel();
            }
        } else {
            this.faderColor.a = MathHelper.fadeLerpSnap(this.faderColor.a, 0.0f);
            this.uiColor.a = MathHelper.fadeLerpSnap(this.uiColor.a, 0.0f);
        }
    }

    private void updateButtons() {
        this.yesHb.update();
        if (this.yesHb.justHovered) {
            CardCrawlGame.sound.play("UI_HOVER");
        }
        if (this.yesHb.hovered && InputHelper.justClickedLeft) {
            CardCrawlGame.sound.play("UI_CLICK_1");
            this.yesHb.clicked = true;
        } else if (!this.yesHb.clicked && this.yesHb.hovered) {
            this.yesHb.clickStarted = false;
        }
        if (this.yesHb.clicked) {
            this.yesHb.clicked = false;
            this.confirm();
        }

        this.noHb.update();
        if (this.noHb.justHovered) {
            CardCrawlGame.sound.play("UI_HOVER");
        }
        if (this.noHb.hovered && InputHelper.justClickedLeft) {
            CardCrawlGame.sound.play("UI_CLICK_1");
            this.noHb.clicked = true;
        } else if (!this.noHb.clicked && this.noHb.hovered) {
            this.noHb.clickStarted = false;
        }
        if (this.noHb.clicked) {
            this.noHb.clicked = false;
            this.cancel();
        }

        if (CInputActionSet.proceed.isJustPressed()) {
            CInputActionSet.proceed.unpress();
            this.confirm();
        } else if (CInputActionSet.cancel.isJustPressed() || InputActionSet.cancel.isJustPressed()) {
            CInputActionSet.cancel.unpress();
            this.cancel();
        }
    }

    public void confirm() {
        if ((textField = textField.trim()).equals("")) {
            return;
        }
        CardCrawlGame.mainMenuScreen.saveSlotScreen.curPop = SaveSlotScreen.CurrentPopup.NONE;
        this.shown = false;
        Gdx.input.setInputProcessor(new ScrollInputProcessor());
        if (this.newSave) {
            boolean saveSlotPrefSave = false;
            logger.info("UPDATING DEFAULT SLOT: ", (Object) this.slot);
            CardCrawlGame.saveSlotPref.putInteger("DEFAULT_SLOT", this.slot);
            saveSlotPrefSave = true;
            CardCrawlGame.reloadPrefs();
            if (!CardCrawlGame.saveSlotPref.getString(SaveHelper.slotName("PROFILE_NAME", this.slot), "").equals(textField)) {
                logger.info("NAME CHANGE IN SLOT " + this.slot + ": " + textField);
                CardCrawlGame.saveSlotPref.putString(SaveHelper.slotName("PROFILE_NAME", this.slot), textField);
                saveSlotPrefSave = true;
            }
            if (saveSlotPrefSave) {
                CardCrawlGame.saveSlotPref.flush();
            }
            if (CardCrawlGame.playerPref.getString("alias", "").equals("")) {
                CardCrawlGame.playerPref.putString("alias", CardCrawlGame.generateRandomAlias());
            }
            CardCrawlGame.alias = CardCrawlGame.playerPref.getString("alias", "");
            CardCrawlGame.playerPref.putString("name", textField);
            CardCrawlGame.playerPref.flush();
            CardCrawlGame.playerName = textField;
        } else if (!CardCrawlGame.saveSlotPref.getString(SaveHelper.slotName("PROFILE_NAME", this.slot), "").equals(textField)) {
            logger.info("RENAMING " + this.slot + ": " + textField);
            CardCrawlGame.saveSlotPref.putString(SaveHelper.slotName("PROFILE_NAME", this.slot), textField);
            CardCrawlGame.saveSlotPref.flush();
            CardCrawlGame.mainMenuScreen.saveSlotScreen.rename(this.slot, textField);
            CardCrawlGame.playerName = textField;
        }
    }

    public void cancel() {
        CardCrawlGame.mainMenuScreen.saveSlotScreen.curPop = SaveSlotScreen.CurrentPopup.NONE;
        this.shown = false;
        Gdx.input.setInputProcessor(new ScrollInputProcessor());
    }

    public void render(SpriteBatch sb) {
        sb.setColor(this.faderColor);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, 0.0f, 0.0f, (float) Settings.WIDTH, (float) Settings.HEIGHT);
        this.renderPopupBg(sb);
        this.renderTextbox(sb);
        this.renderHeader(sb);
        this.renderButtons(sb);
    }

    private void renderHeader(SpriteBatch sb) {
        Color c = Settings.GOLD_COLOR.cpy();
        c.a = this.uiColor.a;
        FontHelper.renderFontCentered(sb, FontHelper.cardTitleFont, TEXT[1], (float) Settings.WIDTH / 2.0f, Settings.OPTION_Y + 150.0f * Settings.scale, c);
    }

    private void renderPopupBg(SpriteBatch sb) {
        sb.setColor(this.uiColor);
        sb.draw(ImageMaster.OPTION_CONFIRM, (float) Settings.WIDTH / 2.0f - 180.0f, Settings.OPTION_Y - 207.0f, 180.0f, 207.0f, 360.0f, 414.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 360, 414, false, false);
    }

    private void renderTextbox(SpriteBatch sb) {
        sb.draw(ImageMaster.RENAME_BOX, (float) Settings.WIDTH / 2.0f - 160.0f, Settings.OPTION_Y + 20.0f * Settings.scale - 160.0f, 160.0f, 160.0f, 320.0f, 320.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 320, 320, false, false);
        FontHelper.renderSmartText(sb, FontHelper.cardTitleFont, textField, (float) Settings.WIDTH / 2.0f - 120.0f * Settings.scale, Settings.OPTION_Y + 24.0f * Settings.scale, 100000.0f, 0.0f, this.uiColor, 0.82f);
        float tmpAlpha = (MathUtils.cosDeg(System.currentTimeMillis() / 3L % 360L) + 1.25f) / 3.0f * this.uiColor.a;
        FontHelper.renderSmartText(sb, FontHelper.cardTitleFont, "_", (float) Settings.WIDTH / 2.0f - 122.0f * Settings.scale + FontHelper.getSmartWidth(FontHelper.cardTitleFont, textField, 1000000.0f, 0.0f, 0.82f), Settings.OPTION_Y + 24.0f * Settings.scale, 100000.0f, 0.0f, new Color(1.0f, 1.0f, 1.0f, tmpAlpha));
    }

    private void renderButtons(SpriteBatch sb) {
        sb.setColor(this.uiColor);
        Color c = Settings.GOLD_COLOR.cpy();
        c.a = this.uiColor.a;
        if (this.yesHb.clickStarted) {
            sb.setColor(new Color(1.0f, 1.0f, 1.0f, this.uiColor.a * 0.9f));
            sb.draw(ImageMaster.OPTION_YES, (float) Settings.WIDTH / 2.0f - 86.5f - 100.0f * Settings.scale, Settings.OPTION_Y - 37.0f - 120.0f * Settings.scale, 86.5f, 37.0f, 173.0f, 74.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 173, 74, false, false);
            sb.setColor(new Color(this.uiColor));
        } else {
            sb.draw(ImageMaster.OPTION_YES, (float) Settings.WIDTH / 2.0f - 86.5f - 100.0f * Settings.scale, Settings.OPTION_Y - 37.0f - 120.0f * Settings.scale, 86.5f, 37.0f, 173.0f, 74.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 173, 74, false, false);
        }
        if (!this.yesHb.clickStarted && this.yesHb.hovered) {
            sb.setColor(new Color(1.0f, 1.0f, 1.0f, this.uiColor.a * 0.25f));
            sb.setBlendFunction(770, 1);
            sb.draw(ImageMaster.OPTION_YES, (float) Settings.WIDTH / 2.0f - 86.5f - 100.0f * Settings.scale, Settings.OPTION_Y - 37.0f - 120.0f * Settings.scale, 86.5f, 37.0f, 173.0f, 74.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 173, 74, false, false);
            sb.setBlendFunction(770, 771);
            sb.setColor(this.uiColor);
        }
        c = this.yesHb.clickStarted || textField.trim().equals("") ? Color.LIGHT_GRAY.cpy() : (this.yesHb.hovered ? Settings.CREAM_COLOR.cpy() : Settings.GOLD_COLOR.cpy());
        c.a = this.uiColor.a;
        FontHelper.renderFontCentered(sb, FontHelper.cardTitleFont, TEXT[2], (float) Settings.WIDTH / 2.0f - 110.0f * Settings.scale, Settings.OPTION_Y - 118.0f * Settings.scale, c, 0.82f);
        sb.draw(ImageMaster.OPTION_NO, (float) Settings.WIDTH / 2.0f - 80.5f + 106.0f * Settings.scale, Settings.OPTION_Y - 37.0f - 120.0f * Settings.scale, 80.5f, 37.0f, 161.0f, 74.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 161, 74, false, false);
        if (!this.noHb.clickStarted && this.noHb.hovered) {
            sb.setColor(new Color(1.0f, 1.0f, 1.0f, this.uiColor.a * 0.25f));
            sb.setBlendFunction(770, 1);
            sb.draw(ImageMaster.OPTION_NO, (float) Settings.WIDTH / 2.0f - 80.5f + 106.0f * Settings.scale, Settings.OPTION_Y - 37.0f - 120.0f * Settings.scale, 80.5f, 37.0f, 161.0f, 74.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 161, 74, false, false);
            sb.setBlendFunction(770, 771);
            sb.setColor(this.uiColor);
        }
        c = this.noHb.clickStarted ? Color.LIGHT_GRAY.cpy() : (this.noHb.hovered ? Settings.CREAM_COLOR.cpy() : Settings.GOLD_COLOR.cpy());
        c.a = this.uiColor.a;
        FontHelper.renderFontCentered(sb, FontHelper.cardTitleFont, TEXT[3], (float) Settings.WIDTH / 2.0f + 110.0f * Settings.scale, Settings.OPTION_Y - 118.0f * Settings.scale, c, 0.82f);
        if (Settings.isControllerMode) {
            sb.draw(CInputActionSet.proceed.getKeyImg(), 770.0f * Settings.xScale - 32.0f, Settings.OPTION_Y - 32.0f - 140.0f * Settings.scale, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
            sb.draw(CInputActionSet.cancel.getKeyImg(), 1150.0f * Settings.xScale - 32.0f, Settings.OPTION_Y - 32.0f - 140.0f * Settings.scale, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
        }
        if (this.shown) {
            this.yesHb.render(sb);
            this.noHb.render(sb);
        }
    }

    public void open(int slot, boolean isNewSave) {
        Gdx.input.setInputProcessor(new TypeHelper());
        if (SteamInputHelper.numControllers == 1 && CardCrawlGame.clientUtils != null && CardCrawlGame.clientUtils.isSteamRunningOnSteamDeck()) {
            CardCrawlGame.clientUtils.showFloatingGamepadTextInput(SteamUtils.FloatingGamepadTextInputMode.ModeSingleLine, 0, 0, Settings.WIDTH, (int) ((float) Settings.HEIGHT * 0.25f));
        }
        this.updateHitboxPositions();
        this.slot = slot;
        this.newSave = isNewSave;
        this.shown = true;
        textField = "";
    }
}

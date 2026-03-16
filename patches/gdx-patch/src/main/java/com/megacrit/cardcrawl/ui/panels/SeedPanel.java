package com.megacrit.cardcrawl.ui.panels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Clipboard;
import com.codedisaster.steamworks.SteamUtils;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.SeedHelper;
import com.megacrit.cardcrawl.helpers.TypeHelper;
import com.megacrit.cardcrawl.helpers.controller.CInputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.helpers.input.ScrollInputProcessor;
import com.megacrit.cardcrawl.helpers.steamInput.SteamInputHelper;
import com.megacrit.cardcrawl.localization.UIStrings;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Android runtime patch for seed input popup buttons.
 *
 * Like RenamePopup, the original desktop class waits for Hitbox.clicked to be
 * completed on release. Commit touch button actions on press so confirm/cancel
 * still work after switching the input processor to TypeHelper.
 */
public class SeedPanel {
    private static final Logger logger = LogManager.getLogger(SeedPanel.class.getName());
    private static final UIStrings uiStrings = CardCrawlGame.languagePack.getUIString("SeedPanel");
    public static final String[] TEXT = SeedPanel.uiStrings.TEXT;
    public String title;
    public static String textField = "";
    public Hitbox yesHb = new Hitbox(160.0f * Settings.scale, 70.0f * Settings.scale);
    public Hitbox noHb = new Hitbox(160.0f * Settings.scale, 70.0f * Settings.scale);
    private static final int CONFIRM_W = 360;
    private static final int CONFIRM_H = 414;
    private static final int YES_W = 173;
    private static final int NO_W = 161;
    private static final int BUTTON_H = 74;
    private Color screenColor = new Color(0.0f, 0.0f, 0.0f, 0.0f);
    private Color uiColor = new Color(1.0f, 1.0f, 1.0f, 0.0f);
    private float animTimer = 0.0f;
    private float waitTimer = 0.0f;
    private static final float ANIM_TIME = 0.25f;
    public boolean shown = false;
    private static final float SCREEN_DARKNESS = 0.8f;
    public MainMenuScreen.CurScreen sourceScreen = null;

    private void updateHitboxPositions() {
        float centerX = (float) Settings.WIDTH / 2.0f;
        float centerY = Settings.OPTION_Y - 118.0f * Settings.scale;
        float yesCenterX = centerX - 100.0f * Settings.scale;
        float noCenterX = centerX + 102.0f * Settings.scale;
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
            if (this.animTimer != 0.0f) {
                this.animTimer -= Gdx.graphics.getDeltaTime();
                if (this.animTimer < 0.0f) {
                    this.animTimer = 0.0f;
                }
                this.screenColor.a = Interpolation.fade.apply(0.8f, 0.0f, this.animTimer * 1.0f / 0.25f);
                this.uiColor.a = Interpolation.fade.apply(1.0f, 0.0f, this.animTimer * 1.0f / 0.25f);
            } else {
                Clipboard clipBoard;
                String pasteText;
                String seedText;
                this.updateYes();
                this.updateNo();
                if (InputActionSet.confirm.isJustPressed()) {
                    this.confirm();
                } else if (InputHelper.pressedEscape) {
                    InputHelper.pressedEscape = false;
                    this.cancel();
                } else if (InputHelper.isPasteJustPressed()
                    && !(seedText = SeedHelper.sterilizeString(pasteText = (clipBoard = Gdx.app.getClipboard()).getContents())).isEmpty()) {
                    textField = seedText;
                }
            }
        } else if (this.animTimer != 0.0f) {
            this.animTimer -= Gdx.graphics.getDeltaTime();
            if (this.animTimer < 0.0f) {
                this.animTimer = 0.0f;
            }
            this.screenColor.a = Interpolation.fade.apply(0.0f, 0.8f, this.animTimer * 1.0f / 0.25f);
            this.uiColor.a = Interpolation.fade.apply(0.0f, 1.0f, this.animTimer * 1.0f / 0.25f);
        }
    }

    private void updateYes() {
        this.yesHb.update();
        if (this.yesHb.justHovered) {
            CardCrawlGame.sound.play("UI_HOVER");
        }
        if (InputHelper.justClickedLeft && this.yesHb.hovered) {
            CardCrawlGame.sound.play("UI_CLICK_1");
            this.yesHb.clicked = true;
        } else if (!this.yesHb.clicked && this.yesHb.hovered) {
            this.yesHb.clickStarted = false;
        }
        if (CInputActionSet.proceed.isJustPressed()) {
            CInputActionSet.proceed.unpress();
            this.yesHb.clicked = true;
        }
        if (this.yesHb.clicked) {
            this.yesHb.clicked = false;
            this.confirm();
        }
    }

    private void updateNo() {
        this.noHb.update();
        if (this.noHb.justHovered) {
            CardCrawlGame.sound.play("UI_HOVER");
        }
        if (InputHelper.justClickedLeft && this.noHb.hovered) {
            CardCrawlGame.sound.play("UI_CLICK_1");
            this.noHb.clicked = true;
        } else if (!this.noHb.clicked && this.noHb.hovered) {
            this.noHb.clickStarted = false;
        }
        if (CInputActionSet.cancel.isJustPressed()) {
            this.noHb.clicked = true;
        }
        if (this.noHb.clicked) {
            this.noHb.clicked = false;
            this.cancel();
        }
    }

    public void show() {
        Gdx.input.setInputProcessor(new TypeHelper(true));
        if (SteamInputHelper.numControllers == 1 && CardCrawlGame.clientUtils != null && CardCrawlGame.clientUtils.isSteamRunningOnSteamDeck()) {
            CardCrawlGame.clientUtils.showFloatingGamepadTextInput(SteamUtils.FloatingGamepadTextInputMode.ModeSingleLine, 0, 0, Settings.WIDTH, (int) ((float) Settings.HEIGHT * 0.25f));
        }
        this.updateHitboxPositions();
        this.shown = true;
        this.animTimer = 0.25f;
        textField = SeedHelper.getUserFacingSeedString();
    }

    public void show(MainMenuScreen.CurScreen sourceScreen) {
        this.show();
        this.sourceScreen = sourceScreen;
    }

    public void confirm() {
        textField = textField.trim();
        try {
            SeedHelper.setSeed(textField);
        } catch (NumberFormatException e) {
            Settings.seed = Long.MAX_VALUE;
        }
        this.close();
    }

    public void cancel() {
        this.close();
    }

    public void close() {
        this.yesHb.move(-1000.0f, -1000.0f);
        this.noHb.move(-1000.0f, -1000.0f);
        this.shown = false;
        this.animTimer = 0.25f;
        Gdx.input.setInputProcessor(new ScrollInputProcessor());
        if (this.sourceScreen == null) {
            CardCrawlGame.mainMenuScreen.screen = MainMenuScreen.CurScreen.CHAR_SELECT;
        } else {
            CardCrawlGame.mainMenuScreen.screen = this.sourceScreen;
            this.sourceScreen = null;
        }
    }

    public static boolean isFull() {
        return textField.length() >= SeedHelper.SEED_DEFAULT_LENGTH;
    }

    public void render(SpriteBatch sb) {
        sb.setColor(this.screenColor);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, 0.0f, 0.0f, (float) Settings.WIDTH, (float) Settings.HEIGHT);
        sb.setColor(this.uiColor);
        sb.draw(ImageMaster.OPTION_CONFIRM, (float) Settings.WIDTH / 2.0f - 180.0f, Settings.OPTION_Y - 207.0f, 180.0f, 207.0f, 360.0f, 414.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 360, 414, false, false);
        sb.draw(ImageMaster.RENAME_BOX, (float) Settings.WIDTH / 2.0f - 160.0f, Settings.OPTION_Y - 160.0f, 160.0f, 160.0f, 320.0f, 320.0f, Settings.scale * 1.1f, Settings.scale * 1.1f, 0.0f, 0, 0, 320, 320, false, false);
        FontHelper.renderSmartText(sb, FontHelper.cardTitleFont, textField, (float) Settings.WIDTH / 2.0f - 120.0f * Settings.scale, Settings.OPTION_Y + 4.0f * Settings.scale, 100000.0f, 0.0f, this.uiColor, 0.82f);
        if (!SeedPanel.isFull()) {
            float tmpAlpha = (MathUtils.cosDeg(System.currentTimeMillis() / 3L % 360L) + 1.25f) / 3.0f * this.uiColor.a;
            FontHelper.renderSmartText(sb, FontHelper.cardTitleFont, "_", (float) Settings.WIDTH / 2.0f - 122.0f * Settings.scale + FontHelper.getSmartWidth(FontHelper.cardTitleFont, textField, 1000000.0f, 0.0f, 0.82f), Settings.OPTION_Y + 4.0f * Settings.scale, 100000.0f, 0.0f, new Color(1.0f, 1.0f, 1.0f, tmpAlpha), 0.82f);
        }
        Color c = Settings.GOLD_COLOR.cpy();
        c.a = this.uiColor.a;
        FontHelper.renderFontCentered(sb, FontHelper.cardTitleFont, TEXT[1], (float) Settings.WIDTH / 2.0f, Settings.OPTION_Y + 126.0f * Settings.scale, c);
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
        c = this.yesHb.clickStarted ? Color.LIGHT_GRAY.cpy() : (this.yesHb.hovered ? Settings.CREAM_COLOR.cpy() : Settings.GOLD_COLOR.cpy());
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
        if (this.shown) {
            if (Settings.isControllerMode) {
                sb.draw(CInputActionSet.proceed.getKeyImg(), 770.0f * Settings.scale - 32.0f, Settings.OPTION_Y - 32.0f - 140.0f * Settings.scale, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
                sb.draw(CInputActionSet.cancel.getKeyImg(), 1150.0f * Settings.scale - 32.0f, Settings.OPTION_Y - 32.0f - 140.0f * Settings.scale, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
            }
            this.yesHb.render(sb);
            this.noHb.render(sb);
            if (!Settings.usesTrophies) {
                FontHelper.renderFontCentered(sb, FontHelper.panelNameFont, TEXT[4], (float) Settings.WIDTH / 2.0f, 100.0f * Settings.scale, new Color(1.0f, 0.3f, 0.3f, c.a));
            } else {
                FontHelper.renderFontCentered(sb, FontHelper.panelNameFont, TEXT[5], (float) Settings.WIDTH / 2.0f, 100.0f * Settings.scale, new Color(1.0f, 0.3f, 0.3f, c.a));
            }
        }
    }
}

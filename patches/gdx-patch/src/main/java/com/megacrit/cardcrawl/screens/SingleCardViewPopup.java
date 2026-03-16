/*
 * Decompiled with CFR 0.152.
 */
package com.megacrit.cardcrawl.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.math.Interpolation;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.GameDictionary;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.PowerTip;
import com.megacrit.cardcrawl.helpers.TipHelper;
import com.megacrit.cardcrawl.helpers.controller.CInputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.localization.LocalizedStrings;
import com.megacrit.cardcrawl.localization.UIStrings;
import com.megacrit.cardcrawl.unlock.UnlockTracker;
import java.util.ArrayList;

public class SingleCardViewPopup {
    private static final UIStrings uiStrings = CardCrawlGame.languagePack.getUIString("SingleCardViewPopup");
    public static final String[] TEXT = SingleCardViewPopup.uiStrings.TEXT;
    public boolean isOpen = false;
    private CardGroup group;
    private AbstractCard card;
    private AbstractCard prevCard;
    private AbstractCard nextCard;
    private Texture portraitImg = null;
    private Hitbox nextHb;
    private Hitbox prevHb;
    private Hitbox cardHb;
    private float fadeTimer = 0.0f;
    private Color fadeColor = Color.BLACK.cpy();
    private static final float LINE_SPACING = 1.53f;
    private float current_x;
    private float current_y;
    private float drawScale;
    private float card_energy_w;
    private static final float DESC_OFFSET_Y2 = -12.0f;
    private static final Color CARD_TYPE_COLOR = new Color(0.35f, 0.35f, 0.35f, 1.0f);
    private static final GlyphLayout gl = new GlyphLayout();
    public static boolean isViewingUpgrade = false;
    public static boolean enableUpgradeToggle = true;
    private static final String COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED_PROP =
        "amethyst.compendium_upgrade_touch_fix_enabled";
    private static final float DEFAULT_BOTTOM_TOGGLE_W = 250.0f;
    private static final float DEFAULT_BOTTOM_TOGGLE_H = 80.0f;
    private static final float DEFAULT_BOTTOM_TOGGLE_CENTER_Y = 70.0f;
    private static final float TOUCH_BOTTOM_TOGGLE_W = 340.0f;
    private static final float TOUCH_BOTTOM_TOGGLE_H = 112.0f;
    private static final float TOUCH_BOTTOM_TOGGLE_CENTER_Y = 96.0f;
    private static Boolean compendiumUpgradeTouchFixEnabled = null;
    private Hitbox upgradeHb = new Hitbox(getBottomToggleWidth(), getBottomToggleHeight());
    private Hitbox betaArtHb = null;
    private boolean viewBetaArt = false;

    public SingleCardViewPopup() {
        this.prevHb = new Hitbox(200.0f * Settings.scale, 70.0f * Settings.scale);
        this.nextHb = new Hitbox(200.0f * Settings.scale, 70.0f * Settings.scale);
    }

    public void open(AbstractCard card, CardGroup group) {
        CardCrawlGame.isPopupOpen = true;
        this.prevCard = null;
        this.nextCard = null;
        this.prevHb = null;
        this.nextHb = null;
        for (int i = 0; i < group.size(); ++i) {
            if (group.group.get(i) != card) continue;
            if (i != 0) {
                this.prevCard = group.group.get(i - 1);
            }
            if (i == group.size() - 1) break;
            this.nextCard = group.group.get(i + 1);
            break;
        }
        this.prevHb = new Hitbox(160.0f * Settings.scale, 160.0f * Settings.scale);
        this.nextHb = new Hitbox(160.0f * Settings.scale, 160.0f * Settings.scale);
        this.prevHb.move((float)Settings.WIDTH / 2.0f - 400.0f * Settings.scale, (float)Settings.HEIGHT / 2.0f);
        this.nextHb.move((float)Settings.WIDTH / 2.0f + 400.0f * Settings.scale, (float)Settings.HEIGHT / 2.0f);
        this.card_energy_w = 24.0f * Settings.scale;
        this.drawScale = 2.0f;
        this.cardHb = new Hitbox(550.0f * Settings.scale, 770.0f * Settings.scale);
        this.cardHb.move((float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f);
        this.card = card.makeStatEquivalentCopy();
        this.loadPortraitImg();
        this.group = group;
        this.isOpen = true;
        this.fadeTimer = 0.25f;
        this.fadeColor.a = 0.0f;
        this.current_x = (float)Settings.WIDTH / 2.0f - 10.0f * Settings.scale;
        this.current_y = (float)Settings.HEIGHT / 2.0f - 300.0f * Settings.scale;
        if (this.canToggleBetaArt()) {
            if (this.allowUpgradePreview()) {
                this.betaArtHb = new Hitbox(getBottomToggleWidth(), getBottomToggleHeight());
                this.betaArtHb.move(
                    (float)Settings.WIDTH / 2.0f + 270.0f * Settings.scale,
                    getBottomToggleCenterY()
                );
                this.upgradeHb.move(
                    (float)Settings.WIDTH / 2.0f - 180.0f * Settings.scale,
                    getBottomToggleCenterY()
                );
            } else {
                this.betaArtHb = new Hitbox(getBottomToggleWidth(), getBottomToggleHeight());
                this.betaArtHb.move((float)Settings.WIDTH / 2.0f, getBottomToggleCenterY());
            }
            this.viewBetaArt = UnlockTracker.betaCardPref.getBoolean(card.cardID, false);
        } else {
            this.upgradeHb.move((float)Settings.WIDTH / 2.0f, getBottomToggleCenterY());
            this.betaArtHb = null;
        }
        this.refreshBottomToggleHitboxes();
    }

    private boolean canToggleBetaArt() {
        if (UnlockTracker.isAchievementUnlocked("THE_ENDING")) {
            return true;
        }
        switch (this.card.color) {
            case RED: {
                return UnlockTracker.isAchievementUnlocked("RUBY_PLUS");
            }
            case GREEN: {
                return UnlockTracker.isAchievementUnlocked("EMERALD_PLUS");
            }
            case BLUE: {
                return UnlockTracker.isAchievementUnlocked("SAPPHIRE_PLUS");
            }
            case PURPLE: {
                return UnlockTracker.isAchievementUnlocked("AMETHYST_PLUS");
            }
        }
        return false;
    }

    private void loadPortraitImg() {
        if (Settings.PLAYTESTER_ART_MODE || UnlockTracker.betaCardPref.getBoolean(this.card.cardID, false)) {
            this.portraitImg = ImageMaster.loadImage("images/1024PortraitsBeta/" + this.card.assetUrl + ".png");
        } else {
            this.portraitImg = ImageMaster.loadImage("images/1024Portraits/" + this.card.assetUrl + ".png");
            if (this.portraitImg == null) {
                this.portraitImg = ImageMaster.loadImage("images/1024PortraitsBeta/" + this.card.assetUrl + ".png");
            }
        }
    }

    public void open(AbstractCard card) {
        CardCrawlGame.isPopupOpen = true;
        this.prevCard = null;
        this.nextCard = null;
        this.prevHb = null;
        this.nextHb = null;
        this.card_energy_w = 24.0f * Settings.scale;
        this.drawScale = 2.0f;
        this.cardHb = new Hitbox(550.0f * Settings.scale, 770.0f * Settings.scale);
        this.cardHb.move((float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f);
        this.card = card.makeStatEquivalentCopy();
        this.loadPortraitImg();
        this.group = null;
        this.isOpen = true;
        this.fadeTimer = 0.25f;
        this.fadeColor.a = 0.0f;
        this.current_x = (float)Settings.WIDTH / 2.0f - 10.0f * Settings.scale;
        this.current_y = (float)Settings.HEIGHT / 2.0f - 300.0f * Settings.scale;
        this.betaArtHb = null;
        if (this.canToggleBetaArt()) {
            this.betaArtHb = new Hitbox(getBottomToggleWidth(), getBottomToggleHeight());
            this.betaArtHb.move(
                (float)Settings.WIDTH / 2.0f + 270.0f * Settings.scale,
                getBottomToggleCenterY()
            );
            this.upgradeHb.move(
                (float)Settings.WIDTH / 2.0f - 180.0f * Settings.scale,
                getBottomToggleCenterY()
            );
            this.viewBetaArt = UnlockTracker.betaCardPref.getBoolean(card.cardID, false);
        } else {
            this.upgradeHb.move((float)Settings.WIDTH / 2.0f, getBottomToggleCenterY());
        }
        this.refreshBottomToggleHitboxes();
    }

    public void close() {
        isViewingUpgrade = false;
        InputHelper.justReleasedClickLeft = false;
        CardCrawlGame.isPopupOpen = false;
        this.isOpen = false;
        if (this.portraitImg != null) {
            this.portraitImg.dispose();
            this.portraitImg = null;
        }
    }

    public void update() {
        this.refreshBottomToggleHitboxes();
        this.cardHb.update();
        this.updateArrows();
        if (this.allowUpgradePreview()) {
            this.updateUpgradePreview();
        }
        if (this.betaArtHb != null && this.canToggleBetaArt()) {
            this.updateBetaArtToggler();
        }
        this.updateInput();
        this.updateFade();
    }

    private void updateBetaArtToggler() {
        this.betaArtHb.update();
        if (this.betaArtHb.hovered && InputHelper.justClickedLeft) {
            this.betaArtHb.clickStarted = true;
        }
        if (this.betaArtHb.clicked || CInputActionSet.topPanel.isJustPressed()) {
            CInputActionSet.topPanel.unpress();
            this.betaArtHb.clicked = false;
            this.viewBetaArt = !this.viewBetaArt;
            UnlockTracker.betaCardPref.putBoolean(this.card.cardID, this.viewBetaArt);
            UnlockTracker.betaCardPref.flush();
            if (this.portraitImg != null) {
                this.portraitImg.dispose();
            }
            this.loadPortraitImg();
        }
    }

    private void updateUpgradePreview() {
        this.upgradeHb.update();
        if (this.upgradeHb.hovered && InputHelper.justClickedLeft) {
            if (isCompendiumUpgradeTouchFixActive()) {
                CInputActionSet.proceed.unpress();
                this.upgradeHb.clicked = false;
                isViewingUpgrade = !isViewingUpgrade;
                InputHelper.justClickedLeft = false;
                InputHelper.justReleasedClickLeft = false;
                return;
            }
            this.upgradeHb.clickStarted = true;
        }
        if (this.upgradeHb.clicked || CInputActionSet.proceed.isJustPressed()) {
            CInputActionSet.proceed.unpress();
            this.upgradeHb.clicked = false;
            isViewingUpgrade = !isViewingUpgrade;
        }
    }

    private boolean allowUpgradePreview() {
        return enableUpgradeToggle && this.card.color != AbstractCard.CardColor.CURSE && this.card.type != AbstractCard.CardType.STATUS;
    }

    private void updateArrows() {
        if (this.prevCard != null) {
            this.prevHb.update();
            if (this.prevHb.justHovered) {
                CardCrawlGame.sound.play("UI_HOVER");
            }
            if (this.prevHb.clicked || this.prevCard != null && CInputActionSet.pageLeftViewDeck.isJustPressed()) {
                CInputActionSet.pageLeftViewDeck.unpress();
                this.openPrev();
            }
        }
        if (this.nextCard != null) {
            this.nextHb.update();
            if (this.nextHb.justHovered) {
                CardCrawlGame.sound.play("UI_HOVER");
            }
            if (this.nextHb.clicked || this.nextCard != null && CInputActionSet.pageRightViewExhaust.isJustPressed()) {
                CInputActionSet.pageRightViewExhaust.unpress();
                this.openNext();
            }
        }
    }

    private void updateInput() {
        if (InputHelper.justClickedLeft) {
            if (this.prevCard != null && this.prevHb.hovered) {
                this.prevHb.clickStarted = true;
                CardCrawlGame.sound.play("UI_CLICK_1");
                return;
            }
            if (this.nextCard != null && this.nextHb.hovered) {
                this.nextHb.clickStarted = true;
                CardCrawlGame.sound.play("UI_CLICK_1");
                return;
            }
        }
        if (InputHelper.justClickedLeft) {
            if (!(this.cardHb.hovered || this.upgradeHb.hovered || this.betaArtHb != null && (this.betaArtHb == null || this.betaArtHb.hovered))) {
                this.close();
                InputHelper.justClickedLeft = false;
                FontHelper.ClearSCPFontTextures();
            }
        } else if (InputHelper.pressedEscape || CInputActionSet.cancel.isJustPressed()) {
            CInputActionSet.cancel.unpress();
            InputHelper.pressedEscape = false;
            this.close();
            FontHelper.ClearSCPFontTextures();
        }
        if (this.prevCard != null && InputActionSet.left.isJustPressed()) {
            this.openPrev();
        } else if (this.nextCard != null && InputActionSet.right.isJustPressed()) {
            this.openNext();
        }
    }

    private void refreshBottomToggleHitboxes() {
        this.upgradeHb.resize(getBottomToggleWidth(), getBottomToggleHeight());
        if (this.betaArtHb != null) {
            this.betaArtHb.resize(getBottomToggleWidth(), getBottomToggleHeight());
        }
    }

    private static float getBottomToggleWidth() {
        if (isCompendiumUpgradeTouchFixActive()) {
            return TOUCH_BOTTOM_TOGGLE_W * Settings.scale;
        }
        return DEFAULT_BOTTOM_TOGGLE_W * Settings.scale;
    }

    private static float getBottomToggleHeight() {
        if (isCompendiumUpgradeTouchFixActive()) {
            return TOUCH_BOTTOM_TOGGLE_H * Settings.scale;
        }
        return DEFAULT_BOTTOM_TOGGLE_H * Settings.scale;
    }

    private static float getBottomToggleCenterY() {
        if (isCompendiumUpgradeTouchFixActive()) {
            return TOUCH_BOTTOM_TOGGLE_CENTER_Y * Settings.scale;
        }
        return DEFAULT_BOTTOM_TOGGLE_CENTER_Y * Settings.scale;
    }

    private static boolean isCompendiumUpgradeTouchFixActive() {
        return Settings.isTouchScreen && isCompendiumUpgradeTouchFixEnabled();
    }

    private static boolean isCompendiumUpgradeTouchFixEnabled() {
        if (compendiumUpgradeTouchFixEnabled != null) {
            return compendiumUpgradeTouchFixEnabled.booleanValue();
        }
        Boolean parsed = parseBooleanLike(
            System.getProperty(COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED_PROP)
        );
        compendiumUpgradeTouchFixEnabled = parsed == null ? Boolean.TRUE : parsed;
        return compendiumUpgradeTouchFixEnabled.booleanValue();
    }

    private static Boolean parseBooleanLike(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(normalized)
            || "1".equals(normalized)
            || "yes".equalsIgnoreCase(normalized)
            || "on".equalsIgnoreCase(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(normalized)
            || "0".equals(normalized)
            || "no".equalsIgnoreCase(normalized)
            || "off".equalsIgnoreCase(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private void openPrev() {
        boolean tmp = isViewingUpgrade;
        this.close();
        this.open(this.prevCard, this.group);
        isViewingUpgrade = tmp;
        this.fadeTimer = 0.0f;
        this.fadeColor.a = 0.9f;
    }

    private void openNext() {
        boolean tmp = isViewingUpgrade;
        this.close();
        this.open(this.nextCard, this.group);
        isViewingUpgrade = tmp;
        this.fadeTimer = 0.0f;
        this.fadeColor.a = 0.9f;
    }

    private void updateFade() {
        this.fadeTimer -= Gdx.graphics.getDeltaTime();
        if (this.fadeTimer < 0.0f) {
            this.fadeTimer = 0.0f;
        }
        this.fadeColor.a = Interpolation.pow2In.apply(0.9f, 0.0f, this.fadeTimer * 4.0f);
    }

    public void render(SpriteBatch sb) {
        AbstractCard copy = null;
        if (isViewingUpgrade) {
            copy = this.card.makeStatEquivalentCopy();
            this.card.upgrade();
            this.card.displayUpgrades();
        }
        sb.setColor(this.fadeColor);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, 0.0f, 0.0f, (float)Settings.WIDTH, (float)Settings.HEIGHT);
        sb.setColor(Color.WHITE);
        this.renderCardBack(sb);
        this.renderPortrait(sb);
        this.renderFrame(sb);
        this.renderCardBanner(sb);
        this.renderCardTypeText(sb);
        if (Settings.lineBreakViaCharacter) {
            this.renderDescriptionCN(sb);
        } else {
            this.renderDescription(sb);
        }
        this.renderTitle(sb);
        this.renderCost(sb);
        this.renderArrows(sb);
        this.renderTips(sb);
        this.cardHb.render(sb);
        if (this.nextHb != null) {
            this.nextHb.render(sb);
        }
        if (this.prevHb != null) {
            this.prevHb.render(sb);
        }
        FontHelper.cardTitleFont.getData().setScale(1.0f);
        if (this.canToggleBetaArt()) {
            this.renderBetaArtToggle(sb);
        }
        if (this.allowUpgradePreview()) {
            this.renderUpgradeViewToggle(sb);
            if (Settings.isControllerMode) {
                sb.draw(CInputActionSet.proceed.getKeyImg(), this.upgradeHb.cX - 132.0f * Settings.scale - 32.0f, -32.0f + 67.0f * Settings.scale, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
            }
        }
        if (this.betaArtHb != null && Settings.isControllerMode) {
            sb.draw(CInputActionSet.topPanel.getKeyImg(), this.betaArtHb.cX - 132.0f * Settings.scale - 32.0f, -32.0f + 67.0f * Settings.scale, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
        }
        if (copy != null) {
            this.card = copy;
        }
    }

    public void renderCardBack(SpriteBatch sb) {
        TextureAtlas.AtlasRegion tmpImg = this.getCardBackAtlasRegion();
        if (tmpImg != null) {
            this.renderHelper(sb, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f, tmpImg);
        } else {
            Texture img = this.getCardBackImg();
            if (img != null) {
                sb.draw(img, (float)Settings.WIDTH / 2.0f - 512.0f, (float)Settings.HEIGHT / 2.0f - 512.0f, 512.0f, 512.0f, 1024.0f, 1024.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 1024, 1024, false, false);
            }
        }
    }

    private Texture getCardBackImg() {
        switch (this.card.type) {
            case ATTACK: {
                switch (this.card.color) {
                    default: 
                }
            }
            case POWER: {
                switch (this.card.color) {
                    default: 
                }
            }
        }
        switch (this.card.color) {
            default: 
        }
        return null;
    }

    private TextureAtlas.AtlasRegion getCardBackAtlasRegion() {
        switch (this.card.type) {
            case ATTACK: {
                switch (this.card.color) {
                    case RED: {
                        return ImageMaster.CARD_ATTACK_BG_RED_L;
                    }
                    case GREEN: {
                        return ImageMaster.CARD_ATTACK_BG_GREEN_L;
                    }
                    case BLUE: {
                        return ImageMaster.CARD_ATTACK_BG_BLUE_L;
                    }
                    case PURPLE: {
                        return ImageMaster.CARD_ATTACK_BG_PURPLE_L;
                    }
                    case COLORLESS: {
                        return ImageMaster.CARD_ATTACK_BG_GRAY_L;
                    }
                }
            }
            case POWER: {
                switch (this.card.color) {
                    case RED: {
                        return ImageMaster.CARD_POWER_BG_RED_L;
                    }
                    case GREEN: {
                        return ImageMaster.CARD_POWER_BG_GREEN_L;
                    }
                    case BLUE: {
                        return ImageMaster.CARD_POWER_BG_BLUE_L;
                    }
                    case PURPLE: {
                        return ImageMaster.CARD_POWER_BG_PURPLE_L;
                    }
                    case COLORLESS: {
                        return ImageMaster.CARD_POWER_BG_GRAY_L;
                    }
                }
            }
        }
        switch (this.card.color) {
            case RED: {
                return ImageMaster.CARD_SKILL_BG_RED_L;
            }
            case GREEN: {
                return ImageMaster.CARD_SKILL_BG_GREEN_L;
            }
            case BLUE: {
                return ImageMaster.CARD_SKILL_BG_BLUE_L;
            }
            case PURPLE: {
                return ImageMaster.CARD_SKILL_BG_PURPLE_L;
            }
            case COLORLESS: {
                return ImageMaster.CARD_SKILL_BG_GRAY_L;
            }
            case CURSE: {
                return ImageMaster.CARD_SKILL_BG_BLACK_L;
            }
        }
        return null;
    }

    private void renderPortrait(SpriteBatch sb) {
        TextureAtlas.AtlasRegion img = null;
        if (this.card.isLocked) {
            switch (this.card.type) {
                case ATTACK: {
                    img = ImageMaster.CARD_LOCKED_ATTACK_L;
                    break;
                }
                case POWER: {
                    img = ImageMaster.CARD_LOCKED_POWER_L;
                    break;
                }
                default: {
                    img = ImageMaster.CARD_LOCKED_SKILL_L;
                }
            }
            this.renderHelper(sb, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f + 136.0f * Settings.scale, img);
        } else if (this.portraitImg != null) {
            sb.draw(this.portraitImg, (float)Settings.WIDTH / 2.0f - 250.0f, (float)Settings.HEIGHT / 2.0f - 190.0f + 136.0f * Settings.scale, 250.0f, 190.0f, 500.0f, 380.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 500, 380, false, false);
        } else if (this.card.jokePortrait != null) {
            sb.draw(this.card.jokePortrait, (float)Settings.WIDTH / 2.0f - (float)this.card.portrait.packedWidth / 2.0f, (float)Settings.HEIGHT / 2.0f - (float)this.card.portrait.packedHeight / 2.0f + 140.0f * Settings.scale, (float)this.card.portrait.packedWidth / 2.0f, (float)this.card.portrait.packedHeight / 2.0f, this.card.portrait.packedWidth, this.card.portrait.packedHeight, this.drawScale * Settings.scale, this.drawScale * Settings.scale, 0.0f);
        }
    }

    private void renderFrame(SpriteBatch sb) {
        TextureAtlas.AtlasRegion tmpImg = null;
        float tOffset = 0.0f;
        float tWidth = 0.0f;
        block0 : switch (this.card.type) {
            case ATTACK: {
                tWidth = AbstractCard.typeWidthAttack;
                tOffset = AbstractCard.typeOffsetAttack;
                switch (this.card.rarity) {
                    case UNCOMMON: {
                        tmpImg = ImageMaster.CARD_FRAME_ATTACK_UNCOMMON_L;
                        break block0;
                    }
                    case RARE: {
                        tmpImg = ImageMaster.CARD_FRAME_ATTACK_RARE_L;
                        break block0;
                    }
                }
                tmpImg = ImageMaster.CARD_FRAME_ATTACK_COMMON_L;
                break;
            }
            case POWER: {
                tWidth = AbstractCard.typeWidthPower;
                tOffset = AbstractCard.typeOffsetPower;
                switch (this.card.rarity) {
                    case UNCOMMON: {
                        tmpImg = ImageMaster.CARD_FRAME_POWER_UNCOMMON_L;
                        break block0;
                    }
                    case RARE: {
                        tmpImg = ImageMaster.CARD_FRAME_POWER_RARE_L;
                        break block0;
                    }
                }
                tmpImg = ImageMaster.CARD_FRAME_POWER_COMMON_L;
                break;
            }
            case CURSE: {
                tWidth = AbstractCard.typeWidthCurse;
                tOffset = AbstractCard.typeOffsetCurse;
                switch (this.card.rarity) {
                    case UNCOMMON: {
                        tmpImg = ImageMaster.CARD_FRAME_SKILL_UNCOMMON_L;
                        break block0;
                    }
                    case RARE: {
                        tmpImg = ImageMaster.CARD_FRAME_SKILL_RARE_L;
                        break block0;
                    }
                }
                tmpImg = ImageMaster.CARD_FRAME_SKILL_COMMON_L;
                break;
            }
            case STATUS: {
                tWidth = AbstractCard.typeWidthStatus;
                tOffset = AbstractCard.typeOffsetStatus;
                switch (this.card.rarity) {
                    case UNCOMMON: {
                        tmpImg = ImageMaster.CARD_FRAME_SKILL_UNCOMMON_L;
                        break block0;
                    }
                    case RARE: {
                        tmpImg = ImageMaster.CARD_FRAME_SKILL_RARE_L;
                        break block0;
                    }
                }
                tmpImg = ImageMaster.CARD_FRAME_SKILL_COMMON_L;
                break;
            }
            case SKILL: {
                tWidth = AbstractCard.typeWidthSkill;
                tOffset = AbstractCard.typeOffsetSkill;
                switch (this.card.rarity) {
                    case UNCOMMON: {
                        tmpImg = ImageMaster.CARD_FRAME_SKILL_UNCOMMON_L;
                        break block0;
                    }
                    case RARE: {
                        tmpImg = ImageMaster.CARD_FRAME_SKILL_RARE_L;
                        break block0;
                    }
                }
                tmpImg = ImageMaster.CARD_FRAME_SKILL_COMMON_L;
            }
        }
        if (tmpImg != null) {
            this.renderHelper(sb, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f, tmpImg);
        } else {
            Texture img = this.getFrameImg();
            tWidth = AbstractCard.typeWidthSkill;
            tOffset = AbstractCard.typeOffsetSkill;
            if (img != null) {
                sb.draw(img, (float)Settings.WIDTH / 2.0f - 512.0f, (float)Settings.HEIGHT / 2.0f - 512.0f, 512.0f, 512.0f, 1024.0f, 1024.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 1024, 1024, false, false);
            } else {
                this.renderHelper(sb, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f, ImageMaster.CARD_FRAME_SKILL_COMMON_L);
            }
        }
        this.renderDynamicFrame(sb, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f, tOffset, tWidth);
    }

    private Texture getFrameImg() {
        switch (this.card.rarity) {
            default: 
        }
        return null;
    }

    private void renderDynamicFrame(SpriteBatch sb, float x, float y, float typeOffset, float typeWidth) {
        if (typeWidth <= 1.1f) {
            return;
        }
        switch (this.card.rarity) {
            case COMMON: 
            case BASIC: 
            case CURSE: 
            case SPECIAL: {
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_COMMON_FRAME_MID_L, 0.0f, typeWidth);
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_COMMON_FRAME_LEFT_L, -typeOffset, 1.0f);
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_COMMON_FRAME_RIGHT_L, typeOffset, 1.0f);
                break;
            }
            case UNCOMMON: {
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_UNCOMMON_FRAME_MID_L, 0.0f, typeWidth);
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_UNCOMMON_FRAME_LEFT_L, -typeOffset, 1.0f);
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_UNCOMMON_FRAME_RIGHT_L, typeOffset, 1.0f);
                break;
            }
            case RARE: {
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_RARE_FRAME_MID_L, 0.0f, typeWidth);
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_RARE_FRAME_LEFT_L, -typeOffset, 1.0f);
                this.dynamicFrameRenderHelper(sb, ImageMaster.CARD_RARE_FRAME_RIGHT_L, typeOffset, 1.0f);
            }
        }
    }

    private void dynamicFrameRenderHelper(SpriteBatch sb, TextureAtlas.AtlasRegion img, float xOffset, float xScale) {
        sb.draw(img, (float)Settings.WIDTH / 2.0f + img.offsetX - (float)img.originalWidth / 2.0f + xOffset * this.drawScale, (float)Settings.HEIGHT / 2.0f + img.offsetY - (float)img.originalHeight / 2.0f, (float)img.originalWidth / 2.0f - img.offsetX, (float)img.originalHeight / 2.0f - img.offsetY, img.packedWidth, img.packedHeight, Settings.scale * xScale, Settings.scale, 0.0f);
    }

    private void renderCardBanner(SpriteBatch sb) {
        TextureAtlas.AtlasRegion tmpImg = null;
        switch (this.card.rarity) {
            case UNCOMMON: {
                tmpImg = ImageMaster.CARD_BANNER_UNCOMMON_L;
                break;
            }
            case RARE: {
                tmpImg = ImageMaster.CARD_BANNER_RARE_L;
                break;
            }
            case COMMON: {
                tmpImg = ImageMaster.CARD_BANNER_COMMON_L;
            }
        }
        if (tmpImg != null) {
            this.renderHelper(sb, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f, tmpImg);
        } else {
            Texture img = this.getBannerImg();
            if (img != null) {
                sb.draw(img, (float)Settings.WIDTH / 2.0f - 512.0f, (float)Settings.HEIGHT / 2.0f - 512.0f, 512.0f, 512.0f, 1024.0f, 1024.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 1024, 1024, false, false);
            } else {
                tmpImg = ImageMaster.CARD_BANNER_COMMON_L;
                this.renderHelper(sb, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f, tmpImg);
            }
        }
    }

    private Texture getBannerImg() {
        switch (this.card.rarity) {
            default: 
        }
        return null;
    }

    private String getDynamicValue(char key) {
        switch (key) {
            case 'B': {
                if (this.card.isBlockModified) {
                    if (this.card.block >= this.card.baseBlock) {
                        return "[#7fff00]" + Integer.toString(this.card.block) + "[]";
                    }
                    return "[#ff6563]" + Integer.toString(this.card.block) + "[]";
                }
                return Integer.toString(this.card.baseBlock);
            }
            case 'D': {
                if (this.card.isDamageModified) {
                    if (this.card.damage >= this.card.baseDamage) {
                        return "[#7fff00]" + Integer.toString(this.card.damage) + "[]";
                    }
                    return "[#ff6563]" + Integer.toString(this.card.damage) + "[]";
                }
                return Integer.toString(this.card.baseDamage);
            }
            case 'M': {
                if (this.card.isMagicNumberModified) {
                    if (this.card.magicNumber >= this.card.baseMagicNumber) {
                        return "[#7fff00]" + Integer.toString(this.card.magicNumber) + "[]";
                    }
                    return "[#ff6563]" + Integer.toString(this.card.magicNumber) + "[]";
                }
                return Integer.toString(this.card.baseMagicNumber);
            }
        }
        return Integer.toString(-99);
    }

    private void renderDescriptionCN(SpriteBatch sb) {
        if (this.card.isLocked || !this.card.isSeen) {
            FontHelper.renderFontCentered(sb, FontHelper.largeCardFont, "? ? ?", (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f - 195.0f * Settings.scale, Settings.CREAM_COLOR);
            return;
        }
        BitmapFont font = FontHelper.SCP_cardDescFont;
        float draw_y = this.current_y + 100.0f * Settings.scale;
        draw_y += (float)this.card.description.size() * font.getCapHeight() * 0.775f - font.getCapHeight() * 0.375f;
        float spacing = 1.53f * -font.getCapHeight() / Settings.scale / this.drawScale;
        GlyphLayout gl = new GlyphLayout();
        for (int i = 0; i < this.card.description.size(); ++i) {
            float start_x = 0.0f;
            start_x = Settings.leftAlignCards ? this.current_x - 214.0f * Settings.scale : this.current_x - this.card.description.get((int)i).width * this.drawScale / 2.0f - 20.0f * Settings.scale;
            for (String tmp : this.card.description.get(i).getCachedTokenizedTextCN()) {
                int j;
                String updateTmp = null;
                for (j = 0; j < tmp.length(); ++j) {
                    if (tmp.charAt(j) != 'D' && (tmp.charAt(j) != 'B' || tmp.contains("[B]")) && tmp.charAt(j) != 'M') continue;
                    updateTmp = tmp.substring(0, j);
                    updateTmp = updateTmp + this.getDynamicValue(tmp.charAt(j));
                    updateTmp = updateTmp + tmp.substring(j + 1);
                    break;
                }
                if (updateTmp != null) {
                    tmp = updateTmp;
                }
                for (j = 0; j < tmp.length(); ++j) {
                    if (tmp.charAt(j) != 'D' && (tmp.charAt(j) != 'B' || tmp.contains("[B]")) && tmp.charAt(j) != 'M') continue;
                    updateTmp = tmp.substring(0, j);
                    updateTmp = updateTmp + this.getDynamicValue(tmp.charAt(j));
                    updateTmp = updateTmp + tmp.substring(j + 1);
                    break;
                }
                if (updateTmp != null) {
                    tmp = updateTmp;
                }
                if (!tmp.isEmpty() && tmp.charAt(0) == '*') {
                    tmp = tmp.substring(1);
                    String punctuation = "";
                    if (tmp.length() > 1 && tmp.charAt(tmp.length() - 2) != '+' && !Character.isLetter(tmp.charAt(tmp.length() - 2))) {
                        punctuation = punctuation + tmp.charAt(tmp.length() - 2);
                        tmp = tmp.substring(0, tmp.length() - 2);
                        punctuation = punctuation + ' ';
                    }
                    gl.setText(font, tmp);
                    FontHelper.renderRotatedText(sb, font, tmp, this.current_x, this.current_y, start_x - this.current_x + gl.width / 2.0f, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.GOLD_COLOR);
                    start_x = Math.round(start_x + gl.width);
                    gl.setText(font, punctuation);
                    FontHelper.renderRotatedText(sb, font, punctuation, this.current_x, this.current_y, start_x - this.current_x + gl.width / 2.0f, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
                    gl.setText(font, punctuation);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[R]")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_red, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[G]")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_green, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[B]")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_blue, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[W]")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_purple, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    start_x += gl.width;
                    continue;
                }
                gl.setText(font, tmp);
                FontHelper.renderRotatedText(sb, font, tmp, this.current_x, this.current_y, start_x - this.current_x + gl.width / 2.0f, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
                start_x += gl.width;
            }
        }
        font.getData().setScale(1.0f);
    }

    private void renderDescription(SpriteBatch sb) {
        if (this.card.isLocked || !this.card.isSeen) {
            FontHelper.renderFontCentered(sb, FontHelper.largeCardFont, "? ? ?", (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f - 195.0f * Settings.scale, Settings.CREAM_COLOR);
            return;
        }
        BitmapFont font = FontHelper.SCP_cardDescFont;
        float draw_y = this.current_y + 100.0f * Settings.scale;
        draw_y += (float)this.card.description.size() * font.getCapHeight() * 0.775f - font.getCapHeight() * 0.375f;
        float spacing = 1.53f * -font.getCapHeight() / Settings.scale / this.drawScale;
        GlyphLayout gl = new GlyphLayout();
        for (int i = 0; i < this.card.description.size(); ++i) {
            float start_x = this.current_x - this.card.description.get((int)i).width * this.drawScale / 2.0f;
            for (String tmp : this.card.description.get(i).getCachedTokenizedText()) {
                if (tmp.charAt(0) == '*') {
                    tmp = tmp.substring(1);
                    String punctuation = "";
                    if (tmp.length() > 1 && tmp.charAt(tmp.length() - 2) != '+' && !Character.isLetter(tmp.charAt(tmp.length() - 2))) {
                        punctuation = punctuation + tmp.charAt(tmp.length() - 2);
                        tmp = tmp.substring(0, tmp.length() - 2);
                        punctuation = punctuation + ' ';
                    }
                    gl.setText(font, tmp);
                    FontHelper.renderRotatedText(sb, font, tmp, this.current_x, this.current_y, start_x - this.current_x + gl.width / 2.0f, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.GOLD_COLOR);
                    start_x = Math.round(start_x + gl.width);
                    gl.setText(font, punctuation);
                    FontHelper.renderRotatedText(sb, font, punctuation, this.current_x, this.current_y, start_x - this.current_x + gl.width / 2.0f, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
                    gl.setText(font, punctuation);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.charAt(0) == '!') {
                    if (tmp.length() == 4) {
                        start_x += this.renderDynamicVariable(tmp.charAt(1), start_x, draw_y, i, font, sb, null);
                        continue;
                    }
                    if (tmp.length() != 5) continue;
                    start_x += this.renderDynamicVariable(tmp.charAt(1), start_x, draw_y, i, font, sb, Character.valueOf(tmp.charAt(3)));
                    continue;
                }
                if (tmp.equals("[R] ")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_red, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[R]. ")) {
                    gl.width = this.card_energy_w * this.drawScale / Settings.scale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_red, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    FontHelper.renderRotatedText(sb, font, LocalizedStrings.PERIOD, this.current_x, this.current_y, start_x - this.current_x + this.card_energy_w * this.drawScale, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
                    start_x += gl.width;
                    gl.setText(font, LocalizedStrings.PERIOD);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[G] ")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_green, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[G]. ")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_green, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    FontHelper.renderRotatedText(sb, font, LocalizedStrings.PERIOD, this.current_x, this.current_y, start_x - this.current_x + this.card_energy_w * this.drawScale, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[B] ")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_blue, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[B]. ")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_blue, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    FontHelper.renderRotatedText(sb, font, LocalizedStrings.PERIOD, this.current_x, this.current_y, start_x - this.current_x + this.card_energy_w * this.drawScale, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[W] ")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_purple, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    start_x += gl.width;
                    continue;
                }
                if (tmp.equals("[W]. ")) {
                    gl.width = this.card_energy_w * this.drawScale;
                    this.renderSmallEnergy(sb, AbstractCard.orb_purple, (start_x - this.current_x) / Settings.scale / this.drawScale, -87.0f - (((float)this.card.description.size() - 4.0f) / 2.0f - (float)i + 1.0f) * spacing);
                    FontHelper.renderRotatedText(sb, font, LocalizedStrings.PERIOD, this.current_x, this.current_y, start_x - this.current_x + this.card_energy_w * this.drawScale, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
                    start_x += gl.width;
                    continue;
                }
                gl.setText(font, tmp);
                FontHelper.renderRotatedText(sb, font, tmp, this.current_x, this.current_y, start_x - this.current_x + gl.width / 2.0f, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
                start_x += gl.width;
            }
        }
        font.getData().setScale(1.0f);
    }

    private void renderSmallEnergy(SpriteBatch sb, TextureAtlas.AtlasRegion region, float x, float y) {
        sb.setColor(Color.WHITE);
        sb.draw(region.getTexture(), this.current_x + x * Settings.scale * this.drawScale + region.offsetX * Settings.scale - 4.0f * Settings.scale, this.current_y + y * Settings.scale * this.drawScale + 280.0f * Settings.scale, 0.0f, 0.0f, region.packedWidth, region.packedHeight, this.drawScale * Settings.scale, this.drawScale * Settings.scale, 0.0f, region.getRegionX(), region.getRegionY(), region.getRegionWidth(), region.getRegionHeight(), false, false);
    }

    private void renderCardTypeText(SpriteBatch sb) {
        String label = "";
        switch (this.card.type) {
            case ATTACK: {
                label = TEXT[0];
                break;
            }
            case SKILL: {
                label = TEXT[1];
                break;
            }
            case POWER: {
                label = TEXT[2];
                break;
            }
            case CURSE: {
                label = TEXT[3];
                break;
            }
            case STATUS: {
                label = TEXT[7];
                break;
            }
        }
        FontHelper.renderFontCentered(sb, FontHelper.panelNameFont, label, (float)Settings.WIDTH / 2.0f + 3.0f * Settings.scale, (float)Settings.HEIGHT / 2.0f - 40.0f * Settings.scale, CARD_TYPE_COLOR);
    }

    private float renderDynamicVariable(char key, float start_x, float draw_y, int i, BitmapFont font, SpriteBatch sb, Character end) {
        StringBuilder stringBuilder = new StringBuilder();
        Color c = null;
        int num = 0;
        switch (key) {
            case 'D': {
                num = this.card.baseDamage;
                if (this.card.upgradedDamage) {
                    c = Settings.GREEN_TEXT_COLOR;
                    break;
                }
                c = Settings.CREAM_COLOR;
                break;
            }
            case 'B': {
                num = this.card.baseBlock;
                if (this.card.upgradedBlock) {
                    c = Settings.GREEN_TEXT_COLOR;
                    break;
                }
                c = Settings.CREAM_COLOR;
                break;
            }
            case 'M': {
                num = this.card.baseMagicNumber;
                if (this.card.upgradedMagicNumber) {
                    c = Settings.GREEN_TEXT_COLOR;
                    break;
                }
                c = Settings.CREAM_COLOR;
                break;
            }
        }
        stringBuilder.append(Integer.toString(num));
        gl.setText(font, stringBuilder.toString());
        FontHelper.renderRotatedText(sb, font, stringBuilder.toString(), this.current_x, this.current_y, start_x - this.current_x + SingleCardViewPopup.gl.width / 2.0f, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, c);
        if (end != null) {
            FontHelper.renderRotatedText(sb, font, Character.toString(end.charValue()), this.current_x, this.current_y, start_x - this.current_x + SingleCardViewPopup.gl.width + 10.0f * Settings.scale, (float)i * 1.53f * -font.getCapHeight() + draw_y - this.current_y + -12.0f, 0.0f, true, Settings.CREAM_COLOR);
        }
        stringBuilder.append(' ');
        gl.setText(font, stringBuilder.toString());
        return SingleCardViewPopup.gl.width;
    }

    private void renderTitle(SpriteBatch sb) {
        if (this.card.isLocked) {
            FontHelper.renderFontCentered(sb, FontHelper.SCP_cardTitleFont_small, TEXT[4], (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f + 338.0f * Settings.scale, Settings.CREAM_COLOR);
        } else if (this.card.isSeen) {
            if (!isViewingUpgrade || this.allowUpgradePreview()) {
                FontHelper.renderFontCentered(sb, FontHelper.SCP_cardTitleFont_small, this.card.name, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f + 338.0f * Settings.scale, Settings.CREAM_COLOR);
            } else {
                FontHelper.renderFontCentered(sb, FontHelper.SCP_cardTitleFont_small, this.card.name, (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f + 338.0f * Settings.scale, Settings.GREEN_TEXT_COLOR);
            }
        } else {
            FontHelper.renderFontCentered(sb, FontHelper.SCP_cardTitleFont_small, TEXT[5], (float)Settings.WIDTH / 2.0f, (float)Settings.HEIGHT / 2.0f + 338.0f * Settings.scale, Settings.CREAM_COLOR);
        }
    }

    private void renderCost(SpriteBatch sb) {
        if (this.card.isLocked || !this.card.isSeen) {
            return;
        }
        if (this.card.cost > -2) {
            TextureAtlas.AtlasRegion tmpImg = null;
            switch (this.card.color) {
                case RED: {
                    tmpImg = ImageMaster.CARD_RED_ORB_L;
                    break;
                }
                case GREEN: {
                    tmpImg = ImageMaster.CARD_GREEN_ORB_L;
                    break;
                }
                case BLUE: {
                    tmpImg = ImageMaster.CARD_BLUE_ORB_L;
                    break;
                }
                case PURPLE: {
                    tmpImg = ImageMaster.CARD_PURPLE_ORB_L;
                    break;
                }
                default: {
                    tmpImg = ImageMaster.CARD_GRAY_ORB_L;
                }
            }
            if (tmpImg != null) {
                this.renderHelper(sb, (float)Settings.WIDTH / 2.0f - 270.0f * Settings.scale, (float)Settings.HEIGHT / 2.0f + 380.0f * Settings.scale, tmpImg);
            }
        }
        Color c = null;
        c = this.card.isCostModified ? Settings.GREEN_TEXT_COLOR : Settings.CREAM_COLOR;
        switch (this.card.cost) {
            case -2: {
                break;
            }
            case -1: {
                FontHelper.renderFont(sb, FontHelper.SCP_cardEnergyFont, "X", (float)Settings.WIDTH / 2.0f - 292.0f * Settings.scale, (float)Settings.HEIGHT / 2.0f + 404.0f * Settings.scale, c);
                break;
            }
            case 1: {
                FontHelper.renderFont(sb, FontHelper.SCP_cardEnergyFont, Integer.toString(this.card.cost), (float)Settings.WIDTH / 2.0f - 284.0f * Settings.scale, (float)Settings.HEIGHT / 2.0f + 404.0f * Settings.scale, c);
                break;
            }
            default: {
                FontHelper.renderFont(sb, FontHelper.SCP_cardEnergyFont, Integer.toString(this.card.cost), (float)Settings.WIDTH / 2.0f - 292.0f * Settings.scale, (float)Settings.HEIGHT / 2.0f + 404.0f * Settings.scale, c);
            }
        }
    }

    private void renderHelper(SpriteBatch sb, float x, float y, TextureAtlas.AtlasRegion img) {
        if (img == null) {
            return;
        }
        sb.draw(img, x + img.offsetX - (float)img.originalWidth / 2.0f, y + img.offsetY - (float)img.originalHeight / 2.0f, (float)img.originalWidth / 2.0f - img.offsetX, (float)img.originalHeight / 2.0f - img.offsetY, img.packedWidth, img.packedHeight, Settings.scale, Settings.scale, 0.0f);
    }

    private void renderArrows(SpriteBatch sb) {
        if (this.prevCard != null) {
            sb.draw(ImageMaster.POPUP_ARROW, this.prevHb.cX - 128.0f, this.prevHb.cY - 128.0f, 128.0f, 128.0f, 256.0f, 256.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 256, 256, false, false);
            if (Settings.isControllerMode) {
                sb.draw(CInputActionSet.pageLeftViewDeck.getKeyImg(), this.prevHb.cX - 32.0f + 0.0f * Settings.scale, this.prevHb.cY - 32.0f + 100.0f * Settings.scale, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
            }
            if (this.prevHb.hovered) {
                sb.setBlendFunction(770, 1);
                sb.setColor(new Color(1.0f, 1.0f, 1.0f, 0.5f));
                sb.draw(ImageMaster.POPUP_ARROW, this.prevHb.cX - 128.0f, this.prevHb.cY - 128.0f, 128.0f, 128.0f, 256.0f, 256.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 256, 256, false, false);
                sb.setColor(Color.WHITE);
                sb.setBlendFunction(770, 771);
            }
        }
        if (this.nextCard != null) {
            sb.draw(ImageMaster.POPUP_ARROW, this.nextHb.cX - 128.0f, this.nextHb.cY - 128.0f, 128.0f, 128.0f, 256.0f, 256.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 256, 256, true, false);
            if (Settings.isControllerMode) {
                sb.draw(CInputActionSet.pageRightViewExhaust.getKeyImg(), this.nextHb.cX - 32.0f + 0.0f * Settings.scale, this.nextHb.cY - 32.0f + 100.0f * Settings.scale, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
            }
            if (this.nextHb.hovered) {
                sb.setBlendFunction(770, 1);
                sb.setColor(new Color(1.0f, 1.0f, 1.0f, 0.5f));
                sb.draw(ImageMaster.POPUP_ARROW, this.nextHb.cX - 128.0f, this.nextHb.cY - 128.0f, 128.0f, 128.0f, 256.0f, 256.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 256, 256, true, false);
                sb.setColor(Color.WHITE);
                sb.setBlendFunction(770, 771);
            }
        }
    }

    private void renderBetaArtToggle(SpriteBatch sb) {
        if (this.betaArtHb == null) {
            return;
        }
        sb.setColor(Color.WHITE);
        sb.draw(ImageMaster.CHECKBOX, this.betaArtHb.cX - 80.0f * Settings.scale - 32.0f, this.betaArtHb.cY - 32.0f, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
        if (this.betaArtHb.hovered) {
            FontHelper.renderFont(sb, FontHelper.cardTitleFont, TEXT[14], this.betaArtHb.cX - 45.0f * Settings.scale, this.betaArtHb.cY + 10.0f * Settings.scale, Settings.BLUE_TEXT_COLOR);
        } else {
            FontHelper.renderFont(sb, FontHelper.cardTitleFont, TEXT[14], this.betaArtHb.cX - 45.0f * Settings.scale, this.betaArtHb.cY + 10.0f * Settings.scale, Settings.GOLD_COLOR);
        }
        if (this.viewBetaArt) {
            sb.setColor(Color.WHITE);
            sb.draw(ImageMaster.TICK, this.betaArtHb.cX - 80.0f * Settings.scale - 32.0f, this.betaArtHb.cY - 32.0f, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
        }
        this.betaArtHb.render(sb);
    }

    private void renderUpgradeViewToggle(SpriteBatch sb) {
        sb.setColor(Color.WHITE);
        sb.draw(ImageMaster.CHECKBOX, this.upgradeHb.cX - 80.0f * Settings.scale - 32.0f, this.upgradeHb.cY - 32.0f, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
        if (this.upgradeHb.hovered) {
            FontHelper.renderFont(sb, FontHelper.cardTitleFont, TEXT[6], this.upgradeHb.cX - 45.0f * Settings.scale, this.upgradeHb.cY + 10.0f * Settings.scale, Settings.BLUE_TEXT_COLOR);
        } else {
            FontHelper.renderFont(sb, FontHelper.cardTitleFont, TEXT[6], this.upgradeHb.cX - 45.0f * Settings.scale, this.upgradeHb.cY + 10.0f * Settings.scale, Settings.GOLD_COLOR);
        }
        if (isViewingUpgrade) {
            sb.setColor(Color.WHITE);
            sb.draw(ImageMaster.TICK, this.upgradeHb.cX - 80.0f * Settings.scale - 32.0f, this.upgradeHb.cY - 32.0f, 32.0f, 32.0f, 64.0f, 64.0f, Settings.scale, Settings.scale, 0.0f, 0, 0, 64, 64, false, false);
        }
        this.upgradeHb.render(sb);
    }

    private void renderTips(SpriteBatch sb) {
        ArrayList<PowerTip> t = new ArrayList<PowerTip>();
        if (this.card.isLocked) {
            t.add(new PowerTip(TEXT[4], GameDictionary.keywords.get(TEXT[4].toLowerCase())));
        } else if (!this.card.isSeen) {
            t.add(new PowerTip(TEXT[5], GameDictionary.keywords.get(TEXT[5].toLowerCase())));
        } else {
            for (String s : this.card.keywords) {
                if (s.equals("[R]") || s.equals("[G]") || s.equals("[B]") || s.equals("[W]")) continue;
                t.add(new PowerTip(TipHelper.capitalize(s), GameDictionary.keywords.get(s)));
            }
        }
        if (!t.isEmpty()) {
            TipHelper.queuePowerTips((float)Settings.WIDTH / 2.0f + 340.0f * Settings.scale, 420.0f * Settings.scale, t);
        }
        if (this.card.cardsToPreview != null) {
            this.card.renderCardPreviewInSingleView(sb);
        }
    }
}

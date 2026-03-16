package com.megacrit.cardcrawl.screens.mainMenu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.controller.CInputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.screens.SingleCardViewPopup;
import com.megacrit.cardcrawl.screens.compendium.CardLibraryScreen;

public class ColorTabBar {
    private static final float TAB_SPACING = 198.0f * Settings.xScale;
    private static final int BAR_W = 1334;
    private static final int BAR_H = 102;
    private static final int TAB_W = 274;
    private static final int TAB_H = 68;
    private static final int TICKBOX_W = 48;
    private static final String COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED_PROP =
        "amethyst.compendium_upgrade_touch_fix_enabled";
    private static final float VIEW_UPGRADE_HB_W = 360.0f;
    private static final float VIEW_UPGRADE_HB_H = 48.0f;
    private static final float TOUCH_VIEW_UPGRADE_HB_W = 420.0f;
    private static final float TOUCH_VIEW_UPGRADE_HB_H = 84.0f;
    private static final float TOUCH_VIEW_UPGRADE_HB_CENTER_Y_OFFSET = -18.0f;
    private static Boolean compendiumUpgradeTouchFixEnabled = null;

    public Hitbox redHb;
    public Hitbox greenHb;
    public Hitbox blueHb;
    public Hitbox purpleHb;
    public Hitbox colorlessHb;
    public Hitbox curseHb;
    public Hitbox viewUpgradeHb;
    public CurrentTab curTab = CurrentTab.RED;
    private TabBarListener delegate;

    public ColorTabBar(TabBarListener delegate) {
        float w = 200.0f * Settings.scale;
        float h = 50.0f * Settings.scale;
        this.redHb = new Hitbox(w, h);
        this.greenHb = new Hitbox(w, h);
        this.blueHb = new Hitbox(w, h);
        this.purpleHb = new Hitbox(w, h);
        this.colorlessHb = new Hitbox(w, h);
        this.curseHb = new Hitbox(w, h);
        this.delegate = delegate;
        this.viewUpgradeHb = new Hitbox(getViewUpgradeHitboxWidth(), getViewUpgradeHitboxHeight());
    }

    public void update(float y) {
        float x = 470.0f * Settings.xScale;
        this.redHb.move(x, y + 50.0f * Settings.scale);
        this.greenHb.move(x += TAB_SPACING, y + 50.0f * Settings.scale);
        this.blueHb.move(x += TAB_SPACING, y + 50.0f * Settings.scale);
        this.purpleHb.move(x += TAB_SPACING, y + 50.0f * Settings.scale);
        this.colorlessHb.move(x += TAB_SPACING, y + 50.0f * Settings.scale);
        this.curseHb.move(x += TAB_SPACING, y + 50.0f * Settings.scale);
        this.updateViewUpgradeHitbox(y);
        this.redHb.update();
        this.greenHb.update();
        this.blueHb.update();
        this.purpleHb.update();
        this.colorlessHb.update();
        this.curseHb.update();
        this.viewUpgradeHb.update();
        if (this.redHb.justHovered || this.greenHb.justHovered || this.blueHb.justHovered ||
            this.colorlessHb.justHovered || this.curseHb.justHovered || this.purpleHb.justHovered) {
            CardCrawlGame.sound.playA("UI_HOVER", -0.4f);
        }
        if (Settings.isControllerMode) {
            if (CInputActionSet.pageRightViewExhaust.isJustPressed()) {
                if (this.curTab == CurrentTab.RED) {
                    this.curTab = CurrentTab.GREEN;
                } else if (this.curTab == CurrentTab.GREEN) {
                    this.curTab = CurrentTab.BLUE;
                } else if (this.curTab == CurrentTab.BLUE) {
                    this.curTab = CurrentTab.PURPLE;
                } else if (this.curTab == CurrentTab.PURPLE) {
                    this.curTab = CurrentTab.COLORLESS;
                } else if (this.curTab == CurrentTab.COLORLESS) {
                    this.curTab = CurrentTab.CURSE;
                } else if (this.curTab == CurrentTab.CURSE) {
                    this.curTab = CurrentTab.RED;
                }
                this.delegate.didChangeTab(this, this.curTab);
            } else if (CInputActionSet.pageLeftViewDeck.isJustPressed()) {
                if (this.curTab == CurrentTab.RED) {
                    this.curTab = CurrentTab.CURSE;
                } else if (this.curTab == CurrentTab.GREEN) {
                    this.curTab = CurrentTab.RED;
                } else if (this.curTab == CurrentTab.BLUE) {
                    this.curTab = CurrentTab.GREEN;
                } else if (this.curTab == CurrentTab.PURPLE) {
                    this.curTab = CurrentTab.BLUE;
                } else if (this.curTab == CurrentTab.COLORLESS) {
                    this.curTab = CurrentTab.PURPLE;
                } else if (this.curTab == CurrentTab.CURSE) {
                    this.curTab = CurrentTab.COLORLESS;
                }
                this.delegate.didChangeTab(this, this.curTab);
            }
        }
        if (InputHelper.justClickedLeft) {
            CurrentTab oldTab = this.curTab;
            if (this.redHb.hovered) {
                this.curTab = CurrentTab.RED;
            } else if (this.greenHb.hovered) {
                this.curTab = CurrentTab.GREEN;
            } else if (this.blueHb.hovered) {
                this.curTab = CurrentTab.BLUE;
            } else if (this.purpleHb.hovered) {
                this.curTab = CurrentTab.PURPLE;
            } else if (this.colorlessHb.hovered) {
                this.curTab = CurrentTab.COLORLESS;
            } else if (this.curseHb.hovered) {
                this.curTab = CurrentTab.CURSE;
            }
            if (oldTab != this.curTab) {
                this.delegate.didChangeTab(this, this.curTab);
            }
        }
        if (this.viewUpgradeHb.justHovered) {
            CardCrawlGame.sound.playA("UI_HOVER", -0.3f);
        }
        boolean handledTouchTap = false;
        if (this.viewUpgradeHb.hovered && InputHelper.justClickedLeft) {
            if (isCompendiumUpgradeTouchFixActive()) {
                this.toggleViewUpgrade();
                this.viewUpgradeHb.clickStarted = false;
                this.viewUpgradeHb.clicked = false;
                InputHelper.justClickedLeft = false;
                handledTouchTap = true;
            } else {
                this.viewUpgradeHb.clickStarted = true;
            }
        }
        if (!handledTouchTap &&
            (this.viewUpgradeHb.clicked ||
                this.viewUpgradeHb.hovered && CInputActionSet.select.isJustPressed())) {
            this.toggleViewUpgrade();
        }
    }

    public Hitbox hoveredHb() {
        if (this.redHb.hovered) {
            return this.redHb;
        }
        if (this.greenHb.hovered) {
            return this.greenHb;
        }
        if (this.blueHb.hovered) {
            return this.blueHb;
        }
        if (this.purpleHb.hovered) {
            return this.purpleHb;
        }
        if (this.colorlessHb.hovered) {
            return this.colorlessHb;
        }
        if (this.curseHb.hovered) {
            return this.curseHb;
        }
        if (this.viewUpgradeHb.hovered) {
            return this.viewUpgradeHb;
        }
        return null;
    }

    public Color getBarColor() {
        switch (this.curTab) {
            case RED:
                return new Color(0.5f, 0.1f, 0.1f, 1.0f);
            case GREEN:
                return new Color(0.25f, 0.55f, 0.0f, 1.0f);
            case BLUE:
                return new Color(0.01f, 0.34f, 0.52f, 1.0f);
            case PURPLE:
                return new Color(0.37f, 0.22f, 0.49f, 1.0f);
            case COLORLESS:
                return new Color(0.4f, 0.4f, 0.4f, 1.0f);
            case CURSE:
                return new Color(0.18f, 0.18f, 0.16f, 1.0f);
            default:
                return Color.WHITE;
        }
    }

    public void render(SpriteBatch sb, float y) {
        sb.setColor(Color.GRAY);
        if (this.curTab != CurrentTab.CURSE) {
            this.renderTab(sb, ImageMaster.COLOR_TAB_CURSE, this.curseHb.cX, y,
                CardLibraryScreen.TEXT[5], true);
        }
        if (this.curTab != CurrentTab.COLORLESS) {
            this.renderTab(sb, ImageMaster.COLOR_TAB_COLORLESS, this.colorlessHb.cX, y,
                CardLibraryScreen.TEXT[4], true);
        }
        if (this.curTab != CurrentTab.BLUE) {
            this.renderTab(sb, ImageMaster.COLOR_TAB_BLUE, this.blueHb.cX, y,
                CardLibraryScreen.TEXT[3], true);
        }
        if (this.curTab != CurrentTab.PURPLE) {
            this.renderTab(sb, ImageMaster.COLOR_TAB_PURPLE, this.purpleHb.cX, y,
                CardLibraryScreen.TEXT[8], true);
        }
        if (this.curTab != CurrentTab.GREEN) {
            this.renderTab(sb, ImageMaster.COLOR_TAB_GREEN, this.greenHb.cX, y,
                CardLibraryScreen.TEXT[2], true);
        }
        if (this.curTab != CurrentTab.RED) {
            this.renderTab(sb, ImageMaster.COLOR_TAB_RED, this.redHb.cX, y,
                CardLibraryScreen.TEXT[1], true);
        }
        sb.setColor(this.getBarColor());
        sb.draw(ImageMaster.COLOR_TAB_BAR, (float)Settings.WIDTH / 2.0f - 667.0f, y - 51.0f,
            667.0f, 51.0f, BAR_W, BAR_H, Settings.xScale, Settings.scale, 0.0f,
            0, 0, BAR_W, BAR_H, false, false);
        sb.setColor(Color.WHITE);
        switch (this.curTab) {
            case RED:
                this.renderTab(sb, ImageMaster.COLOR_TAB_RED, this.redHb.cX, y,
                    CardLibraryScreen.TEXT[1], false);
                break;
            case GREEN:
                this.renderTab(sb, ImageMaster.COLOR_TAB_GREEN, this.greenHb.cX, y,
                    CardLibraryScreen.TEXT[2], false);
                break;
            case BLUE:
                this.renderTab(sb, ImageMaster.COLOR_TAB_BLUE, this.blueHb.cX, y,
                    CardLibraryScreen.TEXT[3], false);
                break;
            case PURPLE:
                this.renderTab(sb, ImageMaster.COLOR_TAB_PURPLE, this.purpleHb.cX, y,
                    CardLibraryScreen.TEXT[8], false);
                break;
            case COLORLESS:
                this.renderTab(sb, ImageMaster.COLOR_TAB_COLORLESS, this.colorlessHb.cX, y,
                    CardLibraryScreen.TEXT[4], false);
                break;
            case CURSE:
                this.renderTab(sb, ImageMaster.COLOR_TAB_CURSE, this.curseHb.cX, y,
                    CardLibraryScreen.TEXT[5], false);
                break;
            default:
                break;
        }
        this.renderViewUpgrade(sb, y);
        this.redHb.render(sb);
        this.greenHb.render(sb);
        this.blueHb.render(sb);
        this.purpleHb.render(sb);
        this.colorlessHb.render(sb);
        this.curseHb.render(sb);
        this.viewUpgradeHb.render(sb);
    }

    private void renderTab(SpriteBatch sb, Texture img, float x, float y, String label,
                           boolean selected) {
        sb.draw(img, x - 137.0f, y - 34.0f + 53.0f * Settings.scale,
            137.0f, 34.0f, TAB_W, TAB_H, Settings.xScale, Settings.scale,
            0.0f, 0, 0, TAB_W, TAB_H, false, false);
        Color c = Settings.GOLD_COLOR;
        if (selected) {
            c = Color.GRAY;
        }
        FontHelper.renderFontCentered(sb, FontHelper.buttonLabelFont, label,
            x, y + 50.0f * Settings.scale, c, 0.9f);
    }

    private void renderViewUpgrade(SpriteBatch sb, float y) {
        Color c = Settings.CREAM_COLOR;
        if (this.viewUpgradeHb.hovered) {
            c = Settings.GOLD_COLOR;
        }
        FontHelper.renderFontRightAligned(sb, FontHelper.topPanelInfoFont,
            CardLibraryScreen.TEXT[7], 1546.0f * Settings.xScale, y, c);
        Texture img = SingleCardViewPopup.isViewingUpgrade ?
            ImageMaster.COLOR_TAB_BOX_TICKED : ImageMaster.COLOR_TAB_BOX_UNTICKED;
        sb.setColor(c);
        sb.draw(img,
            1532.0f * Settings.xScale -
                FontHelper.getSmartWidth(FontHelper.topPanelInfoFont,
                    CardLibraryScreen.TEXT[7], 9999.0f, 0.0f) -
                24.0f,
            y - 24.0f, 24.0f, 24.0f, TICKBOX_W, TICKBOX_W, Settings.scale,
            Settings.scale, 0.0f, 0, 0, TICKBOX_W, TICKBOX_W, false, false);
    }

    private void toggleViewUpgrade() {
        this.viewUpgradeHb.clicked = false;
        CardCrawlGame.sound.playA("UI_CLICK_1", -0.2f);
        SingleCardViewPopup.isViewingUpgrade = !SingleCardViewPopup.isViewingUpgrade;
    }

    private void updateViewUpgradeHitbox(float y) {
        float width = getViewUpgradeHitboxWidth();
        float height = getViewUpgradeHitboxHeight();
        if (this.viewUpgradeHb.width != width || this.viewUpgradeHb.height != height) {
            this.viewUpgradeHb.resize(width, height);
        }
        this.viewUpgradeHb.move(
            1410.0f * Settings.xScale,
            y + getViewUpgradeHitboxCenterYOffset()
        );
    }

    private static float getViewUpgradeHitboxWidth() {
        if (isCompendiumUpgradeTouchFixActive()) {
            return TOUCH_VIEW_UPGRADE_HB_W * Settings.scale;
        }
        return VIEW_UPGRADE_HB_W * Settings.scale;
    }

    private static float getViewUpgradeHitboxHeight() {
        if (isCompendiumUpgradeTouchFixActive()) {
            return TOUCH_VIEW_UPGRADE_HB_H * Settings.scale;
        }
        return VIEW_UPGRADE_HB_H * Settings.scale;
    }

    private static float getViewUpgradeHitboxCenterYOffset() {
        if (isCompendiumUpgradeTouchFixActive()) {
            return TOUCH_VIEW_UPGRADE_HB_CENTER_Y_OFFSET * Settings.scale;
        }
        return 0.0f;
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
        if ("true".equalsIgnoreCase(normalized) ||
            "1".equals(normalized) ||
            "yes".equalsIgnoreCase(normalized) ||
            "on".equalsIgnoreCase(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(normalized) ||
            "0".equals(normalized) ||
            "no".equalsIgnoreCase(normalized) ||
            "off".equalsIgnoreCase(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    public enum CurrentTab {
        RED,
        GREEN,
        BLUE,
        PURPLE,
        COLORLESS,
        CURSE
    }
}

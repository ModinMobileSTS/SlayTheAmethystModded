package com.megacrit.cardcrawl.helpers;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TipHelper {
    private static final Logger logger = LogManager.getLogger(TipHelper.class.getName());
    private static boolean renderedTipThisFrame = false;
    private static boolean isCard = false;
    private static float drawX;
    private static float drawY;
    private static ArrayList<String> KEYWORDS;
    private static ArrayList<PowerTip> POWER_TIPS;
    private static String HEADER;
    private static String BODY;
    private static AbstractCard card;
    private static final Color BASE_COLOR = Color.WHITE.cpy();
    private static final float CARD_TIP_PAD = 0.0f;
    private static final float SHADOW_DIST_Y = 0.0f;
    private static final float SHADOW_DIST_X = 0.0f;
    private static final float BOX_EDGE_H = 0.0f;
    private static final float BOX_BODY_H = 0.0f;
    private static final float BOX_W = 0.0f;
    private static GlyphLayout gl = new GlyphLayout();
    private static float textHeight;
    private static final float TEXT_OFFSET_X = 0.0f;
    private static final float HEADER_OFFSET_Y = 0.0f;
    private static final float ORB_OFFSET_Y = 0.0f;
    private static final float BODY_OFFSET_Y = 0.0f;
    private static final float BODY_TEXT_WIDTH = 0.0f;
    private static final float TIP_DESC_LINE_SPACING = 0.0f;
    private static final float POWER_ICON_OFFSET_X = 0.0f;

    public static void render(SpriteBatch sb) {
        if (!Settings.hidePopupDetails && renderedTipThisFrame) {
            if (AbstractDungeon.player != null &&
                (AbstractDungeon.player.inSingleTargetMode ||
                    AbstractDungeon.player.isDraggingCard && !Settings.isTouchScreen)) {
                HEADER = null;
                BODY = null;
                card = null;
                renderedTipThisFrame = false;
                return;
            }
            if (Settings.isTouchScreen &&
                AbstractDungeon.player != null &&
                AbstractDungeon.player.isHoveringDropZone &&
                InputHelper.isMouseDown) {
                HEADER = null;
                BODY = null;
                card = null;
                renderedTipThisFrame = false;
                return;
            }
            if (isCard && card != null) {
                if (TipHelper.card.current_x > (float)Settings.WIDTH * 0.75f) {
                    TipHelper.renderKeywords(
                        TipHelper.card.current_x - AbstractCard.IMG_WIDTH / 2.0f - CARD_TIP_PAD - BOX_W,
                        TipHelper.card.current_y + AbstractCard.IMG_HEIGHT / 2.0f - BOX_EDGE_H,
                        sb,
                        KEYWORDS
                    );
                } else {
                    TipHelper.renderKeywords(
                        TipHelper.card.current_x + AbstractCard.IMG_WIDTH / 2.0f + CARD_TIP_PAD,
                        TipHelper.card.current_y + AbstractCard.IMG_HEIGHT / 2.0f - BOX_EDGE_H,
                        sb,
                        KEYWORDS
                    );
                }
                card = null;
                isCard = false;
            } else if (HEADER != null) {
                TipHelper.renderTipBox(drawX, drawY, sb, HEADER, BODY);
                HEADER = null;
            } else {
                TipHelper.renderPowerTips(drawX, drawY, sb, POWER_TIPS);
            }
            renderedTipThisFrame = false;
        }
    }

    public static void queuePowerTips(float x, float y, ArrayList<PowerTip> powerTips) {}

    public static String capitalize(String input) {
        return input;
    }

    private static void renderKeywords(float x, float y, SpriteBatch sb, ArrayList<String> keywords) {}

    private static void renderTipBox(float x, float y, SpriteBatch sb, String header, String body) {}

    private static void renderPowerTips(float x, float y, SpriteBatch sb, ArrayList<PowerTip> powerTips) {}
}

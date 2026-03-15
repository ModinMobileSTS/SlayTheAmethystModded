package com.megacrit.cardcrawl.helpers;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.ui.panels.RenamePopup;
import com.megacrit.cardcrawl.ui.panels.SeedPanel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Android runtime patch for text-entry popups.
 *
 * The original desktop implementation ignores touch callbacks because it only
 * expects a physical mouse. On Android, popup buttons rely on the same
 * InputHelper touch flags as the rest of the UI, so wire them through here.
 */
public class TypeHelper implements InputProcessor {
    private static final Logger logger = LogManager.getLogger(TypeHelper.class.getName());

    private final boolean seed;

    public TypeHelper() {
        this(false);
    }

    public TypeHelper(boolean seed) {
        this.seed = seed;
        logger.info("[test] TypeHelper created seed=" + seed);
    }

    @Override
    public boolean keyDown(int keycode) {
        return false;
    }

    @Override
    public boolean keyUp(int keycode) {
        return false;
    }

    @Override
    public boolean keyTyped(char character) {
        String typed = String.valueOf(character);
        logger.info(
            "[test] TypeHelper.keyTyped seed=" + seed +
                " char=" + typed +
                " code=" + (int) character
        );
        if (typed.length() != 1) {
            logger.info("[test] TypeHelper.keyTyped ignored because typed length != 1");
            return false;
        }

        if (seed) {
            if (SeedPanel.isFull()) {
                logger.info("[test] TypeHelper.keyTyped ignored because seed text is full");
                return false;
            }
            if (InputHelper.isPasteJustPressed()) {
                logger.info("[test] TypeHelper.keyTyped ignored because paste shortcut is active");
                return false;
            }
            String validCharacter = SeedHelper.getValidCharacter(typed, SeedPanel.textField);
            if (validCharacter != null) {
                SeedPanel.textField = SeedPanel.textField + validCharacter;
                logger.info("[test] TypeHelper.keyTyped seed text now=" + SeedPanel.textField);
            } else {
                logger.info("[test] TypeHelper.keyTyped rejected by SeedHelper currentSeedText=" + SeedPanel.textField);
            }
            return true;
        }

        float width = FontHelper.getSmartWidth(
            FontHelper.cardTitleFont,
            RenamePopup.textField,
            1.0E7f,
            0.0f,
            0.82f
        );
        if (width >= 240.0f * Settings.scale) {
            logger.info("[test] TypeHelper.keyTyped ignored because rename width limit reached width=" + width);
            return false;
        }
        if (Character.isDigit(character) || Character.isLetter(character)) {
            RenamePopup.textField = RenamePopup.textField + typed;
            logger.info("[test] TypeHelper.keyTyped rename text now=" + RenamePopup.textField);
        } else {
            logger.info("[test] TypeHelper.keyTyped ignored non-alnum char code=" + (int) character);
        }
        return true;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        logger.info(
            "[test] TypeHelper.touchDown seed=" + seed +
                " x=" + screenX +
                " y=" + screenY +
                " pointer=" + pointer +
                " button=" + button +
                " rightPressed=" + Gdx.input.isButtonPressed(1)
        );
        if (!Gdx.input.isButtonPressed(1)) {
            InputHelper.touchDown = true;
        }
        logger.info(
            "[test] TypeHelper.touchDown flags touchDown=" + InputHelper.touchDown +
                " touchUp=" + InputHelper.touchUp +
                " isMouseDown=" + InputHelper.isMouseDown
        );
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        logger.info(
            "[test] TypeHelper.touchUp seed=" + seed +
                " x=" + screenX +
                " y=" + screenY +
                " pointer=" + pointer +
                " button=" + button
        );
        InputHelper.touchUp = true;
        logger.info(
            "[test] TypeHelper.touchUp flags touchDown=" + InputHelper.touchDown +
                " touchUp=" + InputHelper.touchUp +
                " isMouseDown=" + InputHelper.isMouseDown
        );
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        InputHelper.isMouseDown = true;
        logger.info(
            "[test] TypeHelper.touchDragged seed=" + seed +
                " x=" + screenX +
                " y=" + screenY +
                " pointer=" + pointer +
                " isMouseDown=" + InputHelper.isMouseDown
        );
        return false;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        return false;
    }

    @Override
    public boolean scrolled(int amount) {
        return false;
    }
}

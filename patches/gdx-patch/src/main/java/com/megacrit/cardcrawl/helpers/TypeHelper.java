package com.megacrit.cardcrawl.helpers;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.ui.panels.RenamePopup;
import com.megacrit.cardcrawl.ui.panels.SeedPanel;

/**
 * Android runtime patch for text-entry popups.
 *
 * The original desktop implementation ignores touch callbacks because it only
 * expects a physical mouse. On Android, popup buttons rely on the same
 * InputHelper touch flags as the rest of the UI, so wire them through here.
 */
public class TypeHelper implements InputProcessor {
    private final boolean seed;

    public TypeHelper() {
        this(false);
    }

    public TypeHelper(boolean seed) {
        this.seed = seed;
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
        if (typed.length() != 1) {
            return false;
        }

        if (seed) {
            if (SeedPanel.isFull()) {
                return false;
            }
            if (InputHelper.isPasteJustPressed()) {
                return false;
            }
            String validCharacter = SeedHelper.getValidCharacter(typed, SeedPanel.textField);
            if (validCharacter != null) {
                SeedPanel.textField = SeedPanel.textField + validCharacter;
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
            return false;
        }
        if (Character.isDigit(character) || Character.isLetter(character)) {
            RenamePopup.textField = RenamePopup.textField + typed;
        }
        return true;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!Gdx.input.isButtonPressed(1)) {
            InputHelper.touchDown = true;
        }
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        InputHelper.touchUp = true;
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        InputHelper.isMouseDown = true;
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

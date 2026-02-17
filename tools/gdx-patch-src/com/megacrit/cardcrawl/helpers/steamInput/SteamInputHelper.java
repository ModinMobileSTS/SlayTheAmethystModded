package com.megacrit.cardcrawl.helpers.steamInput;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.utils.Array;
import com.codedisaster.steamworks.SteamController;
import com.codedisaster.steamworks.SteamControllerActionSetHandle;
import com.codedisaster.steamworks.SteamControllerHandle;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.controller.CInputHelper;

import java.util.ArrayList;

/**
 * Minimal no-op Steam input bridge for Android runtime.
 */
public class SteamInputHelper {
    public static Array<Controller> controllers = new Array<>();
    public static ArrayList<SteamInputAction> actions = new ArrayList<>();
    public static CInputHelper.ControllerModel model = null;
    public static boolean alive = false;
    public static SteamController controller = null;
    public static SteamControllerHandle[] controllerHandles = new SteamControllerHandle[0];
    public static SteamControllerHandle handle = null;
    public static int numControllers = 0;
    public static SteamControllerActionSetHandle setHandle = null;

    public SteamInputHelper() {
        // Steam input is disabled in this minimal Android launcher path.
        alive = false;
        numControllers = 0;
        controllerHandles = new SteamControllerHandle[0];
    }

    public static void initActions(SteamControllerHandle controllerHandle) {
        handle = controllerHandle;
    }

    public static void updateFirst() {
        // No-op.
    }

    public static void setCursor(Hitbox hb) {
        // No-op.
    }

    public static boolean isJustPressed(int actionIndex) {
        return false;
    }
}

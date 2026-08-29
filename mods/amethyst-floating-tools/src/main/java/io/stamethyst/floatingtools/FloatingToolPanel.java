package io.stamethyst.floatingtools;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.input.Keyboard;

final class FloatingToolPanel {
    private static final String PROP_ENABLED = "amethyst.floating_tools.enabled";
    private static final String PROP_BUTTONS = "amethyst.floating_tools.buttons";
    private static final String PROP_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK =
        "amethyst.floating_tools.auto_switch_left_after_right_click";

    private static final float RELIC_IMG_SIZE = 128f;
    private static final float RELIC_HIT_SIZE = 72f;
    // Scales the whole button (art plus hit box). The ring radii below must grow with it,
    // otherwise neighbouring hit boxes start to overlap.
    private static final float BUTTON_SIZE_SCALE = 1.25f;
    private static final float ORB_HIT_SIZE = 84f;
    private static final float ORB_IMG_SIZE = 104f;
    private static final float RING_INNER_RADIUS = 148f;
    private static final float RING_OUTER_RADIUS = 268f;
    private static final float RING_SWEEP_DEGREES = 136f;
    private static final float RING_OPEN_ROTATION_DEGREES = 4f;
    private static final float RING_EDGE_MARGIN = 2f;
    private static final float ORB_MAX_BREATHE_SCALE = 1.025f * 1.07f;
    private static final float DRAWER_OPEN_SECONDS = 0.30f;
    private static final float DRAWER_CLOSE_SECONDS = 0.22f;
    private static final float DRAWER_STAGGER = 0.35f;
    private static final float DRAWER_REVEAL_FLOOR = 0.35f;
    private static final float BUTTON_APPEAR_MIN_SCALE = 0.55f;
    private static final float BUTTON_CLICKABLE_APPEAR = 0.75f;
    private static final float DRAWER_INPUT_BLOCK_PROGRESS = 0.22f;
    private static final float BUTTON_PRESS_MAX_SCALE = 1.24f;
    private static final float BUTTON_PRESS_INITIAL_SCALE = 1.08f;
    private static final float BUTTON_PRESS_GROW_SPEED = 18f;
    private static final float BUTTON_PRESS_SHRINK_SPEED = 12f;
    private static final float BUTTON_HOVER_SCALE = 1.08f;
    private static final float TIP_BOX_W = 320f;
    private static final float TIP_SIDE_PAD = 24f;
    private static final float TIP_ANCHOR_GAP = 46f;
    private static final float TIP_TOP_OFFSET = 58f;
    private static final float TIP_TEXT_SCALE = 0.9f;
    private static final float TIP_MIN_W = 180f;
    private static final float TIP_H = 54f;

    private static final Color WHITE = new Color(1f, 1f, 1f, 1f);
    private static final Color SHADOW = new Color(0f, 0f, 0f, 0.38f);
    private static final Color TIP_BACKGROUND = new Color(0.035f, 0.028f, 0.022f, 0.96f);
    private static final Color TIP_BORDER = new Color(0.72f, 0.57f, 0.30f, 0.92f);
    private static final Color PASSIVE_OUTLINE = new Color(0f, 0f, 0f, 0.33f);
    private static final Color ACTIVE_OUTLINE = new Color(0.62f, 0.9f, 0.38f, 0.72f);
    private static final Color RELIC_DARK = new Color(0.075f, 0.068f, 0.052f, 1f);
    private static final Color RELIC_MID = new Color(0.18f, 0.14f, 0.075f, 1f);
    private static final Color RELIC_GREEN = new Color(0.60f, 0.85f, 0.42f, 1f);
    private static final Color ORB_HALO = new Color(0.55f, 0.82f, 0.45f, 1f);
    private static final Color ICON_ACTIVE_TINT = new Color(0.82f, 1f, 0.64f, 1f);
    private static final String ICON_PATH = "amethystFloatingTools/images/tools/";

    private final ArrayList<ToolButton> buttons = new ArrayList<ToolButton>();
    private final Set<Action> enabledOptionalActions = new HashSet<Action>();
    private final FloatingToolWheel wheel = new FloatingToolWheel();
    private final Color sidePanelTint = new Color();
    private final Color fadeTint = new Color();
    private final Texture[] iconTextures = new Texture[Action.values().length];
    private final float[] buttonScaleOffsets = new float[Action.values().length];

    private boolean enabled;
    private boolean autoSwitchLeftAfterRightClick;
    private boolean expanded;
    private boolean ctrlDown;
    private boolean shiftDown;
    private boolean altDown;
    private boolean locked;
    private boolean rightMode;
    private boolean rightSurfaceDown;
    private boolean uiLeftPressActive;

    private float drawerRaw;
    private float drawerProgress;
    private float orbCenterX;
    private float orbCenterY;
    private float orbPulse;
    private float ringRadius;
    private Action lastHoveredAction;
    private Action pressedAction;

    void configureFromSystemProperties() {
        boolean wasEnabled = enabled;
        enabled = Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "false"));
        autoSwitchLeftAfterRightClick = Boolean.parseBoolean(
            System.getProperty(PROP_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK, "true")
        );
        configureOptionalActions();
        if (!enabled && wasEnabled) {
            releaseAllHeldKeys();
            releaseRightSurface();
            expanded = false;
            locked = false;
            rightMode = false;
            uiLeftPressActive = false;
            lastHoveredAction = null;
            clearButtonPressState();
            wheel.end();
        }
        layout();
    }

    boolean isEnabled() {
        return enabled;
    }

    void updateFrame() {
        if (!enabled) {
            return;
        }
        float delta = Gdx.graphics.getDeltaTime();
        orbPulse += delta;
        updateButtonPressScales(delta);
        float duration = expanded ? DRAWER_OPEN_SECONDS : DRAWER_CLOSE_SECONDS;
        drawerRaw = MathUtils.clamp(drawerRaw + (expanded ? delta : -delta) / duration, 0f, 1f);
        drawerProgress = smoothStep(drawerRaw);
        layout();
    }

    void updateFromInputHelper() {
        if (!enabled) {
            return;
        }
        layout();
        if (wheel.isActive()) {
            updateActiveWheel();
            consumeLeftInput();
            return;
        }
        if (uiLeftPressActive) {
            if (InputHelper.justReleasedClickLeft || !InputHelper.isMouseDown) {
                uiLeftPressActive = false;
                endButtonPress();
            }
            consumeLeftInput();
            return;
        }

        boolean overTab = containsTab(InputHelper.mX, InputHelper.mY);
        ToolButton button = findButton(InputHelper.mX, InputHelper.mY);
        updateHoverSound(overTab, button);
        boolean overDrawer = containsDrawer(InputHelper.mX, InputHelper.mY);
        if (overTab && (InputHelper.justClickedLeft || InputHelper.justClickedRight)) {
            toggleExpanded();
            playClick();
            consumePointerInput();
            return;
        }
        if (button != null && InputHelper.justClickedLeft) {
            startButtonPress(button.action);
            activate(button);
            playClick();
            uiLeftPressActive = button.action != Action.WHEEL;
            consumePointerInput();
            return;
        }
        if (overDrawer || overTab) {
            if (hasPointerInput()) {
                consumePointerInput();
            }
            return;
        }
        if (locked) {
            releaseRightSurface();
            consumePointerInput();
            return;
        }
        if (rightMode) {
            transformLeftClickToRightClick();
        } else {
            releaseRightSurface();
        }
    }

    void render(SpriteBatch sb) {
        if (!enabled) {
            return;
        }
        layout();
        if (drawerRaw > 0f) {
            renderDrawer(sb);
        }
        renderOrb(sb);
        sb.setColor(Color.WHITE);
    }

    private void toggleExpanded() {
        expanded = !expanded;
    }

    private void activate(ToolButton button) {
        if (button == null) {
            return;
        }
        switch (button.action) {
            case ONLINE:
                expanded = false;
                FloatingToolInputBridge.requestOnlinePanel("floating_tools_drawer");
                break;
            case CTRL:
                ctrlDown = toggleKey(Keyboard.KEY_LCONTROL, ctrlDown);
                break;
            case SHIFT:
                shiftDown = toggleKey(Keyboard.KEY_LSHIFT, shiftDown);
                break;
            case TAB:
                FloatingToolInputBridge.sendKeyStroke(Keyboard.KEY_TAB, '\t');
                break;
            case ALT:
                altDown = toggleKey(Keyboard.KEY_LMENU, altDown);
                break;
            case LOCK:
                locked = !locked;
                releaseRightSurface();
                break;
            case WHEEL:
                wheel.begin(wheelOffset(button));
                break;
            case MOUSE_MODE:
                rightMode = !rightMode;
                releaseRightSurface();
                break;
            case KEYBOARD:
                expanded = false;
                FloatingToolInputBridge.requestKeyboard("floating_tools_drawer");
                break;
            case ADD_KEY:
                expanded = false;
                FloatingToolInputBridge.requestCustomButton("floating_tools_drawer");
                break;
            default:
                break;
        }
    }

    private boolean toggleKey(int keyCode, boolean wasDown) {
        FloatingToolInputBridge.sendKey(keyCode, !wasDown);
        return !wasDown;
    }

    private void releaseAllHeldKeys() {
        if (ctrlDown) {
            FloatingToolInputBridge.sendKey(Keyboard.KEY_LCONTROL, false);
            ctrlDown = false;
        }
        if (shiftDown) {
            FloatingToolInputBridge.sendKey(Keyboard.KEY_LSHIFT, false);
            shiftDown = false;
        }
        if (altDown) {
            FloatingToolInputBridge.sendKey(Keyboard.KEY_LMENU, false);
            altDown = false;
        }
    }

    private void updateActiveWheel() {
        ToolButton button = buttonFor(Action.WHEEL);
        if (button != null && InputHelper.isMouseDown) {
            wheel.update(wheelOffset(button));
        }
        if (InputHelper.justReleasedClickLeft || !InputHelper.isMouseDown) {
            wheel.end();
            endButtonPress();
        }
    }

    private void updateButtonPressScales(float delta) {
        if (delta <= 0f) {
            return;
        }
        for (Action action : Action.values()) {
            int index = action.ordinal();
            float target = action == pressedAction ? BUTTON_PRESS_MAX_SCALE - 1f : 0f;
            float speed = action == pressedAction ? BUTTON_PRESS_GROW_SPEED : BUTTON_PRESS_SHRINK_SPEED;
            buttonScaleOffsets[index] = MathUtils.lerp(
                buttonScaleOffsets[index],
                target,
                MathUtils.clamp(delta * speed, 0f, 1f)
            );
            if (Math.abs(buttonScaleOffsets[index] - target) < 0.002f) {
                buttonScaleOffsets[index] = target;
            }
        }
    }

    private void clearButtonPressState() {
        pressedAction = null;
        for (int i = 0; i < buttonScaleOffsets.length; i++) {
            buttonScaleOffsets[i] = 0f;
        }
    }

    private void startButtonPress(Action action) {
        pressedAction = action;
        int index = action.ordinal();
        buttonScaleOffsets[index] = Math.max(
            buttonScaleOffsets[index],
            BUTTON_PRESS_INITIAL_SCALE - 1f
        );
    }

    private void endButtonPress() {
        pressedAction = null;
    }

    private float buttonPressScale(Action action) {
        return 1f + buttonScaleOffsets[action.ordinal()];
    }

    private float wheelOffset(ToolButton button) {
        return MathUtils.clamp((InputHelper.mY - button.centerY) / (button.hitH / 2f), -1f, 1f);
    }

    private void transformLeftClickToRightClick() {
        if (InputHelper.justClickedLeft) {
            rightSurfaceDown = true;
            FloatingToolInputBridge.sendMouseButton(
                FloatingToolInputBridge.MOUSE_RIGHT,
                true
            );
            clearLeftFields();
            return;
        }
        if (!rightSurfaceDown) {
            return;
        }
        if (InputHelper.justReleasedClickLeft || !InputHelper.isMouseDown) {
            releaseRightSurfaceAndMaybeSwitchToLeft();
            clearLeftFields();
        } else {
            clearLeftFields();
        }
    }

    private void releaseRightSurface() {
        if (!rightSurfaceDown) {
            return;
        }
        FloatingToolInputBridge.sendMouseButton(
            FloatingToolInputBridge.MOUSE_RIGHT,
            false
        );
        rightSurfaceDown = false;
    }

    private void layout() {
        buttons.clear();
        float s = Settings.scale;
        float hit = RELIC_HIT_SIZE * BUTTON_SIZE_SCALE * s;

        ArrayList<Action> coreActions = new ArrayList<Action>();
        coreActions.add(Action.ONLINE);
        coreActions.add(Action.MOUSE_MODE);
        coreActions.add(Action.KEYBOARD);
        coreActions.add(Action.ADD_KEY);

        ArrayList<Action> optionalActions = new ArrayList<Action>();
        if (isOptionalActionEnabled(Action.CTRL)) {
            optionalActions.add(Action.CTRL);
        }
        if (isOptionalActionEnabled(Action.SHIFT)) {
            optionalActions.add(Action.SHIFT);
        }
        if (isOptionalActionEnabled(Action.TAB)) {
            optionalActions.add(Action.TAB);
        }
        if (isOptionalActionEnabled(Action.ALT)) {
            optionalActions.add(Action.ALT);
        }
        if (isOptionalActionEnabled(Action.LOCK)) {
            optionalActions.add(Action.LOCK);
        }
        if (isOptionalActionEnabled(Action.WHEEL)) {
            optionalActions.add(Action.WHEEL);
        }

        // The orb anchors the ring; buttons orbit it on two arcs.
        float visual = RELIC_IMG_SIZE * BUTTON_SIZE_SCALE * BUTTON_PRESS_MAX_SCALE * s;
        float innerRadius = RING_INNER_RADIUS * s;
        float outerRadius = optionalActions.isEmpty() ? innerRadius : RING_OUTER_RADIUS * s;

        // Shrink the ring when the screen is too short for the widest orbit.
        float desiredExtent = outerRadius + visual / 2f;
        float allowedExtent = Settings.HEIGHT / 2f;
        float fit = desiredExtent > allowedExtent ? allowedExtent / desiredExtent : 1f;
        innerRadius *= fit;
        outerRadius *= fit;
        float ringExtent = desiredExtent * fit;

        // Only the orb's own radius is reserved, so it sits flush against the screen edge.
        orbCenterX = Settings.WIDTH - ORB_IMG_SIZE * s / 2f * ORB_MAX_BREATHE_SCALE - RING_EDGE_MARGIN * s;
        orbCenterY = Settings.HEIGHT / 2f;

        layoutRing(coreActions, innerRadius, 1f, hit);
        layoutRing(optionalActions, outerRadius, 1.6f, hit);

        ringRadius = ringExtent;
    }

    private void layoutRing(ArrayList<Action> ringActions, float radius, float rotationScale, float hit) {
        if (ringActions.isEmpty()) {
            return;
        }
        int count = ringActions.size();
        // Fan the buttons across an arc centred on the left-facing direction (180 degrees),
        // kept well under a half circle so the orb can sit flush against the screen edge.
        float sweep = count == 1 ? 0f : RING_SWEEP_DEGREES;
        float step = count == 1 ? 0f : sweep / (count - 1);
        for (int index = 0; index < count; index++) {
            Action action = ringActions.get(index);
            float appear = staggeredAppear(index, count);
            float rotation = (1f - appear) * RING_OPEN_ROTATION_DEGREES * rotationScale;
            float angle = 180f - sweep / 2f + rotation + step * index;
            float reveal = MathUtils.lerp(DRAWER_REVEAL_FLOOR, 1f, appear);
            float centerX = orbCenterX + MathUtils.cosDeg(angle) * radius * reveal;
            float centerY = orbCenterY + MathUtils.sinDeg(angle) * radius * reveal;
            addButton(action, centerX, centerY, hit, hit, appear);
        }
    }

    // Each button trails the one before it so the ring unfurls and folds instead of snapping as a block.
    private float staggeredAppear(int index, int count) {
        if (count <= 1) {
            return drawerProgress;
        }
        float delay = DRAWER_STAGGER * ((float) index / (count - 1));
        float span = 1f - DRAWER_STAGGER;
        return smoothStep(MathUtils.clamp((drawerRaw - delay) / span, 0f, 1f));
    }

    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }

    private void addButton(
        Action action,
        float centerX,
        float centerY,
        float hitW,
        float hitH,
        float appear
    ) {
        buttons.add(new ToolButton(action, centerX, centerY, hitW, hitH, appear));
    }

    private void configureOptionalActions() {
        enabledOptionalActions.clear();
        String configuredButtons = System.getProperty(PROP_BUTTONS, "");
        for (String buttonId : configuredButtons.split(",")) {
            Action action = optionalActionForId(buttonId.trim());
            if (action != null) {
                enabledOptionalActions.add(action);
            }
        }
    }

    private boolean isOptionalActionEnabled(Action action) {
        return enabledOptionalActions.contains(action);
    }

    private static Action optionalActionForId(String buttonId) {
        if ("ctrl".equalsIgnoreCase(buttonId)) {
            return Action.CTRL;
        }
        if ("shift".equalsIgnoreCase(buttonId)) {
            return Action.SHIFT;
        }
        if ("tab".equalsIgnoreCase(buttonId)) {
            return Action.TAB;
        }
        if ("alt".equalsIgnoreCase(buttonId)) {
            return Action.ALT;
        }
        if ("lock".equalsIgnoreCase(buttonId)) {
            return Action.LOCK;
        }
        if ("wheel".equalsIgnoreCase(buttonId)) {
            return Action.WHEEL;
        }
        return null;
    }

    private ToolButton findButton(float px, float py) {
        for (ToolButton button : buttons) {
            if (button.appear >= BUTTON_CLICKABLE_APPEAR && button.contains(px, py)) {
                return button;
            }
        }
        return null;
    }

    private ToolButton buttonFor(Action action) {
        for (ToolButton button : buttons) {
            if (button.action == action) {
                return button;
            }
        }
        return null;
    }

    private boolean containsDrawer(float px, float py) {
        if (drawerProgress <= DRAWER_INPUT_BLOCK_PROGRESS) {
            return false;
        }
        // The ring is circular, so only swallow clicks inside the actual radius.
        float dx = px - orbCenterX;
        float dy = py - orbCenterY;
        return dx * dx + dy * dy <= ringRadius * ringRadius;
    }

    private boolean containsTab(float px, float py) {
        float s = Settings.scale;
        float radius = ORB_HIT_SIZE * s / 2f;
        float dx = px - orbCenterX;
        float dy = py - orbCenterY;
        return dx * dx + dy * dy <= radius * radius;
    }

    private void renderDrawer(SpriteBatch sb) {
        for (ToolButton button : buttons) {
            renderButton(sb, button);
        }
        ToolButton hoveredButton = findButton(InputHelper.mX, InputHelper.mY);
        if (hoveredButton != null && isHoverableAction(hoveredButton.action) &&
            hoveredButton.appear >= BUTTON_CLICKABLE_APPEAR) {
            renderHoverTooltip(sb, hoveredButton);
        }
    }

    private void renderButton(SpriteBatch sb, ToolButton button) {
        if (button.appear <= 0.01f) {
            return;
        }
        boolean active = isActive(button.action);
        boolean hovered = isHoverableAction(button.action) &&
            button.appear >= BUTTON_CLICKABLE_APPEAR &&
            button.contains(InputHelper.mX, InputHelper.mY);
        float appearScale = MathUtils.lerp(BUTTON_APPEAR_MIN_SCALE, 1f, button.appear);
        float drawScale =
            Settings.scale * BUTTON_SIZE_SCALE * buttonPressScale(button.action) * appearScale *
                (hovered ? BUTTON_HOVER_SCALE : 1f);
        float alpha = button.appear;
        renderRelicOutline(sb, button.centerX, button.centerY, drawScale, active || hovered, alpha);
        renderRelicBody(sb, button.centerX, button.centerY, drawScale, active, alpha);
        renderIcon(sb, button, drawScale, active || hovered, alpha);
    }

    private boolean isHoverableAction(Action action) {
        return action == Action.MOUSE_MODE ||
            action == Action.ADD_KEY ||
            action == Action.KEYBOARD ||
            action == Action.ONLINE;
    }

    private void renderHoverTooltip(SpriteBatch sb, ToolButton button) {
        String text = tooltipFor(button.action);
        if (text == null || FontHelper.cardDescFont_N == null) {
            return;
        }

        float s = Settings.scale;
        float textW = FontHelper.getWidth(FontHelper.cardDescFont_N, text, TIP_TEXT_SCALE);
        float boxW = Math.max(TIP_MIN_W * s, textW + TIP_SIDE_PAD * 2f * s);
        float boxH = TIP_H * s;
        float sidePad = TIP_SIDE_PAD * s;
        float x = button.centerX - boxW - TIP_ANCHOR_GAP * s;
        float maxX = Math.max(sidePad, Settings.WIDTH - boxW - sidePad);
        x = MathUtils.clamp(x, sidePad, maxX);
        float y = button.centerY + TIP_TOP_OFFSET * s;
        if (y + boxH > Settings.HEIGHT - sidePad) {
            y = button.centerY - boxH - TIP_TOP_OFFSET * s;
        }
        y = MathUtils.clamp(y, sidePad, Settings.HEIGHT - boxH - sidePad);

        drawRectCentered(sb, x + boxW / 2f + 4f * s, y + boxH / 2f - 4f * s,
            boxW, boxH, SHADOW);
        drawRectCentered(sb, x + boxW / 2f, y + boxH / 2f, boxW, boxH, TIP_BORDER);
        drawRectCentered(sb, x + boxW / 2f, y + boxH / 2f, boxW - 4f * s, boxH - 4f * s,
            TIP_BACKGROUND);
        FontHelper.renderFontCentered(
            sb,
            FontHelper.cardDescFont_N,
            text,
            x + boxW / 2f,
            y + boxH / 2f - 6f * s,
            Settings.CREAM_COLOR,
            TIP_TEXT_SCALE
        );
        sb.setColor(WHITE);
    }

    private String tooltipFor(Action action) {
        switch (action) {
            case MOUSE_MODE:
                return "切换鼠标左右键";
            case ADD_KEY:
                return "新增按键";
            case KEYBOARD:
                return "打开键盘";
            case ONLINE:
                return "打开虚拟局域网菜单";
            default:
                return null;
        }
    }

    private boolean isActive(Action action) {
        switch (action) {
            case CTRL:
                return ctrlDown;
            case SHIFT:
                return shiftDown;
            case ALT:
                return altDown;
            case LOCK:
                return locked;
            case MOUSE_MODE:
                return rightMode;
            case WHEEL:
                return wheel.isActive();
            default:
                return false;
        }
    }

    private void renderOrb(SpriteBatch sb) {
        updateSidePanelTint();
        float s = Settings.scale;
        boolean hovered = containsTab(InputHelper.mX, InputHelper.mY);
        float breathe = MathUtils.sin(orbPulse * 2.2f);
        float radius = ORB_IMG_SIZE * s / 2f * (1f + 0.025f * breathe) * (hovered ? 1.07f : 1f);

        sb.setBlendFunction(770, 1);
        drawDisc(sb, orbCenterX, orbCenterY, radius * 1.6f, ORB_HALO, 0.15f + 0.05f * breathe);
        sb.setBlendFunction(770, 771);

        drawDisc(sb, orbCenterX + 5f * s, orbCenterY - 5f * s, radius, SHADOW, SHADOW.a);
        drawDisc(sb, orbCenterX, orbCenterY, radius, sidePanelTint, 1f);
        drawDisc(sb, orbCenterX, orbCenterY, radius * 0.86f, RELIC_DARK, 1f);
        drawDisc(
            sb,
            orbCenterX,
            orbCenterY,
            radius * 0.63f,
            expanded ? RELIC_GREEN : RELIC_MID,
            1f
        );
        drawDisc(sb, orbCenterX, orbCenterY, radius * 0.44f, RELIC_DARK, 1f);
        drawDisc(
            sb,
            orbCenterX - radius * 0.22f,
            orbCenterY + radius * 0.26f,
            radius * 0.3f,
            WHITE,
            0.14f
        );

        sb.setColor(WHITE);
    }

    private void drawDisc(SpriteBatch sb, float cx, float cy, float radius, Color color, float alpha) {
        Texture disc = ImageMaster.TARGET_UI_CIRCLE;
        if (disc == null) {
            drawRelicShape(sb, cx, cy, radius / 38f, color, alpha);
            return;
        }
        sb.setColor(color.r, color.g, color.b, alpha);
        sb.draw(disc, cx - radius, cy - radius, radius * 2f, radius * 2f);
        sb.setColor(WHITE);
    }

    private void renderRelicOutline(SpriteBatch sb, float cx, float cy, float scale, boolean active, float alpha) {
        drawRelicShape(sb, cx + 5f * scale, cy - 5f * scale, scale, SHADOW, alpha);
        if (active) {
            sb.setBlendFunction(770, 1);
            drawRelicShape(sb, cx, cy, scale, ACTIVE_OUTLINE, alpha);
            sb.setBlendFunction(770, 771);
        } else {
            drawRelicShape(sb, cx, cy, scale, PASSIVE_OUTLINE, alpha);
        }
    }

    private void renderRelicBody(SpriteBatch sb, float cx, float cy, float scale, boolean active, float alpha) {
        drawRelicShape(sb, cx, cy, scale * 0.86f, RELIC_DARK, alpha);
        drawRotatedRect(sb, cx, cy, 60f * scale, 60f * scale, 45f, fade(active ? RELIC_GREEN : RELIC_MID, alpha));
        drawRelicShape(sb, cx, cy, scale * 0.58f, RELIC_DARK, alpha);
    }

    private void drawRelicShape(SpriteBatch sb, float cx, float cy, float scale, Color color, float alpha) {
        drawRotatedRect(sb, cx, cy, 76f * scale, 76f * scale, 45f, fade(color, alpha));
        drawRectCentered(sb, cx, cy, 72f * scale, 52f * scale, fade(color, alpha));
        drawRectCentered(sb, cx, cy, 52f * scale, 72f * scale, fade(color, alpha));
    }

    private Color fade(Color color, float alpha) {
        fadeTint.set(color.r, color.g, color.b, color.a * alpha);
        return fadeTint;
    }

    private void renderIcon(SpriteBatch sb, ToolButton button, float scale, boolean active, float alpha) {
        Texture icon = iconFor(button.action);
        if (icon == null) {
            return;
        }
        float size = 88f * scale;
        sb.setColor(fade(active ? ICON_ACTIVE_TINT : WHITE, alpha));
        sb.draw(icon, button.centerX - size / 2f, button.centerY - size / 2f, size, size);
        sb.setColor(WHITE);
    }

    private Texture iconFor(Action action) {
        Texture icon = iconTextures[action.ordinal()];
        if (icon != null) {
            return icon;
        }
        String filename = null;
        switch (action) {
            case ONLINE:
                filename = "online.png";
                break;
            case CTRL:
                filename = "ctrl.png";
                break;
            case SHIFT:
                filename = "shift.png";
                break;
            case TAB:
                filename = "tab.png";
                break;
            case ALT:
                filename = "alt.png";
                break;
            case LOCK:
                filename = "lock.png";
                break;
            case WHEEL:
                filename = "wheel.png";
                break;
            case MOUSE_MODE:
                filename = "mouse.png";
                break;
            case KEYBOARD:
                filename = "keyboard.png";
                break;
            case ADD_KEY:
                filename = "add_key.png";
                break;
            default:
                break;
        }
        if (filename == null) {
            return null;
        }
        icon = loadToolTexture(filename);
        iconTextures[action.ordinal()] = icon;
        return icon;
    }

    private Texture loadToolTexture(String filename) {
        Texture texture = ImageMaster.loadImage(ICON_PATH + filename);
        if (texture != null) {
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }
        return texture;
    }

    private void updateSidePanelTint() {
        sidePanelTint.r = (MathUtils.cosDeg(System.currentTimeMillis() / 10L % 360L) + 1.25f) / 2.3f;
        sidePanelTint.g = (MathUtils.cosDeg((System.currentTimeMillis() + 1000L) / 10L % 360L) + 1.25f) / 2.3f;
        sidePanelTint.b = (MathUtils.cosDeg((System.currentTimeMillis() + 2000L) / 10L % 360L) + 1.25f) / 2.3f;
        sidePanelTint.a = 1f;
    }

    private static void drawRectCentered(SpriteBatch sb, float cx, float cy, float w, float h, Color color) {
        drawRect(sb, cx - w / 2f, cy - h / 2f, w, h, color);
    }

    private static void drawRect(SpriteBatch sb, float x, float y, float w, float h, Color color) {
        sb.setColor(color);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, w, h);
    }

    private static void drawRotatedRect(SpriteBatch sb, float cx, float cy, float w, float h, float angle, Color color) {
        sb.setColor(color);
        sb.draw(
            ImageMaster.WHITE_SQUARE_IMG,
            cx - w / 2f,
            cy - h / 2f,
            w / 2f,
            h / 2f,
            w,
            h,
            1f,
            1f,
            angle,
            0,
            0,
            1,
            1,
            false,
            false
        );
    }

    private void updateHoverSound(boolean overTab, ToolButton button) {
        Action hovered = overTab ? Action.TAB_HANDLE : null;
        if (button != null && isHoverableAction(button.action)) {
            hovered = button.action;
        }
        if (hovered != null && hovered != lastHoveredAction) {
            playHover();
        }
        lastHoveredAction = hovered;
    }

    private boolean hasPointerInput() {
        return InputHelper.justClickedLeft ||
            InputHelper.justClickedRight ||
            InputHelper.justReleasedClickLeft ||
            InputHelper.justReleasedClickRight ||
            InputHelper.isMouseDown ||
            InputHelper.isMouseDown_R;
    }

    private void consumePointerInput() {
        if (InputHelper.justReleasedClickLeft || !InputHelper.isMouseDown) {
            releaseRightSurfaceAndMaybeSwitchToLeft();
        }
        consumeLeftInput();
        InputHelper.justClickedRight = false;
        InputHelper.justReleasedClickRight = false;
        InputHelper.isMouseDown_R = false;
    }

    private void releaseRightSurfaceAndMaybeSwitchToLeft() {
        boolean hadRightSurface = rightSurfaceDown;
        releaseRightSurface();
        if (hadRightSurface && autoSwitchLeftAfterRightClick) {
            rightMode = false;
        }
    }

    private void consumeLeftInput() {
        clearLeftFields();
    }

    private void clearLeftFields() {
        InputHelper.justClickedLeft = false;
        InputHelper.justReleasedClickLeft = false;
        InputHelper.isMouseDown = false;
        InputHelper.touchDown = false;
        InputHelper.touchUp = false;
    }

    private void playHover() {
        try {
            if (CardCrawlGame.sound != null) {
                CardCrawlGame.sound.playA("UI_HOVER", -0.3f);
            }
        } catch (Throwable ignored) {
        }
    }

    private void playClick() {
        try {
            if (CardCrawlGame.sound != null) {
                CardCrawlGame.sound.playA("UI_CLICK_1", -0.2f);
            }
        } catch (Throwable ignored) {
        }
    }

    private enum Action {
        ONLINE,
        CTRL,
        SHIFT,
        TAB,
        ALT,
        LOCK,
        WHEEL,
        MOUSE_MODE,
        KEYBOARD,
        ADD_KEY,
        TAB_HANDLE
    }

    private static final class ToolButton {
        final Action action;
        final float centerX;
        final float centerY;
        final float hitW;
        final float hitH;
        final float appear;

        ToolButton(
            Action action,
            float centerX,
            float centerY,
            float hitW,
            float hitH,
            float appear
        ) {
            this.action = action;
            this.centerX = centerX;
            this.centerY = centerY;
            this.hitW = hitW;
            this.hitH = hitH;
            this.appear = appear;
        }

        boolean contains(float px, float py) {
            return px >= centerX - hitW / 2f &&
                px <= centerX + hitW / 2f &&
                py >= centerY - hitH / 2f &&
                py <= centerY + hitH / 2f;
        }
    }
}

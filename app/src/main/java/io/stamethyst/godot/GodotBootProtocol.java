package io.stamethyst.godot;

/** Private, session-scoped Messenger messages; no exported component or global channel. */
final class GodotBootProtocol {
    static final int REGISTER = 1;
    static final int ENTER_MAIN_GAME = 2;
    static final int VISIBILITY = 3;
    static final int CANCEL_MAIN_GAME = 4;
    static final int RETURN_TO_LAUNCHER = 5;
    static final int STATE = 10;
    static final int REQUEST_LEAVE = 11;

    private GodotBootProtocol() { }
}

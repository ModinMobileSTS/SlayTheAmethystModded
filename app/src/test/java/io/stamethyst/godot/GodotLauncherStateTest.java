package io.stamethyst.godot;

import org.junit.Test;
import static org.junit.Assert.*;

public class GodotLauncherStateTest {
    @Test public void coldStartCannotEnter() {
        GodotLauncherState state = new GodotLauncherState();
        assertEquals(0, state.progress());
        assertFalse(state.ready());
        assertFalse(state.uiReady());
        assertFalse(state.canEnter());
    }

    @Test public void finalStateSurvivesEngineVerificationAndUiHandoff() {
        GodotLauncherState state = new GodotLauncherState();
        state.update(99, " 游戏画面已准备完成 ", true);
        assertFalse(state.canEnter());
        state.attachUi();
        assertEquals(99, state.progress());
        assertEquals("游戏画面已准备完成", state.message());
        assertTrue(state.ready());
        assertTrue(state.canEnter());
    }

    @Test public void percentageDoesNotManufactureReadiness() {
        GodotLauncherState state = new GodotLauncherState();
        state.attachUi();
        state.update(150, null, false);
        assertEquals(100, state.progress());
        assertEquals("", state.message());
        assertFalse(state.canEnter());
        state.update(-20, "等待首帧", false);
        assertEquals(0, state.progress());
        assertFalse(state.canEnter());
    }

    @Test public void progressContinuesAfterUiHandoffAndReadinessCanBeRevoked() {
        GodotLauncherState state = new GodotLauncherState();
        state.attachUi();
        state.update(62, "正在扫描补丁", false);
        assertEquals(62, state.progress());
        assertFalse(state.canEnter());
        state.update(99, "首帧就绪", true);
        assertTrue(state.canEnter());
        state.update(99, "等待主游戏", false);
        assertFalse(state.canEnter());
    }

    @Test public void binderDeathCannotBeOverwrittenByQueuedProgressOrLateHandshake() {
        GodotLauncherState state = new GodotLauncherState();
        state.update(99, "已就绪", true);
        state.disconnect();
        state.update(100, "延迟到达的进度", true);
        state.attachUi();
        assertTrue(state.disconnected());
        assertFalse(state.ready());
        assertFalse(state.canEnter());
        assertEquals(0, state.progress());
        assertEquals("主游戏进程已退出", state.message());
    }
}

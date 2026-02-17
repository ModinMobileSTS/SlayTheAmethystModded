package net.kdt.pojavlaunch;

import java.util.concurrent.CopyOnWriteArrayList;

public class Logger {
    private static final CopyOnWriteArrayList<eventLogListener> LOG_LISTENERS = new CopyOnWriteArrayList<>();

    public static native void appendToLog(String text);

    public static native void begin(String logFilePath);

    public static void addLogListener(eventLogListener listener) {
        boolean wasEmpty = LOG_LISTENERS.isEmpty();
        LOG_LISTENERS.add(listener);
        if (wasEmpty) {
            setLogListener(Logger::dispatchLog);
        }
    }

    public static void removeLogListener(eventLogListener listener) {
        LOG_LISTENERS.remove(listener);
        if (LOG_LISTENERS.isEmpty()) {
            setLogListener(null);
        }
    }

    private static void dispatchLog(String line) {
        for (eventLogListener listener : LOG_LISTENERS) {
            listener.onEventLogged(line);
        }
    }

    public interface eventLogListener {
        void onEventLogged(String text);
    }

    private static native void setLogListener(eventLogListener logListener);
}

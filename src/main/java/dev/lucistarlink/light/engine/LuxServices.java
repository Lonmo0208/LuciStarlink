package dev.lucistarlink.light.engine;

public final class LuxServices {
    private static volatile LuxEngineController controller;

    private LuxServices() {
    }

    public static LuxEngineController controller() {
        LuxEngineController current = controller;
        if (current == null) {
            synchronized (LuxServices.class) {
                current = controller;
                if (current == null) {
                    current = new LuxEngineController();
                    controller = current;
                }
            }
        }
        return current;
    }

    public static synchronized void resetController() {
        shutdownController();
        controller = new LuxEngineController();
    }

    public static synchronized void shutdownController() {
        LuxEngineController current = controller;
        if (current != null) {
            current.shutdown();
            controller = null;
        }
    }
}

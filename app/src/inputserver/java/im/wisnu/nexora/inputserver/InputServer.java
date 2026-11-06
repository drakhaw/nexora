package im.wisnu.nexora.inputserver;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Persistent key injector that runs on the TV under app_process as the shell user.
 *
 * `input keyevent` starts a new Java VM for every key, which takes about a second
 * on low-end set-top boxes. This process starts once per connection and then
 * reads one command per line from stdin:
 *
 *   k <keycode>   tap (down + up)
 *   l <keycode>   long press (down, repeat with FLAG_LONG_PRESS, up)
 *
 * Invalid lines are ignored. `--check` verifies injection is available, prints
 * "ok" and exits.
 *
 * Built into a standalone dex by the app's Gradle script (buildInputServer) and
 * shipped as an asset; it is not part of the app's own classes.
 */
public final class InputServer {

    private static final int INJECT_MODE_ASYNC = 0;

    private final Object inputManager;
    private final Method injectInputEvent;

    private InputServer() throws ReflectiveOperationException {
        Object manager;
        try {
            // Android 14+ moved injection to InputManagerGlobal.
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            manager = global.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            Class<?> legacy = Class.forName("android.hardware.input.InputManager");
            manager = legacy.getMethod("getInstance").invoke(null);
        }
        inputManager = manager;
        injectInputEvent = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    public static void main(String[] args) throws Exception {
        InputServer server = new InputServer();
        if (args.length > 0 && "--check".equals(args[0])) {
            System.out.println("ok");
            return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            server.handle(line.trim());
        }
    }

    private void handle(String line) {
        int space = line.indexOf(' ');
        if (space <= 0) return;
        String op = line.substring(0, space);
        int keyCode;
        try {
            keyCode = Integer.parseInt(line.substring(space + 1).trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (keyCode <= KeyEvent.KEYCODE_UNKNOWN || keyCode > KeyEvent.getMaxKeyCode()) return;
        switch (op) {
            case "k":
                tap(keyCode, false);
                break;
            case "l":
                tap(keyCode, true);
                break;
            default:
                break;
        }
    }

    /** Same event sequence as `input keyevent [--longpress]`. */
    private void tap(int keyCode, boolean longPress) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        inject(down);
        if (longPress) {
            inject(KeyEvent.changeTimeRepeat(down, now, 1, KeyEvent.FLAG_LONG_PRESS));
        }
        inject(KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
    }

    private void inject(KeyEvent event) {
        try {
            injectInputEvent.invoke(inputManager, event, INJECT_MODE_ASYNC);
        } catch (ReflectiveOperationException e) {
            System.err.println("inject failed: " + e);
        }
    }
}

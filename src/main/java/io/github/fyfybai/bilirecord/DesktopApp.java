package io.github.fyfybai.bilirecord;

import javax.swing.SwingUtilities;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DesktopApp {
    private static final Logger LOG = AppLog.get(DesktopApp.class);

    private DesktopApp() {
    }

    public static void open() {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                LOG.log(Level.SEVERE, "Unhandled exception on " + thread.getName(), failure));
        SwingUtilities.invokeLater(() -> {
            UiTheme.install();
            new RecorderWindow().show();
        });
    }
}

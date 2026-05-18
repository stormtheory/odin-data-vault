import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.*;
import javax.swing.*;

//
// Monitors user inactivity scoped to a specific JFrame and its contents.
//
// Timer runs continuously - never pauses for lost focus or backgrounding.
// If the app is abandoned (minimized, switched away, forgotten), it WILL timeout.
// This is intentional security behaviour - abandoned sessions are a liability.
//
// Tracks:
//   - Mouse movement/clicks within the frame's content pane
//   - Keyboard input directed at any component in the frame
//   - Dynamically added components at runtime
//
public class IdleTimeoutManager {

    // Idle threshold - 10 minutes
    private static long IDLE_TIMEOUT_MINUTES = 10;

    // Warning 1 min before.
    private static long WARNING_TIME_MINUTES=(IDLE_TIMEOUT_MINUTES - 1);

    // Scheduler drives the timeout countdown - single daemon thread
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idle-timeout-thread");
        t.setDaemon(true); // Don't block JVM shutdown if somehow still running
        return t;
    });

    // Hold reference so we can cancel + reschedule on activity
    private ScheduledFuture<?> warningtimeoutTask;
    private ScheduledFuture<?> timeoutTask;

    // The parent frame - scopes all listeners and hosts the warning dialog
    private final JFrame parentFrame;

    /**
     * @param parentFrame    Main application window to scope listeners to
     */
    public IdleTimeoutManager(JFrame parentFrame, long idle_timeout_minutes) {
        IDLE_TIMEOUT_MINUTES = idle_timeout_minutes;
        WARNING_TIME_MINUTES=(IDLE_TIMEOUT_MINUTES - 1);
        this.parentFrame = parentFrame;
    }

    /**
     * Start monitoring. Call once after your JFrame is visible.
     * Timer starts immediately and never pauses - intentional security behaviour.
     */
    public void start() {
        attachContentPaneListeners();
        beforescheduleTimeout();
        scheduleTimeout();

        System.out.println("[IdleTimeout] Started - will shutdown after "
            + IDLE_TIMEOUT_MINUTES + " min of inactivity.");
    }

    /**
     * Attach mouse and key listeners to the frame's content pane and all
     * child components recursively - covers the full window hierarchy.
     */
    private void attachContentPaneListeners() {
        // Mouse listener - resets timer on any click or movement within the frame
        MouseAdapter mouseActivity = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e)   { resetTimeout(); }
            @Override public void mouseDragged(MouseEvent e) { resetTimeout(); }
            @Override public void mousePressed(MouseEvent e) { resetTimeout(); }
        };

        // Key listener - resets timer on any keystroke directed at the frame
        KeyAdapter keyActivity = new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { resetTimeout(); }
        };

        // Attach to the content pane and all current children recursively
        attachToComponentTree(parentFrame.getContentPane(), mouseActivity, keyActivity);

        // Also attach directly to the frame itself for window-level events
        parentFrame.addKeyListener(keyActivity);
        parentFrame.addMouseMotionListener(mouseActivity);
        parentFrame.addMouseListener(mouseActivity);

        // Watch for new components added dynamically at runtime
        parentFrame.getContentPane().addContainerListener(new ContainerAdapter() {
            @Override
            public void componentAdded(ContainerEvent e) {
                // Recursively attach to any newly added component subtree
                attachToComponentTree(e.getChild(), mouseActivity, keyActivity);
            }
        });
    }

    /**
     * Recursively attach mouse and key listeners to a component and all its children.
     * Ensures no component in the hierarchy is missed.
     *
     * @param component  Root of the subtree to attach to
     * @param mouse      Shared mouse adapter
     * @param key        Shared key adapter
     */
    private void attachToComponentTree(Component component,
                                        MouseAdapter mouse,
                                        KeyAdapter key) {
        // Attach listeners to this component
        component.addMouseListener(mouse);
        component.addMouseMotionListener(mouse);
        component.addKeyListener(key);

        // Recurse into children if this is a container
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                attachToComponentTree(child, mouse, key);
            }
        }
    }

    /**
     * Cancel the pending timeout and schedule a fresh one.
     * Called every time user activity is detected.
     */
    private synchronized void resetTimeout() {
        cancelTimeout();
        beforescheduleTimeout();
        scheduleTimeout();
    }
    
    /**
     * Cancel the currently scheduled timeout task if one is pending.
     */
    private synchronized void cancelTimeout() {
        if (warningtimeoutTask != null && !warningtimeoutTask.isDone()) {
            warningtimeoutTask.cancel(false); // Don't interrupt if somehow mid-run
        }
        if (timeoutTask != null && !timeoutTask.isDone()) {
            timeoutTask.cancel(false); // Don't interrupt if somehow mid-run
        }
    }

    /**
     * Schedule the shutdown to fire after IDLE_TIMEOUT_MINUTES of no activity.
     */
    private synchronized void scheduleTimeout() {
        timeoutTask = scheduler.schedule(
            this::onIdleTimeout,
            IDLE_TIMEOUT_MINUTES,
            TimeUnit.MINUTES
        );
    }

    private synchronized void beforescheduleTimeout() {
        warningtimeoutTask = scheduler.schedule(
            this::beforeonIdleTimeout,
            WARNING_TIME_MINUTES,
            TimeUnit.MINUTES
        );
    }


private void beforeonIdleTimeout() {
    System.out.println("[IdleTimeout] Timeout Warning reached.");

    // Marshal back to EDT for UI interaction before shutdown
    SwingUtilities.invokeLater(() -> {

        // --- Build custom button panel with only a Reset option ---
        JButton resetButton = new JButton("Reset Session");
        ThemeManager.styleAccentButton(resetButton);

        // Use an Object array as the custom options - suppresses default OK/Cancel buttons
        Object[] options = { resetButton };

        // Non-blocking warning dialog with embedded reset button
        JOptionPane warning_pane = new JOptionPane(
            "Session is about to time out.",
            JOptionPane.WARNING_MESSAGE,
            JOptionPane.DEFAULT_OPTION,  // No standard button set
            null,                         // Use default warning icon
            options,                      // Inject our custom button only
            null                          // No pre-selected default option
        );

        JDialog warning_dialog = warning_pane.createDialog(parentFrame, "Timeout Warning");
        warning_dialog.setModal(false); // Non-blocking so Timer can close it
        warning_dialog.setVisible(true);

        // Wire reset button - closes dialog and resets the idle timer
        resetButton.addActionListener(e -> {
            warning_dialog.dispose(); // Dismiss the warning immediately
            resetTimeout();           // Delegate to existing timeout reset logic
        });

        new Timer(30000, e -> {
            warning_dialog.dispose();
        }) {{
            setRepeats(false); // Fire once only
            start();
        }};
    });
}


    /**
     * Called when idle timeout fires.
     * Shows a brief warning on the EDT, then performs secure shutdown.
     */
    private void onIdleTimeout() {
        System.out.println("[IdleTimeout] Idle limit reached - initiating secure shutdown.");

        // Marshal back to EDT for UI interaction before shutdown
        SwingUtilities.invokeLater(() -> {

            // --- Build custom button panel with only a Reset option ---
            JButton resetButton = new JButton("Reset Session");
            JButton logoutButton = new JButton("Logout");
            
            ThemeManager.styleAccentButton(resetButton);
            ThemeManager.styleDangerButton(logoutButton);

            // Use an Object array as the custom options - suppresses default OK/Cancel buttons
            Object[] options = { resetButton, logoutButton };

            // Non-blocking warning dialog with embedded reset button
            JOptionPane warning_pane = new JOptionPane(
                "Session timed out due to inactivity.\nShutting down securely.",
                JOptionPane.WARNING_MESSAGE,
                JOptionPane.DEFAULT_OPTION,  // No standard button set
                null,                         // Use default warning icon
                options,                      // Inject our custom button only
                null                          // No pre-selected default option
            );

            JDialog dialog = warning_pane.createDialog(parentFrame, "Session Timeout");
            dialog.setModal(false); // Non-blocking so Timer can close it
            dialog.setVisible(true);

            // Wire buttons - closes dialog and resets the idle timer
            resetButton.addActionListener(e -> {
                dialog.dispose();
                resetTimeout();
            });
            logoutButton.addActionListener(e -> {
                dialog.dispose();
                performSecureShutdown();
            });

            new Timer(5000, e -> {
                dialog.dispose();
                performSecureShutdown();
            }) {{
                setRepeats(false);
                start();
            }};
        });
    }



    /**
     * Wipe sensitive data and exit cleanly.
     * Shutdown hook registered in main will also fire after System.exit().
     */
    private void performSecureShutdown() {
        System.exit(0); // Triggers registered shutdown hook
    }

    /**
     * Stop monitoring without shutting down.
     * Use when user re-authenticates or during testing.
     */
    public void stop() {
        cancelTimeout();
        scheduler.shutdownNow();
        System.out.println("[IdleTimeout] Idle monitoring stopped.");
    }
}
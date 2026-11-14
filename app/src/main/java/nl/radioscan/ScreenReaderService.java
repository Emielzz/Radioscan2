package nl.radioscan;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Leest alleen mee: slaat de zichtbare tekst en schakelaar-standen van het scherm op in
 * out/screens.txt. Voert zelf nooit acties uit en wijzigt niets.
 */
public class ScreenReaderService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable capture = this::captureNow;
    private String lastClass = "";
    private String last = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getClassName() != null) lastClass = event.getClassName().toString();
        handler.removeCallbacks(capture);
        handler.postDelayed(capture, 500);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(capture);
        super.onDestroy();
    }

    private void captureNow() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            CharSequence pkg = root.getPackageName();
            if (pkg != null && pkg.toString().equals(getPackageName())) return;
            StringBuilder sb = new StringBuilder();
            sb.append("[pakket] ").append(pkg).append("  [venster] ").append(lastClass).append('\n');
            walk(root, 0, sb);
            String dump = sb.toString();
            if (dump.equals(last)) return;
            last = dump;
            write(dump);
        } catch (Exception ignored) {
        }
    }

    private void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 40 || sb.length() > 60000) return;
        CharSequence text = n.isPassword() ? null : n.getText();
        CharSequence desc = n.getContentDescription();
        boolean hasText = text != null && text.length() > 0;
        boolean hasDesc = desc != null && desc.length() > 0;
        if (hasText || hasDesc || n.isCheckable()) {
            for (int i = 0; i < depth; i++) sb.append(' ');
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            int dot = cls.lastIndexOf('.');
            sb.append(dot >= 0 ? cls.substring(dot + 1) : cls);
            if (hasText) sb.append(" | tekst=").append(text);
            if (hasDesc) sb.append(" | omschrijving=").append(desc);
            if (n.isCheckable()) sb.append(" | aan=").append(n.isChecked());
            if (!n.isEnabled()) sb.append(" | uitgeschakeld");
            String id = n.getViewIdResourceName();
            if (id != null) sb.append(" | id=").append(id);
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), depth + 1, sb);
        }
    }

    private void write(String dump) {
        try {
            File dir = new File(getFilesDir(), "out");
            dir.mkdirs();
            File f = new File(dir, "screens.txt");
            if (f.length() > 3000000L) return;
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8")) {
                w.write("\n===== " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " =====\n");
                w.write(dump);
            }
        } catch (Exception ignored) {
        }
    }
}

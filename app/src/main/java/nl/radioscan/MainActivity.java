package nl.radioscan;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * RadioScan v2: leest alleen, wijzigt niets.
 * - maakt een rapport van apps, schermen, services, systeemeigenschappen
 * - leest enkele data-bronnen (content providers) van de radio uit
 * - kopieert de belangrijkste fabrikant-APK's
 * - serveert alles via een kleine webserver, zodat je het op je laptop kunt downloaden
 */
public class MainActivity extends Activity {

    // Apps waarvan we het APK willen hebben om te zien hoe auto-data en schermen werken
    private static final String[] KEY_PACKAGES = {
            "android.car.server",
            "android.car.app.canbus",
            "android.car.provider",
            "android.car.app.car_settings",
            "android.car.app.factorysetting",
            "android.car.app.autorun",
            "com.bmw03.launcher2"
    };

    // Data-bronnen die we veilig kunnen lezen (bewust GEEN telefoonboek/gesprekken)
    private static final String[] PROBE_URIS = {
            "content://car_data",
            "content://carsetting"
    };

    private TextView status;
    private EditText pkgInput;
    private EditText uriInput;
    private CheckBox allDetails;
    private File outDir;
    private volatile ServerSocket server;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        outDir = new File(getFilesDir(), "out");
        outDir.mkdirs();

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("RadioScan 2.0 - leest alleen, wijzigt niets");
        title.setTextSize(22);
        root.addView(title);

        root.addView(button("ALLES VERZAMELEN (rapport + APK's + webserver)", v -> collectAll()));

        TextView t2 = new TextView(this);
        t2.setText("\nSchermen openen (alleen kijken, niets wijzigen):");
        root.addView(t2);
        root.addView(button("CAN-bus: Dashboard", v -> launch("android.car.app.canbus",
                "android.car.app.canbus.instance.osuwy.activity.DashBoardActivity")));
        root.addView(button("CAN-bus: Info", v -> launch("android.car.app.canbus",
                "android.car.app.canbus.instance.osuwy.activity.OsuInfoActivity")));
        root.addView(button("CAN-bus: Instellingen", v -> launch("android.car.app.canbus",
                "android.car.app.canbus.instance.osuwy.activity.OsuSettingActivity")));
        root.addView(button("ZLink (CarPlay)", v -> launch("com.zjinnova.zlink",
                "com.zjinnova.android.zlink.features.main.MainActivity")));

        TextView t4 = new TextView(this);
        t4.setText("\nSchermlezer (neemt de tekst van schermen op, i.p.v. foto's):");
        root.addView(t4);
        root.addView(button("A. Toegankelijkheid openen (RadioScan schermlezer aanzetten)",
                v -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, null)));
        root.addView(button("B. App-info (als het grijs is: 'Beperkte instellingen toestaan')",
                v -> openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        root.addView(button("Schermopname wissen", v -> clearScreens()));

        TextView t3 = new TextView(this);
        t3.setText("\nLosse onderdelen:");
        root.addView(t3);

        allDetails = new CheckBox(this);
        allDetails.setText("Details van alle apps (groter rapport)");
        root.addView(allDetails);
        root.addView(button("1. Alleen rapport maken", v -> runReport()));
        root.addView(button("2. Webserver starten", v -> startServer()));
        root.addView(button("3. Rapport delen (mail/Drive)", v -> shareReport()));

        pkgInput = new EditText(this);
        pkgInput.setHint("extra pakketnamen, gescheiden door komma's");
        root.addView(pkgInput);
        root.addView(button("4. Kopieer APK's van deze pakketten", v -> copyApks()));

        uriInput = new EditText(this);
        uriInput.setHint("content://... om uit te lezen");
        root.addView(uriInput);
        root.addView(button("5. Lees deze data-bron uit", v -> probeCustom()));

        status = new TextView(this);
        status.setTextSize(16);
        status.setTextIsSelectable(true);
        root.addView(status);

        setContentView(sv);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void say(final String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private void launch(String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setClassName(pkg, cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            say("Openen mislukt: " + e);
        }
    }

    private void openSettings(String action, Uri data) {
        try {
            Intent i = new Intent(action);
            if (data != null) i.setData(data);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            say("Openen mislukt: " + e);
        }
    }

    private void clearScreens() {
        File f = new File(outDir, "screens.txt");
        say(f.delete() ? "Schermopname gewist." : "Er was geen opname.");
    }

    // ---------------------------------------------------------------- alles in een keer

    private void collectAll() {
        say("Bezig met verzamelen... dit kan een minuutje duren.");
        new Thread(() -> {
            StringBuilder res = new StringBuilder();
            try {
                String report = buildReport(false);
                writeReport(report);
                res.append("Rapport: radioscan.txt\n");
            } catch (Exception e) {
                res.append("Rapport mislukt: ").append(e).append('\n');
            }
            PackageManager pm = getPackageManager();
            for (String name : KEY_PACKAGES) {
                try {
                    res.append(copyPackage(pm, name)).append('\n');
                } catch (Exception e) {
                    res.append(name).append(": niet gelukt (").append(e).append(")\n");
                }
            }
            res.append('\n');
            res.append(ensureServer());
            say(res.toString());
        }).start();
    }

    private void writeReport(String report) throws IOException {
        File f = new File(outDir, "radioscan.txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            w.write(report);
        }
    }

    // ---------------------------------------------------------------- rapport

    private void runReport() {
        say("Bezig met scannen...");
        final boolean all = allDetails.isChecked();
        new Thread(() -> {
            try {
                writeReport(buildReport(all));
                File f = new File(outDir, "radioscan.txt");
                say("Klaar: radioscan.txt (" + (f.length() / 1024) + " KB).\n"
                        + "Start nu de webserver of deel het rapport.");
            } catch (Exception e) {
                say("Fout: " + e);
            }
        }).start();
    }

    private static void kv(StringBuilder sb, String k, Object v) {
        sb.append(k).append('=').append(v).append('\n');
    }

    private static boolean isStandard(String p) {
        return p.equals("android")
                || p.startsWith("android.")
                || p.startsWith("com.android.")
                || p.startsWith("com.google.")
                || p.startsWith("com.qualcomm")
                || p.startsWith("com.mediatek");
    }

    private String buildReport(boolean all) {
        PackageManager pm = getPackageManager();
        StringBuilder sb = new StringBuilder();

        sb.append("== DEVICE ==\n");
        kv(sb, "manufacturer", Build.MANUFACTURER);
        kv(sb, "model", Build.MODEL);
        kv(sb, "device", Build.DEVICE);
        kv(sb, "board", Build.BOARD);
        kv(sb, "hardware", Build.HARDWARE);
        kv(sb, "display", Build.DISPLAY);
        kv(sb, "fingerprint", Build.FINGERPRINT);
        kv(sb, "android", Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
        kv(sb, "abis", Arrays.toString(Build.SUPPORTED_ABIS));

        sb.append("\n== GETPROP ==\n");
        try {
            Process p = Runtime.getRuntime().exec("getprop");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
        } catch (Exception e) {
            sb.append("getprop mislukt: ").append(e).append('\n');
        }

        sb.append("\n== DATA-BRONNEN (providers) ==\n");
        for (String u : PROBE_URIS) probeUri(sb, u);

        sb.append("\n== SETTINGS.SYSTEM ==\n");
        dumpSettings(sb, Settings.System.CONTENT_URI);
        sb.append("\n== SETTINGS.GLOBAL ==\n");
        dumpSettings(sb, Settings.Global.CONTENT_URI);

        sb.append("\n== HOME LAUNCHERS ==\n");
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null) {
            sb.append("default: ").append(def.activityInfo.packageName)
                    .append('/').append(def.activityInfo.name).append('\n');
        }
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            sb.append("kandidaat: ").append(ri.activityInfo.packageName)
                    .append('/').append(ri.activityInfo.name).append('\n');
        }

        sb.append("\n== STARTBARE APPS (launcher-icoon) ==\n");
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            sb.append(ri.activityInfo.packageName).append('/').append(ri.activityInfo.name)
                    .append(" | ").append(ri.loadLabel(pm)).append('\n');
        }

        sb.append("\n== PACKAGES ==\n");
        List<PackageInfo> base = pm.getInstalledPackages(0);
        Collections.sort(base, (a, c) -> a.packageName.compareTo(c.packageName));
        for (PackageInfo pi : base) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) {
                sb.append(pi.packageName).append('\n');
                continue;
            }
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean upd = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            sb.append(pi.packageName).append(" | ").append(pm.getApplicationLabel(ai))
                    .append(" | v").append(pi.versionName)
                    .append(" | ").append(sys ? (upd ? "SYSTEM+UPDATED" : "SYSTEM") : "USER")
                    .append(" | enabled=").append(ai.enabled)
                    .append(" | ").append(ai.sourceDir).append('\n');
        }

        sb.append("\n== DETAILS ==\n");
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                | PackageManager.GET_PERMISSIONS;
        for (PackageInfo pi0 : base) {
            String p = pi0.packageName;
            if (!all && isStandard(p)) continue;
            try {
                PackageInfo pi = pm.getPackageInfo(p, flags);
                sb.append("\n### ").append(p).append('\n');
                if (pi.sharedUserId != null) sb.append("sharedUserId=").append(pi.sharedUserId).append('\n');
                if (pi.activities != null) {
                    for (ActivityInfo a : pi.activities) {
                        sb.append("  activity ").append(a.name)
                                .append(a.exported ? " [exported]" : "")
                                .append(a.permission != null ? " perm=" + a.permission : "")
                                .append('\n');
                    }
                }
                if (pi.services != null) {
                    for (ServiceInfo s : pi.services) {
                        sb.append("  service ").append(s.name)
                                .append(s.exported ? " [exported]" : "")
                                .append(s.permission != null ? " perm=" + s.permission : "")
                                .append('\n');
                    }
                }
                if (pi.receivers != null) {
                    for (ActivityInfo r : pi.receivers) {
                        sb.append("  receiver ").append(r.name)
                                .append(r.exported ? " [exported]" : "")
                                .append(r.permission != null ? " perm=" + r.permission : "")
                                .append('\n');
                    }
                }
                if (pi.providers != null) {
                    for (ProviderInfo pr : pi.providers) {
                        sb.append("  provider ").append(pr.name)
                                .append(" authority=").append(pr.authority)
                                .append(pr.exported ? " [exported]" : "")
                                .append(pr.readPermission != null ? " read=" + pr.readPermission : "")
                                .append(pr.writePermission != null ? " write=" + pr.writePermission : "")
                                .append('\n');
                    }
                }
                if (pi.requestedPermissions != null) {
                    sb.append("  permissions: ").append(TextUtils.join(", ", pi.requestedPermissions)).append('\n');
                }
            } catch (Exception e) {
                sb.append("  (fout bij ").append(p).append(": ").append(e).append(")\n");
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- data-bronnen uitlezen (alleen lezen)

    private void probeUri(StringBuilder sb, String uriStr) {
        sb.append("\n-- ").append(uriStr).append('\n');
        try {
            Uri uri = Uri.parse(uriStr);
            try {
                sb.append("type=").append(getContentResolver().getType(uri)).append('\n');
            } catch (Exception e) {
                sb.append("getType: ").append(e).append('\n');
            }
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c == null) {
                sb.append("query gaf niets terug (null)\n");
                return;
            }
            try {
                sb.append("kolommen=").append(Arrays.toString(c.getColumnNames()))
                        .append(" rijen=").append(c.getCount()).append('\n');
                int n = 0;
                while (c.moveToNext() && n < 200) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 0; i < c.getColumnCount(); i++) {
                        String v;
                        try {
                            v = c.getString(i);
                        } catch (Exception e) {
                            v = "<binair>";
                        }
                        if (v != null && v.length() > 200) v = v.substring(0, 200) + "...";
                        row.append(c.getColumnName(i)).append('=').append(v).append("; ");
                    }
                    sb.append(row).append('\n');
                    n++;
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            sb.append("fout: ").append(e).append('\n');
        }
    }

    private void dumpSettings(StringBuilder sb, Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, new String[]{"name", "value"}, null, null, "name");
            if (c == null) {
                sb.append("(niet leesbaar)\n");
                return;
            }
            try {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    String value = c.getString(1);
                    if (name != null && name.toLowerCase().matches(".*(pass|pwd|token|secret).*")) {
                        value = "<verborgen>";
                    }
                    if (value != null && value.length() > 200) value = value.substring(0, 200) + "...";
                    sb.append(name).append('=').append(value).append('\n');
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            sb.append("(niet leesbaar: ").append(e).append(")\n");
        }
    }

    private void probeCustom() {
        final String u = uriInput.getText().toString().trim();
        if (u.isEmpty()) {
            say("Vul eerst een content://-adres in.");
            return;
        }
        say("Bezig...");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            probeUri(sb, u);
            say(sb.toString());
        }).start();
    }

    // ---------------------------------------------------------------- delen

    private void shareReport() {
        File f = new File(outDir, "radioscan.txt");
        if (!f.exists()) {
            say("Maak eerst een rapport.");
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("*/*");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Deel"));
        } catch (Exception e) {
            say("Delen mislukt: " + e + "\nGebruik de webserver.");
        }
    }

    // ---------------------------------------------------------------- APK's

    private void copyApks() {
        final String[] names = pkgInput.getText().toString().split("[,\\s]+");
        say("Bezig met kopieren...");
        new Thread(() -> {
            StringBuilder res = new StringBuilder();
            PackageManager pm = getPackageManager();
            for (String n : names) {
                String name = n.trim();
                if (name.isEmpty()) continue;
                try {
                    res.append(copyPackage(pm, name)).append('\n');
                } catch (Exception e) {
                    res.append(name).append(": mislukt (").append(e).append(")\n");
                }
            }
            if (res.length() == 0) res.append("Vul eerst pakketnamen in.\n");
            res.append('\n').append(ensureServer());
            say(res.toString());
        }).start();
    }

    private String copyPackage(PackageManager pm, String name) throws Exception {
        ApplicationInfo ai = pm.getApplicationInfo(name, 0);
        StringBuilder res = new StringBuilder();
        res.append(copy(ai.sourceDir, name + "-base.apk"));
        if (ai.splitSourceDirs != null) {
            for (int i = 0; i < ai.splitSourceDirs.length; i++) {
                res.append(", ").append(copy(ai.splitSourceDirs[i], name + "-split" + i + ".apk"));
            }
        }
        return res.toString();
    }

    private String copy(String src, String dstName) throws IOException {
        File dst = new File(outDir, dstName);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        return dstName + " (" + (dst.length() / 1024) + " KB)";
    }

    // ---------------------------------------------------------------- webserver

    private void startServer() {
        say(ensureServer());
    }

    /** Start de webserver als die nog niet draait; geeft tekst met de adressen terug. */
    private synchronized String ensureServer() {
        if (server == null || server.isClosed()) {
            try {
                final ServerSocket ss = new ServerSocket(8080);
                server = ss;
                runOnUiThread(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
                new Thread(() -> {
                    while (!ss.isClosed()) {
                        try (Socket s = ss.accept()) {
                            s.setSoTimeout(5000);
                            serve(s);
                        } catch (IOException e) {
                            if (ss.isClosed()) break;
                        }
                    }
                }).start();
            } catch (IOException e) {
                return "Webserver fout: " + e;
            }
        }
        return serverInfo();
    }

    private String serverInfo() {
        StringBuilder sb = new StringBuilder("Webserver draait. Open op je laptop (zelfde wifi):\n");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        sb.append("http://").append(a.getHostAddress()).append(":8080/\n");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        sb.append("Sluit de app niet zolang je downloadt.");
        return sb.toString();
    }

    private void serve(Socket s) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String req = r.readLine();
        String h;
        while ((h = r.readLine()) != null && !h.isEmpty()) {
            // headers negeren
        }
        if (req == null) return;
        OutputStream out = s.getOutputStream();
        String[] parts = req.split(" ");
        String path = parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : "/";

        if (path.equals("/")) {
            StringBuilder html = new StringBuilder("<html><meta charset=utf-8><body><h2>RadioScan</h2><ul>");
            File[] fs = outDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    html.append("<li><a href=\"").append(f.getName()).append("\">")
                            .append(f.getName()).append("</a> (")
                            .append(f.length() / 1024).append(" KB)</li>");
                }
            }
            html.append("</ul></body></html>");
            byte[] body = html.toString().getBytes("UTF-8");
            header(out, 200, "text/html; charset=utf-8", body.length);
            out.write(body);
            out.flush();
            return;
        }

        File f = new File(outDir, path.substring(1));
        String base = outDir.getCanonicalPath() + File.separator;
        if (!f.getCanonicalPath().startsWith(base) || !f.isFile()) {
            header(out, 404, "text/plain", 0);
            out.flush();
            return;
        }
        String type = f.getName().endsWith(".apk") ? "application/vnd.android.package-archive"
                : "text/plain; charset=utf-8";
        header(out, 200, type, f.length());
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.flush();
    }

    private void header(OutputStream out, int code, String type, long len) throws IOException {
        String text = code == 200 ? "OK" : "Not Found";
        String h = "HTTP/1.1 " + code + " " + text + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + len + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
    }
}

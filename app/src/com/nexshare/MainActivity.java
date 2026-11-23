package com.nexshare;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Secure;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;

public class MainActivity extends Activity {

    private static final int BLUE = 0xFF1E6FD9;
    private TextView status, urlHost, urlIp, davUrl, davHint, permNote, bgStatus;
    private Button toggle, permBtn, bgBtn;
    private Switch davSwitch;
    private Switch tunSwitch;
    private TextView tunStatus, tunUrl, tunDav, tunDesc;
    private Button tunCopy, tunShare, tunDiag;
    private RadioGroup authGroup;
    private LinearLayout authDetail;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BLUE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFEEF3F9);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(BLUE);
        header.setPadding(dp(24), dp(28), dp(24), dp(28));
        TextView title = text("NexShare", 28, Color.WHITE, true);
        TextView sub = text("Share phone files via web browser on the same Wi-Fi", 14, 0xDDFFFFFF, false);
        status = text("", 15, Color.WHITE, true);
        status.setPadding(0, dp(14), 0, 0);
        header.addView(title);
        header.addView(sub);
        header.addView(status);
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.addView(body);

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextSize(17);
        toggle.setTextColor(Color.WHITE);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ServerService.running) stopService(new Intent(MainActivity.this, ServerService.class));
                else startServer();
                handler.postDelayed(new Runnable() {
                    public void run() {
                        refresh();
                    }
                }, 400);
            }
        });
        body.addView(toggle, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout card = card(body);
        card.addView(text("Open from a computer or other phone on the same Wi-Fi", 13, 0xFF6B7785, false));
        urlHost = link(card);
        urlIp = link(card);
        TextView hint = text("If nexshare.local does not open, use the IP address link instead", 12, 0xFF8A96A3, false);
        hint.setPadding(0, dp(8), 0, 0);
        card.addView(hint);

        LinearLayout authCard = card(body);
        authCard.addView(text("Login Mode", 15, 0xFF1D2733, true));
        authGroup = new RadioGroup(this);
        String[] modes = {Settings.MODE_PIN, Settings.MODE_PASSWORD, Settings.MODE_NONE};
        String[] labels = {"Random 6-digit PIN", "Username + Password", "No login required"};
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextSize(15);
            rb.setId(View.generateViewId());
            rb.setTag(modes[i]);
            rb.setChecked(modes[i].equals(Settings.authMode(this)));
            authGroup.addView(rb);
        }
        authGroup.setOnCheckedChangeListener((g, id) -> {
            View rb = g.findViewById(id);
            if (rb == null) return;
            Settings.setAuthMode(this, (String) rb.getTag());
            applyAuthMode();
            if (ServerService.running) startServer(); // re-apply (tunnel may force auth)
        });
        authCard.addView(authGroup);
        authDetail = new LinearLayout(this);
        authDetail.setOrientation(LinearLayout.VERTICAL);
        authDetail.setPadding(0, dp(8), 0, 0);
        authCard.addView(authDetail);

        LinearLayout screenCard = card(body);
        Switch screenSwitch = new Switch(this);
        screenSwitch.setText("Keep screen on while sharing");
        screenSwitch.setTextSize(15);
        screenSwitch.setChecked(Settings.keepScreenOn(this));
        screenSwitch.setOnCheckedChangeListener((v, on) -> {
            Settings.setKeepScreenOn(this, on);
            applyKeepScreen();
            if (ServerService.running) startServer(); // re-apply svc power stayon for root
        });
        screenCard.addView(screenSwitch);
        screenCard.addView(text(Flavor.ROOT ? "Root edition: Screen stays on across other apps (svc power stayon)" : "Screen stays on only while this app is open", 12, 0xFF8A96A3, false));

        LinearLayout bgCard = card(body);
        bgCard.addView(text("Background & Screen Off", 15, 0xFF1D2733, true));
        bgStatus = text("", 13, 0xFF6B7785, false);
        bgCard.addView(bgStatus);
        bgBtn = new Button(this);
        bgBtn.setAllCaps(false);
        bgBtn.setText("Allow Unrestricted Background");
        bgBtn.setOnClickListener(v -> requestBatteryExemption());
        bgCard.addView(bgBtn);

        Switch bootSwitch = new Switch(this);
        bootSwitch.setText("Start server automatically on device boot");
        bootSwitch.setTextSize(14);
        bootSwitch.setChecked(Settings.bootStartEnabled(this));
        bootSwitch.setOnCheckedChangeListener((v, on) -> Settings.setBootStartEnabled(this, on));
        bgCard.addView(bootSwitch);

        LinearLayout davCard = card(body);
        davCard.addView(text("WebDAV (Windows Network Drive)", 15, 0xFF1D2733, true));
        davSwitch = new Switch(this);
        davSwitch.setText("Enable WebDAV Server");
        davSwitch.setTextSize(15);
        davSwitch.setChecked(Settings.webdavEnabled(this));
        davSwitch.setOnCheckedChangeListener((v, on) -> {
            if (on) Settings.requireLogin(this);
            Settings.setWebdavEnabled(this, on);
            applyAuthMode();
            refresh();
        });
        davCard.addView(davSwitch);
        davUrl = link(davCard);
        davHint = text("", 12, 0xFF8A96A3, false);
        davHint.setPadding(0, dp(6), 0, 0);
        davCard.addView(davHint);

        LinearLayout tunCard = card(body);
        tunCard.addView(text("Remote Access / Internet (Cloudflare)", 15, 0xFF1D2733, true));
        tunSwitch = new Switch(this);
        tunSwitch.setText("Enable 1-Click Internet Tunnel");
        tunSwitch.setTextSize(15);
        tunSwitch.setChecked(TunnelManager.get(this).state() != TunnelManager.State.OFF);
        tunCard.addView(tunSwitch);

        tunStatus = text("", 13, 0xFF6B7785, false);
        tunStatus.setPadding(0, dp(4), 0, 0);
        tunCard.addView(tunStatus);

        tunUrl = link(tunCard);
        tunUrl.setVisibility(View.GONE);

        LinearLayout tunBtns = new LinearLayout(this);
        tunBtns.setOrientation(LinearLayout.HORIZONTAL);
        tunBtns.setPadding(0, dp(6), 0, 0);

        tunCopy = new Button(this);
        tunCopy.setAllCaps(false);
        tunCopy.setText("Copy Public Link");
        tunCopy.setVisibility(View.GONE);
        tunBtns.addView(tunCopy, new LinearLayout.LayoutParams(0, -2, 1f));

        tunShare = new Button(this);
        tunShare.setAllCaps(false);
        tunShare.setText("Share");
        tunShare.setVisibility(View.GONE);
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, -2, 1f);
        shareLp.setMarginStart(dp(8));
        tunBtns.addView(tunShare, shareLp);
        tunCard.addView(tunBtns);

        tunSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                Settings.requireLogin(this);
                applyAuthMode();
                new Thread(() -> {
                    try {
                        // Reuse a saved named-tunnel token; otherwise a throwaway quick tunnel.
                        String savedToken = Settings.tunnelToken(MainActivity.this);
                        boolean named = savedToken != null && !savedToken.isEmpty();
                        TunnelManager.get(MainActivity.this).start(named ? "named" : "quick", named ? savedToken : null);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
            } else {
                TunnelManager.get(MainActivity.this).stop();
            }
            refresh();
        });

        tunCopy.setOnClickListener(v -> {
            String u = TunnelManager.get(MainActivity.this).url();
            if (u != null && !u.isEmpty()) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("NexShare Public URL", u));
                Toast.makeText(MainActivity.this, "Link copied: " + u, Toast.LENGTH_SHORT).show();
            }
        });

        tunShare.setOnClickListener(v -> {
            String u = TunnelManager.get(MainActivity.this).url();
            if (u != null && !u.isEmpty()) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, u);
                startActivity(Intent.createChooser(send, "Share Public Link"));
            }
        });

        tunDav = text("", 12, 0xFF7C3AED, true);
        tunDav.setPadding(0, dp(8), 0, 0);
        tunDav.setVisibility(View.GONE);
        tunDav.setOnClickListener(v -> {
            Object url = v.getTag();
            if (url == null) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("NexShare WebDAV", url.toString()));
            Toast.makeText(this, "WebDAV URL copied", Toast.LENGTH_SHORT).show();
        });
        tunCard.addView(tunDav);

        tunDiag = new Button(this);
        tunDiag.setAllCaps(false);
        tunDiag.setText("Tunnel diagnostics");
        tunDiag.setOnClickListener(v -> showTunnelDiagnostics());
        tunCard.addView(tunDiag);

        tunDesc = text("Works from anywhere (schools, restricted networks, 4G/5G) without opening router ports. With WebDAV on, you also get a public HTTPS network-drive URL.", 12, 0xFF8A96A3, false);
        tunDesc.setPadding(0, dp(6), 0, 0);
        tunCard.addView(tunDesc);

        LinearLayout permCard = card(body);
        permNote = text("", 14, 0xFF1D2733, false);
        permCard.addView(permNote);
        permBtn = new Button(this);
        permBtn.setAllCaps(false);
        permBtn.setText("Grant All Files Access");
        permBtn.setOnClickListener(v -> requestFileAccess());
        permCard.addView(permBtn);

        if (Flavor.ROOT) {
            LinearLayout rootCard = card(body);
            TextView rootState = text("", 14, 0xFF1D2733, false);
            rootCard.addView(text("NexShare Root Edition", 15, 0xFF1D2733, true));
            rootCard.addView(rootState);
            Button rootBtn = new Button(this);
            rootBtn.setAllCaps(false);
            rootBtn.setText("Request root permission");
            rootBtn.setOnClickListener(v -> new Thread(() -> {
                RootShell.resetCheck();
                boolean ok = RootShell.available();
                runOnUiThread(() -> rootState.setText(ok ? "✓ Root access granted — Terminal, screen mirror and package installer enabled" : "Root not granted (tap Grant in Magisk/SuperSU)"));
            }).start());
            rootCard.addView(rootBtn);
            new Thread(() -> {
                boolean ok = RootShell.available();
                runOnUiThread(() -> rootState.setText(ok ? "✓ Root access granted — Terminal, screen mirror and package installer enabled" : "Root not granted (tap button below to request)"));
            }).start();
        }

        LinearLayout updateCard = card(body);
        updateCard.addView(text("App Version & Updates", 15, 0xFF1D2733, true));
        TextView verText = text("Installed: v" + Flavor.VERSION + (Flavor.ROOT ? " (Root Edition)" : " (Standard)"), 13, 0xFF6B7785, false);
        verText.setPadding(0, dp(2), 0, dp(6));
        updateCard.addView(verText);

        Switch autoUpdateSwitch = new Switch(this);
        autoUpdateSwitch.setText("Auto-update in background (idle only)");
        autoUpdateSwitch.setChecked(Settings.autoUpdateEnabled(this));
        autoUpdateSwitch.setPadding(0, dp(4), 0, dp(6));
        autoUpdateSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            Settings.setAutoUpdateEnabled(this, isChecked);
            if (isChecked) AutoUpdater.get(this).triggerCheck();
        });
        updateCard.addView(autoUpdateSwitch);

        TextView autoUpdateHint = text("Silently updates when no files are being transferred", 11, 0xFF8A95A5, false);
        autoUpdateHint.setPadding(0, 0, 0, dp(8));
        updateCard.addView(autoUpdateHint);

        TextView updateStatus = text("", 13, 0xFF1D2733, false);
        updateStatus.setVisibility(View.GONE);
        updateCard.addView(updateStatus);

        Button checkUpdateBtn = new Button(this);
        checkUpdateBtn.setAllCaps(false);
        checkUpdateBtn.setText("Check for updates");
        updateCard.addView(checkUpdateBtn);

        Button installUpdateBtn = new Button(this);
        installUpdateBtn.setAllCaps(false);
        installUpdateBtn.setText("Download & Install Update");
        installUpdateBtn.setVisibility(View.GONE);
        updateCard.addView(installUpdateBtn);

        checkUpdateBtn.setOnClickListener(v -> {
            checkUpdateBtn.setEnabled(false);
            checkUpdateBtn.setText("Checking for updates...");
            updateStatus.setVisibility(View.VISIBLE);
            updateStatus.setText("Connecting to GitHub...");
            new Thread(() -> {
                try {
                    String apiUrl = "https://api.github.com/repos/JonyGOi/NexShare/releases/latest";
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(apiUrl).openConnection();
                    conn.setRequestProperty("User-Agent", "NexShare/" + Flavor.VERSION);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        runOnUiThread(() -> {
                            checkUpdateBtn.setEnabled(true);
                            checkUpdateBtn.setText("Check for updates");
                            updateStatus.setText("Failed to check: HTTP " + code);
                        });
                        return;
                    }
                    java.io.InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    org.json.JSONObject rel = new org.json.JSONObject(bo.toString("UTF-8"));
                    String tag = rel.optString("tag_name", "");
                    String latestVer = tag.startsWith("v") ? tag.substring(1) : tag;
                    boolean hasUpdate = !Flavor.VERSION.equals(latestVer);

                    org.json.JSONArray assets = rel.optJSONArray("assets");
                    String dlUrl = "";
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            org.json.JSONObject a = assets.getJSONObject(i);
                            String aname = a.optString("name", "");
                            if (aname.endsWith(".apk")) {
                                boolean isRootAsset = aname.toLowerCase(java.util.Locale.ROOT).contains("root");
                                if (Flavor.ROOT == isRootAsset) {
                                    dlUrl = a.optString("browser_download_url", "");
                                    break;
                                }
                            }
                        }
                    }
                    final String finalDlUrl = dlUrl;
                    runOnUiThread(() -> {
                        checkUpdateBtn.setEnabled(true);
                        checkUpdateBtn.setText("Check for updates");
                        if (hasUpdate && !finalDlUrl.isEmpty()) {
                            updateStatus.setText("🎉 New version available: v" + latestVer);
                            installUpdateBtn.setVisibility(View.VISIBLE);
                            installUpdateBtn.setText("Download & Install v" + latestVer);
                            installUpdateBtn.setOnClickListener(iv -> downloadAndInstall(finalDlUrl, installUpdateBtn, updateStatus));
                        } else if (!finalDlUrl.isEmpty()) {
                            updateStatus.setText("✓ App is up to date (v" + Flavor.VERSION + ")");
                            installUpdateBtn.setVisibility(View.VISIBLE);
                            installUpdateBtn.setText("Reinstall v" + Flavor.VERSION);
                            installUpdateBtn.setOnClickListener(iv -> downloadAndInstall(finalDlUrl, installUpdateBtn, updateStatus));
                        } else {
                            updateStatus.setText("✓ App is up to date (v" + Flavor.VERSION + ")");
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        checkUpdateBtn.setEnabled(true);
                        checkUpdateBtn.setText("Check for updates");
                        updateStatus.setText("Error: " + e.getMessage());
                    });
                }
            }).start();
        });

        applyAuthMode();
        applyKeepScreen();
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        if (Build.VERSION.SDK_INT >= 23 && isBatteryOptimized()) requestBatteryExemption();
        if (!hasFileAccess()) requestFileAccess();
        else if (!ServerService.running) startServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasFileAccess() && !ServerService.running && !startedOnce) startServer();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private boolean startedOnce;

    /** Rebuilds the login-mode detail area. Not called from the periodic refresh (keeps text fields stable). */
    private void applyAuthMode() {
        authDetail.removeAllViews();
        String mode = Settings.authMode(this);
        if (Settings.MODE_PIN.equals(mode)) {
            TextView pin = text(Settings.pin(this), 34, 0xFF1D2733, true);
            pin.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            pin.setLetterSpacing(0.15f);
            pin.setPadding(0, dp(4), 0, dp(4));
            authDetail.addView(pin);
            Button newPin = new Button(this);
            newPin.setAllCaps(false);
            newPin.setText("Generate new PIN");
            newPin.setOnClickListener(v -> applyAuthModeAfter(() -> Settings.regeneratePin(this)));
            authDetail.addView(newPin);
            authDetail.addView(text("Enter this PIN when opening the web UI from other devices", 12, 0xFF8A96A3, false));
        } else if (Settings.MODE_PASSWORD.equals(mode)) {
            EditText user = new EditText(this);
            user.setHint("Username");
            user.setText(Settings.username(this));
            user.setTextSize(15);
            user.setSingleLine(true);
            authDetail.addView(user);
            EditText pass = new EditText(this);
            pass.setHint(Settings.hasPassword(this) ? "New password (leave blank = unchanged)" : "Set password");
            pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            pass.setTextSize(15);
            pass.setSingleLine(true);
            authDetail.addView(pass);
            TextView st = text("", 13, 0xFF2FB36B, false);
            st.setPadding(0, dp(6), 0, 0);
            Button save = new Button(this);
            save.setAllCaps(false);
            save.setText("Save");
            save.setOnClickListener(v -> {
                Settings.setUsername(this, user.getText().toString());
                String pw = pass.getText().toString();
                if (!pw.isEmpty()) Settings.setPassword(this, pw);
                pass.setText("");
                boolean ready = Settings.hasPassword(this);
                st.setText(ready ? "✓ Saved" : "⚠ Password not set");
                st.setTextColor(ready ? 0xFF2FB36B : 0xFFE5484D);
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            });
            authDetail.addView(save);
            if (!Settings.hasPassword(this)) {
                st.setText("⚠ Password not set — set one to enable login");
                st.setTextColor(0xFFE5484D);
            }
            authDetail.addView(st);
        } else {
            authDetail.addView(text("⚠ No login required: Anyone on the same network can access your files", 13, 0xFFB5651D, false));
        }
    }

    /** Shows what the tunnel preflight found (binary, DNS, reachability) plus the last log lines. */
    private void showTunnelDiagnostics() {
        TunnelManager tm = TunnelManager.get(this);
        StringBuilder sb = new StringBuilder();
        try {
            org.json.JSONObject d = tm.diagnose();
            sb.append("cloudflared: ").append(d.optBoolean("supported") ? "ok" : "ausente").append(" (")
              .append(d.optString("arch", "?")).append(")\n")
              .append("Executável: ").append(d.optBoolean("executable") ? "sim" : "não").append("\n")
              .append("Internet: ").append(d.optBoolean("internet") ? "sim" : "NÃO").append("\n")
              .append("DNS do celular: ").append(d.optString("dns", "-")).append("\n")
              .append("api.trycloudflare.com: ").append(d.optString("api", "-")).append("\n")
              .append("region1.v2.argotunnel.com: ").append(d.optString("edge", "-")).append("\n")
              .append("Estado: ").append(d.optString("state", "-")).append(" · tentativa ")
              .append(d.optInt("attempt", 0) + 1).append("/3\n");
            String why = d.optString("explain", "");
            if (!why.isEmpty()) sb.append("\nDiagnóstico: ").append(why).append("\n");
        } catch (Exception e) {
            sb.append("Falha no diagnóstico: ").append(e.getMessage()).append("\n");
        }
        String logs = tm.logTail();
        if (logs != null && !logs.isEmpty()) sb.append("\nÚltimos logs:\n").append(logs);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Tunnel diagnostics")
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .show();
    }

    private void applyAuthModeAfter(Runnable r) {
        r.run();
        applyAuthMode();
    }

    private void applyKeepScreen() {
        if (Settings.keepScreenOn(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startServer() {
        startedOnce = true;
        Intent i = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 1 && hasFileAccess() && !ServerService.running) startServer();
        refresh();
    }

    private void refresh() {
        boolean on = ServerService.running;
        Inet4Address ip = MdnsResponder.lanAddress();
        if (on) {
            boolean eco = ServerService.isEcoMode();
            double temp = WebApi.readCpuTemp();
            if (temp <= 0) {
                Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bat != null) temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            }
            String tempStr = temp > 0 ? " · " + (Math.round(temp * 10.0) / 10.0) + "°C" : "";
            status.setText("● Running (" + (eco ? "🌱 Eco" : "⚡ Active") + tempStr + ")");
        }
        else if (ServerService.lastError != null) status.setText("● Failed to start: " + ServerService.lastError);
        else status.setText("○ Stopped");
        toggle.setText(on ? "Stop Sharing" : "Start Sharing");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(on ? 0xFFE5484D : BLUE);
        toggle.setBackground(bg);

        String host = "http://" + MdnsResponder.HOST + ":" + ServerService.PORT + "  |  https://" + MdnsResponder.HOST + ":" + ServerService.HTTPS_PORT;
        urlHost.setText(host);
        urlHost.setTag("http://" + MdnsResponder.HOST + ":" + ServerService.PORT);
        if (ip != null) {
            String ipUrl = "http://" + ip.getHostAddress() + ":" + ServerService.PORT + "\nhttps://" + ip.getHostAddress() + ":" + ServerService.HTTPS_PORT;
            urlIp.setText(ipUrl);
            urlIp.setTag("https://" + ip.getHostAddress() + ":" + ServerService.HTTPS_PORT);
        } else {
            urlIp.setText("Not connected to Wi-Fi / Hotspot");
            urlIp.setTag(null);
        }
        urlHost.setAlpha(on ? 1f : 0.4f);
        urlIp.setAlpha(on ? 1f : 0.4f);

        boolean davOn = Settings.webdavEnabled(this);
        if (davSwitch != null) davSwitch.setChecked(davOn);
        if (davUrl != null) {
            String dUrl = (ip != null ? "https://" + ip.getHostAddress() + ":" + ServerService.HTTPS_PORT : "http://" + MdnsResponder.HOST + ":" + ServerService.PORT) + "/dav/";
            davUrl.setText(dUrl);
            davUrl.setTag(dUrl);
            davUrl.setAlpha(davOn && on ? 1f : 0.4f);
        }
        if (davHint != null) {
            String mode = Settings.authMode(this);
            String ipOrHost = (ip != null ? ip.getHostAddress() : MdnsResponder.HOST) + ":" + ServerService.PORT;
            if (Settings.MODE_PASSWORD.equals(mode)) {
                String u = Settings.username(this);
                davHint.setText("WebDAV: User: " + u + " | Password: (configured password)\nCommand: net use * http://" + ipOrHost + "/dav/ /user:" + u + " *");
            } else if (Settings.MODE_PIN.equals(mode)) {
                davHint.setText("WebDAV: User: nexshare | Password: PIN (" + Settings.pin(this) + ")\nCommand: net use * http://" + ipOrHost + "/dav/ /user:nexshare *");
            } else {
                davHint.setText("WebDAV: No login required (anonymous LAN access)\nCommand: net use * http://" + ipOrHost + "/dav/");
            }
            davHint.setAlpha(davOn && on ? 1f : 0.4f);
        }

        if (tunSwitch != null && tunStatus != null) {
            TunnelManager tm = TunnelManager.get(this);
            TunnelManager.State ts = tm.state();
            tunSwitch.setChecked(ts != TunnelManager.State.OFF);
            if (ts == TunnelManager.State.CONNECTED) {
                tunStatus.setText("✓ Connected to the internet (Cloudflare)");
                tunStatus.setTextColor(0xFF16A34A);
                String u = tm.url();
                if (u != null && !u.isEmpty()) {
                    tunUrl.setText(u);
                    tunUrl.setTag(u);
                    tunUrl.setVisibility(View.VISIBLE);
                    tunCopy.setVisibility(View.VISIBLE);
                    tunShare.setVisibility(View.VISIBLE);
                    boolean davPublic = Settings.webdavEnabled(this);
                    tunDav.setText(davPublic ? "WebDAV: " + u + "/dav/  (tap to copy)" : "");
                    tunDav.setTag(davPublic ? u + "/dav/" : null);
                    tunDav.setVisibility(davPublic ? View.VISIBLE : View.GONE);
                }
            } else if (ts == TunnelManager.State.STARTING) {
                tunStatus.setText("Connecting Cloudflare tunnel...");
                tunStatus.setTextColor(0xFFD97706);
                tunUrl.setVisibility(View.GONE);
                tunCopy.setVisibility(View.GONE);
                tunShare.setVisibility(View.GONE);
                if (tunDav != null) tunDav.setVisibility(View.GONE);
            } else if (ts == TunnelManager.State.ERROR) {
                String why = tm.explain();
                if (why == null || why.isEmpty()) why = tm.error();
                tunStatus.setText("⚠ " + why);
                tunStatus.setTextColor(0xFFE5484D);
                tunUrl.setVisibility(View.GONE);
                tunCopy.setVisibility(View.GONE);
                tunShare.setVisibility(View.GONE);
                if (tunDav != null) tunDav.setVisibility(View.GONE);
            } else {
                tunStatus.setText("Off — local Wi-Fi only.");
                tunStatus.setTextColor(0xFF6B7785);
                tunUrl.setVisibility(View.GONE);
                tunCopy.setVisibility(View.GONE);
                tunShare.setVisibility(View.GONE);
                if (tunDav != null) tunDav.setVisibility(View.GONE);
            }
        }

        boolean access = hasFileAccess();
        permNote.setText(access ? "✓ All files access granted" : "Storage permission not granted. Web UI will not be able to browse folders.");
        permBtn.setVisibility(access ? View.GONE : View.VISIBLE);

        if (bgStatus != null) {
            boolean opt = isBatteryOptimized();
            if (opt) {
                bgStatus.setText("⚠️ Battery optimization active: Android may stop server when screen is off.");
                bgStatus.setTextColor(0xFFD97706);
                bgBtn.setVisibility(View.VISIBLE);
            } else {
                bgStatus.setText("✓ Unrestricted background execution active — Server stays alive with screen turned off.");
                bgStatus.setTextColor(0xFF16A34A);
                bgBtn.setVisibility(View.GONE);
            }
        }
    }

    private boolean isBatteryOptimized() {
        if (Build.VERSION.SDK_INT >= 23) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return false;
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                } catch (Exception ignored) { }
            }
        }
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.WHITE);
        c.setBackground(bg);
        c.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private TextView link(LinearLayout parent) {
        final TextView t = text("", 18, BLUE, true);
        t.setPadding(0, dp(10), 0, 0);
        t.setOnClickListener(v -> {
            Object url = v.getTag();
            if (url == null) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("NexShare", url.toString()));
            Toast.makeText(this, "Link copied to clipboard", Toast.LENGTH_SHORT).show();
        });
        parent.addView(t);
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void downloadAndInstall(String downloadUrl, Button btn, TextView statusView) {
        btn.setEnabled(false);
        btn.setText("Downloading APK...");
        statusView.setText("Downloading update...");

        new Thread(() -> {
            while (TransferTracker.isBusy(this)) {
                runOnUiThread(() -> statusView.setText("Waiting for active file transfers to complete before updating..."));
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) { }
            }

            java.io.File apkDir = new java.io.File(getCacheDir(), "apk");
            apkDir.mkdirs();
            java.io.File apk = new java.io.File(apkDir, "update.apk");

            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(downloadUrl).openConnection();
                conn.setRequestProperty("User-Agent", "NexShare/" + Flavor.VERSION);
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(45000);
                int code = conn.getResponseCode();
                if (code >= 400) {
                    runOnUiThread(() -> {
                        btn.setEnabled(true);
                        btn.setText("Download & Install Update");
                        statusView.setText("Download failed: HTTP " + code);
                    });
                    return;
                }
                try (java.io.InputStream in = conn.getInputStream(); java.io.OutputStream out = new java.io.FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                if (Flavor.ROOT && RootShell.available()) {
                    runOnUiThread(() -> statusView.setText("Installing via Root..."));
                    String out = RootShell.main().exec("pm install -r -g " + RootShell.q(apk.getAbsolutePath()), 180000);
                    apk.delete();
                    runOnUiThread(() -> {
                        btn.setEnabled(true);
                        btn.setText("Download & Install Update");
                        if (out.toLowerCase(java.util.Locale.ROOT).contains("success")) {
                            statusView.setText("✓ Updated successfully!");
                            Toast.makeText(this, "Updated successfully!", Toast.LENGTH_LONG).show();
                            try {
                                RootShell.main().exec("am start-foreground-service -n com.nexshare/.ServerService", 5000);
                            } catch (Exception ignored) { }
                        } else {
                            statusView.setText("Root install failed: " + out.trim());
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        btn.setEnabled(true);
                        btn.setText("Download & Install Update");
                        statusView.setText("Opening package installer...");
                    });
                    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                        try {
                            Intent allowIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                            allowIntent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(allowIntent);
                        } catch (Exception ignored) { }
                    }
                    Uri uri = NexFileProvider.uriFor(this, apk);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Exception e) {
                apk.delete();
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setText("Download & Install Update");
                    statusView.setText("Update error: " + e.getMessage());
                });
            }
        }).start();
    }
}

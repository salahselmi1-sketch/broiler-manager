package com.farm.broiler;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int REQ_MEDIA = 11;
    private static final int REQ_FILE = 12;
    private static final int REQ_NOTIF = 13;
    private static final String CHANNEL = "farm_alerts";

    private WebView web;
    private PermissionRequest pendingPermission;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "تنبيهات المزرعة", NotificationManager.IMPORTANCE_HIGH));

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    List<String> need = new ArrayList<>();
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO))
                            need.add(Manifest.permission.RECORD_AUDIO);
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA))
                            need.add(Manifest.permission.CAMERA);
                    }
                    if (need.isEmpty()) {
                        request.grant(request.getResources());
                    } else {
                        if (pendingPermission != null) pendingPermission.deny();
                        pendingPermission = request;
                        requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String[] types = params.getAcceptTypes();
                i.setType(types != null && types.length > 0 && types[0].startsWith("image") ? "image/*" : "*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "اختر ملفاً"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MEDIA && pendingPermission != null) {
            List<String> ok = new ArrayList<>();
            for (String r : pendingPermission.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
            }
            if (ok.isEmpty()) pendingPermission.deny();
            else pendingPermission.grant(ok.toArray(new String[0]));
            pendingPermission = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_FILE && fileCallback != null) {
            Uri[] r = null;
            if (result == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(r);
            fileCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ===== أدوات HTTP لكاميرات IP (Basic و Digest) =====
    private static HttpURLConnection open(String url, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "BroilerManager/1.1");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        return c;
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }

    private static String digestHeader(String challenge, String url, String user, String pass) throws Exception {
        Map<String, String> p = new HashMap<>();
        Matcher m = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").matcher(challenge);
        while (m.find()) p.put(m.group(1).toLowerCase(), m.group(2) != null ? m.group(2) : m.group(3));
        URL u = new URL(url);
        String uri = u.getFile().isEmpty() ? "/" : u.getFile();
        String realm = p.getOrDefault("realm", ""), nonce = p.getOrDefault("nonce", "");
        String qop = p.get("qop");
        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5("GET:" + uri);
        StringBuilder h = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri).append("\"");
        if (qop != null && qop.contains("auth")) {
            byte[] r = new byte[8];
            new SecureRandom().nextBytes(r);
            StringBuilder cn = new StringBuilder();
            for (byte x : r) cn.append(String.format("%02x", x));
            String nc = "00000001";
            String resp = md5(ha1 + ":" + nonce + ":" + nc + ":" + cn + ":auth:" + ha2);
            h.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cn).append("\", response=\"").append(resp).append("\"");
        } else {
            h.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append("\"");
        }
        if (p.containsKey("opaque")) h.append(", opaque=\"").append(p.get("opaque")).append("\"");
        h.append(", algorithm=MD5");
        return h.toString();
    }

    private class Bridge {
        /** حفظ ملفات النسخ الاحتياطي والتصدير في التنزيلات */
        @JavascriptInterface
        public String saveFile(String name, String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, name.endsWith(".csv") ? "text/csv" : "application/json");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BroilerManager");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("insert failed");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
                    return "تم الحفظ في التنزيلات/BroilerManager";
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BroilerManager");
                    if (!dir.exists() && !dir.mkdirs()) dir = getExternalFilesDir(null);
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                    return "تم الحفظ: " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "تعذر الحفظ: " + e.getMessage();
            }
        }

        /** جلب صورة من كاميرا IP وإرجاعها data URL (يتجاوز قيود CORS) */
        @JavascriptInterface
        public String fetchImage(String url, String user, String pass) {
            HttpURLConnection c = null;
            try {
                c = open(url, null);
                int code = c.getResponseCode();
                if (code == 401 && user != null && !user.isEmpty()) {
                    String ch = c.getHeaderField("WWW-Authenticate");
                    c.disconnect();
                    String auth = (ch != null && ch.toLowerCase().startsWith("digest"))
                            ? digestHeader(ch, url, user, pass)
                            : "Basic " + Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    c = open(url, auth);
                    code = c.getResponseCode();
                }
                if (code == 401) return "ERR:اسم المستخدم أو كلمة المرور غير صحيحة";
                if (code != 200) return "ERR:HTTP " + code;
                String ct = c.getContentType();
                if (ct == null || !ct.startsWith("image")) ct = "image/jpeg";
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        total += n;
                        if (total > 12_000_000) return "ERR:الصورة كبيرة جداً";
                    }
                }
                return "data:" + ct.split(";")[0] + ";base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            } catch (java.net.SocketTimeoutException e) {
                return "ERR:انتهت مهلة الاتصال (الكاميرا لا ترد)";
            } catch (Exception e) {
                return "ERR:" + e.getClass().getSimpleName() + " " + (e.getMessage() == null ? "" : e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }

        /** إشعار على الهاتف */
        @JavascriptInterface
        public void notify(String title, String text) {
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) return;
            Intent i = new Intent(MainActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 0, i, PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(MainActivity.this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            getSystemService(NotificationManager.class).notify((int) (System.currentTimeMillis() % 1_000_000), n);
        }

        /** جدولة تذكيرات الجدول الليلي (تعمل والتطبيق مغلق). تستبدل الجدولة السابقة */
        @JavascriptInterface
        public void scheduleReminders(String json) {
            try {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                for (int i = 0; i < 20; i++) {
                    PendingIntent old = PendingIntent.getBroadcast(MainActivity.this, 900 + i,
                            new Intent(MainActivity.this, AlarmReceiver.class),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
                    if (old != null) { am.cancel(old); old.cancel(); }
                }
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length() && i < 20; i++) {
                    JSONObject o = a.getJSONObject(i);
                    Intent it = new Intent(MainActivity.this, AlarmReceiver.class)
                            .putExtra("title", o.optString("title"))
                            .putExtra("text", o.optString("text"))
                            .putExtra("id", i);
                    PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, 900 + i, it,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, o.getLong("at"), pi);
                }
                if (a.length() > 0 && Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
                    runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
            } catch (Exception ignored) {}
        }

        /** إبقاء الشاشة تعمل أثناء المراقبة */
        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }
    }
}

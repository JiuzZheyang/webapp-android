package com.example.webapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieSyncManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.view.Menu;
import android.view.MenuItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public class MainActivity extends Activity {
    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "WebAppPrefs";
    private static final String KEY_LAN_URL = "lan_url";
    private static final String KEY_PUBLIC_URL = "public_url";
    private static final String DEFAULT_LAN_URL = "192.168.1.121:12345";
    private static final String DEFAULT_PUBLIC_URL = "";
    private static final String STATE_FILE = "webview_state.dat";
    private static final int REQUEST_FILE = 100;
    private static final int CHUNK_SIZE = 512 * 1024;
    private static final int PING_TIMEOUT_LAN = 1000;
    private static final int PING_TIMEOUT_PUBLIC = 2000;

    private ValueCallback<Uri> mUploadMessage;
    private String pendingUploadFileName;
    private String pendingUploadMimeType;
    private String pendingUploadDeviceId;
    private String pendingUploadDeviceName;

    private String currentActiveUrl = null;
    private boolean isCurrentUrlPublic = false;
    private volatile boolean isChangingUrl = false;
    private boolean isFirstLoad = true;

    private BroadcastReceiver networkReceiver = null;
    private boolean isManualRefresh = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        setupWebView();
        detectAndLoadUrl(false); // 首次加载不显示Toast
        registerNetworkCallback();
    }

    private void registerNetworkCallback() {
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 网络变化时不再自动切换，让用户手动刷新
                // 避免 WiFi/移动数据切换时频繁检测导致不稳定
            }
        };
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(networkReceiver, filter);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                     caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                     caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            NetworkInfo activeNet = cm.getActiveNetworkInfo();
            return activeNet != null && activeNet.isConnected();
        }
    }

    private void detectAndLoadUrl(boolean showToast) {
        final boolean finalShowToast = showToast;
        new AsyncTask<Void, Void, UrlResult>() {
            @Override
            protected UrlResult doInBackground(Void... voids) {
                String lanUrl = prefs.getString(KEY_LAN_URL, DEFAULT_LAN_URL);
                String publicUrl = prefs.getString(KEY_PUBLIC_URL, DEFAULT_PUBLIC_URL);

                boolean lanReachable = false;
                boolean publicReachable = false;
                String lanHost = getHost(lanUrl);
                int lanPort = getPort(lanUrl);
                String publicHost = getHost(publicUrl);
                int publicPort = getPort(publicUrl);

                // Ping both LAN and public concurrently using threads
                final AtomicBoolean lanDone = new AtomicBoolean(false);
                final AtomicBoolean publicDone = new AtomicBoolean(false);

                Thread lanThread = new Thread(() -> {
                    lanReachable = pingHost(lanHost, lanPort, PING_TIMEOUT_LAN);
                    lanDone.set(true);
                });

                Thread publicThread = new Thread(() -> {
                    publicReachable = pingHost(publicHost, publicPort, PING_TIMEOUT_PUBLIC);
                    publicDone.set(true);
                });

                lanThread.start();
                publicThread.start();

                // Wait for both with timeout
                try {
                    lanThread.join(PING_TIMEOUT_LAN + 500);
                    publicThread.join(PING_TIMEOUT_PUBLIC + 500);
                } catch (InterruptedException e) { }

                return new UrlResult(lanReachable, publicReachable, lanUrl, publicUrl);
            }

            @Override
            protected void onPostExecute(UrlResult result) {
                String targetUrl;
                boolean usedPublic;

                if (result.lanReachable && !result.publicReachable) {
                    targetUrl = makeUrl(result.lanUrl);
                    usedPublic = false;
                    if (finalShowToast) showToast("局域网可达，使用局域网");
                } else if (!result.lanReachable && result.publicReachable) {
                    targetUrl = makeUrl(result.publicUrl);
                    usedPublic = true;
                    if (finalShowToast) showToast("公网可达，使用公网");
                } else if (result.lanReachable && result.publicReachable) {
                    targetUrl = makeUrl(result.lanUrl);
                    usedPublic = false;
                    if (finalShowToast) showToast("两者均可达，优先使用局域网");
                } else {
                    if (finalShowToast) showToast("无法连接服务器，正在打开设置...");
                    openSettings();
                    return;
                }

                currentActiveUrl = targetUrl;
                isCurrentUrlPublic = usedPublic;
                webView.loadUrl(targetUrl);
            }

            private class UrlResult {
                boolean lanReachable;
                boolean publicReachable;
                String lanUrl;
                String publicUrl;

                UrlResult(boolean lan, boolean pub, String lan, String pubUrl) {
                    this.lanReachable = lan;
                    this.publicReachable = pub;
                    this.lanUrl = lan;
                    this.publicUrl = pubUrl;
                }
            }
        }.execute();
    }

    private void showToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
        finish();
    }

    private String makeUrl(String url) {
        if (url == null || url.isEmpty()) return "http://" + DEFAULT_LAN_URL + "/";
        if (!url.contains(":")) url = url + ":80";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        if (!url.endsWith("/")) url += "/";
        return url;
    }

    private String getHost(String url) {
        if (url == null || url.isEmpty()) return "";
        url = url.replace("http://", "").replace("https://", "");
        return url.contains(":") ? url.split(":")[0] : url;
    }

    private int getPort(String url) {
        if (url == null || url.isEmpty()) return 80;
        url = url.replace("http://", "").replace("https://", "");
        if (!url.contains(":")) return 80;
        try {
            return Integer.parseInt(url.split(":")[1].replace("/", ""));
        } catch (Exception e) {
            return 80;
        }
    }

    private boolean pingHost(String host, int port, int timeoutMs) {
        if (host == null || host.isEmpty() || port <= 0) return false;
        // 直接使用 Socket 连接检测，不走 HTTP 协议，避免重定向/代理问题
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void setupWebView() {
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) progressBar.setVisibility(ProgressBar.VISIBLE);
                webView.evaluateJavascript(
                    "localStorage.getItem('tiez_device_id') || ('m-' + Math.random().toString(36).substr(2, 9))",
                    value -> { pendingUploadDeviceId = value.replace("\"", ""); }
                );
                webView.evaluateJavascript(
                    "localStorage.getItem('tiez_device_name') || 'Mobile'",
                    value -> { pendingUploadDeviceName = value.replace("\"", ""); }
                );
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(ProgressBar.GONE);
                injectFilePicker();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                    errorCode == WebViewClient.ERROR_CONNECT ||
                    errorCode == WebViewClient.ERROR_TIMEOUT) {
                    tryAlternateUrl();
                }
            }

            private void tryAlternateUrl() {
                if (isChangingUrl) return;
                isChangingUrl = true;

                new AsyncTask<Void, Void, String>() {
                    @Override
                    protected String doInBackground(Void... voids) {
                        String lanUrl = prefs.getString(KEY_LAN_URL, DEFAULT_LAN_URL);
                        String publicUrl = prefs.getString(KEY_PUBLIC_URL, DEFAULT_PUBLIC_URL);

                        // Try to switch to the other URL
                        if (!isCurrentUrlPublic && !publicUrl.isEmpty()) {
                            if (pingHost(getHost(publicUrl), getPort(publicUrl), PING_TIMEOUT_PUBLIC)) {
                                return makeUrl(publicUrl) + "|public";
                            }
                        }
                        if (isCurrentUrlPublic && !lanUrl.isEmpty()) {
                            if (pingHost(getHost(lanUrl), getPort(lanUrl), PING_TIMEOUT_LAN)) {
                                return makeUrl(lanUrl) + "|lan";
                            }
                        }
                        return null;
                    }

                    @Override
                    protected void onPostExecute(String result) {
                        isChangingUrl = false;
                        if (result != null) {
                            boolean wasPublic = result.endsWith("|public");
                            String url = result.replace("|public", "").replace("|lan", "");
                            currentActiveUrl = url;
                            isCurrentUrlPublic = wasPublic;
                            webView.loadUrl(url);
                            showToast("连接失败，自动切换到: " + (wasPublic ? "公网" : "局域网"));
                        } else {
                            showToast("无法连接服务器，正在打开设置...");
                            openSettings();
                        }
                    }
                }.execute();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    if (newProgress == 100) progressBar.setVisibility(ProgressBar.GONE);
                }
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "选择文件"), REQUEST_FILE);
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "选择文件"), REQUEST_FILE);
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "选择文件"), REQUEST_FILE);
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabasePath(getFilesDir().getPath() + "/webview/");

        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                String fileName = "download";
                if (contentDisposition != null) {
                    int idx = contentDisposition.indexOf("filename=");
                    if (idx >= 0) {
                        fileName = contentDisposition.substring(idx + 9);
                        fileName = fileName.replaceAll("[\"']", "").trim();
                    }
                }
                if (fileName == null || fileName.isEmpty() || fileName.equals("download")) {
                    int slashIdx = url.lastIndexOf("/");
                    fileName = (slashIdx >= 0) ? url.substring(slashIdx + 1) : "download";
                }
                try {
                    android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
                    req.setTitle(fileName);
                    req.setDescription("终端传输 - 接收文件");
                    if (mimeType != null && !mimeType.isEmpty()) req.setMimeType(mimeType);
                    req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
                    dm.enqueue(req);
                    showToast("开始下载: " + fileName);
                } catch (Exception e) {
                    showToast("下载失败: " + e.getMessage());
                }
            }
        });
    }

    private void injectFilePicker() {
        String js = "(function() {" +
            "function setupFileInputs() {" +
            "  document.querySelectorAll('input[type=file]').forEach(function(input) {" +
            "    if (!input.dataset.androidReady) {" +
            "      input.dataset.androidReady = 'true';" +
            "      input.addEventListener('click', function(e) {" +
            "        e.preventDefault();" +
            "        e.stopPropagation();" +
            "        window.Android.pickFile(input.accept);" +
            "        return false;" +
            "      });" +
            "    }" +
            "  });" +
            "}" +
            "setupFileInputs();" +
            "setInterval(setupFileInputs, 1000);" +
            "document.addEventListener('click', function(e) {" +
            "  var a = e.target.closest('a');" +
            "  if (a && a.href && a.href.match(/\\.(pdf|zip|doc|docx|xls|xlsx|ppt|pptx|txt|jpg|jpeg|png|gif|mp3|mp4|avi|mkv|apk)$/i)) {" +
            "    e.preventDefault();" +
            "    e.stopPropagation();" +
            "    var fileName = a.download || a.href.split('/').pop();" +
            "    window.Android.downloadFile(a.href, fileName, '');" +
            "    return false;" +
            "  }" +
            "});" +
            "})();";

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(js, null);
        } else {
            webView.loadUrl("javascript:" + js);
        }
    }

    class AndroidBridge {
        @JavascriptInterface
        public void pickFile(String accept) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mUploadMessage = null;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    if (accept != null && accept.length() > 0) {
                        i.setType(accept);
                    } else {
                        i.setType("*/*");
                    }
                    startActivityForResult(Intent.createChooser(i, "选择文件"), REQUEST_FILE);
                }
            });
        }

        @JavascriptInterface
        public void uploadFile(String uriStr, String fileName, String mimeType, String deviceId, String deviceName) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Uri uri = Uri.parse(uriStr);
                    new ChunkedUploadTask(uri, fileName, mimeType, deviceId, deviceName).execute();
                }
            });
        }

        @JavascriptInterface
        public void downloadFile(String url, String fileName, String mimeType) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (url == null || url.trim().isEmpty()) {
                        showToast("无效的下载链接");
                        return;
                    }
                    if (url.startsWith("blob:") || url.startsWith("data:")) {
                        showToast("此类型下载暂不支持，请在网页中长按保存");
                        return;
                    }
                    String absUrl = url;
                    if (absUrl.startsWith("//")) {
                        absUrl = "https:" + absUrl;
                    } else if (absUrl.startsWith("/")) {
                        absUrl = currentActiveUrl + absUrl.substring(1);
                    } else if (!absUrl.startsWith("http://") && !absUrl.startsWith("https://")) {
                        absUrl = currentActiveUrl + absUrl;
                    }
                    final String finalUrl = absUrl;
                    final String finalName = (fileName != null && !fileName.isEmpty()) ? fileName : "download_file";
                    try {
                        android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(finalUrl));
                        req.setTitle(finalName);
                        req.setDescription("终端传输 - 接收文件");
                        if (mimeType != null && !mimeType.isEmpty()) {
                            req.setMimeType(mimeType);
                        }
                        req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, finalName);
                        dm.enqueue(req);
                        showToast("开始下载: " + finalName);
                    } catch (Exception e) {
                        try {
                            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(finalUrl));
                            startActivity(intent);
                        } catch (Exception e2) {
                            showToast("下载失败: " + e.getMessage());
                        }
                    }
                }
            });
        }
    }

    private class ChunkedUploadTask extends AsyncTask<Void, Integer, String> {
        private Uri fileUri;
        private String fileName;
        private String mimeType;
        private String deviceId;
        private String deviceName;
        private int totalChunks = 0;

        ChunkedUploadTask(Uri uri, String name, String type, String devId, String devName) {
            this.fileUri = uri;
            this.fileName = name;
            this.mimeType = type;
            this.deviceId = devId;
            this.deviceName = devName;
        }

        @Override
        protected String doInBackground(Void... voids) {
            try {
                // 根据当前网络状态选择上传地址
                String lanUrl = prefs.getString(KEY_LAN_URL, DEFAULT_LAN_URL);
                String publicUrl = prefs.getString(KEY_PUBLIC_URL, DEFAULT_PUBLIC_URL);
                String uploadUrl;

                // 如果当前使用公网或有公网地址且可达，优先使用公网上传
                if (isCurrentUrlPublic) {
                    uploadUrl = "http://" + publicUrl + "/upload-chunk";
                } else if (!publicUrl.isEmpty() && pingHost(getHost(publicUrl), getPort(publicUrl), PING_TIMEOUT_PUBLIC)) {
                    uploadUrl = "http://" + publicUrl + "/upload-chunk";
                } else {
                    uploadUrl = "http://" + lanUrl + "/upload-chunk";
                }

                InputStream inputStream = getContentResolver().openInputStream(fileUri);
                if (inputStream == null) return "Cannot open file";

                byte[] fileData = new byte[inputStream.available()];
                inputStream.read(fileData);
                inputStream.close();

                int fileSize = fileData.length;
                totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
                String uploadId = UUID.randomUUID().toString();

                for (int i = 0; i < totalChunks; i++) {
                    int start = i * CHUNK_SIZE;
                    int end = Math.min(fileSize, start + CHUNK_SIZE);
                    int chunkSize = end - start;
                    byte[] chunk = new byte[chunkSize];
                    System.arraycopy(fileData, start, chunk, 0, chunkSize);

                    String result = uploadChunk(uploadUrl, chunk, uploadId, i, totalChunks, fileName, fileSize, mimeType);
                    if (result == null) return "Upload failed at chunk " + i;

                    publishProgress((int) ((i + 1) * 100.0 / totalChunks));
                }

                return "Upload complete";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        private String uploadChunk(String urlStr, byte[] chunk, String uploadId,
                int chunkIndex, int totalChunks, String fileName, int totalSize, String contentType) {
            try {
                String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().substring(0, 16);

                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

                baos.write(("--" + boundary + "\r\n").getBytes());
                baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"chunk.bin\"\r\n").getBytes());
                baos.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
                baos.write(chunk);
                baos.write("\r\n".getBytes());

                String metadata = String.format(
                    "{\"upload_id\":\"%s\",\"chunk_index\":%d,\"total_chunks\":%d,\"file_name\":\"%s\",\"sender_id\":\"%s\",\"sender_name\":\"%s\",\"total_size\":%d,\"content_type\":\"%s\"}",
                    uploadId, chunkIndex, totalChunks, fileName, deviceId, deviceName, totalSize, contentType
                );
                baos.write(("--" + boundary + "\r\n").getBytes());
                baos.write("Content-Disposition: form-data; name=\"metadata\"\r\n".getBytes());
                baos.write("Content-Type: application/json\r\n\r\n".getBytes());
                baos.write(metadata.getBytes("UTF-8"));
                baos.write("\r\n".getBytes());
                baos.write(("--" + boundary + "--\r\n").getBytes());

                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setRequestProperty("Content-Length", String.valueOf(baos.size()));

                OutputStream os = conn.getOutputStream();
                os.write(baos.toByteArray());
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                        responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream()
                    )
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();

                return response.toString();
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
            showToast("上传进度: " + values[0] + "%");
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            showToast(result);

            final String finalFileName = fileName;
            String injectJs = String.format(
                "(function() { " +
                "  var isImage = '%s'.startsWith('image/'); " +
                "  var isVideo = '%s'.startsWith('video/'); " +
                "  var msgType = isVideo ? 'video' : (isImage ? 'image' : 'file'); " +
                "  var previewUrl = (isImage || isVideo) ? URL.createObjectURL(new Blob([new ArrayBuffer(1)])) : '%s'; " +
                "  var el = createMessageElement('sent', previewUrl, 'You', msgType, undefined); " +
                "  if (el) { " +
                "    el.dataset.fileName = '%s'; " +
                "    el.dataset.pending = 'false'; " +
                "    el.querySelector('.bubble').innerHTML += ' <span style=\"color:#4caf50\">OK</span>'; " +
                "    if (!isImage && !isVideo) { " +
                "      var fileCard = document.createElement('div'); " +
                "      fileCard.className = 'file-card'; " +
                "      fileCard.innerHTML = '<span class=\"file-icon\">F</span><div class=\"file-info\"><span class=\"file-name\">%s</span></div>'; " +
                "      el.querySelector('.bubble').appendChild(fileCard); " +
                "    } " +
                "    document.getElementById('chat-box').appendChild(el); " +
                "    scrollToBottom(); " +
                "  } " +
                "})();",
                mimeType, mimeType, finalFileName, finalFileName, finalFileName
            );

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(injectJs, null);
            } else {
                webView.loadUrl("javascript:" + injectJs);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FILE) {
            if (data == null || resultCode != Activity.RESULT_OK) {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }
                return;
            }

            Uri uri = data.getData();
            if (uri == null) {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }
                return;
            }

            String fileName = "file";
            String mimeType = "*/*";

            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }

            mimeType = getContentResolver().getType(uri);
            if (mimeType == null) mimeType = "*/*";

            if (pendingUploadDeviceId == null || pendingUploadDeviceId.isEmpty()) {
                pendingUploadDeviceId = "m-" + UUID.randomUUID().toString().substring(0, 9);
            }
            if (pendingUploadDeviceName == null || pendingUploadDeviceName.isEmpty()) {
                pendingUploadDeviceName = "Android";
            }

            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(uri);
                mUploadMessage = null;
            }

            new ChunkedUploadTask(uri, fileName, mimeType, pendingUploadDeviceId, pendingUploadDeviceName).execute();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveWebViewState(webView.getUrl(), 0);
        CookieSyncManager.getInstance().sync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CookieSyncManager.getInstance().startSync();
    }

    private void saveWebViewState(String url, int scrollY) {
        try {
            WebViewState state = new WebViewState();
            state.url = url;
            state.scrollY = scrollY;
            File file = new File(getFilesDir(), STATE_FILE);
            ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file));
            out.writeObject(state);
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private WebViewState loadWebViewState() {
        try {
            File file = new File(getFilesDir(), STATE_FILE);
            if (!file.exists()) return null;
            ObjectInputStream in = new ObjectInputStream(new FileInputStream(file));
            WebViewState state = (WebViewState) in.readObject();
            in.close();
            return state;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static class WebViewState implements Serializable {
        private static final long serialVersionUID = 1L;
        String url;
        int scrollY;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_refresh) {
            webView.reload();
            return true;
        } else if (id == R.id.action_home) {
            detectAndLoadUrl();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkReceiver != null) {
            try {
                unregisterReceiver(networkReceiver);
            } catch (Exception e) { }
        }
    }
}

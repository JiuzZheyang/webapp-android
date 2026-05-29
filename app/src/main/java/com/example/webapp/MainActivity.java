package com.example.webapp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.webkit.ValueCallback;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

public class MainActivity extends Activity {
    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "WebAppPrefs";
    private static final String KEY_URL = "web_url";
    private static final String DEFAULT_URL = "http://192.168.1.121:12345/";
    private static final int REQUEST_FILE = 100;
    private static final int CHUNK_SIZE = 512 * 1024; // 512KB
    private ValueCallback<Uri> mUploadMessage;
    private String pendingUploadFileName;
    private String pendingUploadMimeType;
    private String pendingUploadDeviceId;
    private String pendingUploadDeviceName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        setupWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            String url = prefs.getString(KEY_URL, DEFAULT_URL);
            webView.loadUrl(url);
        }
    }

    private void setupWebView() {
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) progressBar.setVisibility(ProgressBar.VISIBLE);
                // Get device info from localStorage for upload
                webView.evaluateJavascript(
                    "localStorage.getItem('tiez_device_id') || ('m-' + Math.random().toString(36).substr(2, 9))",
                    value -> {
                        pendingUploadDeviceId = value.replace("\"", "");
                    }
                );
                webView.evaluateJavascript(
                    "localStorage.getItem('tiez_device_name') || 'Mobile'",
                    value -> {
                        pendingUploadDeviceName = value.replace("\"", "");
                    }
                );
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(ProgressBar.GONE);
                prefs.edit().putString(KEY_URL, url).apply();
                injectFilePicker();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    if (newProgress == 100) {
                        progressBar.setVisibility(ProgressBar.GONE);
                    }
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
    }

    private void injectFilePicker() {
        String js = "(function() {" +
            "function setupFileInputs() {" +
            "  document.querySelectorAll('input[type=file]').forEach(function(input) {" +
            "    if (!input.dataset.androidReady) {" +
            "      input.dataset.androidReady = 'true';" +
            "      input.style.display = 'block';" +
            "      input.removeAttribute('multiple');" +
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
                // Get base URL from current webview
                String baseUrl = prefs.getString(KEY_URL, DEFAULT_URL);
                String uploadUrl = baseUrl.replace("/#", "") + "upload-chunk";

                // Get file size
                InputStream inputStream = getContentResolver().openInputStream(fileUri);
                if (inputStream == null) return "Cannot open file";
                
                byte[] fileData = new byte[inputStream.available()];
                inputStream.read(fileData);
                inputStream.close();
                
                int fileSize = fileData.length;
                totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
                String uploadId = UUID.randomUUID().toString();

                // Upload each chunk
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
                
                // File part
                baos.write(("--" + boundary + "\r\n").getBytes());
                baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"chunk.bin\"\r\n").getBytes());
                baos.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
                baos.write(chunk);
                baos.write("\r\n".getBytes());
                
                // Metadata part
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
            Toast.makeText(MainActivity.this, "上传进度: " + values[0] + "%", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();
            
            // Reload webview to show uploaded file
            webView.reload();
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

            // Get file name and mime type
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

            // Get device info
            if (pendingUploadDeviceId == null || pendingUploadDeviceId.isEmpty()) {
                pendingUploadDeviceId = "m-" + UUID.randomUUID().toString().substring(0, 9);
            }
            if (pendingUploadDeviceName == null || pendingUploadDeviceName.isEmpty()) {
                pendingUploadDeviceName = "Android";
            }

            // Use native upload instead of WebView callback
            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(uri);
                mUploadMessage = null;
            }

            // Start native chunked upload
            new ChunkedUploadTask(uri, fileName, mimeType, pendingUploadDeviceId, pendingUploadDeviceName).execute();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
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
            String url = prefs.getString(KEY_URL, DEFAULT_URL);
            webView.loadUrl(url);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

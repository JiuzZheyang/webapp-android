package com.example.webapp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.webkit.ValueCallback;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import android.util.Base64;

public class MainActivity extends Activity {
    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "WebAppPrefs";
    private static final String KEY_URL = "web_url";
    private static final String DEFAULT_URL = "http://192.168.1.121:12345/";
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private ValueCallback<Uri> mUploadMessage;
    private String mCurrentInputId = "";

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
        webView.addJavascriptInterface(new FileUploadInterface(), "AndroidFileUpload");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) progressBar.setVisibility(ProgressBar.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(ProgressBar.GONE);
                prefs.edit().putString(KEY_URL, url).apply();
                injectFileInputHandler();
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
                openFileChooser(uploadMsg, null, null);
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
                openFileChooser(uploadMsg, acceptType, null);
            }

            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                mUploadMessage = uploadMsg;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST_CODE);
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
    }

    private void injectFileInputHandler() {
        String script = "(function() { " +
            "var observer = new MutationObserver(function() { " +
            "document.querySelectorAll('input[type=file]').forEach(function(input) { " +
            "if (!input.hasAttribute('data-android-handled')) { " +
            "input.setAttribute('data-android-handled', 'true'); " +
            "input.setAttribute('data-input-id', 'file_input_' + Math.random().toString(36).substr(2, 9)); " +
            "input.addEventListener('click', function(e) { " +
            "e.preventDefault(); " +
            "window.AndroidFileUpload.setInputId(this.getAttribute('data-input-id')); " +
            "window.AndroidFileUpload.openFileChooser(); " +
            "}); } }); }); " +
            "observer.observe(document.body, { childList: true, subtree: true }); " +
            "document.querySelectorAll('input[type=file]').forEach(function(input) { " +
            "if (!input.hasAttribute('data-android-handled')) { " +
            "input.setAttribute('data-android-handled', 'true'); " +
            "input.setAttribute('data-input-id', 'file_input_' + Math.random().toString(36).substr(2, 9)); " +
            "input.addEventListener('click', function(e) { " +
            "e.preventDefault(); " +
            "window.AndroidFileUpload.setInputId(this.getAttribute('data-input-id')); " +
            "window.AndroidFileUpload.openFileChooser(); " +
            "}); } }); })();";

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(script, null);
        } else {
            webView.loadUrl("javascript:" + script);
        }
    }

    public class FileUploadInterface {
        @JavascriptInterface
        public void setInputId(String id) {
            mCurrentInputId = id;
        }

        @JavascriptInterface
        public void openFileChooser() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mUploadMessage = null;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST_CODE);
                }
            });
        }

        @JavascriptInterface
        public void uploadFile(final String base64Data, final String fileName, final String mimeType, final String inputId) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String js = "(function() { " +
                        "var input = document.querySelector('[data-input-id=\"' + '" + inputId + "' + '\"]'); " +
                        "if (input) { " +
                        "var blob = base64ToBlob('" + base64Data + "', '" + mimeType + "'); " +
                        "var file = new File([blob], '" + fileName + "', {type: '" + mimeType + "'}); " +
                        "Object.defineProperty(input, 'files', {value: [file], writable: true, configurable: true}); " +
                        "input.dispatchEvent(new Event('change', {bubbles: true})); " +
                        "} " +
                        "function base64ToBlob(b64, mime) { " +
                        "var byteCharacters = atob(b64); " +
                        "var byteNumbers = new Array(byteCharacters.length); " +
                        "for (var i = 0; i < byteCharacters.length; i++) { " +
                        "byteNumbers[i] = byteCharacters.charCodeAt(i); } " +
                        "var byteArray = new Uint8Array(byteNumbers); " +
                        "return new Blob([byteArray], {type: mime}); } " +
                        "})();";
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                        webView.evaluateJavascript(js, null);
                    } else {
                        webView.loadUrl("javascript:" + js);
                    }
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mUploadMessage == null) {
                return;
            }

            Uri result = null;
            String fileName = "file";
            String mimeType = "*/*";

            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                result = data.getData();
                
                // Get file name and mime type
                Cursor cursor = getContentResolver().query(result, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex);
                    }
                    cursor.close();
                }
                
                mimeType = getContentResolver().getType(result);
                if (mimeType == null) mimeType = "*/*";

                // Convert to base64
                try {
                    InputStream inputStream = getContentResolver().openInputStream(result);
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    inputStream.close();
                    byte[] byteArray = outputStream.toByteArray();
                    String base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP);
                    
                    final String inputId = mCurrentInputId;
                    final String base64Final = base64;
                    
                    // Upload via JS interface
                    final String finalFileName = fileName;
                    new FileUploadInterface().uploadFile(base64Final, finalFileName, mimeType, inputId);
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
            mCurrentInputId = "";
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

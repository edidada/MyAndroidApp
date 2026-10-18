package com.example.myapplication;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class VueActivity extends AppCompatActivity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vue);

        webView = findViewById(R.id.webview_vue);
        Button btnBack = findViewById(R.id.btn_back);

        btnBack.setOnClickListener(v -> finish());

        setupWebView();
        loadVuePage();
        registerBackHandler();
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
    }

    private void loadVuePage() {
        webView.loadUrl("file:///android_asset/vue_page.html");
    }

    /**
     * 返回处理：WebView 能后退就先退一页，否则交给系统默认行为。
     *
     * <p>原来这里重写 {@code Activity#onBackPressed()}，但本工程 targetSdk=36（Android 16），
     * 预测式返回（predictive back）默认开启，Activity.onBackPressed 对返回手势**不再被调用**，
     * WebView 的历史后退因此在 Android 16 上是失效的（lint 的 GestureBackNavigation 报的就是这个，
     * CI 上 :app:lintDevDebug 一直红）。迁到 AndroidX 的 OnBackPressedDispatcher，行为与旧代码逐条对齐：
     * canGoBack -> goBack；否则 setEnabled(false) 后重新派发，等价于以前的 super.onBackPressed()。
     */
    private void registerBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }
}
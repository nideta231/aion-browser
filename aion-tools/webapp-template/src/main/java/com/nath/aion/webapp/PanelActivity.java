package com.nath.aion.webapp;

import android.app.Activity;
import android.os.Bundle;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/** Hosts an extension popup, options page or extension-opened tab in its own small window. */
public class PanelActivity extends Activity {
    static final String EXTRA_TITLE = "title";
    static GeckoSession pending;

    private GeckoSession session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(title != null ? title : "Extension");
        session = pending;
        pending = null;
        if (session == null) {
            finish();
            return;
        }
        GeckoView view = new GeckoView(this);
        setContentView(view);
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCloseRequest(GeckoSession s) {
                finish();
            }
        });
        view.setSession(session);
    }

    @Override
    protected void onDestroy() {
        if (session != null && isFinishing()) session.close();
        super.onDestroy();
    }
}

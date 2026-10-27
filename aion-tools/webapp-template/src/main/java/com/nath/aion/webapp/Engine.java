package com.nath.aion.webapp;

import android.content.Context;
import android.content.Intent;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One Gecko engine per site app, shared by the site window, the Extensions screen and extension
 * popups. Extensions only install when the user asked for it from the Extensions screen (or the
 * built-in uBlock Origin on first run); pages can't install add-ons on their own.
 */
public final class Engine {
    static final String UBLOCK_SLUG = "ublock-origin";
    static final String UBLOCK_ID = "uBlock0@raymondhill.net";

    private static GeckoRuntime runtime;
    private static int approvedInstalls;
    /** Toolbar action of each extension (its popup button), keyed by extension id. */
    static final Map<String, WebExtension.Action> actions = new HashMap<>();
    /** Listeners told when the extension list changes. */
    static final List<Runnable> listeners = new ArrayList<>();

    private Engine() {}

    static synchronized GeckoRuntime get(Context context) {
        if (runtime != null) return runtime;
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .contentBlocking(new ContentBlocking.Settings.Builder()
                        .antiTracking(ContentBlocking.AntiTracking.STRICT)
                        .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                        .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                        .build())
                .aboutConfigEnabled(false)
                .consoleOutput(false)
                .build();
        runtime = GeckoRuntime.create(context.getApplicationContext(), settings);
        Context app = context.getApplicationContext();
        WebExtensionController controller = runtime.getWebExtensionController();
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension, String[] permissions, String[] origins, String[] dataCollection) {
                boolean allowed;
                synchronized (Engine.class) {
                    allowed = approvedInstalls > 0;
                    if (allowed) approvedInstalls--;
                }
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(allowed, false, false));
            }
        });
        controller.list().accept(extensions -> {
            boolean hasUblock = false;
            if (extensions != null) {
                for (WebExtension extension : extensions) {
                    attach(app, extension);
                    if (UBLOCK_ID.equals(extension.id)) hasUblock = true;
                }
            }
            if (!hasUblock) install(app, amoUrl(UBLOCK_SLUG));
        });
        return runtime;
    }

    static String amoUrl(String slug) {
        return "https://addons.mozilla.org/firefox/downloads/latest/" + slug + "/latest.xpi";
    }

    /** Installs an extension the user picked and wires its popup/options handlers. */
    static GeckoResult<WebExtension> install(Context context, String xpiUrl) {
        synchronized (Engine.class) {
            approvedInstalls++;
        }
        GeckoResult<WebExtension> result = runtime.getWebExtensionController().install(xpiUrl);
        return result.map(extension -> {
            attach(context, extension);
            changed();
            return extension;
        }, error -> {
            synchronized (Engine.class) {
                if (approvedInstalls > 0) approvedInstalls--;
            }
            return error;
        });
    }

    static void changed() {
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    /** Hooks an extension's toolbar button, popups, new tabs and options page into this app. */
    static void attach(Context context, WebExtension extension) {
        Context app = context.getApplicationContext();
        extension.setActionDelegate(new WebExtension.ActionDelegate() {
            @Override
            public void onBrowserAction(WebExtension ext, GeckoSession session, WebExtension.Action action) {
                if (session == null) {
                    actions.put(ext.id, action);
                    changed();
                }
            }

            @Override
            public GeckoResult<GeckoSession> onTogglePopup(WebExtension ext, WebExtension.Action action) {
                return openPanel(app, ext.metaData != null ? ext.metaData.name : "Extension", null);
            }

            @Override
            public GeckoResult<GeckoSession> onOpenPopup(WebExtension ext, WebExtension.Action action) {
                return openPanel(app, ext.metaData != null ? ext.metaData.name : "Extension", null);
            }
        });
        extension.setTabDelegate(new WebExtension.TabDelegate() {
            @Override
            public GeckoResult<GeckoSession> onNewTab(WebExtension ext, WebExtension.CreateTabDetails details) {
                return openPanel(app, ext.metaData != null ? ext.metaData.name : "Extension", null);
            }

            @Override
            public void onOpenOptionsPage(WebExtension ext) {
                if (ext.metaData != null && ext.metaData.optionsPageUrl != null) {
                    openPanel(app, ext.metaData.name, ext.metaData.optionsPageUrl);
                }
            }
        });
    }

    /** Shows a popup, options page or extension tab in [PanelActivity]. */
    static GeckoResult<GeckoSession> openPanel(Context context, String title, String url) {
        GeckoSession session = new GeckoSession();
        session.open(runtime);
        if (url != null) session.loadUri(url);
        PanelActivity.pending = session;
        Intent intent = new Intent(context, PanelActivity.class)
                .putExtra(PanelActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        context.startActivity(intent);
        return GeckoResult.fromValue(session);
    }
}

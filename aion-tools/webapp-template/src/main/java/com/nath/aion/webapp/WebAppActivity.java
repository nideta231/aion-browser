package com.nath.aion.webapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;

/**
 * A single-site app: one full-screen GeckoView with no browser toolbar. Its cookies and site data
 * live in this app's own profile. Links that leave the site open in Aion; pop-ups opened without a
 * tap are blocked.
 */
public class WebAppActivity extends Activity implements Engine.PopupHost {
    static final String EXTRA_OPEN_ACTION = "aion.open_action";
    private static final String AION_PACKAGE = "com.nath.aion";
    private static final int FILE_REQUEST = 1;

    private GeckoRuntime runtime;

    private GeckoView view;
    private android.widget.FrameLayout root;
    private android.widget.LinearLayout popupCard;
    private GeckoSession popupSession;
    private String pendingAction;
    private GeckoSession session;
    private String startUrl;
    private String siteHost;
    private boolean canGoBack;
    private boolean fullScreen;
    private PromptDelegate.FilePrompt pendingFilePrompt;
    private GeckoResult<PromptDelegate.PromptResponse> pendingFileResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startUrl = readStartUrl();
        siteHost = baseDomain(Uri.parse(startUrl).getHost());

        view = new GeckoView(this);
        root = new android.widget.FrameLayout(this);
        root.addView(view);
        setContentView(root);
        pendingAction = getIntent().getStringExtra(EXTRA_OPEN_ACTION);

        runtime = Engine.get(this);
        publishShortcuts();

        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .useTrackingProtection(true)
                .suspendMediaWhenInactive(false)
                .build());
        session.setNavigationDelegate(new Navigation());
        session.setContentDelegate(new Content());
        session.setPromptDelegate(new Prompts());
        session.setPermissionDelegate(new Permissions());
        session.setMediaSessionDelegate(new Media());
        session.open(runtime);
        view.setSession(session);
        runtime.getWebExtensionController().setTabActive(session, true);
        session.loadUri(startUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent.getData();
        if (data != null && session != null) session.loadUri(data.toString());
        String action = intent.getStringExtra(EXTRA_OPEN_ACTION);
        if (action != null) pendingAction = action;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Engine.host = this;
        if (session != null) {
            session.setActive(true);
            session.setFocused(true);
            // Extensions ask "which tab is active?" (popups, Dark Reader's site toggle). Tell them.
            runtime.getWebExtensionController().setTabActive(session, true);
        }
        if (pendingAction != null) {
            String id = pendingAction;
            pendingAction = null;
            // Give Gecko a moment to mark this page as the active tab before the popup asks for it.
            view.postDelayed(() -> clickAction(id), 400);
        }
    }

    @Override
    protected void onPause() {
        if (Engine.host == this) Engine.host = null;
        super.onPause();
    }

    private void clickAction(String id) {
        WebExtension.Action action = Engine.actions.get(id);
        if (action != null) action.click();
        else Toast.makeText(this, "This extension has no popup", Toast.LENGTH_SHORT).show();
    }

    /** Ctrl+Shift+X: pick an extension and open its popup over the page. */
    private void chooseExtension() {
        java.util.List<String> ids = new java.util.ArrayList<>(Engine.actions.keySet());
        if (ids.isEmpty()) {
            startActivity(new Intent(this, ExtensionsActivity.class));
            return;
        }
        runtime.getWebExtensionController().list().accept(list -> runOnUiThread(() -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<String> keep = new java.util.ArrayList<>();
            if (list != null) for (WebExtension ext : list) {
                if (ids.contains(ext.id) && ext.metaData != null && ext.metaData.enabled) {
                    keep.add(ext.id);
                    names.add(ext.metaData.name);
                }
            }
            names.add("Manage extensions…");
            new AlertDialog.Builder(this)
                    .setTitle("Extensions")
                    .setItems(names.toArray(new String[0]), (d, which) -> {
                        if (which < keep.size()) clickAction(keep.get(which));
                        else startActivity(new Intent(this, ExtensionsActivity.class));
                    })
                    .show();
        }));
    }

    /** Extension popup shown as a panel in the top-right of the site window. */
    @Override
    public GeckoResult<GeckoSession> showPopup(String title) {
        closePopup();
        float d = getResources().getDisplayMetrics().density;
        popupCard = new android.widget.LinearLayout(this);
        popupCard.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF202124);
        bg.setCornerRadius(14 * d);
        bg.setStroke(Math.round(d), 0xFF5F6368);
        popupCard.setBackground(bg);
        popupCard.setElevation(16 * d);
        popupCard.setClipToOutline(true);

        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(Math.round(14 * d), Math.round(4 * d), Math.round(4 * d), Math.round(4 * d));
        android.widget.TextView name = new android.widget.TextView(this);
        name.setText(title);
        name.setTextColor(0xFFE8EAED);
        bar.addView(name, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Button close = new android.widget.Button(this, null, android.R.attr.borderlessButtonStyle);
        close.setText("✕");
        close.setTextColor(0xFFE8EAED);
        close.setOnClickListener(v -> closePopup());
        bar.addView(close);
        popupCard.addView(bar);

        GeckoView popupView = new GeckoView(this);
        popupCard.addView(popupView, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));

        int width = Math.min(Math.round(400 * d), root.getWidth() - Math.round(16 * d));
        int height = Math.min(Math.round(600 * d), root.getHeight() - Math.round(16 * d));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(width, height,
                android.view.Gravity.TOP | android.view.Gravity.END);
        lp.setMargins(0, Math.round(8 * d), Math.round(8 * d), 0);
        root.addView(popupCard, lp);

        popupSession = new GeckoSession();
        popupSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCloseRequest(GeckoSession s) {
                closePopup();
            }
        });
        popupSession.open(runtime);
        popupView.setSession(popupSession);
        return GeckoResult.fromValue(popupSession);
    }

    private boolean closePopup() {
        if (popupCard == null) return false;
        root.removeView(popupCard);
        popupCard = null;
        if (popupSession != null) popupSession.close();
        popupSession = null;
        if (session != null) session.setActive(true);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (closePopup()) {
            return;
        } else if (fullScreen) {
            session.exitFullScreen();
        } else if (canGoBack) {
            session.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        MediaPlaybackService.stop(this);
        if (session != null) session.close();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_REQUEST || pendingFileResult == null) return;
        PromptDelegate.FilePrompt prompt = pendingFilePrompt;
        GeckoResult<PromptDelegate.PromptResponse> result = pendingFileResult;
        pendingFilePrompt = null;
        pendingFileResult = null;
        if (resultCode != RESULT_OK || data == null) {
            result.complete(prompt.dismiss());
        } else if (data.getClipData() != null) {
            Uri[] uris = new Uri[data.getClipData().getItemCount()];
            for (int i = 0; i < uris.length; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
            result.complete(prompt.confirm(this, uris));
        } else if (data.getData() != null) {
            result.complete(prompt.confirm(this, data.getData()));
        } else {
            result.complete(prompt.dismiss());
        }
    }

    private String readStartUrl() {
        try {
            ApplicationInfo info = getPackageManager()
                    .getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String url = info.metaData != null ? info.metaData.getString("aion.start_url") : null;
            if (url != null) return url.trim();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return "about:blank";
    }

    /** Long-press the app icon -> "Extensions" (the site window has no toolbar to hold a menu). */
    private void publishShortcuts() {
        if (Build.VERSION.SDK_INT < 25) return;
        android.content.pm.ShortcutManager manager = getSystemService(android.content.pm.ShortcutManager.class);
        if (manager == null) return;
        Intent intent = new Intent(this, ExtensionsActivity.class).setAction(Intent.ACTION_VIEW);
        android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, "extensions")
                .setShortLabel("Extensions")
                .setLongLabel("Extensions")
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_manage))
                .setIntent(intent)
                .build();
        try {
            manager.setDynamicShortcuts(java.util.Collections.singletonList(info));
        } catch (RuntimeException ignored) {
        }
    }

    /** Ctrl+Shift+E opens Extensions; F5 / Ctrl+R reloads. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (event.isCtrlPressed() && event.isShiftPressed() && keyCode == android.view.KeyEvent.KEYCODE_X) {
            chooseExtension();
            return true;
        }
        if (event.isCtrlPressed() && event.isShiftPressed() && keyCode == android.view.KeyEvent.KEYCODE_E) {
            startActivity(new Intent(this, ExtensionsActivity.class));
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_F5
                || event.isCtrlPressed() && keyCode == android.view.KeyEvent.KEYCODE_R) {
            session.reload();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** example.co.uk -> example.co.uk, music.youtube.com -> youtube.com. Good enough for "same site". */
    static String baseDomain(String host) {
        if (host == null) return "";
        String[] parts = host.toLowerCase().split("\\.");
        if (parts.length <= 2) return host.toLowerCase();
        String last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
        boolean shortSecondLevel = parts[parts.length - 2].length() <= 3 && parts[parts.length - 1].length() == 2;
        return shortSecondLevel && parts.length >= 3 ? parts[parts.length - 3] + "." + last2 : last2;
    }

    private boolean isSameSite(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null) return true;
        if (!scheme.equals("http") && !scheme.equals("https")) return true;
        String host = baseDomain(uri.getHost());
        return host.equals(siteHost) || isLoginHost(uri.getHost());
    }

    /** Sign-in pages of common identity providers stay inside the app so logins work. */
    private static boolean isLoginHost(String host) {
        if (host == null) return false;
        return host.equals("accounts.google.com") || host.endsWith(".google.com") && host.startsWith("accounts")
                || host.equals("accounts.youtube.com") || host.endsWith("appleid.apple.com")
                || host.endsWith("login.microsoftonline.com") || host.endsWith("login.live.com")
                || host.endsWith("facebook.com") || host.endsWith("discord.com") || host.endsWith("twitch.tv")
                || host.endsWith("github.com") || host.endsWith("challenges.cloudflare.com");
    }

    private void openInAion(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setPackage(AION_PACKAGE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setSystemBarsHidden(boolean enabled) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            if (enabled) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(enabled
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : 0);
        }
    }

    private class Navigation implements GeckoSession.NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession s, boolean value) {
            canGoBack = value;
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
            String uri = request.uri;
            Uri parsed = Uri.parse(uri);
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme();
            if (scheme.equals("intent") || scheme.equals("market") || scheme.equals("mailto")
                    || scheme.equals("tel") || scheme.equals("whatsapp")) {
                if (request.hasUserGesture) openInAion(uri);
                return GeckoResult.deny();
            }
            if (request.target == TARGET_WINDOW_NEW) {
                // Pop-up or target=_blank. Without a tap it is a pop-up ad: drop it.
                if (!request.hasUserGesture) return GeckoResult.deny();
                if (uri == null || uri.isEmpty() || uri.startsWith("about:")) return GeckoResult.deny();
                if (isSameSite(uri)) {
                    s.loadUri(uri);
                } else {
                    openInAion(uri);
                }
                return GeckoResult.deny();
            }
            if (!isSameSite(uri) && request.isDirectNavigation == false && !request.isRedirect) {
                if (!request.hasUserGesture) return GeckoResult.deny(); // drive-by redirect to an ad
                openInAion(uri);
                return GeckoResult.deny();
            }
            return GeckoResult.allow();
        }

        @Override
        public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
            return null; // handled in onLoadRequest; never open extra windows
        }
    }

    private class Content implements GeckoSession.ContentDelegate {
        @Override
        public void onFullScreen(GeckoSession s, boolean enabled) {
            fullScreen = enabled;
            setSystemBarsHidden(enabled);
        }

        @Override
        public void onExternalResponse(GeckoSession s, WebResponse response) {
            // Downloads go to Aion, which has the download manager.
            openInAion(response.uri);
        }

        @Override
        public void onCrash(GeckoSession s) {
            s.close();
            recreate();
        }

        @Override
        public void onKill(GeckoSession s) {
            s.close();
            recreate();
        }
    }

    private class Prompts implements PromptDelegate {
        @Override
        public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt prompt) {
            GeckoResult<PromptResponse> result = new GeckoResult<>();
            new AlertDialog.Builder(WebAppActivity.this)
                    .setTitle(prompt.title)
                    .setMessage(prompt.message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.complete(prompt.dismiss()))
                    .setOnCancelListener(d -> result.complete(prompt.dismiss()))
                    .show();
            return result;
        }

        @Override
        public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt prompt) {
            GeckoResult<PromptResponse> result = new GeckoResult<>();
            new AlertDialog.Builder(WebAppActivity.this)
                    .setTitle(prompt.title)
                    .setMessage(prompt.message)
                    .setPositiveButton(android.R.string.ok,
                            (d, w) -> result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)))
                    .setNegativeButton(android.R.string.cancel,
                            (d, w) -> result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
                    .setOnCancelListener(d -> result.complete(prompt.dismiss()))
                    .show();
            return result;
        }

        @Override
        public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt prompt) {
            GeckoResult<PromptResponse> result = new GeckoResult<>();
            EditText input = new EditText(WebAppActivity.this);
            input.setText(prompt.defaultValue);
            new AlertDialog.Builder(WebAppActivity.this)
                    .setTitle(prompt.title)
                    .setMessage(prompt.message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok,
                            (d, w) -> result.complete(prompt.confirm(input.getText().toString())))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.complete(prompt.dismiss()))
                    .setOnCancelListener(d -> result.complete(prompt.dismiss()))
                    .show();
            return result;
        }

        @Override
        public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt prompt) {
            GeckoResult<PromptResponse> result = new GeckoResult<>();
            ChoicePrompt.Choice[] choices = prompt.choices;
            String[] labels = new String[choices.length];
            for (int i = 0; i < choices.length; i++) labels[i] = choices[i].label;
            AlertDialog.Builder builder = new AlertDialog.Builder(WebAppActivity.this).setTitle(prompt.title);
            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                boolean[] checked = new boolean[choices.length];
                for (int i = 0; i < choices.length; i++) checked[i] = choices[i].selected;
                builder.setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            java.util.List<ChoicePrompt.Choice> picked = new java.util.ArrayList<>();
                            for (int i = 0; i < choices.length; i++) if (checked[i]) picked.add(choices[i]);
                            result.complete(prompt.confirm(picked.toArray(new ChoicePrompt.Choice[0])));
                        });
            } else {
                builder.setItems(labels, (d, which) -> result.complete(prompt.confirm(choices[which])));
            }
            builder.setOnCancelListener(d -> result.complete(prompt.dismiss())).show();
            return result;
        }

        @Override
        public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(prompt.mimeTypes != null && prompt.mimeTypes.length == 1 ? prompt.mimeTypes[0] : "*/*");
            if (prompt.mimeTypes != null && prompt.mimeTypes.length > 1) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes);
            }
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE);
            GeckoResult<PromptResponse> result = new GeckoResult<>();
            pendingFilePrompt = prompt;
            pendingFileResult = result;
            try {
                startActivityForResult(intent, FILE_REQUEST);
            } catch (ActivityNotFoundException e) {
                pendingFilePrompt = null;
                pendingFileResult = null;
                return GeckoResult.fromValue(prompt.dismiss());
            }
            return result;
        }

        @Override
        public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession s, PopupPrompt prompt) {
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY));
        }

        @Override
        public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession s, BeforeUnloadPrompt prompt) {
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
        }

        @Override
        public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession s, RepostConfirmPrompt prompt) {
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
        }
    }

    private class Permissions implements GeckoSession.PermissionDelegate {
        @Override
        public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission permission) {
            switch (permission.permission) {
                case PERMISSION_AUTOPLAY_AUDIBLE:
                case PERMISSION_AUTOPLAY_INAUDIBLE:
                case PERMISSION_PERSISTENT_STORAGE:
                case PERMISSION_MEDIA_KEY_SYSTEM_ACCESS:
                case PERMISSION_STORAGE_ACCESS:
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                default:
                    return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        }
    }

    private class Media implements MediaSession.Delegate {
        @Override
        public void onActivated(GeckoSession s, MediaSession mediaSession) {
            MediaPlaybackService.attach(mediaSession);
        }

        @Override
        public void onDeactivated(GeckoSession s, MediaSession mediaSession) {
            MediaPlaybackService.stop(WebAppActivity.this);
        }

        @Override
        public void onMetadata(GeckoSession s, MediaSession mediaSession, MediaSession.Metadata metadata) {
            MediaPlaybackService.updateMetadata(WebAppActivity.this, metadata.title, metadata.artist);
        }

        @Override
        public void onPlay(GeckoSession s, MediaSession mediaSession) {
            MediaPlaybackService.start(WebAppActivity.this, true);
        }

        @Override
        public void onPause(GeckoSession s, MediaSession mediaSession) {
            MediaPlaybackService.start(WebAppActivity.this, false);
        }

        @Override
        public void onStop(GeckoSession s, MediaSession mediaSession) {
            MediaPlaybackService.stop(WebAppActivity.this);
        }
    }
}

package com.nath.aion.webapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extensions for this site app: install from addons.mozilla.org by name or link, turn on/off,
 * open the extension's popup or settings, remove. Each site app keeps its own extensions.
 */
public class ExtensionsActivity extends Activity {
    private static final String[][] SUGGESTIONS = {
            {"Dark Reader", "darkreader"},
            {"SponsorBlock", "sponsorblock"},
            {"Return YouTube Dislike", "return-youtube-dislikes"},
            {"Bitwarden", "bitwarden-password-manager"},
            {"uBlock Origin", Engine.UBLOCK_SLUG},
    };

    private GeckoRuntime runtime;
    private LinearLayout list;
    private TextView status;
    private final Runnable refresher = () -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Extensions");
        runtime = Engine.get(this);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Extensions");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Extensions here only run in this app. Type a name from addons.mozilla.org or paste its link.");
        hint.setAlpha(0.7f);
        hint.setPadding(0, dp(4), 0, dp(12));
        root.addView(hint);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this);
        input.setHint("e.g. Dark Reader or addons.mozilla.org/…/darkreader");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        addRow.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button add = new Button(this);
        add.setText("Install");
        add.setOnClickListener(v -> installFromInput(input.getText().toString()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            installFromInput(input.getText().toString());
            return true;
        });
        addRow.addView(add);
        root.addView(addRow);

        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        LinearLayout chips = new LinearLayout(this);
        for (String[] suggestion : SUGGESTIONS) {
            Button chip = new Button(this, null, android.R.attr.borderlessButtonStyle);
            chip.setText(suggestion[0]);
            chip.setAllCaps(false);
            chip.setOnClickListener(v -> install(suggestion[1], suggestion[0]));
            chips.addView(chip);
        }
        Button browse = new Button(this, null, android.R.attr.borderlessButtonStyle);
        browse.setText("Browse add-ons…");
        browse.setAllCaps(false);
        browse.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://addons.mozilla.org/android/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        chips.addView(browse);
        chipsScroll.addView(chips);
        root.addView(chipsScroll);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setVisibility(View.GONE);
        root.addView(status);

        TextView installed = new TextView(this);
        installed.setText("Installed");
        installed.setTypeface(Typeface.DEFAULT_BOLD);
        installed.setPadding(0, dp(12), 0, dp(4));
        root.addView(installed);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        Engine.listeners.add(refresher);
        refresh();
    }

    @Override
    protected void onDestroy() {
        Engine.listeners.remove(refresher);
        super.onDestroy();
    }

    private void installFromInput(String text) {
        String value = text.trim();
        if (value.isEmpty()) return;
        if (value.endsWith(".xpi") && value.startsWith("https://")) {
            installUrl(value, value);
            return;
        }
        Matcher amo = Pattern.compile("addons\\.mozilla\\.org/[^ ]*?/addon/([^/?# ]+)").matcher(value);
        String slug = amo.find() ? amo.group(1)
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        install(slug, value);
    }

    private void install(String slug, String label) {
        installUrl(Engine.amoUrl(slug), label);
    }

    private void installUrl(String url, String label) {
        showStatus("Installing " + label + "…");
        Engine.install(this, url).accept(
                extension -> runOnUiThread(() -> {
                    showStatus((extension.metaData != null ? extension.metaData.name : label) + " installed");
                    refresh();
                }),
                error -> runOnUiThread(() -> showStatus("Couldn't install " + label
                        + ". Check the name on addons.mozilla.org, or paste the add-on's page link.")));
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void refresh() {
        runtime.getWebExtensionController().list().accept(extensions -> runOnUiThread(() -> render(extensions)));
    }

    private void render(List<WebExtension> extensions) {
        list.removeAllViews();
        if (extensions == null || extensions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No extensions yet.");
            empty.setAlpha(0.7f);
            list.addView(empty);
            return;
        }
        for (WebExtension extension : extensions) {
            if (extension.isBuiltIn) continue;
            list.addView(row(extension));
        }
    }

    private View row(WebExtension extension) {
        WebExtension.MetaData meta = extension.metaData;
        String name = meta != null && meta.name != null ? meta.name : extension.id;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        top.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        if (meta != null && meta.icon != null) {
            meta.icon.getBitmap(dp(32)).accept(bitmap -> {
                if (bitmap != null) runOnUiThread(() -> icon.setImageBitmap(bitmap));
            });
        }
        TextView label = new TextView(this);
        label.setText(name + (meta != null && meta.version != null ? "  " + meta.version : ""));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setPadding(dp(12), 0, dp(12), 0);
        top.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch enabled = new Switch(this);
        enabled.setChecked(meta != null && meta.enabled);
        enabled.setOnCheckedChangeListener((button, on) -> {
            WebExtensionController controller = runtime.getWebExtensionController();
            (on ? controller.enable(extension, WebExtensionController.EnableSource.USER)
                    : controller.disable(extension, WebExtensionController.EnableSource.USER))
                    .accept(updated -> {
                        if (updated != null) Engine.attach(this, updated);
                        refresh();
                    });
        });
        top.addView(enabled);
        row.addView(top);

        LinearLayout buttons = new LinearLayout(this);
        WebExtension.Action action = Engine.actions.get(extension.id);
        if (action != null && meta != null && meta.enabled) {
            buttons.addView(smallButton("Open", v -> {
                startActivity(new Intent(this, WebAppActivity.class)
                        .putExtra(WebAppActivity.EXTRA_OPEN_ACTION, extension.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                finish();
            }));
        }
        if (meta != null && meta.optionsPageUrl != null && meta.enabled) {
            buttons.addView(smallButton("Settings", v -> Engine.openPanel(this, name, meta.optionsPageUrl)));
        }
        buttons.addView(smallButton("Remove", v -> new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setPositiveButton("Remove", (d, w) -> runtime.getWebExtensionController().uninstall(extension)
                        .accept(ignored -> {
                            Engine.actions.remove(extension.id);
                            runOnUiThread(this::refresh);
                        }))
                .setNegativeButton("Cancel", null)
                .show()));
        row.addView(buttons);
        return row;
    }

    private Button smallButton(String text, View.OnClickListener click) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(click);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

package com.bloodygram.plugins;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;

import com.bloodygram.BloodyStrings;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Installed plugins: toggle, long press for info/remove, install from a file or the bundled example. */
public class BloodyPluginsActivity extends UniversalFragment {

    private static final int ID_INSTALL_FILE = 1;
    private static final int ID_INSTALL_EXAMPLE = 2;
    private static final int ID_PLUGIN = 100;
    private static final int REQUEST_FILE = 0x0B10;

    private ArrayList<BloodyPlugins.Plugin> plugins = new ArrayList<>();

    static final String EXAMPLE = "// @name Bloody tools\n"
            + "// @description /shout, /mock, /roll and :fire: → 🔥\n"
            + "// @version 1.0\n"
            + "// @author Bloodygram\n"
            + "\n"
            + "bloody.command('shout', 'CAPS', function (args) {\n"
            + "  return args.toUpperCase() + '!!!';\n"
            + "});\n"
            + "\n"
            + "bloody.command('mock', 'sPoNgEbOb', function (args) {\n"
            + "  var out = '';\n"
            + "  for (var i = 0; i < args.length; i++) out += i % 2 ? args[i].toUpperCase() : args[i].toLowerCase();\n"
            + "  return out;\n"
            + "});\n"
            + "\n"
            + "bloody.command('roll', 'dice 1-100', function () {\n"
            + "  return '🎲 ' + (1 + Math.floor(Math.random() * 100));\n"
            + "});\n"
            + "\n"
            + "bloody.onSend(function (text) {\n"
            + "  return text.replace(/:fire:/g, '🔥').replace(/:blood:/g, '🩸');\n"
            + "});\n";

    @Override
    protected CharSequence getTitle() {
        return BloodyStrings.get(R.string.BloodyPlugins);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        plugins = BloodyPlugins.list();
        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyPluginsInstalled)));
        for (int i = 0; i < plugins.size(); i++) {
            BloodyPlugins.Plugin p = plugins.get(i);
            String title = p.name + (TextUtils.isEmpty(p.version) ? "" : " " + p.version);
            String subtitle = p.error != null ? "⚠️ " + p.error : !p.commands.isEmpty() ? commandsLine(p) : p.description;
            items.add(UItem.asButtonCheck(ID_PLUGIN + i, title, subtitle).setChecked(p.isEnabled()));
        }
        if (plugins.isEmpty()) {
            items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyPluginsEmpty)));
        }
        items.add(UItem.asButton(ID_INSTALL_FILE, R.drawable.msg_download, BloodyStrings.get(R.string.BloodyPluginsInstallFile)));
        items.add(UItem.asButton(ID_INSTALL_EXAMPLE, R.drawable.msg_emoji_objects, BloodyStrings.get(R.string.BloodyPluginsInstallExample)));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyPluginsInfo)));
    }

    private static String commandsLine(BloodyPlugins.Plugin p) {
        StringBuilder sb = new StringBuilder();
        for (String name : p.commands.keySet()) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append('/').append(name);
        }
        return sb.toString();
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_INSTALL_FILE) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQUEST_FILE);
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else if (item.id == ID_INSTALL_EXAMPLE) {
            confirmInstall(this, "bloody_tools.js", EXAMPLE, () -> listView.adapter.update(true));
        } else if (item.id >= ID_PLUGIN && item.id - ID_PLUGIN < plugins.size()) {
            BloodyPlugins.Plugin p = plugins.get(item.id - ID_PLUGIN);
            BloodyPlugins.setEnabled(p, !p.isEnabled());
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < ID_PLUGIN || item.id - ID_PLUGIN >= plugins.size()) {
            return false;
        }
        BloodyPlugins.Plugin p = plugins.get(item.id - ID_PLUGIN);
        ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_info, BloodyStrings.get(R.string.BloodyPluginsAbout), () -> showInfo(p))
                .add(R.drawable.msg_delete, BloodyStrings.get(R.string.BloodyPluginsRemove), true, () -> {
                    BloodyPlugins.remove(p);
                    listView.adapter.update(true);
                })
                .show();
        return true;
    }

    private void showInfo(BloodyPlugins.Plugin p) {
        if (getParentActivity() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(p.description)) {
            sb.append(p.description).append("\n\n");
        }
        if (!TextUtils.isEmpty(p.author)) {
            sb.append(BloodyStrings.get(R.string.BloodyPluginsAuthor)).append(": ").append(p.author).append('\n');
        }
        for (BloodyPlugins.Command c : p.commands.values()) {
            sb.append('/').append(c.name).append(TextUtils.isEmpty(c.description) ? "" : " — " + c.description).append('\n');
        }
        if (p.error != null) {
            sb.append("\n⚠️ ").append(p.error);
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(p.name)
                .setMessage(sb.toString().trim())
                .setPositiveButton(BloodyStrings.get(R.string.BloodyClose), null)
                .show();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_FILE || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            String source = BloodyPlugins.read(ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri));
            String name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) {
                name = name.substring(name.lastIndexOf('/') + 1);
            }
            confirmInstall(this, name, source, () -> listView.adapter.update(true));
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(BloodyStrings.get(R.string.BloodyPluginsBadFile)).show();
        }
    }

    /** Shows what is being installed and the sandbox warning, then installs. Also used from the chat message menu. */
    public static void confirmInstall(BaseFragment fragment, String fileName, String source, Runnable done) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        BloodyPlugins.Plugin parsed = BloodyPlugins.parse("preview", source);
        String header = parsed.name + (TextUtils.isEmpty(parsed.version) ? "" : " " + parsed.version)
                + (TextUtils.isEmpty(parsed.description) ? "" : "\n" + parsed.description);
        new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(BloodyStrings.get(R.string.BloodyPluginsInstallTitle))
                .setMessage(header + "\n\n" + BloodyStrings.get(R.string.BloodyPluginsWarning))
                .setPositiveButton(BloodyStrings.get(R.string.BloodyPluginsInstall), (d, w) -> {
                    try {
                        BloodyPlugins.install(fileName, source);
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, BloodyStrings.format(R.string.BloodyPluginsInstalledToast, parsed.name)).show();
                    } catch (Exception e) {
                        FileLog.e(e);
                        BulletinFactory.of(fragment).createErrorBulletin(BloodyStrings.get(R.string.BloodyPluginsBadFile)).show();
                    }
                    if (done != null) {
                        done.run();
                    }
                })
                .setNegativeButton(BloodyStrings.get(R.string.BloodyClose), null)
                .show();
    }
}

package com.bloodygram.ui;

import android.content.Context;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/** Per-account look: every account can have its own theme, applied when switching to it. */
public class BloodyAccounts {

    public static void onAccountSwitched(int account) {
        apply(BloodyConfig.getAccountTheme(account));
    }

    private static void apply(String name) {
        if (name == null) {
            return;
        }
        Theme.ThemeInfo theme = Theme.getTheme(name);
        if (theme != null && theme != Theme.getActiveTheme()) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, theme, false, null, -1);
        }
    }

    public static String themeLabel(int account) {
        String name = BloodyConfig.getAccountTheme(account);
        return name == null ? BloodyStrings.get(R.string.BloodyAccountThemeShared) : name;
    }

    public static void chooseTheme(Context context, int account, Runnable onChanged) {
        ArrayList<String> names = new ArrayList<>();
        names.add(BloodyStrings.get(R.string.BloodyAccountThemeShared));
        for (Theme.ThemeInfo theme : Theme.themes) {
            if (theme != null && theme.getName() != null && !names.contains(theme.getName()) && theme.info == null) {
                names.add(theme.getName());
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(BloodyStrings.get(R.string.BloodyAccountTheme));
        builder.setItems(names.toArray(new String[0]), (dialog, which) -> {
            String name = which == 0 ? null : names.get(which);
            BloodyConfig.setAccountTheme(account, name);
            apply(name);
            onChanged.run();
        });
        builder.show();
    }
}

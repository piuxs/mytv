package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phone-style recent apps over Home: a row of app cards and a Clear all card. OK opens a card,
 * holding OK closes that one app, and Clear all closes every app in the row. "Closing" means
 * ActivityManager.killBackgroundProcesses: it frees the app's background process, never uninstalls it
 * and never touches its data. This is what a phone's recents swipe does without root.
 *
 * Recent apps come from UsageStatsManager, which Android exposes behind the GET_USAGE_STATS app-op.
 * build-and-install.sh grants it with appops; without it the panel explains how to open the setting.
 * UsageStats remembers the last time an app was used even after it is killed, so a small preference
 * records the clear time and hides a cleared app until it is used again.
 */
public final class RecentsActivity extends Activity {
    private static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_APPS = 10;
    private static final String PREFS = "recents";
    private static final String CLEARED_PREFIX = "cleared_";

    private static final Set<String> EXCLUDED = new HashSet<>();
    static {
        EXCLUDED.add("com.android.systemui");
        EXCLUDED.add("com.android.tv.settings");
        EXCLUDED.add("com.android.settings");
        EXCLUDED.add("com.android.shell");
        EXCLUDED.add("com.google.android.tvlauncher");
        EXCLUDED.add("com.google.android.leanbacklauncher");
        EXCLUDED.add("com.tcl.framework.custom");
        // TCL account, reminder, Alexa and key helpers start themselves at boot; they are not apps the
        // person opened. Play Store is kept: it is on the dock and is a normal app to close.
        EXCLUDED.add("com.tcl.usercenter");
        EXCLUDED.add("com.tcl.notereminder");
        EXCLUDED.add("com.tcl.useragreement");
        EXCLUDED.add("com.tcl.smartalexa");
        EXCLUDED.add("com.tcl.keycustomfunctionservice");
    }

    private final List<Recent> recents = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RecyclerView list;
    private View empty;
    private ImageView emptyIcon;
    private TextView emptyText;
    private TextView usageButton;
    private View clearButton;
    private RecentsAdapter adapter;
    private SharedPreferences cleared;
    private boolean usageMissing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_recents);
        cleared = getSharedPreferences(PREFS, MODE_PRIVATE);
        findViewById(R.id.recentsRoot).setOnClickListener(v -> finish());
        list = findViewById(R.id.recentsList);
        empty = findViewById(R.id.recentsEmpty);
        emptyIcon = findViewById(R.id.recentsEmptyIcon);
        emptyText = findViewById(R.id.recentsEmptyText);
        usageButton = findViewById(R.id.recentsUsageButton);
        usageButton.setOnClickListener(v -> openUsageAccess());
        clearButton = findViewById(R.id.btnClearAll);
        clearButton.setOnClickListener(v -> clearAll());
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        list.setItemAnimator(null);
        adapter = new RecentsAdapter();
        list.setAdapter(adapter);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the usage-access screen: load the list now that the app-op may be granted.
        if (usageMissing && hasUsageAccess()) load();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        load();
    }

    private void load() {
        if (isFinishing()) return;
        recents.clear();
        usageMissing = false;
        if (!hasUsageAccess()) {
            usageMissing = true;
            adapter.notifyDataSetChanged();
            showEmpty(getString(R.string.usage_access_needed), false, true);
            return;
        }
        collect();
        if (recents.isEmpty()) {
            adapter.notifyDataSetChanged();
            showEmpty(getString(R.string.no_recent_apps), true, false);
        } else {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            clearButton.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
            list.post(() -> {
                RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(0);
                if (vh != null) vh.itemView.requestFocus();
            });
        }
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void collect() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW_MS, now);
        if (stats == null) return;
        Map<String, UsageStats> newest = new HashMap<>();
        for (UsageStats s : stats) {
            if (s == null) continue;
            long used = s.getLastTimeUsed();
            if (used <= 0 || now - used > WINDOW_MS) continue;
            UsageStats prev = newest.get(s.getPackageName());
            if (prev == null || prev.getLastTimeUsed() < used) newest.put(s.getPackageName(), s);
        }
        List<UsageStats> sorted = new ArrayList<>(newest.values());
        sorted.sort(Comparator.comparingLong(UsageStats::getLastTimeUsed).reversed());
        PackageManager pm = getPackageManager();
        for (UsageStats s : sorted) {
            if (recents.size() >= MAX_APPS) break;
            String pkg = s.getPackageName();
            if (pkg.equals(getPackageName()) || EXCLUDED.contains(pkg)) continue;
            if (s.getLastTimeUsed() <= cleared.getLong(CLEARED_PREFIX + pkg, 0L)) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                Drawable icon = pm.getApplicationIcon(info);
                String label = pm.getApplicationLabel(info).toString();
                recents.add(new Recent(pkg, label, icon, launch));
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // An app removed between queryUsageStats and getApplicationInfo: just skip it.
            }
        }
    }

    private void showEmpty(String text, boolean check, boolean offerUsage) {
        list.setVisibility(View.GONE);
        clearButton.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        emptyText.setText(text);
        emptyIcon.setVisibility(check ? View.VISIBLE : View.GONE);
        usageButton.setVisibility(offerUsage ? View.VISIBLE : View.GONE);
        if (offerUsage) usageButton.post(usageButton::requestFocus);
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void open(Recent recent) {
        try {
            startActivity(recent.launch);
            finish();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** One card: close just that app after its card shrinks away. */
    private void close(Recent recent) {
        int position = recents.indexOf(recent);
        if (position < 0) return;
        kill(recent.pkg);
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(position);
        Toast.makeText(this, getString(R.string.closed_app, recent.label), Toast.LENGTH_SHORT).show();
        if (vh == null) {
            recents.remove(position);
            adapter.notifyItemRemoved(position);
            if (recents.isEmpty()) showEmpty(getString(R.string.all_clear), true, false);
            return;
        }
        vh.itemView.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(180)
                .withEndAction(() -> {
                    if (isFinishing()) return;
                    int at = recents.indexOf(recent);
                    if (at < 0) return;
                    recents.remove(at);
                    adapter.notifyItemRemoved(at);
                    if (recents.isEmpty()) showEmpty(getString(R.string.all_clear), true, false);
                }).start();
    }

    /** Clear all: close every app in the row, then show the all-clear briefly and go back Home. */
    private void clearAll() {
        int count = recents.size();
        if (count == 0) return;
        for (Recent recent : recents) kill(recent.pkg);
        for (int i = 0; i < list.getChildCount(); i++) {
            list.getChildAt(i).animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(200).start();
        }
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            recents.clear();
            adapter.notifyDataSetChanged();
            showEmpty(getString(R.string.all_clear), true, false);
            handler.postDelayed(this::finish, 900);
        }, 230);
    }

    private void kill(String pkg) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) am.killBackgroundProcesses(pkg);
        cleared.edit().putLong(CLEARED_PREFIX + pkg, System.currentTimeMillis()).apply();
    }

    private static final class Recent {
        final String pkg;
        final String label;
        final Drawable icon;
        final Intent launch;

        Recent(String pkg, String label, Drawable icon, Intent launch) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.launch = launch;
        }
    }

    private final class RecentsAdapter extends RecyclerView.Adapter<RecentsAdapter.Holder> {

        @Override
        public int getItemCount() {
            return recents.size();
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_app, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            Recent recent = recents.get(position);
            holder.itemView.setScaleX(1f);
            holder.itemView.setScaleY(1f);
            holder.itemView.setAlpha(1f);
            holder.itemView.setNextFocusDownId(R.id.btnClearAll);
            holder.icon.setImageDrawable(recent.icon);
            holder.label.setText(recent.label);
            holder.itemView.setOnClickListener(v -> open(recent));
            holder.itemView.setOnLongClickListener(v -> {
                close(recent);
                return true;
            });
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;

            Holder(View view) {
                super(view);
                icon = view.findViewById(R.id.recentIcon);
                label = view.findViewById(R.id.recentLabel);
            }
        }
    }
}

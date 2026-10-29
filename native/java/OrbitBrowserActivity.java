package __PACKAGE__;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import androidx.webkit.WebViewFeature;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class OrbitBrowserActivity extends Activity {
    private android.content.SharedPreferences prefs;
    private final List<String> profileNames = new ArrayList<>();
    private String activeProfile = "Account 1";
    private WebView webView;
    private LinearLayout root, tabs;
    private EditText address;
    private TextView status;
    private FrameLayout webHost;
    private boolean profileSupported;
    // ---- first-chat detection (URL only; page contents are never read) ----
    static final Object LOCK = new Object();
    private static final Pattern CHAT_URL = Pattern.compile("^https://claude\\.ai/chat/([0-9a-fA-F-]{36})");
    private static final Pattern NEW_CHAT_PAGE = Pattern.compile("^https://claude\\.ai(/new|/|/project/[^/?#]+)?/?([?#].*)?$");
    private String lastUrl = null;
    private boolean autoStart = true; // Alias Manager setting "Orbit auto-start timer"
    // ---- profile identity: a profile's WebView data key never changes, even when it is renamed ----
    private JSONObject keys = new JSONObject();    // displayName -> webview profile key (absent = legacy key from name)
    private JSONObject styles = new JSONObject();  // displayName -> {emoji, color}
    private static final int PURPLE = Color.rgb(92, 69, 158);
    private static final String[] COLOR_NAMES = {"Purple", "Blue", "Green", "Orange", "Red", "Pink", "Teal", "Gray"};
    private static final int[] COLOR_VALUES = {Color.rgb(92,69,158), Color.rgb(46,108,200), Color.rgb(36,140,90), Color.rgb(200,120,30),
            Color.rgb(190,60,60), Color.rgb(190,70,140), Color.rgb(30,140,150), Color.rgb(100,108,125)};
    private static final String[] EMOJIS = {"", "🟣", "🔵", "🟢", "🟠", "🔴", "⭐", "💼", "🏠", "🎮", "🧪"};
    // ---- toolbar timer: aliasId -> start time (ms). Same 5h rule as Alias Manager. ----
    private static final long FIVE_H = 5L * 60 * 60 * 1000;
    private final Map<String, Long> started = new HashMap<>();
    private String statusSuffix = "";
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { @Override public void run() { refreshStatus(); ticker.postDelayed(this, 30000); } };
    // Aliases handed over by Alias Manager: {id, email}. Links are stored as profileName -> aliasId.
    private final List<String[]> aliases = new ArrayList<>();
    private JSONObject links = new JSONObject();
    // ---- Gmail view (own isolated profile, kept alive while you switch back to Claude) ----
    private static final String MAIL_KEY = "orbit_mail_inbox";
    private static final String MAIL_URL = "https://mail.google.com/";
    private static final Pattern LOGIN_URL = Pattern.compile("^https://claude\\.ai/(login|signup)");
    private WebView mailView;
    private boolean mailMode = false;
    private TextView mailBtn;
    private LinearLayout addressRow;
    private HorizontalScrollView tabScroll;
    private boolean compact = false;      // true = address bar + status line hidden (more room for the chat)
    private String aliasDebug = "";
    private static final Pattern LOGGED_IN_URL = Pattern.compile("^https://claude\\.ai/(new|chat/|recents|projects|settings|chats|code|artifacts)");
    private final Set<String> promptedLogin = new LinkedHashSet<>();
    private final Set<String> promptedChats = new LinkedHashSet<>();
    private final Set<String> loginSent = new LinkedHashSet<>();
    private String[] pendingChat = null; // {profile, chatId}: chat seen on a profile with no alias yet
    private String[] pendingLink = null; // alias (id,email) Alias Manager asked for, with no profile linked yet
    private long lastFillAt = 0;
    private final Set<String> loginHinted = new LinkedHashSet<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 15, 24));
        getWindow().setNavigationBarColor(Color.rgb(12, 15, 24));
        prefs = getSharedPreferences("orbit_profiles", Context.MODE_PRIVATE);
        profileSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        compact = prefs.getBoolean("compact", false);
        autoStart = getIntent().getBooleanExtra("autoStart", true); loadAliases(); loadProfiles(); loadLinks(); loadMeta(); applyRequestedAlias(); if (profileSupported) erasePendingNow(); buildUi();
        if (profileSupported) showProfile(activeProfile); else showUnsupported();
        if (pendingLink != null) offerLink();
    }
    private void loadProfiles() {
        String saved = prefs.getString("profiles", "Account 1");
        Set<String> unique = new LinkedHashSet<>();
        for (String s : saved.split("\\n")) if (!s.trim().isEmpty()) unique.add(s.trim());
        profileNames.clear(); profileNames.addAll(unique);
        if (profileNames.isEmpty()) profileNames.add("Account 1");
        activeProfile = prefs.getString("active", profileNames.get(0));
        if (!profileNames.contains(activeProfile)) activeProfile = profileNames.get(0);
    }
    private void loadAliases() {
        aliases.clear();
        int fromIntent = parseAliases(getIntent().getStringExtra("aliases"), aliases);
        int fromSaved = 0;
        if (fromIntent == 0) { // fall back to the copy Alias Manager keeps in sync
            fromSaved = parseAliases(prefs.getString("aliases_json", null), aliases);
        }
        aliasDebug = "(from open: " + fromIntent + ", saved copy: " + fromSaved + ")";
    }
    /** Adds valid {id,email} entries to out and returns how many. A missing id falls back to the email. */
    private int parseAliases(String raw, List<String[]> out) {
        int n = 0;
        if (raw == null) return 0;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String email = o.optString("email", "").trim();
                String id = o.optString("id", "").trim();
                if (email.isEmpty()) continue;
                if (id.isEmpty()) id = email;
                out.add(new String[]{id, email, o.optString("note", "").trim()}); n++;
                long lu = o.optLong("lastUsed", 0);
                if (lu > 0) { Long old = started.get(id); if (old == null || lu > old) started.put(id, lu); }
            }
        } catch (Exception ignored) { }
        return n;
    }
    /** Re-read the saved alias list (Alias Manager updates it every time something changes). */
    private void reloadAliases() {
        List<String[]> fresh = new ArrayList<>();
        if (parseAliases(prefs.getString("aliases_json", null), fresh) > 0) { aliases.clear(); aliases.addAll(fresh); }
    }
    /** If Alias Manager asked for a specific alias, start on the profile linked to it. */
    private void applyRequestedAlias() {
        String want = getIntent().getStringExtra("aliasId");
        if (want == null || want.isEmpty()) return;
        String p = profileLinkedTo(want);
        if (p != null && profileNames.contains(p)) { activeProfile = p; saveProfiles(); return; }
        String email = "this alias";
        for (String[] a : aliases) if (a[0].equals(want)) email = a[1];
        pendingLink = new String[]{want, email}; // asked after the screen is built (offerLink)
    }
    /** Alias Manager opened Orbit for an alias that has no profile yet: offer to link it to the profile on screen. */
    private void offerLink() {
        final String[] pl = pendingLink; pendingLink = null;
        if (pl == null || isFinishing()) return;
        final String profile = activeProfile;
        new AlertDialog.Builder(this).setTitle("Link " + pl[1] + "?")
            .setMessage("No profile is linked to this alias yet.\n\nLink it to \"" + profile + "\"? (If it is another account, tap Not now, pick that tab, then tap 🔗.)")
            .setPositiveButton("Link", (d, w) -> {
                try { String other = profileLinkedTo(pl[0]); if (other != null) links.remove(other); links.put(profile, pl[0]); saveLinks(); } catch (Exception ignored) { }
                refreshStatus();
                afterLink(profile);
            }).setNegativeButton("Not now", null).show();
    }
    private void manageProfile() {
        final String name = activeProfile;
        String[] items = {compact ? "Show address bar" : "Hide address bar (more room for chat)", "Rename", "Change emoji", "Change color", "Sign out (keep this tab)…", "Delete profile…"};
        new AlertDialog.Builder(this).setTitle(name).setItems(items, (d, which) -> {
            if (which == 0) { compact = !compact; prefs.edit().putBoolean("compact", compact).apply(); applyCompact(); }
            else if (which == 1) askRename(name);
            else if (which == 2) pickEmoji(name);
            else if (which == 3) pickColor(name);
            else if (which == 4) confirmSignOut(name);
            else confirmDelete(name);
        }).setNegativeButton("Close", null).show();
    }
    private void pickEmoji(final String name) {
        String[] labels = new String[EMOJIS.length];
        for (int i = 0; i < EMOJIS.length; i++) labels[i] = EMOJIS[i].isEmpty() ? "— None —" : EMOJIS[i];
        new AlertDialog.Builder(this).setTitle("Emoji for " + name).setItems(labels, (d, w) -> setStyle(name, EMOJIS[w], null)).show();
    }
    private void pickColor(final String name) {
        new AlertDialog.Builder(this).setTitle("Color for " + name).setItems(COLOR_NAMES, (d, w) -> setStyle(name, null, COLOR_VALUES[w])).show();
    }
    private void askRename(final String old) {
        final EditText in = new EditText(this); in.setSingleLine(true); in.setText(old); in.setSelection(old.length());
        new AlertDialog.Builder(this).setTitle("Rename profile").setView(in)
            .setPositiveButton("Save", (d, w) -> renameProfile(old, in.getText().toString()))
            .setNegativeButton("Cancel", null).show();
    }
    /** Renaming only changes the label; the WebView data key stays the same, so the login is kept. */
    private void renameProfile(String old, String raw) {
        String clean = raw.replace("\n", " ").trim();
        if (clean.isEmpty() || clean.length() > 24) { showMessage("Use 1 to 24 characters."); return; }
        if (clean.equals(old)) return;
        for (String n : profileNames) if (n.equalsIgnoreCase(clean)) { showMessage("A profile named \"" + n + "\" already exists."); return; }
        try {
            String k = keyOf(old);
            profileNames.set(profileNames.indexOf(old), clean);
            keys.remove(old); keys.put(clean, k);
            String l = links.optString(old, ""); links.remove(old); if (!l.isEmpty()) links.put(clean, l);
            JSONObject st = styles.optJSONObject(old); styles.remove(old); if (st != null) styles.put(clean, st);
            JSONObject seen = new JSONObject(prefs.getString("seen", "{}"));
            JSONArray ids = seen.optJSONArray(old); seen.remove(old); if (ids != null) seen.put(clean, ids);
            prefs.edit().putString("seen", seen.toString()).apply();
            if (activeProfile.equals(old)) activeProfile = clean;
            saveProfiles(); saveLinks(); saveMeta(); renderTabs(); refreshStatus();
        } catch (Exception e) { showMessage("Could not rename: " + e.getMessage()); }
    }
    // ---- Sign out: clears this profile's cookies + site data, but keeps the tab, its alias link and its style ----
    private void confirmSignOut(final String name) {
        if (!profileSupported) { showMessage("Profiles can't be managed until Android System WebView is updated."); return; }
        new AlertDialog.Builder(this).setTitle("Sign out \"" + name + "\"?")
            .setMessage("Erases the login (cookies and site data) of this profile on this phone. The tab, its alias link and its color stay. Your Claude account and chats are NOT deleted.")
            .setPositiveButton("Sign out", (d, w) -> signOut(name))
            .setNegativeButton("Cancel", null).show();
    }
    private void signOut(String name) {
        boolean ok = false;
        try {
            Object prof = ProfileStore.getInstance().getProfile(keyOf(name));
            if (prof != null) {
                // Looked up by reflection on the public androidx.webkit.Profile interface (signature not checked at build time).
                Class<?> pc = Class.forName("androidx.webkit.Profile");
                android.webkit.CookieManager cm = (android.webkit.CookieManager) pc.getMethod("getCookieManager").invoke(prof);
                android.webkit.WebStorage ws = (android.webkit.WebStorage) pc.getMethod("getWebStorage").invoke(prof);
                cm.removeAllCookies(null); cm.flush(); ws.deleteAllData();
                ok = true;
            }
        } catch (Throwable ignored) { }
        if (!ok) { showMessage("Could not sign out this profile (this WebView version does not allow it). Use Delete profile instead."); return; }
        String aliasId = links.optString(name, "");
        loginSent.remove(name + "|" + aliasId); promptedLogin.remove(name); loginHinted.remove(name);
        if (!aliasId.isEmpty()) {
            try { queueEvent(new JSONObject().put("type", "logout").put("aliasId", aliasId).put("profile", name).put("ts", System.currentTimeMillis())); } catch (Exception ignored) { }
        }
        if (name.equals(activeProfile) && webView != null) {
            webView.clearCache(true); webView.clearHistory(); lastUrl = null;
            address.setText("https://claude.ai/login"); webView.loadUrl("https://claude.ai/login");
        }
        Toast.makeText(this, "Signed out: " + name, Toast.LENGTH_SHORT).show();
    }
    // ---- Limit detection (user-requested). Reads the visible text of the Claude page you are looking at, ONLY to find a
    // "you've reached your limit" message and its reset time. Only the matched line (max 300 chars) leaves the page; nothing is stored. ----
    private static final String LIMIT_JS = "(function(){try{var t=(document.body&&document.body.innerText)||'';"
        + "var m=t.match(/[^\\n]{0,80}(you(?:'|’)?ve (?:reached|hit)[^\\n]{0,60}limit|you(?:'|’)?re (?:out of|over)[^\\n]{0,30}(?:messages|usage)|usage limit reached|your limit (?:will )?resets?)[^\\n]*(?:\\n[^\\n]*){0,2}/i);"
        + "return m?m[0].slice(0,300):'';}catch(x){return '';}})()";
    private static final Pattern RESET_REL = Pattern.compile("(?i)\\bin\\s+(?:(\\d+)\\s*(?:hours?|hrs?|h)\\b)?\\s*(?:and\\s*)?(?:(\\d+)\\s*(?:minutes?|mins?|m)\\b)?");
    private static final Pattern RESET_CLOCK = Pattern.compile("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b");
    private final Map<String, long[]> limitSeen = new HashMap<>(); // aliasId -> {key, when}
    private final Runnable limitTick = new Runnable() { @Override public void run() { checkLimit(); ticker.postDelayed(this, 10000); } };
    private void checkLimit() {
        if (!autoStart || mailMode || webView == null || isFinishing()) return;
        final WebView v = webView;
        String u = v.getUrl();
        if (u == null || !u.startsWith("https://claude.ai/")) return;
        final String profile = activeProfile;
        final String aliasId = links.optString(profile, "");
        if (aliasId.isEmpty() || linkedEmail(profile) == null) return;
        v.evaluateJavascript(LIMIT_JS, value -> {
            if (v != webView || value == null) return;
            String text = "";
            try { text = new JSONArray("[" + value + "]").optString(0, ""); } catch (Exception ignored) { }
            if (!text.isEmpty()) onLimitSeen(profile, aliasId, text);
        });
    }
    /** Best effort: "resets in 2 hours 10 minutes" or "resets at 3:30 PM". 0 = unknown (or not within the next 5h). */
    private long parseReset(String text, long now) {
        try { return parseResetUnsafe(text, now); } catch (Exception e) { return 0; } // e.g. absurdly long numbers in the page text
    }
    private long parseResetUnsafe(String text, long now) {
        long at = 0;
        Matcher r = RESET_REL.matcher(text);
        while (r.find()) {
            if (r.group(1) == null && r.group(2) == null) continue;
            long h = r.group(1) == null ? 0 : Long.parseLong(r.group(1)), mi = r.group(2) == null ? 0 : Long.parseLong(r.group(2));
            at = now + (h * 60 + mi) * 60000L; break;
        }
        if (at == 0) {
            Matcher c = RESET_CLOCK.matcher(text);
            if (c.find()) {
                int hh = Integer.parseInt(c.group(1)) % 12, mm = c.group(2) == null ? 0 : Integer.parseInt(c.group(2));
                if (c.group(3).equalsIgnoreCase("pm")) hh += 12;
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.set(java.util.Calendar.HOUR_OF_DAY, hh); cal.set(java.util.Calendar.MINUTE, mm); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= now) cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                at = cal.getTimeInMillis();
            }
        }
        if (at <= now || at - now > FIVE_H + 30 * 60000L) return 0;
        return at;
    }
    private void onLimitSeen(String profile, String aliasId, String text) {
        long now = System.currentTimeMillis();
        long resetAt = parseReset(text, now);
        long key = resetAt > 0 ? resetAt / 600000L : -1; // same reset time (10-minute buckets) = same limit message
        long[] prev = limitSeen.get(aliasId);
        if (prev != null && prev[0] == key && now - prev[1] < 30 * 60000L) return;
        limitSeen.put(aliasId, new long[]{key, now});
        String email = linkedEmail(profile);
        if (email == null) return;
        Long st = started.get(aliasId);
        boolean running = st != null && now - st < FIVE_H;
        long readyAt;
        if (resetAt > 0) { started.put(aliasId, resetAt - FIVE_H); readyAt = resetAt; }
        else if (running) { readyAt = st + FIVE_H; }
        else { started.put(aliasId, now); readyAt = now + FIVE_H; }
        OrbitReminderReceiver.add(this, aliasId, email, readyAt);
        try { queueEvent(new JSONObject().put("type", "limit").put("aliasId", aliasId).put("profile", profile).put("resetAt", resetAt).put("ts", now)); } catch (Exception ignored) { }
        refreshStatus();
        java.text.DateFormat df = android.text.format.DateFormat.getTimeFormat(this);
        Toast.makeText(this, "Limit reached: " + email + (resetAt > 0 ? " • resets " + df.format(new java.util.Date(resetAt)) : ""), Toast.LENGTH_LONG).show();
    }
    private void confirmDelete(final String victim) {
        if (!profileSupported) { showMessage("Profiles can't be managed until Android System WebView is updated."); return; }
        if (profileNames.size() <= 1) { showMessage("You need at least one profile. Add another one first."); return; }
        new AlertDialog.Builder(this).setTitle("Delete \"" + victim + "\"?")
            .setMessage("This signs out of this profile and erases its cookies and site data on this phone.\n\nYour Claude account and chats are NOT deleted. The alias link is removed. This cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> deleteProfile(victim))
            .setNegativeButton("Cancel", null).show();
    }
    private void deleteProfile(String victim) {
        String key = keyOf(victim);
        int i = profileNames.indexOf(victim);
        if (i < 0 || profileNames.size() <= 1) return;
        String next = profileNames.get(i == 0 ? 1 : i - 1);
        if (victim.equals(activeProfile)) { activeProfile = next; saveProfiles(); renderTabs(); showProfile(next); } // destroys the old WebView first
        // Remove it from Orbit right away. Android may still count the profile as "in use" for a moment,
        // so the erase of its cookies/site data is retried, and finished at the next start if needed.
        try {
            profileNames.remove(victim); keys.remove(victim); styles.remove(victim); links.remove(victim);
            JSONObject seen = new JSONObject(prefs.getString("seen", "{}")); seen.remove(victim);
            prefs.edit().putString("seen", seen.toString()).apply();
        } catch (Exception ignored) { }
        saveProfiles(); saveLinks(); saveMeta(); renderTabs(); refreshStatus();
        Set<String> pend = pendingErase(); pend.add(key); savePendingErase(pend);
        eraseAttempt(0);
    }
    private Set<String> pendingErase() {
        Set<String> out = new LinkedHashSet<>();
        for (String k : prefs.getString("pending_erase", "").split("\\n")) if (!k.trim().isEmpty()) out.add(k.trim());
        return out;
    }
    private void savePendingErase(Set<String> set) { prefs.edit().putString("pending_erase", android.text.TextUtils.join("\n", set)).commit(); }
    /** @return true when nothing is left to erase. */
    private boolean erasePendingNow() {
        Set<String> pend = pendingErase();
        if (pend.isEmpty()) return true;
        try {
            ProfileStore store = ProfileStore.getInstance();
            for (String k : new ArrayList<>(pend)) {
                try { if (store.deleteProfile(k) || store.getProfile(k) == null) pend.remove(k); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        savePendingErase(pend);
        return pend.isEmpty();
    }
    private void eraseAttempt(final int attempt) {
        if (erasePendingNow()) { Toast.makeText(this, "Profile deleted and data erased", Toast.LENGTH_SHORT).show(); return; }
        if (attempt < 6) ticker.postDelayed(() -> eraseAttempt(attempt + 1), 800);
        else Toast.makeText(this, "Profile removed. Its data is erased the next time you open Orbit.", Toast.LENGTH_LONG).show();
    }
    private void loadMeta() {
        try { keys = new JSONObject(prefs.getString("keys", "{}")); } catch (Exception e) { keys = new JSONObject(); }
        try { styles = new JSONObject(prefs.getString("styles", "{}")); } catch (Exception e) { styles = new JSONObject(); }
    }
    private void saveMeta() { prefs.edit().putString("keys", keys.toString()).putString("styles", styles.toString()).apply(); }
    private String keyOf(String name) { String k = keys.optString(name, ""); return k.isEmpty() ? profileKey(name) : k; }
    private String nameOfKey(String key) { for (String n : profileNames) if (keyOf(n).equals(key)) return n; return null; }
    private int colorOf(String name) { JSONObject st = styles.optJSONObject(name); return st == null ? PURPLE : st.optInt("color", PURPLE); }
    /** Tab text: your own name if you renamed the profile; otherwise the linked alias (note or short email), else "Account N". */
    private String tabLabel(String name) {
        if (!name.matches("Account \\d+")) return name;
        String id = links.optString(name, "");
        if (id.isEmpty()) return name;
        for (String[] a : aliases) if (a[0].equals(id)) {
            String t = a.length > 2 && !a[2].isEmpty() ? a[2] : a[1].contains("@") ? a[1].substring(0, a[1].indexOf('@')) : a[1];
            return t.length() > 14 ? t.substring(0, 13) + "…" : t;
        }
        return name;
    }
    private String emojiOf(String name) { JSONObject st = styles.optJSONObject(name); return st == null ? "" : st.optString("emoji", ""); }
    private void setStyle(String name, String emoji, Integer color) {
        try {
            JSONObject st = styles.optJSONObject(name); if (st == null) st = new JSONObject();
            if (emoji != null) st.put("emoji", emoji);
            if (color != null) st.put("color", color.intValue());
            styles.put(name, st); saveMeta(); renderTabs();
        } catch (Exception ignored) { }
    }
    private void loadLinks() {
        try { links = new JSONObject(prefs.getString("links", "{}")); } catch (Exception e) { links = new JSONObject(); }
    }
    private void saveLinks() { prefs.edit().putString("links", links.toString()).apply(); }
    private String linkedEmail(String profile) {
        String id = links.optString(profile, "");
        if (id.isEmpty()) return null;
        for (String[] a : aliases) if (a[0].equals(id)) return a[1];
        return null; // alias was deleted in Alias Manager
    }
    private String timerText(String profile) {
        String id = links.optString(profile, "");
        if (id.isEmpty() || linkedEmail(profile) == null) return "";
        Long st = started.get(id);
        if (st == null) return " • Not started";
        long left = FIVE_H - (System.currentTimeMillis() - st);
        if (left <= 0) return " • ✓ Ready";
        long mins = (left + 59999) / 60000;
        return " • ⏱ " + (mins / 60) + "h " + (mins % 60) + "m left";
    }
    private String who() {
        String e = linkedEmail(activeProfile);
        if (mailMode) return "✉ Gmail • claude.ai links open in " + activeProfile + (e != null ? " → " + e : "");
        return "Profile: " + activeProfile + (e != null ? " → " + e : " (no alias linked - tap 🔗)") + timerText(activeProfile) + (autoStart ? "" : " • auto-start OFF");
    }
    private void refreshStatus() {
        if (tabs != null && tabs.getChildCount() > 0 && !tabsSignature().equals(tabsSig)) renderTabs(); // labels changed (alias linked / renamed in Alias Manager)
        if (status != null) status.setText(who() + (statusSuffix.isEmpty() ? "" : " • " + statusSuffix));
    }
    private void goBack() {
        if (mailMode) { if (mailView != null && mailView.canGoBack()) mailView.goBack(); else exitMail(); return; }
        if (webView != null && webView.canGoBack()) webView.goBack();
    }
    @Override public void onBackPressed() {
        if (mailMode) { goBack(); return; }
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); reloadAliases(); refreshStatus(); ticker.removeCallbacks(tick); ticker.post(tick); ticker.removeCallbacks(limitTick); ticker.postDelayed(limitTick, 6000); }
    @Override protected void onPause() { ticker.removeCallbacks(tick); ticker.removeCallbacks(limitTick); super.onPause(); }
    private void linkAlias() {
        reloadAliases();
        if (aliases.isEmpty()) { showMessage("No aliases found. Add an email in Alias Manager first, then open Orbit Browser again.\n\n" + aliasDebug); return; }
        final String profile = activeProfile;
        final String[] items = new String[aliases.size() + 1];
        items[0] = "— No alias —";
        for (int i = 0; i < aliases.size(); i++) {
            String id = aliases.get(i)[0], email = aliases.get(i)[1];
            String other = profileLinkedTo(id);
            items[i + 1] = email + (other != null && !other.equals(profile) ? "  (now: " + other + ")" : "");
        }
        new AlertDialog.Builder(this).setTitle("Alias for " + profile).setItems(items, (d, which) -> {
            try {
                if (which == 0) { links.remove(profile); }
                else {
                    String id = aliases.get(which - 1)[0];
                    String other = profileLinkedTo(id);
                    if (other != null) links.remove(other); // one alias <-> one profile
                    links.put(profile, id);
                }
                saveLinks();
            } catch (Exception ignored) { }
            refreshStatus();
            if (which > 0) afterLink(profile);
        }).setNegativeButton("Cancel", null).show();
    }
    /** Right after a profile gets an alias: fill the login email, tell Alias Manager it is logged in, start a waiting chat. */
    private void afterLink(String profile) {
        if (webView == null) return;
        String u = webView.getUrl();
        maybeFillEmail(webView, u, profile);
        if (u != null && LOGGED_IN_URL.matcher(u).find()) onLoggedInPage(profile);
        if (pendingChat != null && pendingChat[0].equals(profile)) { String[] pc = pendingChat; pendingChat = null; handleNewChat(profile, pc[1]); }
    }
    private void queueEvent(JSONObject ev) {
        try {
            synchronized (LOCK) {
                JSONArray q = new JSONArray(prefs.getString("events", "[]"));
                q.put(ev);
                prefs.edit().putString("events", q.toString()).commit();
            }
        } catch (Exception ignored) { }
    }
    /** A logged-in claude.ai page is showing in this profile. Linked -> tell Alias Manager; not linked -> ask which alias (once). */
    private void onLoggedInPage(String forProfile) {
        String aliasId = links.optString(forProfile, "");
        if (aliasId.isEmpty() || linkedEmail(forProfile) == null) {
            if (!aliases.isEmpty() && !isFinishing() && !mailMode && forProfile.equals(activeProfile) && promptedLogin.add(forProfile)) {
                Toast.makeText(this, forProfile + " is logged in. Pick its alias.", Toast.LENGTH_LONG).show();
                linkAlias();
            }
            return;
        }
        if (!loginSent.add(forProfile + "|" + aliasId)) return;
        try { queueEvent(new JSONObject().put("type", "login").put("aliasId", aliasId).put("profile", forProfile).put("ts", System.currentTimeMillis())); } catch (Exception ignored) { }
    }
    private String profileLinkedTo(String aliasId) {
        java.util.Iterator<String> it = links.keys();
        while (it.hasNext()) { String p = it.next(); if (aliasId.equals(links.optString(p, ""))) return p; }
        return null;
    }
    /** Called for every URL change (incl. single-page navigation). Fires only for /new -> /chat/<id>. */
    private void onUrlChanged(String forProfile, String url) {
        if (url == null || url.equals(lastUrl)) return;
        String prev = lastUrl; lastUrl = url;
        if (LOGGED_IN_URL.matcher(url).find()) onLoggedInPage(forProfile);
        Matcher m = CHAT_URL.matcher(url);
        if (!m.find() || prev == null || !NEW_CHAT_PAGE.matcher(prev).find()) return;
        String chatId = m.group(1).toLowerCase();
        if (isSeen(forProfile, chatId)) return; // already counted
        if (autoStart && (links.optString(forProfile, "").isEmpty() || linkedEmail(forProfile) == null)) {
            // A chat was started but this profile has no alias yet: ask now, and start the timer right after.
            pendingChat = new String[]{forProfile, chatId};
            if (!aliases.isEmpty() && !isFinishing() && !mailMode && forProfile.equals(activeProfile) && promptedChats.add(forProfile + "|" + chatId)) {
                Toast.makeText(this, "Chat detected. Pick the alias for " + forProfile + " to start its timer.", Toast.LENGTH_LONG).show();
                linkAlias();
            }
            return;
        }
        handleNewChat(forProfile, chatId);
    }
    private void handleNewChat(String forProfile, String chatId) {
        if (!markSeen(forProfile, chatId)) return;
        if (!autoStart) return; // chat is remembered, but no timer is started while auto-start is off
        String aliasId = links.optString(forProfile, "");
        String email = linkedEmail(forProfile);
        if (aliasId.isEmpty() || email == null) return;
        try {
            queueEvent(new JSONObject().put("aliasId", aliasId).put("profile", forProfile).put("chatId", chatId).put("ts", System.currentTimeMillis()));
            Long st = started.get(aliasId);
            long now = System.currentTimeMillis();
            if (st == null || now - st >= FIVE_H) { started.put(aliasId, now); OrbitReminderReceiver.add(this, aliasId, email, now + FIVE_H); } // first chat of a new 5h window
            refreshStatus();
            Toast.makeText(this, "Chat detected: " + email, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }
    private boolean isSeen(String profile, String chatId) {
        try {
            JSONArray ids = new JSONObject(prefs.getString("seen", "{}")).optJSONArray(profile);
            if (ids != null) for (int i = 0; i < ids.length(); i++) if (chatId.equals(ids.optString(i))) return true;
        } catch (Exception ignored) { }
        return false;
    }
    /** @return true if chatId is new for this profile (and remembers it). Keeps the last 300 ids. */
    private boolean markSeen(String profile, String chatId) {
        try {
            JSONObject all = new JSONObject(prefs.getString("seen", "{}"));
            JSONArray ids = all.optJSONArray(profile);
            if (ids == null) ids = new JSONArray();
            for (int i = 0; i < ids.length(); i++) if (chatId.equals(ids.optString(i))) return false;
            ids.put(chatId);
            if (ids.length() > 300) { JSONArray t = new JSONArray(); for (int i = ids.length() - 300; i < ids.length(); i++) t.put(ids.optString(i)); ids = t; }
            all.put(profile, ids);
            prefs.edit().putString("seen", all.toString()).apply();
            return true;
        } catch (Exception e) { return false; }
    }
    private void saveProfiles() {
        prefs.edit().putString("profiles", android.text.TextUtils.join("\n", profileNames)).putString("active", activeProfile).apply();
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private TextView label(String text, float size) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(Color.rgb(235,238,248)); t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private TextView button(String text, Runnable action) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(13); t.setGravity(Gravity.CENTER); t.setTextColor(Color.WHITE);
        t.setPadding(dp(10),dp(6),dp(10),dp(6)); t.setBackgroundColor(Color.rgb(39,45,64)); t.setClickable(true); t.setFocusable(true); t.setOnClickListener(v -> action.run()); return t;
    }
    private TextView iconButton(String text, Runnable action) {
        TextView t = button(text, action); t.setTextSize(16); t.setPadding(0,0,0,0); return t;
    }
    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(12,15,24));
        // Keep the toolbar clear of the status bar / navigation bar / keyboard (Android 15 draws edge-to-edge).
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop(); r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        // Row 1: account tabs (scroll) + small icon buttons.   🔗 alias   ✉ Gmail   ＋ account   ⋯ manage   ✕ close
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setBackgroundColor(Color.rgb(19,23,36));
        tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this); tabs.setGravity(Gravity.CENTER_VERTICAL); tabs.setPadding(dp(4),dp(4),dp(4),dp(4)); tabScroll.addView(tabs);
        top.addView(tabScroll, new LinearLayout.LayoutParams(0,dp(40),1));
        mailBtn = iconButton("✉", this::toggleMail);
        TextView[] icons = { iconButton("🔗", this::linkAlias), mailBtn, iconButton("＋", this::addProfile), iconButton("⋯", this::manageProfile), iconButton("✕", this::finish) };
        for (TextView ic : icons) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36),dp(40)); lp.setMargins(dp(1),0,0,0); top.addView(ic, lp); }
        root.addView(top);
        // Row 2: back / address / go / reload (can be hidden from ⋯)
        addressRow = new LinearLayout(this); addressRow.setGravity(Gravity.CENTER_VERTICAL); addressRow.setPadding(dp(6),dp(3),dp(6),dp(3)); addressRow.setBackgroundColor(Color.rgb(16,20,31));
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(13); address.setTextColor(Color.WHITE); address.setHintTextColor(Color.LTGRAY); address.setHint("https://claude.ai/"); address.setText("https://claude.ai/"); address.setMinHeight(0); address.setMinimumHeight(0); address.setPadding(dp(10),0,dp(10),0); address.setBackgroundColor(Color.rgb(25,29,43)); address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v,id,event)-> { if (id == EditorInfo.IME_ACTION_GO) { navigate(address.getText().toString()); return true; } return false; });
        addressRow.addView(button("◀",this::goBack),new LinearLayout.LayoutParams(dp(38),dp(34))); LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0,dp(34),1); alp.setMargins(dp(3),0,dp(3),0); addressRow.addView(address,alp); addressRow.addView(button("Go",()->navigate(address.getText().toString())),new LinearLayout.LayoutParams(dp(44),dp(34))); LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(38),dp(34)); rlp.setMargins(dp(3),0,0,0); addressRow.addView(button("↻",()->{ WebView c=cur(); if(c!=null)c.reload(); }),rlp); root.addView(addressRow);
        // Row 3: one small status line
        status = label(who(),10.5f); status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setPadding(dp(10),dp(2),dp(10),dp(3)); status.setTextColor(Color.rgb(155,165,188)); status.setBackgroundColor(Color.rgb(16,20,31)); root.addView(status);
        webHost = new FrameLayout(this); webHost.setBackgroundColor(Color.WHITE); root.addView(webHost,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root); renderTabs(); applyCompact(); root.requestApplyInsets();
    }
    private void applyCompact() {
        int vis = compact ? View.GONE : View.VISIBLE;
        if (addressRow != null) addressRow.setVisibility(vis);
        if (status != null) status.setVisibility(vis);
    }
    private WebView cur() { return mailMode && mailView != null ? mailView : webView; }
    private String tabsSig = "";
    private String tabsSignature() {
        StringBuilder sb = new StringBuilder(activeProfile);
        for (String n : profileNames) sb.append('|').append(n).append('=').append(tabLabel(n)).append(colorOf(n)).append(emojiOf(n));
        return sb.toString();
    }
    private void renderTabs() {
        tabsSig = tabsSignature();
        tabs.removeAllViews();
        TextView activeTab = null;
        for (String name : profileNames) { final String selectedName=name; boolean on=name.equals(activeProfile); String em=emojiOf(name);
            TextView b=button("",()->switchProfile(selectedName)); b.setTextSize(12);
            SpannableString sp=new SpannableString("●  "+(em.isEmpty()?"":em+" ")+tabLabel(name)); sp.setSpan(new ForegroundColorSpan(on?Color.WHITE:colorOf(name)),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); b.setText(sp);
            b.setBackgroundColor(on?colorOf(name):Color.rgb(31,36,52)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(32)); lp.setMargins(dp(2),0,dp(2),0); tabs.addView(b,lp); if(on) activeTab=b; }
        final TextView at = activeTab;
        if (at != null && tabScroll != null) tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, at.getLeft() - dp(8)), 0));
    }
    // ---- Gmail: see verification emails inside the app. claude.ai links tapped there open in the ACTIVE Claude profile. ----
    private void toggleMail() {
        if (mailMode) { exitMail(); return; }
        if (!profileSupported) { showMessage("Gmail view needs an updated Android System WebView (isolated profiles)."); return; }
        try {
            if (mailView == null) createMailView();
            mailMode = true;
            if (webView != null) webView.setVisibility(View.GONE);
            mailView.setVisibility(View.VISIBLE);
            mailBtn.setBackgroundColor(PURPLE);
            String u = mailView.getUrl(); address.setText(u == null ? MAIL_URL : u);
            refreshStatus();
        } catch (Exception e) { mailMode = false; showMessage("Could not open Gmail: " + e.getMessage()); }
    }
    private void exitMail() {
        mailMode = false;
        if (mailView != null) mailView.setVisibility(View.GONE);
        if (webView != null) { webView.setVisibility(View.VISIBLE); String u = webView.getUrl(); if (u != null) address.setText(u); }
        if (mailBtn != null) mailBtn.setBackgroundColor(Color.rgb(39,45,64));
        refreshStatus();
    }
    private void createMailView() {
        WebView view = new WebView(this);
        WebViewCompat.setProfile(view, MAIL_KEY);
        view.getSettings().setJavaScriptEnabled(true); view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setJavaScriptCanOpenWindowsAutomatically(false); view.getSettings().setSupportMultipleWindows(false);
        try { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, true); } catch (Exception ignored) { }
        view.setWebChromeClient(new WebChromeClient());
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl(); String scheme = u.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) return true;
                if (!req.isForMainFrame()) return false;
                Uri target = unwrapRedirect(u);
                String host = target.getHost();
                if (host != null && (host.equals("claude.ai") || host.endsWith(".claude.ai"))) { openInClaude(target.toString()); return true; }
                return false;
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) { super.onPageStarted(v, url, icon); if (mailMode && url != null) address.setText(url); }
        });
        webHost.addView(view, new FrameLayout.LayoutParams(-1,-1));
        mailView = view;
        view.loadUrl(MAIL_URL);
    }
    /** Gmail wraps links as google.com/url?q=<real link>. Return the real link. */
    private Uri unwrapRedirect(Uri u) {
        try {
            String h = u.getHost();
            if (h != null && h.endsWith("google.com") && "/url".equals(u.getPath())) {
                String q = u.getQueryParameter("q"); if (q == null) q = u.getQueryParameter("url");
                if (q != null && q.startsWith("http")) return Uri.parse(q);
            }
        } catch (Exception ignored) { }
        return u;
    }
    private void openInClaude(String url) {
        exitMail();
        if (webView == null) return;
        String e = linkedEmail(activeProfile);
        address.setText(url); webView.loadUrl(url);
        Toast.makeText(this, "Opening in " + activeProfile + (e != null ? " → " + e : ""), Toast.LENGTH_SHORT).show();
    }
    /** On claude.ai/login, put this profile's linked alias in the email box (you still tap Continue yourself). */
    private void maybeFillEmail(final WebView v, String url, String forProfile) {
        if (url == null || forProfile == null || !LOGIN_URL.matcher(url).find()) return;
        String email = linkedEmail(forProfile);
        if (email == null) {
            if (!aliases.isEmpty() && !isFinishing() && loginHinted.add(forProfile) && forProfile.equals(activeProfile)) linkAlias(); // pick the alias to sign in with
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFillAt < 1500) return;
        lastFillAt = now;
        final String js = "(function(){try{var e=" + JSONObject.quote(email) + ";var i=document.querySelector('input[type=\"email\"],input[name=\"email\"],input[autocomplete=\"email\"]');if(!i||i.value)return 0;var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(i,e);i.dispatchEvent(new Event('input',{bubbles:true}));i.dispatchEvent(new Event('change',{bubbles:true}));return 1;}catch(x){return 0;}})()";
        int[] delays = {300, 1200, 2500};
        for (int dly : delays) ticker.postDelayed(() -> { if (v == webView) v.evaluateJavascript(js, null); }, dly);
    }
    private void addProfile() { if(mailMode)exitMail(); int i=1; while(profileNames.contains("Account "+i)) i++; String name="Account "+i; profileNames.add(name); activeProfile=name; try{ keys.put(name,"orbit_"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,10)); }catch(Exception ignored){} saveMeta(); saveProfiles(); renderTabs(); if(profileSupported)showProfile(name); }
    private void switchProfile(String name) { if(mailMode)exitMail(); if(name.equals(activeProfile))return; activeProfile=name; saveProfiles(); renderTabs(); if(profileSupported)showProfile(name); }
    private String profileKey(String name) { return "orbit_"+name.toLowerCase().replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$",""); }
    private void showProfile(String name) {
        try {
            if(mailMode) exitMail();
            if(webView!=null){ webHost.removeView(webView); webView.stopLoading(); webView.loadUrl("about:blank"); webView.destroy(); webView=null; }
            lastUrl = null;
            final String profileKeyAtCreate = keyOf(name);
            WebView view=new WebView(this); WebViewCompat.setProfile(view,keyOf(name)); view.getSettings().setJavaScriptEnabled(true); view.getSettings().setDomStorageEnabled(true); view.getSettings().setLoadsImagesAutomatically(true); view.getSettings().setJavaScriptCanOpenWindowsAutomatically(false); view.getSettings().setSupportMultipleWindows(false); view.setWebChromeClient(new WebChromeClient());
            view.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req){ String scheme=req.getUrl().getScheme(); return !("http".equals(scheme)||"https".equals(scheme)); }
                @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload){ super.doUpdateVisitedHistory(v,url,isReload); String who=nameOfKey(profileKeyAtCreate); if(who!=null){ onUrlChanged(who,url); maybeFillEmail(v,url,who); } }
                @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){ super.onPageStarted(v,url,icon); if(url!=null&&!mailMode)address.setText(url); statusSuffix="Loading…"; refreshStatus(); }
                @Override public void onPageFinished(WebView v,String url){ super.onPageFinished(v,url); statusSuffix="Session isolated"; refreshStatus(); maybeFillEmail(v,url,nameOfKey(profileKeyAtCreate)); }
            });
            webView=view; webHost.addView(view,0,new FrameLayout.LayoutParams(-1,-1)); address.setText("https://claude.ai/"); view.loadUrl("https://claude.ai/"); renderTabs();
        } catch(Exception e){ statusSuffix="Unable to create isolated profile"; refreshStatus(); showMessage("Could not open profile. Update Android System WebView and try again.\n\n"+e.getMessage()); }
    }
    private void navigate(String raw) {
        String url=raw.trim(); if(url.isEmpty())return; if(!url.contains("://"))url="https://"+url; Uri uri=Uri.parse(url);
        if(!"https".equals(uri.getScheme())&& !"http".equals(uri.getScheme())){showMessage("Only HTTP and HTTPS pages are supported.");return;}
        address.setText(url); InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE); if(imm!=null)imm.hideSoftInputFromWindow(address.getWindowToken(),0); WebView c=cur(); if(mailMode){ String h=uri.getHost(); if(h!=null&&(h.equals("claude.ai")||h.endsWith(".claude.ai"))){ openInClaude(url); return; } } if(c!=null)c.loadUrl(url);
    }
    private void showUnsupported() {
        webHost.removeAllViews(); TextView msg=new TextView(this); msg.setText("Isolated browser profiles are not supported by the installed Android System WebView.\n\nUpdate Android System WebView from Google Play, then reopen Orbit Browser. No shared-session fallback is used, so accounts won't accidentally share cookies."); msg.setTextSize(17); msg.setTextColor(Color.DKGRAY); msg.setGravity(Gravity.CENTER); msg.setPadding(dp(24),dp(24),dp(24),dp(24)); msg.setBackgroundColor(Color.WHITE); webHost.addView(msg,new FrameLayout.LayoutParams(-1,-1)); statusSuffix="Multi-profile feature unavailable"; refreshStatus();
    }
    private void showMessage(String message){ if(!isFinishing())new AlertDialog.Builder(this).setTitle("Orbit Browser").setMessage(message).setPositiveButton("OK",null).show(); }
    @Override public void onDestroy(){ ticker.removeCallbacks(tick); ticker.removeCallbacks(limitTick); if(mailView!=null){mailView.stopLoading();mailView.loadUrl("about:blank");mailView.destroy();mailView=null;} if(webView!=null){webView.stopLoading();webView.loadUrl("about:blank");webView.destroy();webView=null;} super.onDestroy(); }
}

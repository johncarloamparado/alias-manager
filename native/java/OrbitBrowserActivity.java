package __PACKAGE__;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
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

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 15, 24));
        getWindow().setNavigationBarColor(Color.rgb(12, 15, 24));
        prefs = getSharedPreferences("orbit_profiles", Context.MODE_PRIVATE);
        profileSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        loadProfiles(); buildUi();
        if (profileSupported) showProfile(activeProfile); else showUnsupported();
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
    private void saveProfiles() {
        prefs.edit().putString("profiles", android.text.TextUtils.join("\n", profileNames)).putString("active", activeProfile).apply();
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private TextView label(String text, float size) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(Color.rgb(235,238,248)); t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private TextView button(String text, Runnable action) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(13); t.setGravity(Gravity.CENTER); t.setTextColor(Color.WHITE);
        t.setPadding(dp(12),dp(10),dp(12),dp(10)); t.setBackgroundColor(Color.rgb(39,45,64)); t.setClickable(true); t.setFocusable(true); t.setOnClickListener(v -> action.run()); return t;
    }
    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(12,15,24));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(12),dp(8),dp(12),dp(8)); header.setBackgroundColor(Color.rgb(19,23,36));
        header.addView(label("◈  ORBIT BROWSER",16), new LinearLayout.LayoutParams(0,dp(42),1));
        header.addView(button("＋ Account", this::addProfile)); header.addView(button("✕", this::finish), new LinearLayout.LayoutParams(dp(44),dp(42))); root.addView(header);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); scroll.setBackgroundColor(Color.rgb(16,20,31));
        tabs = new LinearLayout(this); tabs.setPadding(dp(8),dp(8),dp(8),dp(8)); scroll.addView(tabs); root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(54)));
        LinearLayout addressRow = new LinearLayout(this); addressRow.setGravity(Gravity.CENTER_VERTICAL); addressRow.setPadding(dp(10),dp(6),dp(10),dp(6));
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(14); address.setTextColor(Color.WHITE); address.setHintTextColor(Color.LTGRAY); address.setHint("https://claude.ai/"); address.setText("https://claude.ai/"); address.setPadding(dp(12),0,dp(12),0); address.setBackgroundColor(Color.rgb(25,29,43)); address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v,id,event)-> { if (id == EditorInfo.IME_ACTION_GO) { navigate(address.getText().toString()); return true; } return false; });
        addressRow.addView(address,new LinearLayout.LayoutParams(0,dp(44),1)); addressRow.addView(button("Go",()->navigate(address.getText().toString())),new LinearLayout.LayoutParams(dp(56),dp(44))); addressRow.addView(button("↻",()->{if(webView!=null)webView.reload();}),new LinearLayout.LayoutParams(dp(46),dp(44))); root.addView(addressRow);
        status = label("Profile: " + activeProfile,12); status.setPadding(dp(12),dp(5),dp(12),dp(5)); status.setTextColor(Color.rgb(155,165,188)); root.addView(status);
        webHost = new FrameLayout(this); webHost.setBackgroundColor(Color.WHITE); root.addView(webHost,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root); renderTabs();
    }
    private void renderTabs() {
        tabs.removeAllViews();
        for (String name : profileNames) { final String selectedName=name; TextView b=button((name.equals(activeProfile)?"●  ":"○  ")+name,()->switchProfile(selectedName)); b.setBackgroundColor(name.equals(activeProfile)?Color.rgb(92,69,158):Color.rgb(31,36,52)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(38)); lp.setMargins(dp(3),0,dp(3),0); tabs.addView(b,lp); }
    }
    private void addProfile() { int i=1; while(profileNames.contains("Account "+i)) i++; String name="Account "+i; profileNames.add(name); activeProfile=name; saveProfiles(); renderTabs(); if(profileSupported)showProfile(name); }
    private void switchProfile(String name) { if(name.equals(activeProfile))return; activeProfile=name; saveProfiles(); renderTabs(); if(profileSupported)showProfile(name); }
    private String profileKey(String name) { return "orbit_"+name.toLowerCase().replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$",""); }
    private void showProfile(String name) {
        try {
            if(webView!=null){ webHost.removeView(webView); webView.stopLoading(); webView.loadUrl("about:blank"); webView.destroy(); webView=null; }
            WebView view=new WebView(this); WebViewCompat.setProfile(view,profileKey(name)); view.getSettings().setJavaScriptEnabled(true); view.getSettings().setDomStorageEnabled(true); view.getSettings().setLoadsImagesAutomatically(true); view.getSettings().setJavaScriptCanOpenWindowsAutomatically(false); view.getSettings().setSupportMultipleWindows(false); view.setWebChromeClient(new WebChromeClient());
            view.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req){ String scheme=req.getUrl().getScheme(); return !("http".equals(scheme)||"https".equals(scheme)); }
                @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){ super.onPageStarted(v,url,icon); if(url!=null)address.setText(url); status.setText("Profile: "+activeProfile+" • Loading…"); }
                @Override public void onPageFinished(WebView v,String url){ super.onPageFinished(v,url); status.setText("Profile: "+activeProfile+" • Session isolated"); }
            });
            webView=view; webHost.removeAllViews(); webHost.addView(view,new FrameLayout.LayoutParams(-1,-1)); address.setText("https://claude.ai/"); view.loadUrl("https://claude.ai/"); renderTabs();
        } catch(Exception e){ status.setText("Unable to create isolated profile: "+e.getMessage()); showMessage("Could not open profile. Update Android System WebView and try again.\n\n"+e.getMessage()); }
    }
    private void navigate(String raw) {
        String url=raw.trim(); if(url.isEmpty())return; if(!url.contains("://"))url="https://"+url; Uri uri=Uri.parse(url);
        if(!"https".equals(uri.getScheme())&& !"http".equals(uri.getScheme())){showMessage("Only HTTP and HTTPS pages are supported.");return;}
        address.setText(url); InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE); if(imm!=null)imm.hideSoftInputFromWindow(address.getWindowToken(),0); if(webView!=null)webView.loadUrl(url);
    }
    private void showUnsupported() {
        webHost.removeAllViews(); TextView msg=new TextView(this); msg.setText("Isolated browser profiles are not supported by the installed Android System WebView.\n\nUpdate Android System WebView from Google Play, then reopen Orbit Browser. No shared-session fallback is used, so accounts won't accidentally share cookies."); msg.setTextSize(17); msg.setTextColor(Color.DKGRAY); msg.setGravity(Gravity.CENTER); msg.setPadding(dp(24),dp(24),dp(24),dp(24)); msg.setBackgroundColor(Color.WHITE); webHost.addView(msg,new FrameLayout.LayoutParams(-1,-1)); status.setText("Multi-profile feature unavailable");
    }
    private void showMessage(String message){ if(!isFinishing())new AlertDialog.Builder(this).setTitle("Orbit Browser").setMessage(message).setPositiveButton("OK",null).show(); }
    @Override public void onDestroy(){ if(webView!=null){webView.stopLoading();webView.loadUrl("about:blank");webView.destroy();webView=null;} super.onDestroy(); }
}

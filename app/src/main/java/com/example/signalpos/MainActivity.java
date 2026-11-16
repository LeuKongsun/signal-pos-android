package com.example.signalpos;

import androidx.appcompat.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Android register. All interface elements are Android Views, never HTML. */
public class MainActivity extends AppCompatActivity {
    private static final String DEFAULT_BACKEND = "https://signal-pos.vercel.app";
    private static final int INK = 0xFF342D29, MUTED = 0xFF756A61, PAPER = 0xFFFFFDFA,
            CANVAS = 0xFFF6F1EA, LINE = 0xFFE4DBD1, ACCENT = 0xFFB95438, SOFT = 0xFFF8E5D8,
            TRACK = 0xFFF1E8DF, SAGE = 0xFFE7F0E4, PAID = 0xFF356541;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final ExecutorService posts = Executors.newSingleThreadExecutor();
    private final ProductImages productImages = new ProductImages(this);
    private String imageProductId;
    private PosApi api;
    private final ActivityResultLauncher<Intent> imagePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                String productId = imageProductId;
                imageProductId = null;
                if (result.getResultCode() != RESULT_OK || result.getData() == null || productId == null) return;
                Uri image = result.getData().getData();
                if (image == null || api == null || !manager()) return;
                request(() -> {
                    String type = getContentResolver().getType(image);
                    try (java.io.InputStream input = getContentResolver().openInputStream(image)) {
                        if (input == null) throw new Exception("Cannot open the selected image");
                        return api.uploadThumbnail(productId, type, input);
                    }
                }, response -> { toast("Product image saved"); loadRegister(); });
            });
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root, page, productRows, cartRows, totalsBox;
    private TextView countBadge, statusView;
    private MaterialButton holdButton, resumeButton, offerButton, clearButton;
    private Button payButton;
    private PosStorage storage;
    private JSONObject catalog = new JSONObject(), staff, shift, authorization;
    private JSONArray cart = new JSONArray(), drinkImages = new JSONArray();
    private String server = "", tab = "Register", category = "All", search = "", promotionId = "", manageSection = "Catalog";
    private LinearLayout manageNav, manageHost;
    private TextView manageTitle, manageDetail;
    private boolean manageNavWide;
    private JSONObject khmerLabels = new JSONObject();
    private boolean khmer;
    private String draftCurrency = "USD";
    private boolean demo = true, busy, syncing, destroyed, rankByAmount, connecting;
    private final Map<String, PosStorage.Snapshot> memory = new HashMap<>();
    private final Map<String, Integer> tabGeneration = new HashMap<>();
    private final Map<String, Integer> flightGeneration = new HashMap<>();
    private final Map<String, List<TabWait>> tabWaiters = new HashMap<>();
    private ConnectivityManager.NetworkCallback networkCallback;
    private static final long TAB_FRESH = 30_000L, CONTEXT_FRESH = 300_000L;
    private long lastContextRefresh, nextSyncAt, clockOffset;
    private boolean clockKnown;
    private int syncAttempts;
    private String exportData, exportSuccess;
    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                String data = exportData, success = exportSuccess; exportData = null;
                if (result.getResultCode() != RESULT_OK || result.getData() == null || data == null) return;
                Uri destination = result.getData().getData();
                io.execute(() -> {
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(destination)) {
                        if (out == null) throw new Exception("Cannot open destination");
                        out.write(data.getBytes(StandardCharsets.UTF_8));
                        runOnUiThread(() -> toast(success));
                    } catch (Exception error) { runOnUiThread(() -> error(error)); }
                });
            });
    private final Runnable maintenance = new Runnable() {
        @Override public void run() {
            if (!demo && staff != null) {
                if (!busy) sync(false);
                if (checkoutNeedsRefresh() || System.currentTimeMillis() - lastContextRefresh > CONTEXT_FRESH) refreshContext(false);
                warmTabs();
                showStatus();
            }
            handler.postDelayed(this, 5000);
        }
    };

    static JSONObject obj(Object... values) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) object.put((String) values[i], values[i + 1]); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
        return object;
    }
    private JSONArray list(JSONObject object, String key) {
        JSONArray array = object == null ? null : object.optJSONArray(key);
        return array == null ? new JSONArray() : array;
    }
    private JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private String staffId() { return staff == null ? "demo" : staff.optString("id"); }
    private String draftKey() { return (demo ? "demo" : server) + ":" + staffId(); }
    private String currency() { return demo ? "USD" : catalog.optString("currency", "USD"); }
    private boolean awaitingStore() { return demo && !server.isEmpty() && !connecting; }
    private String money(long amount) { return PosMath.money(amount, currency()); }
    private String saleMoney(JSONObject sale, long amount) { return PosMath.money(amount, sale.optString("currency", "USD")); }
    private boolean manager() { return demo || (staff != null && !"cashier".equals(staff.optString("role"))); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        storage = new PosStorage(this);
        khmer = getPreferences(MODE_PRIVATE).getBoolean("native-khmer",false);
        if(state!=null) imageProductId=state.getString("imageProductId");
        server = getPreferences(MODE_PRIVATE).getString("native-server", "");
        try {
            khmerLabels = new JSONObject(readAsset("km-labels.json"));
            catalog = new JSONObject(readAsset("demo-catalog.json"));
            drinkImages = new JSONArray(readAsset("drink-images.json"));
        } catch (Exception error) { error(error); }
        restoreDraft();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!"Register".equals(tab)) { tab = "Register"; render(); }
                else new MaterialAlertDialogBuilder(MainActivity.this).setTitle("Leave the register?")
                        .setMessage("Your order is saved on this tablet.")
                        .setNegativeButton("Keep working", null).setPositiveButton("Close app", (d,w) -> finish()).show();
            }
        });
        connecting = !server.isEmpty();
        if (restoreWorkspace()) connecting = false;
        render();
        if (!server.isEmpty()) connect(server);
        watchNetwork();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("imageProductId",imageProductId);
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); render();
    }
    @Override protected void onResume() { super.onResume(); handler.postDelayed(maintenance, 5000); }
    @Override protected void onPause() { handler.removeCallbacks(maintenance); saveDraft(); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (networkCallback != null) {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager != null) manager.unregisterNetworkCallback(networkCallback);
        }
        io.shutdown();
        posts.shutdown();
        productImages.close();
        super.onDestroy();
    }

    private String readAsset(String filename) throws java.io.IOException {
        try (java.io.InputStream input = getAssets().open(filename);
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) bytes.write(buffer, 0, size);
            return bytes.toString("UTF-8");
        }
    }

    private String tr(String label) {
        return khmer ? khmerLabels.optString(label,label) : label;
    }

    private GradientDrawable shape(int color, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(radius)); if (border) drawable.setStroke(dp(1), LINE); return drawable;
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (khmer) view.setLineSpacing(dp(2), 1.2f);
        view.setFontFeatureSettings("tnum"); return view;
    }
    private MaterialButton button(String title, boolean primary, Runnable action) {
        MaterialButton view = new MaterialButton(this); view.setText(title); view.setAllCaps(false);
        view.setTextSize(14); view.setLetterSpacing(0); view.setElevation(0); view.setStateListAnimator(null);
        view.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        view.setMinHeight(dp(50)); view.setMinimumWidth(dp(48));
        view.setCornerRadius(dp(12)); view.setInsetTop(dp(3)); view.setInsetBottom(dp(3));
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? ACCENT : PAPER));
        if (!primary) {
            view.setStrokeWidth(dp(1));
            view.setStrokeColor(android.content.res.ColorStateList.valueOf(LINE));
        }
        view.setTextColor(primary ? Color.WHITE : INK); view.setOnClickListener(v -> action.run()); return view;
    }
    private void gap(LinearLayout parent, int size) { View view = new View(this); parent.addView(view, new LinearLayout.LayoutParams(1, dp(size))); }
    private void rule(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(LINE); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private ScrollView scroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(content); return scroll;
    }
    private EditText input(LinearLayout parent, String label, String value, boolean numeric) {
        return field(parent, label, value, numeric).getEditText();
    }

    private TextInputLayout field(LinearLayout parent, String label, String value, boolean numeric) {
        TextInputLayout field = new TextInputLayout(this);
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setBoxCornerRadii(dp(10), dp(10), dp(10), dp(10));
        field.setHint(label);
        TextInputEditText edit = new TextInputEditText(field.getContext());
        edit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_TEXT);
        edit.setSingleLine(true);
        edit.setTextSize(16);
        edit.setTextColor(INK);
        edit.setMinHeight(dp(52));
        edit.setText(value);
        field.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(field, params);
        return field;
    }
    private LinearLayout dialogBody() { LinearLayout body = column(); body.setPadding(dp(24), dp(8), dp(24), dp(20)); return body; }
    private AlertDialog dialog(String title, LinearLayout body) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(title).setView(scroll(body))
                .setNegativeButton(tr("Cancel"), null).create();
        dialog.setOnShowListener(v -> {
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                    Math.min(dp(620), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
        }); return dialog;
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void error(Exception error) { if (!destroyed) toast(error.getMessage() == null ? "Unable to complete this action" : error.getMessage()); }

    private void render() {
        if (destroyed) return;
        root = column(); root.setBackgroundColor(CANVAS); setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        ViewCompat.requestApplyInsets(root);
        LinearLayout header = column(); header.setPadding(dp(16), dp(8), dp(12), dp(8)); header.setBackgroundColor(PAPER);
        LinearLayout top = row();
        TextView wordmark = text("SIGNAL COFFEE", 15, INK, true);
        wordmark.setLetterSpacing(0.06f);
        top.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        statusView = text("", 12, ACCENT, true);
        showStatus();
        top.addView(statusView);
        MaterialButton account = button(connecting ? "Connecting…" : awaitingStore() ? "Retry" : demo ? tr("Connect server") : staff == null ? tr("Sign in") : staff.optString("name"), false, this::account);
        account.setMinHeight(dp(40)); account.setInsetTop(0); account.setInsetBottom(0); account.setSingleLine(true);
        LinearLayout.LayoutParams accountParams = new LinearLayout.LayoutParams(-2, -2);
        accountParams.setMarginStart(dp(8));
        top.addView(account, accountParams);
        header.addView(top);
        header.addView(navigation());
        root.addView(header); rule(root);
        page = column();
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (tab) {
            case "Sales": showSales(); break;
            case "Shift": showShift(); break;
            case "Reports": showReports(); break;
            case "Manage": showManagement(); break;
            case "Settings": showSettings(); break;
            default: showRegister();
        }
    }
    private String status() {
        if (busy || connecting) return "Connecting…";
        if (awaitingStore()) return "The store didn't answer";
        if (demo) return "DEMO · No live sales";
        String checkout = checkoutNotice();
        if (checkout != null) return checkout;
        if (!online() && storage.pendingCount() > 0) return "Offline · " + storage.pendingCount() + " awaiting sync";
        if (!online()) return "Offline · using saved data";
        if (storage.pendingCount() > 0) return storage.pendingCount() + " awaiting sync";
        String clock = clockNote();
        return clock != null ? clock : "Register ready";
    }

    private void showStatus() {
        if (statusView == null) return;
        boolean calm = !demo && !connecting && !busy && online() && storage.pendingCount() == 0 && checkoutNotice() == null && clockNote() == null;
        statusView.setText(status());
        statusView.setTextColor(calm ? PAID : ACCENT);
        statusView.setBackground(shape(calm ? SAGE : SOFT, 8, false));
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6));
    }

    private HorizontalScrollView navigation() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -2);
        scrollParams.topMargin = dp(10);
        scroller.setLayoutParams(scrollParams);
        LinearLayout nav = row();
        nav.setBackground(shape(TRACK, 14, true));
        nav.setPadding(dp(4), dp(4), dp(4), dp(4));
        int itemHeight = dp(khmer ? 48 : 40);
        for (String item : new String[]{"Register", "Sales", "Shift", "Reports", "Manage", "Settings"}) {
            if ((item.equals("Manage") || item.equals("Reports")) && !manager()) continue;
            boolean selected = item.equals(tab);
            MaterialButton navigation = button(tr(item), selected, () -> { tab = item; render(); });
            navigation.setTextSize(13);
            navigation.setSingleLine(true);
            navigation.setMinHeight(itemHeight);
            navigation.setMinimumWidth(0);
            navigation.setInsetTop(0);
            navigation.setInsetBottom(0);
            navigation.setCornerRadius(dp(10));
            navigation.setPadding(dp(16), 0, dp(16), 0);
            if (!selected) {
                navigation.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
                navigation.setStrokeWidth(0);
                navigation.setTextColor(MUTED);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, itemHeight);
            if (nav.getChildCount() > 0) params.setMarginStart(dp(2));
            nav.addView(navigation, params);
        }
        scroller.addView(nav);
        return scroller;
    }
    private void showConnectingRegister() {
        Configuration configuration = getResources().getConfiguration();
        boolean wide = configuration.screenWidthDp >= 900;
        boolean sideBySide = wide || (configuration.screenWidthDp >= 620 && configuration.screenHeightDp < 560);
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.addView(split, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout menu = column();
        menu.setPadding(dp(12), dp(12), dp(12), dp(12));
        menu.setContentDescription("Loading the menu");
        split.addView(menu, new LinearLayout.LayoutParams(sideBySide ? 0 : -1, sideBySide ? -1 : 0, 1));
        menu.addView(bone(LINE, 48, 0));
        gap(menu, 12);
        int columns = wide ? 3 : 2;
        for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
            LinearLayout line = row();
            line.setGravity(Gravity.TOP);
            for (int column = 0; column < columns; column++) {
                LinearLayout card = column();
                card.setBackground(shape(PAPER, 16, true));
                card.setClipToOutline(true);
                card.addView(bone(TRACK, 148, 0));
                LinearLayout details = column();
                details.setPadding(dp(12), dp(10), dp(12), dp(12));
                details.addView(bone(LINE, 14, 96));
                gap(details, 8);
                details.addView(bone(LINE, 12, 64));
                card.addView(details);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                params.setMargins(dp(4), 0, dp(4), dp(8));
                line.addView(card, params);
            }
            menu.addView(line);
        }
        pulse(menu);
        View seam = new View(this);
        seam.setBackgroundColor(LINE);
        split.addView(seam, new LinearLayout.LayoutParams(sideBySide ? dp(1) : -1, sideBySide ? -1 : dp(1)));
        LinearLayout order = column();
        order.setBackgroundColor(PAPER);
        order.setGravity(Gravity.CENTER);
        order.setPadding(dp(24), dp(24), dp(24), dp(24));
        order.addView(text("Connecting to the store…", 16, MUTED, false));
        int orderWidth = sideBySide ? dp(wide ? 360 : 300) : -1;
        int orderHeight = sideBySide ? -1 : dp(160);
        split.addView(order, new LinearLayout.LayoutParams(orderWidth, orderHeight));
    }

    private void showUnreachedRegister() {
        LinearLayout body = column();
        body.setGravity(Gravity.CENTER);
        body.setPadding(dp(32), dp(32), dp(32), dp(32));
        page.addView(body, new LinearLayout.LayoutParams(-1, -1));
        TextView title = text("The store didn't answer", 28, INK, true);
        title.setGravity(Gravity.CENTER);
        body.addView(title);
        gap(body, 8);
        TextView detail = text("Retry the saved store before taking an order.", 15, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        body.addView(detail);
        gap(body, 6);
        TextView address = text(server, 14, INK, true);
        address.setGravity(Gravity.CENTER);
        body.addView(address);
        gap(body, 18);
        body.addView(button("Retry", true, this::retryStore));
        gap(body, 8);
        body.addView(button("Change server", false, this::connectionDialog));
    }

    private void showRegister() {
        if (connecting) {
            showConnectingRegister();
            return;
        }
        if (awaitingStore()) {
            showUnreachedRegister();
            return;
        }
        Configuration configuration = getResources().getConfiguration();
        boolean wide = configuration.screenWidthDp >= 900;
        boolean sideBySide = wide || (configuration.screenWidthDp >= 620 && configuration.screenHeightDp < 560);
        LinearLayout split = new LinearLayout(this); split.setOrientation(sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.addView(split, new LinearLayout.LayoutParams(-1, -1));
        boolean categoryRail = sideBySide && configuration.screenWidthDp >= 960;
        LinearLayout menu = categoryRail ? row() : column();
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        split.addView(menu, new LinearLayout.LayoutParams(sideBySide ? 0 : -1, sideBySide ? -1 : 0, 1));
        LinkedHashSet<String> names = new LinkedHashSet<>(); names.add("All");
        JSONArray products = list(catalog, "products");
        for (int i = 0; i < products.length(); i++) names.add(products.optJSONObject(i).optString("category"));
        LinearLayout catalog = column();
        if (categoryRail) {
            ScrollView railScroll = new ScrollView(this);
            railScroll.setFillViewport(true);
            LinearLayout rail = column();
            rail.setPadding(dp(4), dp(4), dp(4), dp(4));
            rail.setBackground(shape(PAPER, 12, true));
            for (String name : names) rail.addView(categoryButton(name, true));
            railScroll.addView(rail, new FrameLayout.LayoutParams(-1, -2));
            menu.addView(railScroll, new LinearLayout.LayoutParams(dp(148), -1));
        }
        EditText searchBox = searchField(catalog, tr("Search the menu"), search, -1);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { search = s.toString(); renderProducts(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        if (!categoryRail) {
            HorizontalScrollView categories = new HorizontalScrollView(this);
            categories.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = row();
            chips.setPadding(0, dp(8), 0, dp(4));
            for (String name : names) chips.addView(categoryButton(name, false));
            categories.addView(chips);
            catalog.addView(categories);
        }
        productRows = column();
        catalog.addView(scroll(productRows), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams catalogParams = new LinearLayout.LayoutParams(categoryRail ? 0 : -1, categoryRail ? -1 : 0, 1);
        if (categoryRail) catalogParams.setMarginStart(dp(8));
        menu.addView(catalog, catalogParams);
        productRows.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (view == productRows && right - left != oldRight - oldLeft) renderProducts();
        });
        View seam = new View(this); seam.setBackgroundColor(LINE);
        split.addView(seam, new LinearLayout.LayoutParams(sideBySide ? dp(1) : -1, sideBySide ? -1 : dp(1)));
        LinearLayout order = column(); order.setBackgroundColor(PAPER);
        int orderWidth = sideBySide ? dp(wide ? 360 : 300) : -1;
        int orderHeight = sideBySide ? -1 : dp(Math.min(440, configuration.screenHeightDp / 2));
        split.addView(order, new LinearLayout.LayoutParams(orderWidth, orderHeight));
        LinearLayout orderHeader = column(); orderHeader.setPadding(dp(16), dp(12), dp(16), dp(4));
        LinearLayout title = row();
        title.addView(text(tr("Current order"), 18, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        countBadge = text("—", 12, ACCENT, true);
        countBadge.setGravity(Gravity.CENTER);
        countBadge.setMinWidth(dp(28));
        countBadge.setMinimumHeight(dp(28));
        countBadge.setBackground(shape(SOFT, 14, false));
        title.addView(countBadge);
        orderHeader.addView(title);
        resumeButton = button("Resume", false, this::resumeOrder);
        compact(resumeButton);
        LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(-1, -2);
        resumeParams.topMargin = dp(8);
        resumeButton.setVisibility(View.GONE);
        orderHeader.addView(resumeButton, resumeParams);
        order.addView(orderHeader);
        cartRows = column();
        ScrollView cartScroll = scroll(cartRows);
        cartScroll.setFillViewport(true);
        order.addView(cartScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = column();
        footer.setBackgroundColor(0xFFFCF7F1);
        footer.setPadding(dp(16), dp(10), dp(16), dp(12));
        order.addView(footer);
        totalsBox = column();
        footer.addView(totalsBox);
        payButton = button(tr("Charge"), true, this::checkout);
        LinearLayout.LayoutParams payParams = new LinearLayout.LayoutParams(-1, dp(64));
        payParams.topMargin = dp(8);
        footer.addView(payButton, payParams);
        LinearLayout actions = row();
        LinearLayout.LayoutParams actionGap = new LinearLayout.LayoutParams(0, -2, 1);
        actionGap.topMargin = dp(8);
        actionGap.setMarginEnd(dp(8));
        holdButton = button(tr("Hold"), false, this::holdOrder);
        compact(holdButton);
        actions.addView(holdButton, actionGap);
        offerButton = button(tr("Offer"), false, this::choosePromotion);
        compact(offerButton);
        LinearLayout.LayoutParams offerParams = new LinearLayout.LayoutParams(0, -2, 1);
        offerParams.topMargin = dp(8);
        offerParams.setMarginEnd(dp(8));
        actions.addView(offerButton, offerParams);
        clearButton = button("Clear", false, () -> new MaterialAlertDialogBuilder(this).setTitle("Clear this order?")
                .setNegativeButton("Keep order", null).setPositiveButton("Clear", (d,w) -> {
                    cart = new JSONArray(); promotionId = ""; saveDraft(); renderCart();
                }).show());
        compact(clearButton);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, -2, 1);
        clearParams.topMargin = dp(8);
        actions.addView(clearButton, clearParams);
        footer.addView(actions);
        renderCart();
    }

    private MaterialButton categoryButton(String name, boolean rail) {
        MaterialButton chip = button(name, category.equals(name), () -> { });
        chip.setTag(name);
        chip.setTextSize(rail ? 14 : 13);
        chip.setSingleLine(!rail);
        chip.setMaxLines(rail ? 2 : 1);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(rail ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        chip.setCornerRadius(dp(rail ? 10 : 24));
        chip.setMinimumWidth(rail ? 0 : dp(72));
        chip.setMinHeight(dp(rail ? 52 : 48));
        chip.setInsetTop(0);
        chip.setInsetBottom(0);
        chip.setPadding(dp(rail ? 12 : 16), 0, dp(rail ? 12 : 16), 0);
        styleCategory(chip, category.equals(name));
        chip.setOnClickListener(v -> {
            category = name;
            View parent = (View) v.getParent();
            if (parent instanceof LinearLayout) {
                LinearLayout group = (LinearLayout) parent;
                for (int index = 0; index < group.getChildCount(); index++) {
                    if (group.getChildAt(index) instanceof MaterialButton) {
                        MaterialButton candidate = (MaterialButton) group.getChildAt(index);
                        styleCategory(candidate, category.equals(candidate.getTag()));
                    }
                }
            }
            renderProducts();
        });
        LinearLayout.LayoutParams params = rail
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(-2, dp(48));
        if (rail) params.bottomMargin = dp(4);
        else params.setMarginEnd(dp(8));
        chip.setLayoutParams(params);
        return chip;
    }

    private void compact(MaterialButton view) {
        view.setMinHeight(dp(44));
        view.setInsetTop(0);
        view.setInsetBottom(0);
        view.setTextSize(13);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
    }

    private EditText searchField(LinearLayout parent, String hint, String value, int width) {
        LinearLayout box = row();
        box.setBackground(shape(PAPER, 14, true));
        box.setPadding(dp(14), 0, dp(14), 0);
        EditText edit = new EditText(this);
        edit.setBackground(null);
        edit.setHint(hint);
        edit.setHintTextColor(MUTED);
        edit.setText(value);
        edit.setTextColor(INK);
        edit.setTextSize(15);
        edit.setSingleLine(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setMinHeight(0);
        edit.setMinimumHeight(0);
        box.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(48));
        params.bottomMargin = dp(8);
        if (width != LinearLayout.LayoutParams.MATCH_PARENT) params.setMarginStart(dp(16));
        parent.addView(box, params);
        return edit;
    }

    private void styleCategory(MaterialButton chip, boolean selected) {
        chip.setSelected(selected);
        chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? ACCENT : 0x9EFFFDF8));
        chip.setTextColor(selected ? Color.WHITE : MUTED);
        chip.setStrokeWidth(dp(1));
        chip.setStrokeColor(android.content.res.ColorStateList.valueOf(selected ? ACCENT : LINE));
    }

    private void renderProducts() {
        if (productRows == null || productRows.getWidth() == 0) return;
        productRows.removeAllViews();
        JSONArray products = list(catalog, "products");
        int available = productRows.getWidth();
        int columns = Math.max(1, Math.min(4, available / dp(168)));
        int photoHeight = dp(148);
        int count = 0;
        LinearLayout line = null;
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (!category.equals("All") && !category.equals(product.optString("category"))) continue;
            if (!(product.optString("name") + " " + product.optString("description")).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))) continue;
            if (count++ % columns == 0) { line = row(); line.setGravity(Gravity.TOP); productRows.addView(line); }
            String name = product.optString("name");
            String price = money(product.optLong("price"));
            LinearLayout card = column();
            card.setBackground(shape(PAPER, 16, true));
            card.setClipToOutline(true);
            card.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x24B95438), null, shape(Color.WHITE, 16, false)));

            FrameLayout imageFrame = new FrameLayout(this);
            imageFrame.setBackgroundColor(tone(product.optString("tone")));
            TextView artwork = text(product.optString("initials", "SC"), 28, ACCENT, true);
            artwork.setGravity(Gravity.CENTER);
            artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            imageFrame.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
            if (product.optBoolean("popular")) {
                TextView badge = text(tr("Popular"), 10, ACCENT, true);
                badge.setTag("badge");
                badge.setBackground(shape(0xD9FFFDF8, 6, false));
                badge.setPadding(dp(6), dp(3), dp(6), dp(3));
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
                badgeParams.setMargins(dp(8), dp(8), dp(8), dp(8));
                imageFrame.addView(badge, badgeParams);
            }
            int ordered = orderedCount(product.optString("id"));
            if (ordered > 0) {
                TextView qty = text(String.valueOf(ordered), 12, Color.WHITE, true);
                qty.setTag("badge");
                qty.setGravity(Gravity.CENTER);
                qty.setMinWidth(dp(24));
                qty.setBackground(shape(ACCENT, 12, false));
                qty.setPadding(dp(6), dp(2), dp(6), dp(2));
                FrameLayout.LayoutParams qtyParams = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP);
                qtyParams.setMargins(dp(8), dp(8), dp(8), dp(8));
                imageFrame.addView(qty, qtyParams);
            }
            card.addView(imageFrame, new LinearLayout.LayoutParams(-1, photoHeight));
            showProductImage(product, imageFrame, ImageView.ScaleType.FIT_CENTER);

            LinearLayout details = row();
            details.setGravity(Gravity.CENTER_VERTICAL);
            details.setPadding(dp(12), dp(10), dp(12), dp(12));
            LinearLayout names = column();
            TextView title = text(name, 14, INK, true);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(title);
            String description = product.optString("description");
            if (!description.isEmpty()) {
                TextView subtitle = text(description, 11, MUTED, false);
                subtitle.setMaxLines(1);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                names.addView(subtitle);
            }
            details.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            TextView priceView = text(price, 13, ACCENT, true);
            priceView.setPadding(dp(8), 0, 0, 0);
            details.addView(priceView);
            card.addView(details);
            card.setContentDescription(name + ", " + price + ", " + tr("Add to order"));
            card.setFocusable(true);
            card.setOnClickListener(v -> customize(product));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(dp(4), 0, dp(4), dp(8));
            line.addView(card, params);
        }
        if (line == null) {
            productRows.addView(text("No drinks match your search.", 16, MUTED, false));
        } else {
            while (line.getChildCount() < columns) {
                LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 1, 1);
                spacer.setMargins(dp(4), 0, dp(4), 0);
                line.addView(new View(this), spacer);
            }
        }
    }
    private void showProductImage(JSONObject product, FrameLayout frame, ImageView.ScaleType scaleType) {
        String url=product.optString("thumbnailUrl","");
        if(url.isEmpty() || product.isNull("thumbnailUrl")) url=defaultDrinkImage(product);
        if(url.isEmpty()) return;
        boolean libraryDrink = url.startsWith("/drinks/");
        productImages.load(server,url,bitmap -> {
            if(destroyed) return;
            ImageView image=new ImageView(this);
            image.setScaleType(scaleType);
            if (scaleType == ImageView.ScaleType.FIT_CENTER && libraryDrink) {
                int inset = dp(8);
                image.setPadding(inset, inset, inset, inset);
            }
            image.setImageBitmap(bitmap);
            for (int index = frame.getChildCount() - 1; index >= 0; index--) {
                if (!"badge".equals(frame.getChildAt(index).getTag())) frame.removeViewAt(index);
            }
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(image, 0, new FrameLayout.LayoutParams(-1, -1));
        });
    }

    private String defaultDrinkImage(JSONObject product) {
        if(server.isEmpty()) return "";
        Set<String> keys=new HashSet<>();
        for(String field:new String[]{"slug","id","name"}) {
            keys.add(java.text.Normalizer.normalize(product.optString(field),java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$",""));
        }
        for(int i=0;i<drinkImages.length();i++) {
            JSONObject image=drinkImages.optJSONObject(i);
            JSONArray slugs=list(image,"slugs");
            for(int j=0;j<slugs.length();j++) if(keys.contains(slugs.optString(j))) return drinkImageUrl(image);
        }
        return "";
    }

    private String drinkImageUrl(JSONObject image) {
        return "/drinks/"+Uri.encode(image.optString("file"));
    }

    private void pickProductImage(String productId) {
        imageProductId=productId;
        imagePicker.launch(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp"}));
    }

    private int tone(String name) {
        switch(name) {
            case "sage": return 0xFFDCE4D7; case "sky": return 0xFFDCE8EC;
            case "rose": return 0xFFF0DDDB; case "butter": return 0xFFF0E8C9;
            case "clay": return 0xFFEBD8C9; default: return 0xFFEAE3D5;
        }
    }
    private void customize(JSONObject product) {
        if(cart.length()>0 && !draftCurrency.equals(currency())) {
            toast("Clear or hold the previous-currency order before adding items");
            return;
        }
        String productCurrency=currency();
        LinearLayout body = dialogBody(); body.addView(text(product.optString("description"), 15, MUTED, false));
        JSONArray groups = list(product, "modifierGroups"); Map<String, JSONObject> chosen = new LinkedHashMap<>();
        final MaterialButton[] addButton=new MaterialButton[1];
        Runnable updatePrice=() -> {
            long amount=product.optLong("price"); for(JSONObject selected:chosen.values()) amount+=selected.optLong("price");
            if(addButton[0]!=null) addButton[0].setText(tr("Add to order")+" · "+money(amount));
        };
        for (int i=0; i<groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i); gap(body,16);
            body.addView(text(group.optString("name") + (group.optBoolean("required") ? " · Choose one" : " · Optional"), 15, INK, true));
            RadioGroup radios = new RadioGroup(this); JSONArray options = list(group, "options");
            if (!group.optBoolean("required")) {
                RadioButton none = new RadioButton(this); none.setText("None"); none.setMinHeight(dp(48)); radios.addView(none); none.setChecked(true);
                none.setOnCheckedChangeListener((b,checked) -> { if(checked) { chosen.remove(group.optString("id")); updatePrice.run(); } });
            }
            for (int j=0; j<options.length(); j++) {
                JSONObject option = options.optJSONObject(j); RadioButton radio = new RadioButton(this);
                radio.setId(View.generateViewId()); radio.setMinHeight(dp(48));
                radio.setText(option.optString("name") + (option.optLong("price") == 0 ? " · Included" : "  +" + money(option.optLong("price"))));
                radios.addView(radio); radio.setOnCheckedChangeListener((b,checked) -> {
                    if(checked) { chosen.put(group.optString("id"), obj("id",option.optString("id"),"name",option.optString("name"),"price",option.optLong("price"),"groupId",group.optString("id"))); updatePrice.run(); }
                });
                if(j==0 && group.optBoolean("required")) radio.setChecked(true);
            }
            body.addView(radios);
        }
        EditText note = input(body,"Order note (optional)","",false); note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        AlertDialog dialog = dialog(product.optString("name"), body);
        addButton[0]=button(tr("Add to order")+" · " + money(product.optLong("price")),true,() -> {
            if(!productCurrency.equals(currency())) {
                dialog.dismiss(); toast("The store currency changed. Choose the product again."); return;
            }
            if(cart.length()==0) draftCurrency=productCurrency;
            if(cart.length()>=100) { toast("Maximum 100 order lines"); return; }
            JSONArray modifiers = new JSONArray(); for(JSONObject option: chosen.values()) modifiers.put(option);
            cart.put(obj("id",UUID.randomUUID().toString(),"productId",product.optString("id"),"name",product.optString("name"),
                    "quantity",1,"unitPrice",product.optLong("price"),"modifiers",modifiers,"notes",note.getText().toString().trim()));
            saveDraft(); renderCart(); dialog.dismiss();
        }); body.addView(addButton[0]); updatePrice.run(); dialog.show();
    }
    private long lineTotal(JSONObject item) {
        long price = item.optLong("unitPrice"); JSONArray modifiers = list(item,"modifiers");
        for(int i=0;i<modifiers.length();i++) price += modifiers.optJSONObject(i).optLong("price");
        return price * item.optInt("quantity");
    }
    private JSONObject promotion() {
        JSONArray promotions = list(catalog,"promotions");
        for(int i=0;i<promotions.length();i++) if(promotionId.equals(promotions.optJSONObject(i).optString("id"))) return promotions.optJSONObject(i);
        return null;
    }
    private long subtotal() { long total=0; for(int i=0;i<cart.length();i++) total+=lineTotal(cart.optJSONObject(i)); return total; }
    private long discount() {
        JSONObject promotion = promotion(); if(promotion==null) return 0;
        List<Long> units = new ArrayList<>();
        for(int i=0;i<cart.length();i++) { JSONObject item=cart.optJSONObject(i); for(int j=0;j<item.optInt("quantity");j++) units.add(lineTotal(item)/item.optInt("quantity")); }
        return PosMath.discount(units,promotion.optString("type"),promotion.optInt("percentage"), currency());
    }
    private long total() { return subtotal()-discount(); }
    private void renderCart() {
        if(cartRows==null || !"Register".equals(tab)) return;
        cartRows.removeAllViews();
        cartRows.setGravity(cart.length()==0 ? Gravity.CENTER : Gravity.TOP);
        cartRows.setPadding(dp(20), dp(4), dp(16), dp(8));
        int units = 0;
        for (int i = 0; i < cart.length(); i++) units += cart.optJSONObject(i).optInt("quantity");
        if (countBadge != null) countBadge.setText(units == 0 ? "—" : String.valueOf(units));
        if(cart.length()==0) {
            TextView headline = text(tr("A fresh start."), 22, INK, true);
            headline.setGravity(Gravity.CENTER);
            TextView hint = text(tr("Tap a drink to start an order.\nSizes, extras and notes come next."), 14, MUTED, false);
            hint.setGravity(Gravity.CENTER);
            cartRows.addView(headline);
            gap(cartRows, 8);
            cartRows.addView(hint);
        }
        for(int i=0;i<cart.length();i++) {
            final int index=i; JSONObject item=cart.optJSONObject(i); LinearLayout line=column();
            line.setPadding(0, dp(14), 0, dp(12));
            LinearLayout heading = row();
            TextView name = text(item.optString("name"), 16, INK, true);
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(text(PosMath.money(lineTotal(item), draftCurrency), 15, INK, true));
            line.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            JSONArray modifiers=list(item,"modifiers");
            if (modifiers.length() > 0) {
                StringBuilder details=new StringBuilder();
                for(int j=0;j<modifiers.length();j++) { if(j>0) details.append(" · "); details.append(modifiers.optJSONObject(j).optString("name")); }
                line.addView(text(details.toString(), 12, MUTED, false));
            }
            if(!item.optString("notes").isEmpty()) {
                TextView note = text(item.optString("notes"), 12, ACCENT, false);
                note.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                line.addView(note);
            }
            LinearLayout quantity=row();
            quantity.setPadding(0, dp(8), 0, 0);
            LinearLayout stepper = row();
            stepper.setBackground(shape(SOFT, 10, true));
            stepper.addView(stepButton("−", "Remove one " + item.optString("name"), () -> changeQuantity(index, -1)));
            TextView count=text(String.valueOf(item.optInt("quantity")), 15, INK, true);
            count.setGravity(Gravity.CENTER);
            count.setMinWidth(dp(28));
            stepper.addView(count);
            stepper.addView(stepButton("+", "Add one " + item.optString("name"), () -> changeQuantity(index, 1)));
            quantity.addView(stepper);
            TextView edit = text("Edit", 13, ACCENT, true);
            edit.setPadding(dp(12), dp(8), dp(4), dp(8));
            edit.setOnClickListener(v -> editLine(index));
            edit.setContentDescription("Edit " + item.optString("name"));
            quantity.addView(edit);
            line.addView(quantity, new LinearLayout.LayoutParams(-1, -2));
            line.setOnLongClickListener(v -> { editLine(index); return true; });
            cartRows.addView(line, new LinearLayout.LayoutParams(-1, -2)); rule(cartRows);
        }
        if (holdButton != null) usable(holdButton, cart.length() > 0);
        JSONObject offer = promotion();
        if (offerButton != null) {
            paint(offerButton, offer != null ? SOFT : PAPER, offer != null ? ACCENT : LINE, offer != null ? ACCENT : INK);
            String summary = offerSummary(offer);
            offerButton.setText(offer == null ? tr("Offer") : summary.isEmpty() ? offer.optString("name") : summary);
        }
        boolean blocked = cart.length() > 0 && !draftCurrency.equals(currency());
        if (totalsBox != null) {
            totalsBox.removeAllViews();
            if (blocked) totalsBox.addView(text("Order currency changed. Clear the order to start again.", 14, ACCENT, true));
            else {
                moneyLine(totalsBox, "Subtotal", money(subtotal()), MUTED, 13, false);
                if (discount() > 0) {
                    String offerName = offer == null || offer.optString("name").isEmpty() ? tr("Offer") : offer.optString("name");
                    moneyLine(totalsBox, offerName, "−" + money(discount()), ACCENT, 13, false);
                }
                rule(totalsBox);
                moneyLine(totalsBox, tr("Total"), money(total()), INK, 14, true);
            }
        }
        payButton.setText((demo ? tr("Demo charge") : tr("Charge")) + "  " + money(total()));
        payButton.setEnabled(!blocked && cart.length()>0 && !busy);
        payButton.setAlpha(payButton.isEnabled() ? 1f : 0.45f);
        if (resumeButton != null) {
            boolean held = heldOrder();
            resumeButton.setVisibility(held ? View.VISIBLE : View.GONE);
            if (held) {
                int waiting = heldOrders().length();
                resumeButton.setText(waiting > 1 ? "Resume · " + waiting : "Resume");
                paint(resumeButton, 0xFFFFF4DA, 0xFFD7B77A, ACCENT);
            }
        }
        if (clearButton != null) usable(clearButton, cart.length() > 0);
        if (productRows != null && productRows.getWidth() > 0) renderProducts();
    }

    private int orderedCount(String productId) {
        int count = 0;
        for (int i = 0; i < cart.length(); i++) {
            JSONObject item = cart.optJSONObject(i);
            if (item != null && productId.equals(item.optString("productId"))) count += item.optInt("quantity");
        }
        return count;
    }

    private TextView stepButton(String label, String description, Runnable action) {
        TextView view = text(label, 18, INK, true);
        view.setGravity(Gravity.CENTER);
        view.setMinimumWidth(dp(40));
        view.setMinimumHeight(dp(40));
        view.setContentDescription(description);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        view.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x24B95438), null, shape(Color.WHITE, 10, false)));
        return view;
    }

    private void moneyLine(LinearLayout parent, String label, String amount, int color, int size, boolean emphasize) {
        LinearLayout line = row();
        line.setPadding(0, dp(emphasize ? 8 : 3), 0, dp(2));
        line.addView(text(label, emphasize ? 14 : size, emphasize ? INK : MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(amount, emphasize ? 22 : size, color, true);
        value.setGravity(Gravity.END);
        line.addView(value);
        parent.addView(line);
    }

    private void paint(MaterialButton view, int background, int stroke, int textColor) {
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        view.setStrokeWidth(dp(1));
        view.setStrokeColor(android.content.res.ColorStateList.valueOf(stroke));
        view.setTextColor(textColor);
    }

    private void usable(MaterialButton view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
    }

    private String heldKey() { return draftKey() + ":held"; }
    private boolean heldOrder() { return heldOrders().length() > 0; }
    private JSONArray heldOrders() {
        try {
            JSONObject body = storage.draft(heldKey());
            if (body.has("orders")) return list(body, "orders");
            if (list(body, "items").length() > 0) {
                JSONArray one = new JSONArray();
                one.put(body);
                return one;
            }
        } catch (Exception ignored) { }
        return new JSONArray();
    }
    private void saveHeld(JSONArray orders) {
        try { storage.draft(heldKey(), obj("orders", orders)); }
        catch (Exception error) { error(error); }
    }
    private void changeQuantity(int index,int delta) {
        JSONObject item=cart.optJSONObject(index); if(item==null) return;
        int quantity=item.optInt("quantity")+delta;
        if(quantity>50) { toast("Maximum 50 per line"); return; }
        if(quantity<=0) cart.remove(index); else try { item.put("quantity",quantity); } catch(Exception error) { error(error); }
        saveDraft(); renderCart();
    }
    private void editLine(int index) {
        if(!draftCurrency.equals(currency())) {
            toast("This order uses the previous store currency");
            return;
        }
        String editCurrency=currency();
        JSONObject item=cart.optJSONObject(index); LinearLayout body=dialogBody();
        EditText price=input(body,"Base price ("+currency()+")",PosMath.amount(item.optLong("unitPrice"),currency()),true);
        long menuPrice = catalogPrice(item.optString("productId"));
        if (menuPrice >= 0) body.addView(text("Menu price  " + PosMath.money(menuPrice, editCurrency), 14, MUTED, false));
        EditText note=input(body,tr("Order note"),item.optString("notes"),false);
        AlertDialog dialog=dialog("Edit " + item.optString("name"),body);
        body.addView(button("Save item",true,() -> {
            try { if(!editCurrency.equals(currency())) throw new Exception("The store currency changed");
                long cents=PosMath.cents(price.getText().toString(),editCurrency); if(cents>1000000 || note.length()>160) throw new Exception("Price or note exceeds the allowed limit");
                item.put("unitPrice",cents); item.put("notes",note.getText().toString()); saveDraft(); renderCart(); dialog.dismiss();
            } catch(Exception error) { error(error); }
        })); dialog.show();
    }
    private String offerSummary(JSONObject promotion) {
        if (promotion == null) return "";
        String type = promotion.optString("type");
        if ("percentage".equals(type) && promotion.optInt("percentage") > 0) return promotion.optInt("percentage") + "% off";
        if ("buy-two-get-one".equals(type)) return "Buy 2, get 1";
        return promotion.optString("description");
    }
    private void offerChoice(LinearLayout body, String title, String detail, boolean selected, Runnable action) {
        LinearLayout row = row();
        row.setBackground(shape(selected ? SOFT : PAPER, 12, true));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setMinimumHeight(dp(52));
        row.addView(text(title, 16, selected ? ACCENT : INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (!detail.isEmpty()) row.addView(text(detail, 14, selected ? ACCENT : MUTED, true));
        pressable(row, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        body.addView(row, params);
    }
    private void choosePromotion() {
        JSONArray promotions = list(catalog, "promotions");
        LinearLayout body = dialogBody();
        if (promotions.length() == 0) {
            body.addView(text("No offers are on this menu.", 15, MUTED, false));
            gap(body, 12);
        }
        AlertDialog dialog = dialog("Order offer", body);
        offerChoice(body, "No offer", "", promotionId.isEmpty(), () -> {
            promotionId = "";
            saveDraft();
            renderCart();
            dialog.dismiss();
        });
        for (int i = 0; i < promotions.length(); i++) {
            JSONObject promotion = promotions.optJSONObject(i);
            if (promotion == null) continue;
            String id = promotion.optString("id");
            offerChoice(body, promotion.optString("name"), offerSummary(promotion), id.equals(promotionId), () -> {
                promotionId = id;
                saveDraft();
                renderCart();
                dialog.dismiss();
            });
        }
        dialog.show();
    }
    private void saveDraft() {
        if(storage==null) return;
        try { storage.draft(draftKey(),obj("items",cart,"promotionId",promotionId,"currency",draftCurrency)); }
        catch(Exception error) { error(error); }
    }
    private void restoreDraft() {
        try { JSONObject draft=storage.draft(draftKey()); cart=list(draft,"items"); promotionId=draft.optString("promotionId"); draftCurrency=draft.optString("currency","USD"); }
        catch(Exception error) { error(error); }
    }
    private long catalogPrice(String productId) {
        JSONArray products = list(catalog, "products");
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product != null && productId.equals(product.optString("id"))) return product.optLong("price");
        }
        return -1;
    }
    private void holdOrder() {
        if(cart.length()==0) return;
        try {
            JSONArray orders = heldOrders();
            if (orders.length() >= 8) { toast("Resume a held order before holding another"); return; }
            orders.put(obj("id", UUID.randomUUID().toString(), "items", new JSONArray(cart.toString()),
                    "promotionId", promotionId, "currency", draftCurrency, "heldAt", storeNow()));
            saveHeld(orders);
            cart=new JSONArray(); promotionId="";
            saveDraft(); renderCart(); toast("Order held on this tablet");
        } catch(Exception error) { error(error); }
    }
    private String heldLabel(JSONObject order) {
        JSONArray items = list(order, "items");
        String name = "Order";
        int units = 0;
        long total = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (i == 0) name = item.optString("name", "Order");
            units += item.optInt("quantity");
            total += lineTotal(item);
        }
        if (items.length() > 1) name = name + " +" + (items.length() - 1);
        String label = name + " · " + units + " · " + PosMath.money(total, order.optString("currency", "USD"));
        if (order.has("heldAt")) {
            SimpleDateFormat printer = new SimpleDateFormat("h:mm a", Locale.US);
            printer.setTimeZone(TimeZone.getTimeZone("Asia/Phnom_Penh"));
            label = label + " · " + printer.format(new java.util.Date(order.optLong("heldAt")));
        }
        return label;
    }
    private boolean resumeHeld(JSONArray orders, int index) {
        if (cart.length() > 0) { toast("Finish or clear this order before resuming"); return false; }
        JSONObject order = orders.optJSONObject(index);
        if (order == null) return false;
        String heldCurrency = order.optString("currency", "USD");
        if (!heldCurrency.equals(currency())) {
            toast("The store currency changed. Add the items again from the current menu.");
            return false;
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < orders.length(); i++) if (i != index) next.put(orders.optJSONObject(i));
        saveHeld(next);
        cart = list(order, "items");
        promotionId = order.optString("promotionId");
        draftCurrency = heldCurrency;
        saveDraft();
        renderCart();
        return true;
    }
    private void resumeOrder() {
        if(cart.length()>0) { toast("Finish or clear this order before resuming"); return; }
        JSONArray orders = heldOrders();
        if (orders.length() == 0) return;
        if (orders.length() == 1) { resumeHeld(orders, 0); return; }
        LinearLayout body = dialogBody();
        AlertDialog dialog = dialog("Held orders", body);
        for (int i = 0; i < orders.length(); i++) {
            final int index = i;
            MaterialButton choice = button(heldLabel(orders.optJSONObject(i)), false, () -> {
                if (resumeHeld(orders, index)) dialog.dismiss();
            });
            choice.setSingleLine(true);
            choice.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(choice, new LinearLayout.LayoutParams(-1, -2));
        }
        dialog.show();
    }

    private void checkout() {
        if(cart.length()==0 || busy || !draftCurrency.equals(currency())) return;
        if(!demo && (staff==null || shift==null)) { toast("Sign in and open a shift before taking payment"); return; }
        if(!demo && !authorizationValid()) {
            String notice = checkoutNotice();
            toast(notice == null ? "Checkout preparation expired. Reconnect and refresh the register." : notice);
            return;
        }
        String checkoutCurrency=currency();
        long checkoutTotal=total();
        LinearLayout body=dialogBody();
        body.addView(text("Amount due", 13, MUTED, false));
        body.addView(text(PosMath.money(checkoutTotal,checkoutCurrency),36,INK,true));
        body.addView(text(demo ? "Demo payment · No money or stock will be recorded on your server." : "Prices include tax. The receipt is saved on this tablet before the order clears.",14,MUTED,false));
        gap(body, 12);
        final boolean[] cashPay = {true};
        MaterialButton cash = button(tr("Cash"), false, () -> { });
        MaterialButton bakong = button(tr("Bakong"), false, () -> { });
        compact(cash);
        compact(bakong);
        LinearLayout methods = row();
        methods.addView(cash, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams bakongParams = new LinearLayout.LayoutParams(0, -2, 1);
        bakongParams.setMarginStart(dp(8));
        methods.addView(bakong, bakongParams);
        body.addView(methods);
        TextInputLayout tenderedField = field(body, tr("Cash received") + " (" + currency() + ")",
                PosMath.amount(checkoutTotal, checkoutCurrency), true);
        EditText tendered = tenderedField.getEditText();
        HorizontalScrollView quickCash = new HorizontalScrollView(this);
        quickCash.setHorizontalScrollBarEnabled(false);
        LinearLayout suggestions = row();
        suggestions.setPadding(0, 0, 0, dp(8));
        List<Long> quickAmounts = cashSuggestions(checkoutTotal, checkoutCurrency);
        List<MaterialButton> quickButtons = new ArrayList<>();
        boolean firstAmount = true;
        for (long suggestion : quickAmounts) {
            final long pick = suggestion;
            MaterialButton amountButton = button(pick == checkoutTotal ? "Exact" : PosMath.money(pick, checkoutCurrency), false, () ->
                    tendered.setText(PosMath.amount(pick, checkoutCurrency)));
            compact(amountButton);
            LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(-2, -2);
            if (!firstAmount) amountParams.setMarginStart(dp(8));
            firstAmount = false;
            suggestions.addView(amountButton, amountParams);
            quickButtons.add(amountButton);
        }
        quickCash.addView(suggestions);
        body.addView(quickCash);
        LinearLayout changeWell = column();
        TextView changeLabel = text(tr("Change"), 13, PAID, true);
        TextView change = text(PosMath.money(0, checkoutCurrency), 22, PAID, true);
        changeWell.addView(changeLabel);
        changeWell.addView(change);
        body.addView(changeWell);
        LinearLayout bakongPanel = column();
        bakongPanel.setBackground(shape(SOFT, 12, false));
        bakongPanel.setPadding(dp(14), dp(12), dp(14), dp(4));
        bakongPanel.addView(text(tr("Bakong"), 16, INK, true));
        gap(bakongPanel, 4);
        CheckBox confirmed = new CheckBox(this);
        confirmed.setText(tr("I verified the transfer in the merchant account"));
        confirmed.setMinHeight(dp(56));
        bakongPanel.addView(confirmed);
        bakongPanel.setVisibility(View.GONE);
        body.addView(bakongPanel);
        Runnable showMethod = () -> {
            paint(cash, cashPay[0] ? SOFT : PAPER, cashPay[0] ? ACCENT : LINE, cashPay[0] ? ACCENT : INK);
            paint(bakong, cashPay[0] ? PAPER : SOFT, cashPay[0] ? LINE : ACCENT, cashPay[0] ? INK : ACCENT);
            tenderedField.setVisibility(cashPay[0] ? View.VISIBLE : View.GONE);
            quickCash.setVisibility(cashPay[0] ? View.VISIBLE : View.GONE);
            changeWell.setVisibility(cashPay[0] ? View.VISIBLE : View.GONE);
            bakongPanel.setVisibility(cashPay[0] ? View.GONE : View.VISIBLE);
        };
        cash.setOnClickListener(v -> { cashPay[0] = true; showMethod.run(); });
        bakong.setOnClickListener(v -> { cashPay[0] = false; showMethod.run(); });
        showMethod.run();
        Runnable showChange = () -> {
            long received = -1;
            try { received = PosMath.cents(tendered.getText().toString(), checkoutCurrency); }
            catch (Exception ignored) { }
            for (int i = 0; i < quickButtons.size(); i++) {
                boolean selected = received == quickAmounts.get(i);
                paint(quickButtons.get(i), selected ? ACCENT : PAPER, selected ? ACCENT : LINE, selected ? Color.WHITE : INK);
            }
            if (received < 0) {
                changeWell.setBackground(shape(CANVAS, 12, false));
                changeWell.setPadding(dp(16), dp(12), dp(16), dp(12));
                changeLabel.setText("Enter cash received");
                changeLabel.setTextColor(MUTED);
                change.setText("—");
                change.setTextColor(MUTED);
                return;
            }
            boolean shortCash = received < checkoutTotal;
            changeWell.setBackground(shape(shortCash ? SOFT : SAGE, 12, false));
            changeWell.setPadding(dp(16), dp(12), dp(16), dp(12));
            changeLabel.setText(shortCash ? "Cash is below the total" : tr("Change"));
            changeLabel.setTextColor(shortCash ? ACCENT : PAID);
            change.setText(PosMath.money(Math.abs(received - checkoutTotal), checkoutCurrency));
            change.setTextColor(shortCash ? ACCENT : PAID);
        };
        tendered.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a) { }
            @Override public void onTextChanged(CharSequence s,int st,int b,int c) { showChange.run(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        showChange.run();
        AlertDialog dialog=dialog(tr("Take payment"),body);
        body.addView(button(demo ? tr("Complete demo sale") : tr("Complete payment"),true,() -> {
            try {
                if(!checkoutCurrency.equals(currency()) || checkoutTotal!=total()) {
                    dialog.dismiss();
                    throw new Exception("The order total changed. Review it before taking payment.");
                }
                long amount=cashPay[0] ? PosMath.cents(tendered.getText().toString(),currency()) : total();
                if(amount<total() || amount>2000000000L) throw new Exception("Check the payment amount");
                if(!cashPay[0] && !confirmed.isChecked()) throw new Exception("Verify that the Bakong transfer arrived first");
                JSONObject sale=buildSale(cashPay[0]?"Cash":"Bakong",amount);
                storage.saveSale(demo?"demo":server,sale,demo,draftKey());
                cart=new JSONArray(); promotionId=""; dialog.dismiss(); renderCart(); receipt(sale);
                if(!demo) sync(true);
            } catch(Exception error) { error(error); }
        })); dialog.show();
    }
    private java.util.List<Long> cashSuggestions(long total, String currency) {
        long[] steps = "KHR".equals(currency)
                ? new long[]{100000L, 500000L, 1000000L, 2000000L}
                : new long[]{100L, 500L, 1000L, 2000L};
        LinkedHashSet<Long> amounts = new LinkedHashSet<>();
        amounts.add(total);
        for (long step : steps) {
            long rounded = ((total + step - 1) / step) * step;
            if (rounded > total) amounts.add(rounded);
            if (amounts.size() == 4) break;
        }
        return new ArrayList<>(amounts);
    }

    private long isoMillis(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            java.util.Date instant = iso.parse(raw);
            return instant == null ? 0 : instant.getTime();
        } catch (Exception error) { return 0; }
    }

    private long checkoutInstant(String field) {
        if (authorization == null) return 0;
        JSONObject context = authorization.optJSONObject("context");
        return context == null ? 0 : isoMillis(context.optString(field));
    }

    private long storeNow() { return System.currentTimeMillis() + clockOffset; }

    private void learnStoreClock(JSONObject result, long savedAt) {
        JSONObject context = result == null ? null : result.optJSONObject("context");
        if (context == null || savedAt <= 0) return;
        long issued = isoMillis(context.optString("issuedAt"));
        if (issued == 0) return;
        long offset = issued - savedAt;
        if (clockKnown && clockOffset == offset) return;
        clockOffset = offset;
        clockKnown = true;
        rememberWorkspace();
    }

    private String clockNote() {
        if (!clockKnown || Math.abs(clockOffset) <= 2 * 60 * 1000L) return null;
        long minutes = Math.abs(clockOffset) / 60_000L;
        String span = minutes >= 24 * 60
                ? (minutes / (24 * 60) == 1 ? "1 day" : minutes / (24 * 60) + " days")
                : minutes >= 60 ? (minutes / 60 == 1 ? "1 hour" : minutes / 60 + " hours")
                : minutes + " min";
        return clockOffset > 0
                ? "Tablet clock is " + span + " behind. Sales use the store time."
                : "Tablet clock is " + span + " ahead. Sales use the store time.";
    }

    private boolean checkoutNeedsRefresh() {
        if (demo || staff == null || shift == null) return false;
        if (!clockKnown) {
            long issued = checkoutInstant("issuedAt");
            if (issued > 0 && issued - System.currentTimeMillis() > 2 * 60 * 1000L) return true;
        }
        return checkoutNotice() != null;
    }

    private String checkoutNotice() {
        if (demo || staff == null || shift == null) return null;
        long issued = checkoutInstant("issuedAt");
        long expires = checkoutInstant("expiresAt");
        long now = storeNow();
        if (!clockKnown && issued > 0 && issued - System.currentTimeMillis() > 2 * 60 * 1000L)
            return "The tablet clock is behind the store. Set the time before taking payment.";
        if (expires > 0 && now >= expires)
            return online() ? "Checkout expired. Refreshing the register." : "Checkout expired. Reconnect before taking payment.";
        if (expires > 0 && expires - now <= 30 * 60 * 1000L) {
            int minutes = (int) Math.max(1, (expires - now + 59_999L) / 60_000L);
            return "Checkout expires in " + minutes + " min.";
        }
        if (!authorizationValid())
            return online() ? "Checkout isn't ready yet." : "Checkout isn't ready. Reconnect before taking payment.";
        return null;
    }

    private boolean authorizationValid() {
        if(authorization==null) return false;
        JSONObject context=authorization.optJSONObject("context"); if(context==null || staff==null || shift==null) return false;
        try {
            long now = storeNow();
            long issued = isoMillis(context.optString("issuedAt"));
            long expires = isoMillis(context.optString("expiresAt"));
            return context.optString("staffId").equals(staffId()) && context.optString("shiftId").equals(shift.optString("id"))
                    && context.optString("currency","USD").equals(currency())
                    && issued > 0 && expires > 0 && now >= issued && now < expires;
        } catch(Exception error) { return false; }
    }
    private JSONObject buildSale(String method,long tendered) throws Exception {
        if(!demo && !authorizationValid()) throw new Exception("Reconnect to prepare checkout before taking payment");
        JSONObject context=demo ? obj("staffId","demo","staffName","Demo cashier","storeId","demo","shiftId","demo","taxRateBasisPoints",0) : authorization.getJSONObject("context");
        if(!demo) {
            JSONArray signedProducts=list(context,"products");
            for(int i=0;i<cart.length();i++) {
                JSONObject line=cart.getJSONObject(i), found=null;
                for(int j=0;j<signedProducts.length();j++) if(line.optString("productId").equals(signedProducts.getJSONObject(j).optString("id"))) found=signedProducts.getJSONObject(j);
                if("KHR".equals(currency()) && line.optLong("unitPrice")%100!=0) throw new Exception("Edit the item price to a whole riel amount");
                if(found==null || !found.optString("name").equals(line.optString("name"))) throw new Exception("An item changed. Refresh the menu and add it again.");
                JSONArray groups=list(found,"modifierGroups"), selected=list(line,"modifiers"); Set<String> used=new HashSet<>();
                for(int j=0;j<selected.length();j++) {
                    JSONObject choice=selected.getJSONObject(j); boolean matches=false;
                    if(!used.add(choice.optString("groupId"))) throw new Exception("Choose only one option per group");
                    for(int g=0;g<groups.length();g++) if(groups.getJSONObject(g).optString("id").equals(choice.optString("groupId"))) {
                        JSONArray options=list(groups.getJSONObject(g),"options");
                        for(int o=0;o<options.length();o++) {
                            JSONObject option=options.getJSONObject(o);
                            if(option.optString("id").equals(choice.optString("id")) && option.optString("name").equals(choice.optString("name")) && option.optLong("price")==choice.optLong("price")) matches=true;
                        }
                    }
                    if(!matches) throw new Exception("An option changed. Remove this item and add it again.");
                }
                for(int g=0;g<groups.length();g++) if(groups.getJSONObject(g).optBoolean("required") && !used.contains(groups.getJSONObject(g).optString("id"))) throw new Exception("Choose all required options");
            }
        }
        String id=UUID.randomUUID().toString();
        SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject sale=obj("id",id,"receiptNumber",(demo?"DEMO-":"L-")+id,"createdAt",iso.format(new Date(storeNow())),
                "staffId",context.optString("staffId"),"staffName",context.optString("staffName"),"storeId",context.optString("storeId"),"shiftId",context.optString("shiftId"),
                "items",new JSONArray(cart.toString()),"subtotal",subtotal(),"discount",discount(),"total",total(),
                "currency",currency(),"tax",PosMath.includedTax(total(),context.optInt("taxRateBasisPoints"),currency()),"tendered",tendered,"paymentMethod",method,
                "contextToken",demo?"demo":authorization.getString("token"),"status","completed");
        JSONObject promotion=promotion();
        if(promotion!=null) { sale.put("promotionId",promotionId); sale.put("promotionName",promotion.optString("name")); }
        sale.put("_pending",!demo); return sale;
    }
    private void receipt(JSONObject sale) { receipt(sale, false); }

    private void receipt(JSONObject sale, boolean canRefund) {
        LinearLayout frame = column();
        frame.setBackgroundColor(0xFFEBE5DA);
        frame.setPadding(dp(14), dp(6), dp(14), dp(8));
        LinearLayout paper = column();
        paper.setBackground(shape(0xFFFFFEF9, 2, false));
        paper.setElevation(dp(4));
        paper.setPadding(dp(22), dp(24), dp(22), dp(22));
        for (ReceiptPrintAdapter.Line line : slip(sale)) paper.addView(slipView(line));
        frame.addView(paper);
        gap(frame, 14);
        MaterialButton print = button(tr("Print receipt"), false, () -> printReceipt(sale));
        if (canRefund) actions(frame, print, button("Return sale", false, () -> refund(sale)));
        else actions(frame, print);
        ScrollView scroller = scroll(frame);
        scroller.setBackgroundColor(0xFFEBE5DA);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(tr("Receipt")).setView(scroller)
                .setPositiveButton(tr("Done"), null).create();
        dialog.setOnShowListener(v -> {
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                    Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
        });
        dialog.show();
    }

    private List<ReceiptPrintAdapter.Line> slip(JSONObject sale) {
        List<ReceiptPrintAdapter.Line> lines = new ArrayList<>();
        lines.add(ReceiptPrintAdapter.Line.center("SIGNAL COFFEE", true, false));
        lines.add(ReceiptPrintAdapter.Line.center(tr("Coffee & Goods"), true, false));
        lines.add(ReceiptPrintAdapter.Line.center(tr("Phnom Penh · Cambodia"), false, false));
        if (sale.optString("receiptNumber").startsWith("DEMO"))
            lines.add(ReceiptPrintAdapter.Line.center("DEMO RECEIPT · Not a real sale", true, true));
        markReturn(lines, sale);
        if (sale.optBoolean("_pending"))
            lines.add(ReceiptPrintAdapter.Line.center("Saved on this tablet · Awaiting server sync", false, true));
        if (!sale.optString("_error").isEmpty())
            lines.add(ReceiptPrintAdapter.Line.center(sale.optString("_error"), false, true));
        lines.add(ReceiptPrintAdapter.Line.dash());
        lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.META, sale.optString("receiptNumber"), receiptWhen(sale.optString("createdAt"))));
        lines.add(ReceiptPrintAdapter.Line.dash());
        JSONArray items = list(sale, "items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.ITEM,
                    item.optInt("quantity") + " × " + item.optString("name"), saleMoney(sale, lineTotal(item))));
            JSONArray modifiers = list(item, "modifiers");
            for (int j = 0; j < modifiers.length(); j++)
                lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.NOTE, modifiers.optJSONObject(j).optString("name"), ""));
            if (!item.optString("notes").isEmpty())
                lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.NOTE, item.optString("notes"), ""));
        }
        lines.add(ReceiptPrintAdapter.Line.dash());
        lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.AMOUNT, tr("Subtotal"), saleMoney(sale, sale.optLong("subtotal"))));
        if (sale.optLong("discount") != 0) {
            String offer = sale.optString("promotionName");
            if (offer.isEmpty()) offer = tr("Offer");
            lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.AMOUNT, offer, "−" + saleMoney(sale, sale.optLong("discount"))));
        }
        lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.AMOUNT, tr("VAT included"), saleMoney(sale, sale.optLong("tax"))));
        lines.add(ReceiptPrintAdapter.Line.rule());
        lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.TOTAL, tr("Total"), saleMoney(sale, sale.optLong("total"))));
        lines.add(ReceiptPrintAdapter.Line.rule());
        String method = sale.optString("paymentMethod");
        if (method.isEmpty()) method = "Cash";
        lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.AMOUNT, tr(pretty(method)), saleMoney(sale, sale.optLong("tendered"))));
        if ("cash".equalsIgnoreCase(method))
            lines.add(ReceiptPrintAdapter.Line.pair(ReceiptPrintAdapter.Line.AMOUNT, tr("Change"),
                    saleMoney(sale, Math.max(0, sale.optLong("tendered") - sale.optLong("total")))));
        if (!sale.optString("staffName").isEmpty()) {
            lines.add(ReceiptPrintAdapter.Line.space());
            lines.add(ReceiptPrintAdapter.Line.center(tr("Served by") + " " + sale.optString("staffName"), false, false));
        }
        lines.add(ReceiptPrintAdapter.Line.space());
        lines.add(ReceiptPrintAdapter.Line.center(tr("Made with care. Thanks for stopping by."), false, false));
        return lines;
    }

    private View slipView(ReceiptPrintAdapter.Line line) {
        if (line.kind == ReceiptPrintAdapter.Line.DASH) return dashedRule();
        if (line.kind == ReceiptPrintAdapter.Line.RULE) {
            View rule = new View(this);
            rule.setBackgroundColor(INK);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
            params.topMargin = dp(6);
            params.bottomMargin = dp(6);
            rule.setLayoutParams(params);
            return rule;
        }
        if (line.kind == ReceiptPrintAdapter.Line.SPACE) {
            View gap = new View(this);
            gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(10)));
            return gap;
        }
        if (line.kind == ReceiptPrintAdapter.Line.CENTER) {
            int size = "SIGNAL COFFEE".equals(line.left) ? 12 : line.accent ? 13 : line.strong ? 20 : 13;
            TextView view = text(line.left, size, line.accent ? ACCENT : line.strong ? INK : MUTED, line.strong || line.accent);
            view.setGravity(Gravity.CENTER);
            if ("SIGNAL COFFEE".equals(line.left) && !khmer) view.setLetterSpacing(0.14f);
            view.setPadding(0, dp(1), 0, dp(1));
            return view;
        }
        boolean item = line.kind == ReceiptPrintAdapter.Line.ITEM;
        boolean total = line.kind == ReceiptPrintAdapter.Line.TOTAL;
        boolean note = line.kind == ReceiptPrintAdapter.Line.NOTE;
        int size = total ? 16 : note || line.kind == ReceiptPrintAdapter.Line.META ? 12 : 14;
        int color = total || item ? INK : MUTED;
        LinearLayout row = row();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(total ? 2 : 3), 0, dp(total ? 2 : 3));
        TextView label = text(line.left, size, color, total);
        label.setMaxLines(4);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (!line.right.isEmpty()) {
            TextView amount = text(line.right, size, color, item || total);
            amount.setGravity(Gravity.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMarginStart(dp(12));
            row.addView(amount, params);
        }
        return row;
    }

    private View dashedRule() {
        View line = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                paint.setColor(LINE);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1, dp(1)));
                paint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(4)}, 0));
            }
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawLine(0, getHeight() / 2f, getWidth(), getHeight() / 2f, paint);
            }
        };
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(16)));
        return line;
    }

    private void markReturn(List<ReceiptPrintAdapter.Line> lines, JSONObject sale) {
        JSONObject refund = sale.optJSONObject("refund");
        String status = sale.optString("status");
        if (refund == null && !"voided".equals(status) && !"refunded".equals(status)) return;
        boolean voided = refund != null ? "void".equals(refund.optString("kind")) : "voided".equals(status);
        String reason = refund == null ? "" : refund.optString("reason");
        String banner = voided ? "VOIDED" : "REFUNDED";
        lines.add(ReceiptPrintAdapter.Line.center(reason.isEmpty() ? banner : banner + " · " + reason, true, true));
    }

    private String receiptWhen(String raw) {
        if (raw.isEmpty()) return "";
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date date = parser.parse(raw);
                if (date == null) continue;
                SimpleDateFormat printer = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US);
                printer.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Phnom_Penh"));
                return printer.format(date);
            } catch (Exception ignored) { }
        }
        return raw;
    }

    private void printReceipt(JSONObject sale) {
        android.print.PrintManager manager = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
        if (manager != null) manager.print("Signal POS receipt", new ReceiptPrintAdapter(this, slip(sale)),
                new android.print.PrintAttributes.Builder().build());
    }

    private interface Job { JSONObject run() throws Exception; }
    private interface Success { void accept(JSONObject result) throws Exception; }
    private static final class TabWait {
        final Success apply;
        final Runnable onMiss;
        TabWait(Success apply, Runnable onMiss) { this.apply = apply; this.onMiss = onMiss; }
    }

    private boolean online() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager == null) return true;
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (SecurityException error) { return true; }
    }

    private void watchNetwork() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    if (destroyed || demo || staff == null) return;
                    nextSyncAt = 0;
                    sync(false);
                    warmTabs();
                    showStatus();
                });
            }
            @Override public void onLost(Network network) {
                runOnUiThread(() -> { if (!destroyed) showStatus(); });
            }
        };
        try { manager.registerDefaultNetworkCallback(networkCallback); }
        catch (RuntimeException error) { networkCallback = null; }
    }

    private String cacheKey(String path) { return server + "\n" + path; }

    private PosStorage.Snapshot cached(String path) {
        if (server.isEmpty() || storage == null) return null;
        String key = cacheKey(path);
        PosStorage.Snapshot hit = memory.get(key);
        if (hit != null) return hit;
        PosStorage.Snapshot stored = storage.snapshot(key);
        if (stored != null) memory.put(key, stored);
        return stored;
    }

    private void remember(String path, JSONObject body) {
        if (server.isEmpty() || storage == null || body == null) return;
        PosStorage.Snapshot snap = new PosStorage.Snapshot(body, System.currentTimeMillis());
        memory.put(cacheKey(path), snap);
        try { storage.saveSnapshot(cacheKey(path), body); } catch (Exception ignored) { }
    }

    private void invalidateTab(String path) {
        if (server.isEmpty()) return;
        String key = cacheKey(path);
        tabGeneration.put(key, tabGeneration.getOrDefault(key, 0) + 1);
        memory.remove(key);
        if (storage != null) storage.deleteSnapshot(key);
    }

    private void clearWorkspace() {
        if (!server.isEmpty() && storage != null) storage.deleteSnapshots(server + "\n");
        String prefix = server + "\n";
        memory.keySet().removeIf(key -> key.startsWith(prefix));
        tabWaiters.clear();
        flightGeneration.clear();
    }

    private boolean restoreWorkspace() {
        if (server.isEmpty() || storage == null) return false;
        PosStorage.Snapshot snap = storage.snapshot(server + "\nworkspace");
        if (snap == null) return false;
        JSONObject savedStaff = snap.body.optJSONObject("staff");
        JSONObject savedCatalog = snap.body.optJSONObject("catalog");
        if (savedStaff == null || savedCatalog == null) return false;
        staff = savedStaff;
        catalog = savedCatalog;
        shift = snap.body.optJSONObject("shift");
        authorization = snap.body.optJSONObject("authorization");
        clockOffset = snap.body.optLong("clockOffset");
        clockKnown = snap.body.optBoolean("clockKnown");
        demo = false;
        PosStorage.Snapshot context = cached("/api/orders/context");
        if (context != null) learnStoreClock(context.body, context.updated);
        api = new PosApi(this, server);
        return true;
    }

    private void rememberWorkspace() {
        if (demo || server.isEmpty() || staff == null || storage == null) return;
        try {
            JSONObject body = new JSONObject();
            body.put("staff", staff);
            body.put("catalog", catalog);
            if (shift != null) body.put("shift", shift);
            if (authorization != null) body.put("authorization", authorization);
            body.put("clockOffset", clockOffset);
            body.put("clockKnown", clockKnown);
            storage.saveSnapshot(server + "\nworkspace", body);
        } catch (Exception ignored) { }
    }

    private void readTab(String path, boolean force, Success apply, Runnable onMiss) {
        readTab(path, force, TAB_FRESH, apply, onMiss);
    }

    private void readTab(String path, boolean force, long maxAge, Success apply, Runnable onMiss) {
        if (demo || api == null || server.isEmpty()) {
            if (onMiss != null) onMiss.run();
            return;
        }
        String key = cacheKey(path);
        PosStorage.Snapshot snap = cached(path);
        boolean fresh = snap != null && !force && System.currentTimeMillis() - snap.updated < maxAge;
        if (snap != null) {
            if ("/api/orders/context".equals(path)) learnStoreClock(snap.body, snap.updated);
            try { apply.accept(snap.body); } catch (Exception error) { error(error); }
        }
        if (fresh) return;
        if (!online()) {
            if (snap == null && onMiss != null) onMiss.run();
            return;
        }
        int generation = tabGeneration.getOrDefault(key, 0);
        List<TabWait> waiting = tabWaiters.get(key);
        Integer flight = flightGeneration.get(key);
        boolean start = waiting == null || flight == null || flight != generation;
        if (start) {
            waiting = new ArrayList<>();
            tabWaiters.put(key, waiting);
            flightGeneration.put(key, generation);
        }
        waiting.add(new TabWait(apply, onMiss));
        if (!start) return;
        final String endpoint = server;
        final PosApi transport = api;
        final List<TabWait> owned = waiting;
        io.execute(() -> {
            JSONObject result = null;
            Exception failure = null;
            try { result = transport.request(path, null); }
            catch (Exception error) { failure = error; }
            final JSONObject loaded = result;
            final Exception problem = failure;
            runOnUiThread(() -> finishTab(endpoint, path, key, generation, owned, loaded, problem));
        });
    }

    private void finishTab(String endpoint, String path, String key, int generation, List<TabWait> owned, JSONObject result, Exception failure) {
        if (destroyed || !endpoint.equals(server)) return;
        if (tabGeneration.getOrDefault(key, 0) != generation) return;
        if (owned != tabWaiters.get(key)) return;
        tabWaiters.remove(key);
        flightGeneration.remove(key);
        if (result != null) {
            remember(path, result);
            if ("/api/orders/context".equals(path)) {
                PosStorage.Snapshot saved = cached(path);
                if (saved != null) learnStoreClock(saved.body, saved.updated);
            }
            for (TabWait waiter : owned) {
                try { waiter.apply.accept(result); } catch (Exception error) { error(error); }
            }
            return;
        }
        if (cached(path) != null) return;
        for (TabWait waiter : owned) if (waiter.onMiss != null) waiter.onMiss.run();
        if (failure != null && owned.isEmpty()) error(failure);
    }

    private void warmTabs() {
        if (demo || staff == null || api == null || server.isEmpty()) return;
        warm("/api/catalog", TAB_FRESH, this::applyCatalog);
        warm("/api/shifts", TAB_FRESH, data -> applyShift(data, false));
        warm("/api/orders", TAB_FRESH, data -> { });
        if (shift != null) warm("/api/orders/context", CONTEXT_FRESH, this::applyContext);
        if (!manager()) return;
        warm("/api/manage/catalog", TAB_FRESH, data -> { });
        warm("/api/manage/inventory", TAB_FRESH, data -> { });
        warm("/api/manage/recipes", TAB_FRESH, data -> { });
        warm("/api/manage/staff", TAB_FRESH, data -> { });
        warm("/api/reports?currency=" + Uri.encode(currency()), TAB_FRESH, data -> { });
    }

    private void warm(String path, long maxAge, Success apply) {
        PosStorage.Snapshot snap = cached(path);
        if (snap != null && System.currentTimeMillis() - snap.updated < maxAge) return;
        readTab(path, false, maxAge, apply, null);
    }

    private void refreshMenu() {
        invalidateTab("/api/manage/catalog");
        invalidateTab("/api/catalog");
        invalidateTab("/api/orders/context");
        readTab("/api/catalog", true, data -> {
            applyCatalog(data);
            if (shift != null) readTab("/api/orders/context", true, this::applyContext, null);
        }, null);
    }

    private void applyCatalog(JSONObject data) {
        if (data == null || destroyed || demo || catalog.toString().equals(data.toString())) return;
        catalog = data;
        rememberWorkspace();
        if ("Register".equals(tab)) render();
    }

    private void applyShift(JSONObject data, boolean openIfMissing) {
        if (data == null || destroyed || demo) return;
        JSONObject next = data.optJSONObject("shift");
        boolean changed = shift == null ? next != null : next == null || !shift.toString().equals(next.toString());
        connecting = false;
        if (!changed) {
            if (next != null && !authorizationValid()) refreshContext(false);
            return;
        }
        shift = next;
        rememberWorkspace();
        if (next == null) {
            if (openIfMissing) tab = "Shift";
            if (openIfMissing || "Shift".equals(tab)) render();
            return;
        }
        if ("Shift".equals(tab) || "Register".equals(tab)) render();
        if (!authorizationValid()) refreshContext(false);
    }

    private void applyContext(JSONObject result) {
        if (result == null || destroyed || staff == null || shift == null) return;
        JSONObject context = result.optJSONObject("context");
        if (context != null && !shift.optString("id").equals(context.optString("shiftId"))) return;
        lastContextRefresh = System.currentTimeMillis();
        if (authorization != null && authorization.toString().equals(result.toString())) {
            showStatus();
            return;
        }
        authorization = result;
        if (context == null) { rememberWorkspace(); showStatus(); return; }
        JSONObject next = obj("products", list(context, "products"), "promotions", list(context, "promotions"), "currency", context.optString("currency", "USD"));
        boolean changed = !catalog.toString().equals(next.toString());
        catalog = next;
        rememberWorkspace();
        if (changed && "Register".equals(tab)) render();
        else showStatus();
    }

    private void request(Job job, Success success) { request(job, success, null); }
    private void request(Job job, Success success, Runnable onFailure) {
        if(busy) { toast("Please wait for the current request"); return; }
        busy=true; showStatus();
        posts.execute(() -> {
            try { JSONObject result=job.run(); runOnUiThread(() -> {
                if(destroyed) return; busy=false;
                try { success.accept(result); } catch(Exception error) { error(error); }
                showStatus();
            }); }
            catch(Exception failure) { runOnUiThread(() -> {
                if(destroyed) return; busy=false; error(failure);
                if (onFailure != null) onFailure.run();
                showStatus();
            }); }
        });
    }
    private void account() {
        if (connecting) { toast("Connecting…"); return; }
        if (awaitingStore()) { retryStore(); return; }
        if(demo) { connectionDialog(); return; }
        if(staff==null) { login(); return; }
        new MaterialAlertDialogBuilder(this).setTitle(staff.optString("name"))
                .setItems(new String[]{"Refresh checkout preparation","Sync pending receipts","Sign in again","Sign out"},(d,which) -> {
                    if(which==0) refreshContext(true);
                    if(which==1) sync(true);
                    if(which==2) { login(); return; }
                    if(which==3) {
                        if(storage.pendingCount()>0) { toast("Sync pending receipts before signing out"); return; }
                        request(() -> api.request("/api/auth/logout",obj()),result -> {
                            saveDraft(); clearWorkspace(); staff=null; shift=null; authorization=null; cart=new JSONArray(); promotionId="";
                            catalog = new JSONObject();
                            login();
                        });
                    }
                }).show();
    }
    private void connectionDialog() {
        if(storage.pendingCount()>0) { toast("Sync pending receipts before changing server"); return; }
        LinearLayout body=dialogBody();
        body.addView(text("The interface runs natively on this tablet. Enter the backend address only to share staff, catalog, shifts and sales with your store.",15,MUTED,false));
        EditText address=input(body,"Backend URL",server.isEmpty() ? DEFAULT_BACKEND : server,false);
        AlertDialog dialog=dialog("Connect your store",body);
        body.addView(button("Connect backend",true,() -> {
            try {
                Uri uri=Uri.parse(address.getText().toString().trim());
                if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                        || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath()==null || uri.getPath().isEmpty() || uri.getPath().equals("/")))
                    throw new Exception("Enter an HTTPS backend origin, for example https://pos.example.com");
                String base="https://"+uri.getEncodedAuthority(); dialog.dismiss(); connect(base);
            } catch(Exception error) { error(error); }
        })); dialog.show();
    }
    private void connect(String base) {
        PosApi candidate = new PosApi(this, base);
        boolean known = !demo && staff != null && base.equals(server);
        Job job = () -> candidate.request("/api/auth/session", null);
        Success success = result -> {
            saveDraft();
            JSONObject nextStaff = result.optJSONObject("staff");
            boolean same = known && nextStaff != null && staff.optString("id").equals(nextStaff.optString("id"));
            server = base;
            api = candidate;
            demo = false;
            getPreferences(MODE_PRIVATE).edit().putString("native-server", server).apply();
            staff = nextStaff;
            if (!same) {
                authorization = null;
                shift = null;
                cart = new JSONArray();
                promotionId = "";
                category = "All";
                search = "";
            }
            if (staff == null) {
                connecting = false;
                clearWorkspace();
                catalog = new JSONObject();
                render();
                login();
            } else {
                restoreDraft();
                rememberWorkspace();
                loadRegister();
            }
        };
        Runnable failure = () -> {
            connecting = false;
            if (known) showStatus();
            else render();
        };
        if (known) {
            io.execute(() -> {
                try {
                    JSONObject result = job.run();
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        try { success.accept(result); } catch (Exception error) { error(error); }
                    });
                } catch (Exception ignored) {
                    runOnUiThread(() -> { if (!destroyed) failure.run(); });
                }
            });
        } else if (!busy) request(job, success, failure);
    }
    private void retryStore() {
        if (server.isEmpty()) { connectionDialog(); return; }
        if (busy) { toast("Please wait for the current request"); return; }
        connecting = true;
        render();
        connect(server);
    }
    private void login() {
        LinearLayout body=dialogBody();
        body.addView(text(tr("Enter your staff name and PIN."),14,MUTED,false));
        EditText name=input(body,tr("Staff name"),staff==null?"":staff.optString("name"),false);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        EditText pin=input(body,tr("4-digit PIN"),"",false);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        AlertDialog dialog=dialog(tr("Staff sign in"),body);
        body.addView(button(tr("Unlock register"),true,() -> {
            if(name.getText().toString().trim().isEmpty()) { name.setError("Enter your staff name"); return; }
            if(!pin.getText().toString().matches("[0-9]{4}")) { pin.setError("Enter four digits"); return; }
            JSONObject credentials=obj("name",name.getText().toString().trim(),"pin",pin.getText().toString());
            request(() -> api.request("/api/auth/login",credentials), signed -> {
                saveDraft(); staff=signed.getJSONObject("staff"); pin.setText(""); dialog.dismiss(); restoreDraft(); loadRegister();
            });
        })); dialog.show();
    }
    private void loadRegister() {
        readTab("/api/catalog", true, this::applyCatalog, null);
        readTab("/api/shifts", true, data -> applyShift(data, true), () -> {
            connecting = false;
            if (shift == null) tab = "Shift";
            render();
        });
        warmTabs();
    }
    private void refreshContext(boolean showFailure) {
        if (demo || staff == null || shift == null) return;
        // A failed refresh leaves the previous signed authorization valid until its original expiry.
        final boolean[] told = {false};
        readTab("/api/orders/context", showFailure, CONTEXT_FRESH, result -> {
            applyContext(result);
            if (showFailure && !told[0]) { told[0] = true; toast("Checkout ready"); }
        }, () -> { if (showFailure && !online()) toast("Checkout preparation stayed on this tablet."); });
    }
    private void sync(boolean manual) {
        if (demo || staff == null || syncing || (!manual && System.currentTimeMillis() < nextSyncAt)) return;
        if (!online()) {
            if (manual) toast("Saved on this tablet. Receipts sync when the connection returns.");
            return;
        }
        syncing = true;
        final String owner = staffId(), endpoint = server;
        final PosApi transport = api;
        posts.execute(() -> {
            boolean failed = false;
            int sent = 0;
            try {
                JSONArray pending = storage.receipts(endpoint, owner, true);
                for (int i = 0; i < pending.length(); i++) {
                    JSONObject sale = pending.getJSONObject(i);
                    sale.remove("_pending");
                    sale.remove("_error");
                    try {
                        transport.request("/api/orders/sync", sale);
                        storage.acknowledge(sale.getString("id"));
                        sent++;
                    } catch (Exception error) {
                        storage.syncError(sale.getString("id"), error.getMessage() == null ? "Sync failed" : error.getMessage());
                        failed = true;
                        break;
                    }
                }
            } catch (Exception error) { failed = true; }
            final boolean failure = failed;
            final int delivered = sent;
            runOnUiThread(() -> {
                syncing = false;
                syncAttempts = failure ? Math.min(syncAttempts + 1, 6) : 0;
                nextSyncAt = System.currentTimeMillis() + (failure ? Math.min(60000, 1000L << syncAttempts) : 5000);
                if (destroyed) return;
                showStatus();
                if (delivered > 0) {
                    invalidateTab("/api/orders");
                    invalidateTab("/api/shifts");
                    readTab("/api/shifts", true, data -> applyShift(data, false), null);
                    readTab("/api/orders", false, data -> { }, null);
                }
                if (manual) toast(failure ? "Receipt stays saved. Open Sales to review the sync error." : "Receipts synced");
            });
        });
    }

    private LinearLayout content(String title, String subtitle) {
        return content(title, subtitle, null);
    }

    private LinearLayout content(String title, String subtitle, View action) {
        LinearLayout body = column();
        int inset = getResources().getConfiguration().screenWidthDp >= 720 ? 28 : 14;
        body.setPadding(dp(inset), dp(16), dp(inset), dp(24));
        LinearLayout titles = column();
        titles.addView(text(title, 26, INK, true));
        if (!subtitle.isEmpty()) titles.addView(text(subtitle, 14, MUTED, false));
        if (action == null) body.addView(titles);
        else {
            LinearLayout head = row();
            head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            actionParams.setMarginStart(dp(12));
            head.addView(action, actionParams);
            body.addView(head);
        }
        gap(body, 14);
        page.addView(scroll(body), new LinearLayout.LayoutParams(-1, -1));
        return body;
    }

    private ImageButton iconButton(int icon, String description, Runnable action) {
        ImageButton view = new ImageButton(this);
        view.setImageResource(icon);
        view.setColorFilter(INK);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setBackground(shape(PAPER, 12, true));
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setMinimumWidth(dp(48));
        view.setMinimumHeight(dp(48));
        view.setContentDescription(description);
        view.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x24B95438), null, shape(Color.WHITE, 12, false)));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private LinearLayout panel(LinearLayout parent, int padding) {
        LinearLayout surface = column();
        surface.setBackground(shape(PAPER, 14, true));
        surface.setClipToOutline(true);
        surface.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(surface, params);
        return surface;
    }

    private void actions(LinearLayout parent, View... buttons) {
        HorizontalScrollView toolbar = new HorizontalScrollView(this);
        toolbar.setFillViewport(true);
        LinearLayout group = row();
        for (View button : buttons) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            if (group.getChildCount() > 0) params.setMarginStart(dp(8));
            group.addView(button, params);
        }
        toolbar.addView(group);
        parent.addView(toolbar);
    }

    private void emptyState(LinearLayout parent, String title, String message, View... buttons) {
        LinearLayout card = panel(parent, 18);
        card.addView(text(title, 19, INK, true));
        if (!message.isEmpty()) {
            gap(card, 4);
            card.addView(text(message, 14, MUTED, false));
        }
        if (buttons.length > 0) {
            gap(card, 8);
            actions(card, buttons);
        }
    }

    private void pressable(View view, Runnable action) {
        view.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x24B95438), null, shape(Color.WHITE, 0, false)));
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
    }

    private final class DataTable {
        private final LinearLayout body;
        private final int[] weights;
        private final boolean interactive;

        DataTable(LinearLayout parent, int[] weights, String... headings) {
            this(parent, weights, false, headings);
        }

        DataTable(LinearLayout parent, int[] weights, boolean interactive, String... headings) {
            this.weights = weights;
            this.interactive = interactive;
            body = panel(parent, 0);
            addRow(null, null, true, headings);
        }

        void row(String... values) { addRow(null, null, false, values); }
        void actionRow(String actionLabel, Runnable action, String... values) {
            addRow(actionLabel, action, false, values);
        }

        void empty(String message) {
            rule(body);
            TextView note = text(message, 14, MUTED, false);
            note.setGravity(Gravity.CENTER);
            note.setPadding(dp(16), dp(20), dp(16), dp(20));
            body.addView(note);
        }

        private void addRow(String actionLabel, Runnable action, boolean header, String... values) {
            if (!header) rule(body);
            LinearLayout line = MainActivity.this.row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPaddingRelative(dp(16), dp(header ? 10 : 12), dp(action == null ? 16 : 8), dp(header ? 10 : 12));
            line.setMinimumHeight(dp(header ? 42 : 64));
            if (header) line.setBackgroundColor(TRACK);
            for (int i = 0; i < values.length; i++) {
                boolean end = i > 0;
                int[] tone = !header && i == values.length - 1 ? statusColors(values[i]) : null;
                View cell = tone == null ? ledgerCell(values[i], header, end, i == 0 || i == values.length - 1) : statusChip(values[i], tone);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weights[i]);
                if (i > 0) params.setMarginStart(dp(12));
                line.addView(cell, params);
            }
            if (action != null) {
                TextView chevron = text("›", 22, MUTED, true);
                chevron.setGravity(Gravity.CENTER);
                chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                line.addView(chevron, new LinearLayout.LayoutParams(dp(22), -2));
                FrameLayout target = new FrameLayout(MainActivity.this);
                target.addView(line, new FrameLayout.LayoutParams(-1, -2));
                target.setContentDescription(String.join(", ", values) + ", " + actionLabel);
                target.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                pressable(target, action);
                body.addView(target);
            } else body.addView(line);
        }
    }

    private TextView ledgerCell(String value, boolean header, boolean end, boolean emphasize) {
        TextView cell = text(value, header ? 12 : 14, header ? MUTED : INK, header || emphasize);
        cell.setMaxLines(header ? 2 : value.contains("\n") ? 4 : 1);
        cell.setEllipsize(TextUtils.TruncateAt.END);
        cell.setGravity((end ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
        if (header) {
            ViewCompat.setAccessibilityHeading(cell, true);
            if (!khmer) cell.setLetterSpacing(0.04f);
        }
        return cell;
    }

    private View statusChip(String value, int[] tone) {
        TextView chip = text(value, 12, tone[1], true);
        chip.setBackground(shape(tone[0], 8, false));
        chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        chip.setMaxWidth(dp(132));
        chip.setMaxLines(2);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER);
        LinearLayout wrap = row();
        wrap.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        wrap.addView(chip);
        return wrap;
    }

    private int[] statusColors(String value) {
        if (value == null || value.contains("\n")) return null;
        if (value.equals("Awaiting sync") || value.equals("Sync failed") || value.equals(tr("Low stock")) || value.equals("Inactive"))
            return new int[]{SOFT, ACCENT};
        if (value.equals("Completed") || value.equals(tr("Active")) || value.equals("In stock"))
            return new int[]{SAGE, PAID};
        if (value.equals("Draft") || value.equals("Refunded") || value.equals("Void") || value.equals("Voided"))
            return new int[]{TRACK, MUTED};
        return null;
    }

    private void showSales() {
        LinearLayout entries = column();
        final int[] source = {0};
        Runnable showDevice = () -> {
            try { drawSales(entries, storage.receipts(demo ? "demo" : server, staffId(), false), false); }
            catch (Exception failure) { error(failure); }
        };
        final Runnable[] paint = {() -> { }};
        final boolean[] forceOrders = {false};
        Runnable showServer = () -> {
            boolean refresh = forceOrders[0];
            forceOrders[0] = false;
            if (cached("/api/orders") == null) {
                replaceResults(entries);
                salesSkeleton(entries);
            }
            readTab("/api/orders", refresh, result -> {
                if (source[0] != 1 || !entries.isAttachedToWindow()) return;
                drawSales(entries, list(result, "sales"), true);
            }, () -> {
                if (source[0] != 1 || !entries.isAttachedToWindow() || cached("/api/orders") != null) return;
                source[0] = 0;
                paint[0].run();
                showDevice.run();
            });
        };
        ImageButton refresh = (!demo && staff != null) || connecting
                ? iconButton(R.drawable.ic_refresh, "Sync now", () -> {
                    if (connecting) { toast("Connecting…"); return; }
                    sync(true);
                    if (source[0] == 0) showDevice.run();
                    else { forceOrders[0] = true; showServer.run(); }
                })
                : null;
        LinearLayout body = content("Sales & receipts", "Receipts on this device are available offline.", refresh);
        if ((!demo && staff != null) || connecting) {
            MaterialButton onDevice = button(tr("On this device"), false, () -> { });
            MaterialButton onServer = button(tr("Server history"), false, () -> { });
            Runnable mark = () -> {
                sourceChoice(onDevice, source[0] == 0);
                sourceChoice(onServer, source[0] == 1);
            };
            paint[0] = mark;
            onDevice.setOnClickListener(v -> {
                if (connecting) return;
                source[0] = 0;
                paint[0].run();
                showDevice.run();
            });
            onServer.setOnClickListener(v -> {
                if (connecting) return;
                source[0] = 1;
                paint[0].run();
                showServer.run();
            });
            paint[0].run();
            LinearLayout choice = row();
            choice.setBackground(shape(TRACK, 12, true));
            choice.setPadding(dp(4), dp(4), dp(4), dp(4));
            LinearLayout.LayoutParams choiceButton = new LinearLayout.LayoutParams(-2, dp(40));
            onDevice.setMinHeight(dp(40));
            onServer.setMinHeight(dp(40));
            choice.addView(onDevice, choiceButton);
            LinearLayout.LayoutParams serverParams = new LinearLayout.LayoutParams(-2, dp(40));
            serverParams.setMarginStart(dp(2));
            choice.addView(onServer, serverParams);
            LinearLayout bar = row();
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton export = button(tr("Export backup"), false, () -> {
                if (connecting) { toast("Connecting…"); return; }
                exportBackup();
            });
            LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(-2, -2);
            exportParams.setMarginStart(dp(10));
            bar.addView(export, exportParams);
            body.addView(bar);
        } else {
            actions(body, button(tr("Export backup"), false, this::exportBackup));
        }
        gap(body, 12);
        body.addView(entries);
        if (connecting) salesSkeleton(entries);
        else showDevice.run();
    }

    private void sourceChoice(MaterialButton view, boolean selected) {
        view.setMinHeight(dp(40));
        view.setMinimumWidth(0);
        view.setInsetTop(0);
        view.setInsetBottom(0);
        view.setCornerRadius(dp(8));
        view.setStrokeWidth(0);
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? ACCENT : Color.TRANSPARENT));
        view.setTextColor(selected ? Color.WHITE : MUTED);
    }

    private void drawSales(LinearLayout body, JSONArray sales, boolean remote) {
        body.setContentDescription(null);
        replaceResults(body);
        body.addView(text(remote ? "Server history" : "On this device", 19, INK, true));
        gap(body, 8);
        DataTable table = new DataTable(body, new int[]{6, 2, 3}, true, tr("Receipt"), tr("Total"), "Status");
        if (sales.length() == 0) {
            table.empty(remote ? "Completed store sales will appear here." : "Complete a sale to see its receipt here.");
            return;
        }
        for (int i = 0; i < sales.length(); i++) {
            JSONObject sale = sales.optJSONObject(i);
            String reference = sale.optString("receiptNumber");
            if (reference.length() > 22) {
                String prefix = reference.startsWith("DEMO-") ? "DEMO-" : reference.startsWith("L-") ? "L-" : "";
                reference = prefix + "…" + reference.substring(reference.length() - 8);
            }
            String timestamp = saleWhen(sale.optString("createdAt"));
            String method = sale.optString("paymentMethod");
            String detail = method.isEmpty() ? timestamp : timestamp + " · " + tr(method);
            String status = sale.optBoolean("_pending") ? "Awaiting sync" : pretty(sale.optString("status", "completed"));
            String reason = sale.optString("_error");
            if (!reason.isEmpty()) {
                status = "Sync failed";
                detail = detail + "\n" + reason;
            }
            table.actionRow(tr("View receipt"), () -> receipt(sale, remote && "completed".equals(sale.optString("status"))),
                    reference + "\n" + detail, saleMoney(sale, sale.optLong("total")), status);
        }
    }

    private void salesSkeleton(LinearLayout parent) {
        parent.setContentDescription("Loading receipts");
        parent.addView(bone(LINE, 16, 160));
        gap(parent, 8);
        LinearLayout table = panel(parent, 0);
        LinearLayout header = row();
        header.setBackgroundColor(TRACK);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        header.setMinimumHeight(dp(42));
        salesBone(header, 6, 12);
        salesBone(header, 2, 12);
        salesBone(header, 3, 12);
        table.addView(header);
        for (int i = 0; i < 5; i++) {
            rule(table);
            LinearLayout line = row();
            line.setPadding(dp(16), dp(14), dp(16), dp(14));
            line.setMinimumHeight(dp(64));
            salesBone(line, 6, 14);
            salesBone(line, 2, 14);
            salesBone(line, 3, 14);
            table.addView(line);
        }
        pulse(table);
    }

    private void salesBone(LinearLayout line, int weight, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(height), weight);
        if (line.getChildCount() > 0) params.setMarginStart(dp(12));
        line.addView(bone(LINE, height, 0), params);
    }

    private void exportBackup() {
        try {
            JSONArray receipts=storage.receipts(demo?"demo":server,staffId(),false);
            exportFile("signal-native-sales.json", "application/json",
                    obj("format","signal-pos-native-v1","server",demo?"demo":server,"receipts",receipts).toString(2), "Backup saved");
        } catch(Exception error) { exportData=null; error(error); }
    }
    private void exportFile(String filename, String type, String data, String success) {
        exportData = data;
        exportSuccess = success;
        exportLauncher.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type).putExtra(Intent.EXTRA_TITLE,filename));
    }

    private String saleWhen(String raw) {
        if (raw.isEmpty()) return "";
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                java.util.Date date = parser.parse(raw);
                if (date == null) continue;
                TimeZone phnom = TimeZone.getTimeZone("Asia/Phnom_Penh");
                SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                day.setTimeZone(phnom);
                boolean today = day.format(date).equals(day.format(new java.util.Date(storeNow())));
                SimpleDateFormat printer = new SimpleDateFormat(today ? "h:mm a" : "d MMM, h:mm a", Locale.US);
                printer.setTimeZone(phnom);
                return printer.format(date);
            } catch (Exception ignored) { }
        }
        return raw;
    }

    private void refund(JSONObject sale) {
        if (shift == null) { toast("Open a shift before returning a sale."); return; }
        boolean sameShift = shift.optString("id").equals(sale.optString("shiftId"));
        final boolean[] voiding = {sameShift};
        String method = sale.optString("paymentMethod");
        if (method.isEmpty()) method = "Cash";
        boolean cash = "Cash".equalsIgnoreCase(method);
        LinearLayout body = dialogBody();
        body.addView(text(saleMoney(sale, sale.optLong("total")), 32, INK, true));
        body.addView(text(tr(method) + (sameShift ? "" : " · earlier shift"), 14, MUTED, false));
        gap(body, 12);
        MaterialButton voidChoice = sameShift ? button("Void", false, () -> { }) : null;
        MaterialButton refundChoice = button("Refund", false, () -> { });
        compact(refundChoice);
        if (voidChoice != null) {
            compact(voidChoice);
            LinearLayout choice = row();
            choice.addView(voidChoice, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams refundParams = new LinearLayout.LayoutParams(0, -2, 1);
            refundParams.setMarginStart(dp(8));
            choice.addView(refundChoice, refundParams);
            body.addView(choice);
            gap(body, 8);
        }
        TextView note = text("", 14, MUTED, false);
        body.addView(note);
        gap(body, 8);
        EditText reason = input(body, tr("Reason"), "", false);
        EditText pin = manager() ? null : input(body, "Manager PIN", "", false);
        if (pin != null) {
            pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        }
        MaterialButton confirm = button("Refund sale", true, () -> { });
        Runnable explain = () -> {
            if (voidChoice != null) {
                paint(voidChoice, voiding[0] ? SOFT : PAPER, voiding[0] ? ACCENT : LINE, voiding[0] ? ACCENT : INK);
                paint(refundChoice, voiding[0] ? PAPER : SOFT, voiding[0] ? LINE : ACCENT, voiding[0] ? INK : ACCENT);
            }
            note.setText(voiding[0]
                    ? (cash ? "Void removes this sale from the open shift and takes the cash back out of the drawer."
                            : "Void removes this sale from the open shift. Bakong does not change the cash drawer.")
                    : (cash ? "Refund returns the cash. The drawer total drops by this amount."
                            : "Refund this Bakong payment in the merchant account. The cash drawer does not change."));
            confirm.setText(voiding[0] ? "Void sale" : "Refund sale");
        };
        if (voidChoice != null) voidChoice.setOnClickListener(v -> { voiding[0] = true; explain.run(); });
        refundChoice.setOnClickListener(v -> { voiding[0] = false; explain.run(); });
        explain.run();
        AlertDialog dialog = dialog("Return " + sale.optString("receiptNumber"), body);
        String key = UUID.randomUUID().toString();
        confirm.setOnClickListener(v -> {
            if (reason.getText().toString().trim().length() < 2) { reason.setError("Enter a reason"); return; }
            if (pin != null && !pin.getText().toString().matches("[0-9]{4}")) { pin.setError("Enter the manager PIN"); return; }
            JSONObject payload = obj("orderId", sale.optString("id"), "kind", voiding[0] ? "void" : "refund",
                    "reason", reason.getText().toString().trim(), "idempotencyKey", key);
            if (pin != null) try { payload.put("managerPin", pin.getText().toString()); } catch (Exception ignored) { }
            request(() -> api.request("/api/orders/refund", payload), result -> {
                dialog.dismiss();
                invalidateTab("/api/orders");
                invalidateTab("/api/shifts");
                readTab("/api/shifts", true, data -> applyShift(data, false), null);
                if ("Sales".equals(tab)) render();
                receipt(result.getJSONObject("sale"));
            });
        });
        body.addView(confirm);
        dialog.show();
    }
    private static final class LocalDrawer {
        long cash;
        long bakong;
        int sales;
    }

    private LocalDrawer localDrawer() {
        LocalDrawer local = new LocalDrawer();
        if (demo || shift == null || storage == null) return local;
        try {
            JSONArray pending = storage.receipts(server, staffId(), true);
            String shiftId = shift.optString("id");
            String drawerCurrency = shift.optString("currency", "USD");
            for (int i = 0; i < pending.length(); i++) {
                JSONObject sale = pending.optJSONObject(i);
                if (sale == null || !shiftId.equals(sale.optString("shiftId"))) continue;
                if (!drawerCurrency.equals(sale.optString("currency", "USD"))) continue;
                if (!"completed".equals(sale.optString("status", "completed"))) continue;
                String method = sale.optString("paymentMethod");
                if ("Cash".equals(method)) local.cash += sale.optLong("total");
                else if ("Bakong".equals(method)) local.bakong += sale.optLong("total");
                local.sales++;
            }
        } catch (Exception ignored) { }
        return local;
    }

    private JSONObject shiftOnScreen() throws Exception {
        JSONObject shown = copy(shift);
        LocalDrawer local = localDrawer();
        if (local.sales == 0) return shown;
        shown.put("cashSales", shown.optLong("cashSales") + local.cash);
        shown.put("bakongSales", shown.optLong("bakongSales") + local.bakong);
        shown.put("expectedCash", shown.optLong("expectedCash") + local.cash);
        shown.put("saleCount", shown.optInt("saleCount") + local.sales);
        shown.put("_localSales", local.sales);
        return shown;
    }

    private void showShift() {
        if (connecting) {
            LinearLayout body = content("Register shift", "Track the cash drawer and close out the shift.");
            shiftSkeleton(body);
            return;
        }
        if (demo) {
            LinearLayout body = content("Register shift", "Track the cash drawer and close out the shift.");
            if (awaitingStore()) emptyState(body, "The store didn't answer", "Retry the saved store before opening a shift.", button("Retry", true, this::retryStore));
            else emptyState(body, "Demo register", "Connect your store to open and close shifts.", button("Connect store", true, this::connectionDialog));
            return;
        }
        if (staff == null) {
            LinearLayout body = content("Register shift", "Track the cash drawer and close out the shift.");
            emptyState(body, "Sign in to manage shifts", "", button("Sign in", true, this::login));
            return;
        }
        if (shift == null) {
            LinearLayout body = content("Register shift", "Track the cash drawer and close out the shift.");
            emptyState(body, "No open shift", "Enter the starting cash to open the register.", button(tr("Open shift"), true, () -> {
                if (busy) { toast("Please wait for the current request"); return; }
                shiftAmount(false);
            }));
            return;
        }
        JSONObject shown;
        try { shown = shiftOnScreen(); }
        catch (Exception failure) { shown = shift; }
        ImageButton refresh = iconButton(R.drawable.ic_refresh, "Refresh totals", this::loadRegister);
        LinearLayout body = content("Register shift", "Track the cash drawer and close out the shift.", refresh);
        int screen = getResources().getConfiguration().screenWidthDp;
        int contentWidth = screen - (screen >= 720 ? 56 : 28);
        boolean wide = screen >= 760;
        boolean snug = contentWidth >= 400;
        LinearLayout stage = wide ? row() : column();
        if (wide) {
            stage.setGravity(Gravity.TOP);
            stage.setBaselineAligned(false);
        }
        body.addView(stage, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout summary = panel(stage, 0);
        LinearLayout well = column();
        well.setBackground(shape(SAGE, 12, false));
        well.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout labelRow = row();
        labelRow.addView(text(tr("Expected cash"), 13, PAID, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView currency = text(shift.optString("currency", "USD"), 12, Color.WHITE, true);
        currency.setBackground(shape(PAID, 8, false));
        currency.setPadding(dp(10), dp(4), dp(10), dp(4));
        labelRow.addView(currency);
        well.addView(labelRow);
        well.addView(text(saleMoney(shown, shown.optLong("expectedCash")), 34, PAID, true));
        String meta = shiftMeta(shown);
        if (shown.optInt("_localSales") > 0) {
            String here = shown.optInt("_localSales") == 1 ? "1 saved on this tablet" : shown.optInt("_localSales") + " saved on this tablet";
            meta = meta.isEmpty() ? here : meta + " · " + here;
        }
        if (!meta.isEmpty()) well.addView(text(meta, 13, PAID, false));
        LinearLayout.LayoutParams wellParams = new LinearLayout.LayoutParams(-1, -2);
        wellParams.setMargins(dp(14), dp(14), dp(14), dp(14));
        summary.addView(well, wellParams);
        LinearLayout tiles = row();
        tiles.setPadding(dp(14), 0, dp(14), dp(14));
        tiles.addView(shiftTile(tr("Cash sales"), saleMoney(shown, shown.optLong("cashSales")), INK), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams bakongParams = new LinearLayout.LayoutParams(0, -2, 1);
        bakongParams.setMarginStart(dp(10));
        tiles.addView(shiftTile(tr("Bakong"), saleMoney(shown, shown.optLong("bakongSales")), ACCENT), bakongParams);
        summary.addView(tiles);
        rule(summary);
        LinearLayout footer = row();
        footer.setPadding(dp(14), dp(12), dp(14), dp(14));
        footer.addView(button(tr("Close shift"), true, () -> shiftAmount(true)));
        summary.addView(footer);

        LinearLayout activity = panel(stage, 0);
        moneyLine(activity, tr("Shift activity"), tr("Amount"), true, MUTED, snug);
        for (String key : new String[]{"openingCash", "cashSales", "bakongSales", "cashRefunds", "cashPaidOut", "bakongRefunds"}) {
            long cents = shown.optLong(key);
            boolean outflow = key.endsWith("Refunds") || "cashPaidOut".equals(key);
            int color = cents == 0 ? MUTED : outflow ? ACCENT : INK;
            moneyLine(activity, tr(pretty(key)), saleMoney(shown, cents), false, color, snug);
        }
        LinearLayout.LayoutParams activityParams = (LinearLayout.LayoutParams) activity.getLayoutParams();
        if (wide) {
            LinearLayout.LayoutParams summaryParams = (LinearLayout.LayoutParams) summary.getLayoutParams();
            summaryParams.width = 0;
            summaryParams.weight = 1;
            summaryParams.bottomMargin = 0;
            summaryParams.setMarginEnd(dp(12));
            activityParams.bottomMargin = 0;
        }
        if (snug) {
            activityParams.width = LinearLayout.LayoutParams.WRAP_CONTENT;
            activityParams.weight = 0;
        }
    }

    private LinearLayout shiftTile(String label, String amount, int mark) {
        LinearLayout tile = column();
        tile.setBackground(shape(CANVAS, 12, false));
        tile.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout caption = row();
        caption.addView(swatch(mark, 0));
        caption.addView(text(label, 12, MUTED, true));
        tile.addView(caption);
        tile.addView(text(amount, 18, INK, true));
        return tile;
    }

    private void moneyLine(LinearLayout card, String label, String amount, boolean header, int amountColor, boolean snug) {
        if (!header) rule(card);
        LinearLayout line = row();
        line.setPadding(dp(16), dp(header ? 10 : 12), dp(16), dp(header ? 10 : 12));
        line.setMinimumHeight(dp(header ? 42 : 52));
        if (header) line.setBackgroundColor(TRACK);
        TextView name = ledgerCell(label, header, false, !header);
        TextView value = ledgerCell(amount, header, true, true);
        if (!header) value.setTextColor(amountColor);
        line.addView(name, snug ? new LinearLayout.LayoutParams(dp(168), -2) : new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(snug ? 148 : 124), -2);
        valueParams.setMarginStart(dp(16));
        line.addView(value, valueParams);
        card.addView(line, new LinearLayout.LayoutParams(snug ? -2 : -1, -2));
    }

    private void shiftSkeleton(LinearLayout parent) {
        parent.setContentDescription("Loading shift");
        boolean wide = getResources().getConfiguration().screenWidthDp >= 760;
        LinearLayout stage = wide ? row() : column();
        if (wide) {
            stage.setGravity(Gravity.TOP);
            stage.setBaselineAligned(false);
        }
        parent.addView(stage, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout summary = panel(stage, 0);
        LinearLayout well = column();
        well.setBackground(shape(SAGE, 12, false));
        well.setPadding(dp(16), dp(14), dp(16), dp(14));
        well.addView(bone(PAPER, 12, 120));
        gap(well, 10);
        well.addView(bone(PAPER, 28, 180));
        gap(well, 8);
        well.addView(bone(PAPER, 12, 160));
        LinearLayout.LayoutParams wellParams = new LinearLayout.LayoutParams(-1, -2);
        wellParams.setMargins(dp(14), dp(14), dp(14), dp(14));
        summary.addView(well, wellParams);
        LinearLayout tiles = row();
        tiles.setPadding(dp(14), 0, dp(14), dp(14));
        tiles.addView(skeletonTile(), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMarginStart(dp(10));
        tiles.addView(skeletonTile(), second);
        summary.addView(tiles);
        LinearLayout activity = panel(stage, 0);
        View header = new View(this);
        header.setBackgroundColor(TRACK);
        activity.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));
        for (int i = 0; i < 4; i++) {
            rule(activity);
            LinearLayout line = row();
            line.setPadding(dp(16), dp(12), dp(16), dp(12));
            line.setMinimumHeight(dp(52));
            line.addView(bone(LINE, 12, 148));
            line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            line.addView(bone(LINE, 12, 88));
            activity.addView(line);
        }
        if (wide) {
            LinearLayout.LayoutParams summaryParams = (LinearLayout.LayoutParams) summary.getLayoutParams();
            summaryParams.width = 0;
            summaryParams.weight = 1;
            summaryParams.bottomMargin = 0;
            summaryParams.setMarginEnd(dp(12));
            LinearLayout.LayoutParams activityParams = (LinearLayout.LayoutParams) activity.getLayoutParams();
            activityParams.width = LinearLayout.LayoutParams.WRAP_CONTENT;
            activityParams.weight = 0;
            activityParams.bottomMargin = 0;
        }
        pulse(summary);
        pulse(activity);
    }

    private String shiftMeta(JSONObject shift) {
        List<String> parts = new ArrayList<>();
        String register = shift.optString("registerName");
        if (!register.isEmpty()) parts.add(register);
        String openedBy = shift.optString("openedByName");
        if (!openedBy.isEmpty()) parts.add(openedBy);
        if (shift.has("saleCount")) {
            int sales = shift.optInt("saleCount");
            parts.add(sales == 1 ? "1 sale" : sales + " sales");
        }
        String opened = shiftOpened(shift);
        if (!opened.isEmpty()) parts.add(opened);
        return TextUtils.join(" · ", parts);
    }

    private String shiftOpened(JSONObject shift) {
        String raw = shift.optString("openedAt");
        if (raw.isEmpty()) return "";
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat(pattern, Locale.US);
                parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date date = parser.parse(raw);
                if (date == null) continue;
                java.text.SimpleDateFormat printer = new java.text.SimpleDateFormat("h:mm a", Locale.US);
                printer.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Phnom_Penh"));
                return printer.format(date);
            } catch (Exception ignored) { }
        }
        return "";
    }
    private void drawerBreakdown(LinearLayout body, JSONObject shown) {
        moneyLine(body, tr("Sales"), String.valueOf(shown.optInt("saleCount")), INK, 15, false);
        moneyLine(body, tr("Cash sales"), saleMoney(shown, shown.optLong("cashSales")), INK, 15, false);
        moneyLine(body, tr("Bakong"), saleMoney(shown, shown.optLong("bakongSales")), ACCENT, 15, false);
        moneyLine(body, tr("Opening cash"), saleMoney(shown, shown.optLong("openingCash")), INK, 15, false);
        if (shown.optLong("cashRefunds") != 0)
            moneyLine(body, tr("Cash refunds"), saleMoney(shown, shown.optLong("cashRefunds")), ACCENT, 15, false);
        if (shown.optLong("cashPaidOut") != 0)
            moneyLine(body, tr("Cash paid out"), saleMoney(shown, shown.optLong("cashPaidOut")), ACCENT, 15, false);
        moneyLine(body, tr("Expected cash"), saleMoney(shown, shown.optLong("expectedCash")), PAID, 16, true);
    }

    private void showVariance(TextView view, long diff, String currency) {
        int color = diff == 0 ? PAID : diff > 0 ? ACCENT : 0xFFC4332B;
        String amount = PosMath.money(Math.abs(diff), currency);
        view.setText(diff > 0 ? "+" + amount : diff < 0 ? "-" + amount : amount);
        view.setTextColor(color);
    }

    private void paintCount(LinearLayout well, TextView amount, long diff, String currency) {
        int ground = diff == 0 ? SAGE : diff > 0 ? SOFT : 0xFFF8E4E1;
        well.setBackground(shape(ground, 12, false));
        well.setPadding(dp(16), dp(12), dp(16), dp(14));
        showVariance(amount, diff, currency);
    }

    private void shiftAmount(boolean close) {
        if (connecting) { toast("Connecting…"); return; }
        if(close && (storage.pendingCount()>0 || cart.length()>0)) { toast("Finish this order and sync all pending receipts before closing"); return; }
        String shiftCurrency=close?shift.optString("currency","USD"):currency();
        final JSONObject shown;
        if (close) {
            JSONObject copy;
            try { copy = shiftOnScreen(); }
            catch (Exception failure) { copy = shift; }
            shown = copy;
        } else shown = shift;
        final long expected = close ? shown.optLong("expectedCash") : 0;
        LinearLayout body=dialogBody();
        if (close) {
            drawerBreakdown(body, shown);
            gap(body, 8);
        }
        EditText amount=input(body,(close?"Counted cash":"Opening cash")+" ("+shiftCurrency+")",PosMath.amount(expected,shiftCurrency),true);
        final List<MaterialButton> floatButtons = new ArrayList<>();
        final List<Long> floats = new ArrayList<>();
        if (!close) {
            HorizontalScrollView scroller = new HorizontalScrollView(this);
            scroller.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = row();
            chips.setPadding(0, 0, 0, dp(8));
            boolean firstFloat = true;
            for (long preset : openingFloats(shiftCurrency)) {
                final long pick = preset;
                MaterialButton chip = button(PosMath.money(pick, shiftCurrency), false, () ->
                        amount.setText(PosMath.amount(pick, shiftCurrency)));
                compact(chip);
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
                if (!firstFloat) chipParams.setMarginStart(dp(8));
                firstFloat = false;
                chips.addView(chip, chipParams);
                floatButtons.add(chip);
                floats.add(pick);
            }
            scroller.addView(chips);
            body.addView(scroller);
        }
        if (close) {
            LinearLayout well = column();
            well.addView(text(tr("Variance"), 13, MUTED, true));
            TextView variance = text("", 28, PAID, true);
            well.addView(variance);
            LinearLayout.LayoutParams wellParams = new LinearLayout.LayoutParams(-1, -2);
            wellParams.topMargin = dp(4);
            body.addView(well, wellParams);
            Runnable update = () -> {
                try { paintCount(well, variance, PosMath.cents(amount.getText().toString(), shiftCurrency) - expected, shiftCurrency); }
                catch (Exception ignored) {
                    well.setBackground(shape(CANVAS, 12, false));
                    well.setPadding(dp(16), dp(12), dp(16), dp(14));
                    variance.setText("—");
                    variance.setTextColor(MUTED);
                }
            };
            amount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
                @Override public void afterTextChanged(Editable value) { }
            });
            update.run();
        }
        AlertDialog dialog=dialog(close?tr("Close shift"):tr("Open shift"),body);
        MaterialButton confirm = button(close?tr("Close shift"):tr("Open shift"),true,() -> { });
        if (!close) {
            Runnable showFloat = () -> {
                long counted = -1;
                try { counted = PosMath.cents(amount.getText().toString(), shiftCurrency); }
                catch (Exception ignored) { }
                for (int i = 0; i < floatButtons.size(); i++) {
                    boolean selected = counted == floats.get(i);
                    paint(floatButtons.get(i), selected ? ACCENT : PAPER, selected ? ACCENT : LINE, selected ? Color.WHITE : INK);
                }
                confirm.setText(counted < 0 ? tr("Open shift") : tr("Open shift") + "  " + PosMath.money(counted, shiftCurrency));
            };
            amount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { showFloat.run(); }
                @Override public void afterTextChanged(Editable value) { }
            });
            showFloat.run();
        }
        confirm.setOnClickListener(v -> {
            try {
                long cents=PosMath.cents(amount.getText().toString(),shiftCurrency);
                JSONObject payload=obj(close?"actualCash":"openingCash",cents,"currency",shiftCurrency);
                request(() -> api.request(close?"/api/shifts/close":"/api/shifts",payload),result -> {
                    shift=close?null:result.optJSONObject("shift"); authorization=null; dialog.dismiss();
                    invalidateTab("/api/shifts");
                    rememberWorkspace();
                    render();
                    if(close) {
                        JSONObject closed=result.getJSONObject("shift");
                        LinearLayout summary=dialogBody();
                        drawerBreakdown(summary, closed);
                        gap(summary, 8);
                        moneyLine(summary, "Counted cash", saleMoney(closed, closed.optLong("actualCash")), INK, 16, true);
                        LinearLayout resultWell = column();
                        resultWell.addView(text(tr("Variance"), 13, MUTED, true));
                        TextView resultVariance = text("", 28, PAID, true);
                        resultWell.addView(resultVariance);
                        paintCount(resultWell, resultVariance, closed.optLong("variance"), closed.optString("currency", shiftCurrency));
                        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(-1, -2);
                        resultParams.topMargin = dp(8);
                        summary.addView(resultWell, resultParams);
                        dialog(tr("Shift closed"),summary).show();
                    } else refreshContext(true);
                });
            } catch(Exception error) { error(error); }
        });
        body.addView(confirm);
        dialog.show();
    }

    private long[] openingFloats(String currency) {
        return "KHR".equals(currency)
                ? new long[]{0L, 2_000_000L, 5_000_000L, 10_000_000L}
                : new long[]{0L, 2_000L, 5_000L, 10_000L};
    }

    private void showSettings() {
        LinearLayout body = content("Tablet settings", "Store connection and display preferences.");
        boolean wide = getResources().getConfiguration().screenWidthDp >= 840;
        LinearLayout top = wide ? row() : column();
        if (wide) top.setGravity(Gravity.TOP);
        body.addView(top);

        LinearLayout connection = panel(top, 0);
        LinearLayout connectionBody = column();
        connectionBody.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout connectionTitle = row();
        connectionTitle.setGravity(Gravity.CENTER_VERTICAL);
        connectionTitle.addView(text("Store connection", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        connectionTitle.addView(settingsChip());
        connectionBody.addView(connectionTitle);
        gap(connectionBody, 10);
        boolean linked = !server.isEmpty();
        connectionBody.addView(text(linked ? server : "Demo mode", 16, INK, true));
        gap(connectionBody, 4);
        connectionBody.addView(text(settingsConnectionDetail(), 14, MUTED, false));
        connection.addView(connectionBody);
        rule(connection);
        LinearLayout connectionActions = row();
        connectionActions.setPadding(dp(14), dp(12), dp(14), dp(14));
        connectionActions.setGravity(Gravity.CENTER_VERTICAL);
        if (!linked) connectionActions.addView(settingsAction("Connect store", true, this::connectionDialog));
        else {
            connectionActions.addView(settingsAction("Change server", true, this::connectionDialog));
            MaterialButton second = settingsAction(staff == null ? "Sign in" : "Refresh menu & shift", false,
                    staff == null ? this::login : this::loadRegister);
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-2, -2);
            secondParams.setMarginStart(dp(8));
            connectionActions.addView(second, secondParams);
        }
        connection.addView(connectionActions);

        LinearLayout display = panel(top, 0);
        LinearLayout displayBody = column();
        displayBody.setPadding(dp(18), dp(16), dp(18), dp(16));
        displayBody.addView(text(tr("Language"), 19, INK, true));
        gap(displayBody, 4);
        displayBody.addView(text("The register follows this language.", 14, MUTED, false));
        gap(displayBody, 12);
        LinearLayout languages = row();
        languages.setBackground(shape(TRACK, 12, true));
        languages.setPadding(dp(4), dp(4), dp(4), dp(4));
        MaterialButton english = button("English", false, () -> chooseLanguage(false));
        MaterialButton khmerButton = button("ខ្មែរ", false, () -> chooseLanguage(true));
        sourceChoice(english, !khmer);
        sourceChoice(khmerButton, khmer);
        english.setMinimumWidth(dp(108));
        khmerButton.setMinimumWidth(dp(108));
        languages.addView(english);
        LinearLayout.LayoutParams khmerParams = new LinearLayout.LayoutParams(-2, -2);
        khmerParams.setMarginStart(dp(2));
        languages.addView(khmerButton, khmerParams);
        displayBody.addView(languages, new LinearLayout.LayoutParams(-2, -2));
        display.addView(displayBody);

        if (wide) {
            LinearLayout.LayoutParams left = (LinearLayout.LayoutParams) connection.getLayoutParams();
            left.width = 0;
            left.weight = 1.3f;
            left.setMarginEnd(dp(12));
            LinearLayout.LayoutParams right = (LinearLayout.LayoutParams) display.getLayoutParams();
            right.width = 0;
            right.weight = 1f;
        }

        int pending = storage.pendingCount();
        LinearLayout receipts = panel(body, 0);
        LinearLayout receiptBody = column();
        receiptBody.setPadding(dp(18), dp(16), dp(18), dp(16));
        receiptBody.addView(text("Receipt storage", 19, INK, true));
        gap(receiptBody, 8);
        receiptBody.addView(text(pending == 0 ? "Every receipt on this tablet is synced."
                : pending == 1 ? "1 receipt is waiting to sync."
                : pending + " receipts are waiting to sync.", 16, INK, true));
        gap(receiptBody, 4);
        receiptBody.addView(text("Sync or export your receipts before clearing app data.", 14, MUTED, false));
        receipts.addView(receiptBody);
        rule(receipts);
        LinearLayout receiptActions = row();
        receiptActions.setPadding(dp(14), dp(12), dp(14), dp(14));
        receiptActions.addView(settingsAction(tr("Export backup"), false, this::exportBackup));
        receipts.addView(receiptActions);
    }

    private void chooseLanguage(boolean useKhmer) {
        if (khmer == useKhmer) return;
        khmer = useKhmer;
        getPreferences(MODE_PRIVATE).edit().putBoolean("native-khmer", khmer).apply();
        render();
    }

    private TextView settingsChip() {
        String notice = checkoutNotice();
        boolean calm = !demo && !connecting && !busy && online() && storage.pendingCount() == 0 && notice == null && clockNote() == null;
        String label = connecting || busy ? "Connecting…"
                : awaitingStore() ? "No answer"
                : server.isEmpty() ? "Demo"
                : notice != null ? "Checkout"
                : !online() ? "Offline"
                : storage.pendingCount() > 0 ? "Awaiting sync"
                : "Connected";
        TextView chip = text(label, 12, calm ? PAID : ACCENT, true);
        chip.setBackground(shape(calm ? SAGE : SOFT, 8, false));
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        return chip;
    }

    private String settingsConnectionDetail() {
        if (connecting) return "Checking the saved store connection.";
        if (awaitingStore()) return "The saved store didn't answer. Retry before taking an order.";
        if (server.isEmpty()) return "Connect a store backend to share staff, catalog, shifts, and sales.";
        if (!online()) {
            String notice = checkoutNotice();
            if (notice != null) return notice;
            return "The register is using saved data. Receipts sync when the connection returns.";
        }
        if (staff == null) return "Sign in to open the register.";
        String notice = checkoutNotice();
        if (notice != null) return notice;
        String who = "Signed in as " + staff.optString("name") + " · " + pretty(staff.optString("role"));
        String clock = clockNote();
        return clock == null ? who : who + " · " + clock;
    }

    private MaterialButton settingsAction(String label, boolean primary, Runnable action) {
        MaterialButton view = button(label, primary, action);
        compact(view);
        return view;
    }
    private String pretty(String value) {
        if(value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).replaceAll("([A-Z])"," $1");
    }

    private void showReports() {
        if (demo || staff == null || !manager()) {
            LinearLayout body = content("Sales reports", "Store time · VAT included");
            if (awaitingStore()) emptyState(body, "The store didn't answer", "Retry the saved store before viewing reports.", button("Retry", true, this::retryStore));
            else emptyState(body, "Reports unavailable", demo ? "Connect your store to view sales reports." : "Sign in as a manager or owner.");
            return;
        }
        LinearLayout filters = column();
        filters.setBackground(shape(PAPER, 14, true));
        filters.setClipToOutline(true);
        filters.setPadding(dp(16), dp(14), dp(16), dp(6));
        EditText start = filterDate();
        EditText end = filterDate();
        String[] ranges = {"Today", "Yesterday", "Last 7 days", "Custom"};
        List<String> rangeLabels = new ArrayList<>();
        for (String range : ranges) rangeLabels.add(tr(range));
        Spinner period = filterSpinner(rangeLabels, 0);
        Spinner reportCurrency = filterSpinner(Arrays.asList("KHR", "USD"), "KHR".equals(currency()) ? 0 : 1);
        boolean wideFilters = getResources().getConfiguration().screenWidthDp >= 900;
        if (wideFilters) {
            filterRow(filters,
                    filterSlot(tr("Period"), period, true),
                    filterSlot(tr("From"), start, false, true),
                    filterSlot(tr("To"), end, false, true),
                    filterSlot(tr("Currency"), reportCurrency, true));
        } else {
            filterRow(filters, filterSlot(tr("Period"), period, true), filterSlot(tr("Currency"), reportCurrency, true));
            filterRow(filters, filterSlot(tr("From"), start, false, true), filterSlot(tr("To"), end, false, true));
        }
        LinearLayout results = column();
        final boolean[] quiet = {false};
        final int[] selectedRange = {0};
        final boolean[] forceReport = {false};
        Runnable load = () -> {
            boolean refresh = forceReport[0];
            forceReport[0] = false;
            String query = "?currency=" + reportCurrency.getSelectedItem();
            if (start.length() != 0 || end.length() != 0) query += "&start=" + Uri.encode(start.getText().toString()) + "&end=" + Uri.encode(end.getText().toString());
            String path = "/api/reports" + query;
            if (cached(path) == null) {
                replaceResults(results);
                reportSkeleton(results);
            }
            readTab(path, refresh, response -> {
                if (!results.isAttachedToWindow()) return;
                replaceResults(results);
                JSONObject report = response.getJSONObject("report");
                quiet[0] = true;
                start.setText(report.getString("start"));
                end.setText(report.getString("end"));
                start.setTag(report.getString("today"));
                quiet[0] = false;
                MaterialButton export = button(tr("Export CSV"), false, () -> {
                    try {
                        exportFile("signal-report-" + report.optString("currency") + "-" + report.optString("start") + "-" + report.optString("end") + ".csv",
                                "text/csv", ReportCsv.format(report), "Report saved");
                    } catch (Exception failure) { error(failure); }
                });
                renderReport(results, report, export);
            }, () -> {
                if (!results.isAttachedToWindow() || cached(path) != null) return;
                replaceResults(results);
                results.setContentDescription(null);
                results.addView(text("The report didn't load. Refresh to try again.", 14, MUTED, false));
            });
        };
        ImageButton loadReport = iconButton(R.drawable.ic_refresh, "Load report", () -> {
            forceReport[0] = true;
            load.run();
        });
        TextWatcher markCustom = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int startIndex, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int startIndex, int before, int count) {
                if (quiet[0] || period.getSelectedItemPosition() == 3) return;
                quiet[0] = true;
                period.setSelection(3);
                selectedRange[0] = 3;
                quiet[0] = false;
            }
            @Override public void afterTextChanged(Editable value) { }
        };
        start.addTextChangedListener(markCustom);
        end.addTextChangedListener(markCustom);
        start.setOnClickListener(v -> pickReportDate(start, end, true, load));
        end.setOnClickListener(v -> pickReportDate(end, start, false, load));
        period.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (quiet[0] || position == selectedRange[0]) return;
                if (position == 3) { selectedRange[0] = 3; return; }
                String today = (String) start.getTag();
                if (position != 0 && today == null) {
                    toast("Load today's report to get the store date");
                    quiet[0] = true;
                    period.setSelection(selectedRange[0]);
                    quiet[0] = false;
                    return;
                }
                quiet[0] = true;
                if (position == 0) {
                    if (today == null) { start.setText(""); end.setText(""); }
                    else { start.setText(today); end.setText(today); }
                } else if (position == 1) {
                    String day = reportDateOffset(today, -1);
                    start.setText(day);
                    end.setText(day);
                } else {
                    start.setText(reportDateOffset(today, -6));
                    end.setText(today);
                }
                selectedRange[0] = position;
                quiet[0] = false;
                load.run();
            }
        });
        LinearLayout body = content("Sales reports", "Store time · VAT included", loadReport);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(-1, -2);
        filterParams.bottomMargin = dp(12);
        body.addView(filters, filterParams);
        body.addView(results);
        load.run();
    }

    private void filterRow(LinearLayout parent, View... cells) {
        LinearLayout line = row();
        line.setGravity(Gravity.TOP);
        for (int i = 0; i < cells.length; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.setMarginStart(dp(10));
            line.addView(cells[i], params);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(10);
        parent.addView(line, rowParams);
    }

    private LinearLayout filterSlot(String label, View control, boolean dropdown) {
        return filterSlot(label, control, dropdown, false);
    }

    private LinearLayout filterSlot(String label, View control, boolean dropdown, boolean calendar) {
        LinearLayout slot = column();
        slot.addView(text(label, 11, MUTED, true));
        LinearLayout box = row();
        box.setBackground(shape(CANVAS, 10, true));
        box.setMinimumHeight(dp(48));
        box.setPadding(dp(12), 0, dp(10), 0);
        box.addView(control, new LinearLayout.LayoutParams(0, -2, 1));
        if (dropdown) {
            box.addView(text("▾", 14, MUTED, true));
            if (control instanceof Spinner) box.setOnClickListener(v -> ((Spinner) control).performClick());
        } else if (calendar) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_calendar);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            box.setOnClickListener(v -> control.performClick());
        }
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, -2);
        boxParams.topMargin = dp(4);
        slot.addView(box, boxParams);
        return slot;
    }

    private EditText filterDate() {
        EditText edit = new EditText(this);
        edit.setBackground(null);
        edit.setHint("Select date");
        edit.setHintTextColor(MUTED);
        edit.setTextColor(INK);
        edit.setTextSize(15);
        edit.setSingleLine(true);
        edit.setFocusable(false);
        edit.setFocusableInTouchMode(false);
        edit.setCursorVisible(false);
        edit.setClickable(true);
        edit.setPadding(0, dp(8), 0, dp(8));
        edit.setMinHeight(dp(40));
        return edit;
    }

    private void pickReportDate(EditText field, EditText other, boolean pickingStart, Runnable reload) {
        int year;
        int month;
        int day;
        String current = field.getText().toString();
        String fallback = current;
        if (!fallback.matches("\\d{4}-\\d{2}-\\d{2}")) fallback = other.getText().toString();
        if (!fallback.matches("\\d{4}-\\d{2}-\\d{2}") && field.getTag() instanceof String) fallback = (String) field.getTag();
        if (!fallback.matches("\\d{4}-\\d{2}-\\d{2}") && other.getTag() instanceof String) fallback = (String) other.getTag();
        if (!fallback.matches("\\d{4}-\\d{2}-\\d{2}")) {
            java.util.Calendar calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Phnom_Penh"));
            year = calendar.get(java.util.Calendar.YEAR);
            month = calendar.get(java.util.Calendar.MONTH);
            day = calendar.get(java.util.Calendar.DAY_OF_MONTH);
        } else {
            year = Integer.parseInt(fallback.substring(0, 4));
            month = Integer.parseInt(fallback.substring(5, 7)) - 1;
            day = Integer.parseInt(fallback.substring(8, 10));
        }
        new DatePickerDialog(this, (view, pickedYear, pickedMonth, pickedDay) -> {
            String picked = String.format(Locale.US, "%04d-%02d-%02d", pickedYear, pickedMonth + 1, pickedDay);
            String from = pickingStart ? picked : other.getText().toString();
            String to = pickingStart ? other.getText().toString() : picked;
            if (from.matches("\\d{4}-\\d{2}-\\d{2}") && to.matches("\\d{4}-\\d{2}-\\d{2}") && from.compareTo(to) > 0) {
                toast("From needs to be on or before To");
                return;
            }
            field.setText(picked);
            reload.run();
        }, year, month, day).show();
    }

    private Spinner filterSpinner(List<String> options, int selected) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(null);
        spinner.setPadding(0, 0, 0, 0);
        spinner.setMinimumHeight(dp(40));
        if (selected >= 0 && selected < options.size()) spinner.setSelection(selected);
        return spinner;
    }

    private void replaceResults(LinearLayout results) {
        endAnimations(results);
        results.clearDisappearingChildren();
        results.removeAllViews();
    }

    private void endAnimations(View view) {
        view.clearAnimation();
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) endAnimations(group.getChildAt(i));
    }

    private void reportSkeleton(LinearLayout results) {
        results.setContentDescription("Loading report");
        LinearLayout summary = panel(results, 0);
        LinearLayout period = column();
        period.setPadding(dp(18), dp(16), dp(18), 0);
        period.addView(bone(LINE, 16, 220));
        gap(period, 8);
        period.addView(bone(LINE, 12, 120));
        summary.addView(period);
        LinearLayout well = column();
        well.setBackground(shape(SAGE, 12, false));
        well.setPadding(dp(16), dp(14), dp(16), dp(14));
        well.addView(bone(PAPER, 12, 120));
        gap(well, 10);
        well.addView(bone(PAPER, 28, 180));
        gap(well, 8);
        well.addView(bone(PAPER, 12, 150));
        LinearLayout.LayoutParams wellParams = new LinearLayout.LayoutParams(-1, -2);
        wellParams.setMargins(dp(14), dp(12), dp(14), dp(14));
        summary.addView(well, wellParams);
        LinearLayout tiles = row();
        tiles.setPadding(dp(14), 0, dp(14), dp(14));
        tiles.addView(skeletonTile(), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams secondTile = new LinearLayout.LayoutParams(0, -2, 1);
        secondTile.setMarginStart(dp(10));
        tiles.addView(skeletonTile(), secondTile);
        summary.addView(tiles);
        rule(summary);
        LinearLayout footer = row();
        footer.setPadding(dp(14), dp(12), dp(14), dp(14));
        footer.addView(bone(LINE, 36, 128));
        summary.addView(footer);

        LinearLayout chart = panel(results, 16);
        chart.addView(bone(LINE, 16, 160));
        gap(chart, 16);
        LinearLayout bars = row();
        bars.setGravity(Gravity.BOTTOM);
        bars.setMinimumHeight(dp(112));
        int[] heights = {48, 84, 60, 104, 44, 76, 64, 92};
        for (int i = 0; i < heights.length; i++) {
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(0, dp(heights[i]), 1);
            if (i > 0) bar.setMarginStart(dp(8));
            bars.addView(bone(LINE, heights[i], 0), bar);
        }
        chart.addView(bars);

        LinearLayout table = panel(results, 0);
        View header = new View(this);
        header.setBackgroundColor(TRACK);
        table.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));
        for (int i = 0; i < 4; i++) {
            rule(table);
            LinearLayout line = row();
            line.setPadding(dp(16), dp(12), dp(16), dp(12));
            line.setMinimumHeight(dp(52));
            line.addView(bone(LINE, 12, 148));
            line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            line.addView(bone(LINE, 12, 72));
            table.addView(line);
        }
        pulse(summary);
        pulse(chart);
        pulse(table);
    }

    private LinearLayout skeletonTile() {
        LinearLayout tile = column();
        tile.setBackground(shape(CANVAS, 12, false));
        tile.setPadding(dp(12), dp(12), dp(12), dp(12));
        tile.addView(bone(LINE, 12, 72));
        gap(tile, 8);
        tile.addView(bone(LINE, 18, 96));
        return tile;
    }

    private View bone(int color, int heightDp, int widthDp) {
        View block = new View(this);
        block.setBackground(shape(color, 6, false));
        block.setLayoutParams(new LinearLayout.LayoutParams(widthDp <= 0 ? -1 : dp(widthDp), dp(heightDp)));
        return block;
    }

    private void pulse(View view) {
        AlphaAnimation pulse = new AlphaAnimation(0.45f, 1f);
        pulse.setDuration(800);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        view.startAnimation(pulse);
    }

    private void renderReport(LinearLayout results, JSONObject report, View export) {
        results.setContentDescription(null);
        JSONArray buckets = list(report, "buckets"), payments = list(report, "payments"), products = list(report, "products");
        long collected = 0, returned = 0, orders = 0;
        for (int i = 0; i < buckets.length(); i++) {
            JSONObject bucket = buckets.optJSONObject(i);
            collected += bucket.optLong("collected");
            returned += bucket.optLong("returned");
            orders += bucket.optLong("orders");
        }
        LinearLayout summary = panel(results, 0);
        LinearLayout period = column();
        period.setPadding(dp(18), dp(16), dp(18), 0);
        period.addView(text(report.optString("start") + "  →  " + report.optString("end"), 16, INK, true));
        if (!report.optString("timezone").isEmpty()) period.addView(text(report.optString("timezone"), 13, MUTED, false));
        summary.addView(period);
        LinearLayout well = column();
        well.setBackground(shape(SAGE, 12, false));
        well.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout labelRow = row();
        labelRow.addView(text(tr("Net collected"), 13, PAID, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView currency = text(report.optString("currency", "USD"), 12, Color.WHITE, true);
        currency.setBackground(shape(PAID, 8, false));
        currency.setPadding(dp(10), dp(4), dp(10), dp(4));
        labelRow.addView(currency);
        well.addView(labelRow);
        well.addView(text(saleMoney(report, collected - returned), 34, PAID, true));
        gap(well, 4);
        well.addView(text(orders + " orders · " + report.optInt("items") + " items", 13, PAID, false));
        LinearLayout.LayoutParams wellParams = new LinearLayout.LayoutParams(-1, -2);
        wellParams.setMargins(dp(14), dp(12), dp(14), dp(14));
        summary.addView(well, wellParams);
        LinearLayout tiles = row();
        tiles.setPadding(dp(14), 0, dp(14), dp(14));
        tiles.addView(shiftTile(tr("Collected"), saleMoney(report, collected), PAID), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams returnedParams = new LinearLayout.LayoutParams(0, -2, 1);
        returnedParams.setMarginStart(dp(10));
        tiles.addView(shiftTile(tr("Returned"), saleMoney(report, returned), ACCENT), returnedParams);
        summary.addView(tiles);
        if (collected == 0 && returned == 0) {
            TextView empty = text("No sales or returns in this period.", 14, MUTED, false);
            empty.setPadding(dp(18), 0, dp(18), dp(4));
            summary.addView(empty);
        }
        rule(summary);
        LinearLayout footer = row();
        footer.setPadding(dp(14), dp(12), dp(14), dp(14));
        footer.addView(export);
        summary.addView(footer);
        salesChart(results, report, buckets);
        productChart(results, report, products);
        paymentChart(results, report, payments);

        boolean compact = getResources().getConfiguration().screenWidthDp < 620;
        DataTable paymentTable = compact
                ? new DataTable(results, new int[]{3, 4, 3}, "Payment", "Collected / returned", "Net")
                : new DataTable(results, new int[]{3, 2, 2, 2}, tr("Payment method"), "Collected", "Returned", "Net");
        for (int i = 0; i < payments.length(); i++) {
            JSONObject item = payments.optJSONObject(i);
            long gross = item.optLong("collected"), refunds = item.optLong("returned");
            String grossAmount = saleMoney(report, gross), returnedAmount = saleMoney(report, refunds);
            String netAmount = saleMoney(report, gross - refunds);
            String method = tr(item.optString("method"));
            if (compact) paymentTable.row(method, grossAmount + "\n" + returnedAmount, netAmount);
            else paymentTable.row(method, grossAmount, returnedAmount, netAmount);
        }
        DataTable productTable = new DataTable(results, new int[]{5, 2, 3}, tr("Product"), "Qty", "Before offers");
        for (int i = 0; i < products.length(); i++) {
            JSONObject item = products.optJSONObject(i);
            productTable.row(item.optString("name"), item.optString("quantity"), saleMoney(report, item.optLong("amount")));
        }
        DataTable periodTable = new DataTable(results, new int[]{4, 2, 3}, "Period", "Orders", "Net collected");
        for (int i = 0; i < buckets.length(); i++) {
            JSONObject item = buckets.optJSONObject(i);
            if (item.optInt("orders") == 0 && item.optLong("returned") == 0) continue;
            periodTable.row(item.optString("label"), item.optString("orders"), saleMoney(report, item.optLong("collected") - item.optLong("returned")));
        }
    }

    private void salesChart(LinearLayout parent, JSONObject report, JSONArray buckets) {
        LinearLayout card = panel(parent, 16);
        TextView title = text(tr("Sales over time"), 18, INK, true);
        ViewCompat.setAccessibilityHeading(title, true);
        card.addView(title);
        boolean hourly = report.optString("start").equals(report.optString("end"));
        card.addView(text((hourly ? "Collections by hour" : "Collections by day") + " · VAT included", 12, MUTED, false));
        gap(card, 10);
        LinearLayout legend = row();
        legend.addView(swatch(INK, 0));
        legend.addView(text(tr("Collected"), 12, INK, false));
        legend.addView(swatch(ACCENT, dp(14)));
        legend.addView(text(tr("Returned"), 12, ACCENT, true));
        card.addView(legend);
        long peak = 0;
        for (int i = 0; i < buckets.length(); i++) {
            JSONObject bucket = buckets.optJSONObject(i);
            peak = Math.max(peak, Math.max(bucket.optLong("collected"), bucket.optLong("returned")));
        }
        long scale = Math.max(peak, 1);
        gap(card, 8);
        card.addView(text(saleMoney(report, peak), 12, MUTED, false));
        int chartHeight = dp(148);
        LinearLayout columns = row();
        columns.setGravity(Gravity.BOTTOM);
        boolean stretch = buckets.length() > 0 && buckets.length() <= 12;
        for (int i = 0; i < buckets.length(); i++) {
            JSONObject bucket = buckets.optJSONObject(i);
            long collected = bucket.optLong("collected"), returned = bucket.optLong("returned");
            LinearLayout slot = column();
            slot.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout bars = row();
            bars.setGravity(Gravity.BOTTOM);
            bars.addView(chartBar(INK, chartHeight, collected, scale));
            View gap = new View(this);
            bars.addView(gap, new LinearLayout.LayoutParams(dp(3), 1));
            bars.addView(chartBar(ACCENT, chartHeight, returned, scale));
            slot.addView(bars, new LinearLayout.LayoutParams(-2, chartHeight));
            String label = bucket.optString("label");
            String axisLabel = hourly ? (label.length() >= 2 ? label.substring(0, 2) : label) : (label.length() > 5 ? label.substring(5) : label);
            TextView axis = text(axisLabel, 10, MUTED, false);
            axis.setGravity(Gravity.CENTER);
            slot.addView(axis, new LinearLayout.LayoutParams(-1, -2));
            slot.setContentDescription(label + ", " + tr("Collected") + " " + saleMoney(report, collected) + ", " + tr("Returned") + " " + saleMoney(report, returned));
            LinearLayout.LayoutParams params = stretch ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(dp(36), -2);
            columns.addView(slot, params);
        }
        if (stretch) card.addView(columns);
        else {
            HorizontalScrollView scroller = new HorizontalScrollView(this);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.addView(columns);
            card.addView(scroller);
        }
    }

    private View chartBar(int color, int chartHeight, long value, long max) {
        View bar = new View(this);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float radius = dp(3);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        bar.setBackground(drawable);
        int height = value <= 0 ? 0 : Math.max(dp(3), (int) Math.round(chartHeight * (value / (double) max)));
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(8), height));
        return bar;
    }

    private View swatch(int color, int startMargin) {
        View mark = new View(this);
        mark.setBackground(shape(color, 2, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(10), dp(10));
        params.setMarginStart(startMargin);
        params.setMarginEnd(dp(6));
        mark.setLayoutParams(params);
        return mark;
    }

    private void productChart(LinearLayout parent, JSONObject report, JSONArray products) {
        LinearLayout card = panel(parent, 16);
        LinearLayout head = getResources().getConfiguration().screenWidthDp >= 720 ? row() : column();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(tr("Most-bought items"), 18, INK, true);
        ViewCompat.setAccessibilityHeading(title, true);
        head.addView(title, new LinearLayout.LayoutParams(head.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1, -2, head.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0));
        LinearLayout toggle = row();
        MaterialButton byQuantity = button(tr("Quantity"), !rankByAmount, () -> { });
        MaterialButton byAmount = button(tr("Sales amount"), rankByAmount, () -> { });
        compact(byQuantity);
        compact(byAmount);
        LinearLayout ranks = column();
        Runnable paint = () -> {
            paint(byQuantity, rankByAmount ? PAPER : ACCENT, rankByAmount ? LINE : ACCENT, rankByAmount ? INK : Color.WHITE);
            paint(byAmount, rankByAmount ? ACCENT : PAPER, rankByAmount ? ACCENT : LINE, rankByAmount ? Color.WHITE : INK);
            fillProductRanks(ranks, report, products);
        };
        byQuantity.setOnClickListener(v -> { rankByAmount = false; paint.run(); });
        byAmount.setOnClickListener(v -> { rankByAmount = true; paint.run(); });
        toggle.addView(byQuantity);
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(-2, -2);
        amountParams.setMarginStart(dp(8));
        toggle.addView(byAmount, amountParams);
        if (head.getOrientation() == LinearLayout.VERTICAL) {
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
            toggleParams.topMargin = dp(8);
            head.addView(toggle, toggleParams);
        } else head.addView(toggle);
        card.addView(head);
        gap(card, 12);
        card.addView(ranks);
        paint.run();
        gap(card, 10);
        card.addView(text("Product values are before order discounts. Fully returned and voided orders are excluded.", 12, MUTED, false));
    }

    private void fillProductRanks(LinearLayout ranks, JSONObject report, JSONArray products) {
        ranks.removeAllViews();
        List<JSONObject> top = topProducts(products);
        if (top.isEmpty()) {
            ranks.addView(text("No products sold in this period.", 14, MUTED, false));
            return;
        }
        long max = 1;
        for (JSONObject product : top) max = Math.max(max, rankByAmount ? product.optLong("amount") : product.optLong("quantity"));
        for (int i = 0; i < top.size(); i++) {
            JSONObject product = top.get(i);
            long metric = rankByAmount ? product.optLong("amount") : product.optLong("quantity");
            LinearLayout heading = row();
            heading.setPadding(0, i == 0 ? 0 : dp(12), 0, dp(6));
            TextView index = text(String.format(Locale.US, "%02d", i + 1), 13, MUTED, true);
            index.setPadding(0, 0, dp(10), 0);
            heading.addView(index);
            TextView name = text(product.optString("name"), 14, INK, true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(text(product.optLong("quantity") + " " + tr("sold"), 13, INK, true));
            TextView amount = text(saleMoney(report, product.optLong("amount")), 12, MUTED, false);
            amount.setPadding(dp(10), 0, 0, 0);
            heading.addView(amount);
            ranks.addView(heading);
            LinearLayout track = row();
            track.setBackground(shape(CANVAS, 4, false));
            track.setClipToOutline(true);
            View fill = new View(this);
            fill.setBackground(shape(ACCENT, 4, false));
            long rest = Math.max(0, max - metric);
            if (metric <= 0) track.addView(new View(this), new LinearLayout.LayoutParams(0, dp(8), 1f));
            else {
                track.addView(fill, new LinearLayout.LayoutParams(0, dp(8), (float) metric));
                if (rest > 0) track.addView(new View(this), new LinearLayout.LayoutParams(0, dp(8), (float) rest));
            }
            track.setContentDescription(product.optString("name") + ", " + product.optLong("quantity") + " " + tr("sold") + ", " + saleMoney(report, product.optLong("amount")));
            ranks.addView(track);
        }
    }

    private List<JSONObject> topProducts(JSONArray products) {
        List<JSONObject> ranked = new ArrayList<>();
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product != null) ranked.add(product);
        }
        ranked.sort((a, b) -> {
            int byValue = Long.compare(rankByAmount ? b.optLong("amount") : b.optLong("quantity"), rankByAmount ? a.optLong("amount") : a.optLong("quantity"));
            return byValue != 0 ? byValue : a.optString("name").compareToIgnoreCase(b.optString("name"));
        });
        return ranked.size() > 5 ? ranked.subList(0, 5) : ranked;
    }

    private void paymentChart(LinearLayout parent, JSONObject report, JSONArray payments) {
        LinearLayout card = panel(parent, 16);
        TextView title = text(tr("Payment breakdown"), 18, INK, true);
        ViewCompat.setAccessibilityHeading(title, true);
        card.addView(title);
        card.addView(text("Share of collections before returns", 12, MUTED, false));
        gap(card, 12);
        long total = 0;
        for (int i = 0; i < payments.length(); i++) total += payments.optJSONObject(i).optLong("collected");
        LinearLayout share = row();
        share.setBackground(shape(CANVAS, 8, false));
        share.setClipToOutline(true);
        if (total > 0) {
            for (int i = 0; i < payments.length(); i++) {
                JSONObject payment = payments.optJSONObject(i);
                long collected = payment.optLong("collected");
                if (collected <= 0) continue;
                View segment = new View(this);
                boolean cash = "cash".equalsIgnoreCase(payment.optString("method"));
                segment.setBackgroundColor(cash ? INK : ACCENT);
                share.addView(segment, new LinearLayout.LayoutParams(0, dp(20), (float) collected));
            }
        } else share.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(20)));
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < payments.length(); i++) {
            JSONObject payment = payments.optJSONObject(i);
            if (summary.length() > 0) summary.append(", ");
            summary.append(paymentName(payment.optString("method"))).append(' ')
                    .append(sharePercent(payment.optLong("collected"), total));
        }
        share.setContentDescription(summary.toString());
        card.addView(share);
        for (int i = 0; i < payments.length(); i++) {
            JSONObject payment = payments.optJSONObject(i);
            long collected = payment.optLong("collected"), returned = payment.optLong("returned");
            gap(card, 14);
            LinearLayout heading = row();
            View mark = swatch("cash".equalsIgnoreCase(payment.optString("method")) ? INK : ACCENT, 0);
            heading.addView(mark);
            heading.addView(text(paymentName(payment.optString("method")), 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(text(sharePercent(collected, total), 15, INK, true));
            card.addView(heading);
            gap(card, 6);
            LinearLayout figures = row();
            figures.addView(paymentFigure(tr("Collected"), saleMoney(report, collected)), new LinearLayout.LayoutParams(0, -2, 1));
            figures.addView(paymentFigure(tr("Returned"), saleMoney(report, returned)), new LinearLayout.LayoutParams(0, -2, 1));
            figures.addView(paymentFigure("Net", saleMoney(report, collected - returned)), new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(figures);
        }
    }

    private LinearLayout paymentFigure(String label, String amount) {
        LinearLayout figure = column();
        figure.addView(text(label, 12, MUTED, false));
        figure.addView(text(amount, 14, INK, true));
        return figure;
    }

    private String paymentName(String method) {
        if ("cash".equalsIgnoreCase(method)) return tr("Cash");
        if ("bakong".equalsIgnoreCase(method)) return tr("Bakong");
        return pretty(method);
    }

    private String sharePercent(long part, long total) {
        return String.format(Locale.US, "%.1f%%", total == 0 ? 0 : part * 100.0 / total);
    }

    private String reportDateOffset(String date,int days) {
        TimeZone utc=TimeZone.getTimeZone("UTC");
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
        format.setTimeZone(utc);
        try {
            Calendar day=Calendar.getInstance(utc);
            day.setTime(Objects.requireNonNull(format.parse(date)));
            day.add(Calendar.DATE,days);
            return format.format(day.getTime());
        } catch(java.text.ParseException error) {
            return date;
        }
    }

    private void showManagement() {
        boolean available = connecting || (!demo && staff != null && manager());
        if (!available) {
            LinearLayout body = content("Store management", "");
            emptyState(body, awaitingStore() ? "The store didn't answer" : demo ? "Connect your store" : "Manager access required",
                    awaitingStore() ? "Retry the saved store before managing it." : demo ? "Connect a store backend to use these tools." : "Sign in as a manager or owner.",
                    button(awaitingStore() ? "Retry" : demo ? "Connect store" : "Sign in", true, awaitingStore() ? this::retryStore : demo ? this::connectionDialog : this::login));
            manageHost = null;
            return;
        }
        manageNavWide = getResources().getConfiguration().screenWidthDp >= 800;
        LinearLayout screen = manageNavWide ? row() : column();
        screen.setGravity(Gravity.TOP);
        page.addView(screen, new LinearLayout.LayoutParams(-1, -1));
        manageNav = column();
        LinearLayout navShell = column();
        navShell.setBackgroundColor(PAPER);
        navShell.setPadding(dp(12), dp(16), dp(12), dp(16));
        navShell.addView(text("Manage", 13, MUTED, true));
        gap(navShell, 12);
        if (manageNavWide) {
            navShell.addView(manageNav);
            screen.addView(navShell, new LinearLayout.LayoutParams(dp(196), -1));
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            screen.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));
        } else {
            HorizontalScrollView scroller = new HorizontalScrollView(this);
            scroller.setHorizontalScrollBarEnabled(false);
            manageNav.setOrientation(LinearLayout.HORIZONTAL);
            scroller.addView(manageNav);
            navShell.addView(scroller);
            screen.addView(navShell);
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            screen.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        LinearLayout main = column();
        main.setPadding(dp(manageNavWide ? 24 : 16), dp(16), dp(manageNavWide ? 24 : 16), dp(8));
        manageTitle = text(tr(manageSection), 26, INK, true);
        manageDetail = text(manageBlurb(manageSection), 14, MUTED, false);
        main.addView(manageTitle);
        gap(main, 4);
        main.addView(manageDetail);
        gap(main, 14);
        manageHost = column();
        ScrollView content = scroll(manageHost);
        content.setFillViewport(true);
        main.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        screen.addView(main, new LinearLayout.LayoutParams(manageNavWide ? 0 : -1, manageNavWide ? -1 : 0, 1));
        paintManageNav();
        if (connecting) {
            manageSkeleton(manageHost);
            return;
        }
        showManageSection(manageSection);
    }

    private void paintManageNav() {
        if (manageNav == null) return;
        manageNav.removeAllViews();
        for (String key : new String[]{"Catalog", "Inventory", "Recipes", "Staff"}) {
            boolean selected = key.equals(manageSection);
            LinearLayout item = column();
            item.setBackground(shape(selected ? SOFT : Color.TRANSPARENT, 10, false));
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setMinimumHeight(dp(44));
            TextView label = text(tr(key), 15, selected ? ACCENT : INK, true);
            label.setGravity(Gravity.CENTER_VERTICAL);
            item.addView(label);
            item.setContentDescription(tr(key));
            if (!connecting) pressable(item, () -> showManageSection(key));
            LinearLayout.LayoutParams params = manageNavWide
                    ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(-2, -2);
            if (manageNav.getChildCount() > 0) {
                if (manageNavWide) params.topMargin = dp(4);
                else params.setMarginStart(dp(4));
            }
            manageNav.addView(item, params);
        }
    }

    private String manageBlurb(String key) {
        switch (key) {
            case "Inventory": return "Stock levels, movements and purchases";
            case "Recipes": return "Ingredients and product links";
            case "Staff": return "Accounts, roles and PINs";
            default: return "Products and categories";
        }
    }

    private boolean manageOnScreen() {
        View node = manageHost;
        while (node != null) {
            if (node == page) return true;
            android.view.ViewParent parent = node.getParent();
            if (!(parent instanceof View)) return false;
            node = (View) parent;
        }
        return false;
    }

    private boolean manageVisible(String section) {
        return manageOnScreen() && section.equals(manageSection);
    }

    private void showManageSection(String key) {
        if (!manageOnScreen() || connecting) return;
        if (busy) { toast("Please wait for the current request"); return; }
        manageSection = key;
        if (manageTitle != null) manageTitle.setText(tr(key));
        if (manageDetail != null) manageDetail.setText(manageBlurb(key));
        paintManageNav();
        String path = managePath(key);
        replaceResults(manageHost);
        if (cached(path) == null) manageSkeleton(manageHost);
        switch (key) {
            case "Inventory": manageInventory(); break;
            case "Recipes": manageRecipes(); break;
            case "Staff": manageStaff(); break;
            default: manageCatalog(); break;
        }
    }

    private String managePath(String key) {
        switch (key) {
            case "Inventory": return "/api/manage/inventory";
            case "Recipes": return "/api/manage/recipes";
            case "Staff": return "/api/manage/staff";
            default: return "/api/manage/catalog";
        }
    }

    private void manageSkeleton(LinearLayout parent) {
        parent.setContentDescription("Loading manage");
        LinearLayout actions = row();
        actions.addView(bone(LINE, 40, 156));
        View second = bone(LINE, 40, 132);
        ((LinearLayout.LayoutParams) second.getLayoutParams()).setMarginStart(dp(8));
        actions.addView(second);
        parent.addView(actions);
        gap(parent, 12);
        LinearLayout table = panel(parent, 0);
        LinearLayout header = row();
        header.setBackgroundColor(TRACK);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        header.setMinimumHeight(dp(42));
        salesBone(header, 5, 12);
        salesBone(header, 3, 12);
        salesBone(header, 2, 12);
        table.addView(header);
        for (int i = 0; i < 5; i++) {
            rule(table);
            LinearLayout line = row();
            line.setPadding(dp(16), dp(14), dp(16), dp(14));
            line.setMinimumHeight(dp(64));
            salesBone(line, 5, 14);
            salesBone(line, 3, 14);
            salesBone(line, 2, 14);
            table.addView(line);
        }
        pulse(actions);
        pulse(table);
    }

    private Spinner spinner(LinearLayout body,String label,List<String> options,int selected) {
        gap(body,10); body.addView(text(label,14,INK,true)); Spinner spinner=new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));
        spinner.setMinimumHeight(dp(52)); if(selected>=0 && selected<options.size()) spinner.setSelection(selected); body.addView(spinner); return spinner;
    }
    private CheckBox check(LinearLayout body,String label,boolean checked) {
        CheckBox check=new CheckBox(this); check.setText(label); check.setChecked(checked); check.setMinHeight(dp(48)); body.addView(check); return check;
    }
    private List<String> labels(JSONArray values,String key) {
        List<String> names=new ArrayList<>(); for(int i=0;i<values.length();i++) names.add(values.optJSONObject(i).optString(key)); return names;
    }
    private int indexOf(JSONArray values,String id) {
        for(int i=0;i<values.length();i++) if(values.optJSONObject(i).optString("id").equals(id)) return i; return -1;
    }
    private void manageCatalog() {
        readTab("/api/manage/catalog", false, data -> {
            if (!manageVisible("Catalog")) return;
            LinearLayout body = manageHost;
            replaceResults(body);
            body.setContentDescription(null);
            JSONArray products = list(data, "products");
            actions(body, button("Add product", true, () -> productEditor(data, null)),
                    button(tr("Add category"), false, this::addCategory));
            gap(body, 12);
            if (products.length() == 0) emptyState(body, "No products yet", "Add a product to build your menu.");
            else {
                JSONArray categories = list(data, "categories");
                Map<String, String> categoryNames = new HashMap<>();
                for (int i = 0; i < categories.length(); i++) {
                    JSONObject category = categories.optJSONObject(i);
                    categoryNames.put(category.optString("id"), category.optString("name"));
                }
                DataTable table = new DataTable(body, new int[]{5, 3, 2}, true, tr("Product"), tr("Price"), "Status");
                for (int i = 0; i < products.length(); i++) {
                    JSONObject product = products.optJSONObject(i);
                    String category = categoryNames.getOrDefault(product.optString("categoryId"), "");
                    table.actionRow("Edit product", () -> productEditor(data, product),
                            product.optString("name") + (category.isEmpty() ? "" : "\n" + category),
                            PosMath.money(product.optLong("price"), data.optString("currency", currency())),
                            product.optBoolean("active") ? tr("Active") : "Inactive");
                }
            }
        }, () -> manageFailure("Catalog"));
    }
    private void productEditor(JSONObject data,JSONObject existing) {
        JSONObject product=existing==null?new JSONObject():existing; LinearLayout body=dialogBody();
        EditText name=input(body,"Product name",product.optString("name"),false);
        EditText description=input(body,tr("Description"),product.optString("description"),false);
        EditText price=input(body,"Price ("+currency()+")",PosMath.amount(product.optLong("price"),currency()),true);
        EditText initials=input(body,"Initials (1–4)",product.optString("initials"),false);
        JSONArray categories=list(data,"categories"); if(categories.length()==0) { toast("Create a category first"); return; }
        Spinner category=spinner(body,tr("Category"),labels(categories,"name"),indexOf(categories,product.optString("categoryId")));
        List<String> tones=Arrays.asList("clay","oat","sage","sky","rose","butter");
        Spinner tone=spinner(body,"Color",tones,Math.max(0,tones.indexOf(product.optString("tone"))));
        CheckBox active=check(body,"Active on the register",existing==null || product.optBoolean("active")), popular=check(body,tr("Popular"),product.optBoolean("popular"));
        gap(body,10); body.addView(text("Modifier groups",16,INK,true));
        JSONArray groups=list(data,"modifierGroups"), selected=list(product,"modifierGroupIds"); List<CheckBox> checks=new ArrayList<>();
        for(int i=0;i<groups.length();i++) {
            JSONObject group=groups.optJSONObject(i); boolean enabled=false;
            for(int j=0;j<selected.length();j++) if(group.optString("id").equals(selected.optString(j))) enabled=true;
            checks.add(check(body,group.optString("name"),enabled));
        }
        AlertDialog dialog=dialog(existing==null?"Add product":"Edit product",body);
        List<String> imageOptions=new ArrayList<>();
        imageOptions.add("Keep current image");
        imageOptions.addAll(labels(drinkImages,"name"));
        Spinner drinkImage=spinner(body,"Drink image",imageOptions,0);
        if(existing!=null) {
            FrameLayout preview=new FrameLayout(this);
            body.addView(preview,new LinearLayout.LayoutParams(-1,dp(110)));
            preview.setBackground(shape(tone(product.optString("tone")),12,false));
            showProductImage(product,preview,ImageView.ScaleType.CENTER_CROP);
            body.addView(button("Choose product image",false,() -> pickProductImage(product.optString("id"))));
        }
        body.addView(button(tr("Save product"),true,() -> {
            try {
                JSONArray ids=new JSONArray(); for(int i=0;i<checks.size();i++) if(checks.get(i).isChecked()) ids.put(groups.optJSONObject(i).optString("id"));
                JSONObject payload=obj("name",name.getText().toString().trim(),"description",description.getText().toString(),"price",PosMath.cents(price.getText().toString(),currency()),
                        "initials",initials.getText().toString().trim(),"categoryId",categories.optJSONObject(category.getSelectedItemPosition()).optString("id"),
                        "tone",tone.getSelectedItem().toString(),"active",active.isChecked(),"popular",popular.isChecked(),"modifierGroupIds",ids);
                if(existing!=null) payload.put("id",product.optString("id"));
                if(drinkImage.getSelectedItemPosition()>0)
                    payload.put("drinkThumbnail",drinkImageUrl(drinkImages.getJSONObject(drinkImage.getSelectedItemPosition()-1)));
                request(() -> api.request("/api/manage/catalog",existing==null?"POST":"PATCH",payload),result -> {
                    dialog.dismiss();
                    toast("Product saved.");
                    refreshMenu();
                    showManageSection("Catalog");
                });
            } catch(Exception error) { error(error); }
        })); dialog.show();
    }
    private void addCategory() {
        LinearLayout body=dialogBody(); EditText name=input(body,"Category name","",false); AlertDialog dialog=dialog(tr("Add category"),body);
        body.addView(button("Save category",true,() -> request(() -> api.request("/api/manage/categories",obj("name",name.getText().toString().trim())),result -> {
            dialog.dismiss();
            toast("Category saved");
            refreshMenu();
            showManageSection("Catalog");
        }))); dialog.show();
    }
    private void manageStaff() {
        readTab("/api/manage/staff", false, result -> {
            if (!manageVisible("Staff")) return;
            LinearLayout body = manageHost;
            replaceResults(body);
            body.setContentDescription(null);
            body.addView(button("Add staff member", true, () -> staffEditor(null)));
            gap(body, 12);
            JSONArray members = list(result, "staff");
            if (members.length() == 0) emptyState(body, "No staff yet", "");
            else {
                DataTable table = new DataTable(body, new int[]{5, 3, 2}, true, tr("Name"), tr("Role"), "Status");
                for (int i = 0; i < members.length(); i++) {
                    JSONObject member = members.optJSONObject(i);
                    table.actionRow("Edit staff", () -> staffEditor(member), member.optString("name"),
                            pretty(member.optString("role")), member.optBoolean("active") ? tr("Active") : "Inactive");
                }
            }
        }, () -> manageFailure("Staff"));
    }
    private void staffEditor(JSONObject existing) {
        JSONObject member=existing==null?new JSONObject():existing; LinearLayout body=dialogBody();
        EditText name=input(body,tr("Name"),member.optString("name"),false);
        List<String> roles=Arrays.asList("cashier","manager","owner");
        Spinner role=spinner(body,tr("Role"),roles,Math.max(0,roles.indexOf(member.optString("role"))));
        EditText pin=input(body,existing==null?"4-digit PIN":"New PIN (leave blank to keep)","",false);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        CheckBox active=check(body,tr("Active"),existing==null || member.optBoolean("active"));
        AlertDialog dialog=dialog(existing==null?"Add staff":"Edit staff",body);
        body.addView(button(tr("Save staff"),true,() -> {
            JSONObject payload=obj("name",name.getText().toString().trim(),"role",role.getSelectedItem().toString(),"pin",pin.getText().toString(),"active",active.isChecked());
            try { if(existing!=null) payload.put("id",member.optString("id")); }
            catch(Exception error) { error(error); return; }
            request(() -> api.request("/api/manage/staff",existing==null?"POST":"PATCH",payload),result -> { pin.setText(""); dialog.dismiss(); toast("Staff saved"); invalidateTab("/api/manage/staff"); showManageSection("Staff"); });
        })); dialog.show();
    }
    private void manageInventory() {
        readTab("/api/manage/inventory", false, result -> {
            if (!manageVisible("Inventory")) return;
            LinearLayout body = manageHost;
            replaceResults(body);
            body.setContentDescription(null);
            body.addView(button("Create stock item", true, () -> stockEditor(null)));
            gap(body, 12);
            JSONArray totals = list(result, "purchaseTotals");
            if (totals.length() > 0) {
                DataTable purchases = new DataTable(body, new int[]{4, 3, 3}, "Supplier purchases", "Paid", tr("Unpaid"));
                for (int i = 0; i < totals.length(); i++) {
                    JSONObject total = totals.getJSONObject(i);
                    purchases.row(total.optString("currency"), saleMoney(total, total.optLong("paid")), saleMoney(total, total.optLong("unpaid")));
                }
            }
            JSONArray items = list(result, "items");
            if (items.length() == 0) emptyState(body, "No stock items yet", "Create an item to start tracking its balance.");
            else {
                DataTable table = new DataTable(body, new int[]{5, 3, 3}, true, tr("Item name"), "On hand", "Status");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    long quantity = item.optLong("quantityMilli");
                    String status = !item.optBoolean("active") ? "Inactive"
                            : quantity <= item.optLong("lowStockMilli") ? tr("Low stock") : "In stock";
                    table.actionRow("Stock actions", () -> stockActions(item, result.optString("currency", currency())),
                            item.optString("name"), stockAmount(quantity) + " " + item.optString("unit"), status);
                }
            }
        }, () -> manageFailure("Inventory"));
    }

    private void stockActions(JSONObject item, String purchaseCurrency) {
        new MaterialAlertDialogBuilder(this).setTitle(item.optString("name"))
                .setItems(new String[]{tr("Movement"), "Edit stock item", "Stock history"}, (dialog, action) -> {
                    if (action == 0) stockMovement(item, purchaseCurrency);
                    else if (action == 1) stockEditor(item);
                    else stockHistory(item);
                }).setNegativeButton(tr("Cancel"), null).show();
    }

    private String stockAmount(long milli) { return java.math.BigDecimal.valueOf(milli,3).stripTrailingZeros().toPlainString(); }
    private void stockEditor(JSONObject existing) {
        JSONObject item=existing==null?new JSONObject():existing; LinearLayout body=dialogBody();
        EditText name=input(body,tr("Item name"),item.optString("name"),false);
        List<String> units=Arrays.asList("g","ml","piece"); Spinner unit=spinner(body,"Unit",units,Math.max(0,units.indexOf(item.optString("unit")))); unit.setEnabled(existing==null);
        EditText low=input(body,"Low stock threshold",stockAmount(item.optLong("lowStockMilli")),true);
        EditText opening=existing==null?input(body,tr("Opening stock"),"0",true):null;
        EditText reason=existing==null?input(body,tr("Opening stock reason"),"",false):null;
        CheckBox active=check(body,tr("Active"),existing==null || item.optBoolean("active"));
        AlertDialog dialog=dialog(existing==null?"Create stock item":"Edit stock item",body);
        String id=existing==null?UUID.randomUUID().toString():item.optString("id");
        body.addView(button("Save item",true,() -> {
            JSONObject payload=obj("id",id,"name",name.getText().toString().trim(),"lowStock",low.getText().toString(),"active",active.isChecked());
            if(existing==null) {
                try { payload.put("unit",unit.getSelectedItem().toString()); payload.put("openingStock",opening.getText().toString()); payload.put("reason",reason.getText().toString().trim()); }
                catch(Exception error) { error(error); return; }
            }
            request(() -> api.request("/api/manage/inventory",existing==null?"POST":"PATCH",payload),result -> { dialog.dismiss(); toast("Stock item saved"); invalidateTab("/api/manage/inventory"); showManageSection("Inventory"); });
        })); dialog.show();
    }
    private static final List<String> PURCHASE_METHODS = Arrays.asList("drawer","cash","bakong","bank");
    private static final List<String> PURCHASE_LABELS = Arrays.asList("Register cash","Cash outside register","Bakong","Bank transfer");

    private void stockMovement(JSONObject item,String purchaseCurrency) {
        LinearLayout body=dialogBody();
        Spinner kind=spinner(body,tr("Movement"),Arrays.asList("restock","waste","correction"),0);
        body.addView(text("Stock count (correction) sets the balance to the counted amount.",14,MUTED,false));
        String unit=item.optString("unit");
        TextInputLayout quantityField = field(body, "Quantity in " + unit, "", true);
        EditText quantity = quantityField.getEditText(), reason = input(body, tr("Reason"), "", false);
        String largeUnit="g".equals(unit)?"kg":"ml".equals(unit)?"L":null;
        LinearLayout restock=column(); body.addView(restock);
        CheckBox large=largeUnit==null?null:check(restock,"Enter quantity in "+largeUnit,false);
        CheckBox includePurchase=check(restock,"Record supplier cost",false);
        LinearLayout purchaseFields=column(); restock.addView(purchaseFields); purchaseFields.setVisibility(View.GONE);
        EditText supplier=input(purchaseFields,tr("Supplier"),"",false), invoice=input(purchaseFields,tr("Invoice reference"),"",false);
        EditText cost=input(purchaseFields,"Cost ("+purchaseCurrency+")","",true);
        List<String> methods=new ArrayList<>(); methods.add("Unpaid"); methods.addAll(PURCHASE_LABELS);
        Spinner payment=spinner(purchaseFields,tr("Payment method"),methods,0);
        includePurchase.setOnCheckedChangeListener((view,checked) -> purchaseFields.setVisibility(checked?View.VISIBLE:View.GONE));
        if(large!=null) large.setOnCheckedChangeListener((view,checked) -> quantityField.setHint("Quantity in "+(checked?largeUnit:unit)));
        kind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                restock.setVisibility(position==0?View.VISIBLE:View.GONE);
                quantityField.setHint("Quantity in "+(position==0 && large!=null && large.isChecked()?largeUnit:unit));
            }
        });
        String[] lastBody={null}, key={null};
        AlertDialog dialog=dialog(item.optString("name"),body);
        body.addView(button(tr("Record movement"),true,() -> {
            if(busy) return;
            try {
                boolean isRestock=kind.getSelectedItemPosition()==0;
                String amount=quantity.getText().toString();
                if(isRestock && large!=null && large.isChecked()) amount=new java.math.BigDecimal(amount).movePointRight(3).toPlainString();
                JSONObject payload=obj("itemId",item.optString("id"),"kind",kind.getSelectedItem().toString(),
                        "quantity",amount,"reason",reason.getText().toString().trim());
                if(isRestock && includePurchase.isChecked()) {
                    JSONObject purchase=obj("supplier",supplier.getText().toString(),"invoice",invoice.getText().toString(),
                            "currency",purchaseCurrency,"amountCents",PosMath.cents(cost.getText().toString(),purchaseCurrency));
                    if(payment.getSelectedItemPosition()>0) purchase.put("paymentMethod",PURCHASE_METHODS.get(payment.getSelectedItemPosition()-1));
                    payload.put("purchase",purchase);
                }
                String serialized=payload.toString();
                if(!serialized.equals(lastBody[0])) { lastBody[0]=serialized; key[0]=UUID.randomUUID().toString(); }
                payload.put("idempotencyKey",key[0]);
                request(() -> api.request("/api/manage/inventory/movements",payload),result -> {
                    dialog.dismiss(); toast("Movement recorded"); invalidateTab("/api/manage/inventory"); showManageSection("Inventory");
                });
            } catch(Exception error) { error(error); }
        })); dialog.show();
    }

    private void stockHistory(JSONObject item) {
        LinearLayout body=dialogBody(), entries=column();
        body.addView(entries);
        AlertDialog dialog=dialog("Stock history",body);
        MaterialButton older=button("Load movements",false,() -> {});
        body.addView(older);
        String[] cursor={null};
        older.setOnClickListener(view -> {
            String path="/api/manage/inventory/movements?itemId="+Uri.encode(item.optString("id"))
                    +(cursor[0]==null?"":"&cursor="+Uri.encode(cursor[0]));
            request(() -> api.request(path,null),result -> {
                if(!dialog.isShowing()) return;
                JSONArray movements=list(result,"movements");
                if(cursor[0]==null && movements.length()==0) entries.addView(text(tr("No movements recorded."),16,MUTED,false));
                for(int i=0;i<movements.length();i++) {
                    JSONObject entry=movements.getJSONObject(i);
                    LinearLayout movement = panel(entries, 14);
                    movement.addView(text(pretty(entry.optString("kind")) + " · " + stockAmount(entry.optLong("deltaMilli")) + " " + item.optString("unit"), 18, INK, true));
                    movement.addView(text("Balance  " + stockAmount(entry.optLong("balanceMilli")) + " " + item.optString("unit"), 14, INK, false));
                    gap(movement, 4);
                    movement.addView(text(entry.optString("reason") + "\n" + entry.optString("staffName") + " · " + entry.optString("createdAt"), 13, MUTED, false));
                    JSONObject purchase=entry.optJSONObject("purchase");
                    if(purchase!=null) {
                        LinearLayout details=column();
                        gap(movement, 8);
                        rule(movement);
                        gap(movement, 8);
                        movement.addView(details);
                        stockPurchase(details,entry.getString("id"),purchase);
                    }
                }
                cursor[0]=result.isNull("nextCursor")?null:result.getString("nextCursor");
                older.setText(tr("Load older movements"));
                older.setVisibility(cursor[0]==null?View.GONE:View.VISIBLE);
            });
        });
        dialog.show();
        older.performClick();
    }

    private void stockPurchase(LinearLayout body,String movementId,JSONObject purchase) {
        body.removeAllViews();
        body.addView(text(saleMoney(purchase,purchase.optLong("amountCents"))+" · "+purchase.optString("supplier"),16,INK,true));
        if(!purchase.optString("invoice").isEmpty()) body.addView(text(purchase.optString("invoice"),14,MUTED,false));
        int method=PURCHASE_METHODS.indexOf(purchase.optString("paymentMethod"));
        if(method>=0) {
            body.addView(text(PURCHASE_LABELS.get(method)+" · "+purchase.optString("paidAt"),14,MUTED,false));
            return;
        }
        List<String> options=new ArrayList<>(); options.add("Select payment method"); options.addAll(PURCHASE_LABELS);
        Spinner payment=spinner(body,tr("Unpaid"),options,0);
        body.addView(button(tr("Record payment"),false,() -> {
            int selected=payment.getSelectedItemPosition()-1;
            if(selected<0) { toast("Select a payment method"); return; }
            JSONObject payload=obj("movementId",movementId,"paymentMethod",PURCHASE_METHODS.get(selected));
            request(() -> api.request("/api/manage/inventory/movements","PATCH",payload),
                    result -> stockPurchase(body,movementId,result.getJSONObject("purchase")));
        }));
    }
    private void manageFailure(String section) {
        if (!manageVisible(section)) return;
        replaceResults(manageHost);
        manageHost.setContentDescription(null);
        emptyState(manageHost, "Couldn't load " + tr(section).toLowerCase(), "Check the connection and try again.",
                button("Try again", true, () -> showManageSection(section)));
    }

    private void manageRecipes() {
        readTab("/api/manage/recipes", false, data -> {
            if (!manageVisible("Recipes")) return;
            LinearLayout body = manageHost;
            replaceResults(body);
            body.setContentDescription(null);
            JSONArray recipes = list(data, "recipes");
            actions(body, button("Add recipe", true, () -> recipeDetails(data,
                            obj("id", UUID.randomUUID().toString(), "version", 0))),
                    button("Import source drafts", false, () -> new MaterialAlertDialogBuilder(this).setTitle("Import recipe drafts?")
                            .setMessage("Missing source drafts will be created. Existing edits are preserved by the backend.")
                            .setNegativeButton(tr("Cancel"), null).setPositiveButton("Import", (d, w) ->
                                    request(() -> api.request("/api/manage/recipes/import", obj()), result -> {
                                        invalidateTab("/api/manage/recipes");
                                        showManageSection("Recipes");
                                    })).show()));
            gap(body, 12);
            if (recipes.length() == 0) emptyState(body, "No recipes yet", "Add a recipe or import your source drafts.");
            else {
                DataTable table = new DataTable(body, new int[]{6, 2, 3}, true, tr("Recipe name"), "Version", "Status");
                for (int i = 0; i < recipes.length(); i++) {
                    JSONObject recipe = recipes.optJSONObject(i);
                    table.actionRow("Edit recipe", () -> recipeDetails(data, recipe),
                            recipe.optString("name"), recipe.optString("version"), recipe.optBoolean("active") ? tr("Active") : "Draft");
                }
            }
        }, () -> manageFailure("Recipes"));
    }
    private void recipeDetails(JSONObject data,JSONObject existing) {
        JSONObject recipe=copy(existing); LinearLayout body=dialogBody();
        body.addView(text("Version "+recipe.optInt("version")+" · Review exact product options and ingredient quantities before activation.",14,MUTED,false));
        EditText name=input(body,tr("Recipe name"),recipe.optString("name"),false);
        JSONArray products=list(data,"products"), items=list(data,"items");
        List<String> productNames=new ArrayList<>(); productNames.add("Unlinked"); productNames.addAll(labels(products,"name"));
        int selected=recipe.isNull("productId")?0:indexOf(products,recipe.optString("productId"))+1;
        Spinner product=spinner(body,tr("Product"),productNames,selected);
        // Keep existing exact modifier choices; changing the product requires new options to be reviewed.
        JSONArray modifiers=list(recipe,"modifiers");
        LinearLayout optionControls=column(); body.addView(optionControls); Map<String,Spinner> choices=new LinkedHashMap<>();
        product.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                optionControls.removeAllViews(); choices.clear(); if(position==0) return;
                JSONArray groups=list(products.optJSONObject(position-1),"modifierGroups");
                for(int g=0;g<groups.length();g++) {
                    JSONObject group=groups.optJSONObject(g); JSONArray options=list(group,"options");
                    List<String> labels=new ArrayList<>(); labels.add("None"); labels.addAll(MainActivity.this.labels(options,"name")); int current=0;
                    for(int m=0;m<modifiers.length();m++) if(modifiers.optJSONObject(m).optString("groupId").equals(group.optString("id"))) current=indexOf(options,modifiers.optJSONObject(m).optString("id"))+1;
                    choices.put(group.optString("id"),spinner(optionControls,group.optString("name"),labels,current));
                }
            }
        });
        CheckBox sizeConfirmed=check(body,"Selling size confirmed",!recipe.optBoolean("sizeNeedsConfirmation"));
        EditText notes=input(body,"Recipe notes",recipe.optString("notes"),false);
        List<RecipeLine> lines=new ArrayList<>();
        LinearLayout ingredientsBody=column(); body.addView(ingredientsBody);
        JSONArray ingredients=list(recipe,"ingredients");
        for(int i=0;i<ingredients.length();i++) addRecipeLine(ingredientsBody,lines,items,ingredients.optJSONObject(i));
        Runnable addIngredient=() -> {
            if(lines.size()>=40) { toast("A recipe can have up to 40 ingredients"); return; }
            addRecipeLine(ingredientsBody,lines,items,obj("id",UUID.randomUUID().toString(),"sourceName","Ingredient",
                    "sourceAmount","","sourceCell","","itemId",JSONObject.NULL,"unit",JSONObject.NULL,"quantityMilli",JSONObject.NULL,
                    "needsConfirmation",false,"excluded",false,"note",""));
        };
        if(lines.isEmpty()) addIngredient.run();
        body.addView(button(tr("Add ingredient"),false,addIngredient));
        CheckBox active=check(body,"Activate this recipe",recipe.optBoolean("active"));
        AlertDialog dialog=dialog(recipe.optInt("version")==0?"Add recipe":"Edit recipe",body);
        body.addView(button("Save recipe",true,() -> {
            try {
                JSONArray chosen=new JSONArray();
                if(product.getSelectedItemPosition()>0) {
                    JSONArray groups=list(products.optJSONObject(product.getSelectedItemPosition()-1),"modifierGroups");
                    for(int g=0;g<groups.length();g++) {
                        JSONObject group=groups.optJSONObject(g); Spinner choice=choices.get(group.optString("id"));
                        if(choice!=null && choice.getSelectedItemPosition()>0) chosen.put(obj("groupId",group.optString("id"),"id",list(group,"options").optJSONObject(choice.getSelectedItemPosition()-1).optString("id")));
                    }
                }
                if(lines.isEmpty()) throw new Exception("Add at least one ingredient");
                JSONArray savedIngredients=new JSONArray();
                for(RecipeLine line:lines) savedIngredients.put(line.toJson(items));
                JSONObject payload=obj("id",recipe.getString("id"),"expectedVersion",recipe.getInt("version"),"name",name.getText().toString(),
                        "productId",product.getSelectedItemPosition()==0?JSONObject.NULL:products.getJSONObject(product.getSelectedItemPosition()-1).getString("id"),
                        "modifiers",chosen,"active",active.isChecked(),"sizeNeedsConfirmation",!sizeConfirmed.isChecked(),"notes",notes.getText().toString(),"ingredients",savedIngredients);
                request(() -> api.request("/api/manage/recipes","PUT",payload),result -> { dialog.dismiss(); refreshContext(false); invalidateTab("/api/manage/recipes"); showManageSection("Recipes"); });
            } catch(Exception error) { error(error); }
        })); dialog.show();
    }

    private void addRecipeLine(LinearLayout parent,List<RecipeLine> lines,JSONArray items,JSONObject ingredient) {
        LinearLayout body = panel(parent, 14);
        RecipeLine line=new RecipeLine(body,items,ingredient);
        lines.add(line);
        body.addView(button("Remove ingredient",false,() -> {
            lines.remove(line);
            parent.removeView(body);
        }));
    }

    private final class RecipeLine {
        private final JSONObject ingredient;
        private final EditText name, quantity, note;
        private final Spinner link;
        private final CheckBox confirmed, excluded;

        RecipeLine(LinearLayout body,JSONArray items,JSONObject ingredient) {
            this.ingredient=ingredient;
            name=input(body,tr("Ingredient"),ingredient.optString("sourceName"),false);
            String source=ingredient.optString("sourceAmount")+" "+ingredient.optString("sourceCell");
            if(!source.trim().isEmpty()) body.addView(text("Source: "+source,13,MUTED,false));
            List<String> itemNames=new ArrayList<>();
            itemNames.add("Unlinked");
            itemNames.addAll(labels(items,"name"));
            link=spinner(body,"Stock item (quantity uses its unit)",itemNames,indexOf(items,ingredient.optString("itemId"))+1);
            quantity=input(body,tr("Quantity"),ingredient.isNull("quantityMilli")?"":stockAmount(ingredient.optLong("quantityMilli")),true);
            note=input(body,"Ingredient note",ingredient.optString("note"),false);
            confirmed=check(body,"Source quantity confirmed",!ingredient.optBoolean("needsConfirmation"));
            excluded=check(body,"Exclude from tracked stock",ingredient.optBoolean("excluded"));
        }

        JSONObject toJson(JSONArray items) throws Exception {
            JSONObject result=copy(ingredient);
            int selected=link.getSelectedItemPosition();
            result.put("sourceName",name.getText().toString().trim());
            result.put("itemId",selected==0?JSONObject.NULL:items.getJSONObject(selected-1).getString("id"));
            result.put("unit",selected==0?JSONObject.NULL:items.getJSONObject(selected-1).getString("unit"));
            String amount=quantity.getText().toString().trim();
            result.put("quantityMilli",amount.isEmpty()?JSONObject.NULL:new java.math.BigDecimal(amount).movePointRight(3).longValueExact());
            result.put("note",note.getText().toString());
            result.put("needsConfirmation",!confirmed.isChecked());
            result.put("excluded",excluded.isChecked());
            return result;
        }
    }
}

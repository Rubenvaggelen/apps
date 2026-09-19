package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class TvHomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PremiumUi.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(42), dp(30), dp(42), dp(36));
        root.setGravity(Gravity.TOP);

        root.addView(PremiumUi.brandHeader(this, "TV"));

        TextView title = new TextView(this);
        title.setText("Kies wat je wilt kijken");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setPadding(0, dp(28), 0, dp(18));
        root.addView(title);

        addButton(root, "Live TV", "live");
        addButton(root, "Films", "movie");
        addButton(root, "Series", "series");

        TextView hint = new TextView(this);
        hint.setText("Gebruik de pijltjestoetsen en OK op je afstandsbediening.");
        hint.setTextColor(PremiumUi.MUTED);
        hint.setTextSize(16);
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint);

        scroll.addView(root);
        setContentView(scroll);

        root.post(() -> {
            if (root.getChildCount() > 2) root.getChildAt(2).requestFocus();
        });
    }

    private void addButton(LinearLayout root, String label, String mode) {
        Button button = PremiumUi.primaryButton(this, label);
        button.setFocusable(true);
        button.setOnClickListener(v -> {
            Intent intent = new Intent(this, XtreamCatalogActivity.class);
            intent.putExtra("mode", mode);
            startActivity(intent);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(14);
        root.addView(button, lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

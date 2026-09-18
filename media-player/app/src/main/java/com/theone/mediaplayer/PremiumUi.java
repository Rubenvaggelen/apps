package com.theone.mediaplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

// v18 premium prototype
public final class PremiumUi {
    public static final int BG = Color.rgb(4, 7, 12);
    public static final int PANEL = Color.rgb(12, 18, 28);
    public static final int BLUE = Color.rgb(35, 190, 255);
    public static final int BLUE_SOFT = Color.rgb(77, 210, 255);
    public static final int MUTED = Color.rgb(150, 164, 180);

    private PremiumUi() {}

    public static LinearLayout brandHeader(Context context, String section) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 2), 0, dp(context, 8));

        BrandMarkView mark = new BrandMarkView(context);
        row.addView(mark, new LinearLayout.LayoutParams(dp(context, 52), dp(context, 52)));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(context, 12), 0, 0, 0);

        TextView brand = new TextView(context);
        brand.setText("THE ONE  MEDIA");
        brand.setTextColor(BLUE_SOFT);
        brand.setTextSize(12);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setLetterSpacing(0.14f);
        titles.addView(brand);

        TextView product = new TextView(context);
        product.setText(section == null || section.isEmpty() ? "PLAYER" : section.toUpperCase());
        product.setTextColor(Color.WHITE);
        product.setTextSize(22);
        product.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titles.addView(product);

        row.addView(titles, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        return row;
    }

    public static Button primaryButton(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(context, 18), dp(context, 11), dp(context, 18), dp(context, 11));
        b.setMinHeight(dp(context, 50));
        b.setBackground(gradient(
                context,
                Color.rgb(11, 88, 132),
                Color.rgb(24, 166, 218),
                24,
                Color.rgb(76, 211, 255),
                1
        ));
        b.setElevation(dp(context, 5));
        b.setStateListAnimator(null);
        return b;
    }

    public static Button chipButton(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        b.setMinHeight(dp(context, 42));
        b.setBackground(gradient(
                context,
                Color.rgb(16, 27, 41),
                Color.rgb(10, 18, 28),
                22,
                Color.rgb(38, 112, 148),
                1
        ));
        b.setElevation(dp(context, 2));
        b.setStateListAnimator(null);
        return b;
    }

    public static EditText searchField(Context context, String hint) {
        EditText e = new EditText(context);
        e.setHint("⌕  " + hint);
        e.setHintTextColor(Color.rgb(116, 135, 153));
        e.setTextColor(Color.WHITE);
        e.setTextSize(17);
        e.setSingleLine(true);
        e.setPadding(dp(context, 18), dp(context, 13), dp(context, 18), dp(context, 13));
        e.setBackground(gradient(
                context,
                Color.rgb(14, 22, 34),
                Color.rgb(9, 14, 23),
                18,
                Color.rgb(34, 83, 108),
                1
        ));
        e.setElevation(dp(context, 2));
        return e;
    }

    public static GradientDrawable card(Context context) {
        return gradient(
                context,
                Color.rgb(16, 24, 37),
                Color.rgb(8, 13, 21),
                20,
                Color.rgb(31, 67, 88),
                1
        );
    }

    public static GradientDrawable badge(Context context) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.rgb(11, 70, 104));
        d.setCornerRadius(dp(context, 12));
        d.setStroke(dp(context, 1), Color.rgb(60, 190, 240));
        return d;
    }

    private static GradientDrawable gradient(
            Context context,
            int start,
            int end,
            int radiusDp,
            int stroke,
            int strokeDp
    ) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{start, end}
        );
        d.setCornerRadius(dp(context, radiusDp));
        d.setStroke(dp(context, strokeDp), stroke);
        return d;
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static class BrandMarkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path playPath = new Path();

        public BrandMarkView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) * 0.43f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(5, 10, 17));
            paint.setShadowLayer(dp(getContext(), 9), 0, 0, Color.rgb(20, 160, 230));
            canvas.drawCircle(cx, cy, r, paint);
            paint.clearShadowLayer();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(getContext(), 3));
            paint.setColor(Color.rgb(40, 194, 255));
            canvas.drawCircle(cx, cy, r, paint);

            paint.setStrokeWidth(dp(getContext(), 1));
            paint.setColor(Color.rgb(43, 102, 138));
            canvas.drawCircle(cx, cy, r * 0.70f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(18, 83, 119));
            canvas.drawCircle(cx, cy, r * 0.60f, paint);

            float s = r * 0.42f;
            playPath.reset();
            playPath.moveTo(cx - s * 0.55f, cy - s);
            playPath.lineTo(cx + s, cy);
            playPath.lineTo(cx - s * 0.55f, cy + s);
            playPath.close();
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(dp(getContext(), 5), 0, 0, Color.rgb(75, 210, 255));
            canvas.drawPath(playPath, paint);
            paint.clearShadowLayer();

            paint.setColor(Color.rgb(75, 210, 255));
            canvas.drawCircle(cx + r * 0.70f, cy - r * 0.42f, dp(getContext(), 2), paint);
        }
    }
}

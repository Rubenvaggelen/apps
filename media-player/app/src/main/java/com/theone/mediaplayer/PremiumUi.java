package com.theone.mediaplayer;

import android.content.Context;
import android.widget.ImageView;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

// v18 premium prototype
public final class PremiumUi {
    public static final int BG = Color.rgb(5, 11, 25); // The One Midnight Navy
    public static final int PANEL = Color.rgb(11, 30, 54); // Deep Ocean
    public static final int BLUE = Color.rgb(29, 151, 255); // Electric Blue
    public static final int BLUE_SOFT = Color.rgb(63, 224, 255); // Neon Cyan
    public static final int MUTED = Color.rgb(150, 164, 180);

    private PremiumUi() {}

    public static LinearLayout brandHeader(Context context, String section) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 2), 0, dp(context, 8));

        ImageView mark = new ImageView(context);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mark.setAdjustViewBounds(true);
        row.addView(mark, new LinearLayout.LayoutParams(dp(context, 58), dp(context, 58)));
        // Author-approved blue neon remote artwork bundled locally for offline use.
        mark.setImageResource(R.drawable.remote_final);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(context, 12), 0, 0, 0);

        TextView brand = new TextView(context);
        brand.setText("THE ONE");
        brand.setTextColor(BLUE_SOFT);
        brand.setTextSize(12);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setLetterSpacing(0.14f);
        titles.addView(brand);

        TextView product = new TextView(context);
        product.setText(section == null || section.isEmpty() ? "MEDIA PLAYER" : "MEDIA PLAYER • " + section.toUpperCase());
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

    /** The One Family visual identity shared by phone, TV and Adult library. */
    public static GradientDrawable familyTileBackground(Context context, boolean focused) {
        GradientDrawable shape = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            focused
                ? new int[]{Color.rgb(12, 42, 89), Color.rgb(16, 72, 128), Color.rgb(28, 26, 80)}
                : new int[]{Color.rgb(10, 25, 50), Color.rgb(10, 38, 73), Color.rgb(15, 25, 53)}
        );
        shape.setCornerRadius(dp(context, 23));
        shape.setStroke(dp(context, focused ? 2 : 1),
            focused ? Color.rgb(89, 235, 255) : Color.rgb(30, 99, 151));
        return shape;
    }

    /**
     * The One Family grid tile: square-ish Midnight Navy card, neon pictogram,
     * and bold title. Shared verbatim between Android touch and Google TV DPAD.
     */
    public static LinearLayout familyGridTile(Context context, String symbol,
                                               String title, Runnable action) {
        LinearLayout tile=new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(context,12),dp(context,14),dp(context,12),dp(context,12));
        tile.setBackground(familyTileBackground(context,false));
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setElevation(dp(context,7));

        TextView pictogram=new TextView(context);
        pictogram.setText(symbol);
        pictogram.setTextSize(40);
        pictogram.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        pictogram.setTextColor(Color.rgb(65,220,255));
        pictogram.setGravity(Gravity.CENTER);
        pictogram.setShadowLayer(dp(context,8),0,0,Color.rgb(16,143,255));
        tile.addView(pictogram,new LinearLayout.LayoutParams(-1,dp(context,62)));

        TextView name=new TextView(context);
        name.setText(title);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        name.setTextColor(Color.WHITE);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        tile.addView(name,new LinearLayout.LayoutParams(-1,-2));

        tile.setOnClickListener(v->action.run());
        tile.setOnFocusChangeListener((v,focused)->{
            tile.setBackground(familyTileBackground(context,focused));
            tile.setScaleX(focused?1.025f:1f);
            tile.setScaleY(focused?1.025f:1f);
        });
        return tile;
    }

    public static void addFamilyGridRow(Context context, LinearLayout parent,
                                        String[] symbols, String[] labels,
                                        Runnable[] actions) {
        LinearLayout row=new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int count=Math.min(symbols.length,Math.min(labels.length,actions.length));
        for(int i=0;i<count;i++) {
            LinearLayout tile=familyGridTile(context,symbols[i],labels[i],actions[i]);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                0,dp(context,153),1f);
            int gap=dp(context,7);
            lp.setMargins(gap,gap,gap,gap);
            row.addView(tile,lp);
        }
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    public static TextView familyFooter(Context context) {
        TextView footer = new TextView(context);
        footer.setText("Part of The One Family");
        footer.setTextColor(Color.rgb(119, 170, 205));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        footer.setLetterSpacing(0.07f);
        footer.setPadding(0, dp(context, 18), 0, dp(context, 12));
        return footer;
    }

    public static Button primaryButton(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setLetterSpacing(0.02f);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(context, 18), dp(context, 11), dp(context, 18), dp(context, 11));
        b.setMinHeight(dp(context, 48));

        // Zelfde donkere neon The One-look als de categorieknoppen.
        b.setBackground(familyTileBackground(context, false));
        b.setOnFocusChangeListener((v, hasFocus) -> {
            b.setBackground(familyTileBackground(context, hasFocus));
            b.setScaleX(hasFocus ? 1.015f : 1f);
            b.setScaleY(hasFocus ? 1.015f : 1f);
        });
        b.setElevation(dp(context, 4));
        b.setStateListAnimator(null);
        return b;
    }

    public static Button chipButton(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setLetterSpacing(0.015f);
        b.setAllCaps(false);
        b.setPadding(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9));
        b.setMinHeight(dp(context, 44));
        b.setBackground(familyTileBackground(context, false));
        b.setOnFocusChangeListener((v, hasFocus) ->
            b.setBackground(familyTileBackground(context, hasFocus)));
        b.setElevation(dp(context, 4));
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

    private static RippleDrawable neonButtonBackground(Context context, boolean primary) {
        int[] colors = primary
                ? new int[]{
                        Color.rgb(5, 74, 122),
                        Color.rgb(13, 151, 214),
                        Color.rgb(47, 205, 255)
                }
                : new int[]{
                        Color.rgb(10, 18, 30),
                        Color.rgb(14, 42, 62),
                        Color.rgb(10, 24, 38)
                };

        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                colors
        );
        shape.setCornerRadius(dp(context, primary ? 26 : 22));
        shape.setStroke(
                dp(context, 1),
                primary ? Color.rgb(104, 226, 255) : Color.rgb(48, 141, 184)
        );

        ColorStateList ripple = ColorStateList.valueOf(
                primary ? Color.argb(95, 255, 255, 255) : Color.argb(75, 86, 210, 255)
        );
        return new RippleDrawable(ripple, shape, null);
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

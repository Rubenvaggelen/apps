package com.theone.mediaplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;

/**
 * Reliable D-pad search entry for Android TV/Google TV.
 * Does not depend on the availability of a vendor-provided TV IME.
 */
public final class AdultTvKeyboard {
    private AdultTvKeyboard() {}

    public static void show(Activity activity, String initial, Consumer<String> onSearch) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity,12),dp(activity,12),dp(activity,12),dp(activity,12));
        scroll.addView(body);

        TextView hint=new TextView(activity);
        hint.setText("Zoekterm — kies letters met de afstandsbediening en druk op Zoeken");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        body.addView(hint);

        EditText field=PremiumUi.searchField(activity,"Zoekterm");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setText(initial==null?"":initial);
        field.setSelection(field.length());
        body.addView(field);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("Pornhub • zoeken (TV)")
            .setView(scroll)
            .setNegativeButton("Annuleren", (d,w)->{})
            .create();

        Runnable submit=()->{
            String query=field.getText().toString().trim();
            if(query.length()<2) {
                Toast.makeText(activity,"Vul minimaal 2 tekens in.",Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            onSearch.accept(query);
        };
        field.setOnEditorActionListener((text,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH){submit.run();return true;}
            return false;
        });

        String[] keys={"ABCDEFGHIJ","KLMNOPQRST","UVWXYZ0123","456789"};
        for(String rowText:keys) {
            LinearLayout row=new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for(int i=0;i<rowText.length();i++){
                final char character=rowText.charAt(i);
                Button letter=key(activity,String.valueOf(character));
                letter.setOnClickListener(v->{
                    int start=Math.max(0,field.getSelectionStart());
                    field.getText().insert(start,Character.toString(character));
                });
                row.addView(letter,new LinearLayout.LayoutParams(0,dp(activity,46),1f));
            }
            body.addView(row);
        }
        LinearLayout actions=new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button space=key(activity,"Spatie");
        space.setOnClickListener(v->{
            int start=Math.max(0,field.getSelectionStart());
            field.getText().insert(start," ");
        });
        actions.addView(space,new LinearLayout.LayoutParams(0,dp(activity,52),1f));
        Button erase=key(activity,"⌫");
        erase.setOnClickListener(v->{
            int start=field.getSelectionStart();
            if(start>0)field.getText().delete(start-1,start);
        });
        actions.addView(erase,new LinearLayout.LayoutParams(0,dp(activity,52),1f));
        Button clear=key(activity,"Wissen");
        clear.setOnClickListener(v->field.setText(""));
        actions.addView(clear,new LinearLayout.LayoutParams(0,dp(activity,52),1f));
        body.addView(actions);

        Button search=PremiumUi.primaryButton(activity,"Zoeken");
        search.setOnClickListener(v->submit.run());
        search.setFocusable(true);
        body.addView(search,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,58)));
        dialog.setOnShowListener(ignored->{
            // Prefer a working, guaranteed on-screen button over a missing TV IME.
            search.requestFocus();
        });
        dialog.show();
    }

    private static Button key(Activity activity,String text){
        Button key=PremiumUi.chipButton(activity,text);
        key.setFocusable(true);
        key.setAllCaps(false);
        key.setTextSize(14);
        key.setGravity(Gravity.CENTER);
        key.setPadding(0,0,0,0);
        return key;
    }
    private static int dp(Activity activity,int v){
        return Math.round(v*activity.getResources().getDisplayMetrics().density);
    }
}

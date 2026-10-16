package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Dedicated, independently approved XXX home; no adult entries leak into regular browsing. */
public final class AdultHubActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaPlayerAdultGate.request(this,this::showHome);
    }
    private void showHome() {
        if(!MediaPlayerAdultGate.isUnlocked()){finish();return;}
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(PremiumUi.BG);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(34),dp(26),dp(34),dp(30));
        scroll.addView(root);
        root.addView(PremiumUi.brandHeader(this,"XXX • Afgeschermd"));
        TextView info=new TextView(this);
        info.setText("Deze bibliotheek is apart beveiligd en staat los van de gewone Media Player.");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(17);
        info.setPadding(0,dp(22),0,dp(18));
        root.addView(info);
        add(root,"Live TV","live");
        add(root,"Films","movie");
        add(root,"Series","series");
        Button pornhub=PremiumUi.primaryButton(this,"Pornhub • video's vanaf 5 minuten");
        pornhub.setFocusable(true);
        pornhub.setOnClickListener(v->{
            if(!MediaPlayerAdultGate.isUnlocked()){finish();return;}
            startActivity(new Intent(this,AdultPornhubActivity.class));
        });
        LinearLayout.LayoutParams phLp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        phLp.bottomMargin=dp(12);
        root.addView(pornhub,phLp);
        Button lock=PremiumUi.chipButton(this,"Vergrendelen en terug");
        lock.setOnClickListener(v->{MediaPlayerAdultGate.lock();finish();});
        root.addView(lock);
        setContentView(scroll);
    }
    private void add(LinearLayout root,String label,String mode) {
        Button button=PremiumUi.primaryButton(this,label);
        button.setFocusable(true);
        button.setOnClickListener(v->{
            if(!MediaPlayerAdultGate.isUnlocked()){finish();return;}
            Intent intent=new Intent(this,XtreamCatalogActivity.class);
            intent.putExtra("mode",mode);
            intent.putExtra("adult_only",true);
            startActivity(intent);
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin=dp(12);
        root.addView(button,lp);
    }
    @Override protected void onDestroy() {
        if(isFinishing())MediaPlayerAdultGate.lock();
        super.onDestroy();
    }
    private int dp(int value){
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
}

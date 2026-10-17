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
        LinearLayout screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(PremiumUi.BG);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(26),dp(24),dp(26),dp(16));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        root.addView(PremiumUi.brandHeader(this,"XXX • Afgeschermd"));
        TextView info=new TextView(this);
        info.setText("Deze bibliotheek is apart beveiligd en staat los van de gewone Media Player.");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(16);
        info.setPadding(0,dp(18),0,dp(12));
        root.addView(info);

        PremiumUi.addFamilyGridRow(this,root,
            new String[]{"▣","▶"},new String[]{"Live TV","Films"},
            new Runnable[]{()->openAdultCatalog("live"),()->openAdultCatalog("movie")});
        PremiumUi.addFamilyGridRow(this,root,
            new String[]{"▤","✦"},new String[]{"Series","Pornhub 5+ min"},
            new Runnable[]{()->openAdultCatalog("series"),()->{
                if(!MediaPlayerAdultGate.isUnlocked()){finish();return;}
                // Keep the released Google TV v1021 playback activity untouched.
                // Only touchscreen Android opens the dedicated phone player.
                Class<?> target=getPackageName().endsWith(".tv")
                    ? AdultPornhubActivity.class : AndroidPornhubActivity.class;
                startActivity(new Intent(this,target));
            }});
        Button lock=PremiumUi.primaryButton(this,"Vergrendelen en terug");
        lock.setOnClickListener(v->{MediaPlayerAdultGate.lock();finish();});
        LinearLayout.LayoutParams lockLp=new LinearLayout.LayoutParams(-1,-2);
        lockLp.topMargin=dp(14);
        root.addView(lock,lockLp);
        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));
        screen.addView(PremiumUi.familyFooter(this),
            new LinearLayout.LayoutParams(-1,-2));
        setContentView(screen);
    }

    private void openAdultCatalog(String mode) {
        if(!MediaPlayerAdultGate.isUnlocked()){finish();return;}
        Intent intent=new Intent(this,XtreamCatalogActivity.class);
        intent.putExtra("mode",mode);
        intent.putExtra("adult_only",true);
        startActivity(intent);
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

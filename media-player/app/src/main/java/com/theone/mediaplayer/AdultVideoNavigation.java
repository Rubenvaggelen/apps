package com.theone.mediaplayer;

/** Pure, deterministic navigation through an already-filtered video catalog. */
final class AdultVideoNavigation {
    private AdultVideoNavigation() {}
    static int adjacent(int currentIndex,int listSize,int direction){
        if(listSize<=0 || currentIndex<0 || currentIndex>=listSize || direction==0)return -1;
        int candidate=currentIndex+(direction<0?-1:1);
        return candidate>=0 && candidate<listSize?candidate:-1;
    }
}

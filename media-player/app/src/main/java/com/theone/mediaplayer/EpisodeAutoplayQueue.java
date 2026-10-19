package com.theone.mediaplayer;
import java.util.ArrayList;
import java.util.List;
/** The manually selected episode and the following episodes of that series. */
final class EpisodeAutoplayQueue {
 private EpisodeAutoplayQueue(){}
 static List<Integer> upcomingIndices(int selectedIndex,int episodeCount){
  List<Integer> indices=new ArrayList<>();
  if(selectedIndex<0||selectedIndex>=episodeCount)return indices;
  for(int i=selectedIndex;i<episodeCount;i++)indices.add(i);
  return indices;
 }
}

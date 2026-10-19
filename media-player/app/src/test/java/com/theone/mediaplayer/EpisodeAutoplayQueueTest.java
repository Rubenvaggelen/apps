package com.theone.mediaplayer;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
public class EpisodeAutoplayQueueTest {
 @Test public void startsAtSelectedEpisodeAndContinuesInOrder(){
  assertEquals(Arrays.asList(1,2,3),EpisodeAutoplayQueue.upcomingIndices(1,4));
 }
 @Test public void doesNotLoopBackAtTheEnd(){
  assertEquals(Collections.singletonList(3),EpisodeAutoplayQueue.upcomingIndices(3,4));
 }
 @Test public void neverAutoPlaysWithoutASelectedEpisode(){
  assertTrue(EpisodeAutoplayQueue.upcomingIndices(-1,4).isEmpty());
  assertTrue(EpisodeAutoplayQueue.upcomingIndices(4,4).isEmpty());
  assertTrue(EpisodeAutoplayQueue.upcomingIndices(0,0).isEmpty());
 }
}

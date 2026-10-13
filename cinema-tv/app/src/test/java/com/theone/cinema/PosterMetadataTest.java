package com.theone.cinema;
import org.json.*;import org.junit.Test;import static org.junit.Assert.*;
public class PosterMetadataTest {
 @Test public void picksCoverFromProviderInfo()throws Exception{assertEquals("https://example.test/cover.jpg",PosterMetadata.choose(new JSONObject("{\"info\":{\"movie_image\":\"https://example.test/cover.jpg\"}}"),"https://provider.test"));}
 @Test public void missingPrimaryImageUsesMovieData()throws Exception{assertEquals("https://example.test/other.jpg",PosterMetadata.choose(new JSONObject("{\"info\":{\"movie_image\":\"null\"},\"movie_data\":{\"cover\":\"https://example.test/other.jpg\"}}"),"https://provider.test"));}
 @Test public void relativePosterUsesCorrectProvider(){assertEquals("http://provider.test/images/a.jpg",PosterMetadata.resolve("/images/a.jpg","http://provider.test"));assertEquals("https://cdn.test/a.jpg",PosterMetadata.resolve("//cdn.test/a.jpg","https://provider.test"));}
 @Test public void invalidPosterDoesNotBecomeNetworkRequest(){assertEquals("",PosterMetadata.resolve("javascript:bad","https://provider.test"));assertEquals("",PosterMetadata.resolve("null","https://provider.test"));}
}

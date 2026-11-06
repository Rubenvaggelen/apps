package com.theone.mediaplayer;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class XtreamSeriesCategoryRecoveryTest {
    @Test public void safeCategoryIdsDeduplicateAndPreserveOrder() throws Exception {
        JSONArray categories = new JSONArray()
            .put(new JSONObject().put("category_id","10"))
            .put(new JSONObject().put("category_id","10"))
            .put(new JSONObject().put("category_id","99"))
            .put(new JSONObject().put("category_id","source2:99"))
            .put(new JSONObject().put("category_id","1&invalid=true"));
        assertEquals(Arrays.asList("10","99"), XtreamConfig.seriesCategoryIds(categories));
    }
    @Test public void emptyCategoryListNeverClaimsFullLibrary() {
        assertTrue(XtreamConfig.seriesCategoryIds(new JSONArray()).isEmpty());
    }
}

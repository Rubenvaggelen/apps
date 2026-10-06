package com.gmailorg.hub
import org.junit.Assert.*
import org.junit.Test

class SupermarketLookupParserTest {
    @Test fun readsNodesAndBuildingCentersAndDeduplicates() {
        val body = """{"elements":[{"lat":52.339,"lon":4.872},{"center":{"lat":52.34,"lon":4.87}},{"lat":52.339,"lon":4.872},{"id":9},{"lat":999,"lon":4.87}]}"""
        assertEquals(listOf(52.339 to 4.872, 52.34 to 4.87), SupermarketLookupParser.parse(body))
    }
    @Test fun distinguishesAnEmptySuccessfulSearch() {
        assertTrue(SupermarketLookupParser.parse("""{"elements":[]}""").isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPartialTimeout() {
        SupermarketLookupParser.parse("""{"remark":"runtime error: Query timed out","elements":[]}""")
    }
    @Test(expected = org.json.JSONException::class) fun rejectsServerErrorPage() {
        SupermarketLookupParser.parse("<html>Server unavailable</html>")
    }
}


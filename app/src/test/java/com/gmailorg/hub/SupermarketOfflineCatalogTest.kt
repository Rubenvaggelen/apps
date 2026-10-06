package com.gmailorg.hub

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SupermarketOfflineCatalogTest {
    @Test fun releasedSnapshotHasNationalAndAlmereBuitenCoverage() {
        val file = File("src/main/assets/supermarkets-nl.json")
        assertTrue("Build must bundle offline data", file.isFile)
        val body = file.readText()
        val json = JSONObject(body)
        val points = SupermarketLookupParser.parse(body)
        assertTrue(points.size >= 1000)
        assertTrue(points.all { it.first in 50.7..53.7 && it.second in 3.2..7.3 })
        assertTrue(points.any { it.first in 52.375..52.415 && it.second in 5.235..5.305 })
        assertEquals("ODbL 1.0", json.getString("license"))
        assertTrue(json.getString("generated_at").isNotBlank())
    }
}

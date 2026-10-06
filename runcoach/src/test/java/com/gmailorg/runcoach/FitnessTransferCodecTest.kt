package com.gmailorg.runcoach
import org.junit.Assert.*
import org.junit.Test
class FitnessTransferCodecTest {
    @Test fun keepsProfileTypesAndAllTrainingDates() {
        val values = mapOf("profile_set" to true, "profile_age" to 51, "profile_weight_kg" to 80.5f,
            "profile_goal" to "Conditie", "profile_gym_weekend" to false,
            "completed_2026-10-01" to true, "completed_2025-01-01" to false, "weight_date" to "2026-10-06")
        assertEquals(values, FitnessTransferCodec.decode(FitnessTransferCodec.encode(values)))
    }
    @Test fun excludesKeysOutsideFitnessAndInvalidDates() {
        val values = mapOf("api_key" to "secret", "runcoach" to "anything",
            "completed_2026-99-99" to true, "profile_set" to true)
        assertEquals(mapOf("profile_set" to true), FitnessTransferCodec.decode(FitnessTransferCodec.encode(values)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownSchema() {
        FitnessTransferCodec.decode("""{"version":2,"values":[]}""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongDeclaredTypes() {
        FitnessTransferCodec.decode("""{"version":1,"values":[{"key":"profile_age","type":"string","value":"51"}]}""")
    }
}

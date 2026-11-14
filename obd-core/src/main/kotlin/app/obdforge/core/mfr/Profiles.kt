package app.obdforge.core.mfr

import app.obdforge.core.dtc.DtcDatabase
import app.obdforge.core.obd.ObdClient
import app.obdforge.core.util.Hex
import app.obdforge.core.util.Json
import app.obdforge.core.util.asDouble
import app.obdforge.core.util.asList
import app.obdforge.core.util.asMap
import app.obdforge.core.util.asStr

/**
 * A manufacturer-specific value: request [service] (0x21 or 0x22 typically) with [identifier]
 * to the ECU at [requestId], take the response data after the echoed identifier, and apply
 * [formula] (A = first data byte).
 */
data class EnhancedPid(
    val key: String,
    val name: String,
    val unit: String,
    val requestId: Int,
    val service: Int,
    val identifier: Int,
    val identifierBytes: Int,
    val formula: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 1,
) {
    val expression by lazy { Expression(formula) }

    fun requestHex(): String = Hex.byte(service) + (if (identifierBytes == 2) Hex.byte(identifier shr 8) else "") + Hex.byte(identifier and 0xFF)

    /** Reads and decodes this value through [client]. Returns null + reason if it fails. */
    fun read(client: ObdClient): Pair<Double?, String?> = client.withPhysicalAddress(requestId) {
        val params = if (identifierBytes == 2) intArrayOf(identifier shr 8, identifier and 0xFF) else intArrayOf(identifier and 0xFF)
        val r = client.request(service, *params, timeoutMs = 2_000)
        val p = r.first()
        if (p == null) null to (r.negative.values.firstOrNull()?.let { app.obdforge.core.obd.ServiceResult.nrcText(it) } ?: r.error?.meaning ?: "No answer")
        else {
            val data = p.copyOfRange(identifierBytes.coerceAtMost(p.size), p.size)
            try { expression.eval(data) to null } catch (e: Exception) { null to (e.message ?: "Formula error") }
        }
    }
}

data class EcuAddress(val name: String, val requestId: Int, val responseId: Int)

data class ManufacturerProfile(
    val id: String,
    val make: String,
    val title: String,
    /** What standard OBD-II gives you on these vehicles vs what needs enhanced access. */
    val accessNotes: List<String>,
    val ecus: List<EcuAddress>,
    val enhancedPids: List<EnhancedPid>,
    val dtcDescriptions: Map<String, String>,
    /** Standard channel keys most useful for this platform, used to build a preset dashboard. */
    val recommendedChannels: List<String>,
    val builtIn: Boolean,
    val source: String,
) {
    fun toJson(): String = Json.write(mapOf(
        "format" to "obdforge-profile-1", "id" to id, "make" to make, "title" to title, "accessNotes" to accessNotes,
        "source" to source,
        "ecus" to ecus.map { mapOf("name" to it.name, "requestId" to Hex.byte(it.requestId shr 8) + Hex.byte(it.requestId and 0xFF), "responseId" to Hex.byte(it.responseId shr 8) + Hex.byte(it.responseId and 0xFF)) },
        "enhancedPids" to enhancedPids.map { mapOf("key" to it.key, "name" to it.name, "unit" to it.unit,
            "requestId" to Integer.toHexString(it.requestId).uppercase(), "service" to Hex.byte(it.service), "identifier" to Integer.toHexString(it.identifier).uppercase(),
            "identifierBytes" to it.identifierBytes, "formula" to it.formula, "min" to it.min, "max" to it.max, "decimals" to it.decimals) },
        "dtcs" to dtcDescriptions, "recommendedChannels" to recommendedChannels,
    ), pretty = true)

    companion object {
        /** Parses a profile file. Formulas are validated so a bad file is rejected up front. */
        fun fromJson(text: String): ManufacturerProfile {
            val m = Json.parse(text).asMap()
            require(m["format"] == "obdforge-profile-1") { "Not an OBD Forge profile file (format must be obdforge-profile-1)" }
            val make = m["make"].asStr() ?: throw IllegalArgumentException("Profile has no make")
            val pids = m["enhancedPids"].asList().map { e ->
                val x = e.asMap()
                EnhancedPid(
                    key = "enh_" + (x["key"].asStr() ?: throw IllegalArgumentException("Enhanced PID without key")),
                    name = x["name"].asStr() ?: "Unnamed", unit = x["unit"].asStr() ?: "",
                    requestId = (x["requestId"].asStr() ?: "7E0").toInt(16),
                    service = (x["service"].asStr() ?: "22").toInt(16),
                    identifier = (x["identifier"].asStr() ?: throw IllegalArgumentException("Enhanced PID without identifier")).toInt(16),
                    identifierBytes = x["identifierBytes"].asDouble()?.toInt() ?: 2,
                    formula = (x["formula"].asStr() ?: "A").also { Expression(it) },
                    min = x["min"].asDouble() ?: 0.0, max = x["max"].asDouble() ?: 100.0, decimals = x["decimals"].asDouble()?.toInt() ?: 1,
                )
            }
            val ecus = m["ecus"].asList().map { e -> val x = e.asMap()
                EcuAddress(x["name"].asStr() ?: "ECU", (x["requestId"].asStr() ?: "7E0").toInt(16), (x["responseId"].asStr() ?: "7E8").toInt(16)) }
            @Suppress("UNCHECKED_CAST")
            val dtcs = (m["dtcs"] as? Map<String, Any?>)?.mapValues { it.value.toString() } ?: emptyMap()
            return ManufacturerProfile(
                m["id"].asStr() ?: ("user-" + make.lowercase()), make, m["title"].asStr() ?: "$make (imported)",
                m["accessNotes"].asList().mapNotNull { it.asStr() }, ecus, pids, dtcs,
                m["recommendedChannels"].asList().mapNotNull { it.asStr() }, false, m["source"].asStr() ?: "Imported by user",
            )
        }
    }
}

/**
 * Built-in profiles. They contain only what is standardised or verifiable: the ISO 15765-4
 * diagnostic addresses (7E0/7E8 engine, 7E1/7E9 transmission), standard identification DIDs,
 * honest notes on what each make needs for enhanced access, and recommended standard channels.
 * They deliberately contain no manufacturer PIDs or DTC texts: those must come from a profile
 * file the user imports (e.g. one they built from their service information), so nothing is
 * invented.
 */
object Profiles {
    private val obdAddresses = listOf(EcuAddress("Engine (ECM)", 0x7E0, 0x7E8), EcuAddress("Transmission (TCM)", 0x7E1, 0x7E9))
    private val user = LinkedHashMap<String, ManufacturerProfile>()

    private val turboSet = listOf("rpm", "speed", "boost", "map", "baro", "iat", "maf", "timing", "load", "tps", "stft1", "ltft1", "ect", "ecu_voltage")
    private val diSet = listOf("frp", "frp_abs", "lambda_cmd", "o2w1_lambda", "o2c1_lambda", "cat_b1s1")

    private fun p(id: String, make: String, title: String, notes: List<String>, rec: List<String>) =
        ManufacturerProfile(id, make, title, notes, obdAddresses, emptyList(), emptyMap(), rec, true, "Built-in (standard OBD-II only)")

    private val common = "Standard OBD-II (Modes 01–0A) works on all OBD-II compliant models through any ELM327-compatible adapter."

    val builtIn: List<ManufacturerProfile> = listOf(
        p("subaru-fa20dit", "Subaru", "Subaru FA20DIT (e.g. Forester SJ XT / Levorg / WRX)", listOf(
            common,
            "FA20DIT is direct-injected and turbocharged: rail pressure (PID 23), boost (MAP − baro), timing (PID 0E, cylinder 1), commanded λ (PID 44) and the front air-fuel sensor (PID 24/34) are usually available as standard PIDs. Availability depends on the ECU; this app only shows what the ECU reports as supported.",
            "Per-cylinder misfire counts are only available if the ECU reports Mode 06 misfire monitors.",
            "CVT fluid temperature, per-cylinder knock/fine learning (FLKC), DAM, requested boost, wastegate duty and Subaru-specific codes are NOT part of standard OBD-II. Subaru exposes them through its own protocol (SSM) or manufacturer data identifiers. Import a profile file with the definitions for your exact ECU to add them.",
            "Standard ELM327 hardware can carry these requests on CAN models, but the parameter addresses differ by ECU/ROM, so none are included without a verified source.",
        ), turboSet + diSet + listOf("oil_temp", "cat_b1s1")),
        p("subaru", "Subaru", "Subaru (other engines)", listOf(common, "Enhanced data (SSM / manufacturer DIDs) needs a profile with definitions for the specific ECU."), turboSet),
        p("toyota", "Toyota", "Toyota / Lexus", listOf(common, "Toyota enhanced data uses manufacturer-specific services and data IDs that vary by ECU; import a profile with verified definitions."), turboSet),
        p("honda", "Honda", "Honda / Acura", listOf(common, "Honda enhanced data varies by model and ECU generation; import a verified profile to add it."), turboSet),
        p("nissan", "Nissan", "Nissan / Infiniti", listOf(common, "Nissan enhanced data (Consult protocols) is not covered by standard OBD-II; import a verified profile to add it."), turboSet),
        p("mitsubishi", "Mitsubishi", "Mitsubishi", listOf(common, "Mitsubishi enhanced data (MUT) needs a verified profile."), turboSet),
        p("mazda", "Mazda", "Mazda", listOf(common, "Mazda enhanced data needs a verified profile."), turboSet + diSet),
        p("bmw", "BMW", "BMW / MINI", listOf(common, "BMW modules beyond the engine and transmission often sit behind a gateway and use UDS with BMW-specific addressing; many need a K+DCAN/ENET interface rather than an ELM327."), turboSet + diSet),
        p("mercedes", "Mercedes-Benz", "Mercedes-Benz", listOf(common, "Mercedes enhanced diagnostics use UDS/KWP with vehicle-specific data; many modules are not reachable on the OBD CAN pins without a gateway-aware interface."), turboSet + diSet),
        p("vw", "Volkswagen", "Volkswagen", listOf(common, "VAG enhanced diagnostics (measuring blocks/UDS) use VAG-specific addressing; many need a dedicated interface. Import a verified profile for values reachable via ELM327."), turboSet + diSet),
        p("audi", "Audi", "Audi", listOf(common, "Same platform rules as Volkswagen."), turboSet + diSet),
        p("ford", "Ford", "Ford", listOf(common, "Ford enhanced PIDs (Mode 22) exist for many modules but differ by platform; some are on the medium-speed CAN bus, which ELM327 clones often cannot reach."), turboSet + diSet),
        p("hyundai", "Hyundai", "Hyundai", listOf(common, "Hyundai/Kia enhanced data uses UDS identifiers that vary by ECU; import a verified profile."), turboSet + diSet),
        p("kia", "Kia", "Kia", listOf(common, "Same platform rules as Hyundai."), turboSet + diSet),
        p("generic", "Generic", "Generic OBD-II", listOf(common), turboSet),
    )

    fun all(): List<ManufacturerProfile> = builtIn + user.values

    fun byId(id: String?): ManufacturerProfile? = all().firstOrNull { it.id == id }

    fun forMake(make: String?): List<ManufacturerProfile> = all().filter { make != null && it.make.equals(make, true) }

    fun install(profile: ManufacturerProfile) {
        user[profile.id] = profile
        if (profile.dtcDescriptions.isNotEmpty()) DtcDatabase.addManufacturerCodes(profile.make, profile.dtcDescriptions)
    }

    fun remove(id: String) { user.remove(id) }

    /** A template users can fill in from their own service data. */
    fun template(make: String): String = ManufacturerProfile(
        "user-${make.lowercase()}-1", make, "$make enhanced (my definitions)",
        listOf("Fill in identifiers and formulas from your service information. Test each value against a known reading."),
        obdAddresses,
        listOf(EnhancedPid("example_value", "Example value (replace)", "unit", 0x7E0, 0x22, 0x1234, 2, "((A*256)+B)/10", 0.0, 100.0)),
        mapOf("P1XXX" to "Example manufacturer code text (replace)"), listOf("rpm", "speed"), false, "User template",
    ).toJson()
}

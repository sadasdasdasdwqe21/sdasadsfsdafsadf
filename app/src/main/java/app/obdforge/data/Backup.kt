package app.obdforge.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.obdforge.core.util.Json
import app.obdforge.core.util.asDouble
import app.obdforge.core.util.asList
import app.obdforge.core.util.asMap
import app.obdforge.core.util.asStr
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup to a zip of JSON files (vehicles, sessions with samples, logs, dashboards,
 * profiles). Import adds the data as new rows so it never overwrites existing records.
 */
class BackupManager(private val ctx: Context, private val db: AppDatabase) {

    suspend fun export(out: OutputStream) {
        val zip = ZipOutputStream(out)
        fun put(name: String, data: Any?) { zip.putNextEntry(ZipEntry(name)); zip.write(Json.write(data).toByteArray()); zip.closeEntry() }
        val vehicles = db.vehicles().list()
        put("vehicles.json", vehicles.map { vehicleMap(it) })
        put("service_log.json", db.misc().allServiceLog().map { mapOf("vehicleId" to it.vehicleId, "date" to it.date, "kind" to it.kind, "title" to it.title,
            "details" to it.details, "mileageKm" to it.mileageKm, "cost" to it.cost, "relatedCodes" to it.relatedCodes) })
        put("dashboards.json", db.misc().dashboardList().map { mapOf("name" to it.name, "position" to it.position, "json" to it.json, "preset" to it.preset) })
        put("profiles.json", db.misc().profiles().map { it.json })
        val sessions = db.sessions().list()
        put("sessions.json", sessions.map { mapOf("id" to it.id, "vehicleId" to it.vehicleId, "title" to it.title, "description" to it.description,
            "startedAt" to it.startedAt, "endedAt" to it.endedAt, "status" to it.status, "simulated" to it.simulated, "channels" to it.channels, "trigger" to it.trigger) })
        for (s in sessions) {
            zip.putNextEntry(ZipEntry("samples/${s.id}.csv"))
            val w = zip.bufferedWriter()
            db.sessions().allSamples(s.id).forEach { w.write("${it.channel},${it.t},${it.v}\n") }
            w.flush()
            zip.closeEntry()
        }
        zip.finish(); zip.flush()
    }

    private fun vehicleMap(v: VehicleEntity) = mapOf("id" to v.id, "name" to v.name, "make" to v.make, "model" to v.model, "year" to v.year,
        "engine" to v.engine, "engineCode" to v.engineCode, "transmission" to v.transmission, "fuelType" to v.fuelType, "vin" to v.vin,
        "mileageKm" to v.mileageKm, "ecuInfo" to v.ecuInfo, "profileId" to v.profileId, "displacementL" to v.displacementL, "cylinders" to v.cylinders,
        "massKg" to v.massKg, "dragArea" to v.dragArea, "drivetrainLossPct" to v.drivetrainLossPct, "drivetrain" to v.drivetrain, "tyreSize" to v.tyreSize,
        "turbocharged" to v.turbocharged, "boostLimitKpa" to v.boostLimitKpa, "notes" to v.notes)

    suspend fun import(input: InputStream): String {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes() } }
        val vJson = files["vehicles.json"]?.let { Json.parse(String(it)).asList() } ?: throw IllegalArgumentException("Not an OBD Forge backup (vehicles.json missing)")
        val idMap = HashMap<Long, Long>()
        var sessions = 0
        db.withTransaction {
            for (e in vJson) {
                val m = e.asMap()
                val newId = db.vehicles().insert(VehicleEntity(
                    name = m["name"].asStr() ?: "Vehicle", make = m["make"].asStr() ?: "", model = m["model"].asStr() ?: "",
                    year = m["year"].asDouble()?.toInt(), engine = m["engine"].asStr() ?: "", engineCode = m["engineCode"].asStr() ?: "",
                    transmission = m["transmission"].asStr() ?: "", fuelType = m["fuelType"].asStr() ?: "GASOLINE", vin = m["vin"].asStr() ?: "",
                    mileageKm = m["mileageKm"].asDouble(), ecuInfo = m["ecuInfo"].asStr() ?: "", profileId = m["profileId"].asStr() ?: "generic",
                    displacementL = m["displacementL"].asDouble() ?: 2.0, cylinders = m["cylinders"].asDouble()?.toInt() ?: 4,
                    massKg = m["massKg"].asDouble() ?: 1600.0, dragArea = m["dragArea"].asDouble() ?: 0.75,
                    drivetrainLossPct = m["drivetrainLossPct"].asDouble() ?: 15.0, drivetrain = m["drivetrain"].asStr() ?: "AWD",
                    tyreSize = m["tyreSize"].asStr() ?: "", turbocharged = m["turbocharged"] as? Boolean ?: false,
                    boostLimitKpa = m["boostLimitKpa"].asDouble() ?: 120.0, notes = m["notes"].asStr() ?: "",
                ))
                m["id"].asDouble()?.toLong()?.let { idMap[it] = newId }
            }
            files["service_log.json"]?.let { Json.parse(String(it)).asList() }?.forEach { e ->
                val m = e.asMap()
                val vid = idMap[m["vehicleId"].asDouble()?.toLong() ?: -1L] ?: return@forEach
                db.misc().insertService(ServiceLogEntity(vehicleId = vid, date = m["date"].asDouble()?.toLong() ?: 0, kind = m["kind"].asStr() ?: "REPAIR",
                    title = m["title"].asStr() ?: "", details = m["details"].asStr() ?: "", mileageKm = m["mileageKm"].asDouble(), cost = m["cost"].asDouble(),
                    relatedCodes = m["relatedCodes"].asStr() ?: ""))
            }
            files["profiles.json"]?.let { Json.parse(String(it)).asList() }?.forEach { j ->
                val text = j.asStr() ?: return@forEach
                runCatching { app.obdforge.core.mfr.ManufacturerProfile.fromJson(text) }.getOrNull()?.let { db.misc().putProfile(ProfileEntity(it.id, text)) }
            }
            files["sessions.json"]?.let { Json.parse(String(it)).asList() }?.forEach { e ->
                val m = e.asMap()
                val oldId = m["id"].asDouble()?.toLong() ?: return@forEach
                val newId = db.sessions().insert(SessionEntity(vehicleId = m["vehicleId"].asDouble()?.toLong()?.let { idMap[it] }, title = m["title"].asStr() ?: "Imported",
                    description = m["description"].asStr() ?: "", startedAt = m["startedAt"].asDouble()?.toLong() ?: 0, endedAt = m["endedAt"].asDouble()?.toLong(),
                    status = "COMPLETE", simulated = m["simulated"] as? Boolean ?: false, channels = m["channels"].asStr() ?: "", trigger = "import"))
                val rows = files["samples/$oldId.csv"]?.let { String(it) }?.lineSequence()?.mapNotNull { l ->
                    val p = l.split(','); if (p.size != 3) null else SampleEntity(newId, p[0], p[1].toLong(), p[2].toDouble())
                }?.toList() ?: emptyList()
                rows.chunked(5000).forEach { db.sessions().insertSamples(it) }
                db.sessions().get(newId)?.let { db.sessions().update(it.copy(sampleCount = rows.size.toLong())) }
                sessions++
            }
        }
        return "Imported ${vJson.size} vehicle(s) and $sessions session(s)."
    }

    /** Keeps the last 5 daily automatic backups in app storage. */
    suspend fun dailyBackupIfDue(settings: SettingsStore) {
        val day = System.currentTimeMillis() / 86_400_000
        if (!settings.value.dailyBackup || settings.value.lastBackupDay == day) return
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val f = File(dir, "auto_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.zip")
        f.outputStream().use { export(it) }
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        settings.update { it.copy(lastBackupDay = day) }
    }

    fun autoBackups(): List<File> = File(ctx.filesDir, "backups").listFiles()?.sortedByDescending { it.name }?.toList() ?: emptyList()

    suspend fun importFrom(uri: Uri): String = ctx.contentResolver.openInputStream(uri)?.use { import(it) } ?: throw IllegalArgumentException("Cannot open file")
}

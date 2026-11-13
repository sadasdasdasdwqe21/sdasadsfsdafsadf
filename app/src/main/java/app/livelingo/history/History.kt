package app.livelingo.history

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.livelingo.core.Languages
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Text only. Raw audio is never stored. */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "source_lang") val sourceLanguage: String?,
    @ColumnInfo(name = "target_lang") val targetLanguage: String,
    val original: String,
    val translation: String,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY created_at ASC")
    suspend fun all(): List<HistoryEntry>

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    /** Keeps the table bounded. */
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY created_at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao

    companion object {
        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "livelingo_history.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

class TranslationHistoryRepository(private val dao: HistoryDao) {
    fun recent(limit: Int = 300): Flow<List<HistoryEntry>> = dao.observeRecent(limit)

    suspend fun add(entry: HistoryEntry) {
        dao.insert(entry)
        dao.trim(2_000)
    }

    suspend fun clear() = dao.clear()
    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun exportText(): String = TranscriptFormatter.format(dao.all())
}

object TranscriptFormatter {
    fun format(entries: List<HistoryEntry>, locale: Locale = Locale.getDefault()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale)
        val sb = StringBuilder("LiveLingo transcript\n")
        sb.append("Exported ").append(fmt.format(Date())).append("\n\n")
        for (e in entries) {
            sb.append('[').append(fmt.format(Date(e.createdAt))).append("] ")
                .append(Languages.nameOf(e.sourceLanguage)).append(" → ").append(Languages.nameOf(e.targetLanguage)).append('\n')
            sb.append("  Original:    ").append(e.original).append('\n')
            sb.append("  Translation: ").append(e.translation).append("\n\n")
        }
        return sb.toString()
    }
}

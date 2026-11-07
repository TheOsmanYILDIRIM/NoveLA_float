package my.noveldokusha.feature.local_database.tables

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted source-term and target-language mapping for one NoveLA book URL. */
@Entity(indices = [Index(value = ["bookUrl", "sourceLang", "targetLang"])])
data class NovelLexTerm(
    @PrimaryKey val id: String,
    val bookUrl: String,
    val sourceLang: String,
    val targetLang: String,
    val original: String,
    val translated: String,
    val occurrenceCount: Int = 0,
    val locked: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

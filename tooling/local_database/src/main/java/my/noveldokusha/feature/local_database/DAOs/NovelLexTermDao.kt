package my.noveldokusha.feature.local_database.DAOs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import my.noveldokusha.feature.local_database.tables.NovelLexTerm

@Dao
interface NovelLexTermDao {
    @Query("SELECT * FROM NovelLexTerm WHERE bookUrl = :book AND sourceLang = :source AND targetLang = :target ORDER BY original")
    suspend fun forBook(book: String, source: String, target: String): List<NovelLexTerm>

    @Query("SELECT * FROM NovelLexTerm WHERE bookUrl = :book AND sourceLang = :source AND targetLang = :target ORDER BY original")
    fun observe(book: String, source: String, target: String): Flow<List<NovelLexTerm>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<NovelLexTerm>)

    @Query("SELECT COUNT(*) FROM NovelLexTerm")
    suspend fun count(): Int

    @Query("SELECT * FROM NovelLexTerm ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun getChunk(limit: Int, offset: Int): List<NovelLexTerm>

    @Query("DELETE FROM NovelLexTerm WHERE bookUrl IN (:books)")
    suspend fun deleteForBooks(books: List<String>)

    @Query("UPDATE NovelLexTerm SET bookUrl = :newBook WHERE bookUrl = :oldBook")
    suspend fun moveBook(oldBook: String, newBook: String)
}

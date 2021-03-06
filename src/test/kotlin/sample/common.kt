package sample

typealias AnimeId = Int
typealias PseudoDateTime = Long
typealias Score = Int

object AnimeIds {
    val A = 1
    val B = 2
    val C = 3
    val D = 4
    val E = 5
}

interface HasModifiedAt {
    val modifiedAt: PseudoDateTime
}

data class MyListItem(
        val animeId: AnimeId,
        val updatedAt: PseudoDateTime,
        val score: Score = 0
        ) : HasModifiedAt {

    override val modifiedAt: PseudoDateTime
        get() = updatedAt
}

/**
 *
 */
class ApiServerDb(initialData: Array<MyListItem> = emptyArray()) {
    data class DeletedMyListItem(
            val animeId: AnimeId,
            val deletedAt: PseudoDateTime) : HasModifiedAt {
        override val modifiedAt: PseudoDateTime
            get() = deletedAt
    }

    private val animeIdToMyListItem = mutableMapOf<AnimeId, MyListItem>()
    private val animeIdToDeletedMyListItem = mutableMapOf<AnimeId, DeletedMyListItem>()

    var now: PseudoDateTime = 0

    init {
        reset(*initialData)
    }

    /**
     * @return order by anime_id
     */
    val myListItems: List<MyListItem>
        get() = animeIdToMyListItem.values.sortedBy { it.animeId }

    /**
     * @return order by anime_id
     */
    val deletedMyListItems: List<DeletedMyListItem>
        get() = animeIdToDeletedMyListItem.values.sortedBy { it.animeId }

    fun reset(vararg items: MyListItem) {
        animeIdToMyListItem.clear()
        animeIdToDeletedMyListItem.clear()
        addMyListItems(*items)
    }

    fun addMyListItem(animeId: AnimeId, now: PseudoDateTime) {
        addMyListItems(MyListItem(animeId, updatedAt = now))
    }

    fun update(item: MyListItem) {
        addMyListItems(item)
    }

    fun deleteMyListItem(animeId: AnimeId, now: PseudoDateTime) {
        animeIdToMyListItem.remove(animeId)
        animeIdToDeletedMyListItem[animeId] = DeletedMyListItem(animeId, now)
    }

    /**
     * @deprecated
     */
    fun getDeletedMyListItem(deletedSince: PseudoDateTime): List<DeletedMyListItem> {
        return deletedMyListItems
                .filter { it.deletedAt > deletedSince }
    }

    private fun addMyListItems(vararg items: MyListItem) {
        animeIdToMyListItem.putAll(items.map { it.animeId to it })
        for (item in items) {
            animeIdToDeletedMyListItem.remove(item.animeId)
        }
    }
}

class LocalMyListDb {
    private val animeIdToMyListItem = mutableMapOf<AnimeId, MyListItem>()

    /**
     * order by animeId
     */
    val myListItems: List<MyListItem>
        get() = animeIdToMyListItem.values.sortedBy { it.animeId }

    fun upsert(items: List<MyListItem>) {
        animeIdToMyListItem.putAll(items.map { it.animeId to it })
    }

    fun upsert(item: MyListItem) {
        upsert(listOf(item))
    }

    fun delete(animeIds: List<AnimeId>) {
        for (animeId in animeIds) {
            animeIdToMyListItem.remove(animeId)
        }
    }

    fun delete(animeId: AnimeId) {
        delete(listOf(animeId))
    }
}

object Util {
    data class ListWithHasNext<out T : HasModifiedAt>(
            val items: List<T>,
            val hasNext: Boolean
    )

    /**
     */
    fun <T : HasModifiedAt> getItemsOrderByModifiedAtConsistently(
            all: List<T>,
            modifiedSince: PseudoDateTime,
            limit: Int): ListWithHasNext<T> {

        val resultItems = all
                .filter { it.modifiedAt > modifiedSince }
                .sortedBy { it.modifiedAt }
                .take(limit + 1)
        if (resultItems.size <= limit) {
            return ListWithHasNext(resultItems, hasNext = false)
        }

        val last = resultItems[limit - 1]
        val nextOfLast = resultItems[limit]

        if (last.modifiedAt != nextOfLast.modifiedAt) {
            return ListWithHasNext(resultItems.take(limit), hasNext = true)
        }

        val itemsWithSameModifiedAt = all.filter { it.modifiedAt == last.modifiedAt }
        val hasNext = all.find { it.modifiedAt > last.modifiedAt } != null

        return ListWithHasNext(
                resultItems.dropLast(2) + itemsWithSameModifiedAt,
                hasNext)
    }
}


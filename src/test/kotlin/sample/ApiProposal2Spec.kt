package sample

import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull


class ApiProposal2Spec : Spek({
    val serverDb = ApiServerDb()
    val api = ApiProposal2(serverDb)
    lateinit var localDb: LocalMyListDb

    fun LocalMyListDb.upsertOrDelete(items: List<ApiProposal2.MyListResponse.Item>) {
        for (item in items) {
            if (item.isDeleted) {
                this.delete(item.animeId)
            } else {
                this.upsert(MyListItem(animeId = item.animeId, updatedAt = item.modifiedAt))
            }
        }
    }

    describe("Proposal 2") {
        beforeGroup {
            localDb = LocalMyListDb()
        }

        val initialMyList = arrayOf(
                MyListItem(animeId = 1, updatedAt = 1),
                MyListItem(animeId = 2, updatedAt = 2),
                MyListItem(animeId = 3, updatedAt = 2), // same time
                MyListItem(animeId = 4, updatedAt = 3)
        )

        on("[edge case] delete item during pagination") {
            serverDb.reset(*initialMyList)

            val res1 = api.get_users_me_animelist_bulk(limit = 2)
            localDb.upsertOrDelete(res1.data)

            it("should have next") {
                assertNotNull(res1.after)
            }

            serverDb.deleteMyListItem(animeId = 1, now = 4)

            val res2 = api.get_users_me_animelist_bulk(
                    limit = 2, after = res1.after!!)
            localDb.upsertOrDelete(res2.data)

            it("should have next") {
                assertNotNull(res1.after)
            }

            val res3 = api.get_users_me_animelist_bulk(
                    limit = 2, after = res2.after!!)
            localDb.upsertOrDelete(res3.data)

            it("should not have next") {
                assertNull(res3.after)
            }

            it("is equal to server data") {
                assertEquals(serverDb.myListItems, localDb.myListItems)
            }

            // save for `get the changes since the last time we loaded the list`
            // lastLoadedAt = res3.data.last().modifiedAt
        }
    }
})

// ----
// pseudo server


/**
 *
 */
class ApiProposal2(private val db: ApiServerDb) {
    data class Cursor(
            private val nextUpdatedSince: PseudoDateTime,
            private val nextAnimeId: AnimeId) : Comparable<Cursor> {
        override fun compareTo(other: Cursor): Int {
            return (nextUpdatedSince.toInt() * 10000 + nextAnimeId) -
                    (other.nextUpdatedSince.toInt() * 10000 + other.nextAnimeId)
        }
    }

    data class MyListResponse(
            val data: List<Item>,
            val after: Cursor?) {
        data class Item(
                val animeId: AnimeId,
                val isDeleted: Boolean,
                override val modifiedAt: PseudoDateTime) : HasModifiedAt
    }

    /**
     * pseudo /users/{user_id}/animelist/bulk
     */
    fun get_users_me_animelist_bulk(
            // updatedSince: PseudoDateTime = 0, // TODO to get diff
            after: Cursor = Cursor(0, 0),
            limit: Int): MyListResponse {

        val items = db.myListItems
                .sortedBy { Cursor(it.modifiedAt, it.animeId) }
                .filter { Cursor(it.modifiedAt, it.animeId) > after }
                .take(limit + 1)
                .map {
                    MyListResponse.Item(
                            animeId = it.animeId,
                            modifiedAt = it.modifiedAt,
                            isDeleted = false)
                }

        val deletedItems = db.deletedMyListItems
                .sortedBy { Cursor(it.modifiedAt, it.animeId) }
                .filter { Cursor(it.modifiedAt, it.animeId) > after }
                .take(limit + 1)
                .map {
                    MyListResponse.Item(
                            animeId = it.animeId,
                            modifiedAt = it.modifiedAt,
                            isDeleted = true)
                }

        val hasNext = (items.size + deletedItems.size) > limit

        val allItems = (items + deletedItems)
                .sortedBy { it.modifiedAt }
                .take(limit)

        return MyListResponse(
                data = allItems.take(limit),
                after = if (hasNext) {
                    Cursor(allItems.last().modifiedAt, allItems.last().animeId)
                } else {
                    null
                }
        )
    }
}

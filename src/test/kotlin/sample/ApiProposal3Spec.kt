package sample

import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull


class ApiProposal3Spec : Spek({
    val serverDb = ApiServerDb()
    val api = ApiProposal3(serverDb)
    lateinit var localDb: LocalMyListDb

    fun LocalMyListDb.upsertOrDelete(items: List<ApiProposal3.MyListResponse.Item>) {
        for (item in items) {
            if (item.isDeleted) {
                this.delete(item.animeId)
            } else {
                this.upsert(MyListItem(animeId = item.animeId, updatedAt = item.modifiedAt))
            }
        }
    }

    describe("Proposal 3") {
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

            val res1 = api.get_users_me_animelist_bulk2(limit = 2)
            localDb.upsertOrDelete(res1.data)

            it("should have next") {
                assertNotNull(res1.next)
            }

            serverDb.deleteMyListItem(animeId = 1, now = 4)

            val res2 = api.get_users_me_animelist_bulk2(limit = 2, modifiedSince = res1.next!!.latestUpdatedAt)
            localDb.upsertOrDelete(res2.data)

            it("should not have next") {
                assertNull(res2.next)
            }

            it("is equal to server db") {
                assertEquals(serverDb.myListItems, localDb.myListItems)
            }

            // save for `get the changes since the last time we loaded the list`
            // lastLoadedAt = res2.data.last().modifiedAt
        }
    }
})

// ----
// pseudo server


/**
 *
 */
class ApiProposal3(private val db: ApiServerDb) {
    data class MyListResponse(
            val data: List<Item>,
            val next: NextUrl?
    ) {
        data class Item(
                val animeId: AnimeId,
                val isDeleted: Boolean,
                override val modifiedAt: PseudoDateTime) : HasModifiedAt

        data class NextUrl(val latestUpdatedAt: PseudoDateTime)
    }

    /**
     * pseudo /users/{user_id}/animelist/bulk2
     */
    fun get_users_me_animelist_bulk2(modifiedSince: PseudoDateTime = 0, limit: Int): MyListResponse {
        val (dbItems, hasItemNext) = Util.getItemsOrderByModifiedAtConsistently(
                db.myListItems, modifiedSince, limit)
        val items = dbItems
                .map {
                    MyListResponse.Item(
                            animeId = it.animeId,
                            modifiedAt = it.modifiedAt,
                            isDeleted = false)
                }

        val (dbDeletedItems, hasDeletedItemsNext) = Util.getItemsOrderByModifiedAtConsistently(
                db.deletedMyListItems, modifiedSince, limit)
        val deletedItems = dbDeletedItems
                .map {
                    MyListResponse.Item(
                            animeId = it.animeId,
                            modifiedAt = it.modifiedAt,
                            isDeleted = true)
                }

        val (resultItems, hasResultItemsNext) =
                Util.getItemsOrderByModifiedAtConsistently(items + deletedItems, modifiedSince, limit)

        val hasNext = hasResultItemsNext ||
                hasItemNext ||
                hasDeletedItemsNext
        val next = if (hasNext) {
            MyListResponse.NextUrl(resultItems.last().modifiedAt)
        } else {
            null
        }

        return MyListResponse(
                data = resultItems,
                next = next
        )
    }
}

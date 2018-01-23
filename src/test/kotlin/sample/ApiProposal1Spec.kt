package sample

import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import sample.AnimeIds.A
import sample.AnimeIds.B
import sample.AnimeIds.C
import sample.AnimeIds.D
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApiProposal1Spec : Spek({
    val serverDb = ApiServerDb()
    val api = ApiProposal1(serverDb)
    lateinit var localDb: LocalMyListDb

    describe("Proposal 1") {
        val initialMyList = arrayOf(
                MyListItem(animeId = A, updatedAt = 1),
                MyListItem(animeId = B, updatedAt = 2),
                MyListItem(animeId = C, updatedAt = 2), // same time
                MyListItem(animeId = D, updatedAt = 3)
        )

        on("simple case") {
            serverDb.reset(*initialMyList)
            localDb = LocalMyListDb()

            val res1 = api.get_users_me_animelist(offset = 0, limit = 2)
            it("should have next") {
                assertNotNull(res1.next)
            }

            localDb.upsert(res1.data)

            val res2 = api.get_users_me_animelist(offset = 2, limit = 2)
            it("should not have next") {
                assertNull(res2.next)
            }

            localDb.upsert(res2.data)

            it("is equal to server data") {
                assertEquals(serverDb.myListItems, localDb.myListItems)
            }
        }

        on("[edge case] delete item during pagination") {
            serverDb.reset(*initialMyList)
            localDb = LocalMyListDb()

            // save for `get the changes since the last time we loaded the list`
            val localDbLoadedAt1 = 3L // local time (consider an error with the server)

            val res1 = api.get_users_me_animelist(offset = 0, limit = 2)
            it("should have next") {
                assertNotNull(res1.next)
            }
            localDb.upsert(res1.data)

            serverDb.deleteMyListItem(animeId = B, now = 4)

            val res2 = api.get_users_me_animelist(offset = 2, limit = 2)
            it("should not have next") {
                assertNull(res2.next)
            }

            localDb.upsert(res2.data)

            val serverMyList1 = serverDb.myListItems
            val localMyList1 = localDb.myListItems
            it("is not equal to server data now") {
                assertNotEquals(serverMyList1, localMyList1)
            }

            // next update process

            // save for `get the changes since the last time we loaded the list`
            // val localDbLoadedAt2 = 4L // local time (consider an error with the server)

            // (Pagination is actually required)
            val res3 = api.get_users_me_animelist(
                    updatedSince = localDbLoadedAt1, limit = 100)
            it("should not have next") {
                assertNull(res3.next)
            }
            localDb.upsert(res3.data)

            // (Pagination is actually required)
            val res4 = api.get_users_me_animelist_deleted(
                    deletedSince = localDbLoadedAt1, limit = 100)
            localDb.delete(res4.data)

            val serverMyList2 = serverDb.myListItems
            val localMyListItems2 = localDb.myListItems
            it("is equal to server data now") {
                assertEquals(serverMyList2, localMyListItems2)
            }
        }
    }

    describe("Proposal 1-B") {
        val initialMyList = arrayOf(
                MyListItem(animeId = A, updatedAt = 1),
                MyListItem(animeId = B, updatedAt = 2),
                MyListItem(animeId = C, updatedAt = 3), 
                MyListItem(animeId = D, updatedAt = 4)
        )

        on("[edge case] delete item during pagination") {
            serverDb.reset(*initialMyList)
            localDb = LocalMyListDb()

            // save for `get the changes since the last time we loaded the list`
            val localDbLoadedAt1 = 3L // local time (consider an error with the server)

            val res1 = api.get_users_me_animelist(updatedSince = 0, limit = 2)
            it("should have next") {
                assertNotNull(res1.next)
            }
            localDb.upsert(res1.data)

            serverDb.deleteMyListItem(animeId = B, now = 5)
            
            val res2 = api.get_users_me_animelist(updatedSince = res1.next!!.latestUpdatedAt, limit = 2)
            it("should not have next") {
                assertNull(res2.next)
            }

            localDb.upsert(res2.data)

            val serverMyList1 = serverDb.myListItems
            val localMyList1 = localDb.myListItems
            it("is not equal to server data now") {
                assertNotEquals(serverMyList1, localMyList1)
            }

            // next update process

            // save for `get the changes since the last time we loaded the list`
            // val localDbLoadedAt2 = 4L // local time (consider an error with the server)

            // (Pagination is actually required)
            val res3 = api.get_users_me_animelist(
                    updatedSince = localDbLoadedAt1, limit = 100)
            it("should not have next") {
                assertNull(res3.next)
            }
            localDb.upsert(res3.data)

            // (Pagination is actually required)
            val res4 = api.get_users_me_animelist_deleted(
                    deletedSince = localDbLoadedAt1, limit = 100)
            localDb.delete(res4.data)

            val serverMyList2 = serverDb.myListItems
            val localMyListItems2 = localDb.myListItems
            it("is equal to server data now") {
                assertEquals(serverMyList2, localMyListItems2)
            }
        }
    }
})


// ----
// pseudo server

/**
 *
 */
class ApiProposal1(private val db: ApiServerDb) {
    data class MyListWithOffsetResponse(
            val data: List<MyListItem>,
            val next: NextUrl?
    ) {
        data class NextUrl(val offset: Int)
    }

    data class MyListWithUpdatedSinceResponse(
            val data: List<MyListItem>,
            val next: NextUrl?
    ) {
        data class NextUrl(val latestUpdatedAt: PseudoDateTime)
    }

    data class DeletedUserListResponse(
            val data: List<AnimeId>,
            val next: NextUrl?
    ) {
        data class NextUrl(val latestDeletedAt: PseudoDateTime)
    }

    /**
     * pseudo /users/{user_id}/animelist?offset=
     */
    fun get_users_me_animelist(offset: Int, limit: Int): MyListWithOffsetResponse {
        val resultItems = db.myListItems.drop(offset).take(limit + 1)

        val hasNext = resultItems.size > limit
        val next = if (hasNext) {
            MyListWithOffsetResponse.NextUrl(offset + limit)
        } else {
            null
        }

        return MyListWithOffsetResponse(
                data = resultItems.take(limit),
                next = next
        )
    }

    /**
     * pseudo /users/{user_id}/animelist?updated_since=
     */
    fun get_users_me_animelist(
            updatedSince: PseudoDateTime, limit: Int): MyListWithUpdatedSinceResponse {
        val (items, hasNext) = Util.getItemsOrderByModifiedAtConsistently(
                db.myListItems, updatedSince, limit)

        val next = if (hasNext) {
            MyListWithUpdatedSinceResponse.NextUrl(items.last().updatedAt)
        } else {
            null
        }

        return MyListWithUpdatedSinceResponse(data = items, next = next)
    }

    /**
     * pseudo /users/{user_id}/animelist/deleted
     */
    fun get_users_me_animelist_deleted(deletedSince: PseudoDateTime, limit: Int): DeletedUserListResponse {
        val items =
                db.getDeletedMyListItem(deletedSince)
                        .take(limit + 1)

        val hasNext = items.size > limit

        return DeletedUserListResponse(
                data = items.take(limit).map { it.animeId },
                next = if (hasNext) {
                    DeletedUserListResponse.NextUrl(items.last().deletedAt)
                } else {
                    null
                }
        )
    }
}

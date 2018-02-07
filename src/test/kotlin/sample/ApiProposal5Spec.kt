package sample

import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import sample.AnimeIds.A
import sample.AnimeIds.B
import sample.AnimeIds.C
import sample.AnimeIds.D
import sample.AnimeIds.E
import sample.ApiServerDb.DeletedMyListItem
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Use case
 */
class ApiProposal5Spec : Spek({
    describe("Proposal 5") {
        val initialMyList = arrayOf(
                MyListItem(animeId = C, score = 10, updatedAt = 1),
                MyListItem(animeId = B, score = 9, updatedAt = 1),
                MyListItem(animeId = D, score = 9, updatedAt = 1),
                MyListItem(animeId = E, score = 9, updatedAt = 1),
                MyListItem(animeId = A, score = 8, updatedAt = 1)
        )

        on("[edge case] delete item during pagination") {
            val serverDb = ApiServerDb(initialMyList)
            val api = ApiProposal5(serverDb)
            val localDb = LocalMyListDb()
            serverDb.now = 3

            val res1 = api.get_users_me_animelist_order_by_score(limit = 2)
            it("should have next") {
                assertNotNull(res1.after)
            }
            localDb.upsert(res1.data)

            // edge case
            serverDb.deleteMyListItem(animeId = B, now = 4)

            val res2 = api.get_users_me_animelist_order_by_score(after = res1.after!!, limit = 2)
            it("should not have next") {
                assertNotNull(res2.after)
            }

            localDb.upsert(res2.data)


            val res3 = api.get_users_me_animelist_order_by_score(after = res2.after!!, limit = 2)
            it("should not have next") {
                assertNull(res3.after)
            }

            localDb.upsert(res3.data)

            val lastLoadedAt1 = res1.serverTime - 1 // Consider a error

            val serverMyList1 = serverDb.myListItems
            val localMyList1 = localDb.myListItems
            it("is not equal to server data now") {
                assertEquals(listOf(
                        MyListItem(animeId = A, score = 8, updatedAt = 1),
                        MyListItem(animeId = C, score = 10, updatedAt = 1),
                        MyListItem(animeId = D, score = 9, updatedAt = 1),
                        MyListItem(animeId = E, score = 9, updatedAt = 1)
                ), serverMyList1)

                assertEquals(listOf(
                        MyListItem(animeId = A, score = 8, updatedAt = 1),
                        MyListItem(animeId = B, score = 9, updatedAt = 1),
                        MyListItem(animeId = C, score = 10, updatedAt = 1),
                        MyListItem(animeId = D, score = 9, updatedAt = 1),
                        MyListItem(animeId = E, score = 9, updatedAt = 1)
                ), localMyList1)
            }

            // ----------
            // get diff

            val res4 = api.get_users_me_animelist_diff(
                    modifiedSince = lastLoadedAt1)

            localDb.upsert(res4.updatedMyListItems)
            localDb.delete(res4.deletedMyListItems.map { it.animeId })

            // for getting diff next time
            val lastLoadedAt2 = res4.maxModifiedAt

            val serverMyList2 = serverDb.myListItems
            val localMyListItems2 = localDb.myListItems
            it("is equal to server data now") {
                assertEquals(serverMyList2, localMyListItems2)
            }
        }


        on("[edge case2] update score during pagination") {
            val serverDb = ApiServerDb(initialMyList)
            val api = ApiProposal5(serverDb)
            val localDb = LocalMyListDb()
            serverDb.now = 3

            val res1 = api.get_users_me_animelist_order_by_score(limit = 2)
            it("should have next") {
                assertNotNull(res1.after)
            }
            localDb.upsert(res1.data)

            // edge case
            serverDb.update(MyListItem(animeId = B, score = 8, updatedAt = 4))

            val res2 = api.get_users_me_animelist_order_by_score(after = res1.after!!, limit = 2)
            it("should not have next") {
                assertNotNull(res2.after)
            }

            localDb.upsert(res2.data)


            val res3 = api.get_users_me_animelist_order_by_score(after = res2.after!!, limit = 2)
            it("should not have next") {
                assertNull(res3.after)
            }

            localDb.upsert(res3.data)

            val lastLoadedAt1 = res1.serverTime - 1 // Consider a error

            val serverMyList1 = serverDb.myListItems
            val localMyList1 = localDb.myListItems
            it("is not equal to server data now") {
                assertEquals(listOf(
                        MyListItem(animeId = A, score = 8, updatedAt = 1),
                        MyListItem(animeId = B, score = 8, updatedAt = 4),
                        MyListItem(animeId = C, score = 10, updatedAt = 1),
                        MyListItem(animeId = D, score = 9, updatedAt = 1),
                        MyListItem(animeId = E, score = 9, updatedAt = 1)
                ), serverMyList1)

                assertEquals(listOf(
                        MyListItem(animeId = A, score = 8, updatedAt = 1),
                        MyListItem(animeId = B, score = 9, updatedAt = 1),
                        MyListItem(animeId = C, score = 10, updatedAt = 1),
                        MyListItem(animeId = D, score = 9, updatedAt = 1),
                        MyListItem(animeId = E, score = 9, updatedAt = 1)
                ), localMyList1)
            }

            // ----------
            // get diff

            val res4 = api.get_users_me_animelist_diff(
                    modifiedSince = lastLoadedAt1)

            localDb.upsert(res4.updatedMyListItems)
            localDb.delete(res4.deletedMyListItems.map { it.animeId })

            // for getting diff next time
            val lastLoadedAt2 = res4.maxModifiedAt

            val serverMyList2 = serverDb.myListItems
            val localMyListItems2 = localDb.myListItems
            it("is equal to server data") {
                assertEquals(serverMyList2, localMyListItems2)
            }
        }
    }
})


/**
 * Pseudo server implementation
 */
class ApiProposal5(private val db: ApiServerDb) {
    companion object {
        const val MAX_DIFF_SIZE = 200
    }

    data class Cursor(val lastAnimeId: AnimeId, val lastScore: Score)
    data class MyListWithAfterResponse(
            val data: List<MyListItem>,
            val after: Cursor?,
            val serverTime: PseudoDateTime
    ) {
    }

    data class MyListDiffResponse(
            val maxModifiedAt: PseudoDateTime,
            val deletedMyListItems: List<DeletedMyListItem>,
            val updatedMyListItems: List<MyListItem>
    ) {
    }

    /**
     * GET /users/{user_id}/animelist?after
     *
     * for getting entire list.
     *
     * order by score and anime_id
     */
    fun get_users_me_animelist_order_by_score(
            after: Cursor? = null, limit: Int): MyListWithAfterResponse {

        val filteredItems =
                if (after == null) {
                    db.myListItems
                } else if (db.myListItems.find {
                            it.animeId == after.lastAnimeId &&
                                    it.score == after.lastScore
                        } != null) {
                    db.myListItems
                            .filter { it.animeId > after.lastAnimeId }
                } else {
                    db.myListItems
                            .filter { it.score >= after.lastScore } // Duplication may occur
                }

        val items = filteredItems
                .sortedBy { it.score - 100000 + it.animeId }
                .take(limit + 1)

        val resultItems = items.take(limit)
        val hasNext = items.size > limit
        val nextCursor =
                if (hasNext) {
                    Cursor(resultItems.last().animeId, resultItems.last().score)
                } else {
                    null
                }
        return MyListWithAfterResponse(
                data = resultItems,
                after = nextCursor,
                serverTime = db.now)
    }

    /**
     * GET /users/{user_id}/animelist/diff
     *
     * doesn't have `limit` param. max diff size = 200 (TBD)
     *
     * If diff size is over, return error.
     * In this case, get entire list again.
     *
     * res.deletedMyListItems and res.updatedMyListItems don't have the same anime_id
     */
    fun get_users_me_animelist_diff(
            modifiedSince: PseudoDateTime): MyListDiffResponse {

        val updatedItems = db.myListItems
                .filter { it.updatedAt > modifiedSince }
                .take(MAX_DIFF_SIZE + 1)

        if (updatedItems.size > MAX_DIFF_SIZE) {
            throw RuntimeException("diff size too large")
        }

        val deletedItems = db.deletedMyListItems
                .filter { it.deletedAt > modifiedSince }
                .take(MAX_DIFF_SIZE + 1)

        if (deletedItems.size > MAX_DIFF_SIZE) {
            throw RuntimeException("diff size too large")
        }

        val maxModifiedAt = (updatedItems + deletedItems)
                .map { it.modifiedAt }.max()!!

        return MyListDiffResponse(
                updatedMyListItems = updatedItems,
                deletedMyListItems = deletedItems,
                maxModifiedAt = maxModifiedAt
        )
    }
}

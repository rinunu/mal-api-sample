package sample

import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import sample.AnimeIds.A
import sample.AnimeIds.B
import sample.AnimeIds.C
import sample.AnimeIds.D
import sample.ApiServerDb.DeletedMyListItem
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Use case
 */
class ApiProposal4Spec : Spek({
    describe("Proposal 4") {
        val initialMyList = arrayOf(
                MyListItem(animeId = A, updatedAt = 1),
                // there is no B
                MyListItem(animeId = C, updatedAt = 2),
                MyListItem(animeId = D, updatedAt = 3)
        )

        on("[edge case] delete and add item during pagination") {
            val serverDb = ApiServerDb(initialMyList)
            val api = ApiProposal4(serverDb)
            val localDb = LocalMyListDb()
            serverDb.now = 3


            val res1 = api.get_users_me_animelist(limit = 2)
            it("should have next") {
                assertNotNull(res1.after)
            }
            localDb.upsert(res1.data)
            val lastLoadedAt1 = res1.serverTime - 1 // Consider a error

            // edge case
            serverDb.addMyListItem(animeId = B, now = 4)
            serverDb.deleteMyListItem(animeId = C, now = 4)

            val res2 = api.get_users_me_animelist(after = res1.after!!, limit = 2)
            it("should not have next") {
                assertNull(res2.after)
            }

            localDb.upsert(res2.data)

            val serverMyList1 = serverDb.myListItems
            val localMyList1 = localDb.myListItems
            it("is not equal to server data now") {
                assertEquals(listOf(
                        MyListItem(animeId = A, updatedAt = 1),
                        MyListItem(animeId = B, updatedAt = 4),
                        MyListItem(animeId = D, updatedAt = 3)
                ), serverMyList1)

                assertEquals(listOf(
                        MyListItem(animeId = A, updatedAt = 1),
                        MyListItem(animeId = C, updatedAt = 2),
                        MyListItem(animeId = D, updatedAt = 3)
                ), localMyList1)
            }

            // ----------
            // get diff

            val res3 = api.get_users_me_animelist_diff(
                    modifiedSince = lastLoadedAt1)

            localDb.upsert(res3.updatedMyListItems)
            localDb.delete(res3.deletedMyListItems.map { it.animeId })

            // for getting diff next time
            val lastLoadedAt2 = res3.maxModifiedAt

            val serverMyList2 = serverDb.myListItems
            val localMyListItems2 = localDb.myListItems
            it("is equal to server data now") {
                assertEquals(serverMyList2, localMyListItems2)
            }

        }
    }
})


/**
 * Pseudo server implementation
 */
class ApiProposal4(private val db: ApiServerDb) {
    companion object {
        const val MAX_DIFF_SIZE = 200
    }

    data class MyListWithAfterResponse(
            val data: List<MyListItem>,
            val after: AnimeId?,
            val serverTime: PseudoDateTime
    ) {
        data class NextUrl(val after: AnimeId)
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
     * order by anime_id
     */
    fun get_users_me_animelist(
            after: AnimeId = 0, limit: Int): MyListWithAfterResponse {
        val items = db.myListItems
                .filter { it.animeId > after }
                .sortedBy { it.animeId }
                .take(limit + 1)
        val resultItems = items.take(limit)
        val hasNext = items.size > limit
        val lastAnimeId = resultItems.last().animeId
        return MyListWithAfterResponse(
                data = resultItems,
                after = if (hasNext) {
                    lastAnimeId
                } else {
                    null
                },
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

package com.example.basicfiredatabase.adapters

import com.example.basicfiredatabase.models.User
import com.example.basicfiredatabase.models.UserImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class UserAdapterTest {

    private fun makeUser(
        isComplete: Boolean = false,
        expanded: Boolean = false,
        imagesCount: Int = 0
    ): User {
        val images = List(imagesCount) { idx ->
            UserImage(url = "https://example.com/$idx.jpg", public_id = "id-$idx")
        }
        return User(
            id = "u1",
            title = "Title",
            eventType = "Other",
            descriptions = mapOf("en" to "desc"),
            date = "2025-01-01",
            time = "12:00",
            durationMinutes = 30,
            images = images,
            location = "loc",
            includeMapLink = false,
            mapLink = null,
            isComplete = isComplete,
            expanded = expanded
        )
    }

    @Test
    fun `thumbnail shown when upcoming collapsed and has images`() {
        val u = makeUser(isComplete = true, expanded = false, imagesCount = 2)
        assertTrue(UserAdapter.shouldShowThumbnail(u))
    }

    @Test
    fun `thumbnail hidden when event is complete`() {
        val u = makeUser(isComplete = true, expanded = false, imagesCount = 2)
        assertFalse(UserAdapter.shouldShowThumbnail(u))
    }

    @Test
    fun `thumbnail hidden when card is expanded`() {
        val u = makeUser(isComplete = false, expanded = true, imagesCount = 2)
        assertFalse(UserAdapter.shouldShowThumbnail(u))
    }

    @Test
    fun `thumbnail hidden when there are no images`() {
        val u = makeUser(isComplete = false, expanded = false, imagesCount = 0)
        assertFalse(UserAdapter.shouldShowThumbnail(u))
    }
}

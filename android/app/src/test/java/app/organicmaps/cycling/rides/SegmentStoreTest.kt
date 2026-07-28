package app.organicmaps.cycling.rides

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Importing is the half of sharing that touches a file someone else wrote, so it is the half that
 * has to survive a wrong pick from the file chooser and a name collision with a segment of your own.
 */
class SegmentStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: SegmentStore

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(folder.root)
        store = SegmentStore(context)
    }

    private fun segment(id: String, name: String = "Höllviken runt") = Segment(
        id = id,
        name = name,
        points = listOf(SegmentPoint(55.41, 13.06), SegmentPoint(55.42, 13.07)),
    )

    @Test
    fun `survives a round trip through the shared format`() {
        val original = segment("abc")
        val parsed = store.fromJson(store.toJson(original), "fallback")

        assertEquals(original.id, parsed?.id)
        assertEquals(original.name, parsed?.name)
        assertEquals(original.points, parsed?.points)
    }

    @Test
    fun `rejects a course that describes nothing`() {
        val onePoint = """{"id":"a","name":"n","points":[{"lat":55.4,"lon":13.0}]}"""
        assertNull(store.fromJson(onePoint, "fallback"))
        assertNull(store.fromJson("""{"id":"a","name":"n","points":[]}""", "fallback"))
        assertNull(store.fromJson("""{"id":"a","name":"n"}""", "fallback"))
    }

    @Test
    fun `rejects a file that is not ours`() {
        // What the chooser hands back when the rider picks the wrong thing.
        assertNull(store.fromJson("not json at all", "fallback"))
        assertNull(store.fromJson("", "fallback"))
        assertNull(store.fromJson("<?xml version=\"1.0\"?><gpx/>", "fallback"))
    }

    @Test
    fun `names an unnamed segment rather than showing a blank row`() {
        val json = """{"points":[{"lat":55.41,"lon":13.06},{"lat":55.42,"lon":13.07}]}"""
        assertEquals("Imported segment", store.fromJson(json, "Imported segment")?.name)
    }

    @Test
    fun `keeps the sender's id when nothing of yours uses it`() {
        val stored = store.importSegment(segment("from-a-friend"))

        assertEquals("from-a-friend", stored.id)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a colliding import never overwrites your own segment`() {
        store.save(segment("shared-id", name = "Mine"))

        val stored = store.importSegment(segment("shared-id", name = "Theirs"))

        assertNotEquals("shared-id", stored.id)
        // Both survive: yours keeps its recorded attempts, theirs is a new row you can delete.
        assertEquals(listOf("Mine", "Theirs"), store.list().map { it.name }.sorted())
        assertEquals("Mine", store.list().first { it.id == "shared-id" }.name)
    }
}

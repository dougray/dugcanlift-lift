package com.dugcanlift.macrocalc.watchlink

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchLinkStoreTest {
    // The store is a process singleton; Robolectric gives each test a fresh application, so each
    // test starts from empty preferences but the singleton would still hold the last test's
    // context. Clearing through the store itself keeps the tests independent of that.
    private val store: WatchLinkStore
        get() = WatchLinkStore.get(ApplicationProvider.getApplicationContext()).also { it.forget() }

    @Test
    fun `a fresh phone is paired with nothing`() {
        val store = store
        assertFalse(store.isPaired)
        assertNull(store.pairedName)
        assertFalse(store.isPaired("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `a paired watch is remembered, whatever case its address comes back in`() {
        val store = store
        store.remember("aa:bb:cc:dd:ee:ff", "Galaxy Watch6")
        assertTrue(store.isPaired("AA:BB:CC:DD:EE:FF"))
        assertEquals("Galaxy Watch6", store.pairedName)
    }

    @Test
    fun `session and plan revisions are remembered separately`() {
        val store = store
        store.recordSession("s1", AppliedRevision(3, 111))
        store.recordPlan("s1", AppliedRevision(7, 222))
        assertEquals(AppliedRevision(3, 111), store.appliedSession("s1"))
        assertEquals(AppliedRevision(7, 222), store.pushedPlan("s1"))
        assertNull(store.appliedSession("unknown"))
    }

    @Test
    fun `a newer record replaces the older one for the same id`() {
        val store = store
        store.recordSession("s1", AppliedRevision(1, 1))
        store.recordSession("s1", AppliedRevision(2, 2))
        assertEquals(AppliedRevision(2, 2), store.appliedSession("s1"))
    }

    @Test
    fun `forgetting the watch forgets its revisions too, so a re-paired watch starts clean`() {
        val store = store
        store.remember("AA:BB:CC:DD:EE:FF", "Galaxy Watch6")
        store.recordSession("s1", AppliedRevision(1, 1))
        store.forget()
        assertFalse(store.isPaired)
        assertNull(store.appliedSession("s1"))
    }

    @Test
    fun `the records are bounded rather than growing for the life of the install`() {
        val store = store
        repeat(450) { store.recordSession("s$it", AppliedRevision(1, it)) }
        assertNull("the oldest went", store.appliedSession("s0"))
        assertEquals(AppliedRevision(1, 449), store.appliedSession("s449"))
    }
}

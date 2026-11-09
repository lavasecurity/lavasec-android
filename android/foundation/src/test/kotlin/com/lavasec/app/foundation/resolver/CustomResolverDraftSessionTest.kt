package com.lavasec.app.foundation.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomResolverDraftSessionTest {
    @Test
    fun `an empty session is not dirty and reports the empty primary`() {
        val session = CustomResolverDraftSession.empty
        assertFalse(session.isDirty)
        assertFalse(session.hasUnsavedDraft)
        assertFalse(session.canSave)
        assertFalse(session.canClear)
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.EMPTY_PRIMARY),
            session.validation,
        )
    }

    @Test
    fun `editing marks the draft dirty and ready to save`() {
        val session = CustomResolverDraftSession.empty.editPrimary("https://dns.google/dns-query")
        assertTrue(session.isDirty)
        assertTrue(session.canSave)
        assertTrue(session.canClear)
        assertEquals(CustomResolverValidation.Valid, session.validation)
    }

    @Test
    fun `a name only change has changes but cannot be saved without a primary`() {
        val session = CustomResolverDraftSession.empty.editName("Mine")
        assertTrue(session.isDirty)
        assertFalse(session.canSave)
    }

    @Test
    fun `dismiss reverts unsaved changes to the saved entry`() {
        val saved = CustomResolverDraft("Mine", "8.8.8.8", "")
        val session = CustomResolverDraftSession.from(saved).editPrimary("https://dns.google/dns-query")
        assertTrue(session.isDirty)
        val dismissed = session.dismiss()
        assertFalse(dismissed.isDirty)
        assertEquals(saved, dismissed.draft)
    }

    @Test
    fun `clear editor empties the fields without committing`() {
        val saved = CustomResolverDraft("Mine", "8.8.8.8", "")
        val cleared = CustomResolverDraftSession.from(saved).clearEditor()
        assertTrue(cleared.isCleared)
        assertFalse(cleared.canClear)
        assertEquals(saved, cleared.saved)
    }

    @Test
    fun `saving a valid draft commits the normalized entry`() {
        val session = CustomResolverDraftSession.empty
            .editName("  Mine  ")
            .editPrimary("  8.8.8.8  ")
            .editSecondary("  1.1.1.1  ")
        val result = session.save()
        assertTrue(result is CustomResolverSaveResult.Saved)
        val saved = (result as CustomResolverSaveResult.Saved).session
        assertEquals(CustomResolverDraft("Mine", "8.8.8.8", "1.1.1.1"), saved.saved)
        assertEquals(saved.saved, saved.draft)
        assertFalse(saved.isDirty)
    }

    @Test
    fun `saving an invalid draft is rejected and leaves the saved entry untouched`() {
        val saved = CustomResolverDraft("Mine", "8.8.8.8", "")
        val result = CustomResolverDraftSession.from(saved)
            .editPrimary("https://dns.google")
            .save()
        assertEquals(
            CustomResolverSaveResult.Rejected(CustomResolverValidationFailure.MISSING_DOH_PATH),
            result,
        )
    }

    @Test
    fun `saving the cleared shape commits an empty saved entry`() {
        val saved = CustomResolverDraft("Mine", "8.8.8.8", "1.1.1.1")
        val result = CustomResolverDraftSession.from(saved).clearEditor().save()
        assertTrue(result is CustomResolverSaveResult.Cleared)
        val cleared = (result as CustomResolverSaveResult.Cleared).session
        assertEquals(CustomResolverDraft(), cleared.saved)
        assertFalse(cleared.isDirty)
    }

    @Test
    fun `saving with no changes is a no-op`() {
        val saved = CustomResolverDraft("Mine", "8.8.8.8", "")
        assertEquals(CustomResolverSaveResult.NoChanges, CustomResolverDraftSession.from(saved).save())
    }
}

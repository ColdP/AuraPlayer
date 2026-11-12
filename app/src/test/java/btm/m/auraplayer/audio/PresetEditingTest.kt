package btm.m.auraplayer.audio

import org.junit.Assert.*
import org.junit.Test

class PresetEditingTest {
    @Test fun editingBuiltinCreatesDraftWithoutMutatingBuiltin() {
        val initial = EffectsState().requestPreset("warmth", 1)
        val edited = initial.edited(initial.current.config.copy(width = 1.2))
        assertTrue(edited.draftDirty)
        assertEquals("custom", edited.selected)
        assertEquals("warmth", edited.draftBase)
        assertEquals(initial.current.config, edited.draftOriginal)
        assertEquals(1.2, edited.config.width, 0.0)
        assertEquals(initial.current.config, BuiltInPresets.all.first { it.id == "warmth" }.config)
        assertTrue(edited.imported.isEmpty())
    }
    @Test fun changingBackToOriginalParametersClearsDirtyFlag() {
        val s = EffectsState().requestPreset("warmth", 1)
        val edited = s.edited(s.current.config.copy(width = 1.2))
        assertFalse(edited.edited(s.current.config).draftDirty)
    }
    @Test fun switchingWhileDirtyDoesNotChangeSoundBeforeConfirmation() {
        val s = EffectsState().requestPreset("warmth", 1)
        val draft = s.edited(s.current.config.copy(width = 1.2))
        val waiting = draft.requestPreset("clarity", 2)
        assertEquals("clarity", waiting.pendingSelection)
        assertEquals(draft.config, waiting.config)
        assertTrue(waiting.draftDirty)
        assertTrue(waiting.requestPreset("custom", 3).draftDirty)
        val discarded = waiting.applyPreset(waiting.pendingSelection!!, 3)
        assertEquals("clarity", discarded.selected)
        assertFalse(discarded.draftDirty)
        assertNull(discarded.pendingSelection)
    }
    @Test fun saveThenSwitchPreservesSavedSoundAndRequestedDestination() {
        val s = EffectsState().requestPreset("warmth", 1)
        val draft = s.edited(s.current.config.copy(width = 1.2)).requestPreset("rock", 2)
        val saved = draft.savedDraft("我的声音", 3)
        assertEquals(1, saved.imported.size)
        assertEquals("我的声音", saved.current.name)
        assertEquals(draft.config, saved.current.config)
        assertEquals("rock", saved.pendingSelection)
        assertFalse(saved.draftDirty)
        val switched = saved.applyPreset(saved.pendingSelection!!, 4)
        assertEquals("rock", switched.selected)
        assertEquals(saved.imported, switched.imported)
    }
    @Test fun resetRetainsUserPresetsAndAudioPreferences() {
        val saved = EffectsState(fade = true, fadeMs = 1200, alwaysPlay = true).edited(DspConfig(width = 1.2)).savedDraft("Test", 1)
        val reset = saved.copy(style = SoundStyle.CLASSIC).applyPreset("__defaults__", 2)
        assertEquals("reference", reset.selected)
        assertTrue(reset.config.bypass)
        assertEquals(SoundStyle.ORIGINAL, reset.style)
        assertEquals(saved.imported, reset.imported)
        assertTrue(reset.fade); assertTrue(reset.alwaysPlay); assertEquals(1200, reset.fadeMs)
    }
    @Test fun savedAndUnsavedParametersSurviveRestartButPendingActionsDoNot() {
        val saved = EffectsState(fade = true, fadeMs = 900, alwaysPlay = true).requestPreset("warmth", 1)
            .edited(DspConfig(width = 1.2)).savedDraft("Saved", 2)
        val draft = saved.edited(saved.current.config.copy(width = 1.3)).requestPreset("piano", 3)
        val restored = EffectsStateCodec.decode(EffectsStateCodec.encode(draft), EffectsStateCodec.encodeLibrary(draft.imported))
        assertEquals(draft.copy(pendingSelection = null), restored)
        assertEquals(draft.config, restored.config)
        assertEquals(draft.featured, restored.featured)
        assertTrue(restored.draftDirty)
    }
    @Test fun brokenSavedStateFallsBackToSafeReference() {
        val broken = EffectsStateCodec.decode("not json")
        assertEquals(EffectsState(), broken)
        val unknown = EffectsStateCodec.decode("{\"selected\":\"missing\"}")
        assertEquals("reference", unknown.selected)
        assertTrue(unknown.config.bypass)
    }
}

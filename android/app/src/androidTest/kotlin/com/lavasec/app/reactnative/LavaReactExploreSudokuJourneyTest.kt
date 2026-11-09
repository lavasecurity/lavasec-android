package com.lavasec.app.reactnative

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.data.GuardianProgressStore
import com.lavasec.app.foundation.sudoku.SudokuGame
import com.lavasec.app.sudoku.SharedPreferencesSudokuGameStore
import com.lavasec.app.sudoku.decodeWireSudokuGame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** U15 shipping controls and retained game evidence; no speaker, physical or gesture claim. */
@RunWith(AndroidJUnit4::class)
class LavaReactExploreSudokuJourneyTest {
    @Test
    fun shippingExplorePlayPauseResumeLearningAndBlurBackKeepTheSameProductOwner() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured(); journey.waitProtectionOff()
            val product = journey.application.foregroundProduct
            val graph = checkNotNull(product.state.value.graphGeneration)
            val library = checkNotNull(product.currentFilterLibrary())
            val logging = checkNotNull(product.currentQaLogging())
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            val savedGame = SharedPreferencesSudokuGameStore(journey.context).load()

            journey.click("Explore how Lava works")
            journey.waitLabel("Play demo")
            journey.click("Play demo")
            journey.waitLabel("Pause demo")
            journey.click("Pause demo")
            journey.waitLabel("Resume demo")
            val paused = demoCounter(journey)
            assertTrue("The real demo has multiple learning steps", paused.second > 1)
            assertTrue("Pause must leave a current step before the demo ending", paused.first < paused.second)
            journey.click("Next", scroll = false)
            awaitCounter(journey, paused.first + 1 to paused.second)
            journey.waitLabel("Resume demo")
            journey.click("Previous", scroll = false)
            awaitCounter(journey, paused)
            journey.waitLabel("Resume demo")
            journey.click("Resume demo", scroll = false)
            journey.waitLabel("Pause demo")
            // Real navigator removal blurs/unmounts Explore and must cancel its playback clock.
            journey.back(); journey.waitProtectionOff()
            journey.scrollToTop(); journey.click("Explore how Lava works")
            journey.waitLabel("Play demo")
            assertFalse("A new visit must not retain the blurred playback controls", journey.visible("Pause demo"))
            assertFalse(journey.visible("Resume demo"))

            journey.scrollToTop(); journey.click("DNS", scroll = false)
            journey.waitSelection("DNS", selected = true)
            journey.waitLabel("Open DNS settings")
            journey.click("Open DNS settings")
            journey.waitLabel("DNS order")
            assertEquals("Learning must not change the actual DNS/filter configuration", library, product.currentFilterLibrary())
            journey.back(); journey.scrollToTop()
            journey.waitSelection("DNS", selected = true)
            journey.waitLabel("Open DNS settings")
            journey.back(); journey.waitProtectionOff()

            assertSame(product, journey.application.foregroundProduct)
            assertEquals(graph, product.state.value.graphGeneration)
            assertEquals(library, product.currentFilterLibrary())
            assertEquals(logging, product.currentQaLogging())
            assertEquals(savedGame, SharedPreferencesSudokuGameStore(journey.context).load())
            assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            journey.assertNoFallback()
            println("LAVA_UI_EXPLORE play=pause-resume step=next-previous learning=actual-DNS-route blur=back-reopen owner=retained narration=not-enabled")
        }
    }

    @Test
    fun shippingSudokuKeypadResetAndNewCancelCloseAndReopenRetainTheActualOwnerGame() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured(); journey.waitProtectionOff()
            val product = journey.application.foregroundProduct
            val graph = checkNotNull(product.state.value.graphGeneration)
            val library = checkNotNull(product.currentFilterLibrary())
            val logging = checkNotNull(product.currentQaLogging())
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            val store = SharedPreferencesSudokuGameStore(journey.context)
            val originalGame = store.load()
            val guards = GuardianProgressStore.get(journey.context)
            val originalGuardState = guards.state.value
            var ownedGame: SudokuGame? = null
            var ownedLogging = logging
            var stage = "retention-admission"
            var bodyFailure: Throwable? = null
            try {
                if (!logging.keepLavaGuardProgress) {
                    assertNull("An off-retention fixture must not contain a pre-existing durable game", originalGame)
                    assertTrue("An off-retention fixture must not erase pre-existing local Guard usage",
                        originalGuardState.usageByDay.isEmpty())
                    assertTrue("The real snapshot must withhold retained Sudoku while privacy is off",
                        journey.snapshot().isNull("sudoku"))
                    journey.click("Settings", prefix = true, scroll = false)
                    journey.scrollToTop(); journey.click("Privacy & data")
                    journey.click("Lava Guard progress")
                    ownedLogging = logging.copy(keepLavaGuardProgress = true)
                    journey.await("the actual privacy owner admits retained game progress") {
                        product.currentQaLogging() == ownedLogging &&
                            product.state.value.privacy?.inFlight == false
                    }
                    journey.click("Guard", prefix = true, scroll = false)
                    journey.waitProtectionOff()
                }
                // Isolated game fixture generated by the production native command, never a JS
                // reference board or forged snapshot. Subsequent edit/cancel/reopen evidence is UI.
                stage = "native-generation"
                val generated = journey.completed("sudoku.new").getJSONObject("value")
                val fixture = checkNotNull(decodeWireSudokuGame(generated))
                ownedGame = fixture
                assertEquals(fixture, store.load())
                assertFalse(fixture.isSolved)
                assertTrue(fixture.userValues.all { it == 0 })
                stage = "initial-route-open"
                openSudoku(journey)
                stage = "initial-projection"
                journey.await("the real native generated game is projected into the shipping board") {
                    game(journey) == fixture
                }
                val index = fixture.puzzle.givens.indexOfFirst { it == 0 }
                assertTrue("The generated puzzle must have an editable cell", index >= 0)
                val emptyLabel = cellLabel(index, "empty")
                stage = "cell-selection"
                journey.click(emptyLabel)
                journey.waitSelection(emptyLabel, selected = true)
                val digit = fixture.puzzle.solution[index]
                stage = "keypad-edit"
                pressKeypad(journey, emptyLabel, digit) {
                    journey.application.foregroundProduct === product &&
                        product.state.value.graphGeneration == graph &&
                        product.currentQaLogging() == ownedLogging &&
                        store.load() == fixture && guards.state.value == originalGuardState
                }
                val edited = fixture.placeValue(digit, index)
                ownedGame = edited
                journey.await("one real keypad press publishes the exact retained game") {
                    store.load() == edited && game(journey) == edited
                }
                // The real keypad may require the board's compact portrait ScrollView to
                // move down. Return it upward before checking the earlier cell's actual label.
                stage = "edited-cell-label"
                journey.scrollToTop()
                journey.waitLabel(cellLabel(index, digit.toString()))

                stage = "reset-cancel"
                journey.click("Reset", scroll = false)
                journey.waitLabel("Reset puzzle?")
                journey.click("Cancel", scroll = false)
                assertEquals("Reset cancellation must preserve the actual saved entry", edited, store.load())
                assertEquals(edited, game(journey))
                stage = "new-cancel"
                journey.click("New puzzle", scroll = false)
                journey.waitLabel("Your entries and notes will be removed and a new puzzle will begin.")
                journey.click("Cancel", scroll = false)
                assertEquals("New cancellation must not replace the native puzzle", edited, store.load())
                assertEquals(edited, game(journey))
                journey.click("Close", scroll = false)
                journey.waitProtectionOff()
                stage = "route-reopen"
                openSudoku(journey)
                journey.waitLabel(cellLabel(index, digit.toString()))
                assertEquals("Reopening the actual route must restore the saved game", edited, game(journey))
                assertEquals(edited, store.load())
                journey.click("Close", scroll = false)
                journey.waitProtectionOff()
                stage = "host-reopen"
                journey.reopen(); journey.waitProtectionOff()
                openSudoku(journey)
                journey.waitLabel(cellLabel(index, digit.toString()))
                assertEquals("A new real React host must read the durable game", edited, game(journey))
                assertEquals(edited, SharedPreferencesSudokuGameStore(journey.context).load())
                journey.click("Close", scroll = false)
                journey.waitProtectionOff()
                stage = "final-authority"
                assertSame(product, journey.application.foregroundProduct)
                assertEquals(graph, product.state.value.graphGeneration)
                assertEquals(library, product.currentFilterLibrary())
                assertEquals(logging.copy(keepLavaGuardProgress = true), product.currentQaLogging())
                assertEquals(originalGuardState, guards.state.value)
                assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
                journey.assertNoFallback()
                println("LAVA_UI_SUDOKU entry=real-keypad cancel=reset-new reopen=route-and-real-host retention=owner-confirmed fixture=native-generated evidence=unsolved-game")
            } catch (failure: Throwable) {
                bodyFailure = failure
                throw failure
            } finally {
                val cleanupFailure = runCatching {
                    // Full game and exact logging witnesses remain current across the suspending
                    // privacy restoration. Even another edit of the same puzzle belongs to its writer.
                    val expectedGame = ownedGame ?: originalGame
                    val expectedLogging = ownedLogging
                    val currentGame = store.load()
                    val currentLogging = product.currentQaLogging()
                    val currentGuard = guards.state.value
                    // Emit only finite stage names, equality witnesses and primitive counts.
                    // Never print the board/solution, setting payloads or protected ledger contents.
                    println("LAVA_UI_SUDOKU_CLEANUP stage=$stage bodyFailure=${bodyFailure != null} " +
                        "productCurrent=${journey.application.foregroundProduct === product} graphExpected=$graph graphCurrent=${product.state.value.graphGeneration} " +
                        "loggingMatches=${currentLogging == expectedLogging} loggingPresent=${currentLogging != null} " +
                        "countsMatches=${currentLogging?.keepCounts == expectedLogging.keepCounts} historyMatches=${currentLogging?.keepHistory == expectedLogging.keepHistory} " +
                        "networkMatches=${currentLogging?.keepNetworkActivity == expectedLogging.keepNetworkActivity} retentionMatches=${currentLogging?.keepLavaGuardProgress == expectedLogging.keepLavaGuardProgress} " +
                        "gameMatches=${currentGame == expectedGame} expectedGamePresent=${expectedGame != null} currentGamePresent=${currentGame != null} " +
                        "puzzleMatches=${currentGame?.puzzle == expectedGame?.puzzle} " +
                        "valueDifferences=${if (expectedGame != null && currentGame != null) expectedGame.userValues.zip(currentGame.userValues).count { (a, b) -> a != b } else -1} " +
                        "noteDifferences=${if (expectedGame != null && currentGame != null) expectedGame.notes.zip(currentGame.notes).count { (a, b) -> a != b } else -1} " +
                        "guardMatches=${currentGuard == originalGuardState} guardAvailabilityMatches=${currentGuard.available == originalGuardState.available} " +
                        "guardUsageMatches=${currentGuard.usageByDay == originalGuardState.usageByDay} guardLedgerMatches=${currentGuard.ledger == originalGuardState.ledger} " +
                        "expectedUsageDays=${originalGuardState.usageByDay.size} currentUsageDays=${currentGuard.usageByDay.size}")
                    val ownsFixture = {
                        journey.application.foregroundProduct === product &&
                            product.state.value.graphGeneration == graph &&
                            product.currentQaLogging() == expectedLogging && store.load() == expectedGame &&
                            guards.state.value == originalGuardState
                    }
                    assertTrue("Cleanup refuses any replacement game, logging, graph or Guard state", ownsFixture())
                    var offRetentionCleared = false
                    if (expectedLogging != logging) {
                        assertFalse("This fixture only enables previously disabled progress retention", logging.keepLavaGuardProgress)
                        assertEquals("Cleanup refuses unrelated logging changes",
                            logging.copy(keepLavaGuardProgress = true), expectedLogging)
                        runBlocking {
                            assertTrue("Restore only the exact fixture delta while its full game remains owned",
                                product.setLoggingForRestore(logging, graph, ownsFixture))
                        }
                        assertEquals(logging, product.currentQaLogging())
                        assertNull("Confirmed off-retention restoration must clear the owned game", store.load())
                        offRetentionCleared = true
                    }
                    assertSame(product, journey.application.foregroundProduct)
                    assertEquals("A retired graph must not restore a different owner's game", graph, product.state.value.graphGeneration)
                    if (ownedGame != null) {
                        // Reread after suspension, directly before the supported clear/save. Null is
                        // acceptable only when the completed privacy operation already cleared it.
                        // Sudoku command writes execute on this same main queue. Keep the final
                        // witness and native fixture-only store repair in one nonsuspending turn.
                        journey.instrumentation.runOnMainSync {
                            assertSame(product, journey.application.foregroundProduct)
                            assertEquals(graph, product.state.value.graphGeneration)
                            assertEquals("Final cleanup refuses any unrelated logging change", logging, product.currentQaLogging())
                            assertEquals(originalGuardState, guards.state.value)
                            assertEquals("Final cleanup refuses a same-puzzle replacement or unconfirmed removal",
                                if (offRetentionCleared) null else expectedGame, store.load())
                            if (originalGame == null) {
                                if (!offRetentionCleared) store.clear()
                            } else store.save(originalGame)
                        }
                    }
                    assertEquals(originalGame, store.load())
                    assertEquals(logging, product.currentQaLogging())
                    assertEquals(originalGuardState, guards.state.value)
                }.exceptionOrNull()
                if (cleanupFailure != null) {
                    // Retain the original failed UI/owner boundary; unsafe cleanup still fails
                    // and never reaches a protected write after its ownership assertion.
                    val primary = bodyFailure
                    if (primary == null) throw cleanupFailure
                    primary.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    private fun openSudoku(journey: ReactEmulatorJourney) {
        journey.scrollToTop()
        journey.namedAccessibilityAction("Protection status", "Play Sudoku", prefix = true)
        journey.waitLabel("Notes mode off")
        journey.assertNoFallback()
    }

    private fun game(journey: ReactEmulatorJourney): SudokuGame? =
        decodeWireSudokuGame(journey.snapshot().optJSONObject("sudoku"))

    private fun pressKeypad(journey: ReactEmulatorJourney, selectedCellLabel: String, digit: Int, isCurrent: () -> Boolean) {
        fun labelled(node: AccessibilityNodeInfo, label: String): Boolean =
            listOf(node.text, node.contentDescription).any { it?.toString() == label }
        val selectedCell = journey.accessibilityNodes().single { node ->
            node.isVisibleToUser && node.isSelected && node.isEnabled &&
                node.packageName?.toString() == journey.context.packageName && labelled(node, selectedCellLabel)
        }
        val window = selectedCell.windowId
        journey.waitLabel("Puzzle assistance off")
        // SudokuDigit's value.text is empty with assistance off. RN0.87
        // BaseViewManager joins that empty value to the own digit label with ", ".
        // Match the entire native description; no arbitrary state/value suffix is admitted.
        val nativeDigitLabel = "$digit, "
        fun candidates(): List<AccessibilityNodeInfo> = journey.accessibilityNodes().filter { node ->
            node.isVisibleToUser && node.isEnabled && !node.isEditable && node.isClickable &&
                node.packageName?.toString() == journey.context.packageName && node.windowId == window &&
                node.className?.toString() == "android.widget.Button" && labelled(node, nativeDigitLabel)
        }
        val initiallyVisible = candidates()
        assertTrue("The initial keypad Button must be unambiguous", initiallyVisible.size <= 1)
        // Portrait deliberately disables scrolling when all content fits. Android then
        // exposes no scrollable ancestor; that must not prevent an already-visible key.
        // When the key is below the fold, retain the exact selected-cell container and
        // measured real scroll progress. Never substitute a board numeral's Text child.
        if (initiallyVisible.isEmpty()) {
            journey.scrollRegionContaining(selectedCellLabel).reveal(nativeDigitLabel, expectedButtonRole = true)
        }
        println("LAVA_UI_SUDOKU_KEYPAD_REVEAL window=$window initiallyVisible=${initiallyVisible.size == 1} navigation=${if (initiallyVisible.isEmpty()) "exact-cell-scroll" else "already-visible-button"}")
        var target: AccessibilityNodeInfo? = null
        journey.await("one actual current native keypad Button") {
            val current = candidates()
            assertTrue("The keypad Button must be unambiguous", current.size <= 1)
            val candidate = current.singleOrNull() ?: return@await false
            if (!candidate.refresh() || candidates().singleOrNull() != candidate) return@await false
            target = candidate
            true
        }
        assertTrue("The selected fixture/owner must still own the one keypad action", isCurrent())
        val button = checkNotNull(target)
        assertTrue("The native keypad Button must remain current before activation",
            button.refresh() && candidates().singleOrNull() == button)
        val bounds = Rect().also(button::getBoundsInScreen)
        val accepted = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        println("LAVA_UI_SUDOKU_KEYPAD window=$window identity=${button.hashCode()} class=${button.className} bounds=$bounds accepted=$accepted")
        assertTrue("The one native keypad Button activation must be accepted", accepted)
    }

    // Like the keypad, the shared cell supplies an empty accessibilityValue.text
    // with assistance off. RN0.87 joins it to the label with ", ".
    private fun cellLabel(index: Int, value: String): String =
        "Row ${index / 9 + 1}, column ${index % 9 + 1}: $value, "

    private fun demoCounter(journey: ReactEmulatorJourney): Pair<Int, Int> {
        var counter: Pair<Int, Int>? = null
        journey.await("one actual visible demo step counter") {
            counter = visibleDemoCounter(journey)
            counter != null
        }
        return checkNotNull(counter)
    }

    private fun visibleDemoCounter(journey: ReactEmulatorJourney): Pair<Int, Int>? {
        val counters = journey.accessibilityNodes().filter { it.isVisibleToUser &&
            it.packageName?.toString() == journey.context.packageName }
            .mapNotNull { Regex("^([0-9]+)/([0-9]+)$").matchEntire(it.text?.toString().orEmpty()) }
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.distinct()
        assertTrue("The actual Explore transport counter must be unambiguous", counters.size <= 1)
        return counters.singleOrNull()
    }

    private fun awaitCounter(journey: ReactEmulatorJourney, expected: Pair<Int, Int>) {
        journey.await("the one real demo step action reaches ${expected.first}/${expected.second}") {
            visibleDemoCounter(journey) == expected
        }
    }
}

package com.lavasec.app.reactnative

import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.resolver.ResolverEditRejection
import com.lavasec.app.foundation.resolver.ResolverTierChoice
import com.lavasec.app.foundation.resolver.ResolverTransportTokens
import com.lavasec.app.foundation.resolver.ResolverTransportUnavailableReason
import com.lavasec.app.foundation.sudoku.SudokuGame
import com.lavasec.app.platform.ChainedConfigurationToggleKey
import com.lavasec.app.product.FilterLibraryEditRejection
import com.lavasec.app.sudoku.decodeWireSudokuGame
import org.json.JSONObject

/**
 * Wire-command parsing and strict argument validation for the RN product bridge.
 *
 * Canonical iOS contract anchor: commit `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`,
 * `ReactNative/app/contract.ts` `AppCommand` (filter/library discriminators and `sudoku.*`),
 * `ReactNative/native-app/LavaAppFilters.swift` (the exact filter command shapes), and
 * `ReactNative/native-app/LavaAppBridge.swift` (`sudoku.save`/`sudoku.new`).
 *
 * This file models only the commands whose Android owners already exist. It performs no
 * business rule itself: the bridge hands each parsed command to `MainProductViewModel` (filters)
 * or the Sudoku owner. A canonical command with no Android owner is a typed `Unsupported`
 * rejection, never a fabricated success, and an unknown discriminator is `Unknown`. Malformed
 * arguments are `Invalid` with a stable reason code.
 */
internal sealed interface ProductCommand

internal sealed interface ProductFilterCommand : ProductCommand {
    data class Open(val id: String) : ProductFilterCommand
    data class Close(val id: String) : ProductFilterCommand
    data class Edit(val id: String) : ProductFilterCommand
    data class Cancel(val id: String) : ProductFilterCommand
    data class Save(val id: String) : ProductFilterCommand
    data class Review(val id: String) : ProductFilterCommand
    data class Apply(
        val id: String,
        val review: String,
        /** The standalone domain-history review token, when this apply confirms one. */
        val standaloneReview: String? = null,
    ) : ProductFilterCommand
    data class Switch(val id: String) : ProductFilterCommand
    data class Delete(val id: String) : ProductFilterCommand
    data class Create(val name: String, val duplicateFromId: String?) : ProductFilterCommand
    data class Rename(val id: String, val name: String, val emoji: String? = null) : ProductFilterCommand
    data class Domain(
        val id: String,
        val domain: String,
        val decision: DomainDecision,
        val remove: Boolean,
    ) : ProductFilterCommand

    data class RemoveList(val id: String, val sourceID: String) : ProductFilterCommand
    data class UndoDomain(val id: String, val domain: String) : ProductFilterCommand
    data class UndoList(val id: String, val sourceID: String) : ProductFilterCommand
    data class Lists(val id: String, val ids: List<String>) : ProductFilterCommand

    /**
     * Canonical `filter.customList`: open the "bring your own list" editor for [id] with the
     * picker's current [ids] selection. Returns an opaque editor token; it does not mutate the
     * draft (the editor's `filter.addCustomList` does).
     */
    data class CustomList(val id: String, val ids: List<String>) : ProductFilterCommand

    /**
     * The Android adaptation of the native custom-blocklist editor: add one validated custom
     * source to the draft open for [token]. The URL/name are validated by the owner's editor, and
     * the source is enabled atomically.
     */
    data class AddCustomList(
        val token: String,
        val name: String,
        val url: String,
        val format: BlocklistFormat,
    ) : ProductFilterCommand

    /** Canonical `filter.deleteCustomList`: remove one custom source from the draft. */
    data class DeleteCustomList(val id: String, val sourceID: String) : ProductFilterCommand

    data object RestoreDefaults : ProductFilterCommand
    data class Refresh(val id: String) : ProductFilterCommand
}

/**
 * Canonical `share.*` commands. Every one carries only a filter id; the bridge computes the
 * shareable content through [com.lavasec.app.product.FilterShareOwner] and owns the platform
 * effect (clipboard, Intent, or an explicit card refusal) — no payload, name, or URL crosses the
 * wire.
 */
internal sealed interface ProductShareCommand : ProductCommand {
    data class Query(val id: String) : ProductShareCommand
    data class Copy(val id: String) : ProductShareCommand
    data class Card(val id: String, val token: String) : ProductShareCommand
    data class Present(val id: String) : ProductShareCommand
}

/**
 * Canonical Sudoku commands. `sudoku.save` carries the already-validated game (the parser rejects a
 * malformed/foreign payload), `sudoku.new` asks the bridge to generate and persist a fresh round.
 */
internal sealed interface ProductSudokuCommand : ProductCommand {
    data class Save(val game: SudokuGame) : ProductSudokuCommand
    /** `challenge` digs to the fixed 28-clue challenge target instead of the sampled band. */
    data class New(val challenge: Boolean) : ProductSudokuCommand
}

/**
 * Canonical DNS commands (iOS `dns.tiers`, `dns.customDraft`, `dns.custom`,
 * `customEntry.dismiss`). The tier ladder and custom values are proposals: the parser only
 * decodes them into [ResolverTierChoice]s, and `MainProductViewModel` delegates the actual
 * accept/reject to `ResolverSelectionPolicy`, which owns every provider/transport/custom
 * and chained restriction.
 */
internal sealed interface ProductDnsCommand : ProductCommand {
    data class Tiers(val context: String, val tiers: List<ResolverTierChoice>) : ProductDnsCommand

    /**
     * Canonical `dns.toggle`: enable/disable one projected resolution tier. Android's authority is
     * the 2-slot primary+fallback model, so only index 1 (the fallback slot) is toggleable; the
     * bridge refuses any other index with a stable code rather than reinterpreting the ladder.
     */
    data class Toggle(val context: String, val index: Int, val value: Boolean) : ProductDnsCommand

    data class CustomDraft(val choice: ResolverTierChoice?) : ProductDnsCommand
    data class CustomStage(val id: String, val context: String, val name: String,
        val primary: String, val secondary: String) : ProductDnsCommand
    data class Custom(
        val context: String,
        val name: String,
        val primary: String,
        val secondary: String,
    ) : ProductDnsCommand

    data class DismissCustomEntry(val id: String) : ProductDnsCommand
}

/**
 * Canonical `vpn.*` chained-configuration editor commands.
 *
 * Android's authority is a SINGLE ROTATION ([com.lavasec.app.platform.ChainedConfigurationCommandOwner]
 * over the credential repository), so the canonical multi-profile editor is projected onto one row:
 * a second-row `edit`/`remove`, `swap`, and `rowToggle` are refused with stable codes (see
 * `ChainedConfigurationEditorRefusal`) rather than silently reinterpreted — never a second profile
 * or a second persistence authority (decisions.md 2026-09-29, SINGLE-ROTATION deviation).
 *
 * The wire carries only opaque draft ids, row indexes, the committed generation and the
 * `setup`/`enabled`/`fallback` toggle keys. Configuration text — and therefore the private and
 * pre-shared keys — is entered in the native editor and staged through the owner's opaque key
 * lease; a caller-supplied `privateKey`/`conf`/`configuration` argument is refused, never parsed.
 */
internal sealed interface ProductVpnCommand : ProductCommand {
    /** `vpn.begin`: open a draft over the committed rotation at the wire [generation]. */
    data class Begin(val id: String, val generation: String) : ProductVpnCommand

    data class Cancel(val id: String) : ProductVpnCommand
    data class Reset(val id: String) : ProductVpnCommand
    data class Swap(val id: String) : ProductVpnCommand
    data class Commit(val id: String) : ProductVpnCommand
    data class Toggle(val key: ChainedConfigurationToggleKey, val value: Boolean) : ProductVpnCommand
    data class Edit(val id: String, val index: Int) : ProductVpnCommand
    data class Remove(val id: String, val index: Int) : ProductVpnCommand
    data class RowToggle(val generation: String, val index: Int, val value: Boolean) : ProductVpnCommand
}

/**
 * The Android wire id for a DNS choice. iOS identities encode the transport in the preset
 * id; the Android catalog separates provider from transport, so the bridge composes a
 * stable `provider|transportToken` id from the snapshot `choices` and decodes it back.
 * A malformed id splits into `(whole, "")`, which the policy rejects as an unknown
 * provider or transport — never a silent default.
 */
internal object ResolverDnsWire {
    private const val SEPARATOR = '|'

    fun encode(providerRawId: String, transportToken: String): String =
        "$providerRawId$SEPARATOR$transportToken"

    fun decode(id: String): Pair<String, String> {
        val index = id.indexOf(SEPARATOR)
        if (index <= 0 || index == id.length - 1) return id to ""
        return id.substring(0, index) to id.substring(index + 1)
    }

    fun decodeTierId(id: String): Pair<String, String> = decode(id)
}

/** Stable wire codes for a policy refusal; JS maps them to localized copy. */
internal fun ResolverEditRejection.wireCode(): String = when (this) {
    ResolverEditRejection.ChainedEditLocked -> "chained-edit-locked"
    ResolverEditRejection.StaleEditor -> "stale-editor"
    ResolverEditRejection.InvalidTierCount -> "invalid-tier-count"
    is ResolverEditRejection.UnknownProvider -> "unknown-provider"
    is ResolverEditRejection.UnknownTransport -> "unknown-transport"
    ResolverEditRejection.CustomRequiresPlus -> "custom-requires-plus"
    is ResolverEditRejection.CustomInvalid -> "custom-invalid"
    ResolverEditRejection.CustomTransportMismatch -> "custom-transport-mismatch"
    is ResolverEditRejection.TransportNotOffered -> "transport-not-offered"
    is ResolverEditRejection.TransportUnavailable -> "transport-unavailable"
    ResolverEditRejection.FallbackNotEligible -> "fallback-not-eligible"
    ResolverEditRejection.DuplicateTier -> "duplicate-tier"
}

/**
 * The wire code for a DNS refusal. A custom tier refused because the Android engine has no
 * custom-resolver route is named for the feature (`custom-resolver-runtime-absent`) instead of
 * the generic `transport-unavailable`, so JS can map it to honest custom-DNS copy. Every other
 * refusal keeps its typed [wireCode], never a fabricated success.
 */
internal fun ResolverEditRejection.dnsResultWireCode(): String =
    if (this is ResolverEditRejection.TransportUnavailable &&
        reason == ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT
    ) {
        "custom-resolver-runtime-absent"
    } else {
        wireCode()
    }

/**
 * Canonical whole-library editing commands (`library.edit`/`cancel`/`toggleDeletion`/`form`).
 *
 * iOS presents `library.form` as a native sheet; Android has no library-form presentation, so the
 * adaptation carries the completed identity values on the wire and delegates every stage to the
 * owner's [com.lavasec.app.foundation.filter.FilterLibraryEditSession] — create and rename are
 * draft-only and only `form delete` commits the validated batch. The parser decodes the shape
 * only; every filter rule stays in the owner.
 */
internal sealed interface ProductLibraryCommand : ProductCommand {
    data object Edit : ProductLibraryCommand

    data object Cancel : ProductLibraryCommand

    data class ToggleDeletion(val id: String) : ProductLibraryCommand

    sealed interface Form : ProductLibraryCommand {
        data class Create(val name: String?, val duplicateFromId: String?) : Form

        data class Rename(val id: String, val name: String, val emoji: String?) : Form

        data class Delete(val ids: List<String>?) : Form
    }
}

/** Stable wire codes for a whole-library edit refusal; JS maps them to localized copy. */
internal fun FilterLibraryEditRejection.wireCode(): String = when (this) {
    FilterLibraryEditRejection.NOT_EDITING -> "library-not-editing"
    FilterLibraryEditRejection.UNKNOWN_FILTER -> "library-unknown-filter"
    FilterLibraryEditRejection.ACTIVE_FILTER -> "library-active-filter"
    FilterLibraryEditRejection.FROZEN_FILTER -> "library-frozen-filter"
    FilterLibraryEditRejection.HAS_CHANGES -> "library-has-changes"
    FilterLibraryEditRejection.NO_CHANGES -> "library-no-changes"
    FilterLibraryEditRejection.LIMIT_REACHED -> "library-limit-reached"
    FilterLibraryEditRejection.INVALID_NAME -> "library-invalid-name"
    FilterLibraryEditRejection.INVALID_EMOJI -> "library-invalid-emoji"
    FilterLibraryEditRejection.STALE_STAGING -> "library-stale-staging"
    FilterLibraryEditRejection.COMMIT_FAILED -> "library-commit-failed"
}

/**
 * Canonical account commands (`account.apple`/`google`/`signOut`/`delete`). Argument-free: the
 * process account owner binds each one to the current account generation, so a caller cannot
 * inject a target account, identity or token over the wire. Android ships no Apple credential
 * adapter, so [Apple] is recognized and answered with an explicit provider-unavailable refusal
 * rather than a fabricated success.
 */
internal sealed interface ProductAccountCommand : ProductCommand {
    data object Google : ProductAccountCommand
    data object Apple : ProductAccountCommand
    data object SignOut : ProductAccountCommand
    data object Delete : ProductAccountCommand
}

/**
 * Canonical encrypted-backup commands. Argument-free: the owner binds each one to the current
 * account and generation, so a caller cannot inject a target account or envelope over the wire.
 */
internal sealed interface ProductBackupCommand : ProductCommand {
    data object Refresh : ProductBackupCommand
    data object BackUpNow : ProductBackupCommand
    data object Disable : ProductBackupCommand
    data object Delete : ProductBackupCommand
}

/**
 * Canonical dynamic `settings.set`. The parser decodes the closed value union
 * `string | boolean | number` into [PreferenceValue]; every key/type/domain decision and the
 * owner delegation live in [com.lavasec.app.settings.SettingsCommandDispatcher].
 */
internal sealed interface ProductSettingsCommand : ProductCommand {
    data class Set(val key: String, val value: PreferenceValue) : ProductSettingsCommand
}

internal enum class DomainDecision { BLOCKED, ALLOWED }

/**
 * Canonical read-only Activity/Diagnostics query commands (`activity.query`, `domains.query`,
 * `network.query`, `stats.query`). Every argument is optional or a closed value; the parser only
 * decodes and bounds them, and the app-layer query owner reads the existing Activity/diagnostics
 * owners. A query never mutates durable state, so it carries no generation fence beyond the
 * bridge's ordinary admission and lock gates.
 *
 * `start`/`end` are the canonical Unix **seconds** the shared `NativeLavaReview` date presets use,
 * not millis. `Domains.decision` is the shared screen's `All`/`Allowed`/`Blocked` choice.
 */
internal data class ProductActivityCardQuery(val start: Long, val end: Long, val hourly: Boolean, val chart: String, val period: String) : ProductCommand
internal data class ProductActivityShare(val token: String) : ProductCommand

internal sealed interface ProductQueryCommand : ProductCommand {
    data class Activity(val start: Long?, val end: Long?, val hourly: Boolean) : ProductQueryCommand

    data class Domains(
        val history: Boolean,
        val decision: DomainQueryDecision,
        val search: String,
        val start: Long?,
        val end: Long?,
        val limit: Int?,
    ) : ProductQueryCommand

    data object Network : ProductQueryCommand

    data object Stats : ProductQueryCommand
}

/** The shared Domain List "Show" choice; `ALL` merges the two native decision reads. */
internal enum class DomainQueryDecision { ALL, ALLOWED, BLOCKED }

/**
 * Canonical domain-list commands. `Copy` is a clipboard effect, `EnableHistory` delegates to the
 * privacy logging owner, and `Stage`/`Cancel` are recognized so the wire answers them with their
 * explicit owner availability instead of an unknown-command rejection.
 */
internal sealed interface ProductDomainCommand : ProductCommand {
    data object EnableHistory : ProductDomainCommand

    data class Copy(val domain: String) : ProductDomainCommand

    data class Stage(val domain: String, val decision: DomainDecision) : ProductDomainCommand

    data class Cancel(val token: String) : ProductDomainCommand
}

internal sealed interface ProductDomainCheckCommand : ProductCommand {
    data class Query(val visit: String, val input: String, val refresh: Boolean) : ProductDomainCheckCommand
    data class Cancel(val visit: String) : ProductDomainCheckCommand
    data class Stage(val visit: String, val proof: String, val action: com.lavasec.app.foundation.filter.DomainCheckRuleAction) : ProductDomainCheckCommand
}

/** Canonical `reports.sample`: refresh the local diagnostics sample used by the Stats surface. */
internal data object ProductReportsSample : ProductCommand

/**
 * Canonical `catalog.query`: the shared "Choose Blocklists" picker reads the effective catalog
 * sections plus a rule-budget estimate. The parser bounds only the selected ids; the catalog owner
 * resolves real sections from the catalog the filter authority compiles against (Android has no
 * per-source rule count, so the budget is reported as indeterminate rather than fabricated).
 */
internal data class ProductCatalogQuery(val ids: List<String>) : ProductCommand

/**
 * Canonical `activity.visibility`: the review-moment dwell token from the shared Activity screen.
 * `token` is an opaque caller identity, `visible` the dwell state, and `start`/`end` the optional
 * focused window in Unix seconds. No state is durable here; the owner only tracks the live moment.
 */
internal data class ProductActivityVisibility(
    val token: String,
    val visible: Boolean,
    val start: Long?,
    val end: Long?,
) : ProductCommand

/**
 * Canonical `logs.*`. `logs.clear` carries only the canonical button-title [kind] and the optional
 * `activityViewing` surface; the bridge maps the kind onto the scoped privacy-owner clear. The
 * canonical `logs.export` explicitly selects diagnostics or all retained logs.
 * Both selections remain app-settings-authorized at the native boundary.
 */
internal sealed interface ProductLogsCommand : ProductCommand {
    data class Clear(val kind: String, val fromActivity: Boolean) : ProductLogsCommand

    data class Export(val domains: Boolean) : ProductLogsCommand
}

/** Stable reason codes crossing the bridge; JS maps them to localized copy. */
internal object ProductCommandReasons {
    const val INVALID_ARGUMENT = "invalid-argument"
    const val UNSUPPORTED_ARGUMENT = "unsupported-argument"
    const val UNSUPPORTED_COMMAND = "unsupported-command"
    const val UNKNOWN_COMMAND = "unknown-command"

    /** A `settings.set` key outside the canonical vocabulary. */
    const val UNKNOWN_SETTING = "unknown-setting"

    /** A `settings.set` value whose shape or domain does not match the canonical key. */
    const val INVALID_SETTING_VALUE = "invalid-setting-value"

    /** A canonical `settings.set` key whose owner is a different (not yet ported) slice. */
    const val UNSUPPORTED_SETTING = "unsupported-setting"

    /** A real settings owner could not durably apply the change. */
    const val SETTING_REFUSED = "setting-refused"
}

internal sealed interface ProductCommandParse {
    data class Supported(val command: ProductCommand) : ProductCommandParse

    /** A recognized canonical command with no Android owner yet. */
    data object Unsupported : ProductCommandParse

    /** A discriminator outside the canonical command vocabulary. */
    data object Unknown : ProductCommandParse

    data class Invalid(val reason: String) : ProductCommandParse
}

/**
 * Whether a parsed command, when it completes, commits a durable configuration mutation the
 * encrypted-backup automatic-upload coordinator must observe.
 *
 * The backup payload is composed from the filter library, the resolver/chained configuration and
 * the retained preferences/logging, so only commands that COMMIT to one of those authorities are
 * triggers. Read/effect commands and draft-only stages (`filter.domain`, `filter.undo*`,
 * `filter.lists`, `filter.review`, `library.form create/rename`, `library.toggleDeletion`) are
 * excluded; `library.form delete` is the one whole-library form that commits. `filter.save` is
 * included because a non-weakening save commits directly even though a weakening save only opens a
 * review — the coordinator's own debounce/admission re-checks the envelope and preference, so a
 * redundant arm over unchanged content is harmless. A retired credential generation is re-fenced by
 * the coordinator (`currentConfigurationGeneration`), never by this classifier.
 */
internal fun ProductCommand.commitsDurableConfiguration(): Boolean = when (this) {
    is ProductFilterCommand -> when (this) {
        is ProductFilterCommand.Apply,
        is ProductFilterCommand.Save,
        is ProductFilterCommand.Switch,
        is ProductFilterCommand.Delete,
        is ProductFilterCommand.Create,
        is ProductFilterCommand.Rename,
        // The custom-source draft mutations arm the backup coordinator; the actual durable commit
        // is the follow-up filter save, and a redundant arm over unchanged content is harmless.
        is ProductFilterCommand.AddCustomList,
        is ProductFilterCommand.DeleteCustomList,
        ProductFilterCommand.RestoreDefaults,
        is ProductFilterCommand.Refresh,
        -> true

        else -> false
    }

    is ProductDnsCommand -> when (this) {
        is ProductDnsCommand.Tiers,
        is ProductDnsCommand.Toggle,
        is ProductDnsCommand.Custom,
        -> true

        else -> false
    }

    is ProductVpnCommand -> this is ProductVpnCommand.Commit || this is ProductVpnCommand.Toggle

    is ProductLibraryCommand.Form -> this is ProductLibraryCommand.Form.Delete

    is ProductSettingsCommand -> true

    is ProductDomainCommand -> this is ProductDomainCommand.EnableHistory
    is ProductDomainCheckCommand -> false

    else -> false
}

/** Read-only view of the JSON command so validation is unit-testable without `org.json`. */
internal interface ProductCommandInput {
    fun has(name: String): Boolean
    fun string(name: String): String?
    fun boolean(name: String): Boolean?

    /** An integral JSON number (the canonical query ranges/limits are integral only); null otherwise. */
    fun long(name: String): Long?

    /** The canonical `sudoku.save` game, validated by the pure engine; `null` when malformed. */
    fun sudokuGame(name: String): SudokuGame?

    /** The canonical `dns.tiers` ladder decoded from the wire; `null` when malformed. */
    fun dnsTiers(name: String): List<ResolverTierChoice>?

    /** One canonical `DNSChoice` (custom draft / tier) decoded from the wire; `null` when malformed. */
    fun dnsChoice(name: String): ResolverTierChoice?

    /** A canonical string-array argument; `null` when not an array or any element is not a string. */
    fun stringList(name: String): List<String>?

    /**
     * A canonical `settings.set` value (`string | boolean | number`), or `null` when absent,
     * malformed, or a non-integral number (the canonical union is integral-only).
     */
    fun preferenceValue(name: String): PreferenceValue?
}

internal class JsonProductCommandInput(private val json: JSONObject) : ProductCommandInput {
    override fun has(name: String): Boolean = json.has(name) && !json.isNull(name)
    override fun string(name: String): String? = json.opt(name) as? String
    override fun boolean(name: String): Boolean? = json.opt(name) as? Boolean
    override fun long(name: String): Long? {
        val value = json.opt(name) as? Number ?: return null
        val integral = value.toLong()
        return integral.takeIf { value.toDouble() == integral.toDouble() }
    }
    override fun sudokuGame(name: String): SudokuGame? = decodeWireSudokuGame(json.optJSONObject(name))

    override fun dnsTiers(name: String): List<ResolverTierChoice>? {
        val array = json.optJSONArray(name) ?: return null
        val tiers = ArrayList<ResolverTierChoice>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: return null
            tiers += decodeTier(row) ?: return null
        }
        return tiers
    }

    override fun dnsChoice(name: String): ResolverTierChoice? = json.optJSONObject(name)?.let(::decodeChoice)

    override fun stringList(name: String): List<String>? {
        val array = json.optJSONArray(name) ?: return null
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            values += array.opt(index) as? String ?: return null
        }
        return values
    }

    override fun preferenceValue(name: String): PreferenceValue? {
        val value = json.opt(name) ?: return null
        if (value is Boolean) return PreferenceValue.Bool(value)
        if (value is String) return PreferenceValue.Text(value)
        // The canonical union is integral-only: a fractional JSON number is not a valid setting.
        if (value is Number) {
            val int = value.toInt()
            if (int.toDouble() == value.toDouble()) return PreferenceValue.IntValue(int)
        }
        return null
    }

    private fun decodeTier(json: JSONObject): ResolverTierChoice? {
        val id = json.opt("id") as? String ?: return null
        if (id.isEmpty()) return null
        val (providerId, transportToken) = ResolverDnsWire.decodeTierId(id)
        return ResolverTierChoice(
            providerId = providerId,
            transportToken = transportToken,
            name = json.opt("name") as? String ?: "",
            primary = json.opt("primary") as? String ?: "",
            secondary = json.opt("secondary") as? String ?: "",
            isEnabled = json.opt("isEnabled") as? Boolean ?: true,
        )
    }

    private fun decodeChoice(json: JSONObject): ResolverTierChoice? {
        val id = json.opt("id") as? String ?: return null
        if (id.isEmpty()) return null
        val (providerId, idTransport) = ResolverDnsWire.decodeTierId(id)
        val declared = (json.opt("transport") as? String)
            ?.let { ResolverTransportTokens.fromToken(it)?.token }
        return ResolverTierChoice(
            providerId = providerId,
            transportToken = declared ?: idTransport,
            name = json.opt("name") as? String ?: "",
            primary = json.opt("primary") as? String ?: "",
            secondary = json.opt("secondary") as? String ?: "",
            isEnabled = json.opt("isEnabled") as? Boolean ?: true,
        )
    }
}

/**
 * Canonical filter/library discriminators. The whole `library.*` family is backed by the shared
 * whole-library edit session and parses to [ProductLibraryCommand]; `filter.refresh` is backed by
 * the catalog sync owner. Custom blocklists and `filter.renameForm` are canonical commands
 * whose Android presentation is absent (see
 * `docs/android-contract-inventory.md`); they are recognized so the wire rejects them explicitly
 * rather than as unknown noise.
 */
internal object ProductCommandParser {
    private val UNSUPPORTED = setOf(
        "filter.renameForm",
    )

    fun parse(type: String, input: ProductCommandInput): ProductCommandParse {
        if (type in UNSUPPORTED) return ProductCommandParse.Unsupported
        return when (type) {
            "filter.open", "filter.close", "filter.edit", "filter.cancel",
            "filter.save", "filter.review", "filter.switch", "filter.delete",
            -> {
                val id = input.string("id") ?: return invalid()
                val command = when (type) {
                    "filter.open" -> ProductFilterCommand.Open(id)
                    "filter.close" -> ProductFilterCommand.Close(id)
                    "filter.edit" -> ProductFilterCommand.Edit(id)
                    "filter.cancel" -> ProductFilterCommand.Cancel(id)
                    "filter.save" -> ProductFilterCommand.Save(id)
                    "filter.review" -> ProductFilterCommand.Review(id)
                    "filter.switch" -> ProductFilterCommand.Switch(id)
                    else -> ProductFilterCommand.Delete(id)
                }
                ProductCommandParse.Supported(command)
            }
            "filter.undoDomain" -> {
                val id = input.string("id") ?: return invalid()
                val domain = input.string("domain") ?: return invalid()
                if (input.string("decision") !in listOf("blocked", "allowed")) return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.UndoDomain(id, domain))
            }
            "filter.undoList" -> {
                val id = input.string("id") ?: return invalid()
                val source = input.string("sourceID") ?: return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.UndoList(id, source))
            }
            "filter.lists" -> {
                val id = input.string("id") ?: return invalid()
                val ids = input.stringList("ids") ?: return invalid()
                if (ids.size > 1000 || ids.any { it.isBlank() } || ids.distinct().size != ids.size) return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.Lists(id, ids))
            }
            "filter.customList" -> {
                val id = input.string("id") ?: return invalid()
                val ids = input.stringList("ids") ?: return invalid()
                if (ids.size > 1000 || ids.any { it.isBlank() } || ids.distinct().size != ids.size) return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.CustomList(id, ids))
            }
            "filter.addCustomList" -> {
                val token = input.string("token") ?: return invalid()
                if (token.isBlank()) return invalid()
                val name = input.string("name") ?: return invalid()
                val url = input.string("url") ?: return invalid()
                val format = when (val raw = input.string("format")) {
                    null -> BlocklistFormat.AUTO
                    else -> BlocklistFormat.entries.firstOrNull {
                        it.name.equals(raw, ignoreCase = true)
                    } ?: return invalid()
                }
                ProductCommandParse.Supported(ProductFilterCommand.AddCustomList(token, name, url, format))
            }
            "filter.deleteCustomList" -> {
                val id = input.string("id") ?: return invalid()
                val sourceID = input.string("sourceID") ?: return invalid()
                if (sourceID.isBlank()) return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.DeleteCustomList(id, sourceID))
            }
            "filter.apply" -> {
                val id = input.string("id") ?: return invalid()
                val review = input.string("review") ?: return invalid()
                // A standalone domain-history review rides the same apply as the normal filter
                // review; its opaque token is bounded and validated by the bridge fence.
                val standalone = if (!input.has("standaloneReview")) null
                else input.string("standaloneReview")?.takeIf { it.isNotBlank() } ?: return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.Apply(id, review, standalone))
            }
            "filter.create" -> {
                val name = input.string("name") ?: return invalid()
                val duplicate = if (!input.has("duplicate")) null
                    else input.string("duplicate") ?: return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.Create(name, duplicate))
            }
            "filter.rename" -> {
                val id = input.string("id") ?: return invalid()
                val name = input.string("name") ?: return invalid()
                val emoji = if (!input.has("emoji")) null
                    else input.string("emoji") ?: return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.Rename(id, name, emoji))
            }
            "filter.domain" -> {
                val id = input.string("id") ?: return invalid()
                val domain = input.string("domain") ?: return invalid()
                val decision = when (input.string("decision")) {
                    "blocked" -> DomainDecision.BLOCKED
                    "allowed" -> DomainDecision.ALLOWED
                    else -> return invalid()
                }
                val remove = if (!input.has("remove")) false
                    else input.boolean("remove") ?: return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.Domain(id, domain, decision, remove))
            }
            "filter.removeList" -> {
                val id = input.string("id") ?: return invalid()
                val sourceID = input.string("sourceID") ?: return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.RemoveList(id, sourceID))
            }
            "filter.restoreDefaults" -> ProductCommandParse.Supported(ProductFilterCommand.RestoreDefaults)
            // Canonical whole-library edit session. The parser decodes the arguments strictly;
            // every target/name/emoji/cap rule is decided by the owner's edit session.
            "library.edit" -> ProductCommandParse.Supported(ProductLibraryCommand.Edit)
            "library.cancel" -> ProductCommandParse.Supported(ProductLibraryCommand.Cancel)
            "library.toggleDeletion" -> {
                val id = input.string("id") ?: return invalid()
                ProductCommandParse.Supported(ProductLibraryCommand.ToggleDeletion(id))
            }
            "library.form" -> when (input.string("form")) {
                "create" -> {
                    // Canonical `id` is the optional duplicate source; an explicit name is the
                    // Android adaptation because there is no create-filter sheet.
                    val name = if (!input.has("name")) null else input.string("name") ?: return invalid()
                    val duplicate = if (!input.has("id")) null else input.string("id") ?: return invalid()
                    ProductCommandParse.Supported(ProductLibraryCommand.Form.Create(name, duplicate))
                }
                "rename" -> {
                    val id = input.string("id") ?: return invalid()
                    val name = input.string("name") ?: return invalid()
                    val emoji = if (!input.has("emoji")) null else input.string("emoji") ?: return invalid()
                    ProductCommandParse.Supported(ProductLibraryCommand.Form.Rename(id, name, emoji))
                }
                "delete" -> {
                    val ids = if (!input.has("ids")) null else input.stringList("ids") ?: return invalid()
                    ProductCommandParse.Supported(ProductLibraryCommand.Form.Delete(ids))
                }
                else -> return invalid()
            }
            // Canonical `filter.refresh`: a catalog refresh for the active filter. The bridge
            // delegates the validated acquisition/cache/fallback to the catalog sync owner.
            "filter.refresh" -> {
                val id = input.string("id") ?: return invalid()
                ProductCommandParse.Supported(ProductFilterCommand.Refresh(id))            }
            // Canonical DNS commands. The ladder/custom values are decoded only; every
            // provider/transport/custom/chained restriction is decided by the policy owner.
            "dns.tiers" -> {
                val context = input.string("context") ?: return invalid()
                val tiers = input.dnsTiers("tiers") ?: return invalid()
                ProductCommandParse.Supported(ProductDnsCommand.Tiers(context, tiers))
            }
            "dns.toggle" -> {
                val context = input.string("context") ?: return invalid()
                val index = input.long("index") ?: return invalid()
                if (index !in 0L..MAX_DNS_TIER_INDEX) return invalid()
                val value = input.boolean("value") ?: return invalid()
                ProductCommandParse.Supported(ProductDnsCommand.Toggle(context, index.toInt(), value))
            }
            "dns.customDraft" -> {
                if (!input.has("choice")) {
                    ProductCommandParse.Supported(ProductDnsCommand.CustomDraft(null))
                } else {
                    val choice = input.dnsChoice("choice") ?: return invalid()
                    ProductCommandParse.Supported(ProductDnsCommand.CustomDraft(choice))
                }
            }
            "dns.custom" -> {
                val context = input.string("context") ?: return invalid()
                val name = input.string("name") ?: return invalid()
                val primary = input.string("primary") ?: return invalid()
                val secondary = input.string("secondary") ?: return invalid()
                ProductCommandParse.Supported(ProductDnsCommand.Custom(context, name, primary, secondary))
            }
            "dns.customStage" -> {
                val id = input.string("id")?.takeIf(String::isNotBlank) ?: return invalid()
                val context = input.string("context") ?: return invalid()
                val name = input.string("name") ?: return invalid()
                val primary = input.string("primary") ?: return invalid()
                val secondary = input.string("secondary") ?: return invalid()
                ProductCommandParse.Supported(ProductDnsCommand.CustomStage(id, context, name, primary, secondary))
            }
            "customEntry.dismiss" -> {
                val id = input.string("id") ?: return invalid()
                ProductCommandParse.Supported(ProductDnsCommand.DismissCustomEntry(id))
            }
            // Canonical `vpn.*` single-rotation editor. The parser decodes only the opaque ids,
            // row indexes, generation and toggle key/value; configuration text and key material
            // are refused on the wire (see ProductVpnCommand).
            "vpn.begin" -> {
                rejectSecretArguments(input)?.let { return it }
                val id = input.string("id") ?: return invalid()
                // The generation field must be present, but the canonical empty string is valid:
                // it is the committed-rotation generation an unconfigured device projects (iOS
                // `storedConfigurationGeneration.map(String.init) ?? ""`), and the first
                // configuration is imported over that not-configured state. A blank id is still
                // malformed; a missing generation stays malformed.
                val generation = input.string("generation") ?: return invalid()
                if (id.isBlank()) return invalid()
                ProductCommandParse.Supported(ProductVpnCommand.Begin(id, generation))
            }
            "vpn.cancel", "vpn.reset", "vpn.swap", "vpn.commit" -> {
                rejectSecretArguments(input)?.let { return it }
                val id = input.string("id") ?: return invalid()
                if (id.isBlank()) return invalid()
                ProductCommandParse.Supported(
                    when (type) {
                        "vpn.cancel" -> ProductVpnCommand.Cancel(id)
                        "vpn.reset" -> ProductVpnCommand.Reset(id)
                        "vpn.swap" -> ProductVpnCommand.Swap(id)
                        else -> ProductVpnCommand.Commit(id)
                    },
                )
            }
            "vpn.toggle" -> {
                rejectSecretArguments(input)?.let { return it }
                val key = input.string("key")?.let(ChainedConfigurationToggleKey::fromWireName)
                    ?: return invalid()
                val value = input.boolean("value") ?: return invalid()
                ProductCommandParse.Supported(ProductVpnCommand.Toggle(key, value))
            }
            "vpn.edit", "vpn.remove" -> {
                rejectSecretArguments(input)?.let { return it }
                val id = input.string("id") ?: return invalid()
                // The canonical editor is at most two rows; Android accepts the index only to
                // refuse the second row with a stable multi-row code.
                val index = input.long("index") ?: return invalid()
                if (index !in 0L..1L) return invalid()
                ProductCommandParse.Supported(
                    if (type == "vpn.edit") {
                        ProductVpnCommand.Edit(id, index.toInt())
                    } else {
                        ProductVpnCommand.Remove(id, index.toInt())
                    },
                )
            }
            "vpn.rowToggle" -> {
                rejectSecretArguments(input)?.let { return it }
                val generation = input.string("generation") ?: return invalid()
                if (generation.isBlank()) return invalid()
                val index = input.long("index") ?: return invalid()
                if (index !in 0L..1L) return invalid()
                val value = input.boolean("value") ?: return invalid()
                ProductCommandParse.Supported(
                    ProductVpnCommand.RowToggle(generation, index.toInt(), value),
                )
            }
            "share.query", "share.copy", "share.card", "share.present" -> {
                val id = input.string("id") ?: return invalid()
                val command = when (type) {
                    "share.query" -> ProductShareCommand.Query(id)
                    "share.copy" -> ProductShareCommand.Copy(id)
                    "share.card" -> {
                        val token = input.string("token") ?: return invalid()
                        if (token.isBlank() || token.length > 128) return invalid()
                        ProductShareCommand.Card(id, token)
                    }
                    else -> ProductShareCommand.Present(id)
                }
                ProductCommandParse.Supported(command)
            }
            // Canonical Sudoku commands. `sudoku.save` accepts only a fully validated game; a
            // malformed or foreign payload (wrong length, out-of-range cell, illegal/ambiguous
            // board) is a typed rejection, never a partial save.
            "sudoku.save" -> {
                val game = input.sudokuGame("game") ?: return invalid()
                ProductCommandParse.Supported(ProductSudokuCommand.Save(game))
            }
            "sudoku.new" -> {
                // A present non-boolean `challenge` is malformed, not silently coerced to false.
                val challenge = if (input.has("challenge")) input.boolean("challenge") ?: return invalid() else false
                ProductCommandParse.Supported(ProductSudokuCommand.New(challenge))
            }
            // Canonical dynamic settings: the value union is decoded strictly here; every
            // key/type/domain rule and owner delegation is decided by the settings dispatcher.
            "settings.set" -> {
                val key = input.string("key") ?: return invalid()
                val value = input.preferenceValue("value") ?: return invalid()
                ProductCommandParse.Supported(ProductSettingsCommand.Set(key, value))
            }
            // Canonical account commands. The process owner binds and validates the account
            // generation; a caller-supplied account/identity/token argument is refused rather
            // than ignored, so no credential or target account can be injected from RN.
            "account.google", "account.apple", "account.signOut", "account.delete" -> {
                if (input.has("account") || input.has("accountID") || input.has("userId") ||
                    input.has("token") || input.has("accessToken") || input.has("refreshToken") ||
                    input.has("envelope")
                ) {
                    return ProductCommandParse.Invalid(ProductCommandReasons.UNSUPPORTED_ARGUMENT)
                }
                ProductCommandParse.Supported(
                    when (type) {
                        "account.google" -> ProductAccountCommand.Google
                        "account.apple" -> ProductAccountCommand.Apple
                        "account.signOut" -> ProductAccountCommand.SignOut
                        else -> ProductAccountCommand.Delete
                    },
                )
            }
            // Canonical backup commands. The owner binds and validates the account/generation;
            // any caller-supplied account or envelope argument is refused rather than ignored.
            "backup.refresh", "backup.now", "backup.disable", "backup.delete" -> {
                if (input.has("account") || input.has("envelope") || input.has("accountID")) {
                    return ProductCommandParse.Invalid(ProductCommandReasons.UNSUPPORTED_ARGUMENT)
                }
                ProductCommandParse.Supported(
                    when (type) {
                        "backup.refresh" -> ProductBackupCommand.Refresh
                        "backup.now" -> ProductBackupCommand.BackUpNow
                        "backup.disable" -> ProductBackupCommand.Disable
                        else -> ProductBackupCommand.Delete
                    },
                )
            }
            // Canonical Activity/Diagnostics reads. The parser decodes and bounds every argument;
            // the app-layer query owner performs the actual owner read against the existing
            // Activity/diagnostics state. Ranges are Unix seconds; limits are integral.
            "activity.share" -> {
                val token = input.string("token") ?: return invalid()
                if (token.isBlank() || token.length > 128) return invalid()
                ProductCommandParse.Supported(ProductActivityShare(token))
            }
            "activity.card.query" -> {
                val start = input.rangeSeconds("start") ?: return invalid()
                val end = input.rangeSeconds("end") ?: return invalid()
                if (start > end || end - start > 732L * 86400L) return invalid()
                val hourly = input.boolean("hourly") ?: return invalid()
                val chart = input.string("chart") ?: return invalid()
                val period = input.string("period") ?: return invalid()
                if (chart !in setOf("total", "counts", "rate") || period !in setOf("today", "week", "month", "custom")) return invalid()
                ProductCommandParse.Supported(ProductActivityCardQuery(start, end, hourly, chart, period))
            }
            "activity.query" -> {
                val start = if (input.has("start")) input.rangeSeconds("start") ?: return invalid() else null
                val end = if (input.has("end")) input.rangeSeconds("end") ?: return invalid() else null
                if (start != null && end != null && start > end) return invalid()
                val hourly = if (input.has("hourly")) input.boolean("hourly") ?: return invalid() else false
                ProductCommandParse.Supported(ProductQueryCommand.Activity(start, end, hourly))
            }
            "domains.query" -> {
                val history = input.boolean("history") ?: return invalid()
                val decision = when (input.string("decision")) {
                    "All" -> DomainQueryDecision.ALL
                    "Allowed" -> DomainQueryDecision.ALLOWED
                    "Blocked" -> DomainQueryDecision.BLOCKED
                    else -> return invalid()
                }
                val search = input.string("search") ?: return invalid()
                if (search.length > MAX_QUERY_SEARCH_LENGTH) return invalid()
                val start = if (input.has("start")) input.rangeSeconds("start") ?: return invalid() else null
                val end = if (input.has("end")) input.rangeSeconds("end") ?: return invalid() else null
                if (start != null && end != null && start > end) return invalid()
                val limit = if (input.has("limit")) {
                    val value = input.long("limit") ?: return invalid()
                    if (value !in 1L..MAX_QUERY_LIMIT.toLong()) return invalid()
                    value.toInt()
                } else {
                    null
                }
                ProductCommandParse.Supported(
                    ProductQueryCommand.Domains(history, decision, search, start, end, limit),
                )
            }
            "network.query" -> ProductCommandParse.Supported(ProductQueryCommand.Network)
            "stats.query" -> ProductCommandParse.Supported(ProductQueryCommand.Stats)
            // Canonical domain-list actions. Copy and EnableHistory have live owners; Stage/Cancel
            // are recognized so the bridge can answer with their explicit owner availability.
            "domainCheck.query", "domainCheck.cancel", "domainCheck.stage" -> {
                val visit = input.string("visit") ?: return invalid()
                if (!visit.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) return invalid()
                when (type) {
                    "domainCheck.cancel" -> ProductCommandParse.Supported(ProductDomainCheckCommand.Cancel(visit))
                    "domainCheck.query" -> {
                        val text = input.string("input") ?: return invalid()
                        if (text.length > 4096) return invalid()
                        val refresh = if (input.has("refresh")) input.boolean("refresh") ?: return invalid() else false
                        ProductCommandParse.Supported(ProductDomainCheckCommand.Query(visit, text, refresh))
                    }
                    else -> {
                        val proof = input.string("proof") ?: return invalid()
                        if (proof.length > 128 || proof.isBlank()) return invalid()
                        val action = com.lavasec.app.foundation.filter.DomainCheckRuleAction.entries.firstOrNull { it.wire == input.string("decision") } ?: return invalid()
                        ProductCommandParse.Supported(ProductDomainCheckCommand.Stage(visit, proof, action))
                    }
                }
            }
            "domains.enableHistory" -> ProductCommandParse.Supported(ProductDomainCommand.EnableHistory)
            "domains.copy" -> {
                val domain = input.boundedDomain("domain") ?: return invalid()
                ProductCommandParse.Supported(ProductDomainCommand.Copy(domain))
            }
            "domains.stage" -> {
                val domain = input.boundedDomain("domain") ?: return invalid()
                val decision = when (input.string("decision")) {
                    "allowed" -> DomainDecision.ALLOWED
                    "blocked" -> DomainDecision.BLOCKED
                    else -> return invalid()
                }
                ProductCommandParse.Supported(ProductDomainCommand.Stage(domain, decision))
            }
            "domains.cancel" -> {
                val token = input.string("token") ?: return invalid()
                if (token.isBlank() || token.length > MAX_QUERY_TOKEN_LENGTH) return invalid()
                ProductCommandParse.Supported(ProductDomainCommand.Cancel(token))
            }
            "reports.sample" -> ProductCommandParse.Supported(ProductReportsSample)
            // Canonical `catalog.query` for the shared blocklist picker. The ids are the currently
            // staged selection (bounded, unique, non-blank); the owner resolves the real sections.
            "catalog.query" -> {
                val ids = input.stringList("ids") ?: return invalid()
                if (ids.size > MAX_CATALOG_QUERY_IDS || ids.any { it.isBlank() } || ids.distinct().size != ids.size) {
                    return invalid()
                }
                ProductCommandParse.Supported(ProductCatalogQuery(ids))
            }
            // Canonical `activity.visibility`: an opaque review-moment dwell token. The optional
            // window is bounded Unix seconds; the token is bounded and non-blank.
            "activity.visibility" -> {
                val token = input.string("token") ?: return invalid()
                if (token.isBlank() || token.length > MAX_VISIBILITY_TOKEN_LENGTH) return invalid()
                val visible = input.boolean("visible") ?: return invalid()
                val start = if (input.has("start")) input.rangeSeconds("start") ?: return invalid() else null
                val end = if (input.has("end")) input.rangeSeconds("end") ?: return invalid() else null
                if (start != null && end != null && start > end) return invalid()
                ProductCommandParse.Supported(ProductActivityVisibility(token, visible, start, end))
            }
            // Canonical local-log clear/export. The kind is a canonical button title, bounded and
            // mapped by the bridge; the surface is optional and only `activityViewing` is legal.
            "logs.clear" -> {
                val kind = input.string("kind") ?: return invalid()
                if (kind.isBlank() || kind.length > MAX_LOGS_KIND_LENGTH) return invalid()
                val surface = if (!input.has("surface")) null else input.string("surface") ?: return invalid()
                if (surface != null && surface != "activityViewing") return invalid()
                ProductCommandParse.Supported(ProductLogsCommand.Clear(kind, surface == "activityViewing"))
            }
            "logs.export" -> {
                val domains = input.boolean("domains") ?: return invalid()
                ProductCommandParse.Supported(ProductLogsCommand.Export(domains))
            }
            else -> ProductCommandParse.Unknown
        }
    }

    /** A canonical Unix-seconds range bound: a positive integer no later than the year-2100 cap. */
    private fun ProductCommandInput.rangeSeconds(name: String): Long? =
        long(name)?.takeIf { it in 1L..MAX_QUERY_RANGE_SECONDS }

    /** A non-blank, bounded domain argument; the canonical host grammar is owned by the owner. */
    private fun ProductCommandInput.boundedDomain(name: String): String? =
        string(name)?.takeIf { it.isNotBlank() && it.length <= MAX_QUERY_DOMAIN_LENGTH }

    /**
     * A `vpn.*` command carrying configuration text or key material is refused before it can be
     * parsed. The canonical editor enters the WireGuard configuration in the native presentation
     * and stages it through the owner's opaque key lease; nothing on this wire may smuggle the
     * private or pre-shared key toward JS or the product snapshot.
     */
    private fun rejectSecretArguments(input: ProductCommandInput): ProductCommandParse.Invalid? =
        if (SECRET_ARGUMENT_NAMES.any { input.has(it) }) {
            ProductCommandParse.Invalid(ProductCommandReasons.UNSUPPORTED_ARGUMENT)
        } else {
            null
        }

    private fun invalid() = ProductCommandParse.Invalid(ProductCommandReasons.INVALID_ARGUMENT)

    /** Argument names that would carry configuration text or key material onto the `vpn.*` wire. */
    private val SECRET_ARGUMENT_NAMES = setOf(
        "privateKey", "presharedKey", "preshared", "rawConfiguration", "configuration", "conf", "keyMaterial",
    )

    /** The shared Domain List pages 30 at a time and requests at most `limit + 1`. */
    private const val MAX_QUERY_LIMIT = 300

    private const val MAX_QUERY_SEARCH_LENGTH = 200

    private const val MAX_QUERY_DOMAIN_LENGTH = 253

    private const val MAX_QUERY_TOKEN_LENGTH = 256

    /** The shared picker stages at most this many selected source ids for one `catalog.query`. */
    private const val MAX_CATALOG_QUERY_IDS = 1000

    /** The longest opaque `activity.visibility` dwell token; a longer one is malformed. */
    private const val MAX_VISIBILITY_TOKEN_LENGTH = 256

    /** 2100-01-01T00:00:00Z in Unix seconds; anything later is not a real app date lens. */
    private const val MAX_QUERY_RANGE_SECONDS = 4_102_444_800L

    /** The longest canonical local-log button title; a longer kind is malformed, never unknown. */
    private const val MAX_LOGS_KIND_LENGTH = 64

    /**
     * The highest canonical `dns.toggle` tier index Android accepts. The canonical ladder is a
     * primary plus at most one fallback; a higher index is malformed, not an unowned command.
     */
    private const val MAX_DNS_TIER_INDEX = 1L
}

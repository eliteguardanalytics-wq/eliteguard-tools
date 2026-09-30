package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.Checkpoint
import com.eliteguard.checkpoint.data.Tour
import com.eliteguard.checkpoint.data.normalizeName
import com.eliteguard.checkpoint.net.SupabaseClient
import com.eliteguard.checkpoint.nfc.NfcTags
import com.eliteguard.checkpoint.util.Bg
import com.eliteguard.checkpoint.util.TimeFmt
import java.io.IOException

/**
 * Administrator screen for writing checkpoint names onto tags: select a checkpoint from this
 * tour, then hold a tag against the phone and the name is written to it. The name stays on the
 * tag until an administrator overwrites it.
 *
 * Checkpoints are normally created in the admin portal; adding and renaming them here is kept so
 * a tour can be set up from the field.
 */
class SetupActivity : Activity() {

    private val app by lazy { App.get(this) }
    private val repo by lazy { app.repo }

    private lateinit var tour: Tour
    private var checkpoints: List<Checkpoint> = emptyList()
    private var selected: Checkpoint? = null

    private lateinit var hint: TextView
    private lateinit var nfcBanner: TextView
    private lateinit var progressText: TextView
    private val adapter = SetupAdapter()
    private var nfc: NfcAdapter? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = intent.getStringExtra(EXTRA_TOUR_ID)?.let { app.db.tour(it) }
        if (loaded == null || !app.session.canManageCheckpoints) {
            finish()
            return
        }
        tour = loaded
        setContentView(R.layout.activity_setup)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), getString(R.string.setup_title), showUp = true)
        applyWindowInsets(top = findViewById(R.id.toolbar), bottom = findViewById(R.id.root))
        actionBar?.subtitle = "${tour.propertyName} · ${tour.name}"
        hint = findViewById(R.id.hint)
        nfcBanner = findViewById(R.id.nfc_banner)
        progressText = findViewById(R.id.progress_text)
        nfcBanner.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> select(checkpoints[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            showOptions(checkpoints[position]); true
        }
        nfc = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()
        if (!::tour.isInitialized) return
        reload()
        val adapter = nfc
        when {
            adapter == null -> {
                nfcBanner.text = getString(R.string.nfc_missing); nfcBanner.visibility = View.VISIBLE
            }
            !adapter.isEnabled -> {
                nfcBanner.text = getString(R.string.nfc_disabled) + "\n" + getString(R.string.nfc_open_settings)
                nfcBanner.visibility = View.VISIBLE
            }
            else -> {
                nfcBanner.visibility = View.GONE
                adapter.enableReaderMode(this, ::onTagDiscovered, NfcTags.READER_FLAGS, null)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::tour.isInitialized) nfc?.disableReaderMode(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun reload() {
        val db = app.db
        val current = tour
        Bg.run({ db.checkpoints(current.id) }) { result ->
            result.onSuccess { list ->
                checkpoints = list
                selected = list.firstOrNull { it.id == selected?.id }
                adapter.notifyDataSetChanged()
                render()
            }
        }
    }

    private fun render() {
        val current = selected
        hint.text = if (current == null) {
            getString(R.string.setup_pick_hint)
        } else {
            getString(R.string.setup_selected_hint, current.name)
        }
        hint.setBackgroundResource(if (current == null) R.drawable.bg_banner_warning else R.drawable.bg_banner_success)
        hint.setTextColor(getColor(if (current == null) R.color.accent_text else R.color.success))
        progressText.text = getString(R.string.setup_progress, checkpoints.count { it.hasTag }, checkpoints.size)
    }

    /**
     * Arming the writer is a deliberate two-step: choose the checkpoint, confirm, then tap. A
     * stray tap on a list row must never silently rewrite a tag that is already in service.
     */
    private fun select(checkpoint: Checkpoint) {
        if (selected?.id == checkpoint.id) {
            selected = null
            adapter.notifyDataSetChanged()
            render()
            return
        }
        val message = if (checkpoint.hasTag) {
            getString(R.string.setup_confirm_again, checkpoint.name)
        } else {
            getString(R.string.setup_confirm_new, checkpoint.name)
        }
        confirm(getString(R.string.setup_confirm_title), message, getString(R.string.setup_confirm_yes)) {
            selected = checkpoint
            adapter.notifyDataSetChanged()
            render()
        }
    }

    private fun showOptions(checkpoint: Checkpoint) {
        val options = arrayOf(getString(R.string.setup_rename), getString(R.string.setup_remove))
        AlertDialog.Builder(this)
            .setTitle(checkpoint.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptNames(checkpoint)
                    1 -> confirm(
                        getString(R.string.setup_remove),
                        getString(R.string.setup_remove_message, checkpoint.name),
                        getString(R.string.setup_remove_confirm),
                    ) {
                        runOnline({ repo.deactivateCheckpoint(checkpoint) }) { toast(getString(R.string.setup_saved)) }
                    }
                }
            }
            .show()
    }

    /** Adds checkpoints (one name per line) when [checkpoint] is null, otherwise renames it. */
    private fun promptNames(checkpoint: Checkpoint?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_checkpoint_names, null)
        val input = view.findViewById<EditText>(R.id.names)
        val dialogHint = view.findViewById<TextView>(R.id.hint)
        if (checkpoint == null) {
            dialogHint.setText(R.string.setup_add_hint)
        } else {
            dialogHint.setText(R.string.setup_rename_warning)
            input.setText(checkpoint.name)
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            input.minLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle(if (checkpoint == null) R.string.setup_add_title else R.string.setup_rename_title)
            .setView(view)
            .setPositiveButton(if (checkpoint == null) R.string.setup_add_save else R.string.setup_add_save) { _, _ ->
                val typed = input.text.toString()
                if (checkpoint == null) {
                    val names = typed.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (names.isEmpty()) {
                        toast(getString(R.string.setup_add_missing))
                        return@setPositiveButton
                    }
                    val current = tour
                    runOnline({ repo.createCheckpoints(current, names) }) { created ->
                        if (created.isEmpty()) {
                            toast(getString(R.string.setup_added_none), long = true)
                        } else {
                            toast(getString(R.string.setup_added, created.size))
                        }
                    }
                } else {
                    val name = typed.trim()
                    if (name.isEmpty()) {
                        toast(getString(R.string.setup_add_missing))
                        return@setPositiveButton
                    }
                    runOnline({ repo.renameCheckpoint(checkpoint, name) }) { toast(getString(R.string.setup_saved)) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Runs a server call, reports a friendly error, and refreshes the list afterwards. */
    private fun <T> runOnline(work: () -> T, onSuccess: (T) -> Unit) {
        if (busy) return
        busy = true
        Bg.run(work) { result ->
            busy = false
            result.onSuccess(onSuccess)
            result.onFailure { error ->
                when (error) {
                    is SupabaseClient.AuthException -> handleAuthExpired()
                    is SupabaseClient.ApiException -> toast(describe(error), long = true)
                    is IOException -> toast(getString(R.string.setup_offline), long = true)
                    else -> toast(describe(error), long = true)
                }
            }
            reload()
        }
    }

    // ------------------------------------------------------------------ NFC

    /**
     * Writes the selected checkpoint's name while the tag is still in the field.
     *
     * Re-pointing a tag is a normal administrator action, so the write is not gated behind a
     * confirmation dialog: the tag would have left the field by the time it was answered, and the
     * write would fail. What the tag previously said is read first and reported afterwards instead,
     * so an admin who repointed a tag by mistake can see it and put it back.
     */
    private fun onTagDiscovered(tag: Tag) {
        val uid = NfcTags.uidHex(tag)
        val target = selected
        val existing = NfcTags.readName(tag)
        if (target == null) {
            Bg.post {
                Feedback.warning(this)
                toast(getString(R.string.setup_no_selection), long = true)
                if (existing != null) toast(getString(R.string.setup_tag_says, existing), long = true)
            }
            return
        }
        val written = NfcTags.writeName(tag, target.name)
        val replaced = existing?.takeIf { normalizeName(it) != normalizeName(target.name) }
        Bg.post { finishWrite(target, uid, written, replaced) }
    }

    private fun finishWrite(target: Checkpoint, uid: String, written: Boolean, replaced: String?) {
        if (!written) {
            Feedback.warning(this)
            toast(getString(R.string.setup_write_failed), long = true)
            return
        }
        runOnline({ repo.recordTagWritten(target, uid) }) { updated ->
            Feedback.success(this)
            val message = if (replaced != null) {
                getString(R.string.setup_written_replaced, replaced, updated.name)
            } else {
                getString(R.string.setup_written, updated.name)
            }
            toast(message, long = true)
            selected = null
        }
    }

    // ------------------------------------------------------------------ list

    private inner class SetupAdapter : BaseAdapter() {
        override fun getCount(): Int = checkpoints.size
        override fun getItem(position: Int): Checkpoint = checkpoints[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.row_checkpoint, parent, false)
            val checkpoint = checkpoints[position]
            val isSelected = selected?.id == checkpoint.id
            val background = when {
                isSelected -> R.drawable.bg_card_selected
                checkpoint.hasTag -> R.drawable.bg_card_scanned
                else -> R.drawable.bg_card
            }
            view.findViewById<View>(R.id.card).setBackgroundResource(background)
            view.findViewById<TextView>(R.id.step).apply {
                text = (position + 1).toString()
                visibility = if (checkpoint.hasTag) View.GONE else View.VISIBLE
            }
            view.findViewById<ImageView>(R.id.check).visibility = if (checkpoint.hasTag) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.name).text = checkpoint.name
            val detail = view.findViewById<TextView>(R.id.detail)
            detail.text = if (checkpoint.hasTag) {
                getString(R.string.setup_tag_ready, TimeFmt.dateTime(checkpoint.tagWrittenAt))
            } else {
                getString(R.string.setup_tag_none)
            }
            detail.setTextColor(getColor(if (checkpoint.hasTag) R.color.success else R.color.text_secondary))
            return view
        }
    }

    companion object {
        private const val EXTRA_TOUR_ID = "tour_id"

        fun intent(context: Context, tourId: String): Intent =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_TOUR_ID, tourId)
    }
}

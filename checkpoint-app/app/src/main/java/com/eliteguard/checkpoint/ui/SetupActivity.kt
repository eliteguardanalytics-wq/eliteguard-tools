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
import android.view.Menu
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
import com.eliteguard.checkpoint.data.Property
import com.eliteguard.checkpoint.data.Repository
import com.eliteguard.checkpoint.nfc.NfcTags
import com.eliteguard.checkpoint.util.Bg

/**
 * Supervisor/admin screen for naming checkpoints and enrolling their NFC tags:
 * pick a checkpoint from the list, then tap a tag to link it.
 */
class SetupActivity : Activity() {

    private val app by lazy { App.get(this) }
    private val repo by lazy { app.repo }

    private lateinit var property: Property
    private var checkpoints: List<Checkpoint> = emptyList()
    private var selected: Checkpoint? = null

    private lateinit var hint: TextView
    private lateinit var nfcBanner: TextView
    private val adapter = SetupAdapter()
    private var nfc: NfcAdapter? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = intent.getStringExtra(EXTRA_PROPERTY_ID)?.let { app.db.property(it) }
        if (loaded == null || !app.session.canManageCheckpoints) {
            finish()
            return
        }
        property = loaded
        setContentView(R.layout.activity_setup)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), getString(R.string.setup_title), showUp = true)
        actionBar?.subtitle = property.name
        hint = findViewById(R.id.hint)
        nfcBanner = findViewById(R.id.nfc_banner)
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
        if (!::property.isInitialized) return
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
        if (::property.isInitialized) nfc?.disableReaderMode(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_setup, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish(); true
        }
        R.id.action_add -> {
            promptName(null); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun reload() {
        val db = app.db
        Bg.run({ db.checkpoints(property.id) }) { result ->
            result.onSuccess { list ->
                checkpoints = list
                selected = list.firstOrNull { it.id == selected?.id }
                adapter.notifyDataSetChanged()
                renderHint()
            }
        }
    }

    private fun renderHint() {
        val current = selected
        hint.text = if (current == null) getString(R.string.setup_hint) else getString(R.string.setup_selected_hint, current.name)
    }

    private fun select(checkpoint: Checkpoint) {
        if (selected?.id == checkpoint.id) {
            selected = null
            adapter.notifyDataSetChanged()
            renderHint()
            return
        }
        if (checkpoint.tagUid != null) {
            confirm(getString(R.string.setup_replace_title), getString(R.string.setup_replace_message, checkpoint.name), getString(R.string.setup_replace)) {
                selected = checkpoint
                adapter.notifyDataSetChanged()
                renderHint()
            }
            return
        }
        selected = checkpoint
        adapter.notifyDataSetChanged()
        renderHint()
    }

    private fun showOptions(checkpoint: Checkpoint) {
        val options = arrayOf(getString(R.string.setup_rename), getString(R.string.setup_deactivate))
        AlertDialog.Builder(this)
            .setTitle(checkpoint.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptName(checkpoint)
                    1 -> confirm(getString(R.string.setup_deactivate), getString(R.string.setup_deactivate_message, checkpoint.name), getString(R.string.setup_remove)) {
                        runOnline({ repo.deactivateCheckpoint(checkpoint) }) { toast(getString(R.string.setup_saved)) }
                    }
                }
            }
            .show()
    }

    /** Add (checkpoint == null) or rename a checkpoint. */
    private fun promptName(checkpoint: Checkpoint?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_checkpoint_name, null)
        val input = view.findViewById<EditText>(R.id.name)
        if (checkpoint != null) input.setText(checkpoint.name)
        AlertDialog.Builder(this)
            .setTitle(if (checkpoint == null) getString(R.string.setup_add_title) else getString(R.string.setup_rename))
            .setView(view)
            .setPositiveButton(R.string.setup_add_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.setup_add_missing))
                    return@setPositiveButton
                }
                if (checkpoint == null) {
                    runOnline({ repo.createCheckpoint(property, name) }) { toast(getString(R.string.setup_saved)) }
                } else {
                    runOnline({ repo.renameCheckpoint(checkpoint, name) }) { toast(getString(R.string.setup_saved)) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Runs a server call, shows a friendly error if offline, and refreshes the list afterwards. */
    private fun <T> runOnline(work: () -> T, onSuccess: (T) -> Unit) {
        if (busy) return
        busy = true
        Bg.run(work) { result ->
            busy = false
            result.onSuccess(onSuccess)
            result.onFailure { error ->
                when (error) {
                    is com.eliteguard.checkpoint.net.SupabaseClient.AuthException -> handleAuthExpired()
                    is Repository.TagTaken -> toast(
                        error.otherCheckpoint?.let { getString(R.string.setup_tag_taken, it.name) } ?: getString(R.string.setup_tag_taken_other), long = true
                    )
                    is com.eliteguard.checkpoint.net.SupabaseClient.ApiException -> toast(describe(error), long = true)
                    is java.io.IOException -> toast(getString(R.string.setup_offline), long = true)
                    else -> toast(describe(error), long = true)
                }
            }
            reload()
        }
    }

    // ------------------------------------------------------------------ NFC

    private fun onTagDiscovered(tag: Tag) {
        val uid = NfcTags.uidHex(tag)
        val target = selected
        // Write the checkpoint id onto the tag while it is still in range; the link by serial is what matters.
        val written = if (target != null) NfcTags.writeCheckpointId(tag, target.id) else false
        Bg.post { handleTag(uid, target, written) }
    }

    private fun handleTag(uid: String, target: Checkpoint?, written: Boolean) {
        if (target == null) {
            val owner = checkpoints.firstOrNull { it.tagUid == uid } ?: app.db.checkpointByTag(uid)
            Feedback.warning(this)
            toast(if (owner != null) getString(R.string.setup_tag_taken, owner.name) else getString(R.string.setup_no_selection), long = true)
            return
        }
        runOnline({ repo.linkTag(target, uid) }) { linked ->
            Feedback.success(this)
            toast(if (written) getString(R.string.setup_linked_written, linked.name) else getString(R.string.setup_linked, linked.name), long = true)
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
            val linked = checkpoint.tagUid != null
            view.findViewById<View>(R.id.card).setBackgroundResource(if (isSelected) R.drawable.bg_card_selected else R.drawable.bg_card)
            view.findViewById<TextView>(R.id.step).apply {
                text = (position + 1).toString()
                visibility = if (linked) View.GONE else View.VISIBLE
            }
            view.findViewById<ImageView>(R.id.check).visibility = if (linked) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.name).text = checkpoint.name
            val detail = view.findViewById<TextView>(R.id.detail)
            detail.text = if (linked) getString(R.string.setup_tag_uid, checkpoint.tagUid) else getString(R.string.setup_tag_none)
            detail.setTextColor(getColor(if (linked) R.color.success else R.color.text_secondary))
            return view
        }
    }

    companion object {
        private const val EXTRA_PROPERTY_ID = "property_id"

        fun intent(context: Context, propertyId: String): Intent =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_PROPERTY_ID, propertyId)
    }
}

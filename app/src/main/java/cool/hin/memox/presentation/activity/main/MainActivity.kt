package cool.hin.memox.presentation.activity.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.TransitionManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import cool.hin.memox.presentation.hideKeyboard
import cool.hin.memox.utils.MIME_TYPE_ZIP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.platform.MaterialFade
import cool.hin.memox.presentation.activity.main.fragment.SearchFragment
import cool.hin.memox.presentation.viewmodel.preference.NotesSort
import cool.hin.memox.presentation.viewmodel.preference.NotesSortBy
import cool.hin.memox.presentation.viewmodel.preference.NotesView
import cool.hin.memox.presentation.viewmodel.preference.SortDirection
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.sync.SyncRouter
import cool.hin.memox.data.sync.SyncState
import cool.hin.memox.data.sync.SyncStatus
import cool.hin.memox.data.sync.onedrive.OneDriveAuthHelper
import cool.hin.memox.databinding.ActivityMainBinding
import cool.hin.memox.presentation.activity.LockedActivity
import cool.hin.memox.presentation.activity.main.fragment.DisplayLabelFragment.Companion.EXTRA_DISPLAYED_LABEL
import cool.hin.memox.presentation.activity.main.fragment.MemoXFragment
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.activity.note.NoteActionHandler
import cool.hin.memox.presentation.activity.note.handleRejection
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.showKeyboard
import cool.hin.memox.presentation.setupProgressDialog
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import cool.hin.memox.presentation.viewmodel.ExportMimeType
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences.Companion.START_VIEW_DEFAULT
import cool.hin.memox.presentation.viewmodel.progress.MigrationProgress
import cool.hin.memox.utils.LATEST_DATA_SCHEMA
import cool.hin.memox.utils.backup.exportNotes
import cool.hin.memox.utils.runMigrations
import cool.hin.memox.utils.security.showBiometricOrPinPrompt
import cool.hin.memox.utils.UpdateChecker
import kotlinx.coroutines.launch

class MainActivity : LockedActivity<ActivityMainBinding>() {

    private lateinit var navController: NavController
    private lateinit var configuration: AppBarConfiguration
    private var restoringSearchText = false
    private var paperImeVisible = false
    private var paperImeBottom = 0
    private var paperSystemBottom = 0
    private lateinit var exportFileActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportNotesActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportBackupActivityResultLauncher: ActivityResultLauncher<String>
    private lateinit var identityVerifyActivityResultLauncher: ActivityResultLauncher<Intent>

    private var pendingIdentityVerifiedAction: (() -> Unit)? = null

    private var isStartViewFragment = false

    private val topLevelDestinations = setOf(
        R.id.Notes,
        R.id.PaperTodos,
        R.id.PaperOrganize,
        R.id.Reminders,
        R.id.Archive,
        R.id.Settings,
        R.id.SettingsAbout,
    )

    private val syncHideHandler = Handler(Looper.getMainLooper())
    private val syncHideRunnable = Runnable { hideSyncIsland() }
    private val actionModeCancelCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                baseModel.actionMode.close(true)
            }
        }

    var getCurrentFragmentNotes: (() -> Collection<BaseNote>?)? = null

    override fun onSupportNavigateUp(): Boolean {
        baseModel.keyword = ""
        return NavigationUI.navigateUp(navController, configuration)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "memox" && data.host == "onedrive-auth") {
            lifecycleScope.launch {
                val error = OneDriveAuthHelper.handleRedirect(this@MainActivity, data)
                if (error != null) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.onedrive_auth_failed, error),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.Toolbar)
        configureEdgeToEdgeInsets()

        setupFAB()
        setupActionMode()
        setupNavigation()
        setupToolbar()
        setupSyncIsland()

        setupActivityResultLaunchers()

        checkForMigrations(savedInstanceState)

        // Sync with the active provider on app start (pull remote updates)
        SyncRouter.syncNow(this)

        // Handle OneDrive OAuth redirect if the activity was (re)launched by it
        handleOAuthRedirect(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (baseModel.actionMode.enabled.value) {
                        return
                    }
                    // 下拉呼出的搜索框（尚未进入 Search 页面）优先收起
                    if (binding.SearchEditText.hasFocus() &&
                        navController.currentDestination?.id != R.id.Search
                    ) {
                        binding.SearchEditText.clearFocus()
                        hideKeyboard(binding.SearchEditText)
                        return
                    }
                    // Let Navigation handle back first (e.g. Settings sub-page -> Settings root)
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else if (
                        !isStartViewFragment &&
                            !intent.getBooleanExtra(EXTRA_SKIP_START_VIEW_ON_BACK, false)
                    ) {
                        navigateToStartView()
                    } else {
                        finish()
                    }
                }
            },
        )
        onBackPressedDispatcher.addCallback(this, actionModeCancelCallback)

        baseModel.progress.setupProgressDialog(this)
    }

    override fun initViewModel() {}

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NoteActionHandler.REQUEST_NOTIFICATION_PERMISSION_PIN_TO_STATUS -> {
                if (
                    grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    val baseNotes = baseModel.actionMode.selectedNotes.values
                    baseModel.pinBaseNotesToStatusBar(
                        this@MainActivity,
                        baseNotes.any { !it.isPinnedToStatus },
                    )
                } else handleRejection(R.string.to_pin_note_status_bar)
            }
        }
    }

    private fun checkForMigrations(savedInstanceState: Bundle?) {
        val proceed: () -> Unit = {
            baseModel.startObserving()
            val fragmentIdToLoad = intent.getIntExtra(EXTRA_FRAGMENT_TO_OPEN, -1)
            if (fragmentIdToLoad != -1) {
                navController.navigate(fragmentIdToLoad, intent.extras)
            } else if (savedInstanceState == null) {
                navigateToStartView()
            }
        }
        if (preferences.dataSchemaId.value < LATEST_DATA_SCHEMA) {
            val migrationProgress = MutableLiveData<MigrationProgress>()
            migrationProgress.setupProgressDialog(this)
            lifecycleScope.launch {
                migrationProgress.postValue(
                    MigrationProgress(R.string.migrating_data, indeterminate = true)
                )
                application.runMigrations { titleId ->
                    migrationProgress.postValue(MigrationProgress(titleId, indeterminate = true))
                }
                migrationProgress.postValue(
                    MigrationProgress(R.string.migrating_data, inProgress = false)
                )
                proceed()
            }
        } else {
            proceed()
        }
    }

    private fun configureEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val navHostFragment = binding.NavHostFragment
        ViewCompat.setOnApplyWindowInsetsListener(binding.RelativeLayout) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            binding.Toolbar.apply {
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
                requestLayout()
            }

            binding.ActionMode.apply {
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
                requestLayout()
            }

            paperImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            paperImeBottom = imeInsets.bottom
            paperSystemBottom = systemBarsInsets.bottom
            (binding.BottomNavigation.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = 12.dp + systemBarsInsets.bottom
            }
            (binding.TakeNote.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = 24.dp + systemBarsInsets.bottom
            }
            navHostFragment.setPadding(0, 0, 0,
                if (paperImeVisible) imeInsets.bottom else 104.dp + systemBarsInsets.bottom)
            updatePaperChrome()
            insets
        }
    }

    private fun getStartViewNavigation(): Pair<Int, Bundle> {
        return when (val startView = preferences.startView.value) {
            START_VIEW_DEFAULT -> Pair(R.id.Notes, Bundle())
            else -> {
                val bundle = Bundle().apply { putString(EXTRA_DISPLAYED_LABEL, startView) }
                Pair(R.id.DisplayLabel, bundle)
            }
        }
    }

    private fun navigateToStartView() {
        val (id, bundle) = getStartViewNavigation()
        navController.navigate(id, bundle)
    }

    private fun setupFAB() {
        binding.TakeNote.setOnClickListener {
            val intent = Intent(this, EditNoteActivity::class.java)
            startActivity(prepareNewNoteIntent(intent))
        }
        binding.NavNotes.setOnClickListener { navigatePaperTab(R.id.Notes) }
        binding.NavTodos.setOnClickListener { navigatePaperTab(R.id.PaperTodos) }
        binding.NavOrganize.setOnClickListener { navigatePaperTab(R.id.PaperOrganize) }
        binding.NavExport.contentDescription = getString(R.string.export_backup)
        binding.NavExport.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(Date())
            exportBackupActivityResultLauncher.launch(getString(R.string.ui_backup_filename, timestamp))
        }
    }

    private fun prepareNewNoteIntent(intent: Intent): Intent {
        return supportFragmentManager
            .findFragmentById(R.id.NavHostFragment)
            ?.childFragmentManager
            ?.fragments
            ?.firstOrNull()
            ?.let { fragment ->
                return if (fragment is MemoXFragment) {
                    fragment.prepareNewNoteIntent(intent)
                } else intent
            } ?: intent
    }

    private fun setupActionMode() {
        binding.ActionMode.setNavigationOnClickListener { baseModel.actionMode.close(true) }

        val transition =
            MaterialFade().apply {
                secondaryAnimatorProvider = null
                excludeTarget(binding.NavHostFragment, true)
                excludeChildren(binding.NavHostFragment, true)
                excludeTarget(binding.TakeNote, true)
            }

        baseModel.actionMode.enabled.observe(this) { enabled ->
            TransitionManager.beginDelayedTransition(binding.RelativeLayout, transition)
            if (enabled) {
                binding.Toolbar.visibility = View.GONE
                binding.ActionMode.visibility = View.VISIBLE
                syncHideHandler.removeCallbacks(syncHideRunnable)
                binding.SyncIsland.visibility = View.GONE
            } else {
                binding.Toolbar.visibility = View.VISIBLE
                binding.ActionMode.visibility = View.GONE
            }
            updatePaperChrome()
            actionModeCancelCallback.isEnabled = enabled
        }

        val menu = binding.ActionMode.menu
        baseModel.folder.observe(this@MainActivity, ModelFolderObserver(this, menu, baseModel))
        baseModel.actionMode.loading.observe(this@MainActivity) { loading ->
            menu.setGroupEnabled(Menu.NONE, !loading)
        }
    }

    private fun setupSyncIsland() {
        SyncStatus.state.observe(this) { ui ->
            when (ui.state) {
                SyncState.SYNCING -> {
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    showSyncIsland(syncing = true, message = ui.message)
                }
                SyncState.COMPLETED -> {
                    showSyncIsland(syncing = false, message = ui.message)
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    syncHideHandler.postDelayed(syncHideRunnable, 3000)
                }
                SyncState.IDLE -> {
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    hideSyncIsland()
                }
            }
        }
    }

    /** [message] 非空时展示自定义文案（如批量压缩进度），否则回落到默认同步文案。 */
    private fun showSyncIsland(syncing: Boolean, message: String? = null) {
        val island = binding.SyncIsland
        binding.SyncIslandText.text =
            message
                ?: if (syncing) getString(R.string.sync_status_syncing)
                else getString(R.string.sync_status_completed)
        binding.SyncIslandSpinner.visibility = if (syncing) View.VISIBLE else View.GONE
        binding.SyncIslandDone.visibility = if (syncing) View.GONE else View.VISIBLE
        if (island.visibility != View.VISIBLE) {
            // Expand from the centre (horizontal "pop") for a Dynamic-Island-like reveal.
            island.alpha = 0f
            island.scaleX = 0.4f
            island.scaleY = 1f
            island.visibility = View.VISIBLE
            island.animate()
                .alpha(1f)
                .scaleX(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator())
                .start()
        }
    }

    private fun hideSyncIsland() {
        val island = binding.SyncIsland
        if (island.visibility == View.VISIBLE) {
            // Contract back towards the centre and fade out.
            island.animate()
                .alpha(0f)
                .scaleX(0.4f)
                .setDuration(220)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { island.visibility = View.GONE }
                .start()
        }
    }

    internal fun exportSelectedNotes(mimeType: ExportMimeType) {
        exportNotes(
            baseModel.actionMode.selectedNotes.values,
            mimeType,
            exportFileActivityResultLauncher,
            exportNotesActivityResultLauncher,
        )
    }

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.NavHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        configuration = AppBarConfiguration(topLevelDestinations, binding.DrawerLayout)
        binding.NavView.setupWithNavController(navController)

        // Explicit drawer toggle. The NavigationUI auto-wiring (setupActionBarWithNavController)
        // was not opening the drawer on this device, so we wire the hamburger explicitly.
        val toggle =
            ActionBarDrawerToggle(
                this,
                binding.DrawerLayout,
                binding.Toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close,
            )
        binding.DrawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navController.addOnDestinationChangedListener { _, destination, bundle ->
            val isTopLevel = destination.id in topLevelDestinations
            if (isTopLevel) {
                toggle.isDrawerIndicatorEnabled = true
                toggle.syncState()
            } else {
                // 非顶层（设置二级页等）：显式显示带 tint 的返回箭头。
                // ActionBarDrawerToggle 自带的箭头在某些设备因未着色而不可见。
                toggle.isDrawerIndicatorEnabled = false
                binding.Toolbar.setNavigationIcon(R.drawable.back)
                binding.Toolbar.navigationIcon?.setTint(
                    MaterialColors.getColor(
                        binding.Toolbar,
                        com.google.android.material.R.attr.colorOnSurface,
                        0,
                    ),
                )
            }

            toggle.setToolbarNavigationClickListener {
                if (destination.id in topLevelDestinations) {
                    binding.DrawerLayout.openDrawer(GravityCompat.START)
                } else {
                    NavigationUI.navigateUp(navController, configuration)
                }
            }
            // 搜索栏显隐：进入 Search 目的显示搜索栏，否则显示常态栏
            if (destination.id == R.id.Search) {
                showSearchBar()
            } else {
                hideSearchBar()
                restoringSearchText = true
                binding.SearchEditText.setText(baseModel.keyword)
                restoringSearchText = false
            }
            when (destination.id) {
                R.id.Notes -> baseModel.currentLabel = baseModel.homeLabel
                R.id.Search -> Unit
                R.id.Unlabeled -> baseModel.currentLabel = null
                R.id.DisplayLabel ->
                    bundle?.getString(EXTRA_DISPLAYED_LABEL)?.let {
                        baseModel.currentLabel = it
                    }
                else -> {
                    baseModel.currentLabel = BaseNoteModel.CURRENT_LABEL_EMPTY
                }
            }
            binding.BrandName.text = when (destination.id) {
                R.id.Notes, R.id.Search -> getString(R.string.product_name)
                R.id.PaperTodos -> getString(R.string.paper_todos)
                R.id.PaperOrganize -> getString(R.string.paper_organize)
                R.id.Deleted -> getString(R.string.paper_trash)
                R.id.Labels -> getString(R.string.paper_label_categories)
                R.id.Folders -> getString(R.string.paper_folder_categories)
                R.id.DisplayLabel -> bundle?.getString(EXTRA_DISPLAYED_LABEL).orEmpty()
                R.id.DisplayFolder -> bundle?.getString("memox.intent.extra.DISPLAYED_FOLDER").orEmpty()
                else -> destination.label?.toString().orEmpty()
            }
            updatePaperChrome()
            isStartViewFragment = isStartViewFragment(destination.id, bundle)
        }

        setupUpdateBadges()
    }

    /**
     * 有新版本时，在抽屉「关于」菜单项（actionLayout 红点）显示红点。红点持续显示直到没有新版本。
     */
    private fun setupUpdateBadges() {
        binding.NavView.post {
            val aboutDot =
                binding.NavView.menu.findItem(R.id.SettingsAbout).actionView
                    ?.findViewById<View>(R.id.badge_dot)
            UpdateChecker.state.observe(this) { info ->
                val available = info != null && UpdateChecker.shouldShow(info)
                aboutDot?.visibility = if (available) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupToolbar() {
        val searchEditText = binding.SearchEditText
        val avatar = binding.AvatarButton
        restoringSearchText = true
        searchEditText.setText(baseModel.keyword)
        restoringSearchText = false
        searchEditText.doAfterTextChanged { text ->
            if (restoringSearchText) return@doAfterTextChanged
            val keyword = text?.toString().orEmpty()
            if (baseModel.keyword != keyword) {
                baseModel.keyword = keyword
            }
            val isSearch = navController.currentDestination?.id == R.id.Search
            if (keyword.isNotEmpty() && !isSearch) {
                navController.navigate(
                    R.id.Search,
                    Bundle().apply {
                        putSerializable(SearchFragment.EXTRA_INITIAL_FOLDER, baseModel.folder.value)
                        putString(SearchFragment.EXTRA_INITIAL_LABEL, baseModel.currentLabel)
                    },
                )
            } else if (keyword.isEmpty() && isSearch) {
                navController.popBackStack()
            }
        }

        binding.HeaderSearchButton.setOnClickListener { showSearchBar() }
        binding.FilterButton.setOnClickListener { showSortMenu(it) }
        avatar.setOnClickListener {
            AccountSheetDialog().show(supportFragmentManager, AccountSheetDialog.TAG)
        }

        baseModel.preferences.onedriveSyncEnabled.observe(this) { enabled ->
            updateAvatar(enabled)
        }
    }

    /**
     * Shows the Microsoft account picture once OneDrive is connected, falling back to the tinted
     * default icon. If the picture has not been cached yet it is downloaded in the background.
     */
    private fun updateAvatar(onedriveEnabled: Boolean) {
        val avatar = binding.AvatarButton
        val photo = if (onedriveEnabled) OneDriveAuthHelper.getAvatarFile(this) else null
        if (photo != null) {
            avatar.clearColorFilter()
            avatar.imageTintList = null
            Glide.with(this)
                .load(photo)
                .signature(ObjectKey(photo.lastModified()))
                .circleCrop()
                .placeholder(R.drawable.account_circle)
                .into(avatar)
            return
        }

        Glide.with(this).clear(avatar)
        avatar.setImageResource(R.drawable.account_circle)
        val color =
            if (onedriveEnabled) {
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0)
            } else {
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    0,
                )
            }
        avatar.setColorFilter(color)

        if (onedriveEnabled) {
            lifecycleScope.launch {
                OneDriveAuthHelper.fetchAndStoreAvatar(this@MainActivity)
                if (OneDriveAuthHelper.getAvatarFile(this@MainActivity) != null) {
                    updateAvatar(true)
                }
            }
        }
    }

    internal fun showSearchBar(clearText: Boolean = false) {
        binding.SearchPill.visibility = View.VISIBLE
        if (clearText) {
            restoringSearchText = true
            binding.SearchEditText.setText("")
            baseModel.keyword = ""
            restoringSearchText = false
        }
        binding.SearchEditText.post {
            binding.SearchEditText.requestFocus()
            showKeyboard(binding.SearchEditText)
        }
    }

    internal fun hideSearchBar() {
        val notesDestinations = setOf(R.id.Notes, R.id.Search, R.id.DisplayLabel,
            R.id.DisplayFolder, R.id.Unlabeled, R.id.Archive)
        binding.SearchPill.visibility =
            if (navController.currentDestination?.id in notesDestinations) View.VISIBLE else View.GONE
    }

    fun isSearchBarVisible(): Boolean = binding.SearchPill.visibility == View.VISIBLE

    private fun navigatePaperTab(destination: Int) {
        if (navController.currentDestination?.id == destination) return
        baseModel.actionMode.close(true)
        baseModel.keyword = ""
        restoringSearchText = true
        binding.SearchEditText.setText("")
        restoringSearchText = false
        hideKeyboard(binding.SearchEditText)
        navController.navigate(destination, null, navOptions {
            launchSingleTop = true
            popUpTo(R.id.Notes) { inclusive = false }
        })
    }

    private fun updatePaperChrome() {
        if (!::navController.isInitialized) return
        val destination = navController.currentDestination?.id
        val rootPages = setOf(R.id.Notes, R.id.Search, R.id.PaperTodos, R.id.PaperOrganize,
            R.id.DisplayLabel, R.id.DisplayFolder, R.id.Unlabeled)
        val utility = destination in setOf(R.id.Deleted, R.id.Labels, R.id.Folders, R.id.PaperOrganize)
        val selecting = baseModel.actionMode.enabled.value && destination != R.id.Deleted
        binding.Toolbar.visibility = if (selecting) View.GONE else View.VISIBLE
        binding.ActionMode.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.BrandLogo.visibility = if (utility) View.GONE else View.VISIBLE
        binding.BrandSubtitle.visibility = if (utility) View.GONE else View.VISIBLE
        binding.HeaderSearchButton.visibility = if (utility) View.GONE else View.VISIBLE
        binding.AvatarButton.visibility = if (utility) View.GONE else View.VISIBLE
        binding.BrandName.textSize = if (utility) 20f else 23f
        val toolbarParams = binding.Toolbar.layoutParams
        toolbarParams.height = if (utility) 56.dp else 64.dp
        binding.Toolbar.layoutParams = toolbarParams
        binding.PaperHeader.visibility = if (selecting) View.GONE else View.VISIBLE
        val params = binding.NavHostFragment.layoutParams as RelativeLayout.LayoutParams
        params.addRule(RelativeLayout.BELOW, if (selecting) R.id.ActionMode else R.id.PaperHeader)
        binding.NavHostFragment.layoutParams = params
        val showNavigation = destination in rootPages && !selecting && !paperImeVisible
        binding.NavHostFragment.setPadding(0, 0, 0, if (paperImeVisible) paperImeBottom
            else (if (showNavigation) 104.dp else 12.dp) + paperSystemBottom)
        binding.BottomNavigation.visibility = if (showNavigation) View.VISIBLE else View.GONE
        if (showNavigation) binding.TakeNote.show() else binding.TakeNote.hide()
        val active = when (destination) {
            R.id.PaperTodos -> binding.NavTodos
            R.id.PaperOrganize, R.id.Labels, R.id.Folders, R.id.DisplayLabel, R.id.DisplayFolder -> binding.NavOrganize
            else -> binding.NavNotes
        }
        listOf(binding.NavNotes, binding.NavTodos, binding.NavOrganize, binding.NavExport).forEach {
            val color = ContextCompat.getColor(this, if (it === active) R.color.paper_accent_ink else R.color.paper_muted)
            it.setTextColor(color)
            TextViewCompat.setCompoundDrawableTintList(it, android.content.res.ColorStateList.valueOf(color))
            it.isSelected = it === active
        }
    }

    internal fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val current = baseModel.preferences.notesSorting.value
        NotesSortBy.entries.forEach { sortBy ->
            val label = getString(sortBy.textResId)
            val title =
                if (sortBy == current.sortedBy) {
                    "$label  ${if (current.sortDirection == SortDirection.DESC) "▼" else "▲"}"
                } else {
                    label
                }
            val item = popup.menu.add(Menu.NONE, sortBy.ordinal, Menu.NONE, title)
            item.isChecked = (sortBy == current.sortedBy)
        }
        popup.menu.setGroupCheckable(Menu.NONE, true, true)
        popup.setOnMenuItemClickListener { item ->
            val sortBy = NotesSortBy.entries[item.itemId]
            val cur = baseModel.preferences.notesSorting.value
            val next =
                if (sortBy == cur.sortedBy) {
                    cur.copy(
                        sortDirection =
                            if (cur.sortDirection == SortDirection.DESC) {
                                SortDirection.ASC
                            } else {
                                SortDirection.DESC
                            },
                    )
                } else {
                    cur.copy(sortedBy = sortBy)
                }
            baseModel.preferences.notesSorting.save(next)
            true
        }
        popup.show()
    }

    private fun isStartViewFragment(id: Int, bundle: Bundle?): Boolean {
        val (startViewId, startViewBundle) = getStartViewNavigation()
        return startViewId == id &&
            startViewBundle.getString(EXTRA_DISPLAYED_LABEL) ==
                bundle?.getString(EXTRA_DISPLAYED_LABEL)
    }

    internal fun navigateWithAnimation(id: Int) {
        val options = navOptions {
            launchSingleTop = true
            anim {
                exit = androidx.navigation.ui.R.anim.nav_default_exit_anim
                enter = androidx.navigation.ui.R.anim.nav_default_enter_anim
                popExit = androidx.navigation.ui.R.anim.nav_default_pop_exit_anim
                popEnter = androidx.navigation.ui.R.anim.nav_default_pop_enter_anim
            }
            popUpTo(navController.graph.startDestination) { inclusive = false }
        }
        navController.navigate(id, null, options)
    }

    private fun setupActivityResultLaunchers() {
        exportBackupActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_TYPE_ZIP)) { uri ->
                if (uri != null) baseModel.exportBackup(uri)
            }
        exportFileActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        baseModel.exportSelectedNoteToFile(uri, binding.root)
                    }
                }
            }
        exportNotesActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        baseModel.exportSelectedNotesToFolder(uri, binding.root)
                    }
                }
            }
        identityVerifyActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    pendingIdentityVerifiedAction?.invoke()
                }
                pendingIdentityVerifiedAction = null
            }
    }

    fun verifyIdentityThen(action: () -> Unit) {
        showBiometricOrPinPrompt(
            isForDecrypt = false,
            cipherIv = null,
            activityResultLauncher = identityVerifyActivityResultLauncher,
            titleResId = R.string.unlock,
            descriptionResId = R.string.note_locked,
            onSuccess = { _ -> action() },
        ) { errorCode ->
            if (
                errorCode ==
                    android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                    errorCode ==
                        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
            ) {
                action()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Labels and Settings are now reached from the navigation drawer, so the top bar
        // only hosts the search pill and the account avatar (wired in setupToolbar).
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            ACTION_LABELS -> {
                navController.navigate(R.id.Labels)
                true
            }
            ACTION_SETTINGS -> {
                navController.navigate(R.id.Settings)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FRAGMENT_TO_OPEN = "memox.intent.extra.FRAGMENT_TO_OPEN"
        const val EXTRA_SKIP_START_VIEW_ON_BACK = "memox.intent.extra.SKIP_START_VIEW_ON_BACK"
        private const val ACTION_LABELS = 1001
        private const val ACTION_SETTINGS = 1002
        val ACTIVITES_WITHOUT_TOOLBAR_ICONS =
            setOf(
                R.id.Settings,
                R.id.SettingsAppearance,
                R.id.SettingsBackup,
                R.id.SettingsData,
                R.id.SettingsAbout,
                R.id.Labels,
                R.id.Search,
            )
    }
}

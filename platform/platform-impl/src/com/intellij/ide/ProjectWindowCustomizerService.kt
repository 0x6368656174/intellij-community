// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide

import com.intellij.ide.actions.DistractionFreeModeController
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.ex.WindowManagerEx
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.ui.ColorUtil
import com.intellij.ui.GotItTooltip
import com.intellij.ui.JBColor
import com.intellij.util.PlatformUtils
import com.intellij.util.concurrency.SynchronizedClearableLazy
import com.intellij.util.concurrency.annotations.RequiresBlockingContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import java.awt.*
import java.util.*
import javax.swing.Icon
import javax.swing.JComponent

private fun getProjectPath(project: Project, recentProjectManager: RecentProjectsManagerBase): String {
  return recentProjectManager.getProjectPath(project) ?: project.basePath ?: run {
    //thisLogger().warn("Impossible: no path for project $project")
    ""
  }
}

private fun getProjectNameForIcon(project: Project): String {
  val recentProjectManager = RecentProjectsManagerBase.getInstanceEx()
  val path = getProjectPath(project, recentProjectManager)
  return RecentProjectIconHelper.getProjectName(path, recentProjectManager)
}

@Service(Service.Level.PROJECT)
private class ProjectWindowCustomizerIconCache(private val project: Project) {
  val cachedIcon: SynchronizedClearableLazy<Icon> = SynchronizedClearableLazy { getIconRaw() }

  init {
    val busConnection = project.messageBus.simpleConnect()
    busConnection.subscribe(UISettingsListener.TOPIC, UISettingsListener {
      cachedIcon.drop()
    })
    busConnection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
      cachedIcon.drop()
    })
  }

  private fun getIconRaw(): Icon {
    val recentProjectsManager = RecentProjectsManagerBase.getInstanceEx()
    val path = getProjectPath(project, recentProjectsManager)
    return recentProjectsManager.getProjectIcon(path = path, isProjectValid = true, iconSize = 16)
  }
}

internal enum class MainToolbarCustomizationType {
  JUST_ICON,
  LINEAR_GRAD_WITH_ICON,
  CIRCULAR_GRADIENT_WITH_ICON,
  DROPDOWN_WITH_ICON,
  JUST_DROPDOWN;

  fun isLinearGradient() = this == LINEAR_GRAD_WITH_ICON
  fun isCircularGradient() = this == CIRCULAR_GRADIENT_WITH_ICON
  fun isGradient() = isLinearGradient() || isCircularGradient()
  fun isShowIcon() = this != JUST_DROPDOWN
  fun isDropdown() = this == JUST_DROPDOWN || this == DROPDOWN_WITH_ICON
}

private const val TOOLBAR_BACKGROUND_KEY = "PROJECT_TOOLBAR_COLOR"
private const val ASSOCIATED_PROJECT_COLOR_INDEX_KEY = "ASSOCIATED_PROJECT_COLOR_INDEX_KEY"
private const val LAST_CALCULATED_COLOR_INDEX_KEY = "LAST_CALCULATED_COLOR_INDEX_KEY"

private fun isForceColorfulToolbar() = Registry.`is`("ide.colorful.toolbar.force", true)

private fun conditionToEnable() = isForceColorfulToolbar() || ProjectManagerEx.getOpenProjects().size > 1

private data class ProjectColors(@JvmField val gradient: Color, @JvmField val background: Color)

@Service
class ProjectWindowCustomizerService : Disposable {
  companion object {
    private var instance: ProjectWindowCustomizerService? = null

    init {
      ApplicationManager.registerCleaner { instance = null }
    }

    @RequiresBlockingContext
    fun getInstance(): ProjectWindowCustomizerService {
      var result = instance
      if (result == null) {
        result = service<ProjectWindowCustomizerService>()
        instance = result
      }
      return result
    }
  }

  private var wasGradientPainted = isForceColorfulToolbar()

  private var ourSettingsValue = UISettings.getInstance().differentiateProjects
  private val colorCache = mutableMapOf<String, ProjectColors>()
  private val listeners = mutableListOf<(Boolean) -> Unit>()

  private val gradientColors: Array<Color>
    get() = arrayOf(
      JBColor.namedColor("RecentProject.Color1.MainToolbarGradientStart", JBColor(0xDB3D3C, 0xCE443C)),
      JBColor.namedColor("RecentProject.Color2.MainToolbarGradientStart", JBColor(0xF57236, 0xE27237)),
      JBColor.namedColor("RecentProject.Color3.MainToolbarGradientStart", JBColor(0x2BC8BB, 0x2DBCAD)),
      JBColor.namedColor("RecentProject.Color4.MainToolbarGradientStart", JBColor(0x359AF2, 0x3895E1)),
      JBColor.namedColor("RecentProject.Color5.MainToolbarGradientStart", JBColor(0x8379FB, 0x7B75E8)),
      JBColor.namedColor("RecentProject.Color6.MainToolbarGradientStart", JBColor(0x7E54B5, 0x7854AD)),
      JBColor.namedColor("RecentProject.Color7.MainToolbarGradientStart", JBColor(0xD63CC8, 0x8F4593)),
      JBColor.namedColor("RecentProject.Color8.MainToolbarGradientStart", JBColor(0x954294, 0xC840B9)),
      JBColor.namedColor("RecentProject.Color9.MainToolbarGradientStart", JBColor(0xE75371, 0xD75370))
    )

  private val backgroundColors: Array<Color>
    get() = arrayOf(
      JBColor.namedColor("RecentProject.Color1.MainToolbarDropdownBackground", JBColor(0x534036, 0x534036)),
      JBColor.namedColor("RecentProject.Color2.MainToolbarDropdownBackground", JBColor(0x453F2D, 0x453F2D)),
      JBColor.namedColor("RecentProject.Color3.MainToolbarDropdownBackground", JBColor(0x414130, 0x414130)),
      JBColor.namedColor("RecentProject.Color4.MainToolbarDropdownBackground", JBColor(0x2B434E, 0x2B434E)),
      JBColor.namedColor("RecentProject.Color5.MainToolbarDropdownBackground", JBColor(0x2F3F5E, 0x2F3F5E)),
      JBColor.namedColor("RecentProject.Color6.MainToolbarDropdownBackground", JBColor(0x4B2E3E, 0x4B2E3E)),
      JBColor.namedColor("RecentProject.Color7.MainToolbarDropdownBackground", JBColor(0x47385A, 0x47385A)),
      JBColor.namedColor("RecentProject.Color8.MainToolbarDropdownBackground", JBColor(0x1E403E, 0x1E403E)),
      JBColor.namedColor("RecentProject.Color9.MainToolbarDropdownBackground", JBColor(0x324533, 0x324533))
    )

  fun getProjectIcon(project: Project): Icon {
    return project.service<ProjectWindowCustomizerIconCache>().cachedIcon.get()
  }

  private fun getGradientProjectColor(project: Project): Color = getProjectColor(project).gradient

  fun getBackgroundProjectColor(project: Project): Color = getProjectColor(project).background

  private fun getProjectColor(project: Project): ProjectColors {
    val projectPath = getProjectNameForIcon(project)
    val colorStr = PropertiesComponent.getInstance(project).getValue(TOOLBAR_BACKGROUND_KEY)
    val color = ColorUtil.fromHex(colorStr, null)
    if (color != null) {
      return ProjectColors(color, color)
    }

    // Get calculated earlier color or calculate next color
    return colorCache.computeIfAbsent(projectPath) {
      Disposer.register(project) { colorCache.remove(projectPath) }
      val associatedIndex = getOrGenerateAssociatedColorIndex(projectPath)

      ProjectColors(background = backgroundColors[associatedIndex],
                    gradient = gradientColors[associatedIndex])
    }
  }

  fun getOrGenerateAssociatedColorIndex(projectPath: String): Int {
    var index = propertiesStorage.getInt(associatedProjectColorKey(projectPath), -1)
    if (index >= 0 && index < backgroundColors.size && index < gradientColors.size) return index

    // Calculate next colors by incrementing (and saving the new value) color index
    index = propertiesStorage.nextColorIndex(minOf(backgroundColors.size, gradientColors.size))

    // Save calculated colors and clear customized colors for the project
    setAssociatedColorsIndex(projectPath, index)

    return index
  }

  private fun setAssociatedColorsIndex(projectPath: String, index: Int) {
    propertiesStorage.setValue(associatedProjectColorKey(projectPath), index, -1)
  }

  private fun PropertiesComponent.nextColorIndex(colorsCount: Int): Int {
    val randomDefault = Random().nextInt() % colorsCount
    val result = (getInt(LAST_CALCULATED_COLOR_INDEX_KEY, randomDefault) + 1) % colorsCount
    setValue(LAST_CALCULATED_COLOR_INDEX_KEY, result, -1)
    return result
  }

  private val propertiesStorage: PropertiesComponent get() = PropertiesComponent.getInstance()

  private fun associatedProjectColorKey(projectPath: String) = "${ASSOCIATED_PROJECT_COLOR_INDEX_KEY}_$projectPath"

  internal fun getPaintingType(): MainToolbarCustomizationType {
    return when (Registry.get("ide.colorful.toolbar.gradient.type").selectedOption) {
      "Just Icon"                    -> MainToolbarCustomizationType.JUST_ICON
      "Linear Gradient and Icon"     -> MainToolbarCustomizationType.LINEAR_GRAD_WITH_ICON
      "Circular Gradient and Icon"   -> MainToolbarCustomizationType.CIRCULAR_GRADIENT_WITH_ICON
      "Dropdown Background and Icon" -> MainToolbarCustomizationType.DROPDOWN_WITH_ICON
      "Just Dropdown"                -> MainToolbarCustomizationType.JUST_DROPDOWN
      else                           -> MainToolbarCustomizationType.LINEAR_GRAD_WITH_ICON
    }
  }

  internal fun update(newValue: Boolean) {
    if (newValue != ourSettingsValue) {
      ourSettingsValue = newValue
      wasGradientPainted = newValue && conditionToEnable()
      fireUpdate()
    }
  }

  fun isAvailable(): Boolean {
    return !DistractionFreeModeController.isDistractionFreeModeEnabled() &&
           (PlatformUtils.isRider () || Registry.`is`("ide.colorful.toolbar", true))
  }

  fun isActive(): Boolean = wasGradientPainted && ourSettingsValue && isAvailable()

  private var gotItShown
    get() = PropertiesComponent.getInstance().getBoolean("colorful.instances.gotIt.shown", false)
    set(value) { PropertiesComponent.getInstance().setValue("colorful.instances.gotIt.shown", value) }

  fun enableIfNeeded() {
    if (!wasGradientPainted && conditionToEnable()) {
      wasGradientPainted = true
      fireUpdate()
    }
  }

  fun addListener(coroutineScope: CoroutineScope, fireFirstTime: Boolean, listener: (Boolean) -> Unit) {
    if (fireFirstTime) {
      listener(isActive())
    }
    listeners.add(listener)
    coroutineScope.coroutineContext.job.invokeOnCompletion {
      listeners.remove(listener)
    }
  }

  private fun fireUpdate() {
    listeners.forEach { it(isActive()) }
  }

  private fun recordGotItShown() {
    gotItShown = true
  }

  fun showGotIt(project: Project, component: JComponent) {
    if (!PlatformUtils.isRider()) {
      return
    }
    if (gotItShown || !isActive()) {
      return
    }

    val gotIt = GotItTooltip("colorful.instances", IdeBundle.message("colorfulInstances.gotIt.text"), this).apply {
      withHeader(IdeBundle.message("colorfulInstances.gotIt.title"))
      // withTimeout(5000) TODO: to discuss with designers: do we want autohide or do we want a button?
    }

    gotIt.showCondition = { true }

    if (WindowManagerEx.getInstanceEx().getFrameHelper(project)?.frame?.isFocused == true) {
      gotIt.show(component, GotItTooltip.BOTTOM_MIDDLE)
      recordGotItShown()
    }
  }

  /**
   * @return true if method painted something
   */
  fun paint(window: Window, parent: JComponent, g: Graphics2D): Boolean {
    if (!isActive() || !getPaintingType().isGradient()) {
      return false
    }

    val project = ProjectFrameHelper.getFrameHelper(window)?.project ?: return false

    g.color = parent.background
    val height = parent.height
    g.fillRect(0, 0, parent.width, height)

    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    val color = getGradientProjectColor(project)

    val length = Registry.intValue("ide.colorful.toolbar.gradient.length", 600)
    val x = parent.x.toFloat()
    val y = parent.y.toFloat()
    if (getPaintingType().isCircularGradient()) {
      val offset = 150f
      g.paint = RadialGradientPaint(x + offset, y + height / 2, length - offset, floatArrayOf(0.0f, 0.6f), arrayOf(color, parent.background))
    }
    else {
      g.paint = GradientPaint(x, y, color, length.toFloat(), y, parent.background)
    }
    g.fillRect(0, 0, length, height)

    return true
  }

  fun getToolbarBackground(project: Project?):Color? {
    if (project == null) {
      return null
    }
    return getBackgroundProjectColor(project)
  }

  fun setToolbarColor(background:Color, project: Project) {
    PropertiesComponent.getInstance(project).setValue(TOOLBAR_BACKGROUND_KEY, ColorUtil.toHex(background))
  }

  override fun dispose() {}
}

private class ProjectWindowCustomizerListener : ProjectActivity, UISettingsListener {
  init {
    if (ApplicationManager.getApplication().isHeadlessEnvironment) {
      throw ExtensionNotApplicableException.create()
    }
  }

  override suspend fun execute(project: Project) {
    serviceAsync<ProjectWindowCustomizerService>().enableIfNeeded()
  }

  override fun uiSettingsChanged(uiSettings: UISettings) {
    ProjectWindowCustomizerService.getInstance().update(uiSettings.differentiateProjects)
  }
}


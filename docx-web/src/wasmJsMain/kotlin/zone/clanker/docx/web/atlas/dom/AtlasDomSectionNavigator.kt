package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.AtlasReportSection
import zone.clanker.docx.web.atlas.navigateToSection
import zone.clanker.docx.web.atlas.observeSection

internal class AtlasDomSectionNavigator {
    private var attachedRoot: HTMLDivElement? = null
    private var controller: AtlasController? = null
    private var appliedNavigationRevision = 0
    private val scrollListener: (Event) -> Unit = { updateSectionFromScroll() }

    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
    ) {
        attach(root)
        this.controller = controller
        AtlasReportSection.entries.forEach { section ->
            val link = root.sectionLink(section)
            link.onclick = { event ->
                event.preventDefault()
                controller.navigateToSection(section)
                null
            }
        }
        if (appliedNavigationRevision != model.sectionNavigationRevision) {
            appliedNavigationRevision = model.sectionNavigationRevision
            navigate(root, model.requestedSection)
        }
        renderActiveSection(root, model.activeSection)
    }

    fun release(root: HTMLDivElement) {
        if (attachedRoot !== root) return
        root.removeEventListener("scroll", scrollListener)
        AtlasReportSection.entries.forEach { section -> root.sectionLink(section).onclick = null }
        attachedRoot = null
        controller = null
        appliedNavigationRevision = 0
    }

    private fun attach(root: HTMLDivElement) {
        if (attachedRoot === root) return
        attachedRoot?.let(::release)
        attachedRoot = root
        root.addEventListener("scroll", scrollListener)
    }

    private fun navigate(
        root: HTMLDivElement,
        section: AtlasReportSection,
    ) {
        val target = root.sectionElement(section)
        target.scrollIntoView()
        val heading = target.querySelector("h2") as? HTMLElement
        heading?.tabIndex = -1
        heading?.focus()
        renderActiveSection(root, section)
    }

    private fun updateSectionFromScroll() {
        val root = attachedRoot ?: return
        val activeController = controller ?: return
        val threshold = root.getBoundingClientRect().top + ACTIVE_SECTION_OFFSET_PX
        val visible =
            AtlasReportSection.entries.lastOrNull { section ->
                root.sectionElement(section).getBoundingClientRect().top <= threshold
            } ?: AtlasReportSection.ARCHITECTURE
        activeController.observeSection(visible)
        renderActiveSection(root, visible)
    }

    private fun renderActiveSection(
        root: HTMLDivElement,
        active: AtlasReportSection,
    ) {
        root.setAttribute("data-docx-active-section", active.elementId)
        AtlasReportSection.entries.forEach { section ->
            root.sectionLink(section).also { link ->
                link.classList.toggle("is-active", section == active)
                if (section == active) {
                    link.setAttribute("aria-current", "location")
                } else {
                    link.removeAttribute("aria-current")
                }
            }
        }
    }
}

private fun HTMLDivElement.sectionLink(section: AtlasReportSection): HTMLAnchorElement =
    requiredElement(".srcx-dashboard__nav a[href=\"#${section.elementId}\"]") as HTMLAnchorElement

private fun HTMLDivElement.sectionElement(section: AtlasReportSection): HTMLElement =
    requiredElement("#${section.elementId}") as HTMLElement

private const val ACTIVE_SECTION_OFFSET_PX = 96.0

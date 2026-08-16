package zone.clanker.docx.web.atlas.dom.controls

import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.report.model.ProjectGraphShard

internal fun AtlasDomSurfaceModel.loadedProjects(): List<ProjectGraphShard> =
    (listOfNotNull(project) + buildProjects).distinctBy(ProjectGraphShard::projectId)

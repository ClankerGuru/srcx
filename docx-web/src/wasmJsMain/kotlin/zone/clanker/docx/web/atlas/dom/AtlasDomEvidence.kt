package zone.clanker.docx.web.atlas.dom

import zone.clanker.report.model.ProjectGraphShard

internal fun AtlasDomSurfaceModel.evidenceProjects(): List<ProjectGraphShard> =
    (listOfNotNull(project, evidence.project) + buildProjects)
        .distinctBy(ProjectGraphShard::projectId)

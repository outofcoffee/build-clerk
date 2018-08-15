package com.gatehill.buildclerk.service.builder

import com.gatehill.buildclerk.api.model.BuildReport
import com.gatehill.buildclerk.api.service.BuildReportService
import com.gatehill.buildclerk.config.Settings
import com.gatehill.buildclerk.service.AnalysisService
import com.gatehill.buildclerk.service.PendingActionService
import kotlinx.coroutines.experimental.async
import org.apache.logging.log4j.LogManager
import javax.inject.Inject

/**
 * Records build events and triggers analysis.
 *
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
class BuildEventService @Inject constructor(
        private val buildReportService: BuildReportService,
        private val analysisService: AnalysisService,
        private val pendingActionService: PendingActionService
) {
    private val logger = LogManager.getLogger(BuildEventService::class.java)

    fun checkBuildReport(buildReport: BuildReport) {
        val branchName = buildReport.build.scm.branch

        Settings.EventFilter.branchName?.takeIf(String::isNotBlank)?.let { filterBranchName ->
            if (branchName != filterBranchName) {
                logger.info("Ignoring build $buildReport because branch name: $branchName does not match filter")
                return
            }
        }

        @Suppress("DeferredResultUnused")
        async {
            try {
                buildReportService.record(buildReport)
                val analysis = analysisService.analyseBuild(buildReport)
                if (analysis.isNotEmpty()) {
                    pendingActionService.enqueue(analysis.actionSet)
                }

            } catch (e: Exception) {
                logger.error("Error handling build report: $buildReport", e)
            }
        }
    }
}

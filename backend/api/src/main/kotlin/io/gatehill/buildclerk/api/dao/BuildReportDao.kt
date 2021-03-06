package io.gatehill.buildclerk.api.dao

import io.gatehill.buildclerk.api.Recorded
import io.gatehill.buildclerk.api.model.BuildReport
import io.gatehill.buildclerk.api.model.BuildStatus
import java.time.ZonedDateTime

/**
 * Stores build reports and provides access to build metadata.
 *
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
interface BuildReportDao : Recorded {
    fun save(report: BuildReport)
    fun hasEverSucceeded(commit: String): Boolean
    fun lastPassingCommitForBranch(branchName: String): BuildReport?
    fun countStatusForCommitOnBranch(commit: String, branchName: String, status: BuildStatus): Int
    fun countConsecutiveFailuresOnBranch(branchName: String): Int
    fun fetchLast(branchName: String? = null): BuildReport?

    /**
     * @return the status of a specific build on a branch
     */
    fun fetchBuildStatus(branchName: String, buildNumber: Int): BuildStatus

    /**
     * @return build reports sorted in ascending order by build number
     */
    fun list(branchName: String? = null): List<BuildReport>

    /**
     * @return build reports received between the given timestamps, sorted in ascending order by build number
     */
    fun fetchBetween(branchName: String? = null, start: ZonedDateTime, end: ZonedDateTime): List<BuildReport>

    /**
     * Find any build report for this branch with a build number higher than `buildNumber`.
     *
     * Note, there is no guarantee that the build
     * will be the next consecutive build, only that it has a higher build number.
     *
     * @return a build report, if one or more is found
     */
    fun findHigherBuild(branchName: String, buildNumber: Int): BuildReport?
}

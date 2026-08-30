package com.faction.clientportal.repository;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.util.LikeEscaper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Native-SQL implementation of {@link AssessmentRepositoryCustom}. Spring Data wires this in
 * by the {@code <Repository>Impl} naming convention. Filtering/sorting/pagination run in the
 * database (one page query + one count) — no {@code findAll()} + in-memory scan.
 *
 * <p>Each filter is a {@link Clause} pairing its SQL fragment with the binder for its params,
 * so the WHERE text and the parameter bindings are built from the same list and can't drift.
 */
public class AssessmentRepositoryImpl implements AssessmentRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    /** A WHERE fragment and the binder for whatever named parameters it references. */
    private record Clause(String sql, Consumer<Query> binder) {
        static Clause of(String sql, Consumer<Query> binder) {
            return new Clause(sql, binder);
        }
        /** A fragment with no parameters (e.g. the empty-scope short-circuit). */
        static Clause of(String sql) {
            return new Clause(sql, q -> {});
        }
    }

    @Override
    public Page<Assessment> searchAdvanced(AssessmentSearchCriteria c, Pageable pageable) {
        var clauses = buildClauses(c);

        var where = " WHERE a.deleted_at IS NULL "
                + clauses.stream().map(Clause::sql).collect(Collectors.joining(" "));

        var order = orderBy(pageable);
        var pageQuery = entityManager.createNativeQuery(
                "SELECT a.* FROM assessments a" + order.joins() + where + order.sql(), Assessment.class);
        clauses.forEach(clause -> clause.binder().accept(pageQuery));
        if (pageable.isPaged()) {
            pageQuery.setFirstResult((int) pageable.getOffset());
            pageQuery.setMaxResults(pageable.getPageSize());
        }
        @SuppressWarnings("unchecked")
        List<Assessment> content = pageQuery.getResultList();

        // Unpaged already fetched every match, so its size IS the total — skip the count query.
        long total;
        if (pageable.isPaged()) {
            var countQuery = entityManager.createNativeQuery("SELECT count(*) FROM assessments a" + where);
            clauses.forEach(clause -> clause.binder().accept(countQuery));
            total = ((Number) countQuery.getSingleResult()).longValue();
        } else {
            total = content.size();
        }

        return new PageImpl<>(content, pageable, total);
    }

    private List<Clause> buildClauses(AssessmentSearchCriteria c) {
        List<Clause> clauses = new ArrayList<>();

        if (c.search() != null && !c.search().isBlank()) {
            clauses.add(Clause.of("AND LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')) ESCAPE '!'",
                    q -> q.setParameter("search", LikeEscaper.escape(c.search()))));
        }
        if (c.applicationId() != null) {
            clauses.add(Clause.of("AND a.application_id = :appId", q -> q.setParameter("appId", c.applicationId())));
        }
        // Multi-app filter (UI); empty = no filter (unlike the owned scope, which matches nothing).
        if (c.applicationIds() != null && !c.applicationIds().isEmpty()) {
            clauses.add(Clause.of("AND a.application_id IN (:applicationIds)",
                    q -> q.setParameter("applicationIds", c.applicationIds())));
        }
        if (c.organizationId() != null) {
            clauses.add(Clause.of("AND a.organization_id = :orgId", q -> q.setParameter("orgId", c.organizationId())));
        }
        if (c.ownedAppIds() != null) {
            if (c.ownedAppIds().isEmpty()) {
                clauses.add(Clause.of("AND 1 = 0")); // owned scope with no apps → match nothing
            } else {
                clauses.add(Clause.of("AND a.application_id IN (:ownedAppIds)",
                        q -> q.setParameter("ownedAppIds", c.ownedAppIds())));
            }
        }
        if (c.assessmentTypeId() != null) {
            clauses.add(Clause.of("AND a.assessment_type_id = :typeId", q -> q.setParameter("typeId", c.assessmentTypeId())));
        }
        if (c.assessorId() != null) {
            clauses.add(Clause.of("""
                    AND (a.assessor_id = :assessorId
                         OR a.assessor_ids @> CAST(CONCAT('["', :assessorId, '"]') AS jsonb))""",
                    q -> q.setParameter("assessorId", c.assessorId())));
        }
        if (c.status() != null) {
            clauses.add(Clause.of("AND LOWER(a.status) = LOWER(:status)", q -> q.setParameter("status", c.status())));
        }
        // Multi-select status filter; values arrive lower-cased so the comparison is case-insensitive.
        if (c.statuses() != null && !c.statuses().isEmpty()) {
            clauses.add(Clause.of("AND LOWER(a.status) IN (:statuses)",
                    q -> q.setParameter("statuses", c.statuses())));
        }
        if (c.restrictAssessmentIds() != null) {
            if (c.restrictAssessmentIds().isEmpty()) {
                clauses.add(Clause.of("AND 1 = 0")); // filter matched no assessment at all
            } else {
                clauses.add(Clause.of("AND a.id IN (:restrictIds)",
                        q -> q.setParameter("restrictIds", c.restrictAssessmentIds())));
            }
        }
        // An active date filter excludes undated assessments — an assessment with no start/end date
        // falls in no explicit range. (Undated rows still appear when no date filter is applied.)
        if (c.startDateFrom() != null) {
            clauses.add(Clause.of("AND a.start_date >= :startFrom",
                    q -> q.setParameter("startFrom", c.startDateFrom())));
        }
        if (c.startDateTo() != null) {
            clauses.add(Clause.of("AND a.start_date <= :startTo",
                    q -> q.setParameter("startTo", c.startDateTo())));
        }
        if (c.endDateFrom() != null) {
            clauses.add(Clause.of("AND a.planned_end_date >= :endFrom",
                    q -> q.setParameter("endFrom", c.endDateFrom())));
        }
        if (c.endDateTo() != null) {
            clauses.add(Clause.of("AND a.planned_end_date <= :endTo",
                    q -> q.setParameter("endTo", c.endDateTo())));
        }
        if (c.pastDue()) {
            clauses.add(Clause.of("""
                    AND a.planned_end_date IS NOT NULL AND a.planned_end_date < :now
                    AND (a.status IS NULL OR a.status NOT IN (:completed))""",
                    q -> { q.setParameter("now", c.now()); q.setParameter("completed", c.completedStatuses()); }));
        }
        if (c.excludeCompleted()) {
            // A completed assessment stays in the queue for its reopen window, so the people who
            // can still reopen it can find it without switching the list to "show completed".
            clauses.add(Clause.of("""
                    AND (a.status IS NULL OR a.status NOT IN (:completed)
                         OR (a.completed_date IS NOT NULL AND a.completed_date > :reopenableSince))""",
                    q -> {
                        q.setParameter("completed", c.completedStatuses());
                        q.setParameter("reopenableSince", c.reopenableSince());
                    }));
        }
        if (c.assignedToMe() && c.currentUserId() != null) {
            clauses.add(Clause.of("""
                    AND (a.engagement_manager_id = :meId OR a.remediation_manager_id = :meId
                         OR a.assessor_id = :meId
                         OR a.assessor_ids @> CAST(CONCAT('["', :meId, '"]') AS jsonb))""",
                    q -> q.setParameter("meId", c.currentUserId())));
        }
        if (c.teamMemberIds() != null) {
            if (c.teamMemberIds().isEmpty()) {
                clauses.add(Clause.of("AND 1 = 0")); // team with no members → match nothing
            } else {
                clauses.add(Clause.of("""
                        AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(a.assessor_ids) tm
                                    WHERE tm IN (:teamMembers))""",
                        q -> q.setParameter("teamMembers", c.teamMemberIds())));
            }
        }
        // Mandatory read scope (not a user-clearable filter): the caller only ever sees
        // assessments they are an assessor on, or assessments belonging to one of their teams.
        if (c.scopeAssessorId() != null) {
            clauses.add(Clause.of("""
                    AND (a.assessor_id = :scopeAssessorId
                         OR a.assessor_ids @> CAST(CONCAT('["', :scopeAssessorId, '"]') AS jsonb))""",
                    q -> q.setParameter("scopeAssessorId", c.scopeAssessorId())));
        }
        if (c.scopeTeamIds() != null) {
            if (c.scopeTeamIds().isEmpty()) {
                clauses.add(Clause.of("AND 1 = 0")); // team scope, but the caller is in no team
            } else {
                clauses.add(Clause.of("AND a.team_id IN (:scopeTeamIds)",
                        q -> q.setParameter("scopeTeamIds", c.scopeTeamIds())));
            }
        }
        if (c.campaignId() != null) {
            clauses.add(Clause.of("AND a.campaign_id = :campaignId", q -> q.setParameter("campaignId", c.campaignId())));
        }
        if (c.severityOrdinals() != null && !c.severityOrdinals().isEmpty()) {
            var sql = new StringBuilder("""
                    AND EXISTS (SELECT 1 FROM vulnerabilities v WHERE v.assessment_id = a.id
                    AND v.deleted_at IS NULL AND v.opened_at IS NOT NULL
                    AND v.severity IN (:sevOrdinals)""");
            if (c.startDateFrom() != null) sql.append(" AND v.opened_at >= :sevFrom");
            if (c.startDateTo() != null) sql.append(" AND v.opened_at <= :sevTo");
            sql.append(")");
            clauses.add(Clause.of(sql.toString(), q -> {
                q.setParameter("sevOrdinals", c.severityOrdinals());
                if (c.startDateFrom() != null) q.setParameter("sevFrom", c.startDateFrom());
                if (c.startDateTo() != null) q.setParameter("sevTo", c.startDateTo());
            }));
        }
        return clauses;
    }

    /**
     * The ORDER BY clause plus whatever LEFT JOINs its column needs. The joins are emitted only
     * for the sort keys that reference a related table, so the common case still runs as a plain
     * single-table scan. They are safe to add to {@code SELECT a.*} because each is a many-to-one
     * on a unique id — no row fan-out — which is also why the count query can skip them entirely.
     */
    private record OrderSpec(String joins, String sql) {}

    private static final String JOIN_APP = " LEFT JOIN applications app ON app.id = a.application_id";
    private static final String JOIN_TYPE = " LEFT JOIN assessment_types at2 ON at2.id = a.assessment_type_id";
    private static final String JOIN_TEAM = " LEFT JOIN teams t ON t.id = a.team_id";
    private static final String JOIN_ORG = " LEFT JOIN organizations o ON o.id = a.organization_id";
    private static final String JOIN_CAMPAIGN = " LEFT JOIN campaigns c ON c.id = a.campaign_id";

    /**
     * Whitelisted ORDER BY — unknown/unsorted defaults to created_at DESC, NULLS LAST both
     * directions. Always ends with a.id as a unique tiebreaker so LIMIT/OFFSET paging is stable
     * across pages when the sort key ties (or is NULL) — otherwise tied rows can duplicate or skip.
     */
    private OrderSpec orderBy(Pageable pageable) {
        var order = pageable.getSort().isSorted() ? pageable.getSort().iterator().next() : null;
        if (order == null) {
            return new OrderSpec("", " ORDER BY a.created_at DESC NULLS LAST, a.id");
        }
        String joins = "";
        String column;
        // LOWER(...) on the text columns: the database collates byte-wise, so ordering a name raw
        // puts every capitalized value ahead of every lowercase one. Only text is wrapped —
        // Postgres has no lower() for the timestamp columns.
        switch (order.getProperty()) {
            case "startDate" -> column = "a.start_date";
            case "plannedEndDate" -> column = "a.planned_end_date";
            case "completedDate" -> column = "a.completed_date";
            case "assessmentDate" -> column = "a.assessment_date";
            case "name" -> column = "LOWER(a.name)";
            case "status" -> column = "LOWER(a.status)";
            case "applicationName" -> { column = "LOWER(app.name)"; joins = JOIN_APP; }
            case "appId" -> { column = "LOWER(app.app_id)"; joins = JOIN_APP; }
            case "assessmentTypeName" -> { column = "LOWER(at2.name)"; joins = JOIN_TYPE; }
            case "teamName" -> { column = "LOWER(t.name)"; joins = JOIN_TEAM; }
            case "organizationName" -> { column = "LOWER(o.name)"; joins = JOIN_ORG; }
            case "campaignName" -> { column = "LOWER(c.name)"; joins = JOIN_CAMPAIGN; }
            default -> column = "a.created_at";
        }
        return new OrderSpec(joins, " ORDER BY " + column
                + (order.getDirection() == Sort.Direction.ASC ? " ASC" : " DESC") + " NULLS LAST, a.id");
    }
}

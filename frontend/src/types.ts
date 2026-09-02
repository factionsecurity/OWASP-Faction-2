export interface User {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  loginOption: 'NATIVE' | 'SAML2' | 'OPENID';
  roleIds: string[];
  teamIds?: string[];
  roles?: Role[];
  isInternal: boolean;
  organizationId?: string;
  createdAt: string;
  lastLogin?: string;
  disabledAt?: string;
  deletedAt?: string;
  profileImageId?: string | null;
}

export interface Role {
  id: string;
  name: string;
  description: string;
  permissions: string[];
  externalRole?: boolean;
}

/** One @mention candidate: just what the picker renders. */
export interface MentionableUser {
  username: string;
  displayName: string;
}

export interface PermissionInfo {
  permission: string;
  description: string;
}

export interface ResourcePermissions {
  resource: string;
  displayName: string;
  description: string;
  permissions: PermissionInfo[];
}

export type ApiKeyType = 'USER' | 'SYSTEM';

// READ_WRITE / READ_ONLY apply to user keys (authorities derived live from the owner);
// CUSTOM applies to system keys (an explicit stored permission list).
export type ApiKeyScope = 'READ_WRITE' | 'READ_ONLY' | 'CUSTOM';

export interface ApiKey {
  id: string;
  name: string;
  keyType: ApiKeyType;
  scope: ApiKeyScope;
  hint: string;
  permissions: string[];
  createdAt?: string;
  lastUsedAt?: string;
}

export interface CreateApiKeyRequest {
  name: string;
  scope?: ApiKeyScope; // omit for the READ_WRITE default
}

// The plaintext `key` is returned exactly once, at creation, and is never retrievable again.
export interface CreateApiKeyResponse {
  key: string;
  apiKey: ApiKey;
}

export interface CreateRoleRequest {
  name: string;
  description: string;
  permissions: string[];
  externalRole?: boolean;
}

export interface UpdateRoleRequest {
  name: string;
  description: string;
  permissions: string[];
  externalRole?: boolean;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  success: boolean;
  token: string;
  tokenType: string;
  expiresIn: number;
  userId: string;
  username: string;
  authorities: string[];
  roles?: string[];
  /** Staff account rather than a customer-side one; gates internal-only controls in the UI. */
  isInternal?: boolean;
}

export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data?: T;
  error?: string;
  timestamp?: string;
}

export interface PagedApiResponse<T> extends ApiResponse<T> {
  pagination?: {
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    first: boolean;
    last: boolean;
    numberOfElements: number;
    empty: boolean;
  };
}

export interface CreateUserRequest {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  password: string;
  loginOption: 'NATIVE' | 'SAML2' | 'OPENID';
  roleIds: string[];
  teamIds?: string[];
  isInternal: boolean;
  organizationId?: string;
}

export interface UpdateUserRequest {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  loginOption: 'NATIVE' | 'SAML2' | 'OPENID';
  roleIds: string[];
  teamIds?: string[];
  isInternal: boolean;
  organizationId?: string;
}

export interface Team {
  id: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateTeamRequest {
  name: string;
  description: string;
}

export interface UpdateTeamRequest {
  name: string;
  description: string;
}

export interface Campaign {
  id: string;
  name: string;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCampaignRequest {
  name: string;
}

export interface UpdateCampaignRequest {
  name?: string;
  isDefault?: boolean;
}

export interface AssessmentType {
  id: string;
  name: string;
  description: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAssessmentTypeRequest {
  name: string;
  description: string;
  active: boolean;
}

export interface UpdateAssessmentTypeRequest {
  name: string;
  description: string;
  active: boolean;
}

export interface AssignedUser {
  userId: string;
  displayName: string;
  email: string;
  accessLevel: 'READ' | 'WRITE';
}

export interface UserApplicationAssignment {
  applicationId: string;
  applicationName: string;
  organizationId?: string;
  accessLevel: 'READ' | 'WRITE';
}

export interface AssignUserRequest {
  userId: string;
  accessLevel: 'READ' | 'WRITE';
}

/**
 * A division within an organization (business unit, subsidiary, region) that applications can be
 * attributed to. Attribution only — the owning organization, and therefore who can see the
 * application, is unchanged.
 */
export interface SubOrganization {
  id: string;
  organizationId: string;
  /** Set only by the cross-organization directory listing (`subOrganizationsApi.listAll`). */
  organizationName?: string;
  name: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
  /** Applications attributed to it; non-zero blocks deletion. */
  applicationCount: number;
}

export interface SubOrganizationRequest {
  name: string;
  description?: string;
}

export interface Organization {
  id: string;
  name: string;
  description: string;
  fieldDefinitions?: UserDefinedField[];
  fieldValues?: Record<string, string>;
  assignedUsers?: AssignedUser[];
}

export interface CreateOrganizationRequest {
  name: string;
  description: string;
  fieldValues?: Record<string, string>;
}

export interface UpdateOrganizationRequest {
  name: string;
  description: string;
  fieldValues?: Record<string, string>;
}

export interface EntityFieldConfig {
  id?: string;
  scope: FieldScope;
  fieldDefinitions: UserDefinedField[];
  lastUpdatedBy?: string;
  updatedAt?: string;
}

/** Outcome of a CSV application sync: what was written, and which rows were not. */
export interface ApplicationImportResult {
  processed: number;
  created: number;
  updated: number;
  failed: number;
  createdOrganizations: string[];
  /** Rendered as "Organization / Division". */
  createdSubOrganizations: string[];
  errors: { line: number; identifier?: string; message: string }[];
}

// Application Types
export type ApplicationStatus = 'PRODUCTION' | 'DEVELOPMENT' | 'STAGING' | 'TESTING' | 'DECOMMISSIONED' | 'PLANNED';

export type ConnectionType = 'DEPENDS_ON' | 'USES_API' | 'CONSUMES_DATA' | 'AUTHENTICATES_WITH' | 'SHARES_INFRASTRUCTURE' | 'INTEGRATES_WITH';

export interface ApplicationUrl {
  url: string;
  title: string;
}

export interface Stakeholder {
  name: string;
  email: string;
  role: string;
}

export interface AppOwner {
  fullName: string;
  email: string;
}

export interface ApplicationComment {
  id: string;
  authorId: string;
  authorName: string;
  content: string;
  createdAt: string;
  updatedAt: string;
  systemGenerated?: boolean;
}

export interface Application {
  /** Usernames following the discussion; everyone here is notified on a new comment. */
  subscribers?: string[];
  id: string;
  appId?: string;
  name: string;
  description?: string;
  urls?: ApplicationUrl[];
  stakeHolders?: Stakeholder[];
  technologies?: string[];
  appOwner?: AppOwner;
  status?: ApplicationStatus;
  organizationId?: string;
  /** Optional division within the organization; attribution only. */
  subOrganizationId?: string;
  region?: string;
  applicationType?: string;
  assessmentFrequency?: string;
  customFrequencyMonths?: number;
  lastAssessmentDate?: string;
  ownerName?: string;
  ownerEmail?: string;
  fieldDefinitions?: UserDefinedField[];
  fieldValues?: Record<string, string>;
  assignedUsers?: AssignedUser[];
  comments?: ApplicationComment[];
  createdBy?: string;
  lastUpdatedBy?: string;
  createdAt: string;
  updatedAt: string;
  openIssueCount?: number;
}

/** Pre-aggregated, SLA-aware vulnerability counts for the Vulnerability Trend panel. */
export interface VulnerabilityTrendSummary {
  allFindings: Record<string, number>;
  trackedOpen: Record<string, number>;
  pastDue: Record<string, number>;
  openOnTime: Record<string, number>;
  closed12mo: Record<string, number>;
  /** Opened, not closed, status ≠ 'Exception' (not SLA-gated) — disjoint from `exceptions`. */
  openFindings: Record<string, number>;
  /** Opened, not closed, status = 'Exception'. */
  exceptions: Record<string, number>;
  /** Closed findings carrying both an opened and a closed date — the denominator for MTTR. */
  closedWithDates: Record<string, number>;
  /** Total of those findings' open-to-close durations, in days. Mean = total / count; combine
   *  severities by summing both sides, never by averaging per-severity means. */
  daysToCloseTotal: Record<string, number>;
}

export interface CreateApplicationRequest {
  name: string;
  appId?: string;
  description?: string;
  urls?: ApplicationUrl[];
  stakeHolders?: Stakeholder[];
  technologies?: string[];
  appOwner?: AppOwner;
  status?: ApplicationStatus;
  organizationId?: string;
  /** Optional division within the organization; attribution only. */
  subOrganizationId?: string;
  applicationType?: string;
  assessmentFrequency?: string;
  customFrequencyMonths?: number;
  lastAssessmentDate?: string;
  fieldValues?: Record<string, string>;
}

export interface UpdateApplicationRequest {
  name: string;
  appId?: string;
  description?: string;
  urls?: ApplicationUrl[];
  stakeHolders?: Stakeholder[];
  technologies?: string[];
  appOwner?: AppOwner;
  status?: ApplicationStatus;
  organizationId?: string;
  /** Optional division within the organization; attribution only. */
  subOrganizationId?: string;
  region?: string;
  applicationType?: string;
  assessmentFrequency?: string;
  customFrequencyMonths?: number;
  lastAssessmentDate?: string;
  fieldValues?: Record<string, string>;
}

// Application Connection Types
export interface ApplicationConnection {
  id: string;
  sourceApplicationId: string;
  sourceApplicationName?: string;
  targetApplicationId: string;
  targetApplicationName?: string;
  type: ConnectionType;
  description?: string;
  critical?: boolean;
  dataSensitivity?: string;
  createdBy?: string;
  lastUpdatedBy?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateApplicationConnectionRequest {
  sourceApplicationId: string;
  targetApplicationId: string;
  type: ConnectionType;
  description?: string;
  critical?: boolean;
  dataSensitivity?: string;
}

export interface UpdateApplicationConnectionRequest {
  type: ConnectionType;
  description?: string;
  critical?: boolean;
  dataSensitivity?: string;
}

// Report Template & Assessment Types
export type FieldType = 'STRING' | 'RICH_TEXT' | 'DROPDOWN';
export type FieldScope = 'ASSESSMENT' | 'VULNERABILITY' | 'APPLICATION' | 'ORGANIZATION';

export interface UserDefinedField {
  id: string;
  variableName: string;
  displayName: string;
  helpText?: string;
  fieldType: FieldType;
  dropdownOptions?: string[];
  defaultValue?: string;
  required?: boolean;
  maxLength?: number;
  minLength?: number;
  displayOrder?: number;
  fieldScope?: FieldScope;
}

export interface ReportTemplate {
  id: string;
  name: string;
  description?: string;
  assessmentTypeId: string;
  css?: string;
  font?: string;
  templateFileId?: string;
  templateFileName?: string;
  templateFileSize?: number;
  templateFileContentType?: string;
  version: number;
  scoringType?: ScoringType;
  sections: string[];
  userDefinedFields: UserDefinedField[];
  active: boolean;
  createdBy?: string;
  lastUpdatedBy?: string;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string;
}

export interface ReportTemplateSummary {
  id: string;
  name: string;
  description?: string;
  assessmentTypeId: string;
  templateFileName?: string;
  templateFileSize?: number;
  version: number;
  fieldCount: number;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateReportTemplateRequest {
  name: string;
  description?: string;
  assessmentTypeId: string;
  css?: string;
  scoringType?: ScoringType;
  sections?: string[];
  userDefinedFields: UserDefinedField[];
}

export interface UpdateReportTemplateRequest {
  name?: string;
  description?: string;
  assessmentTypeId?: string;
  css?: string;
  font?: string;
  scoringType?: ScoringType;
  sections?: string[];
  userDefinedFields?: UserDefinedField[];
  active?: boolean;
}

export type AssessmentStatus = 'DRAFT' | 'IN_PROGRESS' | 'ON_HOLD' | 'PENDING_REVIEW' | 'COMPLETED' | 'APPROVED' | 'ARCHIVED';

export interface VulnerabilitySla {
  severity: string;
  pastDueDays: number;
  warningDays: number;
}

/**
 * One remediation stage a fix moves through (e.g. Development → Staging → Production).
 * Ordered; the last configured stage is terminal — completing it closes the vulnerability.
 * The id is stable across renames (completions are keyed by it).
 */
export interface RemediationStage {
  id: string;
  name: string;
}

/** One row of a vulnerability's composed stage view (every configured stage, in order). */
export interface VulnerabilityStageCompletion {
  stageId: string;
  stageName: string;
  terminal: boolean;
  completedAt?: string;
  completedBy?: string;
}

export interface AssessmentWorkflowConfig {
  id?: string;
  statuses: string[];
  newAssessmentStatus: string;
  inProgressStatus: string;
  completedStatus: string;
  statusColors?: Record<string, string>;
  vulnerabilitySlas?: VulnerabilitySla[];
  vulnerabilityStatuses?: string[];
  /** Ordered remediation stages; the last one is terminal (closes the vulnerability). */
  remediationStages?: RemediationStage[];
  allowSelfPeerReview?: boolean;
}

export type AssessmentPeerReviewStatus = 'IN_PROGRESS' | 'IN_PEER_REVIEW' | 'NEEDS_ACCEPTANCE' | 'COMPLETE';

export type PeerReviewStatus = 'PENDING' | 'IN_REVIEW' | 'COMPLETED';

export interface PeerReviewVulnerability {
  vulnerabilityId: string;
  name: string;
  severity: VulnerabilitySeverity;
  order: number;
  likelihood?: string;
  impact?: string;
  cvssScore?: number;
  cvssString?: string;
  // Snapshot
  description?: string;
  recommendation?: string;
  details?: string;
  fieldValues?: Record<string, string>;
  // Reviewer edits
  revisedDescription?: string;
  revisedRecommendation?: string;
  revisedDetails?: string;
  revisedFieldValues?: Record<string, string>;
  // Reviewer notes
  descriptionNotes?: string;
  recommendationNotes?: string;
  detailsNotes?: string;
  fieldNotes?: Record<string, string>;
}

// Reserved fieldNotes key for the section-level reviewer note covering all
// string/dropdown assessment variables (they share one note, not one each).
export const ASSESSMENT_VARIABLES_NOTES_KEY = '__assessment_variables__';

export interface PeerReview {
  id: string;
  assessmentId: string;
  assessmentName?: string;
  snapshotFieldValues: Record<string, string>;
  revisedFieldValues: Record<string, string>;
  fieldNotes: Record<string, string>;
  vulnerabilities: PeerReviewVulnerability[];
  submittedByUserId?: string;
  submittedByName?: string;
  reviewedByUserId?: string;
  reviewedByName?: string;
  /** Everyone who has worked this review, and their names in the same order. */
  reviewerUserIds?: string[];
  reviewerNames?: string[];
  status: PeerReviewStatus;
  createdAt: string;
  completedAt?: string;
}

export interface UpdatePeerReviewRequest {
  revisedFieldValues?: Record<string, string>;
  fieldNotes?: Record<string, string>;
  vulnerabilities?: PeerReviewVulnerability[];
}

export interface AcceptPeerReviewRequest {
  acceptedAssessmentFieldIds: string[];
  acceptedVulnerabilityChanges: Record<string, string[]>;
}

export interface EngagementUrl {
  url: string;
  description: string;
}

export interface Stakeholder {
  name: string;
  email: string;
  role: string;
}

export interface AssessmentFile {
  id: string;
  fileName: string;
  contentType: string;
  fileSize: number;
  uploadedBy: string;
  uploadedByName: string;
  uploadedAt: string;
  downloadUrl?: string; // populated on demand
}

export interface VulnerabilitySummary {
  critical: number;
  high: number;
  medium: number;
  low: number;
  informational: number;
}

export interface Assessment {
  id: string;
  name: string;
  applicationId: string;
  appId?: string; // Human-readable application id, for display
  applicationName?: string; // For display in tables
  assessmentTypeId: string;
  assessmentTypeName?: string; // For display in tables
  organizationId: string;
  campaignId?: string;
  campaignName?: string; // For display in tables
  teamId?: string;
  teamName?: string; // For display in tables
  reportTemplateId: string;
  reportTemplateVersion: number;
  templateName: string;
  templateCss?: string;
  templateFileId?: string;
  scoringType?: ScoringType;
  sections?: string[];
  fieldDefinitions: UserDefinedField[];
  fieldValues: Record<string, string>;
  status: string;
  peerReviewStatus?: AssessmentPeerReviewStatus;
  activePeerReviewId?: string;
  assessorId?: string; // Legacy field
  assessorIds?: string[];
  assessorNames?: string[]; // For display in tables
  assessorEmails?: string[];
  engagementManagerId?: string;
  engagementManagerName?: string;
  engagementManagerEmail?: string;
  remediationManagerId?: string;
  assessmentDate?: string; // Legacy field
  startDate?: string;
  plannedEndDate?: string;
  peerReviewedAt?: string;
  completedDate?: string;
  scope?: string;
  engagementUrls?: EngagementUrl[];
  stakeholders?: Stakeholder[];
  isPastDue?: boolean; // Computed field
  attachments?: AssessmentFile[];
  vulnerabilitySummary?: VulnerabilitySummary; // Vulnerability counts
  generatedReportFileId?: string;
  reportGeneratedAt?: string;
  createdBy?: string;
  lastUpdatedBy?: string;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string;
}

export interface CreateAssessmentRequest {
  name: string;
  applicationId?: string;
  appId?: string;
  applicationName?: string;
  assessmentTypeId: string;
  campaignId?: string;
  reportTemplateId: string;
  teamId?: string;
  assessorId?: string; // Legacy field
  assessorIds?: string[];
  engagementManagerId?: string;
  remediationManagerId?: string;
  startDate?: string;
  plannedEndDate?: string;
  scope?: string;
  engagementUrls?: EngagementUrl[];
  stakeholders?: Stakeholder[];
  initialFieldValues?: Record<string, string>;
}

/** A person who can be assigned to an assessment (the assessor picker's option shape). */
export interface AssignableUser {
  id: string;
  displayName: string;
  email?: string;
}

export interface UpdateAssessmentRequest {
  name?: string;
  applicationId?: string;
  assessmentTypeId?: string;
  campaignId?: string;
  reportTemplateId?: string;
  teamId?: string;
  fieldValues?: Record<string, string>;
  status?: string;
  assessorId?: string; // Legacy field
  assessorIds?: string[];
  engagementManagerId?: string;
  remediationManagerId?: string;
  startDate?: string;
  plannedEndDate?: string;
  scope?: string;
  engagementUrls?: EngagementUrl[];
  stakeholders?: Stakeholder[];
}

export interface ManagerDashboardPeriodCounts {
  week: number;
  month: number;
  year: number;
  allTime: number;
}

export interface ManagerDashboardSummary {
  completedAssessments: ManagerDashboardPeriodCounts;
  vulnerabilities: ManagerDashboardPeriodCounts;
}

export interface ManagerDashboardAssessorCompletedCount {
  assessorId: string;
  assessorName: string;
  count: number;
}

export interface ManagerDashboardStats {
  severityBreakdown: Record<string, number>;
  statusBreakdown: Record<string, number>;
  completedByAssessor: ManagerDashboardAssessorCompletedCount[];
  totalVulnerabilities: number;
  totalAssessments: number;
  totalCompletedAssessments: number;
}

export interface ManagerDashboardAssessment {
  assessment: Assessment;
  teamNames: string[];
}

export interface ManagerDashboardVulnerability {
  id: string;
  name: string;
  severity: VulnerabilitySeverity;
  cvssScore?: number;
  categoryName?: string;
  openedAt?: string;
  closedAt?: string;
  status?: string;
  trackingId?: string;
  assessmentId: string;
  assessmentName?: string;
  appId?: string;
  applicationName?: string;
}

export interface ManagerDashboardVulnerabilityDetail {
  vulnerability: Vulnerability;
  assessment: Assessment;
}

export interface ManagerDashboardFilters {
  search?: string;
  applicationId?: string;
  assessmentTypeId?: string;
  assessorId?: string;
  status?: string;
  teamId?: string;
  campaignId?: string;
  severities?: string[];
  startDateFrom?: string;
  startDateTo?: string;
  endDateFrom?: string;
  endDateTo?: string;
  showCompleted?: boolean;
}

export interface AssessmentMetrics {
  totalCount: number;
  draftCount: number;
  inProgressCount: number;
  onHoldCount: number;
  pendingReviewCount: number;
  completedCount: number;
  approvedCount: number;
  archivedCount: number;
  pastDueCount: number;
  statusCounts?: Record<string, number>;
}

export interface ConflictCheckRequest {
  assessmentId?: string | null;
  assessorIds: string[];
  startDate: string;
  endDate: string;
}

export interface FieldLockInfo {
  fieldId: string;
  username: string;
  displayName: string;
}

export interface VulnerabilityCategory {
  id: string;
  name: string;
  description?: string;
  createdBy: string;
  lastUpdatedBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateVulnerabilityCategoryRequest {
  name: string;
  description?: string;
}

export interface UpdateVulnerabilityCategoryRequest {
  name?: string;
  description?: string;
}

export type VulnerabilitySeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFORMATIONAL';

export type ScoringType = 'NATIVE' | 'CVSS_31' | 'CVSS_40';

export interface DefaultVulnerability {
  id: string;
  name: string;
  severity: VulnerabilitySeverity;
  likelihood?: string;
  impact?: string;
  cvssScore31?: number;
  cvssString31?: string;
  cvssScore40?: number;
  cvssString40?: string;
  description?: string;
  recommendation?: string;
  order: number;
  vulnerabilityCategoryId?: string;
  vulnerabilityCategory?: VulnerabilityCategory;
  fieldValues?: Record<string, string>;
  archived: boolean;
  createdBy: string;
  lastUpdatedBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateDefaultVulnerabilityRequest {
  name: string;
  severity: VulnerabilitySeverity;
  likelihood?: string;
  impact?: string;
  cvssScore31?: number;
  cvssString31?: string;
  cvssScore40?: number;
  cvssString40?: string;
  description?: string;
  recommendation?: string;
  vulnerabilityCategoryId?: string;
  fieldValues?: Record<string, string>;
  order?: number;
}

export interface UpdateDefaultVulnerabilityRequest {
  name?: string;
  severity?: VulnerabilitySeverity;
  likelihood?: string;
  impact?: string;
  cvssScore31?: number;
  cvssString31?: string;
  cvssScore40?: number;
  cvssString40?: string;
  description?: string;
  recommendation?: string;
  vulnerabilityCategoryId?: string;
  fieldValues?: Record<string, string>;
  order?: number;
}

export interface DefaultVulnerabilityImportResult {
  importedCount: number;
  skippedCount: number;
  categoriesCreatedCount: number;
  errors: string[];
}

export interface VulnerabilityComment {
  id: string;
  authorId: string;
  authorName: string;
  content: string;
  createdAt: string;
  updatedAt: string;
  systemGenerated?: boolean;
}

/**
 * Who a finding belongs to and who to talk to about it. Resolved server-side on a single
 * vulnerability read — the detail panel opens from the global list, where no application or
 * organization has been loaded.
 */
export interface VulnerabilityContext {
  applicationId?: string;
  /** Human-readable application id (e.g. APP-0042), not the primary key. */
  appId?: string;
  applicationName?: string;
  organizationId?: string;
  organizationName?: string;
  subOrganizationId?: string;
  subOrganizationName?: string;
  appOwnerName?: string;
  appOwnerEmail?: string;
  /** Assessment stakeholders followed by the application's, deduplicated by email. */
  stakeholders?: Stakeholder[];
}

export interface Vulnerability {
  /** Usernames following the discussion; everyone here is notified on a new comment. */
  subscribers?: string[];
  id: string;
  name: string;
  severity: VulnerabilitySeverity;
  section?: string;
  likelihood?: string;
  impact?: string;
  cvssScore?: number;
  cvssString?: string;
  assetLocation?: string;
  description?: string;
  recommendation?: string;
  details?: string;
  trackingId?: string;
  order: number;
  status?: string;
  openedAt?: string;
  closedAt?: string;
  /** User id of the person accountable for the fix; only internal users may change it. */
  remediationOwnerId?: string;
  /** Display name for remediationOwnerId, resolved server-side. */
  remediationOwnerName?: string;
  /** Application / organization / owner / stakeholder context; single-vulnerability reads only. */
  context?: VulnerabilityContext;
  fieldDefinitions: UserDefinedField[];
  fieldValues: Record<string, string>;
  comments?: VulnerabilityComment[];
  exceptionNumber?: string;
  exceptionStartDate?: string;
  exceptionApproval?: string;
  exceptionState?: string;
  exceptionExpiryDate?: string;
  exceptionJustification?: string;
  exceptionFiles?: AssessmentFile[];
  assessmentId: string;
  vulnerabilityCategoryId?: string;
  vulnerabilityCategory?: VulnerabilityCategory;
  createdBy: string;
  lastUpdatedBy: string;
  createdAt: string;
  updatedAt: string;
}

/** A row of the global vulnerabilities list (GET /vulnerabilities) — a subset of the vuln fields
 *  plus the joined application/assessment/organization display names, enriched server-side. */
export interface VulnerabilityListItem {
  id: string;
  name: string;
  severity: VulnerabilitySeverity;
  status?: string;
  assetLocation?: string;
  cvssScore?: number;
  openedAt?: string;
  closedAt?: string;
  exceptionNumber?: string;
  exceptionState?: string;
  exceptionApproval?: string;
  assessmentId: string;
  applicationId?: string;
  organizationId?: string;
  assessmentName?: string;
  applicationName?: string;
  organizationName?: string;
  /** Remediation stage completions: stage id → completedAt. The terminal stage is `closedAt`. */
  stageCompletions?: Record<string, string>;
}

export interface CreateVulnerabilityRequest {
  name: string;
  severity: VulnerabilitySeverity;
  likelihood?: string;
  impact?: string;
  cvssScore?: number;
  cvssString?: string;
  assetLocation?: string;
  description?: string;
  recommendation?: string;
  details?: string;
  vulnerabilityCategoryId?: string;
  fieldValues?: Record<string, string>;
  section?: string;
}

export interface UpdateVulnerabilityRequest {
  name?: string;
  severity?: VulnerabilitySeverity;
  likelihood?: string;
  impact?: string;
  cvssScore?: number;
  cvssString?: string;
  assetLocation?: string;
  trackingId?: string;
  description?: string;
  recommendation?: string;
  details?: string;
  vulnerabilityCategoryId?: string;
  fieldValues?: Record<string, string>;
  openedAt?: string;
  section?: string;
}

export interface UpdateVulnerabilityExceptionRequest {
  exceptionNumber?: string;
  exceptionStartDate?: string;
  exceptionApproval?: string;
  exceptionState?: string;
  exceptionExpiryDate?: string;
  exceptionJustification?: string;
}

// Checklist Types
export interface ChecklistTemplateQuestion {
  id: string;
  text: string;
  order: number;
}

export interface ChecklistTemplate {
  id: string;
  name: string;
  assessmentTypeId: string;
  questions: ChecklistTemplateQuestion[];
  active: boolean;
  preventClosure: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateChecklistTemplateRequest {
  name: string;
  assessmentTypeId: string;
  questions: ChecklistTemplateQuestion[];
  preventClosure: boolean;
}

export interface UpdateChecklistTemplateRequest {
  name?: string;
  assessmentTypeId?: string;
  questions?: ChecklistTemplateQuestion[];
  active?: boolean;
  preventClosure?: boolean;
}

export type ChecklistResult = 'PASS' | 'FAIL' | 'NA';

export interface ChecklistResponse {
  questionId: string;
  questionText: string;
  result: ChecklistResult | null;
  comment: string;
  order: number;
}

export interface AssessmentChecklist {
  id: string;
  assessmentId: string;
  templateId: string;
  templateName: string;
  responses: ChecklistResponse[];
  createdAt: string;
  updatedAt: string;
}

export interface AddAssessmentChecklistRequest {
  templateId: string;
}

export interface UpdateAssessmentChecklistRequest {
  responses: ChecklistResponse[];
}

// Survey Types
export type SurveyFieldType = 'TEXTAREA' | 'DROPDOWN' | 'YES_NO';
export type SurveyStatus = 'INCOMPLETE' | 'COMPLETE';

export interface SurveyTemplateQuestion {
  id: string;
  text: string;
  fieldType: SurveyFieldType;
  dropdownOptions?: string[];
  order: number;
}

export interface SurveyTemplate {
  id: string;
  name: string;
  questions: SurveyTemplateQuestion[];
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSurveyTemplateRequest {
  name: string;
  questions: SurveyTemplateQuestion[];
}

export interface UpdateSurveyTemplateRequest {
  name?: string;
  questions?: SurveyTemplateQuestion[];
  active?: boolean;
}

export interface SurveyResponse {
  questionId: string;
  questionText: string;
  fieldType: SurveyFieldType;
  dropdownOptions?: string[];
  answer: string | null;
  order: number;
}

export interface AssessmentSurvey {
  id: string;
  assessmentId: string;
  templateId: string;
  templateName: string;
  status: SurveyStatus;
  responses: SurveyResponse[];
  completedBy: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AddAssessmentSurveyRequest {
  templateId: string;
}

export interface UpdateAssessmentSurveyRequest {
  responses?: SurveyResponse[];
  complete?: boolean;
}

export interface SsoSaml2Config {
  enabled: boolean;
  idpMetadataUrl?: string;
  spEntityId?: string;
  privateKeyPem?: string; // "••••••••" if set, null if not
  certificatePem?: string;
  emailAttribute?: string;
  buttonLabel?: string;
  birthrightAccess?: boolean;
  allowIdpInitiated?: boolean;
  acsUrl?: string; // read-only, computed by the backend
  // Microsoft Graph user lookup (typeahead) credentials
  graphTenantId?: string;
  graphClientId?: string;
  graphClientSecret?: string; // "••••••••" if set, null if not
}

export interface AzureDirectoryUser {
  displayName?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
}

export interface SsoOidcConfig {
  enabled: boolean;
  clientId?: string;
  clientSecret?: string; // "••••••••" if set, null if not
  issuerUri?: string;
  emailClaim?: string;
  buttonLabel?: string;
}

export interface SsoConfig {
  saml2: SsoSaml2Config;
  oidc: SsoOidcConfig;
}

export interface SsoStatus {
  saml2Enabled: boolean;
  saml2ButtonLabel: string;
  oidcEnabled: boolean;
  oidcButtonLabel: string;
}

// Notebook Types
export interface NotebookAttachment {
  id: string;
  fileName: string;
  contentType: string;
  fileSize: number;
  uploadedById: string;
  uploadedByName: string;
  uploadedAt: string;
  downloadUrl?: string;
}

export interface ModificationRecord {
  userId: string;
  userName: string;
  modifiedAt: string;
}

export interface NotebookNode {
  id: string;
  applicationId: string;
  assessmentId?: string;
  parentId?: string;
  title: string;
  content: string;
  orderIndex: number;
  depth: number;
  attachments: NotebookAttachment[];
  createdAt: string;
  createdById: string;
  createdByName: string;
  lastModifiedAt: string;
  modifiedBy: ModificationRecord[];
  children?: NotebookNode[];
  hasChildren: boolean;
}

export interface CreateNotebookNodeRequest {
  title: string;
  content?: string;
  parentId?: string;
  orderIndex?: number;
}

export interface UpdateNotebookNodeRequest {
  title?: string;
  content?: string;
  orderIndex?: number;
}

export interface MoveNotebookNodeRequest {
  newParentId?: string;
  newOrderIndex: number;
}

export interface NotebookSearchResult {
  node: NotebookNode;
  breadcrumb: string[];
  assessmentName?: string;
}

export type RetestStatus = 'REQUESTED' | 'SCHEDULED' | 'IN_PROGRESS' | 'PASSED' | 'FAILED' | 'CANCELLED';

export interface Retest {
  id: string;
  vulnerabilityId: string;
  vulnerabilityName?: string;
  vulnerabilitySeverity?: string;
  assessmentId: string;
  assessmentName?: string;
  applicationId?: string;
  applicationName?: string;
  scheduledStartDate: string;
  scheduledEndDate: string;
  /** When it was verified complete; written only at completion. */
  closedDate?: string;
  /** Who verified it, stamped at completion (not the last editor). */
  completedBy?: string;
  completedByName?: string;
  assignedAssessorIds: string[];
  assignedAssessorNames?: string[];
  status: RetestStatus;
  result?: 'PASS' | 'FAIL';
  comment?: string;
  scope?: string;
  createdBy?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRetestRequest {
  vulnerabilityId: string;
  /** Omit both dates to create a retest REQUEST (app owners) that staff schedule later. */
  scheduledStartDate?: string;
  scheduledEndDate?: string;
  assignedAssessorIds?: string[];
  scope?: string;
  comment?: string;
}

export interface UpdateRetestRequest {
  scheduledStartDate?: string;
  scheduledEndDate?: string;
  assignedAssessorIds?: string[];
  scope?: string;
  comment?: string;
  status?: RetestStatus;
  /**
   * Revised ratings for the underlying vulnerability; omit to leave unchanged. Sent with the retest
   * rather than through the vulnerability API, which refuses to modify a finalized assessment —
   * and a retest always runs on one.
   */
  severity?: string;
  likelihood?: string;
  impact?: string;
}

/**
 * How far a passing retest closes the finding: 'RETEST_ONLY' leaves the vulnerability untouched
 * (it stays in the remediation queue), or a configured remediation stage id — a non-terminal
 * stage records a completion and leaves it open; the terminal (last configured) stage closes the
 * vulnerability. Ignored on a failing retest.
 */
export type RetestClosure = string;

export interface CompleteRetestRequest {
  result: 'PASS' | 'FAIL';
  comment?: string;
  closure?: RetestClosure;
  /** Revised ratings for the underlying vulnerability; omit to leave unchanged. */
  severity?: string;
  likelihood?: string;
  impact?: string;
}

/**
 * One row of the interleaved remediation queue (server-computed): a vulnerability at/past its SLA
 * warning threshold or an open retest, flattened to a common shape with joined names. Type-specific
 * fields: retestStatus/startDate/endDate on retest rows; lastRetestStatus on vuln rows.
 * vulnerabilityStatus is the underlying vulnerability's status, set on both row types.
 */
export interface RemediationQueueRow {
  key: string;
  id: string; // row entity's own id: retest id on retest rows, vuln id on vuln rows
  type: 'VULNERABILITY' | 'RETEST';
  vulnerabilityId: string;
  vulnerabilityName?: string; // null on a retest row whose vuln is gone; UI falls back to the id
  severity?: string;
  assessmentId?: string;
  applicationId?: string;
  applicationName?: string;
  organizationId?: string;
  organizationName?: string;
  dueDate?: string;   // SLA deadline (vuln) / scheduled end date (retest); ISO date
  startDate?: string; // retest only
  endDate?: string;   // retest only
  urgent: boolean;
  warning: boolean;
  vulnerabilityStatus?: string; // the underlying vulnerability's workflow status (both row types)
  retestStatus?: RetestStatus;
  lastRetestStatus?: 'PASSED' | 'FAILED';
}

// ── Email Configuration ───────────────────────────────────────────────────────

export interface EmailConfig {
  enabled: boolean;
  provider: string;
  host?: string;
  port: number;
  username?: string;
  password?: string; // null when not set, masked (••••••••) when stored
  fromName?: string;
  fromEmail?: string;
  security: string;
  authEnabled: boolean;
  logoBase64?: string;
  logoMimeType?: string;
}

export interface UpdateEmailConfigRequest {
  enabled?: boolean;
  provider?: string;
  host?: string;
  port?: number;
  username?: string;
  password?: string;
  fromName?: string;
  fromEmail?: string;
  security?: string;
  authEnabled?: boolean;
  logoBase64?: string;
  logoMimeType?: string;
}

// ── Notification Preferences ──────────────────────────────────────────────────

export type NotificationCategory =
  | 'MENTION'
  | 'ASSESSMENT_ASSIGNED'
  | 'RETEST_ASSIGNED'
  | 'OTHER';

export interface NotificationPreference {
  category: NotificationCategory;
  /** Human-readable name, supplied by the backend so the UI needs no lookup table. */
  label: string;
  description: string;
  inAppEnabled: boolean;
  emailEnabled: boolean;
}

export interface UpdateNotificationPreferencesRequest {
  preferences: Array<{
    category: NotificationCategory;
    /** Omitted means "leave this channel unchanged". */
    inAppEnabled?: boolean;
    emailEnabled?: boolean;
  }>;
}

// ── White-label branding ──────────────────────────────────────────────────────

/** The single-image slots. Sign-in backgrounds are a collection, not a slot. */
export type BrandingAssetSlot =
  | 'LOGIN_LOGO'
  | 'MENU_LOGO_LARGE'
  | 'MENU_LOGO_SMALL'
  | 'FAVICON';

/**
 * Asset ids for each slot. Null means the shipped Faction default is in use — which is
 * what keeps an instance that has configured nothing looking exactly as it does today.
 */
export interface UpdateBrandingSizesRequest {
  /** Omitted means "leave unchanged"; out-of-range values are clamped server-side. */
  loginLogoHeight?: number;
  menuLogoLargeHeight?: number;
  menuLogoSmallHeight?: number;
}

export interface Branding {
  loginLogoId?: string | null;
  menuLogoLargeId?: string | null;
  menuLogoSmallId?: string | null;
  faviconId?: string | null;
  /** Rendered heights in CSS pixels, already resolved to the slot default when unset. */
  loginLogoHeight: number;
  menuLogoLargeHeight: number;
  menuLogoSmallHeight: number;
  /** Up to four; the sign-in page picks one at random per load. */
  loginBackgroundIds: string[];
  remainingBackgroundSlots: number;
}

// ── Email Notification Settings (admin) ───────────────────────────────────────

/**
 * Who an event's email goes to. These are audiences recorded on an application or an
 * assessment — a stakeholder usually has no login — which is why this is an admin
 * setting rather than a per-user preference like NotificationPreference above.
 */
export type EmailNotificationAudience =
  | 'ASSESSORS'
  | 'STAKEHOLDERS'
  | 'APP_OWNER'
  | 'MENTIONED_USERS'
  | 'ORG_USERS';

export interface EmailNotificationEvent {
  /** Settings key, e.g. `ASSESSMENT_CREATED` or `VULNERABILITY_CLOSED:staging`. */
  key: string;
  /** The underlying event name, without any remediation-stage suffix. */
  event: string;
  label: string;
  description: string;
  /** Only these switches are offered for this event. */
  audiences: EmailNotificationAudience[];
  notifyAssessors: boolean;
  notifyStakeholders: boolean;
  notifyAppOwner: boolean;
  includeMentionedUsers: boolean;
  notifyOrgUsers: boolean;
  customMessage?: string | null;
  perStage: boolean;
  stageId?: string | null;
}

export interface EmailNotificationConfig {
  enabled: boolean;
  /** False means nothing will send whatever is switched on here. */
  smtpConfigured: boolean;
  pastDueRepeatCount: number;
  pastDueRepeatIntervalDays: number;
  events: EmailNotificationEvent[];
}

export interface UpdateEmailNotificationConfigRequest {
  enabled?: boolean;
  pastDueRepeatCount?: number;
  pastDueRepeatIntervalDays?: number;
  events?: Array<{
    key: string;
    /** Omitted means "leave this switch unchanged". */
    notifyAssessors?: boolean;
    notifyStakeholders?: boolean;
    notifyAppOwner?: boolean;
    includeMentionedUsers?: boolean;
    notifyOrgUsers?: boolean;
    /** Empty string clears the wording; omitted leaves it unchanged. */
    customMessage?: string;
  }>;
}

// ── Inbound (IMAP) Email Configuration ────────────────────────────────────────

export interface InboundEmailConfig {
  enabled: boolean;
  replyAddress?: string;
  provider: string;
  host?: string;
  port: number;
  username?: string;
  password?: string; // null when not set, masked (••••••••) when stored
  security: string;
  folder: string;
  processedFolder?: string;
  pollIntervalSeconds: number;
  maxMessageBytes: number;
  lastPolledAt?: string;
  lastPollError?: string;
  /** True when a reply actually has somewhere to land — drives the Reply-To on mention emails. */
  replyEnabled: boolean;
}

export interface UpdateInboundEmailConfigRequest {
  enabled?: boolean;
  replyAddress?: string;
  provider?: string;
  host?: string;
  port?: number;
  username?: string;
  password?: string;
  security?: string;
  folder?: string;
  processedFolder?: string;
  pollIntervalSeconds?: number;
  maxMessageBytes?: number;
}

export interface TestEmailRequest {
  to: string;
}

export interface TestEmailResponse {
  success: boolean;
  message: string;
  details?: string;
}

export type AiProviderType =
  | 'OPENAI'
  | 'ANTHROPIC'
  | 'OPENROUTER'
  | 'AZURE_OPENAI'
  | 'OPENAI_COMPATIBLE';

export interface AiProviderConfig {
  id: string;
  name: string;
  providerType: AiProviderType;
  baseUrl?: string;
  apiKey?: string; // null when not set, masked (••••••••) when stored
  apiVersion?: string;
  models: string[];
  defaultModel?: string;
  enabled: boolean;
}

export interface SaveAiProviderConfigRequest {
  name?: string;
  providerType?: AiProviderType;
  baseUrl?: string;
  apiKey?: string;
  apiVersion?: string;
  models?: string[];
  defaultModel?: string;
  enabled?: boolean;
}

export interface TestAiProviderRequest {
  id?: string;
  providerType?: AiProviderType;
  baseUrl?: string;
  apiKey?: string;
  apiVersion?: string;
}

export interface AiModelInfo {
  id: string;
  name: string;
}

export interface TestAiProviderResponse {
  success: boolean;
  message: string;
  details?: string;
  models?: AiModelInfo[];
}

export type AiPromptScope = 'ASSESSMENT' | 'VULNERABILITY';

export interface AiPromptTemplate {
  id: string;
  name: string;
  description?: string;
  scope: AiPromptScope;
  prompt: string;
  providerId?: string;
  model?: string;
  allowWebAccess: boolean;
  enabled: boolean;
}

export interface SaveAiPromptTemplateRequest {
  name?: string;
  description?: string;
  scope?: AiPromptScope;
  prompt?: string;
  providerId?: string;
  model?: string;
  allowWebAccess?: boolean;
  enabled?: boolean;
}

export type ContentTemplateScope = 'ASSESSMENT' | 'VULNERABILITY';

/** How a selected template combines with whatever the editor already holds. */
export type ContentTemplateInsertMode = 'OVERWRITE' | 'PREPEND' | 'APPEND';

export interface ContentTemplate {
  id: string;
  name: string;
  description?: string;
  scope: ContentTemplateScope;
  /** HTML body — the same shape RichTextEditor stores */
  content: string;
  enabled: boolean;
  createdBy?: string;
}

export interface SaveContentTemplateRequest {
  name?: string;
  description?: string;
  scope?: ContentTemplateScope;
  content?: string;
  enabled?: boolean;
}

export type WebSearchProviderType = 'BRAVE' | 'TAVILY' | 'SERPER';

export interface WebSearchConfig {
  enabled: boolean;
  allowInAskAi: boolean;
  provider: WebSearchProviderType;
  apiKey?: string; // null when not set, masked (••••••••) when stored
}

export interface UpdateWebSearchConfigRequest {
  enabled?: boolean;
  allowInAskAi?: boolean;
  provider?: WebSearchProviderType;
  apiKey?: string;
}

export interface AiAnonymizationConfig {
  enabled: boolean;
  presidioUrl?: string;
  scoreThreshold: number;
}

export interface UpdateAiAnonymizationConfigRequest {
  enabled?: boolean;
  presidioUrl?: string;
  scoreThreshold?: number;
}

export interface AiLogConfig {
  enabled: boolean;
  retentionDays: number;
}

export interface UpdateAiLogConfigRequest {
  enabled?: boolean;
}

/** One day's AI token totals, summed across every user, provider and model. */
export interface AiTokenUsageDay {
  date: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  requests: number;
}

export interface AiRequestLog {
  id: string;
  createdAt: string;
  username?: string;
  action: string;
  assessmentId?: string;
  vulnerabilityId?: string;
  promptName?: string;
  providerName?: string;
  model?: string;
  anonymizationEnabled: boolean;
  success: boolean;
  errorMessage?: string;
  durationMs: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  // detail-only
  requestPayload?: string;
  responseContent?: string;
}

export interface AiPromptSummary {
  id: string;
  name: string;
  description?: string;
  scope: AiPromptScope;
}

export interface ExecuteAiPromptRequest {
  promptId: string;
  assessmentId: string;
  vulnerabilityId?: string;
  currentText?: string;
}

export interface AskAiRequest {
  assessmentId: string;
  vulnerabilityId?: string;
  question: string;
  currentText?: string;
}

export interface AiGenerationResponse {
  success: boolean;
  content?: string;
  message?: string;
}

export interface SuggestAiTitleRequest {
  assessmentId: string;
  vulnerabilityId?: string;
  description?: string;
  details?: string;
}

export type NotificationTargetType = 'APPLICATION' | 'VULNERABILITY' | 'NOTEBOOK';

export interface Notification {
  id: string;
  username: string;
  title: string;
  message: string;
  type: string;
  link?: string;
  /** What the notification is about — absent on rows recorded before this was captured. */
  targetType?: NotificationTargetType;
  targetId?: string;
  targetName?: string;
  actorUsername?: string;
  actorName?: string;
  /** Plain-text snippet of the comment that triggered it. */
  excerpt?: string;
  read: boolean;
  readAt?: string;
  createdAt: string;
}

export interface ApplicationIdConfig {
  id: string;
  prefix: string;
  nextNumber: number;
  padding: number;
  enabled: boolean;
  createdBy?: string;
  lastUpdatedBy?: string;
  createdAt: string;
  updatedAt: string;
}

// ── Report documents (Finalize panel) ──

export type ReportDocumentType = 'DOCX' | 'PDF' | 'ENCRYPTED_PDF';
export type ReportDocumentStatus = 'GENERATING' | 'COMPLETED' | 'FAILED';

export interface ReportDocumentInfo {
  type: ReportDocumentType;
  status: ReportDocumentStatus;
  available: boolean;
  generatedAt?: string | null;
  errorMessage?: string | null;
}

export interface ReportDocuments {
  documents: ReportDocumentInfo[];
  reportPassword?: string | null;
}

// ── App Store extensions ──

/** Config entry as declared by an extension's config.json. */
export interface ExtensionConfigEntry {
  type: string;
  value: string;
  [key: string]: unknown;
}

export interface Extension {
  id: string;
  name: string;
  author?: string;
  version?: string;
  url?: string;
  description?: string;
  logoBase64?: string;
  logoMimeType?: string;
  hash?: string;

  enabled: boolean;
  displayOrder: number;

  /** Which hooks the JAR declares. */
  providesAssessment: boolean;
  providesVulnerability: boolean;
  providesVerification: boolean;
  providesInventory: boolean;
  providesReport: boolean;

  /** Which of those hooks are switched on. */
  assessmentEnabled: boolean;
  assessmentOrder: number;
  vulnerabilityEnabled: boolean;
  vulnerabilityOrder: number;
  verificationEnabled: boolean;
  verificationOrder: number;
  inventoryEnabled: boolean;
  inventoryOrder: number;
  reportEnabled: boolean;
  reportOrder: number;

  /** Declared config; password values arrive masked. */
  config: Record<string, ExtensionConfigEntry>;

  createdAt?: string;
  updatedAt?: string;
}

export interface UpdateExtensionRequest {
  enabled?: boolean;
  displayOrder?: number;
  assessmentEnabled?: boolean;
  assessmentOrder?: number;
  vulnerabilityEnabled?: boolean;
  vulnerabilityOrder?: number;
  verificationEnabled?: boolean;
  verificationOrder?: number;
  inventoryEnabled?: boolean;
  inventoryOrder?: number;
  reportEnabled?: boolean;
  reportOrder?: number;
}

export interface ExtensionLog {
  id: string;
  level: string;
  eventType: string;
  message?: string;
  stackTrace?: string;
  timestamp: string;
}

/** An application held in an external system of record, surfaced by an inventory extension. */
export interface ExternalApplication {
  applicationId?: string;
  applicationName?: string;
  distributionList?: string;
  customFields?: Record<string, string>;
}


/**
 * One line of the retest activity log: a retest that was verified, the verdict, and who signed
 * off. Read from the retests themselves — a completed retest is already the record of the event.
 */
export interface RetestCompletionLog {
  retestId: string;
  /** PASSED or FAILED. */
  status: string;
  /** PASS or FAIL, as recorded by the verifier. */
  result?: string;
  completedAt?: string;
  completedBy?: string;
  completedByName?: string;
  vulnerabilityId?: string;
  vulnerabilityName?: string;
  severity?: VulnerabilitySeverity;
  assessmentId?: string;
  assessmentName?: string;
  applicationId?: string;
  applicationName?: string;
  organizationId?: string;
  organizationName?: string;
  comment?: string;
}

/** Pass/fail totals for a window of the retest activity log. */
export interface RetestActivitySummary {
  passed: number;
  failed: number;
  /** Passed + failed; cancelled retests are not completions and are counted nowhere. */
  total: number;
}


/** Feature keys from the backend `Feature` enum. Paid capabilities, all-or-nothing. */
export type FeatureKey =
  | 'sso'
  | 'encrypted_pdf'
  | 'branding'
  | 'inbound_email'
  | 'ai_observability'
  | 'external_owners'
  | 'custom_roles';

/** Quota keys from the backend `Quota` enum. Capabilities that ship, but capped. */
export type QuotaKey = 'ai_providers' | 'ai_prompts' | 'extensions';

/**
 * What this build includes. Read once at sign-in; every diamond badge and quota
 * counter in the UI derives from it.
 */
export interface EditionStatus {
  edition: 'COMMUNITY' | 'ENTERPRISE';
  features: Record<FeatureKey, boolean>;
  /** Absent key means no cap — unlimited quotas are omitted rather than sent as a huge number. */
  limits: Partial<Record<QuotaKey, number>>;
  usage: Record<QuotaKey, number>;
  upgradeUrl: string;
}

/** Body of a 402. `code` is what to branch on; `message` is for people. */
export interface UpgradeRequired {
  code: 'FEATURE_NOT_LICENSED' | 'QUOTA_EXCEEDED';
  feature?: FeatureKey;
  quota?: QuotaKey;
  limit?: number;
  message: string;
  upgradeUrl: string;
}

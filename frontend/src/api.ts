import axios from 'axios';

import type { MentionableUser, RetestCompletionLog, RetestActivitySummary, LoginRequest, LoginResponse, User, Role, ResourcePermissions, ApiResponse, PagedApiResponse, CreateUserRequest, UpdateUserRequest, Team, CreateTeamRequest, UpdateTeamRequest, CreateRoleRequest, UpdateRoleRequest, ApiKey, CreateApiKeyRequest, CreateApiKeyResponse, AssessmentType, CreateAssessmentTypeRequest, UpdateAssessmentTypeRequest, Organization, CreateOrganizationRequest, UpdateOrganizationRequest, Application, ApplicationStatus, ApplicationComment, ApplicationImportResult, CreateApplicationRequest, UpdateApplicationRequest, ApplicationConnection, CreateApplicationConnectionRequest, UpdateApplicationConnectionRequest, ReportTemplate, ReportTemplateSummary, CreateReportTemplateRequest, UpdateReportTemplateRequest, Assessment, CreateAssessmentRequest, UpdateAssessmentRequest, AssessmentMetrics, VulnerabilityCategory, CreateVulnerabilityCategoryRequest, UpdateVulnerabilityCategoryRequest, DefaultVulnerability, CreateDefaultVulnerabilityRequest, UpdateDefaultVulnerabilityRequest, DefaultVulnerabilityImportResult, UserDefinedField, Vulnerability, VulnerabilityListItem, VulnerabilityComment, CreateVulnerabilityRequest, UpdateVulnerabilityRequest, UpdateVulnerabilityExceptionRequest, AssessmentFile, EntityFieldConfig, FieldScope, PeerReview, UpdatePeerReviewRequest, AcceptPeerReviewRequest, AssessmentWorkflowConfig, ChecklistTemplate, CreateChecklistTemplateRequest, UpdateChecklistTemplateRequest, AssessmentChecklist, AddAssessmentChecklistRequest, UpdateAssessmentChecklistRequest, AssignedUser, AssignUserRequest, UserApplicationAssignment, SsoConfig, SsoStatus, AzureDirectoryUser, NotebookNode, NotebookSearchResult, CreateNotebookNodeRequest, UpdateNotebookNodeRequest, MoveNotebookNodeRequest, NotebookAttachment, Retest, CreateRetestRequest, UpdateRetestRequest, CompleteRetestRequest, EmailConfig, UpdateEmailConfigRequest, TestEmailRequest, TestEmailResponse, InboundEmailConfig, UpdateInboundEmailConfigRequest, Branding, BrandingAssetSlot, UpdateBrandingSizesRequest, EmailNotificationConfig, UpdateEmailNotificationConfigRequest, NotificationPreference, UpdateNotificationPreferencesRequest, AiProviderConfig, SaveAiProviderConfigRequest, TestAiProviderRequest, TestAiProviderResponse, AiPromptTemplate, SaveAiPromptTemplateRequest, AiPromptSummary, AiPromptScope, ExecuteAiPromptRequest, AskAiRequest, AiGenerationResponse, SuggestAiTitleRequest, WebSearchConfig, UpdateWebSearchConfigRequest, AiAnonymizationConfig, UpdateAiAnonymizationConfigRequest, AiLogConfig, UpdateAiLogConfigRequest, AiRequestLog, AiTokenUsageDay, Notification, NotificationTargetType, SurveyTemplate, CreateSurveyTemplateRequest, UpdateSurveyTemplateRequest, AssessmentSurvey, AddAssessmentSurveyRequest, UpdateAssessmentSurveyRequest, ApplicationIdConfig, ReportDocuments, Campaign, CreateCampaignRequest, UpdateCampaignRequest, ManagerDashboardSummary, ManagerDashboardStats, ManagerDashboardAssessment, ManagerDashboardVulnerability, ManagerDashboardVulnerabilityDetail, ManagerDashboardFilters, VulnerabilityTrendSummary, RemediationQueueRow, AssignableUser, SubOrganization, SubOrganizationRequest, VulnerabilityStageCompletion, Extension, ExtensionLog, UpdateExtensionRequest, ExternalApplication, EditionStatus, UpgradeRequired } from './types';

const api = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

function isJwtExpired(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    return typeof payload.exp === 'number' && payload.exp * 1000 < Date.now();
  } catch {
    return true;
  }
}

// Add a request interceptor to add auth token
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    if (isJwtExpired(token)) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.dispatchEvent(new Event('logout'));
      window.location.href = '/login';
      return Promise.reject(new Error('Session expired'));
    }
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Add a response interceptor to handle auth errors
api.interceptors.response.use(
  (response) => response,
  (error) => {
    // Don't redirect on login endpoint failures - let the login page handle them
    const isLoginEndpoint = error.config?.url?.includes('/auth/login');

    if (error.response?.status === 401 && !isLoginEndpoint) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');

      // Dispatch custom event to notify App component
      window.dispatchEvent(new Event('logout'));

      window.location.href = '/login';
    }

    // 402 means this build does not include what was asked for — not that the caller
    // did something wrong. Announce it so one dialog can explain it everywhere, which
    // also means a gate the UI forgot to render still fails as an upgrade prompt
    // rather than as a raw error toast.
    if (error.response?.status === 402) {
      window.dispatchEvent(new CustomEvent<UpgradeRequired>('upgrade-required', {
        detail: error.response.data as UpgradeRequired,
      }));
    }
    return Promise.reject(error);
  }
);

/**
 * Origin-relative URLs for files streamed by the backend.
 *
 * These go into `<a href>` and `<img src>`, so they are absolute paths rather
 * than axios-relative ones. Object storage is never addressed directly from the
 * browser: the backend authorizes and streams every read, and the browser
 * authenticates with the media cookie issued at login (an `<a>` or `<img>`
 * cannot set an Authorization header).
 */
export const fileUrls = {
  assessmentFile: (assessmentId: string, fileId: string) =>
    `/api/v1/assessments/${assessmentId}/files/${fileId}/content`,
  exceptionFile: (assessmentId: string, vulnId: string, fileId: string) =>
    `/api/v1/assessments/${assessmentId}/vulnerabilities/${vulnId}/exception-files/${fileId}/content`,
  notebookFile: (nodeId: string, fileId: string) =>
    `/api/v1/notebook/nodes/${nodeId}/files/${fileId}/content`,
  report: (assessmentId: string, type: string = 'DOCX') =>
    `/api/v1/reports/${assessmentId}/documents/${encodeURIComponent(type)}/content`,
};

/**
 * Stream a file body to an upload target returned by a `prepare` call.
 *
 * `baseURL: ''` because the backend hands back a full origin-relative path; the
 * request still goes through the axios instance so it picks up the auth header.
 */
export async function uploadFileContent(uploadUrl: string, file: File): Promise<void> {
  await api.put(uploadUrl, file, {
    baseURL: '',
    params: { fileName: file.name },
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
  });
}

export const authApi = {
  login: async (credentials: LoginRequest): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>('/auth/login', credentials);
    return response.data;
  },
  // Note: /auth/me returns a flat object, not the usual { data: ... } envelope
  getMe: () =>
    api.get<{ id: string; username: string; authorities: string[]; roles?: string[]; isInternal?: boolean }>('/auth/me').then(r => r.data),
  /**
   * Clears the server-set media cookie. The JWT is stateless, so callers must
   * still drop their own copy of the token; this only revokes the browser's
   * ability to fetch images and file downloads.
   */
  logout: () => api.post<{ message: string }>('/auth/logout').then(r => r.data),
  forgotPassword: (email: string) =>
    api.post<{ message: string }>('/auth/forgot-password', { email }).then(r => r.data),
  resetPassword: (token: string, newPassword: string) =>
    api.post<{ message: string }>('/auth/reset-password', { token, newPassword }).then(r => r.data),
};

export const profileApi = {
  me: async (): Promise<ApiResponse<User>> => {
    const response = await api.get<ApiResponse<User>>('/users/me');
    return response.data;
  },
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post('/users/me/change-password', { currentPassword, newPassword }),
  uploadImage: async (file: File): Promise<ApiResponse<{ profileImageId: string }>> => {
    const form = new FormData();
    form.append('file', file);
    const response = await api.post<ApiResponse<{ profileImageId: string }>>(
      '/users/me/profile-image', form,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    );
    return response.data;
  },
  removeImage: () => api.delete('/users/me/profile-image'),
  avatarMap: async (): Promise<ApiResponse<Record<string, { seed: string; profileImageId?: string | null }>>> => {
    const response = await api.get<ApiResponse<Record<string, { seed: string; profileImageId?: string | null }>>>('/users/avatars');
    return response.data;
  },
};

export const usersApi = {
  getAll: async (
    page = 0,
    size = 10,
    search = '',
    sort = '',
    /** Optional narrowing filters; an empty value is left off the request entirely. */
    filters: {
      roleId?: string;
      teamId?: string;
      organizationId?: string;
      type?: 'INTERNAL' | 'EXTERNAL' | '';
    } = {},
  ): Promise<PagedApiResponse<User[]>> => {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
    });
    if (search) {
      params.append('search', search);
    }
    if (sort) {
      params.append('sort', sort);
    }
    Object.entries(filters).forEach(([key, value]) => {
      if (value) params.append(key, value);
    });
    const response = await api.get<PagedApiResponse<User[]>>(`/users?${params.toString()}`);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<User>> => {
    const response = await api.get<ApiResponse<User>>(`/users/${id}`);
    return response.data;
  },

  create: async (user: CreateUserRequest): Promise<ApiResponse<User>> => {
    const response = await api.post<ApiResponse<User>>('/users', user);
    return response.data;
  },

  update: async (id: string, user: UpdateUserRequest): Promise<ApiResponse<User>> => {
    const response = await api.put<ApiResponse<User>>(`/users/${id}`, user);
    return response.data;
  },

  getApplicationAssignments: async (id: string): Promise<ApiResponse<UserApplicationAssignment[]>> => {
    const response = await api.get<ApiResponse<UserApplicationAssignment[]>>(`/users/${id}/application-assignments`);
    return response.data;
  },

  syncApplicationAssignments: async (
    id: string,
    assignments: { applicationId: string; accessLevel: 'READ' | 'WRITE' }[],
  ): Promise<ApiResponse<UserApplicationAssignment[]>> => {
    const response = await api.put<ApiResponse<UserApplicationAssignment[]>>(
      `/users/${id}/application-assignments`, { assignments });
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/users/${id}`);
    return response.data;
  },
};

export const rolesApi = {
  getAll: async (page = 0, size = 10, search = '', sort = ''): Promise<PagedApiResponse<Role[]>> => {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
    });
    if (search) {
      params.append('search', search);
    }
    if (sort) {
      params.append('sort', sort);
    }
    const response = await api.get<PagedApiResponse<Role[]>>(`/roles?${params.toString()}`);
    return response.data;
  },

  getAllUnpaginated: async (): Promise<ApiResponse<Role[]>> => {
    const response = await api.get<ApiResponse<Role[]>>('/roles?size=1000');
    return response.data;
  },

  create: async (role: CreateRoleRequest): Promise<ApiResponse<Role>> => {
    const response = await api.post<ApiResponse<Role>>('/roles', role);
    return response.data;
  },

  update: async (id: string, role: UpdateRoleRequest): Promise<ApiResponse<Role>> => {
    const response = await api.put<ApiResponse<Role>>(`/roles/${id}`, role);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/roles/${id}`);
    return response.data;
  },
};

export const permissionsApi = {
  getAll: async (): Promise<ApiResponse<ResourcePermissions[]>> => {
    const response = await api.get<ApiResponse<ResourcePermissions[]>>('/permissions');
    return response.data;
  },
};

export const apiKeysApi = {
  // Self-service user keys — always scoped to the authenticated caller's own keys.
  listMine: async (): Promise<ApiResponse<ApiKey[]>> => {
    const response = await api.get<ApiResponse<ApiKey[]>>('/api-keys');
    return response.data;
  },

  createMine: async (request: CreateApiKeyRequest): Promise<ApiResponse<CreateApiKeyResponse>> => {
    const response = await api.post<ApiResponse<CreateApiKeyResponse>>('/api-keys', request);
    return response.data;
  },

  revokeMine: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/api-keys/${id}`);
    return response.data;
  },
};

export const teamsApi = {
  getAll: async (page = 0, size = 10, search = '', sort = ''): Promise<PagedApiResponse<Team[]>> => {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
    });
    if (search) {
      params.append('search', search);
    }
    if (sort) {
      params.append('sort', sort);
    }
    const response = await api.get<PagedApiResponse<Team[]>>(`/teams?${params.toString()}`);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<Team>> => {
    const response = await api.get<ApiResponse<Team>>(`/teams/${id}`);
    return response.data;
  },

  create: async (team: CreateTeamRequest): Promise<ApiResponse<Team>> => {
    const response = await api.post<ApiResponse<Team>>('/teams', team);
    return response.data;
  },

  update: async (id: string, team: UpdateTeamRequest): Promise<ApiResponse<Team>> => {
    const response = await api.put<ApiResponse<Team>>(`/teams/${id}`, team);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/teams/${id}`);
    return response.data;
  },

  getUsersInTeam: async (teamId: string): Promise<ApiResponse<User[]>> => {
    const response = await api.get<ApiResponse<User[]>>(`/teams/${teamId}/users`);
    return response.data;
  },

  addUserToTeam: async (teamId: string, userId: string): Promise<ApiResponse<void>> => {
    const response = await api.post<ApiResponse<void>>(`/teams/${teamId}/users/${userId}`);
    return response.data;
  },

  removeUserFromTeam: async (teamId: string, userId: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/teams/${teamId}/users/${userId}`);
    return response.data;
  },
};

export const assessmentAssignmentApi = {
  /**
   * Users who can be added as assessors on an assessment — the assessment team's members, or all
   * internal users when it has no team. Gated on assessment access rather than users:read, so it
   * works for assessors who can't browse the user directory.
   */
  getAssignableAssessors: async (assessmentId: string): Promise<ApiResponse<AssignableUser[]>> => {
    const response = await api.get<ApiResponse<AssignableUser[]>>(
      `/assessments/${assessmentId}/assignable-assessors`);
    return response.data;
  },
};

export const subOrganizationsApi = {
  /**
   * Every division the caller can see, across organizations, each carrying its owning
   * organization's id and name. For pickers that have no single organization in hand.
   */
  listAll: async (name?: string): Promise<ApiResponse<SubOrganization[]>> => {
    const response = await api.get<ApiResponse<SubOrganization[]>>('/sub-organizations',
      name ? { params: { name } } : undefined);
    return response.data;
  },

  /** Divisions within an organization, each with the number of applications attributed to it. */
  list: async (organizationId: string): Promise<ApiResponse<SubOrganization[]>> => {
    const response = await api.get<ApiResponse<SubOrganization[]>>(
      `/organizations/${organizationId}/sub-organizations`);
    return response.data;
  },

  create: async (organizationId: string, data: SubOrganizationRequest): Promise<ApiResponse<SubOrganization>> => {
    const response = await api.post<ApiResponse<SubOrganization>>(
      `/organizations/${organizationId}/sub-organizations`, data);
    return response.data;
  },

  update: async (organizationId: string, id: string, data: SubOrganizationRequest): Promise<ApiResponse<SubOrganization>> => {
    const response = await api.put<ApiResponse<SubOrganization>>(
      `/organizations/${organizationId}/sub-organizations/${id}`, data);
    return response.data;
  },

  /** Rejected with 409 while applications are still attributed to it. */
  delete: async (organizationId: string, id: string): Promise<ApiResponse<{ id: string; status: string }>> => {
    const response = await api.delete<ApiResponse<{ id: string; status: string }>>(
      `/organizations/${organizationId}/sub-organizations/${id}`);
    return response.data;
  },
};

export const campaignsApi = {
  getAll: async (page = 0, size = 10, search = '', sort = ''): Promise<PagedApiResponse<Campaign[]>> => {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
    });
    if (search) {
      params.append('search', search);
    }
    if (sort) {
      params.append('sort', sort);
    }
    const response = await api.get<PagedApiResponse<Campaign[]>>(`/campaigns?${params.toString()}`);
    return response.data;
  },

  getAllUnpaged: async (): Promise<ApiResponse<Campaign[]>> => {
    const response = await api.get<ApiResponse<Campaign[]>>('/campaigns/all');
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<Campaign>> => {
    const response = await api.get<ApiResponse<Campaign>>(`/campaigns/${id}`);
    return response.data;
  },

  create: async (campaign: CreateCampaignRequest): Promise<ApiResponse<Campaign>> => {
    const response = await api.post<ApiResponse<Campaign>>('/campaigns', campaign);
    return response.data;
  },

  update: async (id: string, campaign: UpdateCampaignRequest): Promise<ApiResponse<Campaign>> => {
    const response = await api.put<ApiResponse<Campaign>>(`/campaigns/${id}`, campaign);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/campaigns/${id}`);
    return response.data;
  },
};

// Shared query-param builder for every manager dashboard endpoint
function managerDashboardParams(filters: ManagerDashboardFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.search) params.append('search', filters.search);
  if (filters.applicationId) params.append('applicationId', filters.applicationId);
  if (filters.assessmentTypeId) params.append('assessmentTypeId', filters.assessmentTypeId);
  if (filters.assessorId) params.append('assessorId', filters.assessorId);
  if (filters.status) params.append('status', filters.status);
  if (filters.teamId) params.append('teamId', filters.teamId);
  if (filters.campaignId) params.append('campaignId', filters.campaignId);
  if (filters.severities) filters.severities.forEach((s) => params.append('severities', s));
  if (filters.startDateFrom) params.append('startDateFrom', filters.startDateFrom);
  if (filters.startDateTo) params.append('startDateTo', filters.startDateTo);
  if (filters.endDateFrom) params.append('endDateFrom', filters.endDateFrom);
  if (filters.endDateTo) params.append('endDateTo', filters.endDateTo);
  if (filters.showCompleted !== undefined) params.append('showCompleted', String(filters.showCompleted));
  return params;
}

export const managerDashboardApi = {
  getSummary: async (): Promise<ApiResponse<ManagerDashboardSummary>> => {
    const response = await api.get<ApiResponse<ManagerDashboardSummary>>('/manager-dashboard/summary');
    return response.data;
  },

  getStats: async (filters: ManagerDashboardFilters): Promise<ApiResponse<ManagerDashboardStats>> => {
    const response = await api.get<ApiResponse<ManagerDashboardStats>>(
      `/manager-dashboard/stats?${managerDashboardParams(filters).toString()}`);
    return response.data;
  },

  searchAssessments: async (
    filters: ManagerDashboardFilters, page = 0, size = 25, sort = 'startDate,desc'
  ): Promise<PagedApiResponse<ManagerDashboardAssessment[]>> => {
    const params = managerDashboardParams(filters);
    params.append('page', page.toString());
    params.append('size', size.toString());
    params.append('sort', sort);
    const response = await api.get<PagedApiResponse<ManagerDashboardAssessment[]>>(
      `/manager-dashboard/assessments?${params.toString()}`);
    return response.data;
  },

  searchVulnerabilities: async (
    filters: ManagerDashboardFilters, page = 0, size = 25, sort?: string
  ): Promise<PagedApiResponse<ManagerDashboardVulnerability[]>> => {
    const params = managerDashboardParams(filters);
    params.append('page', page.toString());
    params.append('size', size.toString());
    if (sort) params.append('sort', sort);
    const response = await api.get<PagedApiResponse<ManagerDashboardVulnerability[]>>(
      `/manager-dashboard/vulnerabilities?${params.toString()}`);
    return response.data;
  },

  getVulnerabilityDetail: async (id: string): Promise<ApiResponse<ManagerDashboardVulnerabilityDetail>> => {
    const response = await api.get<ApiResponse<ManagerDashboardVulnerabilityDetail>>(
      `/manager-dashboard/vulnerabilities/${id}`);
    return response.data;
  },

  exportAssessmentsCsv: async (filters: ManagerDashboardFilters): Promise<Blob> => {
    const response = await api.get('/manager-dashboard/export/assessments.csv', {
      params: managerDashboardParams(filters),
      responseType: 'blob',
    });
    return response.data;
  },

  exportVulnerabilitiesCsv: async (filters: ManagerDashboardFilters): Promise<Blob> => {
    const response = await api.get('/manager-dashboard/export/vulnerabilities.csv', {
      params: managerDashboardParams(filters),
      responseType: 'blob',
    });
    return response.data;
  },
};

export const assessmentTypesApi = {
  getAll: async (page = 0, size = 10, sort = 'name,asc', search = ''): Promise<PagedApiResponse<AssessmentType[]>> => {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
      sort: sort,
    });
    if (search) {
      params.append('search', search);
    }
    const response = await api.get<PagedApiResponse<AssessmentType[]>>(`/assessment-types?${params.toString()}`);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<AssessmentType>> => {
    const response = await api.get<ApiResponse<AssessmentType>>(`/assessment-types/${id}`);
    return response.data;
  },

  create: async (assessmentType: CreateAssessmentTypeRequest): Promise<ApiResponse<AssessmentType>> => {
    const response = await api.post<ApiResponse<AssessmentType>>('/assessment-types', assessmentType);
    return response.data;
  },

  update: async (id: string, assessmentType: UpdateAssessmentTypeRequest): Promise<ApiResponse<AssessmentType>> => {
    const response = await api.put<ApiResponse<AssessmentType>>(`/assessment-types/${id}`, assessmentType);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/assessment-types/${id}`);
    return response.data;
  },
};

export const organizationsApi = {
  getAll: async (page = 0, size = 10, search = '', sort = ''): Promise<PagedApiResponse<Organization[]>> => {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
    });
    if (search) {
      params.append('search', search);
    }
    if (sort) {
      params.append('sort', sort);
    }
    const response = await api.get<PagedApiResponse<Organization[]>>(`/organizations?${params.toString()}`);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<Organization>> => {
    const response = await api.get<ApiResponse<Organization>>(`/organizations/${id}`);
    return response.data;
  },

  create: async (organization: CreateOrganizationRequest): Promise<ApiResponse<Organization>> => {
    const response = await api.post<ApiResponse<Organization>>('/organizations', organization);
    return response.data;
  },

  update: async (id: string, organization: UpdateOrganizationRequest): Promise<ApiResponse<Organization>> => {
    const response = await api.put<ApiResponse<Organization>>(`/organizations/${id}`, organization);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/organizations/${id}`);
    return response.data;
  },

  getAssignedUsers: async (orgId: string): Promise<ApiResponse<AssignedUser[]>> => {
    const response = await api.get<ApiResponse<AssignedUser[]>>(`/organizations/${orgId}/users`);
    return response.data;
  },

  assignUser: async (orgId: string, data: AssignUserRequest): Promise<ApiResponse<AssignedUser>> => {
    const response = await api.post<ApiResponse<AssignedUser>>(`/organizations/${orgId}/users`, data);
    return response.data;
  },

  updateAssignedUser: async (orgId: string, userId: string, data: { accessLevel: string }): Promise<ApiResponse<AssignedUser>> => {
    const response = await api.put<ApiResponse<AssignedUser>>(`/organizations/${orgId}/users/${userId}`, data);
    return response.data;
  },

  removeAssignedUser: async (orgId: string, userId: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/organizations/${orgId}/users/${userId}`);
    return response.data;
  },
};

export const applicationsApi = {
  getAll: async (
    page = 0,
    size = 10,
    search = '',
    sort = '',
    /** Optional narrowing filters; an empty value is left off the request entirely. */
    filters: {
      organizationId?: string;
      subOrganizationId?: string;
      status?: ApplicationStatus | '';
    } = {},
  ): Promise<PagedApiResponse<Application[]>> => {
    const params: Record<string, string | number> = { page, size };
    if (search) {
      params.search = search;
    }
    if (sort) {
      params.sort = sort;
    }
    Object.entries(filters).forEach(([key, value]) => {
      if (value) params[key] = value;
    });
    const response = await api.get<PagedApiResponse<Application[]>>('/applications', { params });
    return response.data;
  },

  /** Admin only: upsert applications from a CSV (matched by appId, then name). */
  importCsv: async (file: File): Promise<ApiResponse<ApplicationImportResult>> => {
    const form = new FormData();
    form.append('file', file);
    const response = await api.post<ApiResponse<ApplicationImportResult>>(
      '/applications/import', form, { headers: { 'Content-Type': 'multipart/form-data' } });
    return response.data;
  },

  /** Admin only: the CSV layout the sync accepts, with an example row. */
  importTemplate: async (): Promise<Blob> => {
    const response = await api.get('/applications/import/template', { responseType: 'blob' });
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<Application>> => {
    const response = await api.get<ApiResponse<Application>>(`/applications/${id}`);
    return response.data;
  },

  create: async (application: CreateApplicationRequest): Promise<ApiResponse<Application>> => {
    const response = await api.post<ApiResponse<Application>>('/applications', application);
    return response.data;
  },

  update: async (id: string, application: UpdateApplicationRequest): Promise<ApiResponse<Application>> => {
    const response = await api.put<ApiResponse<Application>>(`/applications/${id}`, application);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/applications/${id}`);
    return response.data;
  },

  getAssignedUsers: async (appId: string): Promise<ApiResponse<AssignedUser[]>> => {
    const response = await api.get<ApiResponse<AssignedUser[]>>(`/applications/${appId}/users`);
    return response.data;
  },

  assignUser: async (appId: string, data: AssignUserRequest): Promise<ApiResponse<AssignedUser>> => {
    const response = await api.post<ApiResponse<AssignedUser>>(`/applications/${appId}/users`, data);
    return response.data;
  },

  updateAssignedUser: async (appId: string, userId: string, data: { accessLevel: string }): Promise<ApiResponse<AssignedUser>> => {
    const response = await api.put<ApiResponse<AssignedUser>>(`/applications/${appId}/users/${userId}`, data);
    return response.data;
  },

  removeAssignedUser: async (appId: string, userId: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/applications/${appId}/users/${userId}`);
    return response.data;
  },

  getSubscribers: async (appId: string): Promise<ApiResponse<string[]>> => {
    const response = await api.get<ApiResponse<string[]>>(`/applications/${appId}/subscribers`);
    return response.data;
  },

  addSubscriber: async (appId: string, username: string): Promise<ApiResponse<string[]>> => {
    const response = await api.post<ApiResponse<string[]>>(
      `/applications/${appId}/subscribers/${encodeURIComponent(username)}`);
    return response.data;
  },

  removeSubscriber: async (appId: string, username: string): Promise<ApiResponse<string[]>> => {
    const response = await api.delete<ApiResponse<string[]>>(
      `/applications/${appId}/subscribers/${encodeURIComponent(username)}`);
    return response.data;
  },

  addComment: async (appId: string, content: string): Promise<ApiResponse<ApplicationComment[]>> => {
    const response = await api.post<ApiResponse<ApplicationComment[]>>(`/applications/${appId}/comments`, { content });
    return response.data;
  },

  deleteComment: async (appId: string, commentId: string): Promise<ApiResponse<ApplicationComment[]>> => {
    const response = await api.delete<ApiResponse<ApplicationComment[]>>(`/applications/${appId}/comments/${commentId}`);
    return response.data;
  },
};

export const applicationConnectionsApi = {
  getAll: async (): Promise<ApiResponse<ApplicationConnection[]>> => {
    const response = await api.get<ApiResponse<ApplicationConnection[]>>('/application-connections');
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<ApplicationConnection>> => {
    const response = await api.get<ApiResponse<ApplicationConnection>>(`/application-connections/${id}`);
    return response.data;
  },

  getOutgoing: async (applicationId: string): Promise<ApiResponse<ApplicationConnection[]>> => {
    const response = await api.get<ApiResponse<ApplicationConnection[]>>(`/application-connections/application/${applicationId}/outgoing`);
    return response.data;
  },

  getIncoming: async (applicationId: string): Promise<ApiResponse<ApplicationConnection[]>> => {
    const response = await api.get<ApiResponse<ApplicationConnection[]>>(`/application-connections/application/${applicationId}/incoming`);
    return response.data;
  },

  getAllForApplication: async (applicationId: string): Promise<ApiResponse<ApplicationConnection[]>> => {
    const response = await api.get<ApiResponse<ApplicationConnection[]>>(`/application-connections/application/${applicationId}/all`);
    return response.data;
  },

  create: async (connection: CreateApplicationConnectionRequest): Promise<ApiResponse<ApplicationConnection>> => {
    const response = await api.post<ApiResponse<ApplicationConnection>>('/application-connections', connection);
    return response.data;
  },

  update: async (id: string, connection: UpdateApplicationConnectionRequest): Promise<ApiResponse<ApplicationConnection>> => {
    const response = await api.put<ApiResponse<ApplicationConnection>>(`/application-connections/${id}`, connection);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/application-connections/${id}`);
    return response.data;
  },
};

export const reportTemplatesApi = {
  getAll: async (
    page = 0,
    size = 10,
    name?: string,
    assessmentTypeId?: string,
    active?: boolean,
    sort = 'name,asc'
  ): Promise<PagedApiResponse<ReportTemplateSummary[]>> => {
    const params: Record<string, string | number | boolean> = { page, size, sort };
    if (name) params.name = name;
    if (assessmentTypeId) params.assessmentTypeId = assessmentTypeId;
    if (active !== undefined) params.active = active;

    const response = await api.get<PagedApiResponse<ReportTemplateSummary[]>>('/report-templates', { params });
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<ReportTemplate>> => {
    const response = await api.get<ApiResponse<ReportTemplate>>(`/report-templates/${id}`);
    return response.data;
  },

  getByAssessmentType: async (assessmentTypeId: string): Promise<ApiResponse<ReportTemplateSummary[]>> => {
    const response = await api.get<ApiResponse<ReportTemplateSummary[]>>(
      `/report-templates/by-assessment-type/${assessmentTypeId}`
    );
    return response.data;
  },

  create: async (template: CreateReportTemplateRequest): Promise<ApiResponse<ReportTemplate>> => {
    const response = await api.post<ApiResponse<ReportTemplate>>('/report-templates', template);
    return response.data;
  },

  update: async (id: string, template: UpdateReportTemplateRequest): Promise<ApiResponse<ReportTemplate>> => {
    const response = await api.put<ApiResponse<ReportTemplate>>(`/report-templates/${id}`, template);
    return response.data;
  },

  /** Duplicate a template under a new name — fields, variables, CSS, sections and the DOCX all copied. */
  clone: async (id: string, name: string): Promise<ApiResponse<ReportTemplate>> => {
    const response = await api.post<ApiResponse<ReportTemplate>>(`/report-templates/${id}/clone`, { name });
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<{ status: string; message: string }>> => {
    const response = await api.delete<ApiResponse<{ status: string; message: string }>>(`/report-templates/${id}`);
    return response.data;
  },

  uploadFile: async (id: string, file: File): Promise<ApiResponse<ReportTemplate>> => {
    const formData = new FormData();
    formData.append('file', file);

    const response = await api.post<ApiResponse<ReportTemplate>>(
      `/report-templates/${id}/file`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      }
    );
    return response.data;
  },

  downloadFile: async (id: string): Promise<Blob> => {
    const response = await api.get(`/report-templates/${id}/file`, {
      responseType: 'blob',
    });
    return response.data;
  },

  getVulnerabilityFields: async (): Promise<ApiResponse<UserDefinedField[]>> => {
    const response = await api.get<ApiResponse<UserDefinedField[]>>('/report-templates/vulnerability-fields');
    return response.data;
  },
};

export const assessmentsApi = {
  getAll: async (
    page = 0,
    size = 10,
    applicationId?: string,
    organizationId?: string,
    assessmentTypeId?: string,
    assessorId?: string,
    status?: string,
    name?: string,
    sort = 'createdAt,desc',
    pastDue?: boolean
  ): Promise<PagedApiResponse<Assessment[]>> => {
    const params: Record<string, string | number | boolean> = { page, size, sort };
    if (applicationId) params.applicationId = applicationId;
    if (organizationId) params.organizationId = organizationId;
    if (assessmentTypeId) params.assessmentTypeId = assessmentTypeId;
    if (assessorId) params.assessorId = assessorId;
    if (status) params.status = status;
    if (name) params.name = name;
    if (pastDue) params.pastDue = true;

    const response = await api.get<PagedApiResponse<Assessment[]>>('/assessments', { params });
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<Assessment>> => {
    const response = await api.get<ApiResponse<Assessment>>(`/assessments/${id}`);
    return response.data;
  },

  getByApplication: async (applicationId: string, page = 0, size = 10): Promise<PagedApiResponse<Assessment[]>> => {
    const params = { page, size };
    const response = await api.get<PagedApiResponse<Assessment[]>>(
      `/assessments/by-application/${applicationId}`,
      { params }
    );
    return response.data;
  },

  create: async (assessment: CreateAssessmentRequest): Promise<ApiResponse<Assessment>> => {
    const response = await api.post<ApiResponse<Assessment>>('/assessments', assessment);
    return response.data;
  },

  update: async (id: string, assessment: UpdateAssessmentRequest): Promise<ApiResponse<Assessment>> => {
    const response = await api.put<ApiResponse<Assessment>>(`/assessments/${id}`, assessment);
    return response.data;
  },

  updateStatus: async (id: string, status: string): Promise<ApiResponse<Assessment>> => {
    const response = await api.put<ApiResponse<Assessment>>(`/assessments/${id}`, { status });
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/assessments/${id}`);
    return response.data;
  },

  validateFieldValues: async (id: string, fieldValues: Record<string, string>): Promise<ApiResponse<Record<string, string>>> => {
    const response = await api.post<ApiResponse<Record<string, string>>>(
      `/assessments/${id}/validate`,
      fieldValues
    );
    return response.data;
  },

  getMetrics: async (organizationId?: string): Promise<ApiResponse<AssessmentMetrics>> => {
    const params: Record<string, string> = {};
    if (organizationId) params.organizationId = organizationId;
    const response = await api.get<ApiResponse<AssessmentMetrics>>('/assessments/metrics', { params });
    return response.data;
  },

  getCalendarView: async (
    startDate: string,
    endDate: string,
    page = 0,
    size = 100
  ): Promise<PagedApiResponse<Assessment[]>> => {
    const start = startDate.includes('T') ? startDate : `${startDate}T00:00:00`;
    const end = endDate.includes('T') ? endDate : `${endDate}T23:59:59`;
    const params = { startDate: start, endDate: end, page, size };
    const response = await api.get<PagedApiResponse<Assessment[]>>('/assessments/calendar', { params });
    return response.data;
  },

  checkConflicts: async (
    assessmentId: string | null,
    assessorIds: string[],
    startDate: string,
    endDate: string
  ): Promise<ApiResponse<Assessment[]>> => {
    const response = await api.post<ApiResponse<Assessment[]>>('/assessments/check-conflicts', {
      assessmentId,
      assessorIds,
      startDate,
      endDate
    });
    return response.data;
  },

  search: async (filters: {
    page?: number;
    size?: number;
    search?: string; // Combined search across name, application, assessor, status
    startDateFrom?: string;
    startDateTo?: string;
    endDateFrom?: string;
    endDateTo?: string;
    pastDue?: boolean;
    showCompleted?: boolean;
    assignedToMe?: boolean;
    status?: string;
    /** Multi-select status filter; ORed with each other, ANDed with the rest. */
    statuses?: string[];
    /** Only assessments carrying at least one unfinished survey. */
    openSurveys?: boolean;
    applicationId?: string;
    applicationIds?: string[];
    assessmentTypeId?: string;
    sort?: string;
  }): Promise<PagedApiResponse<Assessment[]>> => {
    const params: Record<string, string | number | boolean> = {
      page: filters.page ?? 0,
      size: filters.size ?? 10,
      sort: filters.sort ?? 'createdAt,desc'
    };

    // All filters are now supported by the backend
    if (filters.search) params.search = filters.search;
    if (filters.startDateFrom) params.startDateFrom = filters.startDateFrom;
    if (filters.startDateTo) params.startDateTo = filters.startDateTo;
    if (filters.endDateFrom) params.endDateFrom = filters.endDateFrom;
    if (filters.endDateTo) params.endDateTo = filters.endDateTo;
    if (filters.pastDue !== undefined) params.pastDue = filters.pastDue;
    if (filters.showCompleted !== undefined) params.showCompleted = filters.showCompleted;
    if (filters.assignedToMe !== undefined) params.assignedToMe = filters.assignedToMe;
    if (filters.status) params.status = filters.status;
    // Comma-joined so Spring binds it to List<String> statuses.
    if (filters.statuses?.length) params.statuses = filters.statuses.join(',');
    if (filters.openSurveys) params.openSurveys = true;
    if (filters.applicationId) params.applicationId = filters.applicationId;
    // Comma-joined so Spring binds it to List<String> applicationIds.
    if (filters.applicationIds?.length) params.applicationIds = filters.applicationIds.join(',');
    if (filters.assessmentTypeId) params.assessmentTypeId = filters.assessmentTypeId;

    const response = await api.get<PagedApiResponse<Assessment[]>>('/assessments', { params });
    return response.data;
  },

  acquireLock: async (assessmentId: string, fieldId: string): Promise<void> => {
    await api.post(`/assessments/${assessmentId}/fields/${fieldId}/lock`);
  },

  releaseLock: async (assessmentId: string, fieldId: string): Promise<void> => {
    await api.delete(`/assessments/${assessmentId}/fields/${fieldId}/lock`);
  },

  exportToCsv: async (filters: {
    applicationId?: string;
    organizationId?: string;
    assessmentTypeId?: string;
    assessorId?: string;
    status?: string;
    name?: string;
  }): Promise<Blob> => {
    const params: Record<string, string> = {};
    if (filters.applicationId) params.applicationId = filters.applicationId;
    if (filters.organizationId) params.organizationId = filters.organizationId;
    if (filters.assessmentTypeId) params.assessmentTypeId = filters.assessmentTypeId;
    if (filters.assessorId) params.assessorId = filters.assessorId;
    if (filters.status) params.status = filters.status;
    if (filters.name) params.name = filters.name;

    const response = await api.get('/assessments/export/csv', {
      params,
      responseType: 'blob'
    });
    return response.data;
  },

  // ── File attachment methods ──────────────────────────────────────────────

  prepareUpload: async (
    assessmentId: string,
    fileName: string,
    contentType: string,
    fileSize: number
  ): Promise<ApiResponse<{ fileId: string; uploadUrl: string; storageKey: string }>> => {
    const response = await api.post(
      `/assessments/${assessmentId}/files/prepare`,
      { fileName, contentType, fileSize }
    );
    return response.data;
  },

  confirmUpload: async (
    assessmentId: string,
    fileId: string,
    fileName: string,
    contentType: string,
    fileSize: number
  ): Promise<ApiResponse<import('./types').AssessmentFile>> => {
    const response = await api.post(`/assessments/${assessmentId}/files`, {
      fileId, fileName, contentType, fileSize,
    });
    return response.data;
  },

  /** Origin-relative URL the browser streams the attachment from. */
  getDownloadUrl: (assessmentId: string, fileId: string): string =>
    fileUrls.assessmentFile(assessmentId, fileId),

  deleteFile: async (assessmentId: string, fileId: string): Promise<ApiResponse<void>> => {
    const response = await api.delete(`/assessments/${assessmentId}/files/${fileId}`);
    return response.data;
  },
};

export const vulnerabilityCategoriesApi = {
  getAll: async (): Promise<ApiResponse<VulnerabilityCategory[]>> => {
    const response = await api.get<ApiResponse<VulnerabilityCategory[]>>('/vulnerability-categories');
    return response.data;
  },

  create: async (data: CreateVulnerabilityCategoryRequest): Promise<ApiResponse<VulnerabilityCategory>> => {
    const response = await api.post<ApiResponse<VulnerabilityCategory>>('/vulnerability-categories', data);
    return response.data;
  },

  update: async (id: string, data: UpdateVulnerabilityCategoryRequest): Promise<ApiResponse<VulnerabilityCategory>> => {
    const response = await api.patch<ApiResponse<VulnerabilityCategory>>(`/vulnerability-categories/${id}`, data);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/vulnerability-categories/${id}`);
    return response.data;
  },
};

export const defaultVulnerabilitiesApi = {
  getAll: async (page = 0, size = 25, sort = 'order,asc', archived = false): Promise<PagedApiResponse<DefaultVulnerability[]>> => {
    const params = new URLSearchParams({ page: page.toString(), size: size.toString(), sort, archived: archived.toString() });
    const response = await api.get<PagedApiResponse<DefaultVulnerability[]>>(`/default-vulnerabilities?${params}`);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<DefaultVulnerability>> => {
    const response = await api.get<ApiResponse<DefaultVulnerability>>(`/default-vulnerabilities/${id}`);
    return response.data;
  },

  create: async (data: CreateDefaultVulnerabilityRequest): Promise<ApiResponse<DefaultVulnerability>> => {
    const response = await api.post<ApiResponse<DefaultVulnerability>>('/default-vulnerabilities', data);
    return response.data;
  },

  update: async (id: string, data: UpdateDefaultVulnerabilityRequest): Promise<ApiResponse<DefaultVulnerability>> => {
    const response = await api.patch<ApiResponse<DefaultVulnerability>>(`/default-vulnerabilities/${id}`, data);
    return response.data;
  },

  archive: async (id: string): Promise<ApiResponse<DefaultVulnerability>> => {
    const response = await api.patch<ApiResponse<DefaultVulnerability>>(`/default-vulnerabilities/${id}/archive`, {});
    return response.data;
  },

  unarchive: async (id: string): Promise<ApiResponse<DefaultVulnerability>> => {
    const response = await api.patch<ApiResponse<DefaultVulnerability>>(`/default-vulnerabilities/${id}/unarchive`, {});
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/default-vulnerabilities/${id}`);
    return response.data;
  },

  import: async (): Promise<ApiResponse<DefaultVulnerabilityImportResult>> => {
    const response = await api.post<ApiResponse<DefaultVulnerabilityImportResult>>('/default-vulnerabilities/import');
    return response.data;
  },
};

export const vulnerabilitiesApi = {
  getAll: (assessmentId: string, page = 0, size = 100, sort = 'order,asc') =>
    api.get(`/assessments/${assessmentId}/vulnerabilities`, { params: { page, size, sort } })
       .then(r => r.data as PagedApiResponse<Vulnerability[]>),

  /** SLA-aware per-severity summary across all vulnerabilities the caller may read (server-scoped).
   *  Takes the same narrowing filters as `searchGlobal` so a summary rendered above the list tracks
   *  it; there is no `includeClosed` — each bucket defines its own open/closed condition. */
  getSummary: (params?: {
    applicationId?: string; organizationIds?: string[]; subOrganizationId?: string; assessmentId?: string;
    severities?: string[]; statuses?: string[]; search?: string; openedFrom?: string; openedTo?: string;
  }) => {
    const { statuses, severities, organizationIds, ...rest } = params ?? {};
    const q: Record<string, string> = {};
    for (const [k, v] of Object.entries(rest)) if (v) q[k] = v as string;
    if (statuses?.length) q.statuses = statuses.join(',');
    if (severities?.length) q.severities = severities.join(',');
    if (organizationIds?.length) q.organizationIds = organizationIds.join(',');
    return api.get('/vulnerabilities/summary', { params: q })
              .then(r => r.data as ApiResponse<VulnerabilityTrendSummary>);
  },

  /** Scoped, paginated, filterable global vulnerabilities list (server-side; no per-assessment fan-out). */
  searchGlobal: (params: {
    page?: number; size?: number; sort?: string; search?: string; severities?: string[];
    organizationIds?: string[]; applicationId?: string; assessmentId?: string;
    statuses?: string[]; includeClosed?: boolean; openedFrom?: string; openedTo?: string;
  }): Promise<PagedApiResponse<VulnerabilityListItem[]>> => {
    const q: Record<string, string | number | boolean> = { page: params.page ?? 0, size: params.size ?? 15 };
    if (params.sort) q.sort = params.sort;
    if (params.search) q.search = params.search;
    if (params.severities?.length) q.severities = params.severities.join(',');
    if (params.organizationIds?.length) q.organizationIds = params.organizationIds.join(',');
    if (params.applicationId) q.applicationId = params.applicationId;
    if (params.assessmentId) q.assessmentId = params.assessmentId;
    if (params.statuses?.length) q.statuses = params.statuses.join(',');
    if (params.includeClosed) q.includeClosed = true;
    if (params.openedFrom) q.openedFrom = params.openedFrom;
    if (params.openedTo) q.openedTo = params.openedTo;
    return api.get('/vulnerabilities', { params: q }).then(r => r.data as PagedApiResponse<VulnerabilityListItem[]>);
  },

  /** CSV of the whole filtered global list (server-side, unpaginated) — same filters as searchGlobal. */
  exportGlobalCsv: (params: {
    sort?: string; search?: string; severities?: string[];
    organizationIds?: string[]; applicationId?: string; assessmentId?: string;
    statuses?: string[]; includeClosed?: boolean; openedFrom?: string; openedTo?: string;
  }): Promise<Blob> => {
    const q: Record<string, string | boolean> = {};
    if (params.sort) q.sort = params.sort;
    if (params.search) q.search = params.search;
    if (params.severities?.length) q.severities = params.severities.join(',');
    if (params.organizationIds?.length) q.organizationIds = params.organizationIds.join(',');
    if (params.applicationId) q.applicationId = params.applicationId;
    if (params.assessmentId) q.assessmentId = params.assessmentId;
    if (params.statuses?.length) q.statuses = params.statuses.join(',');
    if (params.includeClosed) q.includeClosed = true;
    if (params.openedFrom) q.openedFrom = params.openedFrom;
    if (params.openedTo) q.openedTo = params.openedTo;
    return api.get('/vulnerabilities/export.csv', { params: q, responseType: 'blob' })
              .then(r => r.data as Blob);
  },

  /** Composed remediation-stage view: every configured stage in order, with completions.
   *  The terminal (last) stage reflects the vulnerability's own closedAt. */
  getStageCompletions: (assessmentId: string, id: string) =>
    api.get(`/assessments/${assessmentId}/vulnerabilities/${id}/stage-completions`)
       .then(r => r.data as ApiResponse<VulnerabilityStageCompletion[]>),

  /** Mark the fix verified in a stage. Terminal stage closes the vulnerability; earlier stages
   *  record a completion and leave it open. Returns the refreshed composed view. */
  recordStageCompletion: (assessmentId: string, id: string, stageId: string) =>
    api.put(`/assessments/${assessmentId}/vulnerabilities/${id}/stage-completions/${stageId}`)
       .then(r => r.data as ApiResponse<VulnerabilityStageCompletion[]>),

  /** Clear a stage completion. Clearing the terminal stage reopens the vulnerability. */
  clearStageCompletion: (assessmentId: string, id: string, stageId: string) =>
    api.delete(`/assessments/${assessmentId}/vulnerabilities/${id}/stage-completions/${stageId}`)
       .then(r => r.data as ApiResponse<VulnerabilityStageCompletion[]>),

  /** Resolve one vulnerability by id alone (scoped), for the notification deep-link that carries no assessment id. */
  getByIdGlobal: (id: string) =>
    api.get(`/vulnerabilities/${id}`).then(r => r.data as ApiResponse<VulnerabilityListItem>),

  getById: (assessmentId: string, id: string) =>
    api.get(`/assessments/${assessmentId}/vulnerabilities/${id}`)
       .then(r => r.data as ApiResponse<Vulnerability>),

  create: (assessmentId: string, data: CreateVulnerabilityRequest) =>
    api.post(`/assessments/${assessmentId}/vulnerabilities`, data)
       .then(r => r.data as ApiResponse<Vulnerability>),

  update: (assessmentId: string, id: string, data: UpdateVulnerabilityRequest) =>
    api.patch(`/assessments/${assessmentId}/vulnerabilities/${id}`, data)
       .then(r => r.data as ApiResponse<Vulnerability>),

  delete: (assessmentId: string, id: string) =>
    api.delete(`/assessments/${assessmentId}/vulnerabilities/${id}`)
       .then(r => r.data as ApiResponse<void>),

  getSubscribers: (assessmentId: string, vulnId: string) =>
    api.get(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/subscribers`)
       .then(r => r.data as ApiResponse<string[]>),

  addSubscriber: (assessmentId: string, vulnId: string, username: string) =>
    api.post(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/subscribers/${encodeURIComponent(username)}`)
       .then(r => r.data as ApiResponse<string[]>),

  removeSubscriber: (assessmentId: string, vulnId: string, username: string) =>
    api.delete(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/subscribers/${encodeURIComponent(username)}`)
       .then(r => r.data as ApiResponse<string[]>),

  addComment: (assessmentId: string, vulnId: string, content: string) =>
    api.post(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/comments`, { content })
       .then(r => r.data as ApiResponse<VulnerabilityComment[]>),

  deleteComment: (assessmentId: string, vulnId: string, commentId: string) =>
    api.delete(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/comments/${commentId}`)
       .then(r => r.data as ApiResponse<VulnerabilityComment[]>),

  updateStatus: (assessmentId: string, vulnId: string, status: string) =>
    api.patch(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/status`, { status })
       .then(r => r.data as ApiResponse<Vulnerability>),

  updateFields: (assessmentId: string, vulnId: string, fields: UpdateVulnerabilityRequest) =>
    api.patch(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/fields`, fields)
       .then(r => r.data as ApiResponse<Vulnerability>),

  /** Assign, reassign (userId) or clear (null) the person accountable for the fix.
   *  Internal users only — the backend 403s an external caller regardless of permissions. */
  updateRemediationOwner: (assessmentId: string, vulnId: string, userId: string | null) =>
    api.patch(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/remediation-owner`, { userId })
       .then(r => r.data as ApiResponse<Vulnerability>),

  // ── Exception workflow ────────────────────────────────────────────────────

  updateException: (assessmentId: string, vulnId: string, data: UpdateVulnerabilityExceptionRequest) =>
    api.patch(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/exception`, data)
       .then(r => r.data as ApiResponse<Vulnerability>),

  prepareExceptionFile: (assessmentId: string, vulnId: string, fileName: string, contentType: string, fileSize: number) =>
    api.post(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/exception-files/prepare`,
             { fileName, contentType, fileSize })
       .then(r => r.data as ApiResponse<{ fileId: string; uploadUrl: string; storageKey: string }>),

  confirmExceptionFile: (assessmentId: string, vulnId: string, fileId: string, fileName: string, contentType: string, fileSize: number) =>
    api.post(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/exception-files`,
             { fileId, fileName, contentType, fileSize })
       .then(r => r.data as ApiResponse<AssessmentFile>),

  /** Origin-relative URL the browser streams the exception file from. */
  getExceptionFileDownloadUrl: (assessmentId: string, vulnId: string, fileId: string): string =>
    fileUrls.exceptionFile(assessmentId, vulnId, fileId),

  deleteExceptionFile: (assessmentId: string, vulnId: string, fileId: string) =>
    api.delete(`/assessments/${assessmentId}/vulnerabilities/${vulnId}/exception-files/${fileId}`)
       .then(r => r.data as ApiResponse<void>),
};

export const inlineImagesApi = {
  upload: async (
    assessmentId: string,
    file: File
  ): Promise<ApiResponse<{ id: string; url: string }>> => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await api.post(
      `/assessments/${assessmentId}/inline-images`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    );
    return response.data;
  },
};

export const entityFieldsApi = {
  getConfig: async (scope: FieldScope): Promise<ApiResponse<EntityFieldConfig>> => {
    const response = await api.get<ApiResponse<EntityFieldConfig>>(`/entity-fields/${scope}`);
    return response.data;
  },

  updateConfig: async (scope: FieldScope, fieldDefinitions: UserDefinedField[]): Promise<ApiResponse<EntityFieldConfig>> => {
    const response = await api.put<ApiResponse<EntityFieldConfig>>(`/entity-fields/${scope}`, { fieldDefinitions });
        return response.data;
  },
};
export const regionConfigApi = {
  getRegions: async (): Promise<string[]> => {
    const response = await api.get<ApiResponse<string[]>>('/config/regions');
    return response.data.data ?? [];
  },

  updateRegions: async (regions: string[]): Promise<string[]> => {
    const response = await api.put<ApiResponse<string[]>>('/config/regions', regions);
    return response.data.data ?? [];
  },
};

export const peerReviewsApi = {
  getQueue: async (page = 0, size = 20, sort = ''): Promise<PagedApiResponse<PeerReview[]>> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (sort) params.set('sort', sort);
    const response = await api.get<PagedApiResponse<PeerReview[]>>(
      `/peer-reviews/queue?${params.toString()}`
    );
    return response.data;
  },

  getByAssessment: async (assessmentId: string): Promise<ApiResponse<PeerReview[]>> => {
    const response = await api.get<ApiResponse<PeerReview[]>>(
      `/assessments/${assessmentId}/peer-reviews`
    );
    return response.data;
  },

  getById: async (reviewId: string): Promise<ApiResponse<PeerReview>> => {
    const response = await api.get<ApiResponse<PeerReview>>(`/peer-reviews/${reviewId}`);
    return response.data;
  },

  submit: async (assessmentId: string): Promise<ApiResponse<PeerReview>> => {
    const response = await api.post<ApiResponse<PeerReview>>(
      `/assessments/${assessmentId}/peer-reviews/submit`
    );
    return response.data;
  },

  start: async (reviewId: string): Promise<ApiResponse<PeerReview>> => {
    const response = await api.post<ApiResponse<PeerReview>>(`/peer-reviews/${reviewId}/start`);
    return response.data;
  },

  update: async (reviewId: string, data: UpdatePeerReviewRequest): Promise<ApiResponse<PeerReview>> => {
    const response = await api.put<ApiResponse<PeerReview>>(`/peer-reviews/${reviewId}`, data);
    return response.data;
  },

  complete: async (reviewId: string): Promise<ApiResponse<PeerReview>> => {
    const response = await api.post<ApiResponse<PeerReview>>(`/peer-reviews/${reviewId}/complete`);
    return response.data;
  },

  /**
   * Take or refresh the caller's lock on one editable region. `fieldId` is a composite key
   * built by the page (see `lockKey` in PeerReviewEditor), so it is encoded rather than
   * interpolated raw. Rejects with 409 when another reviewer is in that region.
   */
  acquireLock: async (reviewId: string, fieldId: string): Promise<void> => {
    await api.post(`/peer-reviews/${reviewId}/fields/${encodeURIComponent(fieldId)}/lock`);
  },

  releaseLock: async (reviewId: string, fieldId: string): Promise<void> => {
    await api.delete(`/peer-reviews/${reviewId}/fields/${encodeURIComponent(fieldId)}/lock`);
  },

  accept: async (reviewId: string, acceptedChanges: AcceptPeerReviewRequest): Promise<ApiResponse<PeerReview>> => {
    const response = await api.post<ApiResponse<PeerReview>>(
      `/peer-reviews/${reviewId}/accept`,
      acceptedChanges
    );
    return response.data;
  },

  reject: async (reviewId: string): Promise<ApiResponse<PeerReview>> => {
    const response = await api.post<ApiResponse<PeerReview>>(`/peer-reviews/${reviewId}/reject`);
    return response.data;
  },
};

/** @mention candidates for the editor's autocomplete — see UserController#getMentionableUsers. */
export const mentionsApi = {
  getCandidates: async (
    search: string,
    context?: { vulnerabilityId?: string; applicationId?: string }
  ): Promise<ApiResponse<MentionableUser[]>> => {
    const params: Record<string, string> = {};
    if (search) params.search = search;
    if (context?.vulnerabilityId) params.vulnerabilityId = context.vulnerabilityId;
    if (context?.applicationId) params.applicationId = context.applicationId;
    const response = await api.get<ApiResponse<MentionableUser[]>>('/users/mentionable', { params });
    return response.data;
  },
};

export const workflowConfigApi = {
  getConfig: async (): Promise<ApiResponse<AssessmentWorkflowConfig>> => {
    const response = await api.get<ApiResponse<AssessmentWorkflowConfig>>('/config/assessment-workflow');
    return response.data;
  },

  updateConfig: async (config: AssessmentWorkflowConfig): Promise<ApiResponse<AssessmentWorkflowConfig>> => {
    const response = await api.put<ApiResponse<AssessmentWorkflowConfig>>('/config/assessment-workflow', config);
    return response.data;
  },
};

export const reportsApi = {
  generate: async (assessmentId: string): Promise<ApiResponse<void>> => {
    const response = await api.post<ApiResponse<void>>(`/reports/${assessmentId}/generate`);
    return response.data;
  },

  uploadReport: async (assessmentId: string, file: File): Promise<ApiResponse<void>> => {
    const formData = new FormData();
    formData.append('file', file);

    const response = await api.post<ApiResponse<void>>(
      `/reports/${assessmentId}/upload`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      }
    );
    return response.data;
  },

  /** Origin-relative URL the browser streams the generated report from. */
  getDownloadUrl: (assessmentId: string, type?: string): string =>
    fileUrls.report(assessmentId, type ?? 'DOCX'),

  getDocuments: async (assessmentId: string): Promise<ApiResponse<ReportDocuments>> => {
    const response = await api.get<ApiResponse<ReportDocuments>>(`/reports/${assessmentId}/documents`);
    return response.data;
  },

  getPdf: async (assessmentId: string): Promise<Blob> => {
    const response = await api.get(`/reports/${assessmentId}/pdf`, { responseType: 'blob' });
    return response.data as Blob;
  },
};

export const checklistTemplatesApi = {
  getAll: async (assessmentTypeId?: string): Promise<ApiResponse<ChecklistTemplate[]>> => {
    const url = assessmentTypeId
      ? `/checklist-templates?assessmentTypeId=${assessmentTypeId}`
      : '/checklist-templates';
    const response = await api.get<ApiResponse<ChecklistTemplate[]>>(url);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<ChecklistTemplate>> => {
    const response = await api.get<ApiResponse<ChecklistTemplate>>(`/checklist-templates/${id}`);
    return response.data;
  },

  create: async (data: CreateChecklistTemplateRequest): Promise<ApiResponse<ChecklistTemplate>> => {
    const response = await api.post<ApiResponse<ChecklistTemplate>>('/checklist-templates', data);
    return response.data;
  },

  update: async (id: string, data: UpdateChecklistTemplateRequest): Promise<ApiResponse<ChecklistTemplate>> => {
    const response = await api.put<ApiResponse<ChecklistTemplate>>(`/checklist-templates/${id}`, data);
    return response.data;
  },

  delete: async (id: string): Promise<void> => {
    await api.delete(`/checklist-templates/${id}`);
  },
};

export const assessmentChecklistsApi = {
  getByAssessment: async (assessmentId: string): Promise<ApiResponse<AssessmentChecklist[]>> => {
    const response = await api.get<ApiResponse<AssessmentChecklist[]>>(
      `/assessments/${assessmentId}/checklists`
    );
    return response.data;
  },

  add: async (assessmentId: string, data: AddAssessmentChecklistRequest): Promise<ApiResponse<AssessmentChecklist>> => {
    const response = await api.post<ApiResponse<AssessmentChecklist>>(
      `/assessments/${assessmentId}/checklists`,
      data
    );
    return response.data;
  },

  update: async (
    assessmentId: string,
    checklistId: string,
    data: UpdateAssessmentChecklistRequest
  ): Promise<ApiResponse<AssessmentChecklist>> => {
    const response = await api.put<ApiResponse<AssessmentChecklist>>(
      `/assessments/${assessmentId}/checklists/${checklistId}`,
      data
    );
    return response.data;
  },

  remove: async (assessmentId: string, checklistId: string): Promise<void> => {
    await api.delete(`/assessments/${assessmentId}/checklists/${checklistId}`);
  },
};

export const surveyTemplatesApi = {
  getAll: async (active?: boolean): Promise<ApiResponse<SurveyTemplate[]>> => {
    const url = active ? '/survey-templates?active=true' : '/survey-templates';
    const response = await api.get<ApiResponse<SurveyTemplate[]>>(url);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<SurveyTemplate>> => {
    const response = await api.get<ApiResponse<SurveyTemplate>>(`/survey-templates/${id}`);
    return response.data;
  },

  create: async (data: CreateSurveyTemplateRequest): Promise<ApiResponse<SurveyTemplate>> => {
    const response = await api.post<ApiResponse<SurveyTemplate>>('/survey-templates', data);
    return response.data;
  },

  update: async (id: string, data: UpdateSurveyTemplateRequest): Promise<ApiResponse<SurveyTemplate>> => {
    const response = await api.put<ApiResponse<SurveyTemplate>>(`/survey-templates/${id}`, data);
    return response.data;
  },

  delete: async (id: string): Promise<void> => {
    await api.delete(`/survey-templates/${id}`);
  },
};

export const assessmentSurveysApi = {
  getByAssessment: async (assessmentId: string): Promise<ApiResponse<AssessmentSurvey[]>> => {
    const response = await api.get<ApiResponse<AssessmentSurvey[]>>(
      `/assessments/${assessmentId}/surveys`
    );
    return response.data;
  },

  add: async (assessmentId: string, data: AddAssessmentSurveyRequest): Promise<ApiResponse<AssessmentSurvey>> => {
    const response = await api.post<ApiResponse<AssessmentSurvey>>(
      `/assessments/${assessmentId}/surveys`,
      data
    );
    return response.data;
  },

  update: async (
    assessmentId: string,
    surveyId: string,
    data: UpdateAssessmentSurveyRequest
  ): Promise<ApiResponse<AssessmentSurvey>> => {
    const response = await api.put<ApiResponse<AssessmentSurvey>>(
      `/assessments/${assessmentId}/surveys/${surveyId}`,
      data
    );
    return response.data;
  },

  remove: async (assessmentId: string, surveyId: string): Promise<void> => {
    await api.delete(`/assessments/${assessmentId}/surveys/${surveyId}`);
  },
};

export const azureUsersApi = {
  // Azure (Entra ID) directory lookup for the user-create typeahead (SAML2 tenants)
  enabled: () =>
    api.get<ApiResponse<{ enabled: boolean }>>('/sso/azure/users/enabled').then(r => r.data),
  search: (query: string) =>
    api.get<ApiResponse<AzureDirectoryUser[]>>('/sso/azure/users', { params: { query } }).then(r => r.data),
};

export const ssoConfigApi = {
  getConfig: () =>
    api.get<ApiResponse<SsoConfig>>('/admin/sso-config').then(r => r.data),
  updateConfig: (data: Partial<SsoConfig>) =>
    api.put<ApiResponse<SsoConfig>>('/admin/sso-config', data).then(r => r.data),
  testSaml2: (config?: { idpMetadataUrl?: string }) =>
    api.post<ApiResponse<{ success: boolean; message: string; details?: string }>>('/admin/sso-config/test-saml2', config || {}).then(r => r.data),
  testOidc: (config?: { issuerUri?: string }) =>
    api.post<ApiResponse<{ success: boolean; message: string; details?: string }>>('/admin/sso-config/test-oidc', config || {}).then(r => r.data),
  getStatus: () =>
    api.get<ApiResponse<SsoStatus>>('/auth/sso/status').then(r => r.data),
};

export const notebookApi = {
  getTree: (appId: string) =>
    api.get<ApiResponse<NotebookNode[]>>(`/applications/${appId}/notebook`).then(r => r.data),

  search: (appId: string, params: { q?: string; createdById?: string; from?: string; to?: string }) =>
    api.get<ApiResponse<NotebookSearchResult[]>>(`/applications/${appId}/notebook/search`, { params }).then(r => r.data),

  getNode: (nodeId: string) =>
    api.get<ApiResponse<NotebookNode>>(`/notebook/nodes/${nodeId}`).then(r => r.data),

  createNode: (appId: string, data: CreateNotebookNodeRequest) =>
    api.post<ApiResponse<NotebookNode>>(`/applications/${appId}/notebook/nodes`, data).then(r => r.data),

  updateNode: (nodeId: string, data: UpdateNotebookNodeRequest) =>
    api.put<ApiResponse<NotebookNode>>(`/notebook/nodes/${nodeId}`, data).then(r => r.data),

  deleteNode: (nodeId: string) =>
    api.delete<ApiResponse<void>>(`/notebook/nodes/${nodeId}`).then(r => r.data),

  moveNode: (nodeId: string, data: MoveNotebookNodeRequest) =>
    api.put<ApiResponse<NotebookNode>>(`/notebook/nodes/${nodeId}/move`, data).then(r => r.data),

  prepareFile: (nodeId: string, data: { fileName: string; contentType: string; fileSize: number }) =>
    api.post<ApiResponse<{ fileId: string; uploadUrl: string }>>(`/notebook/nodes/${nodeId}/files/prepare`, data).then(r => r.data),

  confirmFile: (nodeId: string, data: { fileId: string; fileName: string; contentType: string; fileSize: number }) =>
    api.post<ApiResponse<NotebookAttachment>>(`/notebook/nodes/${nodeId}/files/confirm`, data).then(r => r.data),

  /** Origin-relative URL the browser streams the attachment from. */
  getFileDownloadUrl: (nodeId: string, fileId: string): string =>
    fileUrls.notebookFile(nodeId, fileId),

  deleteFile: (nodeId: string, fileId: string) =>
    api.delete<ApiResponse<void>>(`/notebook/nodes/${nodeId}/files/${fileId}`).then(r => r.data),
};

// Lightweight queue sizes for the sidebar badges — each call reuses the
// corresponding scoped list endpoint, so counts match what the page shows.
export const queueCountsApi = {
  activeAssessments: (): Promise<number> =>
    api.get('/assessments/summary')
      .then(r => (r.data?.data?.active as number | undefined) ?? 0),

  peerReviewQueue: (): Promise<number> =>
    api.get('/peer-reviews/queue', { params: { page: 0, size: 1 } })
      .then(r => r.data?.pagination?.totalElements ?? 0),

  // Open retests assigned to the current user — completed (PASSED/FAILED) and
  // CANCELLED ones are history, and other people's retests aren't your queue
  openRetests: (): Promise<number> =>
    api.get('/retests', { params: { assignedToMe: true, status: 'REQUESTED,SCHEDULED,IN_PROGRESS' } })
      .then(r => ((r.data?.data as unknown[] | undefined)?.length ?? 0)),

  remediationQueue: (): Promise<number> =>
    api.get('/remediation/queue-count')
      .then(r => (r.data?.data as number | undefined) ?? 0),

  // Findings past their SLA, summed across severities. Reuses the vulnerabilities summary
  // (already scoped server-side) rather than a dedicated count endpoint — the `pastDue`
  // bucket is exactly the "Outside SLA" figure the /vulnerabilities page shows.
  pastDueVulnerabilities: (): Promise<number> =>
    api.get('/vulnerabilities/summary')
      .then(r => Object.values((r.data?.data?.pastDue ?? {}) as Record<string, number>)
                       .reduce((sum, n) => sum + (n || 0), 0)),
};

export const remediationApi = {
  /**
   * One page of the interleaved remediation queue (vulnerabilities + retests), server-ordered and
   * scoped. The optional filters mirror the vulnerabilities list's header; org/application ones are
   * intersected with the caller's scope server-side.
   */
  getQueue: (params: {
    page?: number; size?: number; sort?: string; search?: string;
    severity?: string; organizationId?: string; applicationId?: string; assessmentId?: string;
    statuses?: string[]; type?: string;
    /** Also show retests that have already been verified (PASSED/FAILED). */
    includeCompletedRetests?: boolean;
  }): Promise<PagedApiResponse<RemediationQueueRow[]>> => {
    const q: Record<string, string | number> = { page: params.page ?? 0, size: params.size ?? 20 };
    if (params.sort) q.sort = params.sort;
    if (params.search) q.search = params.search;
    if (params.severity) q.severity = params.severity;
    if (params.organizationId) q.organizationId = params.organizationId;
    if (params.applicationId) q.applicationId = params.applicationId;
    if (params.assessmentId) q.assessmentId = params.assessmentId;
    if (params.statuses?.length) q.statuses = params.statuses.join(',');
    if (params.type) q.type = params.type;
    if (params.includeCompletedRetests) q.includeCompletedRetests = 'true';
    return api.get('/remediation/queue', { params: q }).then(r => r.data as PagedApiResponse<RemediationQueueRow[]>);
  },

  /** CSV of the whole filtered queue (server-side, unpaginated) — same filters as getQueue, plus
   *  each retest's completed date / result / verifier, which the table has no column for. */
  exportQueueCsv: (params: {
    sort?: string; search?: string;
    severity?: string; organizationId?: string; applicationId?: string; assessmentId?: string;
    statuses?: string[]; type?: string; includeCompletedRetests?: boolean;
  }): Promise<Blob> => {
    const q: Record<string, string> = {};
    if (params.sort) q.sort = params.sort;
    if (params.search) q.search = params.search;
    if (params.severity) q.severity = params.severity;
    if (params.organizationId) q.organizationId = params.organizationId;
    if (params.applicationId) q.applicationId = params.applicationId;
    if (params.assessmentId) q.assessmentId = params.assessmentId;
    if (params.statuses?.length) q.statuses = params.statuses.join(',');
    if (params.type) q.type = params.type;
    if (params.includeCompletedRetests) q.includeCompletedRetests = 'true';
    return api.get('/remediation/export.csv', { params: q, responseType: 'blob' })
              .then(r => r.data as Blob);
  },
};

export const retestApi = {
  create: (assessmentId: string, data: CreateRetestRequest) =>
    api.post(`/assessments/${assessmentId}/retests`, data)
       .then(r => r.data as ApiResponse<Retest>),

  getByAssessment: (assessmentId: string) =>
    api.get(`/assessments/${assessmentId}/retests`)
       .then(r => r.data as ApiResponse<Retest[]>),

  getAll: (params?: { assignedToMe?: boolean; status?: string }) =>
    api.get('/retests', { params })
       .then(r => r.data as ApiResponse<Retest[]>),

  getCalendar: (startDate: string, endDate: string) => {
    const start = startDate.includes('T') ? startDate : `${startDate}T00:00:00`;
    const end = endDate.includes('T') ? endDate : `${endDate}T23:59:59`;
    return api.get('/retests/calendar', { params: { startDate: start, endDate: end } })
              .then(r => r.data as ApiResponse<Retest[]>);
  },

  getById: (id: string) =>
    api.get(`/retests/${id}`)
       .then(r => r.data as ApiResponse<Retest>),

  update: (id: string, data: UpdateRetestRequest) =>
    api.patch(`/retests/${id}`, data)
       .then(r => r.data as ApiResponse<Retest>),

  complete: (id: string, data: CompleteRetestRequest) =>
    api.post(`/retests/${id}/complete`, data)
       .then(r => r.data as ApiResponse<Retest>),

  /** Calls a retest off: it moves to CANCELLED and stays on the finding's record. Open to
   *  staff and to the app owners who can request one, scoped server-side to their own apps. */
  cancel: (id: string) =>
    api.delete(`/retests/${id}`)
       .then(r => r.data as ApiResponse<void>),
};

export const emailConfigApi = {
  getConfig: () =>
    api.get<ApiResponse<EmailConfig>>('/admin/email-config').then(r => r.data),

  updateConfig: (data: UpdateEmailConfigRequest) =>
    api.put<ApiResponse<EmailConfig>>('/admin/email-config', data).then(r => r.data),

  test: (data: TestEmailRequest) =>
    api.post<ApiResponse<TestEmailResponse>>('/admin/email-config/test', data).then(r => r.data),
};

/**
 * Branding reads are public — the sign-in page needs its logo and background before
 * anyone has a session. Writes are super_admin only, enforced on the server.
 */
/**
 * Which capabilities this build includes.
 *
 * Read once per session by EditionContext — it changes only when the deployed
 * artifact changes, so there is nothing to poll for.
 */
export const editionApi = {
  get: () => api.get<ApiResponse<EditionStatus>>('/edition').then(r => r.data),
};

export const brandingApi = {
  get: () => api.get<ApiResponse<Branding>>('/branding').then(r => r.data),

  /** URL an asset id is served from. A fresh id per upload makes this self-busting. */
  assetUrl: (assetId: string) => `/api/v1/branding/assets/${assetId}`,

  uploadSlot: async (slot: BrandingAssetSlot, file: File) => {
    const form = new FormData();
    form.append('file', file);
    const response = await api.post<ApiResponse<{ assetId: string }>>(
      `/admin/branding/slots/${slot}`, form,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    );
    return response.data;
  },

  clearSlot: (slot: BrandingAssetSlot) =>
    api.delete<ApiResponse<Branding>>(`/admin/branding/slots/${slot}`).then(r => r.data),

  updateSizes: (data: UpdateBrandingSizesRequest) =>
    api.put<ApiResponse<Branding>>('/admin/branding/sizes', data).then(r => r.data),

  addBackground: async (file: File) => {
    const form = new FormData();
    form.append('file', file);
    const response = await api.post<ApiResponse<{ assetId: string }>>(
      '/admin/branding/backgrounds', form,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    );
    return response.data;
  },

  removeBackground: (assetId: string) =>
    api.delete<ApiResponse<Branding>>(`/admin/branding/backgrounds/${assetId}`).then(r => r.data),
};

export const emailNotificationConfigApi = {
  getConfig: () =>
    api.get<ApiResponse<EmailNotificationConfig>>('/admin/email-notification-config')
       .then(r => r.data),

  updateConfig: (data: UpdateEmailNotificationConfigRequest) =>
    api.put<ApiResponse<EmailNotificationConfig>>('/admin/email-notification-config', data)
       .then(r => r.data),
};

export const notificationPreferencesApi = {
  get: () =>
    api.get<ApiResponse<NotificationPreference[]>>('/users/me/notification-preferences')
       .then(r => r.data),

  update: (data: UpdateNotificationPreferencesRequest) =>
    api.put<ApiResponse<NotificationPreference[]>>('/users/me/notification-preferences', data)
       .then(r => r.data),
};

export const inboundEmailConfigApi = {
  getConfig: () =>
    api.get<ApiResponse<InboundEmailConfig>>('/admin/inbound-email-config').then(r => r.data),

  updateConfig: (data: UpdateInboundEmailConfigRequest) =>
    api.put<ApiResponse<InboundEmailConfig>>('/admin/inbound-email-config', data).then(r => r.data),

  test: () =>
    api.post<ApiResponse<TestEmailResponse>>('/admin/inbound-email-config/test').then(r => r.data),
};

export const aiConfigApi = {
  getProviders: () =>
    api.get<ApiResponse<AiProviderConfig[]>>('/admin/ai-config').then(r => r.data),

  createProvider: (data: SaveAiProviderConfigRequest) =>
    api.post<ApiResponse<AiProviderConfig>>('/admin/ai-config', data).then(r => r.data),

  updateProvider: (id: string, data: SaveAiProviderConfigRequest) =>
    api.put<ApiResponse<AiProviderConfig>>(`/admin/ai-config/${id}`, data).then(r => r.data),

  deleteProvider: (id: string) =>
    api.delete<ApiResponse<void>>(`/admin/ai-config/${id}`).then(r => r.data),

  test: (data: TestAiProviderRequest) =>
    api.post<ApiResponse<TestAiProviderResponse>>('/admin/ai-config/test', data).then(r => r.data),

  getPrompts: () =>
    api.get<ApiResponse<AiPromptTemplate[]>>('/admin/ai-config/prompts').then(r => r.data),

  createPrompt: (data: SaveAiPromptTemplateRequest) =>
    api.post<ApiResponse<AiPromptTemplate>>('/admin/ai-config/prompts', data).then(r => r.data),

  updatePrompt: (id: string, data: SaveAiPromptTemplateRequest) =>
    api.put<ApiResponse<AiPromptTemplate>>(`/admin/ai-config/prompts/${id}`, data).then(r => r.data),

  deletePrompt: (id: string) =>
    api.delete<ApiResponse<void>>(`/admin/ai-config/prompts/${id}`).then(r => r.data),

  getWebSearchConfig: () =>
    api.get<ApiResponse<WebSearchConfig>>('/admin/ai-config/web-search').then(r => r.data),

  updateWebSearchConfig: (data: UpdateWebSearchConfigRequest) =>
    api.put<ApiResponse<WebSearchConfig>>('/admin/ai-config/web-search', data).then(r => r.data),

  getAnonymizationConfig: () =>
    api.get<ApiResponse<AiAnonymizationConfig>>('/admin/ai-config/anonymization').then(r => r.data),

  updateAnonymizationConfig: (data: UpdateAiAnonymizationConfigRequest) =>
    api.put<ApiResponse<AiAnonymizationConfig>>('/admin/ai-config/anonymization', data).then(r => r.data),

  getLoggingConfig: () =>
    api.get<ApiResponse<AiLogConfig>>('/admin/ai-config/logging').then(r => r.data),

  updateLoggingConfig: (data: UpdateAiLogConfigRequest) =>
    api.put<ApiResponse<AiLogConfig>>('/admin/ai-config/logging', data).then(r => r.data),

  /** Daily token totals. Defaults to the start of last month through today. */
  getTokenUsage: (from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    const qs = params.toString();
    return api
      .get<ApiResponse<AiTokenUsageDay[]>>(`/admin/ai-config/usage${qs ? `?${qs}` : ''}`)
      .then(r => r.data);
  },
};

export const auditLogsApi = {
  getAiLogs: (page: number, size: number, username?: string, action?: string, sort?: string) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (username) params.set('username', username);
    if (action) params.set('action', action);
    if (sort) params.set('sort', sort);
    return api.get<PagedApiResponse<AiRequestLog[]>>(`/admin/logs/ai?${params.toString()}`).then(r => r.data);
  },

  getAiLog: (id: string) =>
    api.get<ApiResponse<AiRequestLog>>(`/admin/logs/ai/${id}`).then(r => r.data),

  /** Retests verified in a window — `from`/`to` are ISO dates, inclusive. */
  getRetestLog: (params: {
    page?: number; size?: number; sort?: string;
    from?: string; to?: string; result?: 'PASS' | 'FAIL' | '';
  }) => {
    const q = new URLSearchParams({ page: String(params.page ?? 0), size: String(params.size ?? 25) });
    if (params.sort) q.set('sort', params.sort);
    if (params.from) q.set('from', params.from);
    if (params.to) q.set('to', params.to);
    if (params.result) q.set('result', params.result);
    return api.get<PagedApiResponse<RetestCompletionLog[]>>(`/admin/logs/retests?${q}`).then(r => r.data);
  },

  getRetestSummary: (from?: string, to?: string) => {
    const q = new URLSearchParams();
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    return api.get<ApiResponse<RetestActivitySummary>>(`/admin/logs/retests/summary?${q}`).then(r => r.data);
  },
};

export const aiApi = {
  getPrompts: (scope: AiPromptScope) =>
    api.get<ApiResponse<AiPromptSummary[]>>(`/ai/prompts?scope=${scope}`).then(r => r.data),

  executePrompt: (data: ExecuteAiPromptRequest) =>
    api.post<ApiResponse<AiGenerationResponse>>('/ai/execute-prompt', data).then(r => r.data),

  ask: (data: AskAiRequest) =>
    api.post<ApiResponse<AiGenerationResponse>>('/ai/ask', data).then(r => r.data),

  suggestTitle: (data: SuggestAiTitleRequest) =>
    api.post<ApiResponse<AiGenerationResponse>>('/ai/suggest-title', data).then(r => r.data),
};

export const notificationsApi = {
  getAll: () =>
    api.get<ApiResponse<Notification[]>>('/notifications').then(r => r.data),

  getUnreadCount: () =>
    api.get<ApiResponse<number>>('/notifications/unread-count').then(r => r.data),

  markRead: (id: string) =>
    api.patch<ApiResponse<Notification>>(`/notifications/${id}/read`).then(r => r.data),

  markAllRead: () =>
    api.patch<ApiResponse<void>>('/notifications/read-all').then(r => r.data),

  delete: (id: string) =>
    api.delete<ApiResponse<void>>(`/notifications/${id}`).then(r => r.data),

  deleteAll: () =>
    api.delete<ApiResponse<void>>('/notifications').then(r => r.data),

  /** @mentions plus replies on threads the user follows — the Mentions dashboard feed. */
  getMentions: () =>
    api.get<ApiResponse<Notification[]>>('/notifications/mentions').then(r => r.data),

  getMentionsUnreadCount: () =>
    api.get<ApiResponse<number>>('/notifications/mentions/unread-count').then(r => r.data),

  /**
   * Clears the mentions feed only — assignments and retests stay in the bell. Pass a
   * target type to clear one section of the dashboard; 'OTHER' clears the rows that
   * carry no target (recorded before that context existed).
   */
  deleteAllMentions: (targetType?: NotificationTargetType | 'OTHER') =>
    api
      .delete<ApiResponse<void>>('/notifications/mentions', {
        params: targetType ? { targetType: targetType === 'OTHER' ? 'NONE' : targetType } : undefined,
      })
      .then(r => r.data),
};

export const applicationIdConfigApi = {
  getConfig: (): Promise<ApiResponse<ApplicationIdConfig>> => {
    return api.get<ApiResponse<ApplicationIdConfig>>('/admin/application-id-config').then(r => r.data);
  },

  updateConfig: (config: Partial<ApplicationIdConfig>): Promise<ApiResponse<ApplicationIdConfig>> => {
    return api.put<ApiResponse<ApplicationIdConfig>>('/admin/application-id-config', config).then(r => r.data);
  },

  getNextId: (): Promise<ApiResponse<string>> => {
    return api.get<ApiResponse<string>>('/admin/application-id-config/next').then(r => r.data);
  },

  getPreviewNext: (count = 5): Promise<ApiResponse<string[]>> => {
    return api.get<ApiResponse<string[]>>(`/admin/application-id-config/preview?count=${count}`).then(r => r.data);
  },
};

export default api;

/**
 * App Store administration. The mask sentinel for password config values —
 * echoing it back leaves the stored secret unchanged.
 */
export const EXTENSION_SECRET_MASK = '********';

export const extensionsApi = {
  getAll: async (): Promise<ApiResponse<Extension[]>> => {
    const response = await api.get<ApiResponse<Extension[]>>('/admin/extensions');
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<Extension>> => {
    const response = await api.get<ApiResponse<Extension>>(`/admin/extensions/${id}`);
    return response.data;
  },

  getLogs: async (id: string): Promise<ApiResponse<ExtensionLog[]>> => {
    const response = await api.get<ApiResponse<ExtensionLog[]>>(`/admin/extensions/${id}/logs`);
    return response.data;
  },

  install: async (file: File): Promise<ApiResponse<Extension>> => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await api.post<ApiResponse<Extension>>('/admin/extensions/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  },

  upgrade: async (id: string, file: File): Promise<ApiResponse<Extension>> => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await api.post<ApiResponse<Extension>>(
      `/admin/extensions/${id}/upgrade`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    );
    return response.data;
  },

  update: async (id: string, data: UpdateExtensionRequest): Promise<ApiResponse<Extension>> => {
    const response = await api.put<ApiResponse<Extension>>(`/admin/extensions/${id}`, data);
    return response.data;
  },

  updateConfig: async (id: string, values: Record<string, string>): Promise<ApiResponse<Extension>> => {
    const response = await api.put<ApiResponse<Extension>>(`/admin/extensions/${id}/config`, { values });
    return response.data;
  },

  uninstall: async (id: string): Promise<ApiResponse<void>> => {
    const response = await api.delete<ApiResponse<void>>(`/admin/extensions/${id}`);
    return response.data;
  },
};

/** Looks up applications held in an external system of record via inventory extensions. */
export const applicationInventoryApi = {
  search: async (params: { applicationId?: string; name?: string }): Promise<ApiResponse<ExternalApplication[]>> => {
    const response = await api.get<ApiResponse<ExternalApplication[]>>(
      '/applications/inventory-search',
      { params }
    );
    return response.data;
  },
};

/**
 * Public service status. `version` is the release tag stamped into the backend
 * image by the release workflow (APP_VERSION), or "dev" outside a release build.
 */
export interface ServiceStatus {
  status: string;
  version: string;
  startedAt: string;
  uptimeSeconds: number;
  uptime: string;
}

export const statusApi = {
  get: async (): Promise<ServiceStatus> => {
    const response = await api.get<ServiceStatus>('/status');
    return response.data;
  },
};

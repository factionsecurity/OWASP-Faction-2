import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Edit2, Trash2, Plus } from 'lucide-react';
import { organizationsApi, entityFieldsApi } from '../api';
import type { Organization, CreateOrganizationRequest, UserDefinedField } from '../types';
import RichTextEditor from '../components/RichTextEditor';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import Page from '../components/Page';
import {
  Modal,
  Button,
  IconButton,
  ActionButtons,
  FormGroup,
  FormLabel,
  Input,
  Select,
  Textarea,
  ErrorMessage,
} from '../components';
import './Organizations.css';

export default function Organizations() {
  const navigate = useNavigate();
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [searchQuery, setSearchQuery] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);

  const [formData, setFormData] = useState({
    name: '',
    description: '',
  });

  const [fieldDefinitions, setFieldDefinitions] = useState<UserDefinedField[]>([]);
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});

  // Get user permissions
  const user = JSON.parse(localStorage.getItem('user') || '{}');
  const authorities = user.authorities || [];
  const canCreate = authorities.includes('super_admin') || authorities.includes('organizations:create:all');
  const canEdit = authorities.includes('super_admin') || authorities.includes('organizations:edit:all') || authorities.includes('organizations:read:owned');
  const canDelete = authorities.includes('super_admin') || authorities.includes('organizations:delete:all');

  useEffect(() => {
    loadOrganizations();
    loadFieldDefinitions();
  }, [pagination.page, pagination.pageSize, searchQuery, sort]);

  const loadOrganizations = async () => {
    try {
      setLoading(true);
      const response = await organizationsApi.getAll(
        pagination.page, pagination.pageSize, searchQuery, sortParam(sort));
      if (response.data) {
        setOrganizations(response.data);
        setPagination({
          page: response.pagination?.page || 0,
          pageSize: response.pagination?.size || 10,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        });
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load organizations');
    } finally {
      setLoading(false);
    }
  };

  const loadFieldDefinitions = async () => {
    try {
      const response = await entityFieldsApi.getConfig('ORGANIZATION');
      if (response.data) {
        const sorted = [...(response.data.fieldDefinitions || [])].sort(
          (a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0)
        );
        setFieldDefinitions(sorted);
      }
    } catch (err: any) {
      console.error('Failed to load organization field definitions:', err);
    }
  };

  const handleCreate = () => {
    setFormData({
      name: '',
      description: '',
    });
    setFieldValues({});
    setError('');
    setShowModal(true);
  };

  const handleEdit = (organization: Organization) => {
    navigate(`/organizations/${organization.id}/edit`);
  };

  const handleDelete = async (organizationId: string) => {
    if (!confirm('Are you sure you want to delete this organization?')) return;

    try {
      await organizationsApi.delete(organizationId);
      await loadOrganizations();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to delete organization');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    try {
      const createData: CreateOrganizationRequest = {
        name: formData.name,
        description: formData.description,
        fieldValues: Object.keys(fieldValues).length > 0 ? fieldValues : undefined,
      };
      await organizationsApi.create(createData);

      setShowModal(false);
      await loadOrganizations();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save organization');
    }
  };

  const handlePageChange = useCallback((page: number) => {
    setPagination((prev) => ({ ...prev, page }));
  }, []);

  const handlePageSizeChange = useCallback((pageSize: number) => {
    setPagination((prev) => ({ ...prev, page: 0, pageSize }));
  }, []);

  const handleSearchChange = useCallback((search: string) => {
    setSearchQuery(search);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  // Re-sorting reshuffles the whole result set, so the current page number is
  // meaningless afterwards — go back to the first page.
  const handleSortChange = useCallback((next: SortState | null) => {
    setSort(next);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  const columns: Column<Organization>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      accessor: 'name',
    },
    {
      header: 'Description',
      sortKey: 'description',
      accessor: 'description',
    },
    {
      header: 'Actions',
      width: '120px',
      render: (organization) => (
        <ActionButtons>
          {canEdit && (
            <IconButton
              icon={Edit2}
              onClick={() => handleEdit(organization)}
              title="Edit"
              variant="edit"
            />
          )}
          {canDelete && (
            <IconButton
              icon={Trash2}
              onClick={() => handleDelete(organization.id)}
              title="Delete"
              variant="delete"
            />
          )}
        </ActionButtons>
      ),
    },
  ];

  return (
    <Page className="organizations-page">
      <div className="page-header">
        <div />
        {canCreate && (
          <Button onClick={handleCreate} icon={Plus}>
            Create Organization
          </Button>
        )}
      </div>

      <DataTable
        columns={columns}
        data={organizations}
        loading={loading}
        pagination={pagination}
        onPageChange={handlePageChange}
        onPageSizeChange={handlePageSizeChange}
        onSearchChange={handleSearchChange}
        searchPlaceholder="Search organizations"
        emptyMessage="No organizations found"
        idAccessor="id"
        sort={sort}
        onSortChange={handleSortChange}
      />

      {/* Create/Edit Modal */}
      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title="Create Organization"
        closeOnOverlayClick={false}
      >
        <form onSubmit={handleSubmit} className="organization-form">
            {error && <ErrorMessage>{error}</ErrorMessage>}

            <FormGroup>
              <FormLabel htmlFor="name" required>
                Name
              </FormLabel>
              <Input
                id="name"
                type="text"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                placeholder="Organization name"
                required
              />
            </FormGroup>

            <FormGroup>
              <FormLabel htmlFor="description">Description</FormLabel>
              <Textarea
                id="description"
                value={formData.description}
                onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                placeholder="Organization description"
                rows={4}
              />
            </FormGroup>

            {fieldDefinitions.length > 0 && (
              <div style={{ borderTop: '1px solid var(--color-border)', paddingTop: '1rem', marginTop: '0.5rem' }}>
                <div style={{ fontWeight: 600, marginBottom: '0.75rem', fontSize: '0.9375rem' }}>Additional Information</div>
                {(() => {
                  const regularFields = fieldDefinitions.filter((f) => f.fieldType !== 'RICH_TEXT');
                  const richTextFields = fieldDefinitions.filter((f) => f.fieldType === 'RICH_TEXT');
                  return (
                    <>
                      {regularFields.length > 0 && (
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem' }}>
                          {regularFields.map((field) => (
                            <FormGroup key={field.id}>
                              <FormLabel>
                                {field.displayName}
                                {field.required && <span style={{ color: 'var(--color-danger)', marginLeft: 2 }}>*</span>}
                              </FormLabel>
                              {field.fieldType === 'DROPDOWN' ? (
                                <Select
                                  value={fieldValues[field.id] || ''}
                                  onChange={(e) => setFieldValues({ ...fieldValues, [field.id]: e.target.value })}
                                >
                                  <option value="">Select...</option>
                                  {(field.dropdownOptions || []).map((opt) => (
                                    <option key={opt} value={opt}>{opt}</option>
                                  ))}
                                </Select>
                              ) : (
                                <Input
                                  value={fieldValues[field.id] || ''}
                                  onChange={(e) => setFieldValues({ ...fieldValues, [field.id]: e.target.value })}
                                  placeholder={field.helpText || `Enter ${field.displayName}`}
                                />
                              )}
                              {field.helpText && (
                                <p style={{ fontSize: '0.8125rem', color: 'var(--color-text-muted)', marginTop: '0.25rem' }}>
                                  {field.helpText}
                                </p>
                              )}
                            </FormGroup>
                          ))}
                        </div>
                      )}
                      {richTextFields.map((field) => (
                        <FormGroup key={field.id}>
                          <FormLabel>
                            {field.displayName}
                            {field.required && <span style={{ color: 'var(--color-danger)', marginLeft: 2 }}>*</span>}
                          </FormLabel>
                          <RichTextEditor
                            value={fieldValues[field.id] || ''}
                            onChange={(val) => setFieldValues({ ...fieldValues, [field.id]: val })}
                          />
                        </FormGroup>
                      ))}
                    </>
                  );
                })()}
              </div>
            )}

            <div className="modal-actions">
              <Button type="button" variant="secondary" onClick={() => setShowModal(false)}>
                Cancel
              </Button>
              <Button type="submit">Create</Button>
            </div>
          </form>
      </Modal>
    </Page>
  );
}

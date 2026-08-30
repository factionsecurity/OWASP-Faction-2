import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { organizationsApi, entityFieldsApi } from '../api';
import { usePageTitle } from '../context/PageTitleContext';
import type { UpdateOrganizationRequest, UserDefinedField } from '../types';
import type { AssignedUser } from '../types';
import RichTextEditor from '../components/RichTextEditor';
import Page from '../components/Page';
import SubOrganizationsPanel from '../components/SubOrganizationsPanel';
import {
  Button,
  FormGroup,
  FormLabel,
  Input,
  Select,
  Textarea,
  ErrorMessage,
} from '../components';
import './Organizations.css';

export default function OrganizationEdit() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [formData, setFormData] = useState({ name: '', description: '' });
  const [fieldDefinitions, setFieldDefinitions] = useState<UserDefinedField[]>([]);
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [assignedUsers, setAssignedUsers] = useState<AssignedUser[]>([]);
  const { setBreadcrumbs } = usePageTitle();

  useEffect(() => {
    setBreadcrumbs([
      { label: 'Organizations', to: '/organizations' },
      { label: formData.name || 'Organization' },
    ]);
    return () => setBreadcrumbs(null);
  }, [formData.name]);

  const currentUser = JSON.parse(localStorage.getItem('user') || '{}');
  const authorities: string[] = currentUser.authorities || [];
  const isSuperAdmin = authorities.includes('super_admin');
  const hasEditAll = authorities.includes('organizations:edit:all');
  const hasReadOwned = authorities.includes('organizations:read:owned');
  const canWrite =
    isSuperAdmin ||
    hasEditAll ||
    (hasReadOwned &&
      assignedUsers.some(
        (u) => u.userId === currentUser.id && u.accessLevel === 'WRITE'
      ));

  useEffect(() => {
    if (!id) return;

    organizationsApi
      .getById(id)
      .then((orgRes) => {
        if (orgRes.data) {
          setFormData({ name: orgRes.data.name, description: orgRes.data.description });
          setFieldValues(orgRes.data.fieldValues || {});
          setAssignedUsers(orgRes.data.assignedUsers || []);
        }
      })
      .catch((err: any) => {
        setError(err.response?.data?.message || 'Failed to load organization');
      })
      .finally(() => {
        setLoading(false);
      });

    entityFieldsApi
      .getConfig('ORGANIZATION')
      .then((fieldsRes) => {
        if (fieldsRes.data) {
          const sorted = [...(fieldsRes.data.fieldDefinitions || [])].sort(
            (a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0)
          );
          setFieldDefinitions(sorted);
        }
      })
      .catch(() => {
        // Field definitions are optional; ignore failures
      });
  }, [id]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!id) return;
    setSaving(true);
    setError('');
    try {
      const updateData: UpdateOrganizationRequest = {
        name: formData.name,
        description: formData.description,
        fieldValues: Object.keys(fieldValues).length > 0 ? fieldValues : undefined,
      };
      await organizationsApi.update(id, updateData);
      navigate('/organizations');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save organization');
      setSaving(false);
    }
  };

  if (loading) {
    return <div style={{ padding: '2rem', color: 'var(--color-text-muted)' }}>Loading...</div>;
  }

  const regularFields = fieldDefinitions.filter((f) => f.fieldType !== 'RICH_TEXT');
  const richTextFields = fieldDefinitions.filter((f) => f.fieldType === 'RICH_TEXT');

  return (
    <Page variant="narrow" className="organizations-page">
      <div className="app-edit-layout">
      <form onSubmit={handleSubmit} className="organization-form app-edit-main">
        {error && <ErrorMessage>{error}</ErrorMessage>}

        <div className="form-panel">
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
              disabled={!canWrite}
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
              disabled={!canWrite}
            />
          </FormGroup>
        </div>

        {fieldDefinitions.length > 0 && (
          <div className="form-panel">
            <h3 className="form-section-title">Additional Information</h3>
            {regularFields.length > 0 && (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem' }}>
                {regularFields.map((field) => (
                  <FormGroup key={field.id}>
                    <FormLabel>
                      {field.displayName}
                      {field.required && (
                        <span style={{ color: 'var(--color-danger)', marginLeft: 2 }}>*</span>
                      )}
                    </FormLabel>
                    {field.fieldType === 'DROPDOWN' ? (
                      <Select
                        value={fieldValues[field.id] || ''}
                        onChange={(e) =>
                          setFieldValues({ ...fieldValues, [field.id]: e.target.value })
                        }
                        disabled={!canWrite}
                      >
                        <option value="">Select...</option>
                        {(field.dropdownOptions || []).map((opt) => (
                          <option key={opt} value={opt}>
                            {opt}
                          </option>
                        ))}
                      </Select>
                    ) : (
                      <Input
                        value={fieldValues[field.id] || ''}
                        onChange={(e) =>
                          setFieldValues({ ...fieldValues, [field.id]: e.target.value })
                        }
                        placeholder={field.helpText || `Enter ${field.displayName}`}
                        disabled={!canWrite}
                      />
                    )}
                    {field.helpText && (
                      <p
                        style={{
                          fontSize: '0.8125rem',
                          color: 'var(--color-text-muted)',
                          marginTop: '0.25rem',
                        }}
                      >
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
                  {field.required && (
                    <span style={{ color: 'var(--color-danger)', marginLeft: 2 }}>*</span>
                  )}
                </FormLabel>
                <RichTextEditor
                  value={fieldValues[field.id] || ''}
                  onChange={(val) => setFieldValues({ ...fieldValues, [field.id]: val })}
                  disabled={!canWrite}
                />
              </FormGroup>
            ))}
          </div>
        )}

        {/* Divisions are managed independently of the organization form — each add, rename or
            delete is its own request, so they aren't part of this form's save. */}
        {id && <SubOrganizationsPanel organizationId={id} canWrite={canWrite} />}

        <div className="modal-actions">
          {canWrite && (
            <Button type="button" variant="secondary" onClick={() => navigate('/organizations')}>
              Cancel
            </Button>
          )}
          {canWrite && (
            <Button type="submit" disabled={saving}>
              {saving ? 'Saving...' : 'Update'}
            </Button>
          )}
        </div>
      </form>

      </div>
    </Page>
  );
}

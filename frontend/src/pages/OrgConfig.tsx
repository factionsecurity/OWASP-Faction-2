import { useState, useRef, useEffect, useCallback } from 'react';
import { Plus, X, Trash2, GripVertical } from 'lucide-react';
import { Button, IconButton, Input, Select, Toast } from '../components';
import ConfirmDialog from '../components/ConfirmDialog';
import Page from '../components/Page';
import { entityFieldsApi, regionConfigApi } from '../api';
import type { UserDefinedField, FieldType } from '../types';
import './ReportDesigner.css';
import './OrgConfig.css';
import './Applications.css';

type ScopeKey = 'ORGANIZATION' | 'APPLICATION';

interface PointerDrag {
  scope: ScopeKey;
  fieldIndex: number;
  cursorX: number;
  cursorY: number;
  fieldName: string;
  fieldType: FieldType;
}

interface ConfirmDeleteState {
  scope: ScopeKey;
  fieldId: string;
}

export default function OrgConfig() {
  const [orgFields, setOrgFields] = useState<UserDefinedField[]>([]);
  const [appFields, setAppFields] = useState<UserDefinedField[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [toastKey, setToastKey] = useState(0);
  const [showToast, setShowToast] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [regions, setRegions] = useState<string[]>([]);
  const [newRegion, setNewRegion] = useState('');
  const [confirmDelete, setConfirmDelete] = useState<ConfirmDeleteState | null>(null);
  const [pointerDrag, setPointerDrag] = useState<PointerDrag | null>(null);

  const orgFieldsRef = useRef<UserDefinedField[]>([]);
  const appFieldsRef = useRef<UserDefinedField[]>([]);
  const pointerDragRef = useRef<PointerDrag | null>(null);
  const orgTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const appTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const regionsTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const regionsLoadedRef = useRef(false);
  const reorderLockRef = useRef(false);
  const fieldRefs = useRef<Map<string, HTMLDivElement>>(new Map());

  // Keep refs in sync with state
  useEffect(() => { orgFieldsRef.current = orgFields; }, [orgFields]);
  useEffect(() => { appFieldsRef.current = appFields; }, [appFields]);

  useEffect(() => {
    if (!regionsLoadedRef.current) return;
    if (regionsTimeoutRef.current) clearTimeout(regionsTimeoutRef.current);
    regionsTimeoutRef.current = setTimeout(async () => {
      try {
        await regionConfigApi.updateRegions(regions);
        setToastKey(k => k + 1);
        setShowToast(true);
      } catch (err: any) {
        setError(err.response?.data?.message || 'Failed to save regions');
      }
    }, 800);
  }, [regions]);

  useEffect(() => {
    loadData();
    return () => {
      if (orgTimeoutRef.current) clearTimeout(orgTimeoutRef.current);
      if (appTimeoutRef.current) clearTimeout(appTimeoutRef.current);
      if (regionsTimeoutRef.current) clearTimeout(regionsTimeoutRef.current);
    };
  }, []);

  const loadData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [orgRes, appRes, regionsData] = await Promise.all([
        entityFieldsApi.getConfig('ORGANIZATION'),
        entityFieldsApi.getConfig('APPLICATION'),
        regionConfigApi.getRegions(),
      ]);
      setRegions(regionsData);
      regionsLoadedRef.current = true;
      if (orgRes.data) {
        const sorted = [...(orgRes.data.fieldDefinitions || [])].sort(
          (a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0)
        );
        setOrgFields(sorted);
      }
      if (appRes.data) {
        const sorted = [...(appRes.data.fieldDefinitions || [])].sort(
          (a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0)
        );
        setAppFields(sorted);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load field configurations');
    } finally {
      setLoading(false);
    }
  };

  const saveFields = useCallback((scope: ScopeKey, fields: UserDefinedField[], immediate = false) => {
    const timeoutRef = scope === 'ORGANIZATION' ? orgTimeoutRef : appTimeoutRef;
    if (timeoutRef.current) clearTimeout(timeoutRef.current);

    // Assign displayOrder based on array index before saving
    const fieldsWithOrder = fields.map((f, i) => ({ ...f, displayOrder: i }));

    const doSave = async () => {
      try {
        setSaving(true);
        await entityFieldsApi.updateConfig(scope, fieldsWithOrder);
        setToastKey(k => k + 1);
        setShowToast(true);
      } catch (err: any) {
        setError(err.response?.data?.message || 'Failed to save fields');
      } finally {
        setSaving(false);
      }
    };

    if (immediate) {
      doSave();
    } else {
      timeoutRef.current = setTimeout(doSave, 1000);
    }
  }, []);

  const addField = (scope: ScopeKey) => {
    const newField: UserDefinedField = {
      id: Date.now().toString(),
      variableName: 'new_field',
      displayName: 'New Field',
      fieldType: 'STRING',
    };
    if (scope === 'ORGANIZATION') {
      const updated = [...orgFieldsRef.current, newField];
      orgFieldsRef.current = updated;
      setOrgFields(updated);
      saveFields('ORGANIZATION', updated, true);
    } else {
      const updated = [...appFieldsRef.current, newField];
      appFieldsRef.current = updated;
      setAppFields(updated);
      saveFields('APPLICATION', updated, true);
    }
  };

  const updateField = (scope: ScopeKey, id: string, updates: Partial<UserDefinedField>) => {
    const currentFields = scope === 'ORGANIZATION' ? orgFieldsRef.current : appFieldsRef.current;
    const updated = currentFields.map(f => f.id === id ? { ...f, ...updates } : f);
    if (scope === 'ORGANIZATION') {
      orgFieldsRef.current = updated;
      if ('fieldType' in updates) setOrgFields(updated);
    } else {
      appFieldsRef.current = updated;
      if ('fieldType' in updates) setAppFields(updated);
    }
    saveFields(scope, updated, 'fieldType' in updates);
  };

  const handleDeleteField = (scope: ScopeKey, fieldId: string) => {
    setConfirmDelete({ scope, fieldId });
  };

  const handleConfirmDelete = () => {
    if (!confirmDelete) return;
    const { scope, fieldId } = confirmDelete;
    const currentFields = scope === 'ORGANIZATION' ? orgFieldsRef.current : appFieldsRef.current;
    const updated = currentFields.filter(f => f.id !== fieldId);
    if (scope === 'ORGANIZATION') {
      orgFieldsRef.current = updated;
      setOrgFields(updated);
    } else {
      appFieldsRef.current = updated;
      setAppFields(updated);
    }
    saveFields(scope, updated, true);
    setConfirmDelete(null);
  };

  const addDropdownOption = (scope: ScopeKey, fieldId: string, option: string) => {
    const currentFields = scope === 'ORGANIZATION' ? orgFieldsRef.current : appFieldsRef.current;
    const updated = currentFields.map(f =>
      f.id === fieldId
        ? { ...f, dropdownOptions: [...(f.dropdownOptions || []), option] }
        : f
    );
    if (scope === 'ORGANIZATION') {
      orgFieldsRef.current = updated;
      setOrgFields(updated);
    } else {
      appFieldsRef.current = updated;
      setAppFields(updated);
    }
    saveFields(scope, updated, true);
  };

  const removeDropdownOption = (scope: ScopeKey, fieldId: string, optionIndex: number) => {
    const currentFields = scope === 'ORGANIZATION' ? orgFieldsRef.current : appFieldsRef.current;
    const updated = currentFields.map(f =>
      f.id === fieldId
        ? { ...f, dropdownOptions: f.dropdownOptions?.filter((_, i) => i !== optionIndex) }
        : f
    );
    if (scope === 'ORGANIZATION') {
      orgFieldsRef.current = updated;
      setOrgFields(updated);
    } else {
      appFieldsRef.current = updated;
      setAppFields(updated);
    }
    saveFields(scope, updated, true);
  };

  const handlePointerDown = (
    e: React.PointerEvent<HTMLDivElement>,
    scope: ScopeKey,
    index: number,
    field: UserDefinedField,
  ) => {
    e.preventDefault();
    const drag: PointerDrag = {
      scope,
      fieldIndex: index,
      cursorX: e.clientX,
      cursorY: e.clientY,
      fieldName: field.displayName || 'New Field',
      fieldType: field.fieldType,
    };
    pointerDragRef.current = drag;
    reorderLockRef.current = false;
    setPointerDrag(drag);
  };

  const isDraggingField = !!pointerDrag;
  useEffect(() => {
    if (!isDraggingField) return;

    const onMove = (e: PointerEvent) => {
      const drag = pointerDragRef.current;
      if (!drag) return;

      const updatedDrag = { ...drag, cursorX: e.clientX, cursorY: e.clientY };
      pointerDragRef.current = updatedDrag;
      setPointerDrag(updatedDrag);

      if (reorderLockRef.current) return;

      const currentFields = drag.scope === 'ORGANIZATION' ? orgFieldsRef.current : appFieldsRef.current;

      for (const [fieldId, el] of fieldRefs.current.entries()) {
        const rect = el.getBoundingClientRect();
        if (e.clientY >= rect.top && e.clientY <= rect.bottom) {
          const targetIndex = currentFields.findIndex(f => f.id === fieldId);
          // -1 means field belongs to other scope — skip
          if (targetIndex === -1 || targetIndex === drag.fieldIndex) break;

          const fields = [...currentFields];
          const [draggedField] = fields.splice(drag.fieldIndex, 1);
          fields.splice(targetIndex, 0, draggedField);

          if (drag.scope === 'ORGANIZATION') {
            orgFieldsRef.current = fields;
            setOrgFields(fields);
          } else {
            appFieldsRef.current = fields;
            setAppFields(fields);
          }

          const newDrag = { ...updatedDrag, fieldIndex: targetIndex };
          pointerDragRef.current = newDrag;
          setPointerDrag(newDrag);

          reorderLockRef.current = true;
          requestAnimationFrame(() => requestAnimationFrame(() => {
            reorderLockRef.current = false;
          }));
          break;
        }
      }
    };

    const onUp = () => {
      const drag = pointerDragRef.current;
      if (drag) {
        const currentFields = drag.scope === 'ORGANIZATION' ? orgFieldsRef.current : appFieldsRef.current;
        saveFields(drag.scope, currentFields);
      }
      pointerDragRef.current = null;
      setPointerDrag(null);
    };

    document.addEventListener('pointermove', onMove);
    document.addEventListener('pointerup', onUp);
    document.addEventListener('pointercancel', onUp);
    return () => {
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      document.removeEventListener('pointercancel', onUp);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isDraggingField]);

  const handleAddRegion = () => {
    const trimmed = newRegion.trim();
    if (!trimmed || regions.includes(trimmed)) return;
    setRegions([...regions, trimmed]);
    setNewRegion('');
  };

  const handleRemoveRegion = (region: string) => {
    setRegions(regions.filter((r) => r !== region));
  };

  const renderFieldItem = (scope: ScopeKey, field: UserDefinedField, index: number) => (
    <div
      key={field.id || index}
      ref={(el) => {
        if (el) fieldRefs.current.set(field.id, el);
        else fieldRefs.current.delete(field.id);
      }}
      className={`rd-field ${pointerDrag?.scope === scope && pointerDrag?.fieldIndex === index ? 'rd-field--dragging' : ''}`}
    >
      <div className="rd-field-header">
        <div
          className="rd-drag-handle"
          onPointerDown={(e) => handlePointerDown(e, scope, index, field)}
        >
          <GripVertical size={16} />
        </div>
        <span className="rd-field-name">{field.displayName || 'New Field'}</span>
        <IconButton
          icon={Trash2}
          onClick={() => handleDeleteField(scope, field.id)}
          variant="delete"
          title="Remove field"
          disabled={saving}
        />
      </div>
      <div className="rd-field-body">
        <div className="rd-row">
          <div className="rd-label">Display Name</div>
          <div className="rd-value">
            <Input
              key={`display-${field.id}`}
              defaultValue={field.displayName}
              onChange={(e) => {
                const displayName = e.target.value;
                const variableName = displayName
                  .toLowerCase()
                  .replace(/\s+/g, '-')
                  .replace(/[^a-z0-9-]/g, '');
                updateField(scope, field.id, { displayName, variableName });
                const varInput = document.querySelector(`[data-field-var-id="${field.id}"]`) as HTMLInputElement | null;
                if (varInput) varInput.value = variableName;
              }}
              placeholder="Display Name"
            />
          </div>
        </div>
        <div className="rd-row">
          <div className="rd-label">Variable Name</div>
          <div className="rd-value">
            <Input
              key={`variable-${field.id}`}
              defaultValue={field.variableName}
              data-field-var-id={field.id}
              onChange={(e) => updateField(scope, field.id, { variableName: e.target.value })}
              placeholder="variable_name"
            />
          </div>
        </div>
        <div className="rd-row">
          <div className="rd-label">Field Type</div>
          <div className="rd-value">
            <Select
              value={field.fieldType}
              onChange={(e) => updateField(scope, field.id, { fieldType: e.target.value as FieldType })}
              disabled={saving}
            >
              <option value="STRING">String</option>
              <option value="DROPDOWN">Dropdown</option>
              <option value="RICH_TEXT">Rich Text</option>
            </Select>
          </div>
        </div>
        <div className="rd-row">
          <div className="rd-label">Help Text</div>
          <div className="rd-value">
            <Input
              key={`help-${field.id}`}
              defaultValue={field.helpText || ''}
              onChange={(e) => updateField(scope, field.id, { helpText: e.target.value })}
              placeholder="Optional help text shown below the field"
            />
          </div>
        </div>
        {field.fieldType === 'DROPDOWN' && (
          <div className="rd-row rd-row--top">
            <div className="rd-label">Options</div>
            <div className="rd-value">
              <div className="options-list">
                {field.dropdownOptions?.map((option, idx) => (
                  <div key={idx} className="option-item">
                    <span>{option}</span>
                    <IconButton
                      icon={X}
                      onClick={() => removeDropdownOption(scope, field.id, idx)}
                      variant="delete"
                      title="Remove option"
                      disabled={saving}
                    />
                  </div>
                ))}
              </div>
              <div className="add-option">
                <Input
                  placeholder="New option (press Enter to add)"
                  onKeyPress={(e) => {
                    if (e.key === 'Enter') {
                      const input = e.target as HTMLInputElement;
                      if (input.value.trim()) {
                        addDropdownOption(scope, field.id, input.value.trim());
                        input.value = '';
                      }
                    }
                  }}
                />
              </div>
            </div>
          </div>
        )}
        <div className="rd-row">
          <div className="rd-label">Required</div>
          <div className="rd-value">
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={field.required || false}
                onChange={(e) => updateField(scope, field.id, { required: e.target.checked })}
              />
              Required field
            </label>
          </div>
        </div>
      </div>
    </div>
  );

  if (loading) {
    return <div className="loading">Loading configuration...</div>;
  }

  return (
    <>
      <Page variant="narrow" className="org-config">
        {error && (
          <div className="error-banner">
            <span>{error}</span>
            <button onClick={() => setError(null)}><X size={16} /></button>
          </div>
        )}
        {saving && <div className="save-status">Saving...</div>}

        <div className="rd-section">
          <div className="rd-section-header">
            <span>Organization Fields</span>
            <Button onClick={() => addField('ORGANIZATION')} icon={Plus} size="sm" disabled={saving}>
              Add Field
            </Button>
          </div>
          <div className="rd-fields">
            {orgFields.map((field, index) => renderFieldItem('ORGANIZATION', field, index))}
            {orgFields.length === 0 && (
              <div className="empty-state">
                No organization fields yet. Click "Add Field" to create one.
              </div>
            )}
          </div>
        </div>

        <div className="rd-section">
          <div className="rd-section-header">
            <span>Application Fields</span>
            <Button onClick={() => addField('APPLICATION')} icon={Plus} size="sm" disabled={saving}>
              Add Field
            </Button>
          </div>
          <div className="rd-fields">
            {appFields.map((field, index) => renderFieldItem('APPLICATION', field, index))}
            {appFields.length === 0 && (
              <div className="empty-state">
                No application fields yet. Click "Add Field" to create one.
              </div>
            )}
          </div>
        </div>

        <div className="rd-section">
          <div className="rd-section-header">
            <span>Application Regions</span>
          </div>
          <div className="rd-fields" style={{ padding: '0.75rem' }}>
            {regions.length > 0 && (
              <div className="technology-tags">
                {regions.map((region, index) => (
                  <div key={index} className="technology-tag">
                    <input
                      defaultValue={region}
                      onBlur={(e) => {
                        const updated = [...regions];
                        updated[index] = e.target.value.trim() || region;
                        setRegions(updated);
                      }}
                      style={{
                        background: 'none',
                        border: 'none',
                        color: 'inherit',
                        font: 'inherit',
                        padding: 0,
                        width: `${Math.max(region.length, 4)}ch`,
                        outline: 'none',
                      }}
                    />
                    <button type="button" onClick={() => handleRemoveRegion(region)}>
                      <X size={14} />
                    </button>
                  </div>
                ))}
              </div>
            )}
            {regions.length === 0 && (
              <div className="empty-state">No regions configured.</div>
            )}
            <div className="technology-input-group" style={{ marginTop: '0.75rem' }}>
              <Input
                placeholder="New region name"
                value={newRegion}
                onChange={(e) => setNewRegion(e.target.value)}
                onKeyPress={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    handleAddRegion();
                  }
                }}
              />
              <Button type="button" onClick={handleAddRegion} icon={Plus} size="sm">
                Add
              </Button>
            </div>
          </div>
        </div>
      </Page>

      {showToast && (
        <Toast key={toastKey} message="Configuration saved" variant="success" onDone={() => setShowToast(false)} />
      )}

      {pointerDrag && (
        <div
          className="rd-drag-overlay"
          style={{ left: pointerDrag.cursorX + 16, top: pointerDrag.cursorY - 16 }}
        >
          <GripVertical size={14} />
          <span>{pointerDrag.fieldName}</span>
          <span className="rd-drag-overlay-type">{pointerDrag.fieldType}</span>
        </div>
      )}

      <ConfirmDialog
        isOpen={!!confirmDelete}
        onClose={() => setConfirmDelete(null)}
        onConfirm={handleConfirmDelete}
        title="Remove Field"
        message="Are you sure you want to remove this field? All stored values for this field will be removed from existing records."
        confirmText="Remove"
        variant="danger"
      />
    </>
  );
}

import { useState, useRef, useEffect } from 'react';
import { Plus, X, Trash2, GripVertical } from 'lucide-react';
import { Button, IconButton, Input, Select } from '../components';
import ConfirmDialog from './ConfirmDialog';
import type { UserDefinedField, FieldType } from '../types';

interface PointerDrag {
  fieldIndex: number;
  cursorX: number;
  cursorY: number;
  fieldName: string;
  fieldType: FieldType;
}

export interface CustomFieldListEditorProps {
  title: string;
  description?: string;
  fields: UserDefinedField[];
  /** immediate=false for keystrokes (the caller debounces), true for add/remove/reorder/type changes. */
  onChange(fields: UserDefinedField[], immediate: boolean): void;
  disabled?: boolean;
  /** Shown when the list is empty. */
  emptyText?: string;
}

/**
 * Edits one list of custom field definitions: add, remove, reorder, rename, retype, dropdown
 * options. It owns the drag state and the remove-confirmation; it never saves. The caller
 * decides when and where the list is persisted.
 */
export default function CustomFieldListEditor({
  title,
  description,
  fields,
  onChange,
  disabled = false,
  emptyText = 'No fields yet. Click "Add Field" to create one.',
}: CustomFieldListEditorProps) {
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [pointerDrag, setPointerDrag] = useState<PointerDrag | null>(null);

  // Keystrokes do not re-render, so the latest list lives in a ref the handlers share.
  const fieldsRef = useRef<UserDefinedField[]>(fields);
  const pointerDragRef = useRef<PointerDrag | null>(null);
  const reorderLockRef = useRef(false);
  const fieldRefs = useRef<Map<string, HTMLDivElement>>(new Map());
  const onChangeRef = useRef(onChange);

  useEffect(() => { fieldsRef.current = fields; }, [fields]);
  useEffect(() => { onChangeRef.current = onChange; }, [onChange]);

  const emit = (updated: UserDefinedField[], immediate: boolean) => {
    fieldsRef.current = updated;
    onChangeRef.current(updated, immediate);
  };

  const addField = () => {
    const newField: UserDefinedField = {
      id: Date.now().toString(),
      variableName: 'new_field',
      displayName: 'New Field',
      fieldType: 'STRING',
    };
    emit([...fieldsRef.current, newField], true);
  };

  const updateField = (id: string, updates: Partial<UserDefinedField>) => {
    const updated = fieldsRef.current.map(f => f.id === id ? { ...f, ...updates } : f);
    emit(updated, 'fieldType' in updates);
  };

  const handleConfirmDelete = () => {
    if (!confirmDeleteId) return;
    emit(fieldsRef.current.filter(f => f.id !== confirmDeleteId), true);
    setConfirmDeleteId(null);
  };

  const addDropdownOption = (fieldId: string, option: string) => {
    emit(fieldsRef.current.map(f =>
      f.id === fieldId ? { ...f, dropdownOptions: [...(f.dropdownOptions || []), option] } : f
    ), true);
  };

  const removeDropdownOption = (fieldId: string, optionIndex: number) => {
    emit(fieldsRef.current.map(f =>
      f.id === fieldId ? { ...f, dropdownOptions: f.dropdownOptions?.filter((_, i) => i !== optionIndex) } : f
    ), true);
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>, index: number, field: UserDefinedField) => {
    e.preventDefault();
    const drag: PointerDrag = {
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

      const currentFields = fieldsRef.current;
      for (const [fieldId, el] of fieldRefs.current.entries()) {
        const rect = el.getBoundingClientRect();
        if (e.clientY >= rect.top && e.clientY <= rect.bottom) {
          const targetIndex = currentFields.findIndex(f => f.id === fieldId);
          if (targetIndex === -1 || targetIndex === drag.fieldIndex) break;

          const reordered = [...currentFields];
          const [draggedField] = reordered.splice(drag.fieldIndex, 1);
          reordered.splice(targetIndex, 0, draggedField);
          // The caller debounces, so a drag across several rows saves once it settles.
          emit(reordered, false);

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
      if (pointerDragRef.current) emit(fieldsRef.current, false);
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

  const renderFieldItem = (field: UserDefinedField, index: number) => (
    <div
      key={field.id || index}
      ref={(el) => {
        if (el) fieldRefs.current.set(field.id, el);
        else fieldRefs.current.delete(field.id);
      }}
      className={`rd-field ${pointerDrag?.fieldIndex === index ? 'rd-field--dragging' : ''}`}
    >
      <div className="rd-field-header">
        <div
          className="rd-drag-handle"
          onPointerDown={(e) => handlePointerDown(e, index, field)}
        >
          <GripVertical size={16} />
        </div>
        <span className="rd-field-name">{field.displayName || 'New Field'}</span>
        <IconButton
          icon={Trash2}
          onClick={() => setConfirmDeleteId(field.id)}
          variant="delete"
          title="Remove field"
          disabled={disabled}
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
                updateField(field.id, { displayName, variableName });
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
              onChange={(e) => updateField(field.id, { variableName: e.target.value })}
              placeholder="variable_name"
            />
          </div>
        </div>
        <div className="rd-row">
          <div className="rd-label">Field Type</div>
          <div className="rd-value">
            <Select
              value={field.fieldType}
              onChange={(e) => updateField(field.id, { fieldType: e.target.value as FieldType })}
              disabled={disabled}
            >
              <option value="STRING">String</option>
              <option value="DROPDOWN">Dropdown</option>
              <option value="RICH_TEXT">Rich Text</option>
              <option value="HYPERLINK">Hyperlink</option>
            </Select>
          </div>
        </div>
        <div className="rd-row">
          <div className="rd-label">Help Text</div>
          <div className="rd-value">
            <Input
              key={`help-${field.id}`}
              defaultValue={field.helpText || ''}
              onChange={(e) => updateField(field.id, { helpText: e.target.value })}
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
                      onClick={() => removeDropdownOption(field.id, idx)}
                      variant="delete"
                      title="Remove option"
                      disabled={disabled}
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
                        addDropdownOption(field.id, input.value.trim());
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
                onChange={(e) => updateField(field.id, { required: e.target.checked })}
              />
              Required field
            </label>
          </div>
        </div>
      </div>
    </div>
  );

  return (
    <>
      <div className="rd-section">
        <div className="rd-section-header">
          <span>{title}</span>
          <Button onClick={addField} icon={Plus} size="sm" disabled={disabled}>
            Add Field
          </Button>
        </div>
        <div className="rd-fields">
          {description && <p className="orgconfig-terminology-hint">{description}</p>}
          {fields.map((field, index) => renderFieldItem(field, index))}
          {fields.length === 0 && (
            <div className="empty-state">
              {emptyText}
            </div>
          )}
        </div>
      </div>

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
        isOpen={!!confirmDeleteId}
        onClose={() => setConfirmDeleteId(null)}
        onConfirm={handleConfirmDelete}
        title="Remove Field"
        message="Are you sure you want to remove this field? All stored values for this field will be removed from existing records."
        confirmText="Remove"
        variant="danger"
      />
    </>
  );
}

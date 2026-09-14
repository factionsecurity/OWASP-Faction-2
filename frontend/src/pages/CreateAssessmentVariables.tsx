import type { UserDefinedField } from '../types';
import { FormLabel, Input, Select, RichTextEditor } from '../components';

interface CreateAssessmentVariablesProps {
  /** The assessment-scoped fields to show, in any order. */
  fields: UserDefinedField[];
  /** Values keyed by variable name. */
  values: Record<string, string>;
  onChange: (variableName: string, value: string) => void;
  /** Omitted while creating: an inline image belongs to an assessment that doesn't exist yet. */
  onImageUpload?: (file: File) => Promise<string>;
  /** Shown instead of the fields when they can't be edited yet. */
  hint?: string;
}

const byDisplayOrder = (a: UserDefinedField, b: UserDefinedField) =>
  (a.displayOrder ?? 0) - (b.displayOrder ?? 0);

/**
 * The report template's assessment variables, on the scheduling form.
 *
 * <p>String and Dropdown fields share one compact grid; each Rich Text field gets a full-width
 * editor. Deliberately simpler than the Variables tab on the assessment page, which also has to
 * show who is editing what — nobody else can be editing an assessment that is being scheduled.
 */
export default function CreateAssessmentVariables({
  fields,
  values,
  onChange,
  onImageUpload,
  hint,
}: CreateAssessmentVariablesProps) {
  const simpleFields = fields.filter((f) => f.fieldType !== 'RICH_TEXT').sort(byDisplayOrder);
  const richTextFields = fields.filter((f) => f.fieldType === 'RICH_TEXT').sort(byDisplayOrder);

  return (
    <div className="form-section">
      <h5 className="section-title">Variables</h5>
      {hint ? (
        <small className="text-muted d-block">{hint}</small>
      ) : (
        <>
          {simpleFields.length > 0 && (
            <div className="row g-3">
              {simpleFields.map((field) => (
                <div key={field.variableName} className="col-md-4">
                  <div className="form-group">
                    <FormLabel required={!!field.required}>{field.displayName}</FormLabel>
                    {field.fieldType === 'DROPDOWN' ? (
                      <Select
                        value={values[field.variableName] || ''}
                        onChange={(e) => onChange(field.variableName, e.target.value)}
                      >
                        <option value="">Select...</option>
                        {field.dropdownOptions?.map((opt) => (
                          <option key={opt} value={opt}>{opt}</option>
                        ))}
                      </Select>
                    ) : (
                      <Input
                        type="text"
                        value={values[field.variableName] || ''}
                        onChange={(e) => onChange(field.variableName, e.target.value)}
                        placeholder={field.helpText || `Enter ${field.displayName}`}
                      />
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
          {richTextFields.map((field) => (
            <div key={field.variableName} className="form-group mt-3">
              <FormLabel required={!!field.required}>{field.displayName}</FormLabel>
              <div style={{ minHeight: '200px' }}>
                <RichTextEditor
                  value={values[field.variableName] || ''}
                  onChange={(html) => onChange(field.variableName, html)}
                  onImageUpload={onImageUpload}
                  placeholder={field.helpText}
                />
              </div>
            </div>
          ))}
        </>
      )}
    </div>
  );
}

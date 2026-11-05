import { useState, useRef, useEffect, useCallback } from 'react';
import { Plus, X } from 'lucide-react';
import { Button, Input, Toast } from '../components';
import CustomFieldListEditor from '../components/CustomFieldListEditor';
import Page from '../components/Page';
import { entityFieldsApi, regionConfigApi, terminologyApi } from '../api';
import type { UserDefinedField, TerminologyConfig } from '../types';
import { useTerminology } from '../context/TerminologyContext';
import './ReportDesigner.css';
import './OrgConfig.css';
import './Applications.css';

type ScopeKey = 'ORGANIZATION' | 'APPLICATION';

export default function OrgConfig() {
  const { organizationSingular } = useTerminology();
  const [orgFields, setOrgFields] = useState<UserDefinedField[]>([]);
  const [appFields, setAppFields] = useState<UserDefinedField[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [toastKey, setToastKey] = useState(0);
  const [showToast, setShowToast] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [regions, setRegions] = useState<string[]>([]);
  const [terminology, setTerminology] = useState<TerminologyConfig | null>(null);
  // So a rename takes effect across the interface the moment it is saved, rather than at the
  // next full page load.
  const { refresh: refreshTerminology } = useTerminology();
  const [newRegion, setNewRegion] = useState('');

  const orgFieldsRef = useRef<UserDefinedField[]>([]);
  const appFieldsRef = useRef<UserDefinedField[]>([]);
  const orgTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const appTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const regionsTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const regionsLoadedRef = useRef(false);
  const terminologyTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  /** What the server last confirmed, so an unchanged form does not save itself back. */
  const savedTerminologyRef = useRef<string | null>(null);

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

  // Terminology saves on its own debounce. It was originally folded into the regions effect
  // above, which meant it only ever saved when a region also happened to change — editing a
  // label alone did nothing at all.
  useEffect(() => {
    if (!terminology) return;
    // The effect also runs on the render that follows loading, so without this the page would
    // save what it had just fetched — a pointless write and a toast on every visit.
    if (JSON.stringify(terminology) === savedTerminologyRef.current) return;
    if (terminologyTimeoutRef.current) clearTimeout(terminologyTimeoutRef.current);
    terminologyTimeoutRef.current = setTimeout(async () => {
      try {
        const res = await terminologyApi.updateConfig(terminology);
        // The server trims and rejects blanks, so take back what it actually stored rather than
        // leaving the form showing something that was not saved.
        if (res.data) {
          savedTerminologyRef.current = JSON.stringify(res.data);
          setTerminology(res.data);
        }
        await refreshTerminology();
        setToastKey(k => k + 1);
        setShowToast(true);
      } catch (err: any) {
        setError(err.response?.data?.message || 'Failed to save terminology');
      }
    }, 800);
  }, [terminology]);

  useEffect(() => {
    loadData();
    return () => {
      if (orgTimeoutRef.current) clearTimeout(orgTimeoutRef.current);
      if (appTimeoutRef.current) clearTimeout(appTimeoutRef.current);
      if (regionsTimeoutRef.current) clearTimeout(regionsTimeoutRef.current);
      if (terminologyTimeoutRef.current) clearTimeout(terminologyTimeoutRef.current);
    };
  }, []);

  const loadData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [orgRes, appRes, regionsData, terminologyRes] = await Promise.all([
        entityFieldsApi.getConfig('ORGANIZATION'),
        entityFieldsApi.getConfig('APPLICATION'),
        regionConfigApi.getRegions(),
        terminologyApi.getConfig(),
      ]);
      setRegions(regionsData);
      if (terminologyRes.data) {
        savedTerminologyRef.current = JSON.stringify(terminologyRes.data);
        setTerminology(terminologyRes.data);
      }
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


  const handleFieldsChange = (scope: ScopeKey, fields: UserDefinedField[], immediate: boolean) => {
    if (scope === 'ORGANIZATION') {
      orgFieldsRef.current = fields;
      setOrgFields(fields);
    } else {
      appFieldsRef.current = fields;
      setAppFields(fields);
    }
    saveFields(scope, fields, immediate);
  };

  const handleAddRegion = () => {
    const trimmed = newRegion.trim();
    if (!trimmed || regions.includes(trimmed)) return;
    setRegions([...regions, trimmed]);
    setNewRegion('');
  };

  const handleRemoveRegion = (region: string) => {
    setRegions(regions.filter((r) => r !== region));
  };

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

        <CustomFieldListEditor
          title={`${organizationSingular} Fields`}
          fields={orgFields}
          onChange={(fields, immediate) => handleFieldsChange('ORGANIZATION', fields, immediate)}
          disabled={saving}
          emptyText='No organization fields yet. Click "Add Field" to create one.'
        />

        <CustomFieldListEditor
          title="Application Fields"
          fields={appFields}
          onChange={(fields, immediate) => handleFieldsChange('APPLICATION', fields, immediate)}
          disabled={saving}
          emptyText='No application fields yet. Click "Add Field" to create one.'
        />

        {terminology && (
          <div className="rd-section">
            <div className="rd-section-header">
              <span>Terminology</span>
            </div>
            <div className="rd-fields orgconfig-terminology">
              <p className="orgconfig-terminology-hint">
                What this installation calls these things. Wording only — nothing about how they
                behave changes, and existing data is untouched.
              </p>
              {([
                ['organizationSingular', 'Organization (singular)', 'Value Stream'],
                ['organizationPlural', 'Organizations (plural)', 'Value Streams'],
                ['subOrganizationSingular', 'Sub-organization (singular)', 'Sub-value Stream'],
                ['subOrganizationPlural', 'Sub-organizations (plural)', 'Sub-value Streams'],
              ] as [keyof TerminologyConfig, string, string][]).map(([key, label, example]) => (
                <div key={key} className="orgconfig-terminology-row">
                  <label htmlFor={`terminology-${key}`}>{label}</label>
                  <Input
                    id={`terminology-${key}`}
                    value={terminology[key]}
                    placeholder={example}
                    onChange={(e) => setTerminology({ ...terminology, [key]: e.target.value })}
                  />
                </div>
              ))}
              {/* Both forms are asked for rather than derived: appending "s" gets "Value Streams"
                  right and "Entitys" wrong. */}
              <p className="orgconfig-terminology-hint">
                The plural is asked for separately because it cannot be derived reliably — "Entity"
                becomes "Entities", not "Entitys".
              </p>

              {/* Severity names are the other half of this record, but they are configured in
                  Assessment Config > Severity Names — a severity is a property of the findings an
                  assessment produces, not of the organization the work was done for. Both pages
                  PUT the whole config, so neither can clobber the other's half. */}
            </div>
          </div>
        )}

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
    </>
  );
}

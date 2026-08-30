import './FormControls.css';

interface FormGroupProps {
  children: React.ReactNode;
  className?: string;
}

export function FormGroup({ children, className = '' }: FormGroupProps) {
  return <div className={`form-group ${className}`}>{children}</div>;
}

interface FormLabelProps {
  children: React.ReactNode;
  required?: boolean;
  htmlFor?: string;
}

export function FormLabel({ children, required = false, htmlFor }: FormLabelProps) {
  return (
    <label className="form-label" htmlFor={htmlFor}>
      {children}
      {required && <span className="form-required">*</span>}
    </label>
  );
}

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  error?: string;
}

export function Input({ error, className = '', ...props }: InputProps) {
  return (
    <>
      <input className={`form-input ${error ? 'form-input-error' : ''} ${className}`} {...props} />
      {error && <span className="form-error">{error}</span>}
    </>
  );
}

interface TextareaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  error?: string;
}

export function Textarea({ error, className = '', ...props }: TextareaProps) {
  return (
    <>
      <textarea className={`form-input form-textarea ${error ? 'form-input-error' : ''} ${className}`} {...props} />
      {error && <span className="form-error">{error}</span>}
    </>
  );
}

interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  children: React.ReactNode;
  error?: string;
}

export function Select({ children, error, className = '', ...props }: SelectProps) {
  return (
    <>
      <select className={`form-input form-select ${error ? 'form-input-error' : ''} ${className}`} {...props}>
        {children}
      </select>
      {error && <span className="form-error">{error}</span>}
    </>
  );
}

interface CheckboxProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: string;
  error?: string;
}

export function Checkbox({ label, error, className = '', id, ...props }: CheckboxProps) {
  const checkboxId = id || `checkbox-${Math.random().toString(36).substr(2, 9)}`;

  return (
    <div className="form-group">
      <label className="form-checkbox-label" htmlFor={checkboxId}>
        <input
          type="checkbox"
          id={checkboxId}
          className={`form-checkbox ${className}`}
          {...props}
        />
        <span className="form-checkbox-text">{label}</span>
      </label>
      {error && <span className="form-error">{error}</span>}
    </div>
  );
}

interface FormRowProps {
  children: React.ReactNode;
  columns?: 1 | 2 | 3 | 4;
}

export function FormRow({ children, columns = 2 }: FormRowProps) {
  return <div className={`form-row form-row-${columns}`}>{children}</div>;
}

interface FormHintProps {
  children: React.ReactNode;
}

export function FormHint({ children }: FormHintProps) {
  return <p className="form-hint">{children}</p>;
}

interface ErrorMessageProps {
  children: React.ReactNode;
}

export function ErrorMessage({ children }: ErrorMessageProps) {
  return <div className="error-message">{children}</div>;
}

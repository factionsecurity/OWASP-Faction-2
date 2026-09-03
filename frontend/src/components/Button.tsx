import { LucideIcon } from 'lucide-react';
import './Button.css';

interface ButtonProps {
  children?: React.ReactNode;
  onClick?: (e: React.MouseEvent<HTMLButtonElement>) => void;
  variant?: 'primary' | 'secondary' | 'danger' | 'warning' | 'success';
  size?: 'sm' | 'md' | 'lg';
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  icon?: LucideIcon;
  className?: string;
  /**
   * Native tooltip. Mainly for explaining a disabled button — a control that is inert
   * with no stated reason reads as broken rather than as blocked.
   */
  title?: string;
}

export function Button({
  children,
  onClick,
  variant = 'primary',
  size = 'md',
  disabled = false,
  type = 'button',
  icon: Icon,
  className = '',
  title,
}: ButtonProps) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={`btn btn-${variant} btn-${size} ${className}`.trim()}
    >
      {Icon && <Icon size={18} />}
      {children}
    </button>
  );
}

interface IconButtonProps {
  onClick?: (e: React.MouseEvent<HTMLButtonElement>) => void;
  icon: LucideIcon;
  variant?: 'default' | 'edit' | 'delete' | 'warning' | 'success' | 'info';
  title?: string;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  /**
   * Extra class for the rare button that must carry colour at rest. The `variant` styles are
   * hover-only on purpose — a row of action icons stays quiet until you reach for one — so a
   * button that has to advertise a row's state needs its own rule.
   */
  className?: string;
}

export function IconButton({
  onClick,
  icon: Icon,
  variant = 'default',
  title,
  disabled = false,
  type = 'button',
  className = '',
}: IconButtonProps) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={`btn-icon btn-icon-${variant}${className ? ` ${className}` : ''}`}
    >
      <Icon size={16} />
    </button>
  );
}

interface ActionButtonsProps {
  children: React.ReactNode;
}

export function ActionButtons({ children }: ActionButtonsProps) {
  // Action clicks must not bubble to the row's onRowClick navigation
  return <div className="action-buttons" onClick={(e) => e.stopPropagation()}>{children}</div>;
}

/**
 * ConfirmDialog Component
 *
 * A reusable confirmation dialog for user actions that need confirmation.
 *
 * Usage:
 * ```tsx
 * const [showConfirm, setShowConfirm] = useState(false);
 *
 * <ConfirmDialog
 *   isOpen={showConfirm}
 *   onClose={() => setShowConfirm(false)}
 *   onConfirm={() => {
 *     // handle confirmation
 *     setShowConfirm(false);
 *   }}
 *   title="Confirm Action"
 *   message="Are you sure you want to proceed?"
 *   confirmText="Yes, proceed"
 *   cancelText="Cancel"
 *   variant="danger" // or "warning", "info"
 * />
 * ```
 */
import Modal from './Modal';
import { Button } from './Button';
import { AlertTriangle, Info, AlertCircle } from 'lucide-react';

export interface ConfirmDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  variant?: 'danger' | 'warning' | 'info';
  isLoading?: boolean;
}

export default function ConfirmDialog({
  isOpen,
  onClose,
  onConfirm,
  title,
  message,
  confirmText = 'Confirm',
  cancelText = 'Cancel',
  variant = 'warning',
  isLoading = false,
}: ConfirmDialogProps) {
  const getIcon = () => {
    switch (variant) {
      case 'danger':
        return <AlertCircle size={48} className="text-danger" />;
      case 'warning':
        return <AlertTriangle size={48} className="text-warning" />;
      case 'info':
        return <Info size={48} className="text-info" />;
      default:
        return <AlertTriangle size={48} className="text-warning" />;
    }
  };

  const getConfirmButtonVariant = () => {
    switch (variant) {
      case 'danger':
        return 'danger';
      case 'warning':
        return 'warning';
      case 'info':
        return 'primary';
      default:
        return 'warning';
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={title}
      size="sm"
      closeOnOverlayClick={!isLoading}
      footer={
        <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end', width: '100%' }}>
          <Button variant="secondary" onClick={onClose} disabled={isLoading}>
            {cancelText}
          </Button>
          <Button variant={getConfirmButtonVariant()} onClick={onConfirm} disabled={isLoading}>
            {isLoading ? 'Processing...' : confirmText}
          </Button>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1rem', padding: '1rem 0' }}>
        {getIcon()}
        <p style={{ textAlign: 'center', margin: 0, color: 'var(--text-secondary)' }}>
          {message}
        </p>
      </div>
    </Modal>
  );
}

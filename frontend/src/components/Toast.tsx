import './Toast.css';

interface ToastProps {
  message: string;
  onDone: () => void;
  variant?: 'success' | 'warning' | 'danger';
}

export default function Toast({ message, onDone, variant = 'success' }: ToastProps) {
  return (
    <div className={`toast toast-${variant}`} onAnimationEnd={onDone}>
      {message}
    </div>
  );
}
